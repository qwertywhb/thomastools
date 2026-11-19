package com.example.jingcai.ui.bet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.jingcai.data.model.BetMarket
import com.example.jingcai.data.model.BetMatch
import com.example.jingcai.data.model.BetOption
import com.example.jingcai.data.model.BetPick
import com.example.jingcai.data.model.BetPool
import com.example.jingcai.data.model.BetSport
import com.example.jingcai.data.model.betPickKey
import com.example.jingcai.data.model.shortLine
import com.example.jingcai.data.model.shortTotalLine
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

/**
 * 「全部玩法」弹窗 —— 用户明确要求：**比分 / 总进球 / 半全场 这三个点开弹窗显示**。
 *
 * 结构与参考图一致：每块玩法**左侧一条竖排标签**（比分 / 总进球 / 半全场），
 * 右侧是网格。
 *
 * ── 本版（用户第三轮要求）────────────────────────────────────────────────
 *  1. **整个弹窗居中显示**（不再是底部弹层）：改用 [Dialog] + 居中 `Card`，
 *     宽度 = 屏宽 94%、最大高度 620dp，超出时内部滚动；
 *  2. **去掉「已选 N 项」**（连同任何金额数字），弹窗里只剩玩法网格 + 「选好了」。
 *
 * 之前两轮的改动仍然保留：删掉了顶部「周五001 对阵 时间」那行冗余信息；
 * 格子 38dp / 间距 3dp / 左侧标签 34dp，一屏放得下。
 *
 * 🔴 网格用 `chunked(columns)` + Row 手排，不用 `LazyVerticalGrid`：
 *    Lazy* 组件在高度不受限的容器里会抛
 *    「Vertically scrollable component was measured with an infinity maximum height constraints」。
 */
@Composable
fun BetAllMarketsSheet(
    match: BetMatch,
    state: BetUiState,
    onPick: (BetMarket, BetOption) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            Modifier
                .fillMaxWidth(0.94f)
                // 高度上限：内容少时窗口自动收窄（wrapContent），内容多时到顶后内部滚动。
                // 620dp ≈ 屏幕 78%，保证上下都留出空白，视觉上是「居中卡片」而不是全屏弹层。
                .heightIn(max = 620.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    match.expand.forEach { market ->
                        ExpandMarketBlock(
                            market = market,
                            match = match,
                            state = state,
                            onPick = { opt -> onPick(market, opt) }
                        )
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Text("选好了", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 一块弹窗玩法：[左侧竖排标签] + [网格]。 */
@Composable
private fun ExpandMarketBlock(
    market: BetMarket,
    match: BetMatch,
    state: BetUiState,
    onPick: (BetOption) -> Unit
) {
    Row(Modifier.fillMaxWidth()) {
        // 左侧竖排标签：比分 / 总进球 / 半全场（参考图里是一整条浅底竖条）
        // 篮球让分 / 大小分在这条标签下再挂一行盘口线 —— 与投注卡片 [LineLabel] 同一个口径：
        // 线只出现这一处，选项格只写「让主胜 / 让客胜」「大 / 小」。
        // 宽度跟卡片一致：足球 36dp，篮球 54dp（「大小分」三个字要一行排完，且两行之间留白）。
        Box(
            Modifier
                .width(if (match.sport == BetSport.BASKETBALL) 54.dp else 36.dp)
                .background(SurfaceTint, RoundedCornerShape(5.dp))
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    market.lineLabel,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                if ((market.pool == BetPool.HDC || market.pool == BetPool.HILO) &&
                    market.lineText.isNotBlank()
                ) {
                    Text(
                        if (market.pool == BetPool.HILO) shortTotalLine(market.lineText)
                        else shortLine(market.lineText),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        // 让分 / 大小分的线值统一绿色（与卡片内 [LineLabel] 同口径）
                        color = Down,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))

        Column(
            Modifier.weight(1f).background(SurfaceAlt, RoundedCornerShape(5.dp)).padding(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // 按 groupSizes 分段（比分 = 主胜区 / 平区 / 客胜区，每段各起一行），段内再按 columns 分行。
            val groups: List<List<BetOption>> = if (market.groupSizes.isEmpty()) {
                listOf(market.options)
            } else {
                var idx = 0
                market.groupSizes.mapNotNull { n ->
                    val end = (idx + n).coerceAtMost(market.options.size)
                    if (idx >= end) null else market.options.subList(idx, end).also { idx = end }
                }
            }
            groups.forEach { group ->
                group.chunked(market.columns).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        row.forEach { opt ->
                            OddCell(
                                opt = opt,
                                selected = state.isPicked(match.matchId, opt.pool.code, opt.key),
                                enabled = !match.ended && opt.sellable,
                                modifier = Modifier.weight(1f),
                                compact = false,
                                dense = true,
                                onClick = { onPick(opt) }
                            )
                        }
                        // 末行不足列数时补空位，使每格宽度与满行一致
                        repeat(market.columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * 清单弹窗。本版口径（用户要求）：
 *
 *  1. **按比赛合并**：同一场的多个玩法聚成一块，头行是「编号 + 联赛 + 时间 + 对阵」，
 *     下面一行一个选项（`玩法(让球线) · 选项` + 赔率 + ✕ 删除）；
 *  2. **底部什么都不放**：没有「确认保存」按钮、没有「复制」复选框、没有「共 N 项」汇总、
 *     也没有说明文字 —— 清单里**只要内容**；
 *  3. 保存统一在底部栏点「保存」，点保存时会**自动**把清单内容写进剪贴板
 *     （文案由 [formatTicketForShare] 生成）；
 *  4. 不出现金额。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BetSlipSheet(
    state: BetUiState,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surface
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "清单",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain
                )
                Spacer(Modifier.weight(1f))
                if (state.pickCount > 0) {
                    Text(
                        "清空",
                        fontSize = 12.sp,
                        color = TextSub,
                        modifier = Modifier
                            .clickable(onClick = onClear)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            HorizontalDivider(color = Line, thickness = 1.dp)

            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.pickCount == 0) {
                    Text(
                        "还没有选择选项",
                        fontSize = 12.sp,
                        color = TextSub,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center
                    )
                }
                // 按 matchId 聚合；`picks` 是 LinkedHashMap，所以分组顺序 = 选号顺序。
                state.picks.values.groupBy { it.matchId }.values.forEach { picks ->
                    MatchPickGroup(picks = picks, onRemove = onRemove)
                }
            }

            // 底部**什么都不放**：清单里只要「内容」（用户要求去掉项数汇总和说明文字）
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 一场比赛一块：头行（编号 / 联赛 / 时间）+ 对阵 + 逐选项。 */
@Composable
private fun MatchPickGroup(picks: List<BetPick>, onRemove: (String) -> Unit) {
    val p0 = picks.first()
    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceAlt, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
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
            Spacer(Modifier.width(6.dp))
            Text(
                p0.league,
                fontSize = 10.sp,
                color = TextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${p0.date.takeLast(5)} ${p0.time}",
                fontSize = 10.sp,
                color = TextSub,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${p0.home} VS ${p0.away}",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        picks.forEach { p ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    poolLineText(p),
                    fontSize = 11.sp,
                    color = TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    String.format("%.2f", p.odds),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Up
                )
                Text(
                    "✕",
                    fontSize = 12.sp,
                    color = TextSub,
                    modifier = Modifier
                        .clickable { onRemove(betPickKey(p.matchId, p.poolCode, p.key)) }
                        .padding(start = 10.dp, top = 3.dp, bottom = 3.dp, end = 2.dp)
                )
            }
        }
    }
}

/**
 * `胜平负 · 主胜` / `让球胜平负(-1) · 让平` / `让分 -9.5 · 让客胜`。
 *
 * 🔴 篮球让分：**线只跟玩法名走**（`让分 -9.5`），选项只写「让主胜 / 让客胜」。
 *    别再写成 `让分 · 让客胜(+9.5)` —— 一正一负两条线并排，用户会读成「让分反了」。
 * 🔴 括号只在「让球线 ≠ 玩法名」时加：篮球的 `lineLabel` 直接就是玩法名
 *    （让分 / 大小分 / 胜负），加了会变成 `大小分(大小分)`——用户已指出这是重复。
 */
private fun poolLineText(p: BetPick): String = buildString {
    append(p.poolTitle)
    if ((p.poolCode == "hdc" || p.poolCode == "hilo") && p.line.trim().isNotEmpty()) {
        append(' ').append(if (p.poolCode == "hilo") shortTotalLine(p.line) else shortLine(p.line))
    } else if (p.lineLabel.isNotBlank() && p.lineLabel != "0" && p.lineLabel != p.poolTitle) {
        append('(').append(p.lineLabel).append(')')
    }
    append(" · ").append(p.pickText)
}
