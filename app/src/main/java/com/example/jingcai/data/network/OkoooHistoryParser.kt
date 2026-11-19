package com.example.jingcai.data.network

import com.example.jingcai.data.model.HistoryData
import com.example.jingcai.data.model.HistoryMatch
import com.example.jingcai.data.model.HistorySection
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 解析 okooo「近期状态」页 `https://www.okooo.cn/soccer/match/{mid}/history/`（GBK 已由 ApiClient 解码）。
 *
 * 🔴 **UA 必须用 okhttp UA**（见 [ApiClient]）：桌面 Chrome / 部分移动 Chrome UA 会被 okooo WAF 405 限流
 *    （2026-10-23 实测移动 Chrome UA 连发 8/8 全 405，`okhttp/4.12.0` 6/6 全 200）。
 *    本解析器只负责字符串 → 模型；网络层见 `HistoryRepository`。
 *
 * 页面三表（样本 1341208 实测）：
 *  - `table.homecomp`（主队近期）/ `table.awaycomp`（客队近期）/ `table.vscomp`（两队历史交锋）
 *  - 表头 `tr.tableh` 的 `th.lstitle` 内含：队名文本 + 过滤 select + `span.homescore|awayscore|vsscore`
 *    （小结由页面 JS `gethomerecord` 计算，此处**复刻其口径**得出 [HistorySection.summary]）
 *  - `tr.titlebg` 是 13 列列名行；`tr.jsThisMatch`（`style="display:none"`）是「本场」占位行，**必须跳过**
 *  - 数据行：`tr[attr="主id,比分,客id"][data-lt][data-matchid]`，固定 13 个 td：
 *      0=联赛+轮次 span | 1=时间(链接短日期 + div.smalltitle 完整时间) | 2=主队 | 3=比分(td attr 直取)
 *      4=客队 | 5=半场 | 6/7/8=99家平均终指 | 9=365终指主 | 10=亚盘盘口 | 11=365终指客 | 12=赢盘结果
 *    胜方高亮 `span.bold.redtxt`（主胜）/ `span.bold.bluetxt`（客胜）/ `span.bold `（平）；
 *    赢盘 `span.redB`=赢 / `span.blueB`=输 / `span.huiB`=走。
 *
 * 容错：缺 td / 空比分（未开赛）按空串处理，不抛异常；整页无表时返回空段。
 */
object OkoooHistoryParser {

    fun parse(html: String): HistoryData {
        val doc = Jsoup.parse(html)

        // 本场主客队 id / 名：页面内嵌 JS `var host_team_id = '416'`，兜底取 homecomp 表头文本。
        var homeId = ""
        var awayId = ""
        for (m in Regex("var\\s+host_team_id\\s*=\\s*'([^']+)'").findAll(html)) homeId = m.groupValues[1]
        for (m in Regex("var\\s+guest_team_id\\s*=\\s*'([^']+)'").findAll(html)) awayId = m.groupValues[1]

        val homeTable = doc.selectFirst("table.homecomp")
        val awayTable = doc.selectFirst("table.awaycomp")
        val vsTable = doc.selectFirst("table.vscomp")

        val homeName = sectionTitle(homeTable) ?: ""
        val awayName = sectionTitle(awayTable) ?: ""
        if (homeId.isEmpty()) homeId = rowTeamId(homeTable) ?: ""
        if (awayId.isEmpty()) awayId = rowTeamId(awayTable) ?: ""

        val homeRows = parseRows(homeTable)
        val awayRows = parseRows(awayTable)
        val vsRows = parseRows(vsTable)

        return HistoryData(
            homeId = homeId,
            homeName = homeName,
            awayId = awayId,
            awayName = awayName,
            curLeague = currentLeague(homeTable, homeRows),
            home = HistorySection(homeName, homeId, homeRows, summary(homeRows, homeId, false)),
            away = HistorySection(awayName, awayId, awayRows, summary(awayRows, awayId, true)),
            vs = HistorySection("两队交锋", homeId, vsRows, summary(vsRows, homeId, false)),
            homeFuture = parseFuture(doc.selectFirst("div.homefuture")),
            awayFuture = parseFuture(doc.selectFirst("div.awayfuture"))
        )
    }

    // ===== 表头 =====

    /**
     * `th.lstitle` 内的队名。
     *
     * 样本里 homecomp 是「th > div(内含队名+select)」、awaycomp 是「th 直接文本 + div(select)」
     * 两种形态，且 select/option 里的「全赛事/6场…」会被 `Element.text()` 一并带出
     * ⇒ 收集**排除 select 子树**后的文本节点再拼接，两种形态都对。
     */
    private fun sectionTitle(table: Element?): String? {
        val th = table?.selectFirst("th.lstitle") ?: return null
        val sb = StringBuilder()
        collectText(th, sb)
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    /** 递归收集文本节点，跳过 `select` / `script` / `span.*score`（小结，不属队名）。 */
    private fun collectText(node: org.jsoup.nodes.Node, sb: StringBuilder) {
        when (node) {
            is org.jsoup.nodes.TextNode -> sb.append(node.text())
            is Element -> {
                val tag = node.normalName()
                if (tag == "select" || tag == "script" || tag == "option" || tag == "label") return
                if (tag == "span" && (node.className().contains("homescore") ||
                        node.className().contains("awayscore") || node.className().contains("vsscore"))
                ) return
                for (c in node.childNodes()) collectText(c, sb)
            }
        }
    }

    /** 从首行主队 td 的 attr 兜底推断主队 id（JS 变量缺失时）。 */
    private fun rowTeamId(table: Element?): String? {
        val tr = table?.select("tr")?.firstOrNull { it.hasAttr("data-matchid") } ?: return null
        return tr.children().getOrNull(2)?.attr("attr")?.takeIf { it.isNotEmpty() }
    }

    /**
     * 本赛事名：优先取表头 filter 下拉里「本赛事」的 option value（排除哨兵 all / notFriendly）；
     * 缺失时退回「有比分行里出现次数最多的联赛」，再退回第一行联赛。
     * 🔴 不能用 rows.first().league —— 首行可能是一场球会友谊（样本 1338460 即如此）。
     */
    private fun currentLeague(table: Element?, rows: List<HistoryMatch>): String {
        val fromSelect = table?.select("select[id$=LeagueFilter] option")
            ?.mapNotNull { it.attr("value").trim().takeIf { v -> v.isNotEmpty() && v != "all" && v != "notFriendly" } }
            ?.firstOrNull()
        if (!fromSelect.isNullOrEmpty()) return fromSelect
        val byFreq = rows.filter { it.hasScore && it.league.isNotEmpty() }
            .groupingBy { it.league }.eachCount()
            .maxByOrNull { it.value }?.key
        return byFreq ?: rows.firstOrNull { it.league.isNotEmpty() }?.league ?: ""
    }

    // ===== 数据行 =====

    private fun parseRows(table: Element?): List<HistoryMatch> {
        if (table == null) return emptyList()
        val out = mutableListOf<HistoryMatch>()
        for (tr in table.select("tr")) {
            // 跳过表头/列名行/「本场」占位行（tr.jsThisMatch）。
            // ⚠️ 不要按 style=display:none 跳行：okooo 页面的 display:none 只是「默认只显示前 10 条」
            //    的网页初始态，隐藏行是真实历史数据，页面 JS（footcompetitionhistory.js 的 filter()）
            //    会在用户切 6/10/20/50 场时显示它们；只跳 jsThisMatch 才是唯一真正的占位行。
            if (!tr.hasAttr("data-matchid")) continue
            val cls = tr.className()
            if (cls.contains("jsThisMatch") || cls.contains("tableh") || cls.contains("titlebg")) continue

            val tds = tr.children()
            if (tds.size < 13) continue // 缺列容错：整行丢弃（样本恒为 13 列）

            // td0：联赛 a + 轮次 span
            val td0 = tds[0]
            val league = td0.selectFirst("a")?.text()?.trim() ?: td0.text().trim()
            val round = td0.selectFirst("span")?.text()?.trim() ?: ""

            // td1：短日期链接 + div.smalltitle 完整时间
            val td1 = tds[1]
            val date = td1.selectFirst("a")?.text()?.trim() ?: ""
            val fullTime = td1.selectFirst("div.smalltitle")?.text()?.trim() ?: ""

            // td3：比分直接取 attr（未开赛 attr="-" 或空）
            val rawScore = tds[3].attr("attr").trim()
            val score = if (rawScore == "-" || rawScore == "event") "" else rawScore

            // td12：赢盘结果（redB=赢 / blueB=输 / huiB=走），文本兜底
            val td12 = tds[12]
            val pan = when {
                td12.selectFirst(".redB") != null -> "赢"
                td12.selectFirst(".blueB") != null -> "输"
                td12.selectFirst(".huiB") != null -> "走"
                else -> td12.text().trim()
            }

            out.add(
                HistoryMatch(
                    mid = tr.attr("data-matchid").toLongOrNull() ?: 0L,
                    league = league,
                    round = round,
                    date = date,
                    fullTime = fullTime,
                    home = tds[2].text().trim(),
                    away = tds[4].text().trim(),
                    homeId = tds[2].attr("attr").trim(),
                    awayId = tds[4].attr("attr").trim(),
                    score = score,
                    halfScore = tds[5].text().trim(),
                    matchType = tr.attr("data-lt").trim(),
                    pan = pan
                )
            )
        }
        return out
    }

    /**
     * 解析未来赛程块（div.homefuture / div.awayfuture）。
     * 🔴 与 history 表不同：只有 6 个 td、数据行没有 attr 也没有 data-lt，不能套用 [parseRows]。
     * 🔴 必须跳过 tr.jsThisMatch（本场），它在 future 块里可能带着比分（样本 1341208 即 0-0）。
     */
    private fun parseFuture(container: Element?): List<HistoryMatch> {
        val table = container?.selectFirst("table") ?: return emptyList()
        val out = mutableListOf<HistoryMatch>()
        for (tr in table.select("tr")) {
            if (!tr.hasAttr("data-matchid")) continue
            if (tr.className().contains("jsThisMatch")) continue
            val tds = tr.children()
            if (tds.size < 6) continue
            val td0 = tds[0]
            val td1 = tds[1]
            out.add(
                HistoryMatch(
                    mid = tr.attr("data-matchid").toLongOrNull() ?: 0L,
                    league = td0.selectFirst("a")?.text()?.trim() ?: td0.text().trim(),
                    round = td0.selectFirst("span")?.text()?.trim() ?: "",
                    date = td1.selectFirst("a")?.text()?.trim() ?: "",
                    fullTime = td1.selectFirst("div.smalltitle")?.text()?.trim() ?: "",
                    home = tds[2].text().trim(),
                    away = tds[4].text().trim(),
                    score = "",          // 未来赛程一律无比分
                    halfScore = "",
                    matchType = tr.attr("data-lt").trim(),
                    pan = ""
                )
            )
        }
        return out.take(5)
    }

    /**
     * 复刻页面 JS `gethomerecord`（footcompetitionhistory.js）的统计口径：
     *  - 仅统计**有效比分**行（score != "-"），不含被跳过的本场行；
     *  - `teamId` 为主视角：主场作战计入「主场X胜X平X负」，否则计入「客场…」；
     *  - 主/客视角都输出 `N胜N平N负，主场|客场N胜N平N负，进X球失X球`。
     */
    private fun summary(rows: List<HistoryMatch>, teamId: String, awayView: Boolean): String {
        var win = 0; var draw = 0; var lose = 0
        var homeWin = 0; var homeDraw = 0; var homeLose = 0
        var goalsFor = 0; var goalsAgainst = 0
        for (r in rows) {
            if (!r.hasScore) continue
            val res = r.resultFor(teamId)
            when (res) {
                "胜" -> win++
                "平" -> draw++
                "负" -> lose++
                else -> continue
            }
            if (r.isHomeFor(teamId)) {
                when (res) {
                    "胜" -> homeWin++
                    "平" -> homeDraw++
                    "负" -> homeLose++
                }
                goalsFor += r.homeGoals; goalsAgainst += r.awayGoals
            } else {
                goalsFor += r.awayGoals; goalsAgainst += r.homeGoals
            }
        }
        val venue = if (awayView) {
            "客场${win - homeWin}胜${draw - homeDraw}平${lose - homeLose}负"
        } else {
            "主场${homeWin}胜${homeDraw}平${homeLose}负"
        }
        return "${win}胜${draw}平${lose}负，$venue，进${goalsFor}球失${goalsAgainst}球"
    }
}
