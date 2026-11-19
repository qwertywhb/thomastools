package com.example.jingcai.data.model

/**
 * 「比分直播」页的一场比赛（数据源：`cp.titan007.com/buy/JingCai.aspx` + `data/dx101.xml`）。
 *
 * 字段与页面列一一对应，取自实测确认的契约：
 *  - [titanId]：titan007 内部比赛 id（来自 `tr` 内 `a#HomeTeam_{titanId}`），仅用于把内联脚本
 *    `M` 数组里的排名关联回本行；
 *  - [matchId]：**竞彩编号**（`tr@matchid`，如 `5555001`），是关联小/大球（dx101）与胜平负赔率
 *    （`sp_{matchId}_{n}`）的 key；
 *  - [num]：行首 3 位编号（如 `001`，**不带**「周五」前缀）；
 *  - [color]：联赛名块背景色，取自源站 `background-color`（**带 `#` 前缀**，如 `#FF7000`）；
 *  - [score]：比分文本，未开始为 `-`，完场如 `3-2`；
 *  - 大小球三值 [ouHome] / [ouGoal] / [ouAway] 来自 dx101（公司 3），缺数据时统一为 `-`；
 *  - 胜平负 [spfH]/[spfD]/[spfA] 与让球胜平负 [rqH]/[rqD]/[rqA] 来自 `spfItem` 内层表；
 *  - [handicap]：让球数（`tr@polygoal`，如 `-1` / `+1`），UI 按正负定色。
 *  - [state]：比赛状态（`change.xml` 的 `D[1]`）：`0`=未开始、`-1`=完场、`1~4`=进行中。
 *  - [elapsedMin]：进行中已进行的分钟数（`change.xml` 的 `D[8]` 形如 `10:10` → 10 分钟），
 *    仅当 [state] 为进行中时有意义；UI 据此在状态格显示「进行 67'」。
 */
data class LiveMatch(
    val titanId: Long,
    /** UI 唯一 key（= titanId 字符串）。区别于 [jcNum]（竞彩编号，仅竞彩场次有）。 */
    val matchId: String,
    val num: String,
    val league: String,
    val color: String,
    /** 联赛 ID（bfdata `A[i][45]`），热门过滤用。 */
    val sclassId: String = "",
    val time: String,
    val home: String,
    val away: String,
    /**
     * 主队 / 客队**联赛排名**（bfdata `A[i][22]` / `[23]`，纯名次如 `9`；空串＝源站没给）。
     *
     * 源站原始值可能是「阶段/分区 + 名次」：`秋11`（秋季赛 11 名）、`北3`（北区 3）、
     * `埃及超2`。解析时只取**末尾数字**（见 [com.example.jingcai.data.network.TitanLiveParser]）——
     * 前缀对扫读无用，还会把本就只有 60dp 的队名区挤掉一个字。
     */
    val homeRank: String,
    val awayRank: String,
    val score: String,
    val ouHome: String,
    val ouGoal: String,
    val ouAway: String,
    /** 进行中分钟数（由 bfdata [12] 半场开始时刻 + 服务器校正时间算出，仅进行中有意义；change.xml 不带时钟）。 */
    val elapsedMin: Int = 0,
    /** bfdata [12] 当前半场开始时刻（`Y,M,D,h,mi,s`，JS 0 起月份）。state 变化时用它重算 [elapsedMin]。 */
    val halfStart: String = "",
    /**
     * 比赛状态（bfdata `A[i][13]` / change.xml `D[1]`）：
     * 0 未开始 / 1~3 进行中（上、中、下） / -1 完场 / 其它负数=延期腰斩等。
     */
    val state: Int = 0,
    val spfH: String,
    val spfD: String,
    val spfA: String,
    val handicap: String,
    val rqH: String,
    val rqD: String,
    val rqA: String,
    /** 隔行底色（保留字段，当前全 false）。 */
    val striped: Boolean,
    /**
     * 竞彩编号（cp.titan007 `JingCai.aspx` 的 `tr@matchid`）。仅竞彩场次非空，用于 dx101 大小球关联。
     */
    val jcNum: String = "",
    /**
     * 竞彩**官方编号**「周一007」（竞彩页 `tr@name` = 「周一」，首格编号 = 「007」）。
     *
     * 与 [jcNum]（站点内部 matchid「5572007」）不是一回事：给用户看的一律用本字段。
     */
    val jcOrder: String = "",
    /**
     * 本场在 titan007 竞彩的**赔率变动窗口 id**（`JingCai.aspx` 每行「变」字链接里的 `spid`）。
     *
     * `spid` 是**一场一个**（胜平负与让球共用同一个 spid，靠 `typeID` 区分池），
     * 拿到它就能查该场竞彩胜平负的完整变动记录，见 `LiveScoreRepository.fetchSpfHistory`。
     * 空串 = 该场没有变动记录入口（页面没渲染「变」链接），UI 不画变动图标。
     */
    val spid: String = "",
    val swap: Boolean = false,
    /**
     * 进球闪色：最近一次进球的一侧。`0`=无、`1`=主队进球、`2`=客队进球。
     * 参照 live.titan007.com：进球方队名底色变橄榄黄绿（#BBBB22）持续 30 秒。
     */
    val flashSide: Int = 0,
    /** 进球闪色的起始时刻（epoch ms）；UI 据此判断是否仍在 30 秒窗口内。 */
    val flashAt: Long = 0L,
    /**
     * 本次闪色是**进球被取消**（VAR 改判 / 进球取消）而不是进球。
     *
     * 比分回退（主或客进球数变少）时置 true，[flashSide] 指向「被扣掉进球」的那一侧，
     * UI 用另一套底色（浅玫红）区分「刚进球」和「进球被取消」。
     * 窗口结束（30 秒）后随 [flashAt] 一起失效，无需单独清理。
     */
    val flashCancel: Boolean = false,
    /**
     * 官方举牌补时（第四官员宣布的 Stoppage 分钟数），来自**手机版 titan007** 的 `livechange.txt`
     * （`!` 分隔多场、`^` 分隔字段的实时变更流，字段 [21] = `injuryTime`，对应 `ScoreWs.js` 的
     * `QtliveChange.injuryTime = arrTr[21]`）。
     *  - **空串 = 尚未宣布补时**（绝大多数时刻如此，含「补时还没出来」的情况）；
     *  - 非空（如 `"4"`）= 已宣布，UI 时间列据此显示红粗「补4'」。
     *  ⚠️ 这是**唯一**正确的补时来源；bfdata 的 `[22]/[23]` 实测是**联赛排名/组别**（如「秋1」「春6」），
     *    既不是补时也不是射门/角球——之前两次误用它们导致「补时没出来却显示 13'」「角球取不到值」。
     *  ⚠️ 仅在 [state] ∈ {1,3}（上/下半场进行中）时参与显示；完场/中场/未开始一律回退时间列显示开赛时间。
     */
    val injuryTime: String = "",
    /**
     * 半场比分（bfdata `A[i][16]`/`[17]`：主/客半场进球数，整数）。
     * 仅在 [state] ≥ 2（中场/下半场/加时）或 -1（完场）时于 UI 显示为「比分」下方小字「(半 H-A)」。
     * change.xml 不携带半场分，增量更新靠 [applyChanges] 的 `copy` 保留此值（不会被清 0）。
     */
    val homeHalf: Int = 0,
    val awayHalf: Int = 0,
    /**
     * 黄牌数（bfdata `A[i][20]`/`[21]`，增量 `change.xml` 的 `D[12]`/`[13]`）。
     *
     * 0 = 没有牌，UI **不画角标**（参照 live.titan007.com：只有 >0 才显示小方块）。
     * 单位是「张」，不是「有人被罚」——同一个球员两黄变红时，源站黄牌仍计 2 张、红牌另计 1 张。
     */
    val homeYellow: Int = 0,
    val awayYellow: Int = 0,
    /**
     * 红牌数（bfdata `A[i][18]`/`[19]`，增量 `change.xml` 的 `D[6]`/`[7]`）。
     *
     * ⚠️ 字段索引是从源站 `jcLive.js` 里**反推**出来的（`if (D[6] != A[matchindex][18])` 之后
     * 往 `redcard1_<id>` 里塞 `redcard{N}.gif`；黄牌同理对应 `D[12]/[13]` ↔ `A[i][20]/[21]`）。
     * 不要凭「看起来像 0」猜，这套索引是唯一能让数字和源站对上的。
     */
    val homeRed: Int = 0,
    val awayRed: Int = 0,
    /**
     * 角球数（主 / 客）。来源：`livestatic.titan007.com/vbsxml/detail_ut.js` 的 `tc` 数组。
     *
     * 🔴 **-1 = 无数据**，不是「0 个角球」：未开赛、以及源站还没出技统的场次都没有值。
     *    归一成 0 会在所有未开赛行上显示「角球 0-0」，像真的一样。
     * 0 是合法值（已开赛但一脚角球都没有），必须与 -1 区分开。
     *
     * ⚠️ bfdata 的 `A[i][22]/[23]` **不是**角球（实测是联赛排名/组别，如「秋1」「春6」），
     *    老注释里把它当「比赛统计」是错的。
     */
    val homeCorner: Int = -1,
    val awayCorner: Int = -1,
    /** 半场角球（主 / 客），同上：-1 = 无数据；仅在 `state >= 2` 或完场后有意义。 */
    val homeHalfCorner: Int = -1,
    val awayHalfCorner: Int = -1
)

/**
 * 「比分直播」页按日期分组。
 *
 * 注意：**顺序严格保持源站顺序**（源站会把销售期遗留的场次放到最后，如把 10-15 排在 10-18 之后），
 * 不按日期重排 —— 只有保持源顺序才能与参考页面逐行对齐。
 */
data class LiveDayGroup(
    val businessDate: String,
    /** 中文星期，如「星期五」。 */
    val weekday: String,
    val matches: List<LiveMatch>
)

/**
 * 「比分直播」全量解析结果（数据源：`livestatic.titan007.com/vbsxml/bfdata_ut.js`）。
 *
 *  - [matches]：全部比赛（完整 tab 用）；
 *  - [hotSclassIds]：热门联赛的 sclassID 集合（`bfdata_ut.js` 的 `B[i][10] != 0`），热门 tab 用；
 *  - [jcIds]：竞彩场次的 titanId 集合（`JingCai.aspx` 的 `HomeTeam_/GuestTeam_` 后缀），竞彩 tab 用。
 */
data class RawLive(
    val matches: List<LiveMatch>,
    val hotSclassIds: Set<String>,
    val jcIds: Set<String>
)

/**
 * `change.xml`（`cp.titan007.com/info/match/data/change.xml`）里的一条增量。
 *
 * 源站 `jcLive.js` 用 `text.split("^")` 解析 CDATA，取 `D[0]`=titan matchId、`D[1]`=state、
 * `D[2]`=主队进球、`D[3]`=客队进球。`state`：`0`=未开始、`-1`=完场、`1/2/3/4`=进行中。
 * ⚠️ 实测 `D[8]` 是**开赛时间 HH:mm**（如 `13:30`），**不是**进行时钟 —— 进行分钟数一律由
 * bfdata 的 `[12]`（半场开始时刻）现算，见 [com.example.jingcai.data.network.TitanLiveParser]。
 */
data class LiveChange(
    val titanId: String,
    val state: Int,
    val home: Int,
    val away: Int,
    /**
     * 红黄牌增量（`D[6]`=主红、`D[7]`=客红、`D[12]`=主黄、`D[13]`=客黄）。
     *
     * 🔴 **-1 = 本条增量没带这个字段**（源站某些行该位是空串），不是「0 张」。
     *    apply 时只覆盖 >= 0 的值，否则会把已经吃到的红黄牌误清成 0 —— 这类「静默清零」
     *    在比分直播里最难被发现（牌突然消失，用户以为数据源抽风）。
     */
    val homeRed: Int = -1,
    val awayRed: Int = -1,
    val homeYellow: Int = -1,
    val awayYellow: Int = -1
)
