package com.example.jingcai.ui.league

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jingcai.data.crash.CrashLog
import com.example.jingcai.data.model.LeagueInfo
import com.example.jingcai.data.update.UpdateManager
import com.example.jingcai.data.model.LeagueSeason
import com.example.jingcai.data.model.ScheduleMatch
import com.example.jingcai.data.model.StandingRow
import com.example.jingcai.data.repository.ThemeMode
import com.example.jingcai.data.repository.UserPrefs
import com.example.jingcai.ui.*
import com.example.jingcai.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeagueScreen(vm: LeagueViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val themeMode by UserPrefs.themeMode.collectAsState()
    var showTree by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    var showCrash by remember { mutableStateOf(false) }
    /** 积分榜里被点开的球队（null = 不显示球队弹层）。 */
    var pickedTeam by remember { mutableStateOf<StandingRow?>(null) }
    // 读崩溃日志放到 key 上来「一次性」取：每次重组都读文件没必要（虽然 exists 很快）。
    val crashText = remember(showCrash) { CrashLog.read(ctx) }

    // ── 下拉刷新 ─────────────────────────────────────────────
    // 联赛/积分榜数据变得慢，但「下拉刷新」是用户手上的肌肉记忆；缺了会以为页面卡死。
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState) {
        snapshotFlow { pullState.isRefreshing }.collect { on -> if (on) vm.refresh() }
    }
    // 同上：只在数据回来时收起指示器，绝不程序化 startRefresh（会形成刷新循环）。
    LaunchedEffect(state.refreshing) {
        if (!state.refreshing) pullState.endRefresh()
    }

    Box(
        Modifier
            .fillMaxSize()
            .nestedScroll(pullState.nestedScrollConnection)
    ) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 联赛头部：热门联赛高亮快捷条 + 全部联赛（展开左侧树） ──
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(3.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("热门联赛", color = TextSub, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    // 手动检查更新：显示当前版本号，点一下立刻查一次 GitHub Release。
                    // 自动检查是 6 小时一次且只在冷启动，这里给一个「我想现在就看」的出口。
                    Text(
                        "v" + UpdateManager.versionName(ctx),
                        fontSize = 11.sp, color = TextSub,
                        modifier = Modifier
                            .clickable { UpdateManager.check(ctx, manual = true) }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    // 上次崩溃的现场（仅在存在日志时出现）。点开可看堆栈 / 复制 / 清除。
                    // 平时不显示：没有异常时它只会是个多余的红色噪点。
                    if (crashText != null) {
                        Text(
                            "崩溃日志",
                            fontSize = 10.sp,
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .background(Up, RoundedCornerShape(999.dp))
                                .clickable { showCrash = true }
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    // 轻量入口：点击展开/收起全部联赛树（不再使用「隐藏全部联赛 ▴」文案）
                    OutlinedButton(
                        onClick = { showTree = !showTree },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(if (showTree) "收起 ▴" else "全部联赛 ▾", fontSize = 12.sp)
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.hotLeagues.isEmpty()) {
                        Text("加载中...", color = TextSub, fontSize = 12.sp)
                    }
                    state.hotLeagues.forEach { l ->
                        LeagueChip(l, state.current?.uniformLeagueId == l.uniformLeagueId) { vm.selectLeague(l) }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("赛季", color = TextSub, fontSize = 11.sp)
                    Spacer(Modifier.width(10.dp))
                    SeasonDropdown(state.current, state.season) { vm.selectSeason(it) }
                }

                // ── 外观（深色模式）──
                // 全 App 没有独立设置页，「资料」是唯一的常驻配置位，所以外观放这里。
                // 三态而不是一个开关：只给「深/浅」两态的话，想回到默认的人无处可去。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("外观", color = TextSub, fontSize = 11.sp)
                    Spacer(Modifier.width(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeMode.values().forEach { m ->
                            ThemeChip(m.label, themeMode == m) { UserPrefs.setThemeMode(m) }
                        }
                    }
                }
            }
        }

        // ── 其他联赛：左侧可折叠树（按大洲分组），与 WinForms TreeView 一致 ──
        // 选中某个联赛后自动收起整棵树（onPicked 回调），把屏幕空间让给数据区。
        if (showTree) {
            LeagueTree(state, vm) { showTree = false }
        }

        // ── 赛程 / 积分榜 分段 ──
        Row(Modifier.fillMaxWidth().background(SurfaceTint, RoundedCornerShape(12.dp)).padding(3.dp)) {
            SegBtn("赛程赛果", state.tab == 0, Modifier.weight(1f)) { vm.switchTab(0) }
            SegBtn("积分榜", state.tab == 1, Modifier.weight(1f)) { vm.switchTab(1) }
        }

        // ── 赛程：日期选择条（当天 / ±7 天 / 指定日期） ──
        if (state.tab == 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(
                    onClick = { vm.shiftRange(-7) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp)
                ) { Text("‹ 前七天", fontSize = 12.sp) }

                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { showDatePicker = true }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📅 ", fontSize = 12.sp)
                        Text(
                            "${state.rangeStart} 至 ${state.rangeEnd}",
                            // 13sp 时「2026-10-22 至 2026-10-29」≈150dp，超出中间可用宽 ≈145dp
                            // ⇒ 会被挤成两行（实测截图里日期断开）。12sp ≈138dp 可单行显示。
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primary,
                            maxLines = 1
                        )
                    }
                }

                TextButton(
                    onClick = { vm.shiftRange(7) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp)
                ) { Text("后七天 ›", fontSize = 12.sp) }
                TextButton(
                    onClick = { vm.resetRange() },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp)
                ) { Text("回到默认", fontSize = 12.sp) }
            }
        }

        when {
            state.loading -> Loading()
            state.error != null -> ErrorText(state.error)
            state.tab == 0 -> ScheduleList(state.schedule.flatMap { it.matches })
            else -> StandingTable(state.standings) { pickedTeam = it }
        }
    }
        PullToRefreshContainer(
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    // 积分榜行下钻：这支球队本赛季还有哪些球、什么时候踢。
    // 数据直接用本页已加载的赛程（`state.schedule`），不额外发一次请求 ——
    // 积分榜与赛程本来就是同一个联赛的同一批数据，再请求一次只会慢、不会多。
    pickedTeam?.let { t ->
        TeamSheet(
            row = t,
            matches = state.schedule.flatMap { it.matches }
                .filter { it.home == t.team || it.away == t.team },
            onDismiss = { pickedTeam = null }
        )
    }

    if (showCrash && crashText != null) {
        CrashLogDialog(
            text = crashText,
            onDismiss = { showCrash = false },
            onClear = {
                CrashLog.clear(ctx)
                showCrash = false
            }
        )
    }

    if (showDatePicker) {
        RangePickerDialog(
            start = state.rangeStart,
            end = state.rangeEnd,
            onDismiss = { showDatePicker = false },
            onConfirm = { s, e -> vm.setRange(s, e); showDatePicker = false }
        )
    }
}

/**
 * 热门联赛 chip：选中时用主色渐变填充；未选中态给**可见的浅底 + 描边**。
 *
 * 旧版未选中是「白色渐变铺在白色卡片上」——等于没有底，chip 看起来像漂浮的裸文字
 * （页面加深后更明显）。现在未选中用 [SurfaceAlt] 底 + [Line] 描边，边界清楚。
 */
@Composable
private fun LeagueChip(l: LeagueInfo, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(
            if (on) Brush.horizontalGradient(listOf(Primary, PrimaryDark))
            else Brush.verticalGradient(listOf(SurfaceAlt, SurfaceAlt)),
            RoundedCornerShape(999.dp)
        )
            .then(if (on) Modifier else Modifier.border(1.dp, Line, RoundedCornerShape(999.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            l.name,
            color = if (on) Color.White else TextMain,
            fontSize = 13.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** 左侧树：大洲分组 → 联赛；点分组可折叠，点联赛即切换并自动收起整棵树。 */
@Composable
private fun LeagueTree(state: LeagueUiState, vm: LeagueViewModel, onPicked: () -> Unit) {
    val expanded = remember { mutableStateMapOf<String, Boolean>().apply { put("热门", true) } }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(3.dp)) {
        Column(Modifier.heightIn(max = 300.dp).padding(vertical = 6.dp)) {
            Text("全部联赛", color = TextSub, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                state.groups.forEach { g ->
                    val open = expanded[g.name] == true
                    item(key = "g_${g.name}") {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { expanded[g.name] = !open }
                                .background(SurfaceAlt)
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (open) "▾" else "▸", fontSize = 11.sp, color = TextSub)
                            Spacer(Modifier.width(6.dp))
                            Text(g.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                            Spacer(Modifier.width(6.dp))
                            Text("${g.leagues.size}", fontSize = 11.sp, color = TextSub)
                        }
                    }
                    if (open) {
                        items(g.leagues, key = { "l_${g.name}_${it.uniformLeagueId}" }) { l ->
                            val on = state.current?.uniformLeagueId == l.uniformLeagueId
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { vm.selectLeague(l); onPicked() }
                                    // 选中底走主题（原先写死 #FBE3E3/白，深色下会是两块亮斑）
                                    .background(if (on) SurfaceTint else Surface)
                                    .padding(start = 34.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    l.name, fontSize = 13.sp,
                                    color = if (on) Primary else TextSub,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 日期范围选择弹窗：起止日期各一个 yyyy-MM-dd 输入 + 预设按钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(
    start: String,
    end: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var s by remember { mutableStateOf(start) }
    var e by remember { mutableStateOf(end) }
    val datePickerState = rememberDateRangePickerState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择赛程日期范围", fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = s, onValueChange = { s = it },
                        label = { Text("开始", fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = e, onValueChange = { e = it },
                        label = { Text("结束", fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { s = today(); e = shiftDate(today(), 7) },
                        label = { Text("今天起 7 天", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = { s = shiftDate(today(), -7); e = today() },
                        label = { Text("近 7 天", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = { s = today(); e = today() },
                        label = { Text("仅今天", fontSize = 11.sp) }
                    )
                }
                DateRangePicker(
                    state = datePickerState,
                    title = null,
                    headline = null,
                    showModeToggle = false,
                    modifier = Modifier.height(320.dp)
                )
                // 日历选择后同步回输入框
                LaunchedEffect(datePickerState.selectedStartDateMillis, datePickerState.selectedEndDateMillis) {
                    datePickerState.selectedStartDateMillis?.let { s = millisToDate(it) }
                    datePickerState.selectedEndDateMillis?.let { e = millisToDate(it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (s.isNotBlank() && e.isNotBlank()) onConfirm(s.trim(), e.trim()) }) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** yyyy-MM-dd ↔ 毫秒（UTC 零点，仅用于日历回填）。 */
private fun millisToDate(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(java.util.Date(millis))

@Composable
private fun SegBtn(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        // 选中底用 Surface（原先写死白色 ⇒ 深色模式下选中的那个是白块）
        modifier.background(if (on) Surface else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        // 选中字用 Up 而不是 Primary：深色底上 Primary(#C62828) 对比只有 ~3:1，
        // 「赛程赛果」会糊成暗红块；Up 在深色下调亮过一档，两种模式都可读。
        Text(text, color = if (on) Up else TextSub, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * 外观（跟随系统 / 浅色 / 深色）三选的小胶囊。
 *
 * 选中＝主色实心 + 白字（与 [LeagueChip] 选中态一致），未选中＝[SurfaceAlt] 底 + 副文字色。
 * 嵌在白卡里所以不加描边：加了会比同一行的「赛季」文字还重，视线被吸住。
 */
@Composable
private fun ThemeChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.sp,
        color = if (on) Color.White else TextSub,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .background(if (on) Primary else SurfaceAlt, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun SeasonDropdown(league: LeagueInfo?, season: LeagueSeason?, onSelect: (LeagueSeason) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(34.dp)
        ) {
            Text(season?.seasonName ?: "请选择赛季", fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text("▾", fontSize = 11.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            league?.seasons?.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.seasonName) },
                    onClick = { onSelect(s); expanded = false }
                )
            }
        }
    }
}

/** 赛程赛果：外层固定表头 + 可滚动列表，不截断。已去掉「阶段」列，宽度让给「日期」以显示完整 yyyy-MM-dd。 */
@Composable
private fun ScheduleList(matches: List<ScheduleMatch>) {
    if (matches.isEmpty()) { EmptyText("该时间段暂无比赛"); return }
    Card(Modifier.fillMaxSize(), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(3.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(SurfaceTint).padding(vertical = 8.dp)) {
                H("日期", 0.22f); H("时间", 0.10f); H("主队", 0.22f)
                H("比分", 0.12f); H("客队", 0.22f); H("轮次", 0.12f)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                // 隔行浅底：与表头 [SurfaceTint] 拉开层级，长列表逐行更好跟读（原来纯白底「太淡」）。
                itemsIndexed(matches) { idx, m ->
                    val score = m.score.ifEmpty { m.status.ifEmpty { "-" } }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (idx % 2 == 1) SurfaceAlt else Color.Transparent)
                            .padding(vertical = 9.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        C(m.matchDate.take(10), 0.22f, sub = true)
                        C(m.matchTime, 0.10f, sub = true)
                        C(m.home, 0.22f)
                        C(score, 0.12f, color = Up, bold = true)
                        C(m.away, 0.22f)
                        // 轮次列：只显示轮次，绝不回退到阶段（phase）。week 为空时统一显示 "-"，
                        // 以免该列看起来消失，同时满足「去掉阶段」的验收要求。
                        C(if (m.week.isNotEmpty()) "第${m.week}轮" else "-", 0.12f, sub = true)
                    }
                    HorizontalDivider(color = Line, thickness = 0.5.dp)
                }
            }
        }
    }
}

/**
 * 积分榜：外层固定表头 + 纵向 LazyColumn（不截断）。
 *
 * 宽度策略（本轮回修，彻底解决「滑动太多」）：
 * - 正常屏：表宽 = 父容器可用宽（[fillMaxWidth]，零横向滚动），各列按 [STANDING_WEIGHTS] 用 weight 分配。
 *   经宽度验算，各列内容最小总宽 ≈ 230dp，故 ≥320dp 的可用宽即可一屏显示完且各列有余量。
 * - 超窄屏（可用宽 < 320dp，如极限 320dp 小屏减去内边距）：兜底退化为固定 [STANDING_MIN_TABLE_W]
 *   宽表头 + 横向滚动，避免球队名被挤到不可读。
 * - 表头与数据行使用同一套权重，保证严格对齐。
 */
@Composable
private fun StandingTable(rows: List<StandingRow>, onPickTeam: (StandingRow) -> Unit = {}) {
    if (rows.isEmpty()) { EmptyText("暂无积分榜"); return }
    Card(Modifier.fillMaxSize(), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(3.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 320dp 以下才退化横滑；否则一屏显示完。
            val narrow = maxWidth < STANDING_MIN_SCREEN_W
            if (narrow) {
                Column(
                    Modifier.fillMaxSize().horizontalScroll(rememberScrollState())
                ) {
                    StandingHeader(Modifier.width(STANDING_MIN_TABLE_W))
                    LazyColumn(Modifier.width(STANDING_MIN_TABLE_W).fillMaxHeight()) {
                        standingItems(rows, onPickTeam)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    StandingHeader(Modifier.fillMaxWidth())
                    LazyColumn(Modifier.fillMaxWidth().fillMaxHeight()) {
                        standingItems(rows, onPickTeam)
                    }
                }
            }
        }
    }
}

/** 积分榜各列权重（合计 1.0）：球队列 0.25 可容纳 5 字队名，数字列紧凑。表头与数据行共用。 */
private val STANDING_WEIGHTS = floatArrayOf(
    0.09f,  // 排名
    0.25f,  // 球队
    0.075f, // 赛
    0.075f, // 胜
    0.075f, // 平
    0.075f, // 负
    0.075f, // 进
    0.075f, // 失
    0.085f, // 净（含负号，略放宽）
    0.105f  // 积分
)

/** 常规最小可用屏宽：低于此值才启用横向滚动兜底。 */
private val STANDING_MIN_SCREEN_W = 320.dp

/** 兜底固定表宽：按各列内容最小总宽（≈230dp）留足余量。 */
private val STANDING_MIN_TABLE_W = 320.dp

/** 积分榜表头：与数据行共用 [STANDING_WEIGHTS]。 */
@Composable
private fun StandingHeader(modifier: Modifier) {
    Row(modifier.background(SurfaceTint).padding(vertical = 6.dp)) {
        H("排名", STANDING_WEIGHTS[0]); H("球队", STANDING_WEIGHTS[1]); H("赛", STANDING_WEIGHTS[2])
        H("胜", STANDING_WEIGHTS[3]); H("平", STANDING_WEIGHTS[4]); H("负", STANDING_WEIGHTS[5])
        H("进", STANDING_WEIGHTS[6]); H("失", STANDING_WEIGHTS[7]); H("净", STANDING_WEIGHTS[8])
        H("积分", STANDING_WEIGHTS[9])
    }
}

/** 积分榜数据行（含分隔线）：与表头共用 [STANDING_WEIGHTS]。 */
private fun androidx.compose.foundation.lazy.LazyListScope.standingItems(
    rows: List<StandingRow>,
    onPick: (StandingRow) -> Unit
) {
    itemsIndexed(rows) { idx, t ->
        Row(
            Modifier.fillMaxWidth()
                .background(if (idx % 2 == 1) SurfaceAlt else Color.Transparent)
                .clickable { onPick(t) }
                .padding(vertical = 6.dp)
        ) {
            C(t.rank, STANDING_WEIGHTS[0]); C(t.team, STANDING_WEIGHTS[1], left = true)
            C(t.played.toString(), STANDING_WEIGHTS[2]); C(t.win.toString(), STANDING_WEIGHTS[3])
            C(t.draw.toString(), STANDING_WEIGHTS[4]); C(t.lose.toString(), STANDING_WEIGHTS[5])
            C(t.goal.toString(), STANDING_WEIGHTS[6]); C(t.loss.toString(), STANDING_WEIGHTS[7])
            C(t.net.toString(), STANDING_WEIGHTS[8]); C(t.points, STANDING_WEIGHTS[9], bold = true)
        }
        HorizontalDivider(color = Line, thickness = 0.5.dp)
    }
}

@Composable
private fun RowScope.H(text: String, w: Float) {
    Text(
        text, fontSize = 10.sp, color = TextSub, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center, modifier = Modifier.weight(w), maxLines = 1
    )
}

@Composable
private fun RowScope.C(
    text: String, w: Float,
    left: Boolean = false, bold: Boolean = false, sub: Boolean = false, color: Color = TextMain
) {
    Text(
        text, fontSize = 10.sp,
        color = if (sub) TextSub else color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = if (left) TextAlign.Start else TextAlign.Center,
        modifier = Modifier.weight(w), maxLines = 1
    )
}


/**
 * 上次崩溃的现场：堆栈 + 机型 + 版本。
 *
 * 只读文本 + 「复制 / 清除 / 关闭」三个动作。**不做自动上报**：本项目没有后端，
 * 假装上报只会让用户以为数据被悄悄传走；要反馈时复制粘贴即可。
 */
@Composable
private fun CrashLogDialog(text: String, onDismiss: () -> Unit, onClear: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("上次崩溃", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(text, fontSize = 10.5.sp, color = TextSub)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(text))
                        onDismiss()
                    }) { Text("复制", fontSize = 13.sp) }
                    TextButton(onClick = onClear) { Text("清除", fontSize = 13.sp, color = Up) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
                }
            }
        }
    }
}

/**
 * 积分榜下钻：某支球队在本联赛的赛程。
 *
 * ## 为什么是一个弹层，而不是新开一页
 * 用户点积分榜的动机几乎都是「这队接下来打谁、什么时候」——问一句就想走，
 * 不该为它压掉整条导航栈再退回来。弹层一按就关，且上下文的积分榜还在原地。
 *
 * ## 数据口径
 * 用的是**本页已经加载好的** [schedule]（同一联赛的赛程），不再发请求：
 * 多一次网络只会多一次失败，信息量一点没多。
 * 只按**队名精确匹配**主客位 —— 同一联赛里不会有两支队同名，模糊匹配反而会串到别队。
 */
@Composable
private fun TeamSheet(row: StandingRow, matches: List<ScheduleMatch>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Surface)
        ) {
            Column(Modifier.width(322.dp)) {
                // ── 头部：队名 + 排名/积分 ──
                Column(
                    Modifier.fillMaxWidth().background(SurfaceTint)
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)
                ) {
                    Text(
                        row.team,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextMain,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "第 ${row.rank} 名 · ${row.points} 分 · 已赛 ${row.played} 场 " +
                            "${row.win}胜${row.draw}平${row.lose}负",
                        fontSize = 11.5.sp, color = TextSub, maxLines = 1
                    )
                }
                HorizontalDivider(color = Line, thickness = 0.6.dp)

                // ── 赛程 ──
                if (matches.isEmpty()) {
                    Text(
                        "该日期范围内没有这队的比赛",
                        fontSize = 12.sp, color = TextSub,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
                        textAlign = TextAlign.Center
                    )
                } else {
                    Column(
                        // 限制高度再滚：长赛程不能把弹层顶出屏幕（底部按钮会被推到屏幕外）
                        Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
                    ) {
                        matches.forEachIndexed { i, m ->
                            if (i > 0) {
                                HorizontalDivider(
                                    color = Line, thickness = 0.5.dp,
                                    modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                            TeamMatchRow(row.team, m)
                        }
                    }
                }

                HorizontalDivider(color = Line, thickness = 0.6.dp)
                Text(
                    "关闭",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 12.dp)
                )
            }
        }
    }
}

/** 球队赛程的一行：日期 / 主客 / 对手 / 比分或开球时间。 */
@Composable
private fun TeamMatchRow(team: String, m: ScheduleMatch) {
    val isHome = m.home == team
    val rival = if (isHome) m.away else m.home
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            m.matchDate.takeLast(5), fontSize = 10.5.sp, color = TextSub,
            modifier = Modifier.width(42.dp), maxLines = 1
        )
        // 主/客用一个小方块标：中文里「主」「客」一个字够用，不加边框反而看不清
        Text(
            if (isHome) "主" else "客",
            fontSize = 9.5.sp,
            color = if (isHome) Up else Down,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(
                    (if (isHome) Up else Down).copy(alpha = 0.12f),
                    RoundedCornerShape(3.dp)
                )
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            rival, fontSize = 12.5.sp, color = TextMain,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        // 已开赛显示比分，未开赛显示开球时间
        Text(
            if (m.score.isNotBlank()) m.score else m.matchTime,
            fontSize = 12.sp,
            fontWeight = if (m.score.isNotBlank()) FontWeight.Bold else FontWeight.Normal,
            color = if (m.score.isNotBlank()) TextMain else TextSub,
            maxLines = 1
        )
    }
}
