package com.example.jingcai.ui.bet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jingcai.R
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.TicketStatus
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.Line

/**
 * 注单结果徽标（订单右侧那枚「中没中」的印章）。
 *
 * ## 本版：换成**印章图**，不再是 Compose 自绘圆章
 * 用户提供了两张现成的印章素材（`documentary_win.png` / `documentary_losing.png`），
 * 要求投注相关的列表都改用它们；`待开奖` 那张是按同样构图新画的绿色版。
 *
 * | 状态 | 资源 | 画面 |
 * |---|---|---|
 * | 已中奖 | `bet_stamp_win` | 红章「恭喜你 / 中奖了」 |
 * | 未中奖 | `bet_stamp_lost` | 灰章「很遗憾 / 未中奖」 |
 * | 待开奖 | `bet_stamp_pending` | 绿章「请稍候 / 待开奖」 |
 *
 * 🔴 三张图**原始像素都是 197×156**，绿色那张是用 `gen_pending_stamp.py` 按同样几何
 *    （圆心 (90,68)、半径 69、条幅 -15°）画出来的。**换图必须三张一起换**，
 *    否则并排大小不一 —— 这里按 [STAMP_AR] 固定长宽比，不靠图片自身尺寸兜底。
 *
 * ## 图片放 `drawable-nodpi`
 * 197×156 是"设计稿像素"而非某个密度档位，放进 `drawable-xxhdpi` 之类会被系统
 * 再缩一次；`nodpi` 表示原样取用，缩放交给 Compose 的 `ContentScale.Fit`。
 *
 * ## 已作废（VOID）仍然自绘
 * 全部腿取消才会出现，概率极低；而且语义是"退款"不是"没中"，
 * 套用灰色「未中奖」印章会误导。所以保留原来那枚小圆章，见 [VoidMark]。
 */
private const val STAMP_W = 197f
private const val STAMP_H = 156f
private const val STAMP_AR = STAMP_W / STAMP_H

/**
 * @param width 印章宽度。高度按 197:156 自动算（58dp → 约 46dp 高），
 *              与原来 44dp 的圆章视觉体量相当。
 */
@Composable
fun BetResultMark(
    ticket: BetTicket,
    modifier: Modifier = Modifier,
    width: Dp = 58.dp
) {
    val stamp = when (ticket.status) {
        TicketStatus.WON -> R.drawable.bet_stamp_win
        TicketStatus.LOST -> R.drawable.bet_stamp_lost
        TicketStatus.PENDING -> R.drawable.bet_stamp_pending
        TicketStatus.VOID -> null
    }

    if (stamp != null) {
        Image(
            painter = painterResource(stamp),
            // 读屏软件靠这句知道是哪一单中了没，图片本身没有可读文本
            contentDescription = ticket.status.label,
            contentScale = ContentScale.Fit,
            modifier = modifier.width(width).aspectRatio(STAMP_AR)
        )
    } else {
        VoidMark(modifier, width * 0.76f)
    }
}

/** 「已作废」的兜底圆章：中性底 + 灰环 + 一个「退」字（底/环走主题，深色下不成白饼）。 */
@Composable
private fun VoidMark(modifier: Modifier, diameter: Dp) {
    val fg = TextSub
    val bg = SurfaceAlt
    val ring = Line

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clip(CircleShape)) {
            val r = size.minDimension / 2f
            drawCircle(bg, radius = r)
            drawCircle(ring.copy(alpha = .8f), radius = r - size.minDimension * .075f,
                style = Stroke(width = size.minDimension * .045f))
        }
        // 字单独用 Text 画在 Canvas 上层：Canvas 画不出字体粗细
        Text("退", fontSize = (diameter.value * .42f).sp, fontWeight = FontWeight.Black, color = fg)
    }
}
