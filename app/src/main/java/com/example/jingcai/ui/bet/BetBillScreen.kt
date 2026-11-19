package com.example.jingcai.ui.bet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jingcai.data.model.BetPick
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.TicketStatus
import com.example.jingcai.data.repository.BetTicketStore
import com.example.jingcai.data.settle.BetDay
import com.example.jingcai.data.settle.BetSettleEngine
import com.example.jingcai.data.settle.Parlay
import com.example.jingcai.ui.theme.Background
import com.example.jingcai.ui.theme.Down
import com.example.jingcai.ui.theme.Flat
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.SurfaceTint
import com.example.jingcai.ui.theme.TextMain
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Up
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 投注账单（模拟投注 → 顶栏「账单」）。
 *
 * ## 这一页只回答三件事（用户 2026-11-10 定稿）
 *  1. **今天买了什么** —— 按投注日分组，一场一行写清编号、对阵、选项；
 *  2. **花了多少钱** —— 每条卡头挂本金标签，组头给当天合计；
 *  3. **什么中了、什么没中** —— 右侧印章 + 每一腿的「中 / 挂」小标 + 中奖金额。
 *
 * ## 为什么整页没有统计
 * 用户明确要求去掉「累计盈亏 / 命中率 / 累计投入 / 累计回报 / 注单统计」这一整套：
 * 账单就是一张流水，不做任何跨天累计。配套的数据口径同时改了 ——
 * **已开奖的注单只保留 24 小时**，之后由 [BetTicketStore] 自动删除
 * （本金、派彩、赛果一起走），所以这里也不可能再做出累计。
 *
 * 记录页原有的「删除 / 清空 / 隐藏 / 管理」也一并下线：单子满 24 小时自己会走，
 * 不需要用户手动整理。整卡点击仍可进 [BetTicketDetailScreen] 看这一单的完整算法。
 */
@Composable
fun BetBillScreen(
    onBack: () -> Unit,
    onOpenDetail: (ticketId: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BetTicketStore(context) }

    var tickets by remember { mutableStateOf(store.loadTickets()) }
    var refreshing by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    /** 手动录分的目标注单（null = 弹窗关闭）。 */
    var manualTicket by remember { mutableStateOf<BetTicket?>(null) }
    /**
     * 待确认删除的注单（null = 没有）。
     *
     * 入口是**长按卡片 → 操作菜单里的「删除订单」**：整卡单击已经给了「进详情」，
     * 再挂一个看得见的删除按钮会把头行的「过关方式」挤没（实测 3x1 就换行了）。
     * 长按与本项目行情页「长按比赛行看技术统计」是同一套手势习惯。
     */
    var pendingDelete by remember { mutableStateOf<BetTicket?>(null) }
    /**
     * 长按唤起操作菜单的目标注单（null = 菜单关闭）。
     *
     * 🔴 长按**不再直接弹删除**（2026-11-18 用户要求「长按订单复制投注内容」）：
     *    一个手势挂两个互斥动作，直接删是没法回头的（注单只在本机，没有云端副本），
     *    改成菜单后「复制 / 删除」并列，误触也只是多看一眼。
     */
    var menuTicket by remember { mutableStateOf<BetTicket?>(null) }
    /**
     * 主档曾经解析失败过（[BetTicketStore.loadTickets] 会先备份原始 JSON 再逐条救）。
     * 有备份就说明「用户可能少了单」，必须留一个恢复出口，不能让列表安静地显示成空。
     */
    var brokenBackup by remember { mutableStateOf(store.hasBrokenBackup()) }

    fun reload() {
        tickets = store.loadTickets()
    }

    /**
     * 复制这张单的投注内容。
     *
     * 🔴 文本**必须**走 [formatTicketForShare] —— 那是「下单后自动复制」的同一个出口
     *    （`BetViewModel` 保存成功后拼的也是它）。在账单里另写一套拼接，过两个月就会出现
     *    「买的时候复制是 `让分 -9.5：让客胜`，账单复制是 `让客胜(-9.5)`」这种两份文案。
     *    这个函数只输出「编号 主队 VS 客队 玩法：选项；… + 过关方式 + 倍数」，
     *    不含金额与开奖结果 ⇒ 已开奖的单复制出来和下单时**逐字一致**。
     */
    fun copyTicket(t: BetTicket) {
        val text = formatTicketForShare(t)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            toast = "复制失败：剪贴板不可用"
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText("竞彩投注", text))
        toast = "已复制投注内容"
    }

    /** 结算一轮并给一句结果提示（`notify = false`：页面内自己提示，不要再弹通知）。 */
    suspend fun settleOnce() {
        refreshing = true
        val out = BetSettleRunner.run(notify = false, ctx = context, reason = "billPage")
        reload()
        refreshing = false
        toast = when {
            out.settled.isNotEmpty() ->
                "已开奖 ${out.settled.size} 单" +
                    if (out.payout > 0) "，中奖 ¥${fmt(out.payout)}" else "，都没中"
            out.unresolved > 0 -> "有 ${out.unresolved} 单暂时查不到赛果"
            else -> null
        }
    }

    // 进页面自动跑一轮：用户最想看的是「刚买的这单中没中」，不必先点刷新
    LaunchedEffect(Unit) { settleOnce() }

    val today = BetDay.today()
    // 新的在前；分组键是**投注日**（12:00 换日，见 [BetDay]），与卡头的时间同源
    val groups = remember(tickets) {
        tickets.sortedByDescending { it.createdAt }.groupBy { BetDay.of(it.createdAt) }
    }

    Column(Modifier.fillMaxSize().background(Background)) {
        if (brokenBackup) {
            Row(
                Modifier.fillMaxWidth().background(SurfaceAlt)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "注单数据格式异常，已自动备份原始内容",
                    fontSize = 11.5.sp, color = Flat, maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "恢复",
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Primary,
                    modifier = Modifier
                        .clickable {
                            val n = store.restoreBroken()
                            reload()
                            brokenBackup = store.hasBrokenBackup()
                            toast = if (n > 0) "已恢复 $n 条注单" else "备份里没有可恢复的记录"
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        BillTopBar(refreshing = refreshing, onBack = onBack, onRefresh = { scope.launch { settleOnce() } })

        if (tickets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有投注", fontSize = 13.sp, color = TextSub)
                    Spacer(Modifier.height(6.dp))
                    Text("已开奖的订单保留 24 小时，之后自动清理", fontSize = 10.5.sp, color = TextSub)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groups.forEach { (day, list) ->
                    item(key = "day-$day") { DayHeader(day, list, today) }
                    items(list, key = { it.id }) { t ->
                        BillTicketCard(
                            ticket = t,
                            onOpenDetail = { onOpenDetail(t.id) },
                            onManualSettle = { manualTicket = t },
                            onLongPress = { menuTicket = t }
                        )
                    }
                }
                item(key = "foot") {
                    Text(
                        "已开奖的订单保留 24 小时后自动清除；未开奖的会一直留在这里。长按注单可复制内容或删除。",
                        fontSize = 10.5.sp, color = TextSub,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)
                    )
                }
            }
        }
    }

    if (toast != null) {
        BillToast(toast!!) { toast = null }
    }

    // 长按注单的操作菜单：复制投注内容 / 删除订单。
    // 用 AlertDialog 而不是 BottomSheet —— 账单页没有自己的弹层宿主，两行操作用对话框
    // 足够，且与下面的删除确认是同一套视觉，不额外引入一套手势/返回键处理。
    menuTicket?.let { t ->
        AlertDialog(
            onDismissRequest = { menuTicket = null },
            title = { Text("这条注单", fontSize = 15.sp) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    BillMenuRow(
                        "复制投注内容",
                        "与下单后复制的内容一致，可直接发微信"
                    ) {
                        menuTicket = null
                        copyTicket(t)
                    }
                    Spacer(Modifier.height(2.dp))
                    BillMenuRow(
                        "删除订单",
                        "从账单移除，无法恢复",
                        danger = true
                    ) {
                        menuTicket = null
                        pendingDelete = t
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuTicket = null }) {
                    Text("取消", color = TextSub, fontSize = 13.sp)
                }
            }
        )
    }

    // 删除确认（长按卡片唤起）。
    // 不直接删：这个页面唯一的反馈是 toast，误触一次就少一条记录且**无法恢复**
    // （注单只在本机 SharedPreferences 里，没有云端副本）。
    pendingDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条注单？", fontSize = 16.sp) },
            text = {
                Text(
                    if (t.settled)
                        "这条已开奖的记录会从账单里移除，本金与派彩也一起消失。删除后无法恢复。"
                    else
                        "这单还没开奖，删除后不会再自动结算，也不会再弹中奖提示。删除后无法恢复。",
                    fontSize = 12.5.sp, color = TextSub
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.removeTicket(t.id)
                        reload()
                        pendingDelete = null
                        toast = "已删除"
                    }
                ) {
                    Text("删除", color = Up, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = TextSub, fontSize = 13.sp)
                }
            }
        )
    }

    // 手动录分（赛果源查不到时的兜底，只在「下单时就没匹配到赛果源」的单上出现）
    manualTicket?.let { t ->
        BetManualSettleDialog(
            ticket = t,
            onDismiss = { manualTicket = null },
            onConfirm = { scores ->
                val (stamped, payout) = BetSettleEngine(store).settleManually(t, scores)
                reload()
                manualTicket = null
                toast = when {
                    stamped == null -> "还有场次没填，未能开奖"
                    payout > 0 -> "已开奖：中奖 ¥${fmt(payout)}"
                    else -> "已开奖：未中奖"
                }
            }
        )
    }
}

@Composable
private fun BillTopBar(refreshing: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(SurfaceTint)) {
        Row(
            Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹ 返回",
                fontSize = 13.sp, color = Primary, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            )
            Spacer(Modifier.weight(1f))
            Text("账单", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Spacer(Modifier.weight(1f))
            if (refreshing) {
                Box(Modifier.width(52.dp).padding(10.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        strokeWidth = 1.6.dp,
                        modifier = Modifier.width(14.dp).height(14.dp)
                    )
                }
            } else {
                Text(
                    "刷新",
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Primary,
                    modifier = Modifier.clickable(onClick = onRefresh)
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }
        }
        HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * 一天的分组头：`今天 · 11-10 周二` + 当天「几单 · 投入多少」+（有开奖时）「中几单 · 派彩多少」。
 *
 * 🔴 这是**当天**的合计，不是累计账：上一天的数字不会带过来，也不会往下累加 ——
 *    用户明确不要跨天累计，账单只反映「这一天花了多少、中了什么」。
 */
@Composable
private fun DayHeader(day: String, list: List<BetTicket>, today: String) {
    val rel = BetDay.relative(day, today)
    val stake = list.sumOf { it.stake }
    val settled = list.filter { it.settled }
    val won = settled.filter { it.status == TicketStatus.WON }
    val wonAmount = won.sumOf { it.payout }

    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (rel.isEmpty()) BetDay.label(day) else "$rel · ${BetDay.label(day)}",
                fontSize = 13.sp, fontWeight = FontWeight.Black, color = TextMain, maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${list.size} 单 · 投入 ¥${fmt(stake)}",
                fontSize = 11.sp, color = TextSub, maxLines = 1
            )
        }
        if (settled.isNotEmpty()) {
            Text(
                "已开奖 ${settled.size} 单 · 中 ${won.size} 单" +
                    if (won.isNotEmpty()) " · 派彩 ¥${fmt(wonAmount)}" else "",
                fontSize = 10.5.sp,
                color = if (won.isNotEmpty()) Up else TextSub,
                maxLines = 1,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

/**
 * 一条账单。
 *
 * 收起的时代已经过去：账单**没有折叠态**，每场、每个选项一次列全 ——
 * 单子最多只留 24 小时，量小，没必要再让用户点两下才看得到买了什么。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BillTicketCard(
    ticket: BetTicket,
    onOpenDetail: () -> Unit,
    onManualSettle: () -> Unit,
    onLongPress: () -> Unit
) {
    val byMatch = remember(ticket) { ticket.picks.groupBy { it.matchId }.values.toList() }
    val parlayName = remember(ticket) { Parlay.of(ticket) }

    Card(
        // 整条可点 ⇒ 该单的完整详情（含算法、可能中奖金额、直播入口）；
        // 长按 ⇒ 操作菜单（复制投注内容 / 删除订单）。手势与行情页「长按比赛行看技术统计」一致。
        Modifier.fillMaxWidth().combinedClickable(
            onClick = onOpenDetail,
            onLongClick = onLongPress
        ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(
            1.dp,
            if (ticket.status == TicketStatus.WON) Up.copy(alpha = .35f) else Line
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                // 头行：时间 + 本金（花在这单上的钱）+ 过关方式
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 只留时分：日期在上面的分组头已经写明了，重复一遍纯占宽度
                    Text(
                        TIME_ONLY_FMT.format(Date(ticket.createdAt)),
                        fontSize = 11.sp, color = TextSub
                    )
                    Spacer(Modifier.width(6.dp))
                    BetStakeTag(ticket.stake, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        parlayName,
                        fontSize = 10.5.sp, color = TextSub, maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(5.dp))

                // 买的是什么：一场一块，比分 + 每个选项的中/挂
                byMatch.forEach { picks ->
                    TicketMatchBlock(
                        picks,
                        ticket.scoreMap[picks.first().matchId.toString()],
                        settled = ticket.settled,
                        // 账单里也显示开球时间（用户 2026-11-17 要求补到元信息行左侧）。
                        // 🔴 早先传 false 是因为它和队名抢同一行的宽度（「曼城 VS 阿…」）；
                        //    自从队名独占第二行、时间只留在编号行后，多它一个不再挤压任何东西。
                        showKickoff = true,
                        // 未开奖的「待开奖」占位也不显示：卡片右上角已有印章，重复且占宽度
                        showPendingChip = false
                    )
                }

                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (needManualHint(ticket)) {
                        Text(
                            "手动录分",
                            fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Up,
                            modifier = Modifier.clickable(onClick = onManualSettle)
                                .padding(vertical = 3.dp, horizontal = 2.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 中了多少钱 / 没中 / 还没开奖（可能中奖金额）
                    when {
                        !ticket.settled -> BetPayoutHint(ticket, fontSize = 11.5.sp)
                        ticket.status == TicketStatus.WON -> Text(
                            "中 ¥${fmt(ticket.payout)}",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Up, maxLines = 1
                        )
                        ticket.status == TicketStatus.VOID -> Text(
                            "退回 ¥${fmt(ticket.payout)}",
                            fontSize = 11.5.sp, color = TextSub, maxLines = 1
                        )
                        else -> Text(
                            "未中",
                            fontSize = 11.5.sp, color = TextSub, maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // 右侧印章：中 / 未中 / 待开奖，扫一眼就知道结果
            BetResultMark(ticket, width = 54.dp)
        }
    }
}

/**
 * 是否给这张单露出「手动录分」。
 *
 * 只有在**下单时就没换到赛果源**的单上才给：这类单自动结算永远拿不到结果，
 * 不给出口就是死单。普通待开奖单每张都挂一个链接太吵，用户会误以为要自己操作。
 */
private fun needManualHint(t: BetTicket): Boolean =
    !t.settled && t.note.contains("手动录分")

/**
 * 长按菜单里的一行操作（主标题 + 一行说明）。
 *
 * 用 `Row` 而不是 `TextButton`：说明文字要跟着主标题走，按钮那套居中排版塞不下两行，
 * 而且这里要的是「列表项」的观感（整行可点），不是「按钮」。
 */
@Composable
private fun BillMenuRow(
    title: String,
    desc: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 2.dp)
    ) {
        Text(
            title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            // 删除用涨色（本项目「红涨绿跌」，红在这里是警示色，与关闭/异常同一套）
            color = if (danger) Up else Flat,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(desc, fontSize = 10.5.sp, color = TextSub, maxLines = 2)
    }
}

/**
 * 轻提示条：2.4 秒自动消失。
 *
 * 必须由调用方 [onDone] 把状态清回 null，否则下次进入还会显示上一次的文案。
 */
@Composable
private fun BillToast(text: String, onDone: () -> Unit) {
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(2400)
        onDone()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            fontSize = 12.sp, color = Color.White,
            modifier = Modifier
                .padding(bottom = 40.dp)
                .background(Color(0xE6202536), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/**
 * 展开态的「一场」：编号 + 对阵 + 赛果记分牌 + 每个选项（赔率 + 中/挂）。
 *
 * 🔴 `internal` 而不是 private：订单详情页（[BetTicketDetailScreen]）复用同一块，
 *    两处口径必须逐字一致 —— 各写一份迟早会对不上（比如这里让分补了盘口线）。
 *
 * @param score 该场的赛果快照 `"全场主:客|半场主:客"`；null / 空 = 还没赛果。
 */
@Composable
internal fun TicketMatchBlock(
    picks: List<BetPick>,
    score: String?,
    settled: Boolean,
    /**
     * 是否在**编号行**左侧（编号之后）显示「开球日期 时间」。
     *
     * 账单页与详情页都传 true：队名已经独占第二行，时间不再和队名抢宽度
     * （早先账单页传 false，正是因为它在同一行里把「曼城 VS 阿…」挤成了省略号）。
     */
    showKickoff: Boolean = true,
    /**
     * 未开奖时是否显示「待开奖」灰底占位。
     *
     * 账单页传 false：卡片右上角本来就有「待开奖」印章，这里的占位纯属重复，
     * 却要吃掉 ~47dp —— 正是它把「波洛尼亚 VS 帕纳辛纳科斯」挤成「波洛… VS 帕纳…」。
     */
    showPendingChip: Boolean = true
) {
    val p0 = picks.first()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(SurfaceAlt, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // 🔴 对阵行拆成**两行**（用户 2026-11-17 报障「已开奖的订单队伍名称长了，会被赛果挡住」）：
        //    原先「编号 + 主队 VS 客队 + 比分牌（+ 开球时间）」全塞在一行里时，
        //    比分牌是**先测量的定宽元素**（`0 : 3` 13sp + 18dp 内边距 ≈ 70dp），
        //    队名只能吃它剩下的 ~180dp ——「北马其顿 VS 斯洛文尼亚」这种 6+7 字
        //    就只剩「北马… VS 斯洛…」，长名字看起来像被赛果压住了。
        //    现在第 1 行只放元信息（编号 / 开球时间 / 比分牌），第 2 行队名**独占整条卡内宽**
        //    （≈300dp）：除非单边超过 ~11 个汉字，否则不会再出省略号。
        //
        //    比分牌仍然靠右、与编号同一水平线 —— 读法还是「左边场次、右边几比几」。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                p0.num,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Flat,
                modifier = Modifier
                    .background(Flat.copy(alpha = .14f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
            )
            if (showKickoff) {
                Spacer(Modifier.width(6.dp))
                // 开球时间：`11-18 03:45`。窄屏下宁可省略也不能把右侧比分挤出去，
                // 所以钉死 1 行 + Ellipsis（这一行本身没有 weight，不会主动撑宽）。
                Text(
                    "${p0.date.takeLast(5)} ${p0.time}",
                    fontSize = 10.sp,
                    color = TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.weight(1f))
            // 赛果记分牌：靠右，与编号对齐。
            // 字号 13 → 11sp：它已经不参与队名的宽度竞争（队名单独一行了），
            // 再保持大号只会让这一格显得笨重（用户：「不用占这么大的空间」）。
            MatchScoreChip(
                score, fontSize = 11.sp,
                showHalf = picks.any { it.poolCode == "hafu" },
                showPending = showPendingChip && !settled
            )
        }
        Spacer(Modifier.height(3.dp))
        // 对阵：**标出主客**（用户要求「账单中主队客队标注出来」）。
        // 顺序固定「主队 VS 客队」：账单里没有赔率列需要对齐，按这个顺序读最自然，
        // 也和足球比分页表头「主队 / 比分 / 客队」一致。
        //
        // 🔴 角标改成**队名右上角的小圆圈**（用户 2026-11-17：主红、客绿、字更小），
        //    不再是「〔主〕」那种浅底文字块 —— 那种写法每个角标要吃掉 ~30dp
        //    （一个字 + 前后空格），两个角标 60dp，长队名就被挤成「凯尔…」。
        //    圆圈只有 10dp，且是**贴着队名右侧、顶到行的上沿**（不是压在字上），
        //    省下来的宽度全给队名（用户同时报障「有些队伍名称显示不全」）。
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 🔴 两支队的宽度权重**按队名长度分配**（不是各 1f）：
            //    各占一半时「波洛尼亚(4字) VS 帕纳辛纳科斯(6字)」会让 6 字那条
            //    只分到 4 字那条同样的宽度，于是长队名被截成「帕纳…」（实测截图）。
            //    按字数分权重后，长名字拿到它该有的那一份，「VS」只按内容占位。
            TeamName(
                p0.home, home = true,
                modifier = Modifier.weight(p0.home.length.coerceAtLeast(1).toFloat(), fill = false)
            )
            Text(" VS ", fontSize = 10.sp, color = TextSub, maxLines = 1)
            TeamName(
                p0.away, home = false,
                modifier = Modifier.weight(p0.away.length.coerceAtLeast(1).toFloat(), fill = false)
            )
        }
        Spacer(Modifier.height(3.dp))
        picks.forEach { p ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    billPoolTitleAnnotated(p),
                    fontSize = 11.sp,
                    color = TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    billPickLabel(p),
                    fontSize = 11.sp,
                    color = TextMain,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    String.format("%.2f", p.odds),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Up,
                    modifier = Modifier.padding(end = 6.dp)
                )
                // 你选的这一项到底中没中
                LegChip(p.legSafe)
            }
        }
    }
}

/**
 * 一支球队 + 右上角的「主 / 客」小圆圈。
 *
 * 圆圈是**并排贴右**而不是压在字上（不压在字上才不用给队名额外留内边距，
 * 也绝不会盖掉最后一个字的右上角），`Alignment.Top` 让它顶到行的上沿 ⇒ 视觉上是上标。
 *
 * 队名挂 `weight(1f, fill = false)`：内容短就按内容宽度排（不会白占），
 * 只有真放不下时才在自己这一格里省略。两支队各占一半「剩余宽度」——
 * 角标只花 10dp，所以 6 个字的队名（如「帕纳辛纳科斯」）在 360dp 屏上仍然排得下。
 */
@Composable
private fun TeamName(name: String, home: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Text(
            name,
            modifier = Modifier.weight(1f, fill = false),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(1.5.dp))
        SideBadge(if (home) "主" else "客", home)
    }
}

/**
 * 主 / 客圆形角标：**主红、客绿**，白字 6.5sp，直径 10dp。
 *
 * 语义色按用户口径单独定义，不复用比分色，也不和「涨红跌绿」的赔率色混用。
 * 字号压到 6.5sp 并关掉字体上下留白：10dp 的圆里放一个 7.5sp 的汉字会偏下、
 * 甚至底边被圆裁掉（元信息行「析」字踩过同一个坑）。
 */
@Composable
private fun SideBadge(text: String, home: Boolean) {
    Box(
        Modifier
            .size(10.dp)
            .background(if (home) Up else Down, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 6.5.sp,
            color = Color.White,
            maxLines = 1,
            style = TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeight = 6.5.sp
            )
        )
    }
}

/**
 * 账单「玩法」列文案：**篮球让分把盘口线提到这一列**（`让分 -9.5`）。
 *
 * 🔴 为什么在这里提线（用户报障「让分是根据主队来说的，账单中的让分是不是有问题」）：
 *    体彩 `goalLineValue` 是**主队视角**的一条线 —— 实测与 titan007 篮球行挂在主队身上的
 *    `(-10.5)` 完全一致（同日掘金 vs 太阳：体彩 `-10.50`、源站主队侧 `(-10.5)`）。
 *    官方客户端也只显示这一条：`让分：-9.5` + `主胜 / 客胜`。
 *    旧实现把线分别拼进两条腿、还给客队那条**翻了符号**（`让客胜(+9.50)`），
 *    数值上没错（客队受让 9.5），但和体彩口径反着，用户对不上账。
 *    现在账单里线**只出现一次、永远是主队视角**，与官方一致。
 *
 * 直接复用 [poolTitleOf]：投注页 / 投注单 / 分享 / 通知 / 账单**同一条口径**，
 * 免得「某个页面还在用别的写法」这种老问题再出现一次。
 */
private fun billPoolTitle(p: BetPick): String = poolTitleOf(p)

/** 玩法名末尾的**盘口线**（`让分 -6.5` / `大小分 227.5` 里的那个数）。 */
private val LINE_TAIL_RE = Regex("\\s(-?\\d+(?:\\.\\d+)?)$")

/**
 * 账单玩法列：**线值单独染绿**，整行仍是同一个 Text（省略号行为不变）。
 *
 * 用户口径（2026-11-17）：「让分和大小分的值绿色字体表示」。投注页的 `LineLabel`
 * 已经改成绿色，账单这里若还是灰的，同一笔单在两个页面会显示成两种颜色。
 *
 * 只对 `hdc`（让分）/ `hilo`（大小分）生效：足球那列是 `让球胜平负(-1)`，末尾是右括号，
 * 而且那是「选项的限定条件」不是盘口线，不该染色。
 */
@Composable
private fun billPoolTitleAnnotated(p: BetPick): AnnotatedString {
    val text = billPoolTitle(p)
    if (p.poolCode != "hdc" && p.poolCode != "hilo") return AnnotatedString(text)
    val m = LINE_TAIL_RE.find(text) ?: return AnnotatedString(text)
    val lineColor = Down
    return buildAnnotatedString {
        append(text.substring(0, m.range.first))
        withStyle(SpanStyle(color = lineColor, fontWeight = FontWeight.Bold)) {
            append(text.substring(m.range.first))
        }
    }
}

/**
 * 账单「选项」列文案：篮球让分只留 `让主胜` / `让客胜`，**不带任何线**。
 *
 * 线已经在 [billPoolTitle] 那一列了，这一列再带一遍就成了
 * 「让分 -9.5   让客胜(+9.5)」：既重复，又把「主队视角」和「本队视角」混在同一行。
 * 文案来自 [com.example.jingcai.data.model.BetPick.pickText]（按 `key` 推导），
 * 存量注单里旧解析器拼的括号线自动不再出现，不用迁移数据。
 */
private fun billPickLabel(p: BetPick): String = p.pickText

/** 金额统一两位小数（账单 / 详情 / 通知同口径）。 */
internal fun fmt(v: Double): String = String.format("%.2f", v)

/** 账单卡头只要时分：投注日已经在分组头里，重复写 `11-10` 会白占一格宽度。 */
private val TIME_ONLY_FMT = SimpleDateFormat("HH:mm", Locale.CHINA)
