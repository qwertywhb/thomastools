package com.example.jingcai.data.settle

import com.example.jingcai.data.model.BetTicket

/**
 * 过关方式（自由过关）的**展示口径**。
 *
 * ## 🔴 只写关次清单，不写竞彩名（用户 2026-11-06 二次定稿）
 * 用户勾关次时看到的就是「单关 / 3x1 / 4x1 / 5x1」这几个胶囊，
 * 所以记录、账本、详情、复制文案必须**逐字一致**地写成 `3x1,4x1,5x1`。
 *
 * 演变（别改回去）：
 *  1. 最初只取最高关次 ⇒ `3x1,4x1,5x1` 被压成 `5x1`，用户以为只买了一注；
 *  2. 先改成竞彩官方名 `5串16`（3串1+4串1+5串1 共 16 注），用户当场否掉：
 *     「不要显示成 5x16，就按 3x1,4x1,5x1 显示」——`n串N` 里的 N 是**注数**，
 *     和用户脑子里「我勾了三档关次」对不上；
 *  3. 现口径：名字 = 关次清单，注数由 [detailOf] 单独给 `共 16 注`。
 *
 * 单关写 `单关`，不写 `1x1`（没人这么说）。
 */
object Parlay {

    /** 最多到几串：再多注数就爆炸，实盘里也几乎没人打。 */
    const val MAX_LEG = 6

    /** 给 UI 的候选关次：`[1, 2, …, min(场次数, MAX_LEG)]`；没有场次时为空。 */
    fun options(matchCount: Int): List<Int> =
        if (matchCount <= 0) emptyList() else (1..minOf(matchCount, MAX_LEG)).toList()

    /**
     * 关次清单，用户视角的写法：`3x1,4x1,5x1`；只有单关时写 `单关`。
     *
     * 🔴 分隔符是**半角逗号**、关次写法是 `Mx1`：与投注页胶囊（`"${m}串1"` 之外的另一套
     * 用户语言）以及复制文本保持一致，别再引入 `+`、`串`、`x` 混用。
     */
    fun legsText(ms: List<Int>): String {
        val list = ms.filter { it >= 1 }.distinct().sorted()
        if (list.isEmpty()) return "单关"
        return list.joinToString(",") { if (it <= 1) "单关" else "${it}x1" }
    }

    /**
     * 该单的**总注数**（复式展开、自由过关各关次相加）。0 = 没有 picks 的坏档。
     *
     * 🔴 不要用 `picks.size` 代替：同场多选（复式）时选项数 ≠ 注数，
     *    5 场只打 3串1 也有 `C(5,3) = 10` 注。
     */
    fun betsOf(ticket: BetTicket): Int =
        if (ticket.picks.isEmpty()) 0 else BetCalculator.countBets(ticket.picks, ticket.ms)

    /**
     * 整单过关方式的展示名（记录 / 账本 / 详情 / 复制文案**唯一出口**）。
     *
     * 就是 [legsText]，不再做任何「合并成 n串N」的加工 —— 见类注释的演变史。
     */
    fun of(ticket: BetTicket): String = legsText(ticket.ms)

    /**
     * 注数行：`共 16 注`；只有 1 注时返回空串（没必要占一行）。
     *
     * 🔴 单关次但多注也必须显示（5 场只打 3串1 = 10 注），否则「3x1」看着像只买了 1 注。
     */
    fun detailOf(ticket: BetTicket): String {
        val bets = betsOf(ticket)
        return if (bets > 1) "共 $bets 注" else ""
    }
}
