package com.example.jingcai.ui.bet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.TicketStatus
import com.example.jingcai.data.repository.BetTicketStore
import com.example.jingcai.data.settle.BetCalculator
import com.example.jingcai.data.settle.Parlay
import com.example.jingcai.ui.theme.Background
import com.example.jingcai.ui.theme.Down
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.LineSoft
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.SurfaceTint
import com.example.jingcai.ui.theme.TextMain
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Up
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单张注单的**完整详情页**。
 *
 * ## 为什么要有这一页（而不是继续用列表里的「展开」）
 * 中奖弹窗的「立即查看」、状态栏派奖通知的点击，都必须落到**某一张具体的注单**上；
 * 记录页的展开区是卡片内的局部状态（`expandedId`），没法从外部定位，也不适合承载
 * 「这一单到底怎么算的」这种要逐行读的信息。所以单独开一页，由 ticketId 直接查库。
 *
 * ## 数据从哪来
 * 只读 [BetTicketStore]（SharedPreferences + Gson）。**没有 ViewModel**：详情是静态快照，
 * 本页不参与结算，读一次即可 —— 与记录页同一口径。
 *
 * ## 🔴 查不到注单是正常分支
 * 已开奖的注单满 24 小时会被 [BetTicketStore] 自动清掉，而通知可能还挂在状态栏上。
 * 这时给一张「已清理」的说明卡，**不能崩、也不能白屏**。
 */
@Composable
fun BetTicketDetailScreen(
    ticketId: String,
    onBack: () -> Unit,
    onOpenLive: (ticketId: String) -> Unit
) {
    val context = LocalContext.current
    // 🔴 返回键必须由**本页**接管：本页是盖在 NavHost 之上的一层，而 NavHost 自己也注册了
    //    OnBackPressedDispatcher 回调（栈深 > 1 时启用）。`OnBackPressedDispatcher` 按**注册顺序
    //    的逆序**派发 ⇒ 谁后组合谁优先。AppNav 里那个总 BackHandler 组合在 NavHost 之前，
    //    所以从「投注记录」打开详情时返回键会被 NavHost 抢走：**下面那一页被 pop 掉、详情仍盖在上面**。
    //    把回调放进本页（组合顺序天然在 NavHost 之后）才能稳定关掉这一层。
    BackHandler { onBack() }

    // remember(ticketId)：同一路由实例换 id 时重新读库（理论上不会，但便宜）
    val ticket = remember(ticketId) {
        BetTicketStore(context).loadTickets().firstOrNull { it.id == ticketId }
    }

    Column(Modifier.fillMaxSize().background(Background)) {
        DetailTopBar(onBack, ticket)

        if (ticket == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("这条注单不存在", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    Spacer(Modifier.height(6.dp))
                    Text("已开奖的订单保留 24 小时，过期会自动清理", fontSize = 12.sp, color = TextSub)
                }
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item { StatusCard(ticket) }
            item { InfoCard(ticket) }
            // 每场一块：编号/联赛/时间 + 对阵 + 赛果 + 每个选项的赔率与单腿结果。
            // 直接复用记录页展开区那块 —— 两处口径必须一致，否则「详情」和「记录」对不上。
            matchBlocks(ticket)
            if (ticket.note.isNotBlank()) {
                item { NoteCard(ticket.note) }
            }
            item { LiveButton { onOpenLive(ticket.id) } }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

/** LazyColumn 里逐场铺开（包一层，免得主函数被列表 DSL 撑得读不下去）。 */
private fun LazyListScope.matchBlocks(ticket: BetTicket) {
    ticket.picks.groupBy { it.matchId }.values.forEach { picks ->
        item {
            TicketMatchBlock(
                picks,
                ticket.scoreMap[picks.first().matchId.toString()],
                settled = ticket.settled
            )
        }
    }
}

@Composable
private fun DetailTopBar(onBack: () -> Unit, ticket: BetTicket?) {
    Box(Modifier.fillMaxWidth().background(SurfaceTint)) {
        Row(
            Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹ 返回",
                fontSize = 13.sp,
                color = Primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            )
            Spacer(Modifier.weight(1f))
            Text("订单详情", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Spacer(Modifier.weight(1f))
            // 分享成图（微信要的是图，不是文字）。注单不存在时不给入口，免得点了没反应。
            if (ticket != null) {
                val ctx = LocalContext.current
                Text(
                    "分享",
                    fontSize = 13.sp,
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { TicketShareCard.share(ctx, ticket) }
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                )
            } else {
                Spacer(Modifier.width(52.dp))
            }
        }
        HorizontalDivider(
            color = Line, thickness = 1.dp,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * 顶部状态卡：印章 + 状态 + 金额。
 *
 * 底色按状态给一层极淡的渐变 tint（中奖红 / 未中灰 / 待开奖绿），
 * 让人一眼看出「这单是赢是输」，而不只是靠印章图去认字。
 */
@Composable
private fun StatusCard(t: BetTicket) {
    val accent = when (t.status) {
        TicketStatus.WON -> Up
        TicketStatus.LOST -> TextSub
        TicketStatus.PENDING -> Down
        TicketStatus.VOID -> TextSub
    }
    val (big, bigColor) = when (t.status) {
        TicketStatus.WON -> "+¥${fmt(t.payout)}" to Up
        TicketStatus.LOST -> "-¥${fmt(t.stake)}" to TextSub
        TicketStatus.VOID -> "退回 ¥${fmt(t.payout)}" to TextSub
        TicketStatus.PENDING -> "— —" to TextSub
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = .07f), Color.Transparent)))
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BetResultMark(t, width = 76.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (t.status) {
                        TicketStatus.WON -> "恭喜中奖"
                        TicketStatus.LOST -> "未中奖"
                        TicketStatus.PENDING -> "待开奖"
                        TicketStatus.VOID -> "已作废"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Spacer(Modifier.height(6.dp))
                Text(big, fontSize = 25.sp, fontWeight = FontWeight.Black, color = bigColor)
                Spacer(Modifier.height(4.dp))
                Text(
                    when (t.status) {
                        TicketStatus.WON -> "本金 ¥${fmt(t.stake)} · 回报 ${ratio(t)} 倍"
                        TicketStatus.PENDING -> "还有场次未出赛果"
                        else -> "本金 ¥${fmt(t.stake)}"
                    },
                    fontSize = 11.5.sp,
                    color = TextSub
                )
            }
        }
    }
}

/** 订单信息卡：key-value 逐行。 */
@Composable
private fun InfoCard(t: BetTicket) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            // 🔴 不再显示「注单编号」：用户口径「注单编号可以去掉」。
            //    对用户没有信息量（UUID 后 12 位谁也记不住），删掉后首行直接是下单时间。
            InfoRow("下单时间", DATE_FMT.format(Date(t.createdAt)))
            InfoDivider()
            InfoRow("结算时间", if (t.settled) DATE_FMT.format(Date(t.settledAt)) else "未结算")
            InfoDivider()
            // 过关方式逐字写用户勾的那几档：`3x1,4x1,5x1`（用户 2026-11-06 定稿，
            // 不要合并成 `5串16` 这种竞彩名），注数单独一行「共 16 注」。
            InfoRow("过关方式", Parlay.of(t))
            val parlayDetail = Parlay.detailOf(t)
            if (parlayDetail.isNotEmpty()) {
                InfoDivider()
                InfoRow("注数", parlayDetail)
            }
            InfoDivider()
            InfoRow("倍数 / 场次", "${t.times} 倍 / ${t.picks.groupBy { it.matchId }.size} 场")
            InfoDivider()
            InfoRow("本金", "¥${fmt(t.stake)}")
            // 可能中奖金额（用户：「单子后面最好把可能中奖金额显示出来」）：
            // 只在未结算时给，已结算的单位置已经被真实「派彩」占了，再写会让人以为还能中。
            // 🔴 金额可能浮动：同场同玩法复选（半全场胜平+负平、总进球 5/6/7 球）只能中一个档；
            //    串关只中部分场次也照样有奖（5 场 3x1,4x1,5x1 只中 3 场 = 拿一注 3x1）。
            //    所以给区间「最低~最高」；唯一时按用户要求写「固定奖金」。
            if (!t.settled) {
                BetCalculator.payoutSpan(t)?.let { span ->
                    InfoDivider()
                    if (span.fixed || span.min <= 0.0) {
                        InfoRow("固定奖金", "¥${fmt(span.max)}", valueColor = Up)
                    } else {
                        InfoRow(
                            "可能中奖金额",
                            "¥${fmt(span.min)} ~ ¥${fmt(span.max)}",
                            valueColor = Up
                        )
                    }
                }
            }
            if (t.settled) {
                InfoDivider()
                InfoRow(
                    "派彩",
                    "¥${fmt(t.payout)}",
                    valueColor = if (t.status == TicketStatus.WON) Up else TextMain
                )
                InfoDivider()
                InfoRow(
                    "盈亏",
                    (if (t.profit >= 0) "+" else "") + "¥${fmt(t.profit)}",
                    valueColor = when {
                        t.profit > 0 -> Up
                        t.profit < 0 -> TextSub
                        else -> TextMain
                    }
                )
            }
        }
    }
}

@Composable
private fun NoteCard(note: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Text(note, fontSize = 11.5.sp, color = TextSub, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun LiveButton(onClick: () -> Unit) {
    Text(
        "看相关场次比分直播 ›",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(Primary, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun InfoRow(
    key: String,
    value: String,
    valueColor: Color = TextMain,
    mono: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, fontSize = 12.sp, color = TextSub, modifier = Modifier.width(78.dp))
        Text(
            value,
            fontSize = if (mono) 11.5.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            letterSpacing = if (mono) 0.6.sp else 0.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun InfoDivider() = HorizontalDivider(color = LineSoft, thickness = 1.dp)

/**
 * 回报倍数（派彩 ÷ 本金）。本金为 0 时给 0.00，避免除零。 */
private fun ratio(t: BetTicket): String =
    if (t.stake <= 0) "0.00" else String.format("%.2f", t.payout / t.stake)

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
