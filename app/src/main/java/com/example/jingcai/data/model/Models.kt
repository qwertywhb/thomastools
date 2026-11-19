package com.example.jingcai.data.model

/** 比赛（来自 okooo 竞彩页）。 */
data class Match(
    val mid: Long,
    val order: String,
    val league: String,
    val time: String,
    val fullTime: String,
    val home: String,
    val away: String,
    val homeRank: String,
    val awayRank: String,
    val single: Boolean,
    val finished: Boolean,
    val handicap: String,
    val win: Double,
    val draw: Double,
    val lose: Double,
    val hwin: Double,
    val hdraw: Double,
    val hlose: Double,
    val score: String,
    /**
     * 联赛背景色（形如 `#FF6699`，**直接来自 okooo 页面 `a.saiming` 的 `background-color`**）。
     *
     * 🔴 一定不要自己编一套颜色：源站本来就给每个联赛配了固定底色（澳超深棕、德乙品红、
     *    荷甲粉…），自造色板等于把「认得出的联赛」变回「认不出的色块」。
     *    为空时才由 `LeagueColors` 兜底（老数据/字段缺失）。
     */
    val color: String = "",
    /**
     * 开赛时刻（epoch ms，由 [fullTime]「yyyy-MM-dd HH:mm」解析；解析失败为 0）。
     *
     * 🔴 为什么单独存而不是每次拿字符串比：比赛列表要对**每一行**判「是否已开赛」，
     *    几十上百行反复 `SimpleDateFormat.parse` 会明显掉帧；解析一次存 Long 最省。
     * ⚠️ 不能拿 [time]（HH:mm）配页面日期算：竞彩「销售日」与开赛日不是一回事，
     *    今晚 22:00 那一期的 04:00 场次实际是**次日凌晨**开赛，用 `date + time` 会早算一整天。
     */
    val kickoffMs: Long = 0L
) {
    /**
     * 是否**已开赛**（用户口径：按开赛时间卡，不依赖源站的 `data-end`）。
     *
     * 已完场一定算已开赛（源站标记兜底，防止个别场次 fullTime 缺失漏判）；
     * 否则看开赛时刻是否已到。
     */
    fun startedAt(nowMs: Long): Boolean =
        finished || (kickoffMs in 1 until nowMs)
}

/** 同奖查询的单场记录。 */
data class SameOddsMatch(
    val home: String,
    val away: String,
    val league: String,
    val matchDate: String,
    val h: String,
    val d: String,
    val a: String,
    val status: Int,
    val score: String
) {
    val bonusType: String get() = when (status) {
        1 -> "初始奖金"
        2 -> "变化奖金"
        3 -> "最终奖金"
        else -> ""
    }
}

/** 同奖查询整体结果。 */
data class SameOddsResult(
    val winNum: Int,
    val drawNum: Int,
    val loseNum: Int,
    val hRate: String,
    val dRate: String,
    val aRate: String,
    val matches: List<SameOddsMatch>
) {
    val total: Int get() = winNum + drawNum + loseNum
}

/** 固定奖金变动的一条记录。 */
data class FixedBonusRow(
    val updateTime: String,
    val h: String,
    val d: String,
    val a: String,
    val hf: Int,
    val df: Int,
    val af: Int
)

/** 赔率变化数据。 */
data class FixedBonusData(
    val home: String,
    val away: String,
    val league: String,
    val handicap: String,
    val had: List<FixedBonusRow>,
    val hhad: List<FixedBonusRow>
)

/**
 * 竞彩胜平负**赔率变动**的一条记录。
 *
 * 数据源：titan007 `cp.titan007.com/Handle/JcSp.aspx?oddsType=3&spid=<本场 spid>&typeID=105`
 * （就是竞彩页每行末尾那个「变」字弹窗）。**服务端按时间倒序**返回（最新在前），
 * 解析层统一翻正成**时间正序**，图表的横轴才会从左往右走。
 *
 * ⚠️ `typeID=105` = 胜平负；`typeID=101` = **让球**胜平负。两者 spid 相同、只有 typeID 不同，
 *    要错一个就会把「让球后的 4.45/3.60/1.57」当成胜平负，看起来像赔率突变。
 */
data class JcSpfChange(
    /** 变化时间，源站格式 `MM-dd HH:mm`（无年份，按今年补）。 */
    val time: String,
    val h: String,
    val d: String,
    val a: String
) {
    val hVal: Double get() = h.toDoubleOrNull() ?: 0.0
    val dVal: Double get() = d.toDoubleOrNull() ?: 0.0
    val aVal: Double get() = a.toDoubleOrNull() ?: 0.0
}

/** 联赛赛季。 */
data class LeagueSeason(val seasonId: Int, val seasonName: String)
/** 联赛。 */
data class LeagueInfo(
    val name: String,
    val uniformLeagueId: Long,
    val seasons: List<LeagueSeason>
)

/** 联赛分组（热门/各大洲）。 */
data class LeagueGroup(val name: String, val leagues: List<LeagueInfo>)

/** 赛程单场。 */
data class ScheduleMatch(
    val matchDate: String,
    val matchTime: String,
    val home: String,
    val away: String,
    val phase: String,
    val week: String,
    val score: String,
    val status: String
)

/** 按天分组的赛程。 */
data class ScheduleDay(val matchDate: String, val matches: List<ScheduleMatch>)

/** 积分榜一行。 */
data class StandingRow(
    val rank: String,
    val team: String,
    val played: Int,
    val win: Int,
    val draw: Int,
    val lose: Int,
    val goal: Int,
    val loss: Int,
    val net: Int,
    val points: String
)

/**
 * 竞彩赛程（体彩官方「竞彩足球赛程」）的一场比赛。
 *
 * 数据源：`getMatchListV1.qry?clientCode=3001`，与 [Match]（okooo 抓取）**不是同一个东西**：
 *  - [order] 是体彩官方编号「周六005」（接口字段 `matchNumStr`），列表按它升序排列；
 *  - [color] 是该联赛的官方配色（接口字段 `backColor`，6 位 hex 无 `#` 前缀），
 *    即页面上那个彩色联赛名块 —— **必须用接口给的颜色**，不要自己按联赛名映射一套，
 *    否则和官网不一致；
 *  - 该接口**一次返回多天**（实测 4 天：周五~周一），故外层按 [ScheduleDayGroup] 分组。
 */
data class JingcaiScheduleMatch(
    /** 体彩 matchId，用于后续跳详情（本页暂无跳转）。 */
    val matchId: Long,
    /** 官方编号，如「周六005」。 */
    val num: String,
    val league: String,
    /** 联赛名块的背景色，6 位 hex（无 `#`）。 */
    val color: String,
    val home: String,
    val away: String,
    /** 开赛日期 yyyy-MM-dd（注意可能与所属 businessDate 不同日：凌晨场次会跨到次日）。 */
    val date: String,
    /** 开赛时间 HH:mm。 */
    val time: String,
    /** 是否已停售/已截止（接口 matchStatus != "Selling"）。 */
    val ended: Boolean
) {
    /** 列表右侧展示用：`2026-10-17 18:00`。 */
    val dateTime: String get() = if (date.isEmpty()) time else "$date $time"
}

/** 竞彩赛程按「销售日」分组（周五 / 周六 / ...）。 */
data class ScheduleDayGroup(
    val businessDate: String,
    /** 中文星期，如「周五」。 */
    val weekday: String,
    val matches: List<JingcaiScheduleMatch>
)
