package com.example.jingcai.data.network

import com.example.jingcai.data.model.AnalysisRow
import com.example.jingcai.data.model.AnalysisSection
import com.example.jingcai.data.model.MatchAnalysis
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 「数据分析」页解析器（`zq.titan007.com/analysis/<titanId>cn.htm`）。
 *
 * **纯函数，不发网络请求**（便于用离线 HTML 做自检，见 `AnalysisParseSelfCheck`）。
 *
 * ## 只取两块
 * 源站整页有 30 个 `div.porletP` 板块（数据对比 / 亚盘 / 欧赔 / 大小球 / 预测…），
 * 用户只要「双方交手战绩」和「近期战绩」，因此按 `h2.fx_title2` 的**文案**定位板块，
 * 不认 `id="porlet_8"` 这类序号 —— 序号是源站板块排序的结果，换场次/换版本会漂。
 *
 * ## 表格字段契约（实测 2026-11-09，墨西联 克雷塔罗 vs 普埃布拉）
 * 三张表结构一致（15 列、两行表头）：
 * ```
 * [0]类型 [1]日期 [2]主场 [3]比分(半场) [4]角球 [5]客场
 * [6][7][8] 让球 主/盘口/客   [9][10][11] 欧赔 主/和/客
 * [12]胜负  [13]让球  [14]进球数
 * ```
 * - 主/客队名里夹着排名徽标 `span.hp`（如 `<span class="hp">1</span>普埃布拉`），
 *   `text()` 会把「1」粘在队名前面 ⇒ 必须**先删掉 `span.hp`** 再取文本，否则队名变成 `1普埃布拉`。
 * - `[3]` 是 `1-2(1-0)` 一整串：比分与半场要拆开；友谊赛等只有 `1-0`、没有括号。
 * - `[12]` 的胜负是**关注球队视角**（交锋表=本场主队，近期表=该表所属球队），
 *   与页面显示一致，**不做视角换算**。
 * - 汇总行是 `td[colspan=15]`，没有 `id`，所以取行时按 `tr[id^=trv_]` / `tr[id^=trhn_]` / `tr[id^=tran_]` 过滤。
 */
object TitanAnalysisParser {

    private const val ID_H2H = "trv_"
    private const val ID_HOME_RECENT = "trhn_"
    private const val ID_AWAY_RECENT = "tran_"

    /** 源站「近期战绩」默认取近 10 场；解析结果按页面实际行数走，不硬截断。*/
    fun parse(html: String): MatchAnalysis? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)

        // 按标题文案找板块（标题里可能含「关闭」按钮等子元素，用 text() 后 contains 判断）
        val blocks = doc.select("div.porletP")
        val h2hBlock = blocks.firstOrNull { titleOf(it).contains("对赛往绩") }
        val recentBlock = blocks.firstOrNull { titleOf(it).contains("近期战绩") }

        val h2h = h2hBlock?.selectFirst("table#table_v")?.let { parseTable(it, ID_H2H) }
            ?: AnalysisSection()

        val homeTable = recentBlock?.selectFirst("table#table_hn")
        val awayTable = recentBlock?.selectFirst("table#table_an")
        /**
         * 队名（横版表头 `font.vander16 > b`），顺序即 [主队, 客队]。
         * 🔴 必须限定在 `table#vsNew`（横版）里找：同一个板块内 `table#vsOld`（竖版，`display:none`）
         * 也有两份同样的表头，抓全板块会取到 4 个（主,客,主,客），一旦竖版 DOM 顺序或残留变化就串队。
         */
        val names = (recentBlock?.selectFirst("table#vsNew") ?: recentBlock)
            ?.select("font.vander16 b")
            ?.map { it.text().trim() }
            ?: emptyList()

        val homeRecent = homeTable?.let { parseTable(it, ID_HOME_RECENT) }
            ?.let { it.copy(title = names.getOrNull(0).orEmpty()) } ?: AnalysisSection()
        val awayRecent = awayTable?.let { parseTable(it, ID_AWAY_RECENT) }
            ?.let { it.copy(title = names.getOrNull(1).orEmpty()) } ?: AnalysisSection()

        val result = MatchAnalysis(
            homeTeam = names.getOrNull(0).orEmpty(),
            awayTeam = names.getOrNull(1).orEmpty(),
            h2h = h2h,
            homeRecent = homeRecent,
            awayRecent = awayRecent
        )
        return if (result.isEmpty) null else result
    }

    private fun titleOf(block: Element): String =
        block.selectFirst("h2.fx_title2")?.text()?.replace("\u00a0", " ").orEmpty()

    /**
     * 解析一张战绩表。
     * @param rowIdPrefix 该表数据行 `tr` 的 id 前缀（用来排除表头与汇总行）
     */
    private fun parseTable(table: Element, rowIdPrefix: String): AnalysisSection {
        val rows = table.select("tr[id^=$rowIdPrefix]").mapNotNull { parseRow(it) }
        val summary = summaryOf(table)
        return AnalysisSection(rows = rows).copy(
            total = summary.total,
            win = summary.win,
            draw = summary.draw,
            loss = summary.loss,
            winRate = summary.winRate,
            marginRate = summary.marginRate,
            bigRate = summary.bigRate
        )
    }

    private fun parseRow(tr: Element): AnalysisRow? {
        // 🔴 `children()` 返回 jsoup 的 `Elements`，它自带 `filter(NodeFilter)` 重载，
        //    直接 `filter { }` 会被解析成那个签名（报 "Expected 2 parameters of types Node!, Int"）。
        val tds = tr.children().toList().filter { it.tagName() == "td" }
        // 数据行固定 15 列；少于 6 列必然是畸形行（广告/占位），直接丢
        if (tds.size < 6) return null
        val rawScore = tds.getOrNull(3)?.text()?.trim().orEmpty()
        val (score, half) = splitScore(rawScore)
        return AnalysisRow(
            league = tds[0].text().trim(),
            date = tds.getOrNull(1)?.text()?.trim().orEmpty(),
            home = teamName(tds.getOrNull(2)),
            away = teamName(tds.getOrNull(5)),
            score = score,
            halfScore = half,
            corner = tds.getOrNull(4)?.text()?.trim().orEmpty(),
            result = tds.getOrNull(12)?.text()?.trim().orEmpty()
        )
    }

    /**
     * 队名：先摘掉排名徽标 `span.hp`。
     * 直接 `text()` 会把徽标里的数字留在最前面（`1普埃布拉`），这是本页最容易踩的坑。
     */
    private fun teamName(td: Element?): String {
        if (td == null) return ""
        val clone = td.clone()
        clone.select("span.hp").remove()
        return clone.text().trim()
    }

    /** `1-2(1-0)` → (`1-2`, `1-0`)；没有括号时半场为空。*/
    private fun splitScore(raw: String): Pair<String, String> {
        val s = raw.replace(" ", "")
        if (s.isEmpty()) return "" to ""
        val open = s.indexOf('(')
        if (open < 0) return s to ""
        val close = s.indexOf(')', open + 1)
        val score = s.substring(0, open)
        val half = if (close > open) s.substring(open + 1, close) else ""
        return score to half
    }

    /**
     * 汇总行（表尾 `td[colspan=15]`）。两种文案都要认：
     *  - 交锋：`近 10 场, 胜出 4 场,平局 2 场,输 4 场, 胜率：40% 赢率：44.4% 大率：33.3% 单率：30%`
     *  - 近期：`近10场,胜4平1负5, 胜率:40% 赢率:40% 大:60% 单率:40%`
     */
    private data class Summary(
        val total: Int, val win: Int, val draw: Int, val loss: Int,
        val winRate: String, val marginRate: String, val bigRate: String
    )

    private fun summaryOf(table: Element): Summary {
        // 汇总行 = 最后一个带 colspan 的 tr（数据行都可能没有 colspan）
        // 🔴 先 `toList()`：jsoup 的 `Elements` 自带 `lastOrNull(...)` 重载（NodeFilter），
        //    直接传 lambda 会被解析成它，报 "Expected 2 parameters of types Node!, Int"。
        val trs = table.select("tr").toList()
        val text = trs.lastOrNull { tr ->
            tr.children().any { it.tagName() == "td" && it.hasAttr("colspan") }
        }?.text()?.replace("\u00a0", " ")?.trim().orEmpty()
        if (text.isEmpty()) return Summary(0, 0, 0, 0, "", "", "")
        val t = text.replace(" ", "")
        return Summary(
            total = Regex("近(\\d+)场").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            win = firstInt(t, "胜"),
            draw = firstInt(t, "平"),
            loss = firstInt(t, "负").takeIf { it > 0 } ?: firstInt(t, "输"),
            winRate = percent(t, "胜率"),
            marginRate = percent(t, "赢率"),
            bigRate = percent(t, "大率").ifEmpty { percent(t, "大:") }
        )
    }

    /** 「胜出 4」「胜4」「平局 2」→ 4；不会误吃「胜率40%」（`率` 不是数字）。*/
    private fun firstInt(text: String, prefix: String): Int =
        Regex("$prefix(?:出|局)?(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun percent(text: String, prefix: String): String =
        Regex("$prefix[:：]?([\\d.]+%)").find(text)?.groupValues?.get(1).orEmpty()
}
