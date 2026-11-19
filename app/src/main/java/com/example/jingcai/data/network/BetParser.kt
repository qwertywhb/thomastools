package com.example.jingcai.data.network

import com.example.jingcai.data.model.BetMarket
import com.example.jingcai.data.model.BetMatch
import com.example.jingcai.data.model.BetOption
import com.example.jingcai.data.model.BetPool
import com.example.jingcai.data.model.BetSport
import org.json.JSONObject

/**
 * 体彩官方 calculator 接口 → [BetMatch]。
 *
 * 足球与篮球的**外层结构完全相同**（`value.matchInfoList[].subMatchList[]`），
 * 差别只在玩法键名与让球字段语义，故用一个解析器 + [BetSport] 分支。
 *
 * 🔴 三个必须记住的坑（都已踩过）：
 *  1. `poolCode` 是**过滤器**，不是「有就返回」。只要请求里没写某个 poolCode，
 *     对应字段就是 `{}` —— 实测篮球请求 `mnl,hilo` 时 `hdc` 恒为空对象，
 *     会被误判成「这场没开让分」。所以三玩法必须一起请求。
 *  2. 涨跌标志是**独立字段**（`hf` / `df` / `af` / `s0f` / `s00s00f` …），
 *     值为 `"1"` 涨 / `"-1"` 跌 / `"0"` 无。别拿赔率差值自己算。
 *  3. 比分键是 `s{主}s{客}` 两位补零（`s01s02` = 1:2），另有 `s1sh/s1sd/s1sa`
 *     三个「其他」。**取键时不能只认 `s\d\d`**，否则会把三个「其他」丢掉。
 */
object BetParser {

    /** 比分键 `s{主}{客}`，各两位（`s01s02` = 1:2）。三个「其他」不走这个正则。 */
    private val CRS_KEY = Regex("^s(\\d{2})s(\\d{2})$")

    fun parseFootball(root: JSONObject): List<BetMatch> = parse(root, BetSport.FOOTBALL)

    fun parseBasketball(root: JSONObject): List<BetMatch> = parse(root, BetSport.BASKETBALL)

    private fun parse(root: JSONObject, sport: BetSport): List<BetMatch> {
        val v = root.optJSONObject("value") ?: return emptyList()
        val days = v.optJSONArray("matchInfoList") ?: return emptyList()
        val out = mutableListOf<BetMatch>()
        for (di in 0 until days.length()) {
            val day = days.optJSONObject(di) ?: continue
            val businessDate = day.optString("businessDate")
            val subs = day.optJSONArray("subMatchList") ?: continue
            for (si in 0 until subs.length()) {
                val m = subs.optJSONObject(si) ?: continue
                // isHide=1 是官方「隐藏」标记（下架/异常场次），不在任何前台页面展示
                if (m.optString("isHide") == "1") continue
                val matches = if (sport == BetSport.FOOTBALL) footballMarkets(m)
                else basketballMarkets(m)
                if (matches.isEmpty()) continue
                out.add(
                    BetMatch(
                        sport = sport,
                        matchId = m.optString("matchId").toLongOrNull() ?: 0L,
                        num = m.optString("matchNumStr"),
                        league = m.optString("leagueAbbName"),
                        color = m.optString("backColor"),
                        home = m.optString("homeTeamAbbName"),
                        away = m.optString("awayTeamAbbName"),
                        homeRank = rankText(m.optString("homeRank")),
                        awayRank = rankText(m.optString("awayRank")),
                        businessDate = businessDate,
                        date = m.optString("matchDate"),
                        // 接口给 "17:00:00"，页面只显示时分
                        time = m.optString("matchTime").take(5),
                        ended = m.optString("matchStatus") != "Selling",
                        inline = matches.filter { it.pool.inline },
                        expand = matches.filterNot { it.pool.inline }
                    )
                )
            }
        }
        return out
    }

    /** `[澳超9]` → `9`；`[]`/`-` → 空串。参考图里队名旁只显示数字。 */
    private fun rankText(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.isEmpty()) "" else digits
    }

    // ============================ 足球 ============================

    private fun footballMarkets(m: JSONObject): List<BetMarket> {
        val list = mutableListOf<BetMarket>()

        // ① 胜平负（让球 0）
        m.optJSONObject("had")?.let { had ->
            val opts = listOf(
                opt(BetPool.HAD, "h", "主胜", had, "h", "hf"),
                opt(BetPool.HAD, "d", "平", had, "d", "df"),
                opt(BetPool.HAD, "a", "主负", had, "a", "af")
            ).filter { it.odds > 0 }
            if (opts.isNotEmpty()) list.add(BetMarket(BetPool.HAD, "0", opts, 3))
        }

        // ② 让球胜平负（lineLabel = 让球线，如 "-1"）
        m.optJSONObject("hhad")?.let { hhad ->
            val line = hhad.optString("goalLine").ifEmpty { "0" }
            val opts = listOf(
                opt(BetPool.HHAD, "h", "让胜", hhad, "h", "hf", line),
                opt(BetPool.HHAD, "d", "让平", hhad, "d", "df", line),
                opt(BetPool.HHAD, "a", "让负", hhad, "a", "af", line)
            ).filter { it.odds > 0 }
            if (opts.isNotEmpty()) list.add(BetMarket(BetPool.HHAD, line, opts, 3))
        }

        // ③ 比分（弹窗）：主胜区 → 平区 → 客胜区，各区内按进球数升序，末尾接「其他」
        m.optJSONObject("crs")?.let { crs ->
            val wins = mutableListOf<BetOption>()
            val draws = mutableListOf<BetOption>()
            val loses = mutableListOf<BetOption>()
            val others = mutableListOf<BetOption>()
            val it = crs.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (!k.startsWith("s") || k.endsWith("f")) continue
                if (k == "s1sh" || k == "s1sd" || k == "s1sa") {
                    val label = when (k) {
                        "s1sh" -> "胜其他"; "s1sd" -> "平其他"; else -> "负其他"
                    }
                    val o = opt(BetPool.CRS, k, label, crs, k, "${k}f")
                    if (o.odds > 0) others.add(o)
                    continue
                }
                // s{主}{客}，各两位
                val mm = CRS_KEY.find(k) ?: continue
                val hg = mm.groupValues[1].toIntOrNull() ?: continue
                val ag = mm.groupValues[2].toIntOrNull() ?: continue
                val o = opt(BetPool.CRS, k, "$hg:$ag", crs, k, "${k}f")
                if (o.odds <= 0) continue
                when {
                    hg > ag -> wins.add(o)
                    hg == ag -> draws.add(o)
                    else -> loses.add(o)
                }
            }
            fun sortedMain(l: List<BetOption>) = l.sortedWith(
                compareBy({ it.label.substringBefore(":").toInt() }, { it.label.substringAfter(":").toInt() })
            )
            // 客胜区按「客队进球优先升序」排（0:1 0:2 1:2 0:3 …），与参考图一致
            fun sortedAway(l: List<BetOption>) = l.sortedWith(
                compareBy({ it.label.substringAfter(":").toInt() }, { it.label.substringBefore(":").toInt() })
            )
            val ordered = mutableListOf<BetOption>()
            ordered.addAll(sortedMain(wins))
            others.firstOrNull { it.key == "s1sh" }?.let { ordered.add(it) }
            val nWin = ordered.size
            ordered.addAll(sortedMain(draws))
            others.firstOrNull { it.key == "s1sd" }?.let { ordered.add(it) }
            val nDraw = ordered.size - nWin
            ordered.addAll(sortedAway(loses))
            others.firstOrNull { it.key == "s1sa" }?.let { ordered.add(it) }
            val nLose = ordered.size - nWin - nDraw
            if (ordered.isNotEmpty()) {
                // 三个分区各起一行（参考图的排法）：13 / 5 / 13
                list.add(
                    BetMarket(
                        BetPool.CRS, "比分", ordered, 5,
                        groupSizes = listOf(nWin, nDraw, nLose).filter { it > 0 }
                    )
                )
            }
        }

        // ④ 总进球（弹窗）
        m.optJSONObject("ttg")?.let { ttg ->
            val opts = (0..7).mapNotNull { i ->
                val o = opt(BetPool.TTG, "s$i", if (i == 7) "7+球" else "${i}球", ttg, "s$i", "s${i}f")
                if (o.odds > 0) o else null
            }
            if (opts.isNotEmpty()) list.add(BetMarket(BetPool.TTG, "总进球", opts, 4))
        }

        // ⑤ 半全场（弹窗）：3×3 = 半场(胜/平/负) × 全场(胜/平/负)
        m.optJSONObject("hafu")?.let { hafu ->
            val order = listOf(
                "hh" to "胜胜", "hd" to "胜平", "ha" to "胜负",
                "dh" to "平胜", "dd" to "平平", "da" to "平负",
                "ah" to "负胜", "ad" to "负平", "aa" to "负负"
            )
            val opts = order.mapNotNull { (k, label) ->
                val o = opt(BetPool.HAFU, k, label, hafu, k, "${k}f")
                if (o.odds > 0) o else null
            }
            if (opts.isNotEmpty()) list.add(BetMarket(BetPool.HAFU, "半全场", opts, 3))
        }

        return list
    }

    // ============================ 篮球 ============================

    /**
     * 篮球三玩法，展示顺序与参考图一致：**让分 → 大小分 → 胜负**。
     *
     * 🔴 每行内选项顺序是「客在左、主在右」（参考图如此），不是主在左。
     */
    private fun basketballMarkets(m: JSONObject): List<BetMarket> {
        val list = mutableListOf<BetMarket>()

        m.optJSONObject("hdc")?.let { hdc ->
            val line = hdc.optString("goalLineValue").ifEmpty { hdc.optString("goalLine") }
            val opts = listOf(
                // 🔴 **让分选项一律不带线**（用户 2026-11-17 定稿，与体彩官方表格一致）：
                //    让分只有一条线、且永远是主队视角（`goalLineValue` 就是主队那条，如 -9.50），
                //    这条线挂在玩法名旁边显示一次（`BetMarket.lineText`）。
                //    以前给两侧各拼各自的线（主 `-9.50` / 客 `+9.50`），屏幕上就成了
                //    「让主胜 -9.5 / 让客胜 +9.5」并排 —— 一正一负像两边各让一半，
                //    用户直接读成「让分反了」。结算只看 `key` + `BetOption.line`，与文案无关。
                // 🔴 文案就三个字「让客胜 / 让主胜」（用户 2026-11-17 再定稿）：
                //    玩法名已经是「让分」，选项再写「让分客胜」等于每格重复一次玩法名，
                //    还把 2 列表格拉宽；「让」字留着是为了脱离玩法列单独看也不歧义。
                opt(BetPool.HDC, "a", "让客胜", hdc, "a", "af", line),
                opt(BetPool.HDC, "h", "让主胜", hdc, "h", "hf", line)
            ).filter { it.odds > 0 }
            if (opts.isNotEmpty()) {
                list.add(BetMarket(BetPool.HDC, "让分", opts, 2, lineText = line.trim()))
            }
        }

        m.optJSONObject("hilo")?.let { hilo ->
            val line = hilo.optString("goalLineValue").ifEmpty { hilo.optString("goalLine") }
            val opts = listOf(
                // 🔴 大小分沿用让分的口径（用户 2026-11-17）：
                //    盘口线（如 `227.5`）挂在玩法名旁边显示一次，选项格只写「大 / 小」。
                //    旧文案是「大于 227.50 / 小于 227.50」—— 线在两边各写一遍，
                //    一列 2 格里挤 6 个字，读起来像两个盘口；而且「大于/小于」比「大/小」多占
                //    一个字宽，窄屏上会被省略成「大于…」。
                opt(BetPool.HILO, "h", "大", hilo, "h", "hf", line),
                opt(BetPool.HILO, "l", "小", hilo, "l", "lf", line)
            ).filter { it.odds > 0 }
            if (opts.isNotEmpty()) {
                list.add(BetMarket(BetPool.HILO, "大小分", opts, 2, lineText = line.trim()))
            }
        }

        m.optJSONObject("mnl")?.let { mnl ->
            val opts = listOf(
                opt(BetPool.MNL, "a", "客胜", mnl, "a", "af"),
                opt(BetPool.MNL, "h", "主胜", mnl, "h", "hf")
            ).filter { it.odds > 0 }
            if (opts.isNotEmpty()) list.add(BetMarket(BetPool.MNL, "胜负", opts, 2))
        }

        return list
    }

    // ============================ 工具 ============================

    // ⚠️ 这里曾经有 `withLine()`（给选项拼 `让主胜(-15.50)`）和 `flipLine()`（把主队线翻成客队线）。
    //    2026-11-17 起**让分选项不再带线**，两个函数已删除 —— 线只保留一条主队视角的，
    //    由 `BetMarket.lineText` 带出、在玩法名旁显示一次。别再把「一正一负两条线」加回来。

    private fun opt(
        pool: BetPool,
        key: String,
        label: String,
        obj: JSONObject,
        oddsKey: String,
        trendKey: String,
        line: String = ""
    ): BetOption = BetOption(
        pool = pool,
        key = key,
        label = label,
        odds = obj.optString(oddsKey).toDoubleOrNull() ?: 0.0,
        trend = obj.optString(trendKey).toIntOrNull() ?: 0,
        line = line
    )
}
