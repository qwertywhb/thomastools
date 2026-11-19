package com.example.jingcai.ui.bet

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.settle.BetCalculator
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Up

/**
 * 「固定奖金 ¥xxx」/「可能中奖金额 ¥a~¥b」—— 账单页与注单详情共用。
 *
 * ## 为什么要有
 * 用户 2026-11-06：「单子后面最好把可能中奖金额显示出来」。
 * 待开奖的单子原来只有本金，没有任何「这单能中多少」的预期 —— 押了 32 元到底图多少，
 * 只能自己拿赔率一笔笔乘，等于没给。
 *
 * ## 为什么是一个区间
 * 两种来源都会让金额浮动（口径见 [BetCalculator.payoutSpan]）：
 *  1. 同一场**同一玩法**买了多个档（半全场「胜平」+「负平」、总进球「5球/6球/7球」），
 *     几个档互斥、最多中一个 ⇒ 中哪个拿哪个的钱；
 *  2. **只中一部分场次**也照样有奖：5 场打 `3x1,4x1,5x1` 只中 3 场，就拿一注 3x1。
 * 所以「最低」不是随便取的小数，而是**真的中了的话最少能拿多少**。
 * 金额唯一时（只买一注 / 只有一个可能组合）只显示「固定奖金」。
 *
 * ## 🔴 三个不渲染的情况（别为了凑满而编数字）
 *  1. 已结算：那里已经有真实「派彩」，再写「可中」会让人以为还能中；
 *  2. 注数超过 [BetTicket.MAX_BETS] / 赛果组合过多，枚举不出（[BetCalculator.payoutSpan] 返回 null）；
 *  3. 没有 picks 的坏档。
 */
@Composable
fun BetPayoutHint(
    ticket: BetTicket,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.5.sp
) {
    if (ticket.settled) return
    // 枚举是 O(赛果状态 × 注数)，随卡片重组每次都算没必要
    val span = remember(ticket) { BetCalculator.payoutSpan(ticket) } ?: return
    if (span.fixed || span.min <= 0.0) {
        Text(
            "固定奖金 ¥${fmt(span.max)}",
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = Up,
            maxLines = 1,
            modifier = modifier
        )
        return
    }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(
            "可能中奖金额",
            fontSize = (fontSize.value * 0.88f).sp,
            color = TextSub,
            maxLines = 1
        )
        Text(
            "¥${fmt(span.min)}~¥${fmt(span.max)}",
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = Up,
            maxLines = 1
        )
    }
}
