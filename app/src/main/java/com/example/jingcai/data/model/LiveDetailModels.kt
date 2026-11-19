package com.example.jingcai.data.model

/**
 * 「动画直播」详情页数据模型。
 *
 * 数据源（均为 titan007）：
 *  - 动画全量 `GET https://livestatic.titan007.com/flashdata/get?id=<titanId>`
 *  - 动画增量 `GET .../flashdata/get?chid=<titanId>`（约 1 秒轮询；线上走 WS `_cgf_<id>`，HTTP 为兜底）
 *  - 技术统计 `GET https://live.titan007.com/vbsxml/detailin.js?r=007` 的 `tT_f[<titanId>]`
 */

/** 动画场次头信息（来自 flashdata 第 0 段）。*/
data class FlashSchedule(
    val sId: Long,
    val homeTeamId: String,
    val guestTeamId: String,
    val homeScore: Int,
    val guestScore: Int,
    /** 0 未开始 / 1 上半场 / 2 中场 / 3 下半场 / 4 加时 / -1 完场。*/
    val state: Int,
    /** 进行分钟字符串（增量段给出，如 "47"）。*/
    val time: String = ""
)

/**
 * 动画球点（[x]/[y] 为 0..1 归一化坐标，对应源站 568×352 球场）。
 * [eventId] 用于把点归属到某次进攻事件（20/21/22）。
 */
data class FlashPoint(
    val pId: Int,
    val teamId: String,
    val x: Float,
    val y: Float,
    val eventId: String
)

/** 本场技术统计一行：`kind` 直接索引源站 `techKindNameList`（0 角球 / 4 射门 / 5 射正 / 10 任意球 / 11 控球率 / 15 犯规 / 16 越位）。*/
data class TechStat(
    val kind: Int,
    val name: String,
    val home: String,
    val guest: String,
    /** 主队条占比（0..100）。*/
    val homePre: Int,
    /** 客队条占比（0..100）。*/
    val guestPre: Int
)

/**
 * 技术统计的「时段」——源站 `techStatisticData` 的外层下标：
 * 0 = 全场、1 = 上半场、2 = 下半场（进行中的比赛只有 0 段，半场段要等打完才下发）。
 */
object TechSection {
    const val ALL = 0
    const val FIRST_HALF = 1
    const val SECOND_HALF = 2

    val LABELS = listOf("全场", "上半场", "下半场")
}

/**
 * 比赛事件（长按比分弹出的「事件时间轴」一行）。
 *
 * [kind] 与源站 `d_f` / 详情页事件表图标的编号**完全一致**，见 [EventKind]；
 * [side] 1 = 主队、2 = 客队（与 [TechStat] 无关，语义是「事件属于哪一方」）。
 */
data class MatchEvent(
    val minute: String,
    val side: Int,
    val kind: Int,
    val player: String = "",
    /** 换人 = 上场球员；进球 = 助攻球员（源站 HTML 侧写作「助攻:xxx」）。*/
    val player2: String = "",
    val memo: String = ""
)

/**
 * 球员身上的一条标记（首发/替补名单右侧的小图标）。
 *
 * 源站详情页把「进球 / 红黄牌 / 换人 / 助攻」直接挂在球员头像旁的 `<img title="66' 黄牌">` 上，
 * 一张 `img` 就是一条标记。[kind] 沿用 [EventKind] 的编号（红黄牌、入球与事件时间轴同源），
 * 换人和助攻另起 101+ 段，避免和源站事件编号撞车。
 */
data class PlayerMark(val minute: String, val kind: Int)

/** 首发/替补名单里的一个球员。[number] 是球衣号（源站可能为空串）。*/
data class LineupPlayer(
    val number: String,
    val name: String,
    val captain: Boolean = false,
    val marks: List<PlayerMark> = emptyList()
)

/** 一支球队的阵容。[formation] 形如 `4-2-3-1`，[coach] 是主教练姓名（源站可能为空）。*/
data class TeamLineup(
    val formation: String = "",
    val coach: String = "",
    val starters: List<LineupPlayer> = emptyList(),
    val subs: List<LineupPlayer> = emptyList()
) {
    val isEmpty: Boolean get() = starters.isEmpty() && subs.isEmpty()
}

/** 一场比赛的双侧阵容（详情页 `div#matchBox2`）。*/
data class MatchLineup(val home: TeamLineup, val away: TeamLineup) {
    val isEmpty: Boolean get() = home.isEmpty && away.isEmpty
}

/**
 * 伤停名单（详情页 `div.backupPlay.hurtPlay`，与首发/替补是同一个 `div#matchBox2` 里的兄弟块）。
 *
 * ## 为什么是「原始文本行」而不是结构化球员
 * 源站这块没有稳定的字段契约：无数据时只渲染一句「暂无数据」，有数据时把
 * 球员名与伤情说明直接拼在同一个块里，没有 `em.num` / `div.name` 那种可依赖的结构
 * （首发阵容那套有，所以 [LineupPlayer] 能结构化成球衣号 + 姓名）。
 * 与其猜一套字段然后在大联赛上印出错位的数据，不如**原样保留文本行**，
 * 展示层只负责排成两列 —— 源站改版时最多是文案不好看，不会出现「球员名错位」。
 *
 * 🔴 字段结构**尚未获得真实样本验证**：2026-11-10 落地时值周一白天，当天没有五大联赛场次，
 *    抓到的全是「暂无数据」。解析走的是**宽松文本提取**（优先 `li/tr/p`，退化为整块文本），
 *    空块一律返回 null，所以「拿不到」的情形不会在 UI 上留一块空白。
 */
data class MatchInjuries(
    val home: List<String> = emptyList(),
    val away: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = home.isEmpty() && away.isEmpty()
}

/** 名单标记类型；[EventKind] 覆盖事件类，换人/助攻是名单专属。*/
object LineupMark {
    const val SUB_ON = 101
    const val SUB_OFF = 102
    const val ASSIST = 103

    fun label(kind: Int): String = when (kind) {
        EventKind.GOAL -> "入球"
        EventKind.PENALTY -> "点球"
        EventKind.OWN_GOAL -> "乌龙"
        EventKind.RED -> "红牌"
        EventKind.YELLOW -> "黄牌"
        EventKind.SECOND_YELLOW -> "两黄变红"
        SUB_ON -> "换入"
        SUB_OFF -> "换出"
        ASSIST -> "助攻"
        else -> ""
    }
}

/** 事件类型编号（源站 `_detail.kindExplainList`）。*/
object EventKind {
    const val GOAL = 1
    const val RED = 2
    const val YELLOW = 3
    const val CANCEL_GOAL = 6
    const val PENALTY = 7
    const val OWN_GOAL = 8
    const val SECOND_YELLOW = 9
    const val SUBSTITUTION = 11
    const val MISS_PENALTY = 13
    const val VAR = 14

    /** 文案（标题/无障碍/兜底）。*/
    fun label(kind: Int): String = when (kind) {
        GOAL -> "入球"
        RED -> "红牌"
        YELLOW -> "黄牌"
        CANCEL_GOAL -> "入球无效"
        PENALTY -> "点球"
        OWN_GOAL -> "乌龙"
        SECOND_YELLOW -> "两黄变红"
        SUBSTITUTION -> "换人"
        MISS_PENALTY -> "射失点球"
        VAR -> "视频裁判"
        else -> ""
    }
}
