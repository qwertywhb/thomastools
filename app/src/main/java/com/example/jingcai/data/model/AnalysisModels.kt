package com.example.jingcai.data.model

/**
 * 「数据分析」页（`zq.titan007.com/analysis/<id>cn.htm`）的模型。
 *
 * 只覆盖用户要的两块：**双方交手战绩** 与 **近期战绩**，源站其余板块
 * （数据对比 / 亚盘 / 欧赔 / 大小球 / 预测）一律不解析。
 */
data class MatchAnalysis(
    val homeTeam: String = "",
    val awayTeam: String = "",
    /** 对赛往绩（近 N 次交锋）。*/
    val h2h: AnalysisSection = AnalysisSection(),
    /** 主队近期战绩。*/
    val homeRecent: AnalysisSection = AnalysisSection(),
    /** 客队近期战绩。*/
    val awayRecent: AnalysisSection = AnalysisSection()
) {
    val isEmpty: Boolean
        get() = h2h.rows.isEmpty() && homeRecent.rows.isEmpty() && awayRecent.rows.isEmpty()
}

/**
 * 一段战绩表格。
 *
 * @param title 段标题（近期战绩用队名，交锋为空）
 * @param rows 表格行，按源站顺序（时间倒序）
 * @param total/win/draw/loss 底部汇总（源站「近 10 场, 胜出 4 场…」），取不到时均为 0
 * @param winRate/marginRate/bigRate 源站的胜率 / 赢率 / 大球率（原样字符串，如 `40%`）
 */
data class AnalysisSection(
    val title: String = "",
    val rows: List<AnalysisRow> = emptyList(),
    val total: Int = 0,
    val win: Int = 0,
    val draw: Int = 0,
    val loss: Int = 0,
    val winRate: String = "",
    val marginRate: String = "",
    val bigRate: String = ""
) {
    val hasSummary: Boolean get() = total > 0
}

/**
 * 战绩表格的一行（源站 `table#table_v` / `table#table_hn` / `table#table_an` 的 `tr`）。
 *
 * @param result 关注球队视角的赛果：`胜` / `平` / `负`，源站没给时为空串
 *   （交锋表是**本场主队**视角，近期表是**该表所属球队**视角 —— 与源站一致，不要换算）
 * @param halfScore 半场比分，如 `1-0`；源站没渲染时为空
 * @param corner 角球 `主-客`，如 `3-12`
 */
data class AnalysisRow(
    val league: String = "",
    val date: String = "",
    val home: String = "",
    val away: String = "",
    val score: String = "",
    val halfScore: String = "",
    val corner: String = "",
    val result: String = ""
)
