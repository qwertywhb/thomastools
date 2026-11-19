package com.example.jingcai.data.repository

import com.example.jingcai.data.model.JcSpfChange
import com.example.jingcai.data.model.RawLive
import com.example.jingcai.data.network.ApiClient
import com.example.jingcai.data.network.TitanLiveParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * 「比分直播」数据源：titan007 三个 GET（均需 Referer）。
 *
 *  1. 全量比赛 `livestatic.titan007.com/vbsxml/bfdata_ut.js?<ts>`（UTF-8，~98KB，每 ~10s 更新）
 *     —— 覆盖全部联赛，**「完整」tab 的真值来源**；同时用响应头 `Date` 校正设备时钟；
 *  2. 竞彩编号 `cp.titan007.com/buy/JingCai.aspx`（UTF-8）—— 得到 titanId -> 竞彩编号，用于「竞彩」过滤 + 大小球关联；
 *     🔴 **必须多期合并**：默认页只渲染「今天这一期」，昨天已完赛的竞彩场次一个都不在里面，
 *     要靠 `?typeID=101&oddstype=2&date=<yyyy-m-d>` 逐日补（见 [loadHistJc]）；
 *  3. 大小球 `cp.titan007.com/data/dx101.xml?<ts>`（UTF-8）—— 仅竞彩场次有，按竞彩编号关联；
 *  4. 大小球 `livestatic.titan007.com/vbsxml/goalbf3.xml?r=007<ts>`（**全量**，157/288 场有盘，按 titanId 关联）
 *     + `ch_goalbf3.xml`（变动增量，参考站每 4 秒轮询）—— 使「完整/热门」tab 也有大小球；
 *  5. 比分增量 `cp.titan007.com/info/match/data/change.xml?<ts>`（UTF-8）—— 5 秒 tick 用，
 *     **覆盖全部进行中比赛（竞彩 + 非竞彩）**。
 *  6. 官方补时 `m.titan007.com/phone/livechange.txt?<ts>`（UTF-8，移动站 `zqlivechange` 轮询流）—— 5 秒 tick
 *     用，**唯一**正确的举牌补时来源（字段 [21]=injuryTime，未宣布即空串）。见 [TitanLiveParser.parseLiveChange]。
 *
 * 设计要点：**绝不在 5 秒 tick 里整页重拉 bfdata**（那是 ~0.5MB/分钟级），见 [LiveViewModel.tick]。
 */
class LiveScoreRepository {

    private val liveStatic = "https://livestatic.titan007.com"
    private val cp = "https://cp.titan007.com"
    private val mHost = "https://m.titan007.com"
    private val jcPageUrl = "$cp/buy/JingCai.aspx"
    private val jcReferer = jcPageUrl

    /** titan007 服务器时间相对设备时钟的偏移（ms）。整页刷新时由 bfdata 的 Date 头校正。 */
    @Volatile private var serverTimeOffsetMs: Long = 0L

    /** 用服务器时间校正后的「现在」（修正设备时钟漂移，避免进行分钟数整体偏慢约 1 分钟）。 */
    fun effectiveNow(): Long = System.currentTimeMillis() + serverTimeOffsetMs

    private fun ts(): String = Date().time.toString()

    private fun dx101Url(): String = "$cp/data/dx101.xml?${ts()}"

    private fun goalBf3Url(): String = "$liveStatic/vbsxml/goalbf3.xml?r=007${ts()}"

    private fun chGoalBf3Url(): String = "$liveStatic/vbsxml/ch_goalbf3.xml?r=007${ts()}"

    private fun changeUrl(): String = "$cp/info/match/data/change.xml?${ts()}"

    private fun liveChangeUrl(): String = "$mHost/phone/livechange.txt?${ts()}"

    /** 技统快照（角球 / 半场角球），全量一场一条，见 [loadCorners]。 */
    private fun detailUtUrl(): String = "$liveStatic/vbsxml/detail_ut.js?r=007${ts()}"

    // ==================================================================================
    // 竞彩编号：当期页 + 历史页
    // ==================================================================================

    /**
     * 补几天的历史竞彩页。
     *
     * bfdata 的「比赛日」窗口跨两天（如 11-02 早上拉到的 `matchdate` 仍是 11-01，
     * `firstschematchtime` 从 11-01 08:00 起），所以昨天这一期必须补；
     * 再往前一天 bfdata 已经不收录，补了也是 0 命中，但成本只在首次（有缓存），留作边界保险。
     */
    private val jcHistoryDays = 2

    /**
     * 一次竞彩页解析出的两样东西：
     *  - [rows]：titanId -> 该场元信息（内部 matchid / 赔率变动 spid / 官方编号）；
     *  - [spf]：matchid -> 胜平负赔率（UI 的「赔率子行」用）。
     * 两者同源于同一份 HTML，放一起缓存，避免重复下载。
     */
    private data class JcPage(
        val rows: Map<String, TitanLiveParser.JcRow> = emptyMap(),
        val spf: Map<String, Triple<String, String, String>> = emptyMap()
    ) {
        /** titanId -> 竞彩内部 matchid（dx101 大小球关联、竞彩 tab 过滤仍用这套映射）。 */
        val ids: Map<String, String> get() = rows.mapValues { it.value.matchId }
    }

    /** 历史竞彩页缓存：已完赛场次几乎不变，没必要跟着 15 秒的整页刷新重复拉 ~240KB。 */
    @Volatile private var histJcCache: JcPage = JcPage()
    @Volatile private var histJcCacheKey: String = ""
    @Volatile private var histJcCacheAt: Long = 0L
    private val histJcTtlMs = 10 * 60_000L

    /**
     * 历史某一期的竞彩页 URL。
     *
     * 🔴 日期格式是站点的 `yyyy-m-d`（**月/日不补零**，页面下拉里就是 `2026-11-1`），
     *    补零写法不一定被接受，照抄下拉的值最稳。时区固定 Asia/Shanghai，
     *    否则设备在别的时区会算错「昨天」。
     */
    private fun jcDateUrl(daysAgo: Int): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        val d = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
        return "$cp/buy/JingCai.aspx?typeID=101&oddstype=2&date=$d"
    }

    /** 解析一页竞彩 HTML（元信息 + 赔率一起，两个 parser 各解析一次，代价可接受）。 */
    private fun parseJcPage(html: String): JcPage =
        JcPage(TitanLiveParser.parseJcRows(html), TitanLiveParser.parseSpf(html))

    /**
     * 拉最近几期的竞彩页并合并（编号 + 胜平负赔率）。
     *
     * 🔴 **这就是「竞彩少了很多已完场比赛」的根因**：默认页只有当前一期（实测 19 行，
     *    其中 10 场能在 bfdata 里找到），而昨天一期有 25 场且 25 场全部命中 bfdata。
     *    不补历史 ⇒ 竞彩 tab 只剩今天十来场，昨天几十场完赛全丢。
     *
     * 单期失败静默跳过（不让历史页拖垮整个竞彩 tab）。
     */
    private fun loadHistJc(): JcPage {
        val urls = (1..jcHistoryDays).map { jcDateUrl(it) }
        val key = urls.joinToString("|")
        val now = System.currentTimeMillis()
        val cached = histJcCache
        // 日期滚动后 key 变（跨天）⇒ 必须重拉，否则「昨天」永远是前天
        if (key == histJcCacheKey && cached.rows.isNotEmpty() && now - histJcCacheAt < histJcTtlMs) return cached

        val rows = HashMap<String, TitanLiveParser.JcRow>()
        val spf = HashMap<String, Triple<String, String, String>>()
        for (u in urls) {
            runCatching {
                val p = parseJcPage(ApiClient.get(u, jcReferer))
                rows.putAll(p.rows)
                spf.putAll(p.spf)
            }
        }
        val out = JcPage(rows, spf)
        if (rows.isNotEmpty()) {
            histJcCache = out
            histJcCacheKey = key
            histJcCacheAt = now
        }
        return out
    }

    /**
     * 角球 / 半场角球的全量快照缓存（`detail_ut.js`，~247KB）。
     *
     * 🔴 **必须节流**：整页刷新每 15 秒一次（`LiveViewModel.tick` 每 3 拍），
     *    无脑跟着拉就是每分钟约 1MB 的下行流量。角球变化远没那么快，
     *    [cornerTtlMs] 内的重复请求直接吃缓存。
     * 失败时返回上一次的结果（首次失败就是空表 ⇒ UI 不显示角球行，不会显示假的 0-0）。
     */
    @Volatile private var cornerCache: Map<String, TitanLiveParser.Corners> = emptyMap()
    @Volatile private var cornerCacheAt: Long = 0L
    private val cornerTtlMs = 60_000L

    /** 拉/取角球快照（带 60 秒节流）。**调用方须在 IO 线程**。 */
    fun loadCorners(): Map<String, TitanLiveParser.Corners> {
        val now = System.currentTimeMillis()
        val cached = cornerCache
        if (now - cornerCacheAt < cornerTtlMs) return cached
        return try {
            val map = TitanLiveParser.parseCorners(
                ApiClient.get(detailUtUrl(), "https://live.titan007.com/")
            )
            if (map.isNotEmpty()) {
                cornerCache = map
                cornerCacheAt = now
                map
            } else {
                cached
            }
        } catch (e: Exception) {
            cached
        }
    }

    /**
     * 整页（全量）拉取 + 多源合并 → [RawLive]。
     * 大小球 / 竞彩编号失败不整体失败（降级为空映射）。
     *
     * @param includeCorners 是否补角球技统（多 1 个 ~247KB 的请求，60 秒节流）。
     *   **只有比分直播页需要**：后台进球轮询（`BackgroundGoalWatcher`，每 3 分钟整页校准一次）
     *   和投注结算只要比分，必须传 false —— 否则夜里息屏值守时会白白多下几 MB。
     */
    suspend fun fetchBfData(includeCorners: Boolean = true): RawLive = withContext(Dispatchers.IO) {
        // 历史竞彩页先并发跑起来（首次要拉 2 页 ~240KB，之后 10 分钟内走缓存），
        // 和下面的 bfdata 请求叠在一起，整页刷新不会被它拖慢一个往返。
        val histJc = async { loadHistJc() }
        // 角球快照同样先排队（60 秒内命中缓存几乎零成本），与 bfdata 并行，
        // 首屏不会为它多等一个往返。
        val corners = if (includeCorners) async { loadCorners() } else null

        // bfdata 用 getWithServerTime：借响应头 Date 校正设备时钟，避免「进行 X'」整体偏慢。
        val (js, serverMs) = ApiClient.getWithServerTime("$liveStatic/vbsxml/bfdata_ut.js?r=007${ts()}", "https://live.titan007.com/")
        if (serverMs > 0) serverTimeOffsetMs = serverMs - System.currentTimeMillis()
        val nowMs = effectiveNow()

        val raw = TitanLiveParser.parseBfData(js, nowMs)
        // 当期页（在售 + 今天刚完场）+ 历史页（昨天/前天已完场）合并。
        // 🔴 只用当期页的话，竞彩 tab 会只剩今天十来场 —— 昨天的竞彩完赛一场都看不到。
        val jcRows = HashMap<String, TitanLiveParser.JcRow>()
        val spf = HashMap<String, Triple<String, String, String>>()
        try {
            val cur = parseJcPage(ApiClient.get(jcPageUrl, jcReferer))
            jcRows.putAll(cur.rows)
            spf.putAll(cur.spf)
        } catch (e: Exception) {
            // 当期页失败不整体失败：历史页仍能给出昨天的场次
        }
        val hist = histJc.await()
        jcRows.putAll(hist.rows)
        spf.putAll(hist.spf)
        val jcMap = jcRows.mapValues { it.value.matchId }
        val ou = try {
            TitanLiveParser.parseOverUnder(ApiClient.get(dx101Url(), jcReferer))
        } catch (e: Exception) {
            emptyMap()
        }
        // goalbf3.xml = 全量大小球（实测 157/288 场有盘，未开盘联赛显示 `-`）；
        // ch_goalbf3.xml = 变动增量（参考站每 4 秒轮询），覆盖同 titanId 的最新水位。
        val goalBf3 = HashMap<String, Triple<String, String, String>>()
        try {
            goalBf3.putAll(TitanLiveParser.parseGoalBf3(ApiClient.get(goalBf3Url(), "https://live.titan007.com/")))
        } catch (e: Exception) {
            // 静默
        }
        try {
            goalBf3.putAll(TitanLiveParser.parseGoalBf3(ApiClient.get(chGoalBf3Url(), "https://live.titan007.com/")))
        } catch (e: Exception) {
            // 静默
        }
        // 赔率要在 applyOverUnder **之前**填：后者只补大小球与编号，不会覆盖 spf 三值；
        // 反过来先 applyOverUnder 再 applySpf 结果一样，但这样写少一次全列表 copy。
        val withSpf = TitanLiveParser.applySpf(raw.matches, jcRows, spf)
        val merged = TitanLiveParser.applyGoalBf3(
            TitanLiveParser.applyOverUnder(withSpf, ou, jcMap),
            goalBf3
        )
        // 角球放最后一步：它只写四个新字段，不参与任何尺寸/赔率推导，覆盖顺序无所谓，
        // 但放在最后能保证「其它源全部失败」时角球仍然进得来。
        val matches = if (corners != null) {
            TitanLiveParser.applyCorners(merged, corners.await())
        } else {
            merged
        }
        RawLive(matches, raw.hotSclassIds, jcRows.keys.toSet())
    }

    /**
     * 拉某一场竞彩**胜平负的赔率变动记录**（竞彩页每行「变」字弹窗的数据）。
     *
     * 🔴 `typeID=105` = 胜平负；`typeID=101` = **让球**胜平负。同一场两者 `spid` 相同，
     *    只有 typeID 不同 —— 写错就是「让球赔率冒充胜平负」，数值会凭空大跳一截。
     * 返回**时间正序**；空 spid 直接返回空表（UI 本来就不会画入口）。
     */
    suspend fun fetchSpfHistory(spid: String, typeId: Int = TYPE_SPF): List<JcSpfChange> =
        withContext(Dispatchers.IO) {
            if (spid.isBlank()) return@withContext emptyList()
            val url = "$cp/Handle/JcSp.aspx?oddsType=3&spid=$spid&typeID=$typeId"
            TitanLiveParser.parseJcSpfHistory(ApiClient.get(url, jcReferer))
        }

    companion object {
        /** 胜平负（不让球）。 */
        const val TYPE_SPF = 105

        /** 让球胜平负（亚盘玩家看的那个）。与 [TYPE_SPF] **共用同一个 spid**，只有 typeID 不同。 */
        const val TYPE_RQ = 101
    }

    /** 只拉比分增量原始串（5 秒 tick 用，交给 [TitanLiveParser.parseChanges] 解析）。 */    suspend fun fetchRawChanges(): String = withContext(Dispatchers.IO) {
        ApiClient.get(changeUrl(), jcReferer)
    }

    /**
     * 拉手机版 `livechange.txt` 原始串（5 秒 tick 用，交给 [TitanLiveParser.parseLiveChange] 解析官方补时）。
     * Referer 指向手机站首页；解析层对空/异常容错，调用方（tick）失败静默、保留旧值。
     */
    suspend fun fetchRawLiveChange(): String = withContext(Dispatchers.IO) {
        ApiClient.get(liveChangeUrl(), "$mHost/")
    }
}
