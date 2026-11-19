package com.example.jingcai.ui.bet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jingcai.data.model.BetMarket
import com.example.jingcai.data.model.BetMatch
import com.example.jingcai.data.model.BetOption
import com.example.jingcai.data.model.BetPool
import com.example.jingcai.data.model.BetSport
import com.example.jingcai.data.model.shortLine
import com.example.jingcai.data.model.shortTotalLine
import com.example.jingcai.data.settle.Parlay
import com.example.jingcai.ui.EmptyText
import com.example.jingcai.ui.ErrorText
import com.example.jingcai.ui.Loading
import com.example.jingcai.ui.theme.Background
import com.example.jingcai.ui.theme.Down
import com.example.jingcai.ui.theme.Flat
import com.example.jingcai.ui.theme.LeagueColors
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.LineSoft
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.SurfaceTint
import com.example.jingcai.ui.theme.TextMain
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Up
import com.example.jingcai.ui.theme.parseHexColorOrNull

/**
 * 模拟投注（混合过关）——参考 okooo 竞彩 / 竞彩篮球混合过关的选号页。
 *
 * 布局口径（对齐用户给的三张参考图）：
 *
 * ```
 * ┌ [周五001][澳超]              [已选2] [全部玩法 ∨] ┐
 * │ [9] 布里斯班 主   10-23 17:00   客 珀斯 [10]     │
 * │ ┌──┬──────────┬──────────┬──────────┐          │
 * │ │ 0│ 主胜1.98↑ │ 平 3.35↑ │ 主负3.07↓ │          │
 * │ │-1│ 让胜4.08↑ │ 让平3.65↑ │ 让负1.64↓ │          │
 * │ └──┴──────────┴──────────┴──────────┘          │
 * └──────────────────────────────────────────────────┘
 * ```
 *
 * 玩法分布（用户明确要求）：
 *  - **足球**：胜平负、让球胜平负 —— 内联；比分、总进球、半全场 —— 点「全部玩法」弹窗展开；
 *  - **篮球**：让分、大小分、胜负 —— 全部内联（**不做胜分差**）。
 *
 * 🔴 左侧那条窄标签是「让球线」：足球 `0` / `-1`，篮球是玩法名（让分/大小分/胜负）。
 *
 * ── 本版改动（用户第四轮要求）────────────────────────────────────────────
 *  1. **去掉账户 / 余额**：底部栏不再显示余额，也不再显示「最高奖金」
 *     （下单前只看注数 + 本金，中奖金额去「账单」里看）；
 *  2. **过关方式可多选**：单关/2串1/…/6串1 都能同时勾（竞彩的自由过关），
 *     `{2,3}` 就是「3串4」；配合同场多选即复式，注数由公式自动展开。
 */
@Composable
fun BetScreen(
    vm: BetViewModel = viewModel(),
    /** 打开「账单」页（今天买了什么、花了多少、中没中）。 */
    onOpenBill: () -> Unit = {}
) {
    val state by vm.state.collectAsState()

    // 首次进入才拉数据：切回 tab 不重复请求（接口有频率限制）
    LaunchedEffect(Unit) { vm.ensureLoaded() }

    var showSlip by remember { mutableStateOf(false) }

    // 「复制」复选框默认选中：保存完成后把清单文本写进剪贴板，可直接发给别人。
    // 文本由 VM 拼（formatTicketForShare），复制动作在这里做（需要 Context）。
    val context = LocalContext.current
    LaunchedEffect(state.pendingCopy) {
        val text = state.pendingCopy ?: return@LaunchedEffect
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("竞彩投注", text))
        }
        vm.consumeCopy()
    }

    Column(Modifier.fillMaxSize().background(Background)) {
        BetTopBar(
            state = state,
            onSwitch = { vm.switchSport(it) },
            onOpenBill = onOpenBill
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && state.matches.isEmpty() -> Loading()
                state.error != null -> ErrorText(state.error)
                state.matches.isEmpty() -> EmptyText("当前没有在售的${state.sport.label}场次")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.matches, key = { it.matchId }) { m ->
                        BetMatchCard(
                            match = m,
                            state = state,
                            onPick = { market, opt -> vm.togglePick(m, market, opt) },
                            onOpenAll = { vm.openSheet(m) }
                        )
                    }
                }
            }
        }

        BetSlipBar(
            state = state,
            onTimes = { vm.setTimes(it) },
            onToggleParlay = { vm.toggleParlay(it) },
            onSelectAll = { vm.selectAllParlay() },
            onOnlySingle = { vm.onlySingle() },
            onOpenSlip = { showSlip = true },
            onPlace = { vm.placeBet() }
        )
    }

    // 「全部玩法」弹窗（比分 / 总进球 / 半全场）
    state.sheetMatch?.let { m ->
        BetAllMarketsSheet(
            match = m,
            state = state,
            onPick = { market, opt -> vm.togglePick(m, market, opt) },
            onDismiss = { vm.closeSheet() }
        )
    }

    // 清单弹窗（按比赛合并，可逐项删除；保存只在底部栏做，保存时自动复制）
    if (showSlip) {
        BetSlipSheet(
            state = state,
            onRemove = { vm.removePick(it) },
            onClear = { vm.clearPicks() },
            onDismiss = { showSlip = false }
        )
    }

    // 一次性提示（保存成功 / 请先选择）
    state.message?.let { msg ->
        LaunchedEffect(msg) {
            // 「已复制到剪切板」这类提示停留久一点，用户能看清（1.8s → 3.2s）
            kotlinx.coroutines.delay(3200)
            vm.consumeMessage()
        }
        BetToast(msg)
    }
}

// ============================== 顶栏：球类切换 ==============================

/**
 * 顶栏：足球 / 篮球 用**分段胶囊**切换。
 *
 * 🔴 上一版只是「文字变蓝加粗」，用户反馈**切了看不出来**。
 *    现在改成白底胶囊容器 + 选中段**实心主色蓝底白字**，明暗对比足够强，
 *    并且给了 [animateColorAsState] 过渡，切换有轻微动效。
 */
@Composable
private fun BetTopBar(
    state: BetUiState,
    onSwitch: (BetSport) -> Unit,
    onOpenBill: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(SurfaceTint)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 胶囊容器：内边距 3dp，营造「滑块」质感
            Row(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .background(Surface, RoundedCornerShape(19.dp))
                    .border(1.dp, Line, RoundedCornerShape(19.dp))
                    .padding(3.dp)
            ) {
                BetSport.values().forEach { s ->
                    BetSportTab(
                        sport = s,
                        selected = s == state.sport,
                        modifier = Modifier.weight(1f),
                        onClick = { onSwitch(s) }
                    )
                }
            }
            // 账单入口：**只有一个**（原先「记录 + 账本」两个入口已合并）。
            // 用 Up（红）而不是 Primary：深色底上 Primary(#C62828) 对比只有 ~2.6:1，几乎看不见。
            // 「刷新」不做：数据本来就有 5s tick 自动刷新，手动刷新是冗余入口。
            RecordEntry("账单", Up, pulse = true, onClick = onOpenBill)
        }
        HorizontalDivider(color = Line, thickness = 1.dp)
    }
}

/**
 * 顶栏文字入口（账单）。
 *
 * [pulse] = true 时每 5 秒轻轻跳一下（用户要求「投注记录」保持醒目，合并成账单后沿用）。
 *
 * 跳动做法：`LaunchedEffect` 里 `delay(5000)` 触发一次脉冲，
 * 用 [animateFloatAsState] 把 scale 从 1 → 1.2 → 1，配合 [graphicsLayer] 只缩放绘制、
 * 不影响布局（否则文字一大一小会把左侧胶囊挤来挤去）。
 */
@Composable
private fun RecordEntry(
    label: String,
    color: Color,
    pulse: Boolean,
    onClick: () -> Unit
) {
    var pulsing by remember { mutableStateOf(false) }
    if (pulse) {
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(5000)
                pulsing = true
                kotlinx.coroutines.delay(240)
                pulsing = false
            }
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (pulsing) 1.2f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "recordPulse"
    )
    Text(
        label,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 8.dp)
    )
}

@Composable
private fun RowScope.BetSportTab(
    sport: BetSport,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) Primary else Color.Transparent,
        tween(180),
        label = "betTabBg"
    )
    val fg by animateColorAsState(
        if (selected) Color.White else TextSub,
        tween(180),
        label = "betTabFg"
    )
    Box(
        modifier
            .height(32.dp)
            .background(bg, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sport.icon, fontSize = 14.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                sport.label,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = fg
            )
        }
    }
}

// ============================== 比赛卡片 ==============================

@Composable
private fun BetMatchCard(
    match: BetMatch,
    state: BetUiState,
    onPick: (BetMarket, BetOption) -> Unit,
    onOpenAll: () -> Unit
) {
    val leagueColor = parseHexColorOrNull(match.color) ?: LeagueColors.solid(match.league)
    val pickedCount = state.pickCountOf(match.matchId)

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, if (pickedCount > 0) Primary else Line)
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {

            // —— 头行：编号 + 联赛 + 已选数 + 全部玩法 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    match.num,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Flat,
                    modifier = Modifier
                        .background(Flat.copy(alpha = .12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    match.league,
                    fontSize = 10.sp,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .background(leagueColor, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                )
                if (match.ended) {
                    Spacer(Modifier.width(6.dp))
                    Text("已停售", fontSize = 9.sp, color = TextSub)
                }
                Spacer(Modifier.weight(1f))

                // 同场多选时，这里显示「已选 N」——否则选中项散落在多行里看不出来
                if (pickedCount > 0) {
                    Text(
                        "已选 $pickedCount",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primary,
                        modifier = Modifier
                            .background(Primary.copy(alpha = .10f), RoundedCornerShape(9.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                // 「全部玩法」：只有真有弹窗玩法时才显示（篮球全是内联，不显示）
                if (match.expand.isNotEmpty() && !match.ended) {
                    Text(
                        "全部玩法 ∨",
                        fontSize = 11.sp,
                        color = Primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(onClick = onOpenAll)
                            .background(Primary.copy(alpha = .08f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // —— 队行：左侧队 + 时间 + 右侧队 ——
            //
            // 🔴 左右谁放主队**按球类分**，不是固定「主在左」：
            //    参考图里足球是「[9] 布里斯班 主 … 客 珀斯 [10]」（主队在左），
            //    篮球是「[7] 墨尔本联 客 … 主 珀斯 [3]」（客队在左）。
            //    共同规律是**左侧球队 = 第一个赔率所对应的球队**（足球第一格是「主胜」，
            //    篮球第一格是「让客胜 / 客胜」），所以按球类切换才不会和赔率列对不上。
            val leftFirst = match.sport == BetSport.FOOTBALL
            val leftTeam = if (leftFirst) match.home else match.away
            val rightTeam = if (leftFirst) match.away else match.home
            val leftFlag = if (leftFirst) "主" else "客"
            val rightFlag = if (leftFirst) "客" else "主"
            val leftRank = if (leftFirst) match.homeRank else match.awayRank
            val rightRank = if (leftFirst) match.awayRank else match.homeRank

            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamName(
                    rank = leftRank, name = leftTeam, flag = leftFlag,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    // 🔴 必须 takeLast(5)：接口给的是 `2026-10-23`，`take(5)` 会切出「2026-」，
                    //    参考图中间那列要的是「10-23 17:00」。
                    "${match.date.takeLast(5)} ${match.time}",
                    fontSize = 10.sp,
                    color = TextSub,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                TeamName(
                    rank = rightRank, name = rightTeam, flag = rightFlag,
                    modifier = Modifier.weight(1f), rankAfter = true
                )
            }

            Spacer(Modifier.height(7.dp))

            // —— 玩法网格 ——
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(SurfaceAlt, RoundedCornerShape(8.dp))
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                match.inline.forEach { market ->
                    InlineMarketRow(
                        market = market,
                        match = match,
                        state = state,
                        onPick = { opt -> onPick(market, opt) }
                    )
                }
            }
        }
    }
}

/**
 * 队名 + 主客标记 + 排名。
 *
 * 两侧是**镜像排列**（照参考图）：
 *  - 左：[9] 布里斯班 主   → 排名在前、主客标在名字后
 *  - 右：客 珀斯 [10]      → 主客标在前、排名在名字后
 * 这样两侧的「主/客」都紧贴中间的时间列，视觉上对称。
 */
@Composable
private fun TeamName(
    rank: String,
    name: String,
    flag: String,
    modifier: Modifier,
    rankAfter: Boolean = false
) {
    Row(
        modifier,
        horizontalArrangement = if (rankAfter) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!rankAfter) {
            if (rank.isNotEmpty()) TeamRank(rank, Modifier.padding(end = 3.dp))
        } else {
            TeamFlag(flag, Modifier.padding(end = 3.dp))
        }
        Text(
            name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (!rankAfter) {
            TeamFlag(flag, Modifier.padding(start = 3.dp))
        } else if (rank.isNotEmpty()) {
            TeamRank(rank, Modifier.padding(start = 3.dp))
        }
    }
}

@Composable
private fun TeamRank(rank: String, modifier: Modifier = Modifier) {
    Text("[${rank}]", fontSize = 10.sp, color = TextSub, modifier = modifier, maxLines = 1)
}

/** 主 = 红、客 = 灰（与参考图一致，不与「涨红跌绿」的赔率色混用语义）。 */
@Composable
private fun TeamFlag(flag: String, modifier: Modifier = Modifier) {
    Text(
        flag,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = if (flag == "主") Up else TextSub,
        modifier = modifier,
        maxLines = 1
    )
}

/**
 * 一行玩法：左侧盘口标签（宽 32dp）+ 右侧选项网格。
 *
 * 网格用**等分行**实现而不是 `LazyVerticalGrid` —— 弹窗/卡片里嵌套可滚动网格会撞
 * 「无限高约束」异常（同奖查询那次闪退的同一个根因），这里数据量固定（最多 5 列），
 * 普通 Row 分 weight 最稳。
 */
@Composable
private fun InlineMarketRow(
    market: BetMarket,
    match: BetMatch,
    state: BetUiState,
    onPick: (BetOption) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 🔴 篮球让分 / 大小分：玩法名下面**再挂一行盘口线**（`-9.50` ⇒ `-9.5` / `227.50` ⇒ `227.5`）。
        //    这是盘口线在本页的**唯一出现位置** —— 选项里只有「让主胜 / 让客胜」「大 / 小」。
        //    足球的 `lineLabel` 本来就是线（`0` / `-1`），`lineText` 为空，不受影响。
        //
        // 🔴 标签列宽按运动分开（用户要求「篮球让分大小胜负那列长度给长一点」，
        //    后又要求「让分和大小分显示太紧凑了，让宽一点」⇒ 篮球 44 → 54dp）：
        //    篮球的「大小分」三个字 + 下面一行的盘口线（`-7.5` / `175.5`）挤在 44dp 里两边没留白，
        //    整格发闷；54dp 后两行都有呼吸感。足球那列只有 `0` / `-1` 这种一个字符的线，
        //    但也从 32 放到 36dp，与篮球同一视觉节奏。
        LineLabel(
            market.lineLabel,
            if (match.sport == BetSport.BASKETBALL) 54.dp else 36.dp,
            sub = when (market.pool) {
                BetPool.HDC -> shortLine(market.lineText)
                // 大小分的总分盘口没有「让/受让」语义，接口给的 `+175.50` 去掉加号 ⇒ `175.5`
                BetPool.HILO -> shortTotalLine(market.lineText)
                else -> ""
            }
        )
        Spacer(Modifier.width(4.dp))
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            market.options.forEach { opt ->
                OddCell(
                    opt = opt,
                    selected = state.isPicked(match.matchId, opt.pool.code, opt.key),
                    enabled = !match.ended && opt.sellable,
                    modifier = Modifier.weight(1f),
                    compact = true,
                    onClick = { onPick(opt) }
                )
            }
            // 选项少于列数时补空位，保证不同场次同名列宽一致
            repeat((market.columns - market.options.size).coerceAtLeast(0)) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 左侧窄标签：足球是让球线（0 / -1），篮球是玩法名（`让分` / `大小分` / `胜负`）。
 *
 * [sub] 是标签下追加的**第二行**，目前只有篮球让分 / 大小分用它挂盘口线
 * （`让分` + `-9.5`、`大小分` + `227.5`）。这样盘口线在整页只出现一次，
 * 选项格只写「让主胜 / 让客胜」「大 / 小」。
 *
 * 线颜色：**统一绿色**（用户口径：「让分和大小分的值绿色字体表示」）。历史上的
 * 红（主队让分）/ 绿（主队受让）双色、以及大小分的中性色都已取消 —— 一个灰底小格
 * 里两行字，再分红绿只会让这一列花掉，绿色一色反而更好认。
 */
@Composable
fun LineLabel(text: String, width: Dp, sub: String = "") {
    Box(
        Modifier
            .width(width)
            .heightIn(min = 36.dp)
            .background(SurfaceTint, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Flat,
                textAlign = TextAlign.Center,
                // 🔴 窄屏（36dp）下必须留 2 行：足球窄标签只有数字，篮球 54dp 后
                //    「大小分」能一行排完，两者都能正常显示。改成 1 行会在窄列里裁字。
                maxLines = 2
            )
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Down,
                    maxLines = 1,
                    // 主文案与线值之间留 1dp：之前两行紧贴，整格看着发闷
                    modifier = Modifier.padding(top = 1.dp),
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeight = 12.sp
                    )
                )
            }
        }
    }
}

/**
 * 单个赔率格。
 *
 * [compact] = true 用于卡片内联（横向：玩法 赔率↑）；
 * false 用于弹窗网格（纵向：玩法在上、赔率在下，5 列时横向放不下「让主胜(+1.5)」这类长文案）。
 *
 * [dense] = true 时进一步压低高度（弹窗紧凑模式用）。
 */
@Composable
fun OddCell(
    opt: BetOption,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    dense: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        selected -> Primary.copy(alpha = .12f)
        !enabled -> SurfaceTint.copy(alpha = .55f)
        else -> Surface
    }
    val borderColor = if (selected) Primary else LineSoft
    val labelColor = if (selected) Primary else if (enabled) TextMain else TextSub.copy(alpha = .6f)

    Box(
        modifier
            .height(if (compact) 34.dp else if (dense) 38.dp else 44.dp)
            .background(bg, RoundedCornerShape(5.dp))
            .border(if (selected) 1.2.dp else 0.8.dp, borderColor, RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    opt.label,
                    fontSize = 10.5.sp,
                    color = labelColor,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(3.dp))
                OddsText(opt, selected, enabled)
            }
        } else {
            // 🔴 纵向两行（玩法 / 赔率）必须关掉 `includeFontPadding`：
            //    默认 true 时中文回退字体的上下度量会把每行撑到 ≈1.6 倍字号，
            //    两行叠加直接超出格子高度 ⇒ 赔率被裁掉一部分（实测 34dp 时只露出数字顶部）。
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    opt.label,
                    color = labelColor,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = if (dense) 10.sp else 11.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    )
                )
                Spacer(Modifier.height(if (dense) 1.dp else 2.dp))
                OddsText(opt, selected, enabled, small = dense)
            }
        }
    }
}

/**
 * 赔率数字 + 涨跌箭头。
 *
 * 🔴 涨红跌绿是**中国股市/彩市惯例**（与欧美相反），别按 Material 的 error/success 语义套。
 */
@Composable
private fun OddsText(opt: BetOption, selected: Boolean, enabled: Boolean, small: Boolean = false) {
    val numColor = when {
        !enabled -> TextSub.copy(alpha = .6f)
        selected -> Primary
        else -> TextMain
    }
    val arrowColor = when {
        opt.trend > 0 -> Up
        opt.trend < 0 -> Down
        else -> TextSub
    }
    // 与标签同理：关掉字体额外行距，否则两行文字叠加会顶出格子。
    val numStyle = TextStyle(
        fontSize = if (small) 11.sp else 11.5.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (opt.sellable) String.format("%.2f", opt.odds) else "-",
            fontWeight = FontWeight.Bold,
            color = numColor,
            style = numStyle,
            maxLines = 1
        )
        if (opt.trend != 0) {
            Text(
                if (opt.trend > 0) "↑" else "↓",
                fontSize = 9.sp,
                color = arrowColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 1.dp),
                maxLines = 1
            )
        }
    }
}

// ============================== 底部投注栏 ==============================

/**
 * 底部栏：**只有「倍数 + 清单 + 保存」**，没有金额、也没有「已选 N 项」。
 *
 * 用户要求：
 *  - 「已选 N 项」去掉（卡片右上角仍有「已选 N」小徽章，够看）；
 *  - 「清单」二字**红色**，点击打开清单弹窗；
 *  - 「保存」按钮宽一些。
 */
@Composable
private fun BetSlipBar(
    state: BetUiState,
    onTimes: (Int) -> Unit,
    onToggleParlay: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onOnlySingle: () -> Unit,
    onOpenSlip: () -> Unit,
    onPlace: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(bottom = 7.dp))

        // —— 第一行：过关方式（多选，可横滑）+ 倍数（右）——
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("过关", fontSize = 11.sp, color = TextSub, modifier = Modifier.padding(end = 6.dp))
                val opts = state.parlayOptions
                if (opts.isEmpty()) {
                    Text("先选比赛", fontSize = 11.sp, color = TextSub.copy(alpha = .6f))
                } else {
                    // 多选：勾中的关次一起算注数（单关 + 2串1 + 3串1 = 自由过关）
                    opts.forEach { m ->
                        ParlayChip(
                            label = if (m == 1) "单关" else "${m}串1",
                            selected = m in state.effectiveMs,
                            enabled = true
                        ) { onToggleParlay(m) }
                    }
                    // 全选 = 竞彩的「M串(2^M-1)」；已全选时再点回到只打单关
                    val all = opts.toSet()
                    val isAll = state.effectiveMs.toSet() == all
                    Text(
                        if (isAll) "仅单关" else "全选",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primary,
                        maxLines = 1,
                        modifier = Modifier
                            .clickable { if (isAll) onOnlySingle() else onSelectAll() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            TimesEditor(state.times, onTimes)
        }

        Spacer(Modifier.height(6.dp))

        // —— 第二行：过关名 + 注数/本金 + 清单 + 下单 ——
        // 🔴 只有「注数」和「本金」：不再显示理论最高奖金（用户要求：买的时候别看中间金额，
        //    中奖多少等结算后在记录/账本里看），也不显示余额（本 App 没有账户）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        state.parlayName,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primary,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${state.betCount}",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Up
                    )
                    Text(" 注 · 本金 ", fontSize = 11.sp, color = TextSub)
                    Text(
                        "¥${fmt(state.stake)}",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Up
                    )
                }
                // 🔴 关次没选时给一句人话提示：否则下面只有「未选关次 0 注 · 本金 ¥0.00」，
                //    用户可能以为是数据加载不出来。单关不再默认勾选（用户要求）。
                // 关次清单 + 复式标记：只在「多关次 / 复式」时出现，否则不占地方
                val sub = if (state.effectiveMs.isEmpty()) {
                    "点上面的过关方式开始"
                } else buildString {
                    if (state.effectiveMs.size > 1) append(Parlay.legsText(state.effectiveMs))
                    if (state.compound) {
                        if (isNotEmpty()) append(" · ")
                        append("复式")
                    }
                }
                if (sub.isNotEmpty()) {
                    Text(sub, fontSize = 10.sp, color = TextSub, maxLines = 1)
                }
            }

            // 清单：红色（用户要求）
            Text(
                "清单",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = Up,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onOpenSlip)
                    .padding(horizontal = 7.dp, vertical = 8.dp)
            )

            Spacer(Modifier.width(3.dp))

            // 按钮按要求放宽：固定最小宽度 + 稍微加高，长宽比更接近参考图的「大按钮」。
            Button(
                onClick = onPlace,
                enabled = state.pickCount > 0 && state.betCount > 0 && !state.placing,
                shape = RoundedCornerShape(19.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    disabledContainerColor = Primary.copy(alpha = .35f)
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(38.dp)
                    .widthIn(min = 96.dp)
            ) {
                Text(
                    if (state.placing) "提交中" else "投注",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 过关方式胶囊（单关 / 2串1 / …）。
 *
 * 🔴 关次上限 = 已选**场次**数，不是选项数：同场选 3 个比分仍然只能算「1 场」，
 *    2串1 必须是两个不同场次。
 * 🔴 **可多选**：同时点亮 2串1 和 3串1 就是竞彩的「3串4」（4 注）。
 */
@Composable
private fun ParlayChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = when {
            selected -> Color.White
            enabled -> TextSub
            else -> TextSub.copy(alpha = .35f)
        },
        maxLines = 1,
        modifier = Modifier
            .padding(end = 5.dp)
            .background(
                if (selected) Primary else SurfaceAlt,
                RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

/**
 * 倍数编辑器：`[−] [输入框] [＋] 倍`，整体绿色。
 *
 * 🔴 输入框用 [BasicTextField] + 本地 draft，而不是直接把 `times` 当 value：
 *    否则「删空再输入」会被立刻回填，用户按键像被吃掉。
 *    提交策略 = 边输边解析（非法/为空时只留草稿，不改状态），失焦时回落到当前倍数。
 */
@Composable
private fun TimesEditor(times: Int, onTimes: (Int) -> Unit) {
    // 跟随外部倍数变化重置草稿（点 +/- 或下单后归 1 都要同步）
    var draft by remember(times) { mutableStateOf(times.toString()) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TimesStepBtn("−", enabled = times > 1) { onTimes(times - 1) }

        Box(
            Modifier
                .width(46.dp)
                .height(32.dp)
                .background(Surface, RoundedCornerShape(6.dp))
                .border(1.2.dp, Down.copy(alpha = .55f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(2)
                    draft = digits
                    val n = digits.toIntOrNull()
                    if (n != null && n >= 1) onTimes(n)
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Down,
                    textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        TimesStepBtn("＋", enabled = times < com.example.jingcai.data.model.BetTicket.MAX_TIMES) {
            onTimes(times + 1)
        }

        Text("倍", fontSize = 10.sp, color = Down, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TimesStepBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .background(Down.copy(alpha = if (enabled) .12f else .05f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Down else Down.copy(alpha = .3f)
        )
    }
}

// ============================== 轻提示 ==============================

@Composable
private fun BetToast(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            msg,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(bottom = 96.dp)
                .background(Color(0xE6202737), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
