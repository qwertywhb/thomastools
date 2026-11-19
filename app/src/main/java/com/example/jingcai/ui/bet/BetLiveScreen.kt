package com.example.jingcai.ui.bet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jingcai.data.model.BetPick
import com.example.jingcai.data.repository.BetTicketStore
import com.example.jingcai.ui.LiveSport
import com.example.jingcai.ui.live.LiveScreen
import com.example.jingcai.ui.theme.Background
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.Flat
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.SurfaceTint
import com.example.jingcai.ui.theme.Up
import com.example.jingcai.ui.theme.TextMain
import com.example.jingcai.ui.theme.TextSub

/**
 * 「本次投注的相关场次比分直播」。
 *
 * 做法：**直接把比分直播页拿来用**（[LiveScreen] 支持 `onlyNums` / `onlyTeams` / `fixedSport`），
 * 所以表格样式、5 秒 tick、进球闪色、点行进详情这些行为**一行代码都不用重写**，
 * 且和正常比分直播页永远保持同一个口径。
 *
 * 球类由投注内容决定（现在切球类会清空清单，所以一张单子只会是足球或篮球）。
 * 场次匹配用「竞彩编号 OR 队名对」两条兜底，见 [com.example.jingcai.ui.live.filterTicket]。
 */
@Composable
fun BetLiveScreen(
    ticketId: String,
    onBack: () -> Unit,
    onOpenDetail: (Long, String, String) -> Unit,
    onOpenBbDetail: (String, String) -> Unit
) {
    val context = LocalContext.current
    val ticket = remember(ticketId) {
        BetTicketStore(context).loadTickets().firstOrNull { it.id == ticketId }
    }

    Column(Modifier.fillMaxSize().background(Background)) {
        // 顶栏**只留一个返回**：标题「相关场次」和右上角的编号都已按要求去掉，高度压到 44dp
        Box(Modifier.fillMaxWidth().background(SurfaceTint)) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "‹ 返回",
                    fontSize = 13.sp,
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(horizontal = 6.dp, vertical = 9.dp)
                )
                Spacer(Modifier.weight(1f))
            }
            HorizontalDivider(
                color = Line, thickness = 1.dp,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        if (ticket == null) {
            // 🔴 这里必须写 `if/else`，不能 `return@Column` 早退：
            //    Column 的 content 是 @Composable lambda，早退会让 Compose 的 group 进出不配对，
            //    实测抛 `IndexOutOfBoundsException: Index -1 out of bounds`（ComposerImpl.exitGroup）直接闪退。
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这条注单已过期清理，或从未存在", fontSize = 13.sp, color = TextSub)
            }
        } else {
            val byMatch = ticket.picks.groupBy { it.matchId }.values
            val onlyNums = ticket.picks.map { it.num }.filter { it.isNotBlank() }.toSet()
            val onlyTeams = byMatch.map { it.first().home to it.first().away }
            val sport = if (ticket.picks.firstOrNull()?.sport == "BASKETBALL") {
                LiveSport.BASKETBALL
            } else {
                LiveSport.FOOTBALL
            }

            // 投注内容默认**收起**，右侧只留一个竖排悬浮入口（不占比分区的任何位置）
            var panelOpen by remember { mutableStateOf(false) }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                // 比分直播占满整块
                LiveScreen(
                    onOpenDetail = onOpenDetail,
                    onOpenBbDetail = onOpenBbDetail,
                    onlyNums = onlyNums,
                    onlyTeams = onlyTeams,
                    fixedSport = sport
                )

                // ① 悬浮入口：贴在右边缘中间的竖排胶囊，收起时显示
                HandleSlot(
                    visible = !panelOpen,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onClick = { panelOpen = true }
                )

                // ② 轻遮罩：点一下收起。透明度故意压得很低，收起前左侧比分仍然能看见
                ScrimSlot(
                    visible = panelOpen,
                    modifier = Modifier.align(Alignment.Center),
                    onClick = { panelOpen = false }
                )

                // ③ 右侧抽屉：从右边缘滑入
                DrawerSlot(
                    visible = panelOpen,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    SideBetPanel(byMatch = byMatch, onClose = { panelOpen = false })
                }
            }
        }
    }
}

/**
 * 🔴 **必须把 [AnimatedVisibility] 单独包一层函数，不能直接写在 `Box { }` 里**：
 *    本文件外层是 `Column` 的 content lambda，`ColumnScope` 作为**外层隐式接收者**始终在作用域内，
 *    直接写 `AnimatedVisibility(...)` 会被解析到 `ColumnScope` 的成员重载上，编译报
 *    `can't be called in this context by implicit receiver`。
 *    抽成顶层 `@Composable` 后作用域里没有 `ColumnScope`，才会正确命中无 scope 的公共重载。
 */
@Composable
private fun HandleSlot(visible: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(120)),
        modifier = modifier
    ) {
        BetPanelHandle(onClick = onClick)
    }
}

/** 抽屉后面的轻遮罩：点一下就收起。 */
@Composable
private fun ScrimSlot(visible: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(120)),
        modifier = modifier
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.16f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        )
    }
}

/** 右侧抽屉的滑入/滑出外壳。 */
@Composable
private fun DrawerSlot(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(200)),
        modifier = modifier
    ) {
        content()
    }
}

/**
 * 右侧边缘的悬浮入口：**竖排「投注内容」+ 顶部 `‹‹`**（`‹‹` 表示「内容从右边拉出来」）。
 *
 * 只在抽屉收起时显示，位于比分直播区的右边缘垂直居中，宽 30dp 左右，
 * 不遮挡表格里任何实质信息（表格右侧留白区）。
 */
@Composable
private fun BetPanelHandle(onClick: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 13.dp, bottomStart = 13.dp)
    Column(
        Modifier
            .width(30.dp)
            .shadow(6.dp, shape)
            .background(Primary, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "‹‹",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            lineHeight = 11.sp
        )
        Spacer(Modifier.height(5.dp))
        // 竖排：每个字单独一个 Text，比给 Text 设窄宽度更稳
        "投注内容".forEach { c ->
            Text(
                c.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                lineHeight = 14.sp
            )
        }
    }
}

/**
 * 右侧抽屉里的投注内容：一场一张卡片，竖排（`编号 + 时间` / `主 VS 客` / 每注一行玩法·选项·赔率）。
 *
 * 🔴 抽屉宽度用 `fillMaxWidth(0.82f)`（相对比分区宽度），不能再写死 dp：
 *    真机 420dp 宽时是 344dp，小屏也不会把玩法文字挤成省略号。
 */
@Composable
private fun SideBetPanel(byMatch: Collection<List<BetPick>>, onClose: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
    Column(
        Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.82f)
            .shadow(12.dp, shape)
            .background(Surface, shape)
    ) {
        // 标题栏：左「投注内容」，右「»」收起（不放「N 场」这类汇总，用户已要求全站去掉）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("投注内容", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Spacer(Modifier.weight(1f))
            Text(
                "»",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Primary,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            )
        }
        HorizontalDivider(color = Line, thickness = 1.dp)

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            byMatch.forEach { picks ->
                val p0 = picks.first()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(SurfaceAlt, RoundedCornerShape(10.dp))
                        .padding(horizontal = 9.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p0.num,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Flat,
                            maxLines = 1,
                            modifier = Modifier
                                .background(Flat.copy(alpha = .14f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${p0.date.takeLast(5)} ${p0.time}",
                            fontSize = 9.5.sp,
                            color = TextSub,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${p0.home} VS ${p0.away}",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    // 每一注单独一行：玩法名（可省略）→ 选项（红）→ 赔率
                    picks.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                poolTitleOf(p),
                                fontSize = 10.5.sp,
                                color = TextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(4.dp))
                            // 选项：让分只写「让主胜 / 让客胜」，线在左侧玩法名里（`让分 -9.5`）
                            Text(
                                p.pickText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Up,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                String.format("%.2f", p.odds),
                                fontSize = 10.sp,
                                color = Flat,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
