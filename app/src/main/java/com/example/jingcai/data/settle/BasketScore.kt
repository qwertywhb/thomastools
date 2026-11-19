package com.example.jingcai.data.settle

/**
 * 篮球某一场的赛果 + **数据源自己的主客队名**。
 *
 * ## 🔴 为什么一定要带上队名（用户报障：「有些主客队搞反了，让分开奖错了」）
 * 结算用的比分来自球米宝（`matchs.qiumibao.com`），而投注单里的 `home/away` 来自**体彩**。
 * 当时实测（2026-11-02 周一 16 场竞彩篮球）两者主客**看起来相反**：
 *
 * | 竞彩编号 | 体彩（下单页） | 球米宝（比分源） | 结论 |
 * |---|---|---|---|
 * | 周一304 | 76人 **VS 公牛** | **公牛** 107 : 126 **76人** | 看起来相反 |
 * | 周一305 | 步行者 **VS 老鹰** | **老鹰** 108 : 104 **步行者** | 看起来相反 |
 *
 * ⚠️ **根本原因已在 2026-11-16 查清并修正**：不是两家口径不同，而是
 * [com.example.jingcai.data.network.BbLiveParser] 把源站的「客 @ 主」排版
 * 解析成了「主 @ 客」（ESPN 官方 8/8 核对）。解析修正后 `sourceHome` 已是真主队，
 * 本类的 [alignedTo] **正常应恒等（不翻）**，保留它只作兜底：数据源再改顺序时仍能自愈。
 *
 * 不能按联赛硬编码「NBA 就翻、NBL 不翻」——按**队名**判方向是唯一不依赖联赛表的做法，
 * 也和赔率展示那边（`BbJcOdds.flippedFor`）保持同一套口径。
 */
data class BasketScore(
    /** 数据源（球米宝）定义的主队名。 */
    val sourceHome: String,
    /** 数据源（球米宝）定义的客队名。 */
    val sourceAway: String,
    /** 数据源口径的比分。 */
    val score: MatchScore,
    /**
     * 主客**已经用源站自己的标注摆正**（[com.example.jingcai.data.model.BbLiveMatch.hostOnFirst]
     * 非 null）。为 true 时 [alignedTo] **不再做队名匹配**，直接返回 [score]。
     *
     * ## 🔴 为什么必须加这个开关（2026-11-19 排查出的真 bug）
     * 欧洲联赛体彩用**极简简称**（`巴萨` / `贝游击` / `贝红星`），源站用全称
     * （`巴塞罗那` / `贝尔格莱德游击` / `贝尔格莱德红星`）。两者**既不是子串、字符集也不
     * 互相覆盖**（`贝游击` 三个字在 `贝尔格莱德游击` 里不连续），所以队名判据必然返回
     * 「不翻」。而这类场次恰恰是源站「主 @ 客」（需要翻）的 —— 于是
     * `BetCalculator` 的 `d = fullHome + line - fullAway` 拿客队分当主队分，
     * **让分 / 胜负方向整反**（中奖单被判死）。
     *
     * 实测 2026-11-18 全量 21 场有标注场次，**2 场**中招：
     * ```
     * 周二304 源站 巴黎 : 巴塞罗那   体彩主=巴萨    应翻 → 队名判据不翻  ❌
     * 周二305 源站 巴伦西亚 : 贝尔格莱德游击  体彩主=贝游击  应翻 → 不翻  ❌
     * ```
     * 显示侧（`BbLiveViewModel.alignToJc`）早就优先用 `hostOnFirst` 了，只有结算侧还在
     * 吃队名 —— 这就是「比分直播显示对了、开奖却反了」的根因。
     */
    val hostKnown: Boolean = false
) {

    /**
     * 把比分摆成「`home` 为全场主队」的口径，再交给结算。
     *
     * 判定顺序（**只有一种情况翻，其余一律不翻**）：
     * 1. 数据源的客队才是单据里的主队、且数据源的主队不是它 ⇒ 两家主客相反 ⇒ 翻；
     * 2. 其它一切（完全一致 / 队名对不上 / 两边都能对上）⇒ 原样返回。
     *
     * 🔴 规则写成「只认一种翻法」而不是四路组合判断：四路判断在译名有全称简称差异时
     *    会出现「两边都匹配」的矛盾局面，一旦判错就是把中奖单判死，代价不可逆。
     *    这里宁可漏翻（返回原样，最坏也只是和旧版一样错），也不要在不确定时瞎翻。
     */
    fun alignedTo(home: String, away: String): MatchScore {
        // 源站标注已经定了主客（hostOnFirst），[score] 构造时就是体彩口径 ⇒ 不需要也不该再猜
        if (hostKnown) return score
        val h = home.trim()
        val a = away.trim()
        if (h.isEmpty() || a.isEmpty()) return score
        val awayIsMyHome = nameEq(sourceAway, h)
        val homeIsMyHome = nameEq(sourceHome, h)
        return if (awayIsMyHome && !homeIsMyHome) score.flipped() else score
    }

    /**
     * 队名是否同一支。
     *
     * 允许「双向包含」：源站可能给全称（`达拉斯独行侠`）而体彩给简称（`独行侠`），
     * 这类差异在足球那边是常态；篮球实测两边都是简称且完全一致，包含只作为兜底。
     */
    private fun nameEq(x: String, y: String): Boolean {
        val p = x.trim()
        val q = y.trim()
        if (p.isEmpty() || q.isEmpty()) return false
        return p == q || p.contains(q) || q.contains(p)
    }
}

/**
 * 主客互换（含半场）。
 *
 * 篮球没有「半全场」玩法，半场分只是顺带换算，换过去不影响判定；
 * 但**必须一起换**，否则将来加半场玩法时会静默错位。
 */
fun MatchScore.flipped(): MatchScore = copy(
    fullHome = fullAway,
    fullAway = fullHome,
    halfHome = halfAway,
    halfAway = halfHome
)
