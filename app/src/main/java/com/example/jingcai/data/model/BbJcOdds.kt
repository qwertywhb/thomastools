package com.example.jingcai.data.model

/**
 * 竞彩篮球**单场三玩法赔率**（比分直播列表用，不参与结算）。
 *
 * ## 为什么单独建模型，不复用 [BetMatch]
 * [BetMatch] 是给**下单页**用的：它把线揉进 `label`（`让主胜(-8.50)`），并按「inline/expand」
 * 拆成两级。列表页要的是「一行三个玩法、每格一个数字」，直接读 `label` 再拆字符串太脆，
 * 所以这里按接口原始字段存，展示层自己拼文案。
 *
 * ## 🔴 主客队口径
 * 体彩的 `homeTeam/awayTeam` 就是**真主客**。比分源（球米宝）2026-11-16 之前的解析
 * 把数据源的「客 @ 主」排版当成了「主 @ 客」，于是看起来像「两家相反」——
 * 那其实是**我们的解析反了**，已在 [BbLiveParser] 修正（ESPN 官方 8/8 核对通过）。
 *
 * 所以 [flippedFor] 现在**正常情况下恒为 false**：两边都是真主客，不需要翻。
 * 保留它纯粹是兜底 —— 数据源哪天再改顺序、或译名导致主客错位时，至少还有一层防错。
 */
data class BbJcOdds(
    /** 竞彩编号（`周一302`），与比分列表的 `BbLiveMatch.jcNo` 同格式，作为唯一键。 */
    val num: String,
    val homeTeam: String,
    val awayTeam: String,
    /** 让分盘口线，**主队视角**（`-8.50` 表示主队让 8.5 分）。空串 = 未开售。 */
    val hdcLine: String,
    val hdcHome: BbOddSide,
    val hdcAway: BbOddSide,
    /** 大小分总分线（接口给 `+228.50`，展示时去掉正号）。 */
    val hiloLine: String,
    val hiloOver: BbOddSide,
    val hiloUnder: BbOddSide,
    val mnlHome: BbOddSide,
    val mnlAway: BbOddSide
) {
    /** 三个玩法全都没有可售赔率 ⇒ 整块不渲染，别占版面。 */
    val any: Boolean get() = hdcHome.sellable || hiloOver.sellable || mnlHome.sellable

    /** 客队让分线＝主队线的相反数（同盘口另一侧）。 */
    val hdcLineAway: String get() = flipLine(hdcLine)

    /**
     * 比分直播那一行是否**要把主客翻过来**再展示赔率。
     *
     * 只在「体彩主队 == 直播客队」且「体彩客队 == 直播主队」时才翻；队名对不上
     * （译名差异、数据缺失）一律按不翻处理 —— 宁可标签保守，也不要把让分给错队。
     *
     * 🔴 解析层修正后两边主客已同源，此函数**应恒为 false**，仅作兜底（见类注释）。
     */
    fun flippedFor(rowHome: String, rowAway: String): Boolean =
        homeTeam.isNotBlank() && awayTeam.isNotBlank() &&
            homeTeam == rowAway && awayTeam == rowHome

    companion object {
        /** `-8.50` ⇔ `+8.50`；空串/纯数字原样返回。 */
        fun flipLine(line: String): String {
            val t = line.trim()
            return when {
                t.isEmpty() -> t
                t.startsWith("-") -> "+" + t.substring(1)
                t.startsWith("+") -> "-" + t.substring(1)
                else -> t
            }
        }
    }
}

/** 单个选项的赔率 + 涨跌。 */
data class BbOddSide(
    val odds: Double,
    /** 1 涨（红）/ -1 跌（绿）/ 0 无。取自接口独立字段 `hf/af/lf`，不是自己算差值。 */
    val trend: Int = 0
) {
    val sellable: Boolean get() = odds > 0
}
