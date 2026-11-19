package com.example.jingcai.data.settle

import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.LegResult
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 真实注单回放（**仅 debug**）：拿手机导出的注单 + 球米宝实时赛果，
 * 逐腿对比「旧口径（不翻主客）」与「新口径（按队名对齐）」的判定差异。
 *
 * ## 为什么要这个脚本
 * 用户报障「篮球开奖有些主客队搞反了，让分开奖错了」时，光靠自检里的构造用例只能证明
 * *逻辑*对，证明不了*他那几张单*到底错没错。这里直接用他存档里的原始 `BetPick`
 * （含 `home/away/line/key/odds`）跑一遍，输出「哪条腿被翻案了」。
 *
 * 跑法（项目根目录）：
 * ```
 * java -cp "app/build/tmp/kotlin-classes/debug;<kotlin-stdlib.jar>;<gson.jar>" \
 *   com.example.jingcai.data.settle.BbSideReplay qa/_tickets.json
 * ```
 * `qa/_tickets.json` = 从 `shared_prefs/bet_ticket_store.xml` 里取出的 `tickets` 数组原文。
 */
object BbSideReplay {

    @JvmStatic
    fun main(args: Array<String>) {
        val file = File(args.getOrElse(0) { "qa/_tickets.json" })
        if (!file.exists()) {
            println("找不到注单文件：${file.absolutePath}")
            return
        }
        val tickets: List<BetTicket> = Gson().fromJson(
            file.readText(),
            object : TypeToken<List<BetTicket>>() {}.type
        )
        val basket = fetchBasket()
        println("球米宝篮球竞彩场次：${basket.size} 场，注单 ${tickets.size} 张\n")

        var diffs = 0
        for (t in tickets) {
            val bk = t.picks.filter { it.sport != "FOOTBALL" }
            if (bk.isEmpty()) continue

            val bars = mutableListOf<String>()
            val oldScores = HashMap<Long, MatchScore>()
            val newScores = HashMap<Long, MatchScore>()
            var changed = false

            for (p in t.picks) {
                if (p.sport == "FOOTBALL") continue
                val bs = basket[p.num]
                if (bs == null) {
                    bars += "    ${p.num} ${p.home} VS ${p.away}  → 无赛果（待开奖）"
                    continue
                }
                val old = bs.score
                val new = bs.alignedTo(p.home, p.away)
                oldScores[p.matchId] = old
                newScores[p.matchId] = new
                val lo = BetCalculator.judge(p, old)
                val ln = BetCalculator.judge(p, new)
                if (lo != ln) changed = true
                val src = "${bs.sourceHome} ${old.fullHome}:${old.fullAway} ${bs.sourceAway}"
                val mark = if (lo != ln) "  ⟵ 翻案" else ""
                bars += "    ${p.num} [${p.poolCode}/${p.key}${if (p.line.isNotBlank()) " " + p.line else ""}] " +
                    "单据 ${p.home} VS ${p.away} | 源 $src | 旧=$lo 新=$ln$mark"
            }

            val so = BetCalculator.settle(t, oldScores)
            val sn = BetCalculator.settle(t, newScores)
            if (changed || so.status != sn.status) diffs++
            println("=== ${t.id.take(8)}  ${t.ms}串  ${t.times}倍  存档状态=${t.status}")
            bars.forEach { println(it) }
            println("    整单：旧=${so.status}(派彩 ${so.payout})  新=${sn.status}(派彩 ${sn.payout})" +
                if (so.status != sn.status) "   ⟵ 结论改变" else "")
            println()
        }
        println("有差异的注单：$diffs 张")
    }

    private fun fetchBasket(): Map<String, BasketScore> {
        val conn = URL("https://matchs.qiumibao.com/live/all.htm").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
        conn.setRequestProperty("Referer", "https://www.188bifen.com/lanqiubifen.htm")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(text).asJsonObject
        val out = HashMap<String, BasketScore>()
        for (e in root.getAsJsonArray("matches")) {
            val a = e.asJsonArray
            if (a.get(19).asString != "2") continue
            val jc = a.get(10).asJsonArray
            if (jc.size() == 0 || jc.get(0).asString.isBlank()) continue
            val status = a.get(2).asInt
            if (status == 1) continue
            val cancelled = status == 9 || status == 0
            val h = a.get(5).asJsonArray
            val w = a.get(6).asJsonArray
            out[jc.get(0).asString] = BasketScore(
                sourceHome = h.get(1).asString,
                sourceAway = w.get(1).asString,
                score = MatchScore(
                    fullHome = h.get(2).asInt,
                    fullAway = w.get(2).asInt,
                    halfHome = h.get(3).asInt,
                    halfAway = w.get(3).asInt,
                    finished = status == 8 || cancelled,
                    cancelled = cancelled
                )
            )
        }
        return out
    }

    @Suppress("unused")
    private fun LegResult.name() = name
}
