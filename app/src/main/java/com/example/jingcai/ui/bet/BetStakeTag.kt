package com.example.jingcai.ui.bet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jingcai.ui.theme.Up

/**
 * 本金标签（记录页 / 账本页共用）。
 *
 * ## 为什么要单独做一个标签
 * 用户反馈：本金原来只是「2串1 · 3 倍 · 本金 ¥60.00」这种说明文字里的**一小段灰字**，
 * 而且只在展开后才出现 —— 收起状态根本看不到这单押了多少钱。
 * 本金是每张注单最该一眼看到的数字（押多少、中了赔多少都从它推），
 * 所以做成**独立的胶囊标签**，放在卡片头行固定位置，不再混在说明文字里。
 *
 * ## 🔴 颜色 = 红（`Up`）
 * 用户明确要求「显示金额和中奖状态的时候用红色字体」：金额一律红字（本金 / 派彩 / 中奖金额），
 * 与国内彩票「钱＝红」的读法一致；绿色只留给「查看投注内容 / 展开」这类**操作入口**。
 *
 * 金额统一走 [fmt]（两位小数），和账本其它金额口径一致，不要在调用处自己拼格式。
 */
@Composable
fun BetStakeTag(
    stake: Double,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp
) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .background(Up.copy(alpha = .09f), shape)
            .border(1.dp, Up.copy(alpha = .30f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "本金",
            fontSize = fontSize * 0.86f,
            color = Up.copy(alpha = .80f),
            maxLines = 1
        )
        Spacer(Modifier.width(3.dp))
        Text(
            "¥${fmt(stake)}",
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = Up,
            maxLines = 1
        )
    }
}
