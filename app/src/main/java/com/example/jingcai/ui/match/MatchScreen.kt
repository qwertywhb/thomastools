package com.example.jingcai.ui.match

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jingcai.data.model.Match
import com.example.jingcai.ui.*
import com.example.jingcai.ui.theme.*
import android.widget.Toast

/**
 * 比赛列表（首页 tab）。
 *
 * ── 本轮改动（用户：①「布局现代化一点」②「往下滑动中间会有某条空白很明显」）─────────
 *
 * **② 空白行的真因（性能/动画 bug，不是布局）**
 * 旧版给**每一个列表项**都套了 `AnimatedVisibility(visible = remember(m){false})` +
 * `LaunchedEffect { visible = true }`，入场上滑 + 淡入，还按 index 加了 28ms 级联延迟。
 * 这套动画只在「首屏首帧」成立，一旦用户开始滚动，LazyColumn 会**新组合**进入视野的项：
 *  - 新组合的项 `visible` 初值又是 `false` ⇒ 该行先以 **0 高度/全透明** 存在一帧；
 *  - `LaunchedEffect` 下一帧才置 true，然后再花 240ms 淡入；
 *  - 快速滑动 + 回收复用同时发生，几行同时处于「已占位但还没显现」的状态
 *    ⇒ 屏幕上就出现**一条明显的空白**（甚至能看到半透明的一行）。
 * 实测截图里「周四008 半透明 + 中间大片空白 + 周四010」正是这个状态。
 *
 * 修法：**彻底去掉逐项入场动画**，改成整个列表一次性 180ms 淡入（只在首次进入/换日期时发生，
 * 不会因为滚动而触发）；滚动时每行都是「首帧可见」，不再有空白。
 *
 * **① 现代化**
 * 顶部原是「两个裸箭头 + 一行裸文字 + 一行裸开关」浮在页面底色上，没有容器、没有层次。
 * 现在合并成一张白色圆角控制卡：圆形箭头按钮（浅底 + 按压缩放）、居中的加粗日期、
 * 「今天」小胶囊、下分隔线、以及带「已隐藏 N 场」提示的开关行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchScreen(
    vm: MatchViewModel = viewModel(),
    onSameOdds: (Match) -> Unit,
    onOddsChange: (Match) -> Unit,
    onRecentForm: (Match) -> Unit
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current

    // ── 页面可见性 → 60 秒自动刷新开关 ──────────────────────────────
    // 切走 tab / 退后台 / 锁屏时立刻停；否则 viewModelScope 里那个 while(true)
    // 会一天跑几百次（费流量、费电，还会被源站按频率限流）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> vm.setActive()
                Lifecycle.Event.ON_PAUSE -> vm.setInactive()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 「已开赛」是随时间变化的量：每 30 秒推进一次当前时刻，让刚开赛的场次自动从列表退场。
    // 不推进的话，页面停在原地就永远按「进入时的那一秒」判，开赛后还一直挂在列表上。
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            vm.tick()
        }
    }

    // 有数据时的刷新失败 → 一条 Toast（不遮挡列表）。整页错误走下面的 ErrorText。
    LaunchedEffect(state.error) {
        val msg = state.error
        if (msg != null && state.allMatches.isNotEmpty()) {
            Toast.makeText(ctx, friendlyError(msg), Toast.LENGTH_SHORT).show()
            vm.consumeError()
        }
    }

    // 整表一次性淡入（替代旧的逐项动画）：只在进入页面时跑一次，滚动不会再次触发。
    var listAlpha by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { listAlpha = 1f }
    val alpha by animateFloatAsState(listAlpha, tween(180), label = "listAlpha")

    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    // 🔴 只能「数据回来 → endRefresh」，**不能**在这里 startRefresh：
    //    上一行在 isRefreshing 的上升沿发请求，如果这里又程序化 startRefresh，
    //    就会形成「startRefresh → 侦测到 true → 再发请求」的无限刷新循环（真机上表现为顶部一直转圈）。
    LaunchedEffect(state.refreshing) {
        if (!state.refreshing) pullState.endRefresh()
    }

    // 🔴 material3 1.2.0 没有 PullToRefreshBox，只能手动挂 nestedScroll（与赛程页同一套写法）。
    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .nestedScroll(pullState.nestedScrollConnection)
    ) {
        Column(Modifier.fillMaxSize()) {
            MatchHeader(state, vm)

            // 静默刷新：顶部 2dp 细条。固定占 2dp（无刷新时放透明占位），
            // 否则细条出现/消失会让整页上下跳 2dp。
            if (state.refreshing) ThinProgressBar() else Spacer(Modifier.height(2.dp))

            when {
                // 只在「一点内容都没有」时整页转圈：切日期有旧列表时不抹掉内容
                state.loading && state.allMatches.isEmpty() -> Loading()
                state.error != null && state.allMatches.isEmpty() ->
                    ErrorText(state.error, onRetry = { vm.retry() })
                state.visibleMatches.isEmpty() -> EmptyResult(state)
                else -> LazyColumn(
                    // 刷新中把内容压暗：列表明明是旧日期的数据，压暗才不会让人误读
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { this.alpha = alpha * if (state.refreshing) 0.55f else 1f },
                    state = listState,
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 🔴 这里**不能**再包 AnimatedVisibility / animateItem 之类的逐项动画：
                    //    滚动时新组合的项会先以 0 高度或全透明存在若干帧，就是「中间一条空白」的成因。
                    itemsIndexed(state.visibleMatches, key = { i, m -> "${m.mid}-$i" }) { _, m ->
                        MatchCard(m, onSameOdds, onOddsChange, onRecentForm)
                    }
                }
            }
        }

        PullToRefreshContainer(
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * 顶部控制卡：日期切换 + 搜索 + 已开赛开关。
 *
 * 🔴 **本轮合并掉一整行**：旧版把「显示已开赛」单独放一行（一个裸 Switch + 右侧大片空白），
 * 再加上一条分隔线，稳定吃掉约 34dp 首屏高度 —— 而它只是一个布尔开关。
 * 现在开关改成[日期行右端的一枚胶囊][EndedChip]，同一个控件顺手承担「隐藏了几场」的告知，
 * 分隔线一起删掉。整张控制卡从三行变两行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchHeader(state: MatchUiState, vm: MatchViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, Line)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArrowButton("‹") { vm.load(shiftDate(state.date, -1)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    // 日期切换时淡入上滑
                    AnimatedContent(
                        targetState = state.date,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 })
                                .togetherWith(fadeOut(tween(150)))
                        },
                        label = "date"
                    ) { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
                            if (state.isToday) {
                                Spacer(Modifier.width(6.dp))
                                TodayPill()
                            }
                        }
                    }
                }
                ArrowButton("›") { vm.load(shiftDate(state.date, 1)) }
                Spacer(Modifier.width(6.dp))
                EndedChip(state, vm)
            }

            SearchRow(state, vm)
        }
    }
}

/**
 * 「含已开赛」开关（原「显示已开赛」一行）。
 *
 * 一枚胶囊同时表达两件事：
 *  - **开/关**：底色实心 = 含已开赛场次；浅底 = 不含；
 *  - **隐藏了多少**（仅关闭且没在搜索/关注过滤时）：文案直接变成「已隐藏 N 场」，
 *    省掉旧版那枚单独的徽标，也顺手解决了「列表看着少了几场但不知道为什么」。
 *
 * 为什么不再用 Switch：Switch 必须配文字标签才看得懂，两者并排就是一行；
 * 而这一行只有这一个控件，右侧全是空白。
 */
@Composable
private fun EndedChip(state: MatchUiState, vm: MatchViewModel) {
    val on = state.showEnded
    val filtering = state.query.isNotBlank() || state.onlyFollowedTeams
    val label = when {
        on -> "含已开赛"
        // 过滤态下「已隐藏 N 场」会误导（那是搜索过滤掉的，不是开赛过滤），退回中性文案
        !filtering && state.hiddenCount > 0 -> "已隐藏 ${state.hiddenCount} 场"
        else -> "不含已开赛"
    }
    Box(
        Modifier
            .height(26.dp)
            .background(if (on) Primary else SurfaceAlt, RoundedCornerShape(999.dp))
            // 🔴 关态必须描边：SurfaceAlt 在白卡上几乎看不出边界，实测截图里它跟
            //    「不含已开赛」这句正文长得一模一样，用户根本不知道这里能点。
            //    开态是实心主色，本来就有边界，不必再描。
            .then(
                if (on) Modifier
                else Modifier.border(1.dp, Line, RoundedCornerShape(999.dp))
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { vm.toggleShowEnded() }
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (on) Color.White else TextSub,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * 搜索行：本地过滤，不发请求。
 *
 * 为什么用 [BasicTextField] 自绘而不是 `OutlinedTextField`：后者的最小高度是 56dp，
 * 塞进顶部卡会把卡撑得过高（手机上列表区本来就紧）。这里是 32dp 的小胶囊。
 */
@Composable
private fun SearchRow(state: MatchUiState, vm: MatchViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.5.sp, color = TextMain),
            cursorBrush = SolidColor(Primary),
            modifier = Modifier.weight(1f).height(32.dp),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxSize()
                        .background(SurfaceAlt, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔍", fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (state.query.isEmpty()) {
                            Text("搜索队伍 / 联赛 / 编号", fontSize = 12.sp, color = TextSub.copy(alpha = 0.75f))
                        }
                        inner()
                    }
                    if (state.query.isNotEmpty()) {
                        Text(
                            "✕", fontSize = 11.sp, color = TextSub,
                            modifier = Modifier
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    vm.setQuery("")
                                }
                                .padding(start = 6.dp)
                        )
                    }
                }
            }
        )
        Spacer(Modifier.width(8.dp))
        // 「只看关注球队」——没有关注任何队时点了也是空的，所以先给出可解释的空态（见 EmptyResult）
        val on = state.onlyFollowedTeams
        Box(
            Modifier
                .height(32.dp)
                .background(if (on) Primary else SurfaceAlt, RoundedCornerShape(999.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { vm.toggleOnlyFollowedTeams() }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (state.followedTeams.isEmpty()) "☆ 关注" else "★ ${state.followedTeams.size}",
                fontSize = 11.5.sp,
                color = if (on) Color.White else TextSub,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

/** 空列表的三种成因，文案必须能指向下一步动作，不能统一写「暂无比赛」。 */
@Composable
private fun EmptyResult(state: MatchUiState) {
    val q = state.query.trim()
    when {
        q.isNotEmpty() -> EmptyText("没有找到「$q」相关的比赛\n换个队名或竞彩编号试试")
        state.onlyFollowedTeams && state.followedTeams.isEmpty() ->
            EmptyText("还没有关注的球队\n点比赛卡右上角的 ☆ 关注一支队")
        state.onlyFollowedTeams -> EmptyText("今天没有关注球队的比赛")
        else -> EmptyText("暂无比赛")
    }
}

/** 「今天」小胶囊。 */
@Composable
private fun TodayPill() {
    Box(
        Modifier
            .background(Primary, RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text("今天", color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

// MatchCard / MiniTag 已抽到 com.example.jingcai.ui.MatchCard.kt，供「比赛」tab 使用。

/** 日期切换箭头：圆形浅底按钮，按下时缩放反馈。 */
@Composable
private fun ArrowButton(glyph: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, tween(120), label = "arrowScale")
    Box(
        Modifier
            .size(34.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(SurfaceAlt, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, fontSize = 20.sp, color = Primary, fontWeight = FontWeight.Bold)
    }
}
