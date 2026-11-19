package com.example.jingcai.data.model

/**
 * 澳客 okooo「近期状态」（history 页）数据模型。
 *
 * 页面结构（样本 1341208 实测）：
 *  - `table.homecomp`：主队近期（表头 th 内含主队名 + 过滤 select + `span.homescore` 小结）
 *  - `table.awaycomp`：客队近期（表头 th 内含客队名 + `span.awayscore`）
 *  - `table.vscomp`：两队历史交锋（表头 th 内含主队名 + `span.vsscore`）
 * 每个表的数据行固定 13 个 td；`tr.jsThisMatch`（`style="display:none"`）是「本场」占位行，需跳过。
 */

/** 单场历史比赛（三个表共用同一行结构）。 */
data class HistoryMatch(
    /** 澳客场次 id（`tr[data-matchid]`），0 表示缺失。 */
    val mid: Long = 0L,
    /** 联赛名（td0 `a` 文本）。 */
    val league: String = "",
    /** 轮次（td0 内 `span` 文本，可能为空）。 */
    val round: String = "",
    /** 短日期，如 `26-10-17`（td1 链接文本）。 */
    val date: String = "",
    /** 完整时间，如 `2026-10-17 13:00`（td1 `div.smalltitle`，可能为空）。 */
    val fullTime: String = "",
    val home: String = "",
    val away: String = "",
    /** 主队 id（td2 `attr`），用于「本队是主是客」判断。 */
    val homeId: String = "",
    val awayId: String = "",
    /** 全场比分，如 `0-4`（td3 `attr` 直取；未开赛可能为空）。 */
    val score: String = "",
    /** 半场比分（td5，可能为空）。 */
    val halfScore: String = "",
    /** 赛事类型（`tr[data-lt]`）：league / cup / friend / 空。 */
    val matchType: String = "",
    /** 赢盘结果文字（td12：赢/输/走），可能为空。 */
    val pan: String = ""
) {
    /** 主队进球（解析失败为 -1）。 */
    val homeGoals: Int get() = score.substringBefore("-").trim().toIntOrNull() ?: -1
    /** 客队进球（解析失败为 -1）。 */
    val awayGoals: Int get() = score.substringAfterLast("-").trim().toIntOrNull() ?: -1
    /** 比分是否有效（未开赛 / 缺失为 false）。 */
    val hasScore: Boolean get() = homeGoals >= 0 && awayGoals >= 0 && score.contains("-")

    /**
     * 本队视角赛果（[teamId] 是本队 id，来自 `host_team_id`/`guest_team_id`）。
     * 返回 "胜"/"平"/"负"；比分缺失返回 ""。
     */
    fun resultFor(teamId: String): String {
        if (!hasScore) return ""
        val isHome = homeId == teamId
        val diff = homeGoals - awayGoals
        return when {
            diff == 0 -> "平"
            (diff > 0) == isHome -> "胜"
            else -> "负"
        }
    }

    /** 本队是否主队（本行 td2 的 attr == 本队 id）。 */
    fun isHomeFor(teamId: String): Boolean = homeId == teamId
}

/**
 * 一段近期比赛 + 派生统计（与网页 `gethomerecord` 同口径：全量行统计，不含被跳过的本场行）。
 */
data class HistorySection(
    /** 段名（主队名 / 客队名 / "两队交锋"）。 */
    val title: String = "",
    /** 本段统计对应球队 id（交锋段为主队 id）。 */
    val teamId: String = "",
    val rows: List<HistoryMatch> = emptyList(),
    /** 网页 `span.homescore`/`awayscore`/`vsscore` 同款小结文案（由 JS `gethomerecord` 逻辑复刻）。 */
    val summary: String = ""
) {
    /** 取前 [n] 行（网页默认渲染 10 场，App 端自行截取）。 */
    fun top(n: Int): List<HistoryMatch> = rows.take(n)
}

/** history 页整体解析结果。 */
data class HistoryData(
    /** 本场主队 id / 名（页面 JS 变量 `host_team_id`）。 */
    val homeId: String = "",
    val homeName: String = "",
    val awayId: String = "",
    val awayName: String = "",
    /** 本场比赛所属联赛名（页面 filter 下拉里「本赛事」的 option value），供「本赛事」筛选使用。 */
    val curLeague: String = "",
    val home: HistorySection = HistorySection(),
    val away: HistorySection = HistorySection(),
    val vs: HistorySection = HistorySection(),
    /** 主队未来 5 场（div.homefuture，页面自带；本场占位行已剔除）。 */
    val homeFuture: List<HistoryMatch> = emptyList(),
    /** 客队未来 5 场（div.awayfuture）。 */
    val awayFuture: List<HistoryMatch> = emptyList()
)
