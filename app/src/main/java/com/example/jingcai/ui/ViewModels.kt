package com.example.jingcai.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.example.jingcai.data.model.*
import com.example.jingcai.data.network.TitanLiveParser
import com.example.jingcai.data.repository.BbJcOddsRepository
import com.example.jingcai.data.repository.BbLiveRepository
import com.example.jingcai.data.repository.JingCaiRepository
import com.example.jingcai.data.repository.LiveScoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.example.jingcai.data.repository.AppContextHolder
import com.example.jingcai.data.repository.LiveSnapshotStore
import com.example.jingcai.data.repository.MatchSnapshotStore
import com.example.jingcai.data.repository.UserPrefs
import com.example.jingcai.ui.live.GoalDeliverer
import com.example.jingcai.ui.live.GoalEvent
import com.example.jingcai.ui.live.KickoffAlert
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
fun shiftDate(date: String, days: Int): String {
    val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val d = f.parse(date) ?: Date()
    val c = java.util.Calendar.getInstance()
    c.time = d
    c.add(java.util.Calendar.DAY_OF_MONTH, days)
    return f.format(c.time)
}

// ===== 比赛列表 =====
/**
 * 「比赛」tab 状态。
 *
 * ## 过滤口径（用户明确要求）
 * **已开赛的场次默认不显示**，判据是**开赛时间**（[Match.startedAt]），不是源站的
 * `data-end`（那只在终场后才翻 true，整个比赛过程中看起来都「未截止」）。
 * 源站完赛标记仍参与判定，防止个别场次缺 `fullTime` 时漏判。
 *
 * 🔴 只在**看今天**时过滤（[isToday]）：翻到往期日期时整张表都是历史数据，
 *    按时间卡会把往期列表整段清空。
 * 🔴 [nowMs] 必须由外部定时推进（[MatchViewModel.tick]）：「已开赛」是随时间变化的量，
 *    只在加载时算一次的话，页面开着不动就永远不会把刚开赛的场次收走。
 */
data class MatchUiState(
    val date: String = today(),
    val allMatches: List<Match> = emptyList(),
    val loading: Boolean = false,
    /** 后台静默刷新中（列表**不清空**，只在顶部出一条细进度条 + 整体压暗）。 */
    val refreshing: Boolean = false,
    val error: String? = null,
    val showEnded: Boolean = false,
    /** 判「已开赛」的当前时刻（每 30 秒由 [MatchViewModel.tick] 推进）。 */
    val nowMs: Long = System.currentTimeMillis(),
    /** 最近一次成功拉到数据的时间戳（0 = 还没成功过）。用于「数据为 14:20」提示。 */
    val lastUpdatedAt: Long = 0L,
    /** 搜索关键字（队名 / 联赛 / 竞彩编号，本地过滤，不发请求）。 */
    val query: String = "",
    /** 只看关注球队的比赛。 */
    val onlyFollowedTeams: Boolean = false,
    /** 关注的球队名集合（用户维度，来自 [UserPrefs.followedTeams]）。 */
    val followedTeams: Set<String> = emptySet()
) {
    val isToday: Boolean get() = date == today()

    /** 搜索命中：队名 / 联赛 / 竞彩编号任一包含关键字（大小写不敏感）。 */
    private fun Match.hit(q: String): Boolean =
        home.contains(q, ignoreCase = true) || away.contains(q, ignoreCase = true) ||
            league.contains(q, ignoreCase = true) || order.contains(q, ignoreCase = true)

    /**
     * 列表实际展示的行。
     *
     * 🔴 搜索时**不套**「已开赛自动隐藏」：用户输入队名是定向查找，把已开赛的那场藏起来
     *    等于「搜了却没有」——那才是最让人困惑的失败。搜索结束回到默认视图时过滤恢复。
     */
    val visibleMatches: List<Match>
        get() {
            val q = query.trim()
            val base = if (isToday && !showEnded && q.isEmpty())
                allMatches.filter { !it.startedAt(nowMs) } else allMatches
            var list = if (q.isEmpty()) base else base.filter { it.hit(q) }
            if (onlyFollowedTeams) {
                list = list.filter { it.home in followedTeams || it.away in followedTeams }
            }
            return list
        }

    val hiddenCount: Int get() = allMatches.size - visibleMatches.size
}

class MatchViewModel : ViewModel() {
    private val repo = JingCaiRepository()
    private val _state = MutableStateFlow(MatchUiState())
    val state: StateFlow<MatchUiState> = _state

    /**
     * 页面可见性：只有 RESUMED 时才跑 60 秒自动刷新。
     *
     * 🔴 为什么必须有这个开关：比赛 tab 是默认页，用户切到别的 tab、切到微信、锁屏，
     *    组合都还在，`viewModelScope` 里那个 `while(true)` 不会自己停 —— 不判可见性就是
     *    一天几百次无意义请求（费流量也费电，还会被源站 WAF 限流）。
     */
    private val _active = MutableStateFlow(false)

    /** 切日期 / 下拉 / 自动刷新互斥：三者撞在一起时只让一个在途，避免旧响应盖新响应。 */
    private val req = Mutex()

    init {
        // 🔴 必须 keepContent = true：冷启动正是最需要「先出画面」的时刻。
        //    这个参数只决定「有没有旧内容时是否保留列表」，init 时本来就没有内容，
        //    传 true 的唯一作用是允许 restoreSnapshot() 先把本地快照顶上屏幕。
        load(keepContent = true)

        // 自动刷新：60 秒一次。竞彩赔率是随时在动的，页面挂着不动的话旧实现里
        // **一次都不会更新**（只有 tick 推进 nowMs），用户看到的赔率能停在半小时前。
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                if (_active.value) refresh()
            }
        }

        // 关注球队集合变了立刻反映到列表过滤，不需要重新拉数据。
        viewModelScope.launch {
            UserPrefs.followedTeams.collect { set ->
                _state.update { it.copy(followedTeams = set) }
            }
        }
    }

    fun setActive() { _active.value = true }
    fun setInactive() { _active.value = false }

    /**
     * 首次进入 / 切换日期。
     *
     * @param keepContent 保留旧列表（压暗 + 顶部细进度条）。切日期时默认保留，因为
     *   「整页白屏转圈」是最高频的视觉中断；只有冷启动（无任何数据）才走整页 loading。
     */
    fun load(date: String = _state.value.date, keepContent: Boolean = true) {
        val prevDate = _state.value.date
        val hasData = _state.value.allMatches.isNotEmpty()
        viewModelScope.launch {
            req.withLock {
                _state.update {
                    it.copy(
                        date = date,          // 日期先变：点击要立刻有反馈，不能等网络
                        loading = !hasData || !keepContent,
                        refreshing = hasData && keepContent,
                        error = null
                    )
                }

                // 冷启动无内存数据时，先把本地快照顶上去（0ms 出画面），再让网络去替换。
                if (!hasData && keepContent) restoreSnapshot(date)

                try {
                    val matches = repo.getMatches(date)
                    _state.update {
                        it.copy(
                            date = date, allMatches = matches, loading = false, refreshing = false,
                            error = null, nowMs = System.currentTimeMillis(),
                            lastUpdatedAt = System.currentTimeMillis()
                        )
                    }
                    runCatching { MatchSnapshotStore.save(AppContextHolder.ctx, date, matches) }
                } catch (e: Exception) {
                    _state.update {
                        // 旧数据还在时把日期回滚，避免「日期是今天、内容是昨天」的错位
                        it.copy(
                            date = if (it.allMatches.isEmpty()) date else prevDate,
                            loading = false, refreshing = false, error = e.message
                        )
                    }
                }
            }
        }
    }

    /**
     * 静默刷新（下拉 / 60 秒自动 / 错误重试共用）：**不清空内容**，失败也保留旧数据。
     *
     * 失败时仍然写 [MatchUiState.error]（有数据时由 UI 用 Snackbar 提示一次后
     * [consumeError] 清掉），否则用户主动下拉了却「什么都没发生」，会以为手势没生效。
     */
    fun refresh() {
        viewModelScope.launch {
            req.withLock {
                val hasData = _state.value.allMatches.isNotEmpty()
                _state.update {
                    if (hasData) it.copy(refreshing = true, error = null)
                    else it.copy(loading = true, error = null)
                }
                try {
                    val date = _state.value.date
                    val matches = repo.getMatches(date)
                    _state.update {
                        it.copy(
                            allMatches = matches, loading = false, refreshing = false,
                            error = null, nowMs = System.currentTimeMillis(),
                            lastUpdatedAt = System.currentTimeMillis()
                        )
                    }
                    runCatching { MatchSnapshotStore.save(AppContextHolder.ctx, date, matches) }
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, refreshing = false, error = e.message) }
                }
            }
        }
    }

    /** 整页错误态点「重试」。 */
    fun retry() = refresh()

    /**
     * 消费一次性错误（有数据时用 Snackbar 展示，不遮挡列表）。
     * 与「整页错误」区分：那条走 `allMatches.isEmpty() && error != null`。
     */
    fun consumeError() = _state.update { it.copy(error = null) }

    /** 本地快照顶替：只在冷启动、内存里还没数据时调用。 */
    private fun restoreSnapshot(date: String) {
        val hit = runCatching { MatchSnapshotStore.load(AppContextHolder.ctx, date) }.getOrNull() ?: return
        // 期间已经有网络结果回来了就别覆盖
        if (_state.value.allMatches.isNotEmpty()) return
        _state.update {
            it.copy(
                allMatches = hit.first, loading = false,
                lastUpdatedAt = hit.second, nowMs = System.currentTimeMillis()
            )
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun toggleOnlyFollowedTeams() =
        _state.update { it.copy(onlyFollowedTeams = !it.onlyFollowedTeams) }

    /** 推进「当前时刻」，让刚开赛的场次自动从列表里退场（页面每 30 秒调一次）。 */
    fun tick() = _state.update { it.copy(nowMs = System.currentTimeMillis()) }

    fun toggleShowEnded() = _state.update { it.copy(showEnded = !it.showEnded) }
}

// ===== 竞彩赛程（体彩官方赛程表，纯表格页） =====
/**
 * 竞彩赛程页状态。
 *
 * 与「比赛」tab 的 [MatchUiState] **无关**：本页数据来自体彩官方 `getMatchListV1.qry`，
 * 一次返回多天（周五 ~ 周一），按销售日分组展示。
 *
 * 设计要点：
 *  - **无任何顶部控件**（日期切换条 / 「显示已截止」开关都已按需求移除）；
 *  - 因控件被移除，**不做任何过滤** —— 若仍过滤已停售场次，用户没有任何入口把它们找回来 = 静默丢数据；
 *  - 唯一刷新入口是手势下拉刷新，故用 refreshing 单独驱动指示器，不替换列表内容。
 */
/** 赛程页顶部 tab 的球类。 */
enum class ScheduleSport(val label: String) {
    FOOTBALL("足球赛程"),
    BASKETBALL("篮球赛程")
}

data class ScheduleUiState(
    /** 当前查看的球类（默认足球，与历史行为一致）。 */
    val sport: ScheduleSport = ScheduleSport.FOOTBALL,
    val groups: List<ScheduleDayGroup> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null
) {
    val totalCount: Int get() = groups.sumOf { it.matches.size }
}

class ScheduleViewModel : ViewModel() {
    private val repo = JingCaiRepository()
    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state

    init { load() }

    /** 首次进入 / 下拉刷新。刷新时保留旧列表，避免闪白。 */
    fun refresh() = fetch(isRefresh = true)

    fun load() = fetch(isRefresh = false)

    /**
     * 顶部 tab 切换球类（足球 <-> 篮球）。
     *
     * 切过去立即重新拉取；重复点当前项直接返回，不打无用请求。
     * 刷新期间**保留旧列表**（`loading=true` 但 `groups` 不清空），UI 的 `when` 分支
     * 只在 `loading && groups.isEmpty()` 时才显示 Loading，所以切换时不会闪白。
     */
    fun switchSport(sport: ScheduleSport) {
        if (sport == _state.value.sport) return
        _state.update { it.copy(sport = sport) }
        fetch(isRefresh = false)
    }

    private fun fetch(isRefresh: Boolean) {
        viewModelScope.launch {
            _state.update {
                if (isRefresh) it.copy(refreshing = true, error = null)
                else it.copy(loading = true, error = null)
            }
            try {
                // 足球/篮球是两套网关路径，但返回结构完全相同（见仓库层 parseMatchInfoList）。
                val groups = if (_state.value.sport == ScheduleSport.BASKETBALL) {
                    repo.getBasketballSchedule()
                } else {
                    repo.getJingcaiSchedule()
                }
                _state.update {
                    it.copy(groups = groups, loading = false, refreshing = false)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.message) }
            }
        }
    }
}

// ===== 比分直播（titan007，5 秒自动刷新） =====
/**
 * 比分直播页状态。
 *
 * 数据来自 titan007：`livestatic.titan007.com/vbsxml/bfdata_ut.js`（全量比赛 + 热门联赛）
 * + `cp.titan007.com/buy/JingCai.aspx`（竞彩编号/过滤）+ `data/dx101.xml`（大小球）
 * + `info/match/data/change.xml`（比分增量，覆盖全部进行中比赛）。
 *
 * 三个顶部子页签 [LiveTab]（参照 live.titan007.com，默认 [LiveTab.ALL] 完整）：
 *  - [LiveTab.ALL]：完整赛事（livestatic `bfdata_ut.js` 全量 ~288 场，跨所有联赛）；
 *  - [LiveTab.HOT]：热门赛事（联赛 `B[i][10] != 0` 的热门联赛）；
 *  - [LiveTab.JINGCAI]：竞彩赛事（titan007 竞彩场次，titanId 在 `JingCai.aspx` 集合里）。
 *    🔴 该集合是**多期合并**的（当期页 + 前 2 天带 `date` 的页面）：bfdata 的比赛日窗口跨两天，
 *    只拉当期页的话昨天已完赛的竞彩场次会全部缺失（实测 10 场 vs 25 场）。
 *
 * 三 tab **同源**（同一份全量数据，仅过滤条件不同），故内容互不相同。
 *
 * 刷新：页面可见时每 5 秒静默 tick（合并比分 / 状态 / 进行分钟数）；首次/切回/回前台立即整页刷新。
 */
enum class LiveTab { ALL, HOT, JINGCAI }

data class LiveUiState(
    val tab: LiveTab = LiveTab.ALL,
    /** 全量比赛（完整 tab 的数据，热门/竞彩由其过滤）。 */
    val allMatches: List<LiveMatch> = emptyList(),
    /** 热门联赛 sclassID 集合（热门 tab 过滤用）。 */
    val hotSclassIds: Set<String> = emptySet(),
    /** 竞彩 titanId 集合（竞彩 tab 过滤用）。 */
    val jcIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null
) {
    /** 当前 tab 可见的比赛（已按 tab 过滤）。 */
    val visibleMatches: List<LiveMatch>
        get() = when (tab) {
            LiveTab.ALL -> allMatches
            LiveTab.HOT -> allMatches.filter { it.sclassId in hotSclassIds }
            LiveTab.JINGCAI -> allMatches.filter { it.titanId.toString() in jcIds }
        }

    /** 为渲染兼容，包成单组（[LiveDayGroup]）。 */
    val groups: List<LiveDayGroup>
        get() = listOf(LiveDayGroup("", "", visibleMatches))

    val totalCount: Int get() = visibleMatches.size
}

class LiveViewModel : ViewModel() {
    private val repo = LiveScoreRepository()
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state

    /** 页面可见性：仅 RESUMED 时为真，驱动 5 秒 tick（切走/退后台即停，省流量）。 */
    private val _active = MutableStateFlow(false)

    /** 保证同一时刻只有一个在途请求（tick 与整页刷新互斥，防叠加重拉）。 */
    private val mutex = Mutex()
    private var tickCount = 0
    /** 关注比赛上一次比分（用于 tick 内检测进球，仅比分上涨时发事件）。 */
    private val followScores = HashMap<Long, String>()

    init {
        // 冷启动先把上次的快照顶上去（0ms 出画面），再让 fullRefresh / 5 秒 tick 去纠正。
        restoreSnapshot()

        // ViewModel 内部自驱的 5 秒轮询：与 Compose 生命周期解耦，避免旧实现里
        // 「tick 实际未触发」导致大小球/比分/进行分钟数全程不刷新。
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (_active.value) tick()
            }
        }
    }

    /**
     * 用本地快照先填一屏。只在「内存里还没有任何比赛」时生效，
     * 避免覆盖刚回来的网络结果（init 与 fullRefresh 可能几乎同时发生）。
     */
    private fun restoreSnapshot() {
        val p = runCatching { LiveSnapshotStore.load(AppContextHolder.ctx) }.getOrNull() ?: return
        _state.update {
            if (it.allMatches.isNotEmpty()) it
            else it.copy(allMatches = p.matches, hotSclassIds = p.hotIds, jcIds = p.jcIds)
        }
    }

    /** 页面 RESUMED 调用；切走/退后台调 [setInactive]。 */
    fun setActive() { _active.value = true }    /** 页面 PAUSE/STOP 调用，停止 5 秒 tick。 */
    fun setInactive() { _active.value = false }

    /**
     * **仅调试包**：给当前第一场进行中的比赛打一次 30 秒闪色。
     *
     * 用途只有一个——回归「队名底色」的两种颜色（橄榄黄=进球 / 浅玫红=取消进球）。
     * 真实触发要等一次 VAR 改判，无法按需复现；入口是 `-e demo_flash "1|0"`（见 MainActivity）。
     * 走的是与真实闪色完全相同的字段（flashSide/flashAt/flashCancel），所以看到的就是线上效果。
     */
    fun debugFlashFirst(cancelled: Boolean) {
        _state.update { st ->
            val idx = st.allMatches.indexOfFirst { it.state != 0 && it.state != -1 }
            if (idx < 0) return@update st
            val list = st.allMatches.toMutableList()
            list[idx] = list[idx].copy(
                flashSide = 1,
                flashAt = System.currentTimeMillis(),
                flashCancel = cancelled
            )
            st.copy(allMatches = list)
        }
    }

    /**
     * 整页重建（fetchBfData）会把进球闪色重置，这里：
     *  1. 把**仍在 30 秒窗口内**的闪色从旧列表搬回来，保证「进球→闪 30 秒」不被每 15 秒的全量兜底刷新打断；
     *  2. 【关键修复】旧闪色已过期/不存在时，按「新比分 vs 旧比分」检测**本 tick 的进球**并即时闪色。
     *     旧实现漏了第 2 步：整页重建（每第 3 个 tick）只保留旧闪色、不检测新进球，导致凡是先被整页
     *     重建读到新比分的进球（约 1/3）根本不闪——表现为「有些进球（尤其后段比赛）底色没变橙黄」。
     */
    private fun mergeFlash(oldList: List<LiveMatch>, newList: List<LiveMatch>): List<LiveMatch> {
        if (oldList.isEmpty()) return newList
        val now = System.currentTimeMillis()
        val oldMap = oldList.associateBy { it.titanId }
        return newList.map { m ->
            val o = oldMap[m.titanId]
            // 🔴 顺序很重要：**先判「本拍比分变了」再判「沿用旧闪色」**。
            //    反过来写的话，上一粒进球还在 30 秒窗口内时，进球取消会被「沿用旧闪色」吃掉，
            //    队名底色不会从橄榄黄切成玫红（用户要求「取消进球名称底色也要变」）。
            val oldScore = o?.score ?: m.score
            val (oh, oa) = TitanLiveParser.parseScore(oldScore)
            val (nh, na) = TitanLiveParser.parseScore(m.score)
            // "-" 是「未开始」的占位比分，解析成 0-0；拿它比对会把进行中的比分误判成「全部回退」。
            val valid = oldScore != "-" && m.score != "-"
            val upSide = when {
                nh > oh -> 1
                na > oa -> 2
                else -> 0
            }
            val downSide = when {
                nh < oh -> 1
                na < oa -> 2
                else -> 0
            }
            when {
                // 本拍进球：橄榄黄底
                valid && upSide != 0 -> m.copy(flashSide = upSide, flashAt = now, flashCancel = false)
                // 本拍进球被取消（比分回退）：同一套 30 秒窗口，但换玫红底
                valid && downSide != 0 -> m.copy(flashSide = downSide, flashAt = now, flashCancel = true)
                // 无变化且旧闪色仍在窗口内：原样沿用（含 cancel 标记）
                o != null && o.flashSide != 0 && now - o.flashAt < 30_000L ->
                    m.copy(flashSide = o.flashSide, flashAt = o.flashAt, flashCancel = o.flashCancel)
                else -> m
            }
        }
    }

    /** 切换顶部页签。 */
    fun setTab(tab: LiveTab) { _state.update { it.copy(tab = tab) } }

    /** 下拉刷新 / 立即整页刷新：显示下拉指示器。 */
    fun refresh() {
        viewModelScope.launch { doFull(showRefreshing = true, showLoading = false) }
    }

    /** 立即整页刷新（首次进入 / 切回本 tab / 回前台）：有数据时静默，不闪 loading。 */
    fun fullRefresh() {
        val hasData = _state.value.groups.isNotEmpty()
        viewModelScope.launch { doFull(showRefreshing = false, showLoading = !hasData) }
    }

    /**
     * 5 秒 tick（ViewModel 内部循环在 [active] 时驱动）：
     *  - 普通 tick：拉 `change.xml` 合并比分 / 状态 / 进行分钟数（覆盖全部进行中比赛）；
     *  - 每第 3 个 tick（≈15s）：整页兜底重建（重拉 bfdata + 竞彩编号 + 大小球）。
     *
     * 全程**静默**：不置 loading/refreshing，失败忽略、保留旧数据。
     */
    suspend fun tick() {
        mutex.withLock {
            tickCount += 1
            val idx = tickCount
            var chg = 0
            var full = false

            if (idx % 3 == 0) {
                // 整页兜底：全量重建（含竞彩编号 / 大小球合并），并保留未过期的进球闪色
                try {
                    val raw = repo.fetchBfData()
                    val merged = mergeFlash(_state.value.allMatches, raw.matches)
                    _state.update {
                        it.copy(
                            allMatches = merged,
                            hotSclassIds = raw.hotSclassIds,
                            jcIds = raw.jcIds
                        )
                    }
                    full = true
                } catch (e: Exception) {
                    // 静默
                }
            } else {
                // 比分 / 状态 / 进行分钟数增量（每次 tick 都拉，体积 ~0.2KB）
                try {
                    val changes = TitanLiveParser.parseChanges(repo.fetchRawChanges())
                    chg = changes.size
                    if (changes.isNotEmpty()) {
                        _state.update { st ->
                            st.copy(allMatches = TitanLiveParser.applyChanges(st.allMatches, changes, repo.effectiveNow()))
                        }
                    }
                } catch (e: Exception) {
                    // 静默
                }
            }

            // 官方举牌补时（唯一正确来源：手机版 livechange.txt）每次 tick 都合并；没出现的场次沿用旧值、不回退。
            mergeInjuryTime()
            // 关注比赛进球检测（在页内→动画弹窗；否则→状态栏）。受 goalAlert 总开关约束。
            detectGoals()
            // 开赛提醒：前台也要查——用户此刻可能正停在投注页 / 资料页，看不到直播列表。
            // 每 12 个 tick（≈60 秒）一次：提醒精度是分钟级，没必要 5 秒算一遍（还要读 prefs）。
            // 与后台 [BackgroundGoalWatcher] 走同一个 [KickoffAlert.check]，落盘去重保证只弹一条。
            if (idx % 12 == 1 && UserPrefs.kickoffAlert.value) {
                runCatching { KickoffAlert.check(_state.value.allMatches) }
            }

            Log.i("LiveRefresh", "tick #$idx t=${System.currentTimeMillis()} chg=$chg full=$full")
        }
    }

    /**
     * 合并官方补时（手机版 `livechange.txt`）。增量流语义：只在场次出现在流中且值变化时覆盖其
     * [LiveMatch.injuryTime]；未出现的场次沿用旧值（不回退到空串，避免漏显）。拉取/解析失败静默、保留旧数据。
     */
    private suspend fun mergeInjuryTime() {
        try {
            val map = TitanLiveParser.parseLiveChange(repo.fetchRawLiveChange())
            if (map.isNotEmpty()) {
                _state.update { st ->
                    st.copy(allMatches = TitanLiveParser.applyInjuryTime(st.allMatches, map))
                }
            }
        } catch (e: Exception) {
            // 静默：补时非关键路径，拉取失败不影响比分/状态
        }
    }

    /**
     * 检测关注比赛的进球**与进球取消**：与 [followScores] 上一次比分对比。
     *  - 主或客进球数**上涨** → 进球事件；
     *  - 主或客进球数**回退** → 进球被取消（VAR 改判 / 进球取消），弹窗与状态栏都换成「取消进球」文案。
     * 首见某关注比赛（刚关注 / 刚进入页面）只记录基线、不误报。
     * 受 [UserPrefs.goalAlert] 总开关约束（关掉就只同步基线、不发事件）。
     */
    private fun detectGoals() {
        val followed = UserPrefs.followed.value
        if (followed.isEmpty()) { followScores.clear(); return }
        for (m in _state.value.allMatches) {
            if (m.titanId !in followed) continue
            // 已完场（state == -1）不再产生进球提醒：终场比分定格的最后一拍可能被解析成「上涨」，
            // 但那已经不是「刚进球」，提醒没有意义（页面也会自动摘掉关注）。
            if (m.state == -1) continue
            val prev = followScores[m.titanId]
            val new = m.score
            // 🔴 "-" 是未开始的占位比分（parseScore 会解析成 0-0）。拿它参与比对会凭空产出
            //    「2-1 → 0-0」这种不存在的「取消进球」，所以只刷新基线、跳过判定。
            if (new.isEmpty() || new == "-") {
                followScores[m.titanId] = new
                continue
            }
            if (prev != null && prev != new && prev != "-" && UserPrefs.goalAlert.value) {
                val (ph, pa) = TitanLiveParser.parseScore(prev)
                val (nh, na) = TitanLiveParser.parseScore(new)
                when {
                    nh > ph || na > pa -> {
                        val side = if (nh > ph) 1 else 2
                        GoalDeliverer.onGoal(GoalEvent(m.titanId, m.home, m.away, new, side, m.league))
                    }
                    nh < ph || na < pa -> {
                        // 回退的一侧 = 被扣掉进球的那支球队（弹窗里给它打高亮）
                        val side = if (nh < ph) 1 else 2
                        GoalDeliverer.onCancel(
                            GoalEvent(m.titanId, m.home, m.away, new, side, m.league)
                        )
                    }
                }
            }
            followScores[m.titanId] = new
        }
    }

    private suspend fun doFull(showRefreshing: Boolean, showLoading: Boolean) {
        mutex.withLock {
            _state.update {
                when {
                    showRefreshing -> it.copy(refreshing = true, error = null)
                    showLoading -> it.copy(loading = true, error = null)
                    else -> it
                }
            }
            try {
                val raw = repo.fetchBfData()
                val merged = mergeFlash(_state.value.allMatches, raw.matches)
                _state.update {
                    it.copy(
                        allMatches = merged,
                        hotSclassIds = raw.hotSclassIds,
                        jcIds = raw.jcIds,
                        loading = false,
                        refreshing = false
                    )
                }
                // 桌面上放了小组件就顺手重绘一次（没放时 requestRefresh 是空操作）
                runCatching { com.example.jingcai.widget.LiveWidgetProvider.requestRefresh(AppContextHolder.ctx) }
                // 成功就刷新本地快照：下次冷启动/弱网时先看到这一份
                runCatching {
                    LiveSnapshotStore.save(
                        AppContextHolder.ctx,
                        LiveSnapshotStore.Payload(
                            matches = merged,
                            hotIds = raw.hotSclassIds,
                            jcIds = raw.jcIds,
                            savedAt = System.currentTimeMillis()
                        )
                    )
                }
                // 整页重建后补时落回默认值，立即再合并一次官方补时，避免首屏/刷新后时间列漏显「补N'」。
                mergeInjuryTime()
                // 用最新比分重置关注比赛基线，避免把「进行中的既有比分」误判成新进球
                for (m in _state.value.allMatches) {
                    if (m.titanId in UserPrefs.followed.value) followScores[m.titanId] = m.score
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        // 只有「首次加载且无任何数据」时才暴露错误，静默刷新失败保留旧数据
                        error = if (it.allMatches.isEmpty()) e.message else it.error
                    )
                }
            }
        }
    }
}

// ===== 比分直播 → 篮球（qiumibao / 188bifen 同源） =====
/**
 * 「比分直播」顶部球类页签。足球沿用原 [LiveViewModel]（titan007），
 * 篮球走 [BbLiveViewModel]（qiumibao，188bifen 篮球页同源）——**两套数据源完全独立**，
 * 不要试图合并成一个 VM：字段、刷新节奏、过滤条件都不同。
 */
enum class LiveSport(val label: String) {
    FOOTBALL("足球"),
    BASKETBALL("篮球")
}

data class BbLiveUiState(
    /**
     * 扁平的全量（增量补丁直接改它，再由 [sections] 派生视图）。
     *
     * 🔴 这里存的是**已按体彩队名对齐主客**的列表（见 `BbLiveViewModel.alignToJc`），
     *    不是源站原始顺序 —— 欧洲联赛源站是「主 @ 客」，与 NBA 相反。
     */
    val all: List<BbLiveMatch> = emptyList(),
    val sections: List<BbSection> = emptyList(),
    /**
     * 竞彩赔率表：`竞彩编号 → 三玩法赔率`。
     *
     * 🔴 与比分**分开两条链路**：比分 6 秒一刷，赔率 5 分钟 / 下拉才刷（赔率变动远没有比分频繁，
     *    跟着 6 秒刷等于白打接口，还容易被 WAF 限流）。取不到就是空表，页面照常显示比分。
     */
    val odds: Map<String, BbJcOdds> = emptyMap(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null
) {
    val totalCount: Int get() = all.size
}

/**
 * 篮球比分直播 VM。
 *
 * 刷新策略（与足球一致的心智模型，但用「版本号 + 增量」省流量）：
 *  - 首屏 / 回前台 / 下拉 → `fetchAll()`（~310KB）；
 *  - 每 6 秒 tick → 只拉 `version.htm`（几十字节），**版本号变了才**拉增量（1~2KB）；
 *  - 每 5 分钟兜底整页重建（比赛跨天、联赛色板变化等只有全量才能反映）。
 *
 * 为什么不像足球那样「每次 tick 都拉增量」：这个源没有 cheap 的全量增量流，
 * 增量必须靠版本号寻址，硬拉等于反复下载同一份数据。
 */
class BbLiveViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = BbLiveRepository()
    // 赔率要落盘存档（停售后接口不再返回，但用户仍想看当时的盘口）⇒ 需要 Context
    private val oddsRepo = BbJcOddsRepository(app)
    private val _state = MutableStateFlow(BbLiveUiState())
    val state: StateFlow<BbLiveUiState> = _state

    /** 页面可见性：仅 RESUMED 时驱动 6 秒 tick。 */
    private val _active = MutableStateFlow(false)

    private val mutex = Mutex()

    /**
     * 源站顺序的**原始**列表（未做体彩主客对齐）。
     *
     * 🔴 增量补丁必须打在它身上，而不是 `_state.value.all`：后者已经过 [alignToJc] 交换，
     *    再用源站语义的 `homeScore/awayScore` 去覆盖，会把刚对好的主客又写反一次
     *    （症状：刷新一次对、tick 一次又反，用户看到「比分在两边跳」）。
     */
    private var raw: List<BbLiveMatch> = emptyList()

    /** 当前已合并到的最新版本号；与 `version.htm` 一致时跳过增量。 */
    @Volatile private var version: Long = 0L

    /** 上次整页重建时刻（5 分钟兜底）。 */
    @Volatile private var lastFullAt: Long = 0L

    init {
        // 先把存档里的赔率贴上去（IO 线程，别在主线程读 prefs），接口回来再整体覆盖
        viewModelScope.launch(Dispatchers.IO) {
            val snap = oddsRepo.snapshot()
            if (snap.isNotEmpty()) _state.update { it.copy(odds = snap) }
        }
        viewModelScope.launch {
            while (true) {
                delay(6_000)
                if (_active.value) tick()
            }
        }
    }

    fun setActive() { _active.value = true }

    fun setInactive() { _active.value = false }

    /** 下拉刷新（显示指示器，保留旧列表不闪白）。 */
    fun refresh() {
        viewModelScope.launch { doFull(showLoading = false, showRefreshing = true) }
    }

    /** 首次进入 / 切回本页 / 回前台：有数据时静默重建，避免闪 loading。 */
    fun fullRefresh() {
        val empty = _state.value.all.isEmpty()
        viewModelScope.launch { doFull(showLoading = empty, showRefreshing = false) }
    }

    /**
     * 6 秒 tick：只查版本号，变了才拉增量合并。
     * 全程静默——失败保持旧数据，绝不清空列表、绝不弹错。
     */
    suspend fun tick() {
        if (System.currentTimeMillis() - lastFullAt > 5 * 60_000L) {
            fullRefresh()
            return
        }
        mutex.withLock {
            try {
                val (v, url) = repo.fetchVersion() ?: return@withLock
                if (v == version) return@withLock
                val patches = repo.fetchPatch(url)
                version = v
                if (patches.isEmpty()) return@withLock
                // 🔴 打在 raw 上（源站语义），再整体对齐一次 —— 见 [raw] 的注释
                val merged = applyPatches(raw, patches)
                raw = merged
                val aligned = alignToJc(merged, _state.value.odds)
                _state.update { it.copy(all = aligned, sections = buildSections(aligned)) }
                Log.i("BbLive", "tick v=$v patches=${patches.size}")
            } catch (e: Exception) {
                // 静默：tick 失败不影响已有数据
            }
        }
    }

    private suspend fun doFull(showLoading: Boolean, showRefreshing: Boolean) {
        mutex.withLock {
            _state.update {
                it.copy(
                    loading = showLoading,
                    refreshing = showRefreshing,
                    error = if (showLoading) null else it.error
                )
            }
            try {
                val list = repo.fetchAll()
                // 版本号顺手刷新，避免首屏后第一次 tick 就白拉一次增量。
                version = try { repo.fetchVersion()?.first ?: 0L } catch (e: Exception) { 0L }
                lastFullAt = System.currentTimeMillis()
                // 🔴 赔率是**附加信息**：拉失败就沿用上一次的表（首次失败＝空表），
                //    绝不能让一次赔率接口抖动把整页比分变成错误页。
                val odds = runCatching { oddsRepo.getOdds(force = true) }
                    .getOrElse { _state.value.odds }
                raw = list
                val aligned = alignToJc(list, odds)
                _state.update {
                    it.copy(
                        all = aligned,
                        sections = buildSections(aligned),
                        odds = odds,
                        loading = false,
                        refreshing = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        // 只有首次且无任何数据时才暴露错误，静默刷新失败保留旧列表
                        error = if (it.all.isEmpty()) e.message else it.error
                    )
                }
            }
        }
    }

    /**
     * 分区：**未结束（进行中 + 未开始）在上，已结束沉到最下面**，延期/异常垫底。
     *
     * 与 m.188bifen 篮球页的顺序一致（它也是 未开始 → 「已结束」 → 完赛 → 延期）：
     *  - 未结束区：按日期升序（今天 → 明天），组内「进行中 → 未开始」；
     *  - 已结束区：按日期**降序**（最近的比赛在最上面，不会一进来就是去年的赛果）；
     *  - 延期/异常：单独垫底。
     */
    private fun buildSections(list: List<BbLiveMatch>): List<BbSection> {
        if (list.isEmpty()) return emptyList()
        val postponed = list.filter { it.status == 9 || it.status == 0 }
        val normal = list.filter { it.status != 9 && it.status != 0 }
        val finished = normal.filter { it.isFinished }
        val pending = normal.filter { !it.isFinished }

        fun days(rows: List<BbLiveMatch>, ascending: Boolean): List<BbDayGroup> {
            val sorted = rows.sortedWith(compareBy({ it.sortRank }, { it.startTs }))
            val map = sorted.groupBy { it.dateKey }
            val keys = if (ascending) map.keys.sorted() else map.keys.sortedDescending()
            return keys.map { k -> BbDayGroup(map[k]!!.first().dateLabel, map[k]!!) }
        }

        val out = ArrayList<BbSection>(3)
        if (pending.isNotEmpty()) out.add(BbSection("", days(pending, ascending = true)))
        if (finished.isNotEmpty()) out.add(BbSection("已结束", days(finished, ascending = false)))
        if (postponed.isNotEmpty()) out.add(BbSection("延期 / 异常", days(postponed, ascending = true)))
        return out
    }

    /** 把增量补丁按 matchId 合并回扁平列表（打不中的场次直接忽略）。 */
    /**
     * 用**体彩队名**校正主客：源站的队位顺序并不统一 ——
     * NBA 是「客 @ 主」（`[5]`=客），而欧洲联赛（欧篮联 / 欧俱杯…）是「主 @ 客」（`[5]`=主）。
     *
     * 2026-11-18 实测（用户报障「欧冠篮球主客队让分反了」）：
     * ```
     * 周二301   源站 [5]=迪拜 80  :  [6]=伊斯坦布尔埃菲斯 75
     * ```
     * EuroLeague 官网 / 场馆（Coca-Cola Arena）确认**迪拜是主队**；解析层按「[5]=客」会把它
     * 显示成客队，让分线（主队视角）也就挂到埃菲斯那一侧 —— 正是用户看到的现象。
     * 同一天另外三场欧篮联（巴斯克尼亚 82:85 夏普尔、马卡比 95:97 奥林匹亚科斯、
     * 萨拉基利斯 117:114 拜仁）逐场核对官方比分，**全部是「主场在前」**。
     *
     * 判定只用一条规则（与结算的 [com.example.jingcai.data.settle.BasketScore.alignedTo]
     * 完全同口径）：「体彩客队 == 源站的主队」且「体彩主队 ≠ 源站的主队」⇒ 两侧反了，交换。
     * 队名允许双向包含（体彩给简称、源站给全称：`埃菲斯` ⊂ `伊斯坦布尔埃菲斯`）。
     *
     * 🔴 但**队名匹配只是兜底**：体彩对欧洲球队的简称极短（`贝红星` / `巴萨` / `贝游击`），
     *    与源站的 `贝尔格莱德红星` / `巴塞罗那` / `贝尔格莱德游击` 既不是子串、字符集也不互相
     *    包含，纯靠译名一定漏判。所以优先用 [BbLiveMatch.hostOnFirst] —— 源站自己的让分标注
     *    挂在哪一侧就是主队，11/11 核对命中。只有它 = null（未开售、没有标注）时才退回队名。
     *
     * ⚠️ 不能按联赛名硬编码 —— 用户报的这一场能修，明天换个联赛又错。没拿到赔率的场次
     * （未开售 / 非竞彩）即便判不出也只是队名左右顺序，不涉及让分。
     */
    private fun alignToJc(list: List<BbLiveMatch>, odds: Map<String, BbJcOdds>): List<BbLiveMatch> {
        if (list.isEmpty()) return list
        var flipped = 0
        val out = list.map { m ->
            val swap = when (m.hostOnFirst) {
                // 源站标注已经在告诉我们答案，不需要赔率、也不怕译名差异
                true -> true
                false -> false
                // 没有标注：只能用体彩队名猜（拿不到赔率就保持源站顺序）
                null -> odds[m.jcNo]?.let { jcSaysSwapped(it, m.home) } ?: false
            }
            if (swap) {
                flipped++
                m.swapped()
            } else {
                m
            }
        }
        if (flipped > 0) Log.i("BbLive", "主客对齐：交换 $flipped 场（源站把主队放在第一支）")
        return out
    }

    /** 体彩口径下，源站那一侧的主队是不是其实是客队。 */
    private fun jcSaysSwapped(o: BbJcOdds, sourceHome: String): Boolean {
        val awayIsMyHome = jcNameEq(o.awayTeam, sourceHome)
        val homeIsMyHome = jcNameEq(o.homeTeam, sourceHome)
        // 🔴 只认一种翻法：两边都能对上（队名互为子串）时宁可不翻 —— 判错的代价比漏翻大
        return awayIsMyHome && !homeIsMyHome
    }

    /** 队名是否同一支：完全相等，或一方是另一方的子串（体彩简称 vs 源站全称）。 */
    private fun jcNameEq(x: String, y: String): Boolean {
        val p = x.trim()
        val q = y.trim()
        if (p.isEmpty() || q.isEmpty()) return false
        return p == q || p.contains(q) || q.contains(p)
    }

    private fun applyPatches(all: List<BbLiveMatch>, patches: List<BbPatch>): List<BbLiveMatch> {
        if (patches.isEmpty()) return all
        val map = patches.associateBy { it.matchId }
        return all.map { m ->
            val p = map[m.matchId] ?: return@map m
            m.copy(
                status = p.status,
                homeScore = p.homeScore,
                awayScore = p.awayScore,
                homeHalf = p.homeHalf,
                awayHalf = p.awayHalf,
                period = p.period,
                homeQuarters = p.homeQuarters.ifEmpty { m.homeQuarters },
                awayQuarters = p.awayQuarters.ifEmpty { m.awayQuarters }
            )
        }
    }
}

// ===== 同奖查询 =====
data class SameOddsUiState(
    val h: String = "",
    val d: String = "",
    val a: String = "",
    val bonus: Int = 0,
    val result: SameOddsResult? = null,
    val loading: Boolean = false,
    val error: String? = null
)

class SameOddsViewModel(private val handle: SavedStateHandle) : ViewModel() {
    private val repo = JingCaiRepository()
    private val _state = MutableStateFlow(SameOddsUiState())
    val state: StateFlow<SameOddsUiState> = _state

    init {
        // 从比赛列表「同奖」按钮进入时预设赔率并自动查询
        val h = handle.get<String>("h") ?: ""
        val d = handle.get<String>("d") ?: ""
        val a = handle.get<String>("a") ?: ""
        if (h.isNotEmpty() || d.isNotEmpty() || a.isNotEmpty()) {
            _state.update { it.copy(h = h, d = d, a = a) }
            query()
        }
    }

    fun setOdds(h: String, d: String, a: String) = _state.update { it.copy(h = h, d = d, a = a) }
    fun setBonus(bonus: Int) = _state.update { it.copy(bonus = bonus) }

    /** 重置：与 WinForms 的「重置」一致，条件回到「全部」、清空结果。 */
    fun reset() = _state.update {
        it.copy(h = "", d = "", a = "", bonus = 0, result = null, error = null)
    }

    fun query() {
        val s = _state.value
        if (s.h.isBlank() && s.d.isBlank() && s.a.isBlank()) {
            _state.update { it.copy(error = "请至少选择一个条件") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val r = repo.getSameOdds(
                    s.h.ifBlank { null }, s.d.ifBlank { null }, s.a.ifBlank { null }, s.bonus
                )
                _state.update { it.copy(result = r, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}

// ===== 赔率变化 =====
data class OddsChangeUiState(
    val order: String = "",
    val vsText: String = "",
    val data: FixedBonusData? = null,
    val loading: Boolean = false,
    val error: String? = null
)

class OddsChangeViewModel : ViewModel() {
    private val repo = JingCaiRepository()
    private val _state = MutableStateFlow(OddsChangeUiState())
    val state: StateFlow<OddsChangeUiState> = _state

    fun load(order: String, vsText: String) {
        viewModelScope.launch {
            _state.update { it.copy(order = order, vsText = vsText, loading = true, error = null, data = null) }
            try {
                val data = repo.getFixedBonus(order)
                if (data == null) {
                    _state.update { it.copy(loading = false, error = "未找到该场在体彩的对应比赛（已截止场次不提供赔率变化）") }
                } else {
                    _state.update { it.copy(data = data, loading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}

/**
 * 竞彩**胜平负赔率变动**页状态。
 *
 * 只按 `spid`（场次级赔率 id）取数，与「赔率变化」页（按竞彩编号查体彩 `getFixedBonus`）**两条链路**：
 * 前者是 titan007 的竞彩变动记录（能精确到这一场的胜平负），后者是体彩官方的固定奖金历史。
 */
data class SpfChangeUiState(
    val spid: String = "",
    /** 玩法：105 胜平负 / 101 让球胜平负。两者共用 spid，只有 typeID 不同。 */
    val typeId: Int = LiveScoreRepository.TYPE_SPF,
    val rows: List<JcSpfChange> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

class SpfChangeViewModel : ViewModel() {
    private val repo = LiveScoreRepository()
    private val _state = MutableStateFlow(SpfChangeUiState())
    val state: StateFlow<SpfChangeUiState> = _state

    /**
     * @param typeId `105` 胜平负 / `101` 让球胜平负（亚盘）。默认 [LiveScoreRepository.TYPE_SPF]。
     *
     * 🔴 切换玩法时**必须清空 rows 并重新拉**：`spid` 是同一个，直接沿用旧数据会让
     *    让球赔率页显示胜平负的数字（两者量级相近、肉眼很难发现错）。
     */
    fun load(spid: String, typeId: Int = LiveScoreRepository.TYPE_SPF) {
        if (spid.isBlank()) {
            _state.update { SpfChangeUiState(error = "该场没有赔率变动记录") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(spid = spid, typeId = typeId, loading = true, error = null, rows = emptyList())
            }
            try {
                val rows = repo.fetchSpfHistory(spid, typeId)
                // 期间用户又切了玩法 ⇒ 丢弃这次的结果（否则慢的那个请求会覆盖新玩法的数据）
                if (_state.value.typeId != typeId) return@launch
                _state.update { it.copy(rows = rows, loading = false) }
            } catch (e: Exception) {
                if (_state.value.typeId != typeId) return@launch
                _state.update { it.copy(loading = false, error = e.message ?: "赔率变动加载失败") }
            }
        }
    }

    /** 切换玩法（同一个 spid 重拉）。 */
    fun switchType(typeId: Int) {
        val sp = _state.value.spid
        if (_state.value.typeId == typeId) return
        load(sp, typeId)
    }
}

// ===== 赛事资料 =====

data class LeagueUiState(
    val groups: List<LeagueGroup> = emptyList(),
    val current: LeagueInfo? = null,
    val season: LeagueSeason? = null,
    val tab: Int = 0,
    val schedule: List<ScheduleDay> = emptyList(),
    val standings: List<StandingRow> = emptyList(),
    /** 赛程查询日期范围（默认今天 ~ +7 天，与 WinForms 一致）。 */
    val rangeStart: String = today(),
    val rangeEnd: String = shiftDate(today(), 7),
    val loading: Boolean = false,
    /** 下拉刷新中：**不清空**已有赛程/积分榜，只转指示器。 */
    val refreshing: Boolean = false,
    val error: String? = null
) {
    /** 热门联赛（用于顶部高亮快捷条）。 */
    val hotLeagues: List<LeagueInfo> get() = groups.firstOrNull { it.name == "热门" }?.leagues ?: emptyList()
}

class LeagueViewModel : ViewModel() {
    private val repo = JingCaiRepository()
    private val _state = MutableStateFlow(LeagueUiState())
    val state: StateFlow<LeagueUiState> = _state

    init { loadGroups() }

    private fun loadGroups() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val groups = repo.getLeagueGroups()
                val first = groups.firstOrNull()?.leagues?.firstOrNull()
                val season = first?.seasons?.firstOrNull()
                _state.update { it.copy(groups = groups, current = first, season = season, loading = false) }
                first?.let { loadData(it, season) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun selectLeague(league: LeagueInfo) {
        val season = league.seasons.firstOrNull()
        // 切换联赛时重置日期范围，与 WinForms「SetDefaultRange」一致
        _state.update {
            it.copy(
                current = league, season = season,
                rangeStart = today(), rangeEnd = shiftDate(today(), 7)
            )
        }
        loadData(league, season)
    }

    fun selectSeason(season: LeagueSeason) {
        val league = _state.value.current ?: return
        _state.update { it.copy(season = season) }
        loadData(league, season)
    }

    fun switchTab(tab: Int) {
        _state.update { it.copy(tab = tab) }
        val s = _state.value
        s.current?.let { loadData(it, s.season) }
    }

    /** 赛程日期范围：直接指定起止日期。 */
    fun setRange(start: String, end: String) {
        _state.update { it.copy(rangeStart = start, rangeEnd = end) }
        reload()
    }

    /** 赛程日期范围平移 N 天（与 WinForms 前七天/后七天一致）。 */
    fun shiftRange(days: Int) {
        val s = _state.value
        setRange(shiftDate(s.rangeStart, days), shiftDate(s.rangeEnd, days))
    }

    /** 回到默认范围（今天 ~ +7 天）。 */
    fun resetRange() = setRange(today(), shiftDate(today(), 7))

    fun reload() {
        val s = _state.value
        s.current?.let { loadData(it, s.season) }
    }

    /**
     * 下拉刷新：重拉当前联赛 + 当前赛季。
     *
     * 与 [reload] 的区别：不置 `loading`（那会把赛程表整块换成转圈），只置 `refreshing`
     * 让下拉指示器转，表内容原地留着。
     */
    fun refresh() {
        val s = _state.value
        val league = s.current
        if (league == null) { loadGroups(); return }
        loadData(league, s.season, isRefresh = true)
    }

    private fun loadData(league: LeagueInfo, season: LeagueSeason?, isRefresh: Boolean = false) {
        if (season == null) return
        viewModelScope.launch {
            _state.update {
                if (isRefresh) it.copy(refreshing = true, error = null)
                else it.copy(loading = true, error = null)
            }
            try {
                if (_state.value.tab == 0) {
                    val schedule = repo.getMatchResult(
                        league.uniformLeagueId, season.seasonId,
                        _state.value.rangeStart, _state.value.rangeEnd
                    )
                    _state.update { it.copy(schedule = schedule, loading = false, refreshing = false) }
                } else {
                    val standings = repo.getTables(league.uniformLeagueId, season.seasonId)
                    _state.update { it.copy(standings = standings, loading = false, refreshing = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.message) }
            }
        }
    }
}
