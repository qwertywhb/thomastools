package com.example.jingcai.ui.follow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jingcai.data.model.LiveMatch
import com.example.jingcai.data.model.TicketStatus
import com.example.jingcai.data.repository.AppContextHolder
import com.example.jingcai.data.repository.BetTicketStore
import com.example.jingcai.data.repository.LiveScoreRepository
import com.example.jingcai.data.repository.UserPrefs
import com.example.jingcai.ui.ErrorText
import com.example.jingcai.ui.Loading
import com.example.jingcai.ui.ToolPage
import com.example.jingcai.ui.friendlyError
import com.example.jingcai.ui.theme.Flat
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.TextMain
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Up
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「我的关注」页 —— 把散落在三个地方的东西收到一屏。
 *
 * ## 为什么需要它（用户的真实用法）
 * 关注比赛本来只能在比分直播那条**几百场的长列表**里找，跨天之后（尤其凌晨场）几乎找不回来；
 * 关注的球队要靠列表顶部的筛选才看得见；已投注的场次只能进账单页从注单里反推。
 * 三件事都跟「我关心的那几场」有关，却要在三个页面各找一遍。
 *
 * ## 三块内容的取数口径（刻意不同，别合并）
 *  1. **关注的比赛**：`UserPrefs.followed` ∩ 比分直播全量数据，**排除已完场**
 *     （完场的关注会被主页面自动摘掉，这里再兜一次，防止刚完场那一瞬还挂着）；
 *  2. **我投注的场次**：本地注单里**待开奖**的场次，同一场的复式选项合并成一行。
 *     只取待开奖：已开奖的满 24 小时就被清掉了，看它没有意义（详见 `BetRetention`）；
 *  3. **关注的球队**：按队名匹配**下一场未开赛**的比赛。找不到就显示「近期无赛事」——
 *     不是所有关注队都天天有球，空白容易让人以为坏了。
 *
 * 🔴 三个数据源各自独立：比分直播拉取失败时，投注和球队两块**照样要显示**，
 *    所以 [FollowUiState.error] 只影响第一块，不做整页错误页。
 */
class FollowViewModel : ViewModel() {
    private val repo = LiveScoreRepository()
    private val _state = MutableStateFlow(FollowUiState())
    val state: StateFlow<FollowUiState> = _state

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // 注单是本地数据，先读出来：即使比分拉取失败，投注那块也能显示
            val bets = runCatching { loadBetRows() }.getOrDefault(emptyList())
            try {
                val raw = repo.fetchBfData(includeCorners = false)
                _state.value = FollowUiState(loading = false, matches = raw.matches, betRows = bets)
            } catch (e: Exception) {
                _state.value = FollowUiState(
                    loading = false,
                    error = friendlyError(e.message),
                    betRows = bets
                )
            }
        }
    }

    /** 待开奖注单 → 按场次合并的行。同一场买了多个选项时合并成「主胜 / 平」。 */
    private fun loadBetRows(): List<BetRow> {
        val ctx = runCatching { AppContextHolder.ctx }.getOrNull() ?: return emptyList()
        val tickets = BetTicketStore(ctx).loadTickets().filter { it.status == TicketStatus.PENDING }
        return tickets.flatMap { it.picks }
            .groupBy { it.titanId to "${it.home}|${it.away}" }
            .map { (key, picks) ->
                val p = picks.first()
                // 一行最多列 3 个选项：复式单（总进球买 4/5/6/7 球那种）全列出来会被
                // 省略号吃掉后半段，连「买了几个」都看不出来；改成「3 个 + 等 N 项」。
                val labels = picks.map { pickLabel(it) }.distinct()
                BetRow(
                    titanId = key.first,
                    home = p.home,
                    away = p.away,
                    league = p.league,
                    num = p.num,
                    date = p.date,
                    time = p.time,
                    labels = if (labels.size <= 3) labels.joinToString(" / ")
                    else labels.take(2).joinToString(" / ") + " 等 ${labels.size} 项"
                )
            }
            .sortedWith(compareBy({ it.date }, { it.time }))
    }

    /** 「胜平负 主胜 1.85」→ 只留玩法 + 选项，赔率不进这一行的文案（一行放不下）。 */
    private fun pickLabel(p: com.example.jingcai.data.model.BetPick): String =
        listOf(p.poolTitle, p.label).filter { it.isNotEmpty() }.joinToString(" ")
}

data class FollowUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val matches: List<LiveMatch> = emptyList(),
    val betRows: List<BetRow> = emptyList()
)

/** 「我投注的场次」的一行（同一场的多个选项已合并）。 */
data class BetRow(
    val titanId: Long,
    val home: String,
    val away: String,
    val league: String,
    val num: String,
    val date: String,
    val time: String,
    val labels: String
)

@Composable
fun FollowScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long, String, String) -> Unit,
    vm: FollowViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val followed by UserPrefs.followed.collectAsState()
    val teams by UserPrefs.followedTeams.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    // 关注的比赛：排除已完场（完场的会自动被摘关注，这里兜住「刚完场那一拍」）
    val followedMatches = remember(state.matches, followed) {
        state.matches
            .filter { it.titanId in followed && it.state != -1 }
            .sortedBy { it.time }
    }
    // 关注的球队 × 下一场未开赛的比赛。找不到就是「近期无赛事」，不是错误。
    val teamRows = remember(state.matches, teams) {
        teams.sorted().map { t ->
            t to state.matches
                .asSequence()
                .filter { (it.home == t || it.away == t) && it.state == 0 }
                .minByOrNull { it.time }
        }
    }

    ToolPage(
        title = "我的关注",
        subtitle = listOfNotNull(
            followedMatches.size.takeIf { it > 0 }?.let { "关注 $it 场" },
            state.betRows.size.takeIf { it > 0 }?.let { "投注 $it 场" },
            teams.size.takeIf { it > 0 }?.let { "球队 $it 支" }
        ).joinToString(" · ").ifEmpty { null },
        onBack = onBack
    ) {
        when {
            state.loading && state.matches.isEmpty() && state.betRows.isEmpty() -> Loading()
            else -> {
                // 比分直播拉取失败**只影响第一块**：投注与球队是本地/缓存数据，照常显示
                if (state.error != null) ErrorText(state.error, onRetry = { vm.load() })

                // ── ① 关注的比赛 ──
                SectionTitle("关注的比赛", followedMatches.size)
                if (state.error == null) {
                    if (followedMatches.isEmpty()) {
                        // 文案必须写真实入口：关注**比赛**的星标是比分直播表格最左一列
                        //（[MatchRow] 的 FollowCell，见 LiveScreen.kt:1185 的「必须放最左」注释），
                        // 不是球队卡上那个 ☆，也不能说「点球队左侧」——星标在行首、球队名在中间。
                        HintCard("还没有关注的比赛", "在比分直播列表里点每行最左边的 ☆，开赛前和进球时都会提醒你")
                    } else {
                        Card {
                            followedMatches.forEachIndexed { i, m ->
                                if (i > 0) RowDivider()
                                LiveRow(
                                    league = m.league,
                                    time = m.time,
                                    home = m.home,
                                    away = m.away,
                                    score = m.score,
                                    state = m.state,
                                    onClick = { onOpenDetail(m.titanId, m.home, m.away) },
                                    onUnfollow = { UserPrefs.toggleFollow(m.titanId) }
                                )
                            }
                        }
                    }
                }

                // ── ② 我投注的场次 ──
                SectionTitle("我投注的场次", state.betRows.size)
                if (state.betRows.isEmpty()) {
                    HintCard("还没有待开奖的注单", "已开奖的订单保留 24 小时后自动清理，所以这里只显示未开奖的")
                } else {
                    Card {
                        state.betRows.forEachIndexed { i, b ->
                            if (i > 0) RowDivider()
                            BetRowItem(b) {
                                if (b.titanId > 0) onOpenDetail(b.titanId, b.home, b.away)
                            }
                        }
                    }
                }

                // ── ③ 关注的球队 ──
                SectionTitle("关注的球队", teamRows.size)
                if (teamRows.isEmpty()) {
                    // 球队关注入口在**底部「比赛」tab 的卡片头部**：编号与开赛时间之间的那个 ☆，
                    // 点开弹「主队/客队」二选一（见 MatchCard.kt 的 TeamFollowStar，真机已核）。
                    // 旧文案写「搜索框旁」——那里确实有个「☆ 关注」，但它是**只看关注**的筛选开关，
                    // 点了不会关注任何球队，属于指错入口；写「右上角」也不准，星标在头部行中间偏右。
                    HintCard(
                        "还没有关注的球队",
                        "在「比赛」页的比赛卡上，点开赛时间左边的 ☆，选一支球队，它接下来的比赛会出现在这里"
                    )
                } else {
                    Card {
                        teamRows.forEachIndexed { i, (team, next) ->
                            if (i > 0) RowDivider()
                            TeamRow(team, next) {
                                if (next != null) onOpenDetail(next.titanId, next.home, next.away)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** 区块标题：左标题 + 右条数（条数为 0 时不画，免得「0」比标题还显眼）。 */
@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, top = 14.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(13.dp).background(Primary, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(7.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text("$count", fontSize = 11.5.sp, color = TextSub)
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp))
    ) { content() }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = Line, thickness = 0.6.dp, modifier = Modifier.padding(start = 12.dp))
}

/** 空态卡片：写清「为什么空」和「怎么让它不空」，而不是干巴巴一句「暂无」。 */
@Composable
private fun HintCard(title: String, hint: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(SurfaceAlt, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Text(title, fontSize = 13.sp, color = TextMain, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(hint, fontSize = 11.5.sp, color = TextSub, lineHeight = 16.sp)
    }
}

/**
 * 关注的比赛一行。
 *
 * 右端比分即状态：未开赛显示开赛时间、进行中显示比分（主色）、完场显示比分（灰）。
 * 关注星常显（不像列表里悬停才出）：这一页本来就是「关注清单」，取消关注是主要操作之一。
 */
@Composable
private fun LiveRow(
    league: String,
    time: String,
    home: String,
    away: String,
    score: String,
    state: Int,
    onClick: () -> Unit,
    onUnfollow: () -> Unit
) {
    val live = state in 1..3
    val ended = state == -1
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    league, fontSize = 10.5.sp, color = TextSub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Text(time, fontSize = 10.5.sp, color = TextSub)
                if (live) {
                    Spacer(Modifier.width(6.dp))
                    Text("进行中", fontSize = 10.sp, color = Primary, fontWeight = FontWeight.Medium)
                } else if (ended) {
                    Spacer(Modifier.width(6.dp))
                    Text("完场", fontSize = 10.sp, color = TextSub)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "$home  vs  $away",
                fontSize = 13.5.sp, color = TextMain, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        val shown = if (state == 0 || score == "-") "未开" else score
        Text(
            shown,
            fontSize = if (state == 0) 11.sp else 14.sp,
            fontWeight = if (state == 0) FontWeight.Normal else FontWeight.Bold,
            color = when {
                state == 0 -> TextSub
                live -> Up
                else -> TextSub
            }
        )
        Box(
            Modifier.width(34.dp).height(34.dp).clickable(onClick = onUnfollow),
            contentAlignment = Alignment.Center
        ) {
            Text("★", fontSize = 14.sp, color = Primary)
        }
    }
}

/** 已投注一行：编号 + 联赛 + 时间 / 对阵 / 我买的玩法。点击进动画直播（titanId 有效时）。 */
@Composable
private fun BetRowItem(b: BetRow, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = b.titanId > 0, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (b.num.isNotEmpty()) {
                Text(
                    b.num, fontSize = 10.5.sp, color = Flat, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Flat.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                b.league, fontSize = 10.5.sp, color = TextSub,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(6.dp))
            Text("${b.date.takeLast(5)} ${b.time}", fontSize = 10.5.sp, color = TextSub)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "${b.home}  vs  ${b.away}",
            fontSize = 13.5.sp, color = TextMain, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            b.labels,
            fontSize = 11.5.sp, color = Flat,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** 关注的球队一行：队名 / 下一场（对手 + 时间），没有就写「近期无赛事」。 */
@Composable
private fun TeamRow(team: String, next: LiveMatch?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = next != null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            team, fontSize = 13.5.sp, color = TextMain, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (next == null) {
            Text("近期无赛事", fontSize = 11.5.sp, color = TextSub)
        } else {
            Column(horizontalAlignment = Alignment.End) {
                val rival = if (next.home == team) next.away else next.home
                Text(
                    "vs $rival", fontSize = 12.sp, color = TextMain,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    "${next.league} · ${next.time}",
                    fontSize = 10.5.sp, color = TextSub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
