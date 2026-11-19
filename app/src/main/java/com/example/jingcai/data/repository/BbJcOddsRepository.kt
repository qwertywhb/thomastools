package com.example.jingcai.data.repository

import android.content.Context
import com.example.jingcai.data.model.BbJcOdds
import com.example.jingcai.data.model.BbOddSide
import com.example.jingcai.data.network.ApiClient
import com.example.jingcai.data.network.BbJcOddsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 竞彩篮球赔率（比分直播列表用）。
 *
 * 数据源与「模拟投注」页同一个接口（`getMatchCalculatorV1`），**一次请求拿到当天全部场次**，
 * 所以列表页不必逐场拉：请求一次、按竞彩编号建表，页面直接查表。
 *
 * ## 🔴 为什么必须落盘存档
 * 接口没有日期参数（试过 `matchDate` / `date` / `businessDate`，全部被忽略），
 * 永远只回**当天在售**的场次：比赛一结束（或过了业务日）就查不到赔率了。
 * 而用户看比分列表时最常看的就是「刚打完那场当时开的什么盘」——
 * 所以每次抓到的赔率按竞彩编号存下来，后续接口不再返回时沿用存档。
 * 只存最近 [KEEP_MS]（5 天），避免无限涨。
 *
 * 内存再缓存 60 秒：列表 6 秒 tick 只改比分、不碰赔率；
 * 5 分钟兜底重建 / 下拉刷新时才重新拉。
 */
class BbJcOddsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val url =
        "https://webapi.sporttery.cn/gateway/jc/basketball/getMatchCalculatorV1.qry" +
            "?poolCode=mnl,hilo,hdc&channel=c"
    private val referer = "https://www.sporttery.cn/"

    @Volatile private var cached: Map<String, BbJcOdds> = emptyMap()
    @Volatile private var cachedAt: Long = 0L

    /** 首屏用：先把存档的赔率拿出来，接口回来之前不至于一片空白。 */
    fun snapshot(): Map<String, BbJcOdds> {
        if (cached.isNotEmpty()) return cached
        val map = loadStored()
        cached = map
        return map
    }

    /** @param force 忽略 60 秒内存缓存（下拉刷新 / 5 分钟重建时用）。 */
    suspend fun getOdds(force: Boolean = false): Map<String, BbJcOdds> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && cached.isNotEmpty() && now - cachedAt < 60_000L) return@withContext cached

        val root = JSONObject(ApiClient.get(url, referer))
        if (!root.optBoolean("success", true)) {
            throw IllegalStateException(root.optString("errorMessage").ifEmpty { "赔率接口返回失败" })
        }
        val fetched = BbJcOddsParser.parse(root)

        // 合并：新抓到的覆盖存档，存档里接口已不再返回的（停售/完赛）继续保留
        val merged = LinkedHashMap<String, BbJcOdds>()
        val storedAt = loadStoredAt()
        val cutoff = now - KEEP_MS
        for ((num, o) in loadStored()) {
            if ((storedAt[num] ?: 0L) >= cutoff) merged[num] = o
        }
        merged.putAll(fetched)
        val stamps = HashMap<String, Long>(storedAt)
        for (num in fetched.keys) stamps[num] = now

        save(merged, stamps.filterKeys { merged.containsKey(it) })
        cached = merged
        cachedAt = now
        merged
    }

    // ===== 存档（JSON：编号 → 紧凑字段） =====

    private fun loadStored(): Map<String, BbJcOdds> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, BbJcOdds>()
        for (num in obj.keys()) {
            val o = obj.optJSONObject(num) ?: continue
            runCatching { fromJson(num, o) }.getOrNull()?.let { out[num] = it }
        }
        return out
    }

    private fun loadStoredAt(): Map<String, Long> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return obj.keys().asSequence()
            .mapNotNull { num -> obj.optJSONObject(num)?.optLong("t", 0L)?.let { num to it } }
            .toMap()
    }

    private fun save(map: Map<String, BbJcOdds>, stamps: Map<String, Long>) {
        runCatching {
            val root = JSONObject()
            for ((num, o) in map) root.put(num, toJson(o, stamps[num] ?: 0L))
            prefs.edit().putString(KEY, root.toString()).apply()
        }
    }

    private fun toJson(o: BbJcOdds, ts: Long): JSONObject = JSONObject().apply {
        put("h", o.homeTeam)
        put("a", o.awayTeam)
        put("hl", o.hdcLine)
        put("hh", pack(o.hdcHome))
        put("ha", pack(o.hdcAway))
        put("tl", o.hiloLine)
        put("to", pack(o.hiloOver))
        put("tu", pack(o.hiloUnder))
        put("mh", pack(o.mnlHome))
        put("ma", pack(o.mnlAway))
        put("t", ts)
    }

    private fun fromJson(num: String, o: JSONObject): BbJcOdds = BbJcOdds(
        num = num,
        homeTeam = o.optString("h"),
        awayTeam = o.optString("a"),
        hdcLine = o.optString("hl"),
        hdcHome = unpack(o.optJSONArray("hh")),
        hdcAway = unpack(o.optJSONArray("ha")),
        hiloLine = o.optString("tl"),
        hiloOver = unpack(o.optJSONArray("to")),
        hiloUnder = unpack(o.optJSONArray("tu")),
        mnlHome = unpack(o.optJSONArray("mh")),
        mnlAway = unpack(o.optJSONArray("ma"))
    )

    /** `[赔率, 涨跌]` 两元素数组，比嵌套对象省一半字符。 */
    private fun pack(s: BbOddSide): JSONArray = JSONArray().put(s.odds).put(s.trend)

    private fun unpack(a: JSONArray?): BbOddSide =
        if (a == null) BbOddSide(0.0) else BbOddSide(a.optDouble(0, 0.0), a.optInt(1, 0))

    private companion object {
        const val PREFS = "bb_jc_odds"
        const val KEY = "store"
        const val KEEP_MS = 5L * 24 * 3600 * 1000
    }
}
