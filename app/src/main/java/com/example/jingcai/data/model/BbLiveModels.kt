package com.example.jingcai.data.model

/**
 * 「比分直播 → 篮球」的一场比赛（数据源：`matchs.qiumibao.com/live/all.htm`，
 * 与 https://www.188bifen.com/lanqiubifen.htm 篮球页**同源同结构**）。
 *
 * 🔴 源数据是一个 38 元素的**数组**（不是对象），字段含义由 188bifen 前端 JS 反推确认
 * （`static4style.duoduocdn.com/.../lanqiubifen.v1.*.js` 的 `formatData`）：
 *
 * ```
 * [0]  比赛 id          2139787
 * [1]  联赛 id          924 → leagues["924"] = ["NBA", …, "#6BD7FB", 1]
 * [2]  状态码           0 异常 / 1 未开始 / 2~7 进行中（第1~4节、加时）/ 8 完 / 9 推迟
 * [3]  开赛时间戳（秒）
 * [5]  **第一支**球队 [id, 名称, 总分, 半场分, …, 让分文本, 队徽路径]（NBA=客 / 欧洲联赛=主，见下）
 * [6]  **第二支**球队 同上（[8] 位置是让分文本，如 "(-6.5)"）
 * [10] 竞彩编号数组   ["周三301", "北119", ""] —— [0] 非空才是竞彩场次
 * [16] 详情页路径     "/zhibo/nba/2026/match2139787vl.htm"（zhibo8 页面，末尾多一个 l）
 * [17] 节次文案       通常为空，进行中时可能是 "第3节 05:12"
 * [19] 球类            1 足球 / 2 篮球 / 3 电竞 ⇒ **篮球过滤靠它**（不能用队徽路径猜）
 * [20] 分节比分       "一:23-25二:33-26三:37-39四:23-38"（**每节也是先客后主**，与 [5]/[6] 同序）
 * ```
 *
 * ⚠️ 常见误判：`[17]` 不是状态（完场场次也可能为空）、`[19]` 不是状态码而是球类。
 * 状态一律用 **[2]**，与站点 `statusArr = ["比赛异常","未","进行中"×6,"完","推迟"]` 对齐。
 *
 * 🔴🔴 **篮球的队位顺序不统一，别一刀切**：
 *  - NBA / CBA 等：源站沿用英文站「Away @ Home」排版，`[5]`=客、`[6]`=主
 *    （2026-11-16 用 ESPN 官方 scoreboard 逐场核对 8/8）；
 *  - 欧洲联赛（欧篮联 / 欧俱杯…）：源站是「主 @ 客」，`[5]`=**主**、`[6]`=客
 *    （2026-11-18 用 EuroLeague 官方赛程 + 场馆核对 4/4：迪拜 80:75 埃菲斯、
 *    巴斯克尼亚 82:85 夏普尔、马卡比 95:97 奥林匹亚科斯、萨拉基利斯 117:114 拜仁）。
 *
 * 所以**解析层不判主客，只保证「[5] 是数组里第一支、[6] 是第二支」**；真正的主客校正发生在
 * [com.example.jingcai.ui.BbLiveViewModel] 的 `alignToJc()`，两条判据按优先级：
 *  1. **[hostOnFirst]**：源站自己的让分标注挂在哪一侧（11/11 核对命中，最可靠）；
 *  2. 标注缺失（未开售）时才用**体彩赔率接口的队名**兜底。
 * 按联赛名硬编码「欧洲就翻」是错的 —— 联赛有几百个，改不完。
 *
 * 搞反的代价是**队名主客颠倒 + 让分线挂错队**（让分线是主队视角），
 * 结算侧 [com.example.jingcai.data.settle.BasketScore.alignedTo] 用的是同一套队名判据。
 */
data class BbLiveMatch(
    val matchId: Long,
    /** 竞彩编号（如「周三301」）。空串 = 非竞彩场次（列表里不会出现）。 */
    val jcNo: String,
    val league: String,
    /** 联赛底色，取自源站 `leagues[id][5]`（形如 `#6BD7FB`）。 */
    val color: String,
    /** 开赛时间戳（秒）。 */
    val startTs: Long,
    /** `HH:mm`（列表「时间」列）。 */
    val timeText: String,
    /** `yyyy-MM-dd`，分组用。 */
    val dateKey: String,
    /** `10-22 周三`，分组标题用。 */
    val dateLabel: String,
    val home: String,
    val away: String,
    /** 当前总分（未开始为 0）。 */
    val homeScore: Int,
    val awayScore: Int,
    /** 半场得分（主队 `[6][3]` / 客队 `[5][3]`）。 */
    val homeHalf: Int,
    val awayHalf: Int,
    /**
     * 每节得分（主客一一对应，来自 `[20]`；增量来自 `[12]`）。
     *
     * 🔴 源串列序是「客-主」，**解析层已对调**，这里 `home*` 恒为主队、`away*` 恒为客队。
     *    2026-11-17 前解析层按「先主后客」取，导致队名/总分正确但每节比分互换。
     */
    val homeQuarters: List<Int>,
    val awayQuarters: List<Int>,
    /** 状态码：0 异常 / 1 未开始 / 2~7 进行中 / 8 完 / 9 推迟。 */
    val status: Int,
    /** 源站节次文案（`[17]`），常为空。 */
    val period: String,
    /** 详情页路径（`[16]`，`/zhibo/nba/2026/match2139787vl.htm`）。 */
    val detailPath: String,
    /**
     * 主 / 客队**联赛排名**（队名数组 `[7]`，纯名次如 `3`；空串＝源站没给）。
     *
     * 🔴 篮球源站（qiumibao）实测**这个位置全是空串**，排名只有足球行才有
     *    （足球 `[7]` 是 FIFA 世界排名：巴西 6、日本 17、中国 95）。
     *    这里照样解析，是为了「源站哪天给了就能直接显示」，UI 侧判空即不画。
     *    想要真实篮球排名（东1/西3）必须另接一个积分榜数据源，不是这个接口能给的。
     */
    val homeRank: String = "",
    val awayRank: String = "",
    /**
     * 源站把**主队**放在了数组的哪一支：`true` = 第一支（`[5]`），`false` = 第二支（`[6]`），
     * `null` = 该场没有让分标注，源站没暴露这条信息。
     *
     * 🔴 判据是源站自己的让分标注 `[5][8]` / `[6][8]`（形如 `(-7.5)` / `(+2.5)`）：
     *    **有标注的那一侧就是主队**。2026-11-18 用 11 场逐场核对体彩主队（NBA 5 场 +
     *    欧篮联 6 场），**11/11 命中**：
     *    ```
     *    周二301 欧篮联 [5]迪拜  '(-7.5)'   [6]埃菲斯      ''     体彩主=迪拜
     *    周二303 欧篮联 [5]米兰  '(+3.5)'   [6]贝红星      ''     体彩主=米兰
     *    周二307 NBA    [5]尼克斯 ''        [6]猛龙       '(+2.5)' 体彩主=猛龙
     *    ```
     *    这正是「NBA 正常、欧洲联赛主客反了」的根因 —— 两边的队位惯例不同，
     *    而标注位置把答案直接告诉我们，**完全不用猜译名**（体彩给「贝红星/巴萨/贝游击」
     *    这种极简简称，跟源站的「贝尔格莱德红星/巴塞罗那」做子串匹配必然失败）。
     */
    val hostOnFirst: Boolean? = null
) {
    /** 已开赛（有比分可显示）。 */
    val started: Boolean get() = status in 2..8
    val isLive: Boolean get() = status in 2..7

    val isFinished: Boolean get() = status == 8

    /** 状态列文案（未开始留空——时间列已经说明了）。 */
    val statusText: String
        get() = when {
            period.isNotBlank() -> period
            status == 8 -> "完赛"
            status == 9 -> "推迟"
            status == 0 -> "异常"
            status in 2..7 -> "第${status - 1}节"
            else -> ""
        }

    /**
     * 主客队对调（队名 / 总分 / 半场 / 分节 / 排名一起换）。
     *
     * 🔴 只在「源站把主队放在 `[5]`」的场次上用 —— 欧洲联赛（欧篮联、欧俱杯…）就是如此，
     *    与 NBA 的「客 @ 主」相反，见 [com.example.jingcai.ui.BbLiveViewModel] 的
     *    `alignToJc()`：用**体彩队名**判定该场到底要不要换，绝不按联赛名硬编码。
     *
     * 只换「哪一侧」不换「数值本身」：`homeQuarters` 与 `homeScore` 必须成对搬走，
     * 否则会出现「队名换了、每节比分没换」这种最难发现的错位。
     */
    fun swapped(): BbLiveMatch = copy(
        home = away,
        away = home,
        homeScore = awayScore,
        awayScore = homeScore,
        homeHalf = awayHalf,
        awayHalf = homeHalf,
        homeQuarters = awayQuarters,
        awayQuarters = homeQuarters,
        homeRank = awayRank,
        awayRank = homeRank
    )

    /** 排序权重：进行中 → 未开始 → 完赛 → 推迟/异常（与 188bifen 的 statusArr 排序一致）。 */
    val sortRank: Int
        get() = when {
            isLive -> 0
            status == 1 -> 1
            isFinished -> 2
            else -> 3
        }

    /**
     * zhibo8 直播详情页地址（**移动版**）。
     *
     * 源站给的 `[16]` 就是移动版路径：`/zhibo/nba/2026/match2139787vl.htm`
     * ⇒ `https://m.zhibo8.com/zhibo/nba/2026/match2139787vl.htm`（200，响应式，直接用 WebView 打开即可）。
     *
     * ⚠️ 别再拼桌面站 `www.zhibo8.com`：那边的同 id 地址要去掉结尾的 `l`（`match2139787v.htm`）才 200，
     *    而且整页是 1020px 桌面布局，得靠注入 CSS 硬改才能看（上一版就是这么干的，又脆又难看）。
     */
    val zhibo8Url: String
        get() = if (detailPath.isEmpty()) "" else "https://m.zhibo8.com$detailPath"
}

/** 「比分直播 → 篮球」按日期分组（标题形如 `10-22 周三`）。 */
data class BbDayGroup(
    val dateLabel: String,
    val matches: List<BbLiveMatch>
)

/**
 * 列表分区。**已结束的比赛要沉到最下面**（用户要求，也与 m.188bifen 篮球页一致：
 * 未开始/进行中 → 「已结束」分隔 → 完赛各区 → 延期/异常）。
 *
 * [title] 非空时 UI 会在该分区最前面插一条分隔标题（目前只有 `已结束` / `延期·异常`）。
 */
data class BbSection(
    val title: String,
    val groups: List<BbDayGroup>
)

/**
 * 分区标题（`已结束` / `延期·异常`）在 LazyColumn 里的 key。
 *
 * 🔴 **必须带分区序号 [si]**：同一天会同时出现在两个分区里（上午打完的进「已结束」、
 *    晚上还没打的留在「进行中」），只用标题/日期做 key 会生成两条一模一样的 key，
 *    Compose 抛 `IllegalArgumentException: Key "..." was already used` ⇒ **整个页面闪退**
 *    （2026-11-03 真实事故，见 [bbDayItemKey] 注释）。
 */
fun bbSectionItemKey(si: Int, section: BbSection): String = "bbsec-$si-${section.title}"

/**
 * 日期分组（`11-03 周二`）在 LazyColumn 里的 key。
 *
 * 🔴 **key 里必须含分区序号 [si]**。曾经写成 `"bbhdr-${'$'}{group.dateLabel}"`，而
 *    `buildSections()` 会把同一天拆进「进行中」和「已结束」两个分区 ⇒ 同一个
 *    `11-03 周二` 出现两次 ⇒ LazyColumn 直接抛异常，用户一点篮球 tab 就闪退。
 *
 * ⚠️ 不要再退回「只用 dateLabel」：同分区内 dateLabel 由 `groupBy(dateKey)` 保证唯一，
 *    跨分区**不**唯一。加 `si` 就够了，不要用「全局自增序号」当 key —— 那样每次刷新
 *    场次增减都会让整个列表重组、滚动位置乱跳。
 */
fun bbDayItemKey(si: Int, group: BbDayGroup): String = "bbhdr-$si-${group.dateLabel}"

/**
 * 增量补丁（数据源：`matchc.qiumibao.com/live/YYYY/MM/DD/<version>.htm`）。
 *
 * 🔴 增量行的**数组布局与全量行不同**（少了一大截），字段映射由 188bifen 前端
 * `updateData()` 反推确认：
 *
 * ```
 * 全量 a  ←  增量 t
 * a[2]  = t[1]    状态码
 * a[4]  = t[2]    时间戳
 * a[5]  = t[3]    **客**队数组（含总分/半场分）
 * a[6]  = t[4]    **主**队数组
 * a[8]  = t[5]    情报
 * a[17] = t[6]    节次文案
 * a[20] = t[12]   分节比分
 * ```
 *
 * 即：**绝不能把增量行当全量行解析**（t[3] 是全量里的"0"，t[5] 是全量里的主队数组）。
 */
data class BbPatch(
    val matchId: Long,
    val status: Int,
    val homeScore: Int,
    val awayScore: Int,
    val homeHalf: Int,
    val awayHalf: Int,
    val period: String,
    val homeQuarters: List<Int>,
    val awayQuarters: List<Int>
)
