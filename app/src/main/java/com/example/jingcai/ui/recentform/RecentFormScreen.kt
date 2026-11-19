package com.example.jingcai.ui.recentform

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jingcai.data.model.HistoryData
import com.example.jingcai.data.model.HistoryMatch
import com.example.jingcai.data.repository.HistoryRepository
import com.example.jingcai.ui.*
import com.example.jingcai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ===== ViewModel =====

/** 四视图。 */
enum class RecentFormTab(val label: String) {
    // 🔴 页签文案必须 ≤4 个汉字：TabRow 四等分后每格只有 90dp，M3 Tab 自带左右 padding
    //    使文字可用宽仅 ≈58dp；6 字 @11sp 需 ≈59dp，实测「场」「锋」被硬切（2026-10-23 像素量测：
    //    6 字墨迹 172/188px vs 可用 203px，差 2px 就裁）。4 字 = 148px，余量充足。
    HOME("主队近期"), AWAY("客队近期"), COMPARE("未来5场"), VS("历史交锋")
}

/** 场次数量过滤（6/10/20，交锋段还可选 50）。 */
enum class CountFilter(val label: String, val n: Int) {
    N6("6场", 6), N10("10场", 10), N20("20场", 20), N50("50场", 50)
}

/** 赛事过滤（网页 `*LeagueFilter`：全赛事/本赛事/非友谊赛）。 */
enum class LeagueFilter(val label: String) { ALL("全赛事"), CUR("本赛事"), NO_FRIEND("非友谊赛") }

/** 场地过滤（网页 `*Filter`：全部场地/主场/客场）。 */
enum class VenueFilter(val label: String) { ALL("全部场地"), HOME("主场"), AWAY("客场") }

data class RecentFormUiState(
    val tab: RecentFormTab = RecentFormTab.HOME,
    val data: HistoryData? = null,
    val loading: Boolean = false,
    val error: String? = null,
    // 主/客近期各自独立的过滤（网页也是三组 select 各管各的）
    val homeCount: CountFilter = CountFilter.N10,
    val homeLeague: LeagueFilter = LeagueFilter.ALL,
    val homeVenue: VenueFilter = VenueFilter.ALL,
    val awayCount: CountFilter = CountFilter.N10,
    val awayLeague: LeagueFilter = LeagueFilter.ALL,
    val awayVenue: VenueFilter = VenueFilter.ALL,
    /** 交锋段场次（6/10/20/50）。 */
    val vsCount: CountFilter = CountFilter.N10
)

class RecentFormViewModel : ViewModel() {
    private val repo = HistoryRepository()
    private val _state = MutableStateFlow(RecentFormUiState())
    val state: StateFlow<RecentFormUiState> = _state

    fun load(mid: Long) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val data = repo.fetch(mid)
                _state.update { it.copy(data = data, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun setTab(t: RecentFormTab) = _state.update { it.copy(tab = t) }
    fun setHomeCount(c: CountFilter) = _state.update { it.copy(homeCount = c) }
    fun setHomeLeague(l: LeagueFilter) = _state.update { it.copy(homeLeague = l) }
    fun setHomeVenue(v: VenueFilter) = _state.update { it.copy(homeVenue = v) }
    fun setAwayCount(c: CountFilter) = _state.update { it.copy(awayCount = c) }
    fun setAwayLeague(l: LeagueFilter) = _state.update { it.copy(awayLeague = l) }
    fun setAwayVenue(v: VenueFilter) = _state.update { it.copy(awayVenue = v) }
    fun setVsCount(c: CountFilter) = _state.update { it.copy(vsCount = c) }
}

// ===== Screen =====

/**
 * 近期状态详情页（四视图）。
 *
 * 表格行：联赛 | 时间 | 主队 | 比分 | 客队 | 半场；**不横向滚动**，列压单行。
 * 队名着色只针对「本队」：胜=红 [Up] / 负=绿 [Down] / 平=[TextMain]，一律加粗；对手保持 [TextMain] 常规。
 * 过滤（场次/赛事/场地）在 App 端本地完成 —— 服务端已渲染全部行（网页 select 是 JS 过滤）。
 */
@Composable
fun RecentFormScreen(mid: Long, vsText: String, vm: RecentFormViewModel = viewModel(), onBack: () -> Unit) {
    LaunchedEffect(mid) { vm.load(mid) }
    val state by vm.state.collectAsState()

    // 与「赔率变化 / 同奖查询」共用 [ToolPage]：同一页面底色 + 同高主色顶栏 + 同尺寸卡片。
    // 四视图页签放进 header：顶栏正下方、内容上方的整宽固定区，滚动内容时页签不会跟着跑。
    ToolPage(
        title = "近期状态",
        subtitle = state.data?.takeIf { it.homeName.isNotEmpty() }?.let { "${it.homeName} VS ${it.awayName}" }
            ?: vsText.ifEmpty { null },
        onBack = onBack,
        // 标题与「布里斯班狮吼 VS 珀斯光荣」同排显示：竖排两行会把 52dp 顶栏撑成两行文字，
        // 用户反馈标题被挤成分行、观感碎（副标题只是对阵信息，跟着标题走一行足够）。
        inlineSubtitle = true,
        header = {
            TabRow(selectedTabIndex = state.tab.ordinal, containerColor = Surface, contentColor = Primary) {
                RecentFormTab.entries.forEach { t ->
                    Tab(
                        selected = state.tab == t,
                        onClick = { vm.setTab(t) },
                        text = { Text(t.label, fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) {
        when {
            state.loading -> Loading()
            state.error != null -> ErrorText(state.error)
            state.data == null -> EmptyText()
            else -> when (state.tab) {
                RecentFormTab.HOME -> TeamRecentView(state, isHome = true, vm = vm)
                RecentFormTab.AWAY -> TeamRecentView(state, isHome = false, vm = vm)
                RecentFormTab.COMPARE -> CompareView(state)
                RecentFormTab.VS -> VsView(state, vm = vm)
            }
        }
    }
}

// ===== 主/客队近期（同一套 UI，两份独立过滤） =====

@Composable
private fun TeamRecentView(state: RecentFormUiState, isHome: Boolean, vm: RecentFormViewModel) {
    val d = state.data ?: return
    val sec = if (isHome) d.home else d.away
    val teamId = if (isHome) d.homeId else d.awayId
    val count = if (isHome) state.homeCount else state.awayCount
    val leagueF = if (isHome) state.homeLeague else state.awayLeague
    val venueF = if (isHome) state.homeVenue else state.awayVenue

    // 过滤链：赛事 → 场地 → 场次（与网页 JS `filter()` 顺序一致：先筛后截断）
    // 🔴 本赛事 = 页面 filter 下拉的 option value（d.curLeague），不能用 sec.rows.first().league：
    //    首行可能是一场球会友谊（样本 1338460 即如此）。
    val curLeague = d.curLeague.ifEmpty { sec.rows.firstOrNull { it.hasScore }?.league ?: "" }
    val rows = sec.rows.asSequence()
        .filter { r ->
            when (leagueF) {
                LeagueFilter.ALL -> true
                LeagueFilter.CUR -> curLeague.isNotEmpty() && r.league == curLeague
                LeagueFilter.NO_FRIEND -> r.matchType != "friend"
            }
        }
        .filter { r ->
            when (venueF) {
                VenueFilter.ALL -> true
                VenueFilter.HOME -> r.isHomeFor(teamId)
                VenueFilter.AWAY -> !r.isHomeFor(teamId)
            }
        }
        .take(count.n)
        .toList()

    // 过滤后小结（网页 `getscorehtml` 也是过滤后重算）
    val summary = filteredSummary(rows, teamId, !isHome)

    FilterBar(
        count = count, onCount = { if (isHome) vm.setHomeCount(it) else vm.setAwayCount(it) },
        leagueF = leagueF, onLeague = { if (isHome) vm.setHomeLeague(it) else vm.setAwayLeague(it) },
        venueF = venueF, onVenue = { if (isHome) vm.setHomeVenue(it) else vm.setAwayVenue(it) },
        showVenue = true
    )
    SummaryBar("${sec.title}近况：$summary")
    HistoryTable(rows, teamId)
}

// ===== 主客未来 5 场（div.homefuture / div.awayfuture） =====

/**
 * 未来赛程视图：两队各 5 场。
 * 未来行无可比比分 ⇒ 不做胜负着色，改为**本队名加粗高亮**；表固定 4 列（无半场/赢盘）。
 */
@Composable
private fun CompareView(state: RecentFormUiState) {
    val d = state.data ?: return

    FutureBlock(title = "${d.homeName}未来5场", rows = d.homeFuture, highlightTeam = d.homeName)
    FutureBlock(title = "${d.awayName}未来5场", rows = d.awayFuture, highlightTeam = d.awayName)
}

@Composable
private fun FutureBlock(title: String, rows: List<HistoryMatch>, highlightTeam: String) {
    SummaryBar(title)
    if (rows.isEmpty()) {
        EmptyText("暂无未来赛程")
    } else {
        HistoryTable(rows, teamId = "", future = true, highlightTeam = highlightTeam)
    }
}

// ===== 两队历史交锋 =====

@Composable
private fun VsView(state: RecentFormUiState, vm: RecentFormViewModel) {
    val d = state.data ?: return
    val rows = d.vs.rows.take(state.vsCount.n)

    FilterBar(
        count = state.vsCount, onCount = { vm.setVsCount(it) },
        leagueF = null, onLeague = {}, venueF = null, onVenue = {}, showVenue = false
    )
    SummaryBar("两队交锋：${filteredSummary(rows, d.homeId, false)}")
    HistoryTable(rows, d.homeId)
}

// ===== 过滤条 / 小结条 =====

@Composable
private fun FilterBar(
    count: CountFilter, onCount: (CountFilter) -> Unit,
    leagueF: LeagueFilter?, onLeague: (LeagueFilter) -> Unit,
    venueF: VenueFilter?, onVenue: (VenueFilter) -> Unit,
    showVenue: Boolean
) {
    // 单行横滑：三个下拉语义用「按钮组」实现（避免 DropdownMenu 的测量复杂度），
    // 整条可横向滚动，绝不撑爆屏宽。
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CountFilter.entries.forEach { c ->
            FilterChip3(c.label, selected = c == count) { onCount(c) }
        }
        if (leagueF != null) {
            Spacer(Modifier.width(4.dp))
            LeagueFilter.entries.forEach { l ->
                FilterChip3(l.label, selected = l == leagueF) { onLeague(l) }
            }
        }
        if (showVenue && venueF != null) {
            Spacer(Modifier.width(4.dp))
            VenueFilter.entries.forEach { v ->
                FilterChip3(v.label, selected = v == venueF) { onVenue(v) }
            }
        }
    }
}

/** 小号选择胶囊（选中=Primary 实底白字，未选=浅底 Primary 字），替代 Material FilterChip（高度偏大）。 */
@Composable
private fun FilterChip3(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) Primary else SurfaceAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, fontSize = 11.sp, maxLines = 1,
            color = if (selected) Color.White else TextMain,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/** 小结条：浅底圆角 + 深色文字（网页 `span.*score` 的 App 版）。圆角 10dp = 与 [SectionCard] 标题条同族。 */
@Composable
private fun SummaryBar(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceTint)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(text, fontSize = 12.sp, color = TextMain, fontWeight = FontWeight.Medium, maxLines = 2)
    }
}

// ===== 历史表格 =====

/** 表头列宽权重（单行铺满、不横向滚动）。去掉「赢盘」列后 6 列重新摊分，和 = 1.00。 */
private val COL_W = listOf(0.15f, 0.18f, 0.22f, 0.13f, 0.22f, 0.10f)

/** 未来赛程 4 列权重（联赛|时间|主队|客队，和为 1；无比分列，宽度让给队名）。 */
private val COL_W_FUTURE = listOf(0.17f, 0.20f, 0.315f, 0.315f)

@Composable
private fun HistoryTable(
    rows: List<HistoryMatch>,
    teamId: String,
    future: Boolean = false,
    highlightTeam: String = ""
) {
    val headers = if (future) listOf("联赛", "时间", "主队", "客队")
    else listOf("联赛", "时间", "主队", "比分", "客队", "半场")
    val weights = if (future) COL_W_FUTURE else COL_W

    // SectionCard(null)：本表自带表头行，不再加标题条（否则与表头 SurfaceTint 同色糊成一片）
    SectionCard(null) {
        Column {
            Row(Modifier.background(SurfaceTint).padding(vertical = 7.dp, horizontal = 6.dp)) {
                headers.forEachIndexed { i, h ->
                    Text(
                        h, fontSize = 10.sp, color = TextSub, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(weights[i])
                    )
                }
            }
            if (rows.isEmpty()) {
                Text("无数据", color = TextSub, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            } else {
                rows.forEachIndexed { i, r ->
                    HistoryRow(r, teamId, alt = i % 2 == 1, future = future, highlightTeam = highlightTeam)
                }
            }
        }
    }
}

/**
 * 单行：联赛 | 时间 | 主队 | 比分 | 客队 | 半场。
 *
 * 着色规则（用户口径，只针对「本队」）：
 *  - 主队近期页 → 本队 = 主队 id；客队近期页 → 本队 = 客队 id；历史交锋页 → 本队 = 主队 id；
 *  - **只给本队名称着色**：本队胜 → 红 [Up]、负 → 绿 [Down]、平 → [TextMain]，三种都加粗；
 *  - 对手名称永远 [TextMain] + Normal（旧版「谁赢谁上色」会让两侧都粗都彩，语义混乱，已作废）。
 *
 * [future] = true 时走「未来赛程」布局：只 4 列（联赛|时间|主队|客队，比分列整列删除）、
 * 时间拆 MM-dd + HH:mm 两行、改用 [highlightTeam] 名称匹配高亮本队（未来场次没有比分，无法按胜负着色）。
 */
@Composable
private fun HistoryRow(
    r: HistoryMatch,
    teamId: String,
    alt: Boolean,
    future: Boolean = false,
    highlightTeam: String = ""
) {
    // 本队视角：res 为胜/平/负（"" = 无比分），focusIsHome = 本队是否在主队列
    val res = r.resultFor(teamId)
    val focusIsHome = r.isHomeFor(teamId)
    val focusPresent = res.isNotEmpty()
    val focusColor = when (res) {
        "胜" -> Up
        "负" -> Down
        else -> TextMain
    }
    val iAmHome = future && sameTeam(r.home, highlightTeam)
    val iAmAway = future && sameTeam(r.away, highlightTeam)

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (alt) SurfaceAlt else Surface)
            .padding(vertical = 6.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            r.league + if (r.round.isNotEmpty()) " ${r.round}" else "",
            fontSize = 10.sp, color = TextSub, maxLines = 1,
            modifier = Modifier.weight(if (future) COL_W_FUTURE[0] else COL_W[0])
        )
        if (future) {
            // 时间两行：上行 MM-dd，下行 HH:mm（fullTime = "yyyy-MM-dd HH:mm"）
            Column(
                Modifier.weight(COL_W_FUTURE[1]),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val ft = r.fullTime
                if (ft.length >= 16) {
                    Text(ft.substring(5, 10), fontSize = 10.sp, color = TextMain, maxLines = 1)
                    Text(ft.substring(11, 16), fontSize = 9.sp, color = TextSub, maxLines = 1)
                } else {
                    Text(r.date, fontSize = 10.sp, color = TextMain, maxLines = 1)
                }
            }
        } else {
            Text(
                r.date, fontSize = 10.sp, color = TextSub, maxLines = 1, textAlign = TextAlign.Center,
                modifier = Modifier.weight(COL_W[1])
            )
        }
        Text(
            r.home, fontSize = 11.sp, maxLines = 1,
            // future 分支：本队蓝底加粗高亮（无比分可比）；近期/交锋分支：只有本队会着色
            color = when {
                future -> if (iAmHome) Primary else TextMain
                focusIsHome -> focusColor
                else -> TextMain
            },
            fontWeight = when {
                future -> if (iAmHome) FontWeight.Bold else FontWeight.Normal
                focusIsHome && focusPresent -> FontWeight.Bold
                else -> FontWeight.Normal
            },
            textAlign = if (future) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.weight(if (future) COL_W_FUTURE[2] else COL_W[2])
        )
        if (!future) {
            Text(
                when {
                    r.score.isEmpty() -> "-"
                    else -> r.score
                },
                fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                textAlign = TextAlign.Center,
                color = TextMain,
                modifier = Modifier.weight(COL_W[3])
            )
        }
        Text(
            r.away, fontSize = 11.sp, maxLines = 1,
            textAlign = if (future) TextAlign.Center else TextAlign.End,
            color = when {
                future -> if (iAmAway) Primary else TextMain
                !focusIsHome && focusPresent -> focusColor
                else -> TextMain
            },
            fontWeight = when {
                future -> if (iAmAway) FontWeight.Bold else FontWeight.Normal
                !focusIsHome && focusPresent -> FontWeight.Bold
                else -> FontWeight.Normal
            },
            modifier = Modifier.weight(if (future) COL_W_FUTURE[3] else COL_W[4])
        )
        if (!future) {
            Text(
                r.halfScore.ifEmpty { "-" }, fontSize = 10.sp, color = TextSub, maxLines = 1,
                textAlign = TextAlign.Center, modifier = Modifier.weight(COL_W[5])
            )
        }
    }
}

/** 赢盘列已于 2026-10-23 按用户要求整列删除（数据层 HistoryMatch.pan 与解析逻辑保留，将来可能复用）。 */

/**
 * 队名是否指向同一支球队（未来赛程没有 teamId，只能比文本）。
 * 🔴 页面表头用的全称与行内简称经常不一致：样本 1338460 表头是「布里斯班狮吼」，行内是「布里斯班」
 * ⇒ 必须前缀兼容，否则本队高亮会漏掉主队。
 */
private fun sameTeam(rowName: String, highlight: String): Boolean {
    if (rowName.isEmpty() || highlight.isEmpty()) return false
    if (rowName == highlight) return true
    val short = if (rowName.length <= highlight.length) rowName else highlight
    val long = if (rowName.length <= highlight.length) highlight else rowName
    return short.length >= 2 && long.startsWith(short)
}

/**
 * 过滤后小结（同 [OkoooHistoryParser] 复刻的网页 `gethomerecord` 口径）。
 * 放在 UI 层纯函数，避免为「过滤后重算」再走一遍网络/解析。
 */
private fun filteredSummary(rows: List<HistoryMatch>, teamId: String, awayView: Boolean): String {
    var win = 0; var draw = 0; var lose = 0
    var homeWin = 0; var homeDraw = 0; var homeLose = 0
    var goalsFor = 0; var goalsAgainst = 0
    for (r in rows) {
        if (!r.hasScore) continue
        val res = r.resultFor(teamId)
        when (res) {
            "胜" -> win++
            "平" -> draw++
            "负" -> lose++
            else -> continue
        }
        if (r.isHomeFor(teamId)) {
            when (res) {
                "胜" -> homeWin++
                "平" -> homeDraw++
                "负" -> homeLose++
            }
            goalsFor += r.homeGoals; goalsAgainst += r.awayGoals
        } else {
            goalsFor += r.awayGoals; goalsAgainst += r.homeGoals
        }
    }
    val venue = if (awayView) {
        "客场${win - homeWin}胜${draw - homeDraw}平${lose - homeLose}负"
    } else {
        "主场${homeWin}胜${homeDraw}平${homeLose}负"
    }
    return "${win}胜${draw}平${lose}负，$venue，进${goalsFor}球失${goalsAgainst}球"
}
