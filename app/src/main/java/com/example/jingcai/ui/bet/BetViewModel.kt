package com.example.jingcai.ui.bet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.jingcai.data.model.BetMarket
import com.example.jingcai.data.model.BetMatch
import com.example.jingcai.data.model.BetOption
import com.example.jingcai.data.model.BetPick
import com.example.jingcai.data.model.BetSport
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.TicketStatus
import com.example.jingcai.data.model.betPickKey
import com.example.jingcai.data.network.BetSettleParser
import com.example.jingcai.data.repository.BetRepository
import com.example.jingcai.data.repository.BetSettleRepository
import com.example.jingcai.data.repository.BetTicketStore
import com.example.jingcai.data.settle.BetCalculator
import com.example.jingcai.data.settle.Parlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 模拟投注页状态。
 *
 * ## 本版口径（相对上一版的改动）
 *  1. **去掉账户 / 余额**：本 App 不涉及资金账户，下单不扣款、不判余额，
 *     页面任何位置都不显示余额（上一版是模拟金 10000 元）；
 *  2. **支持自由过关**：[parlayMs] 是**可多选**的关次集合，例如 `{2,3}` 就是竞彩的「3串4」
 *     （3 场打 2串1 + 3串1 = 4 注）；注数 = 各关次注数之和，见 [BetCalculator.countBets]；
 *  3. **复式**：同一场可选多个选项（键 = `matchId|玩法|选项键`），注数公式自动按选项展开；
 *  4. **不显示最高奖金**：下单前只看「注数 + 本金」，中奖金额在记录 / 账本里看结果。
 */
data class BetUiState(
    val sport: BetSport = BetSport.FOOTBALL,
    val matches: List<BetMatch> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 键 = `matchId|玩法|选项键`，所以同场多选互不覆盖（复式）。 */
    val picks: Map<String, BetPick> = emptyMap(),
    val times: Int = 1,
    /**
     * 过关方式（可多选）：`{1}` = 单关，`{2}` = 2串1，`{2,3}` = 3串4，`{1,2,3}` = 3串7。
     *
     * 🔴 用户要求「把单关默认选中去掉」⇒ 默认是**空集合**（一个关次都不勾）。
     *    这时注数/本金都是 0、投注按钮灰着，必须自己点一个关次才能下单，
     *    避免「以为默认是 2串1，结果下单成了两注单关」。切球类、下单成功后同样回到空集合。
     */
    val parlayMs: Set<Int> = emptySet(),
    /** 正在提交（拉 titanId 映射 + 落盘），期间按钮禁用防重复下单。 */
    val placing: Boolean = false,
    /** 正在展开「全部玩法」弹窗的比赛；null = 弹窗关闭。 */
    val sheetMatch: BetMatch? = null,
    /** 待复制文本：UI 复制完调 [BetViewModel.consumeCopy] 清空，避免重复复制。 */
    val pendingCopy: String? = null,
    /** 一次性提示（已保存/请先选择），展示后由 UI 清空。 */
    val message: String? = null
) {
    val pickList: List<BetPick> get() = picks.values.toList()

    /** 已选**选项**个数（同场多选会累计）。 */
    val pickCount: Int get() = picks.size

    /** 已选**场次**数 = 可选的最高关次。 */
    val matchCount: Int get() = picks.values.map { it.matchId }.distinct().size

    /** 可选的关次（1..场次数，最多 6 串）。 */
    val parlayOptions: List<Int> get() = Parlay.options(matchCount)

    /**
     * 实际生效的关次集合：过滤掉「关次 > 场次数」的无效项。
     *
     * 🔴 两种"空"的语义必须分开：
     *  - `parlayMs` 本身为空 = 用户**还没选**过关方式 ⇒ 返回空（不替他默认成单关）；
     *  - 选了但全被场次数过滤掉（如只剩 2 场却只勾了 3串1）⇒ 退回单关兜底，
     *    否则会突然变成 0 注、按钮灰掉，用户不知道发生了什么。
     */
    val effectiveMs: List<Int>
        get() {
            if (matchCount <= 0) return emptyList()
            if (parlayMs.isEmpty()) return emptyList()
            val ok = parlayMs.filter { it in 1..matchCount }.sorted()
            return ok.ifEmpty { listOf(1) }
        }

    /**
     * 注数（自由过关累加、复式展开）。
     *
     * 🔴 关次没选（`effectiveMs` 为空）时必须返回 0：底层 [BetCalculator.countBets]
     *    对空关次是按「单关」兜底的，直接用会算出 1 注、按钮亮着，
     *    而真正落单时 `parlayMs` 是空的 —— 用户会以为能投，编译期看不出来。
     */
    val betCount: Int
        get() = if (picks.isEmpty() || effectiveMs.isEmpty()) 0
        else BetCalculator.countBets(pickList, effectiveMs)

    /** 本金（元）= 2 × 注数 × 倍数。 */
    val stake: Double get() = BetCalculator.stakeOf(betCount, times)

    /** 过关方式展示名：`3x1,4x1,5x1` / `单关`；没选关次时提示「未选关次」。 */
    val parlayName: String
        get() = if (matchCount <= 0) "—"
        else if (effectiveMs.isEmpty()) "未选关次"
        else Parlay.legsText(effectiveMs)

    /** 是否复式（某一场选了两个以上选项）。 */
    val compound: Boolean get() = picks.values.groupBy { it.matchId }.any { it.value.size > 1 }

    /** 该场已选了几项 —— 卡片右上角显示，让「同场多选」看得见。 */
    fun pickCountOf(matchId: Long): Int = picks.values.count { it.matchId == matchId }

    fun isPicked(matchId: Long, poolCode: String, key: String): Boolean =
        picks.containsKey(betPickKey(matchId, poolCode, key))
}

class BetViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BetRepository()
    private val settleRepo = BetSettleRepository()
    private val store = BetTicketStore(app)

    private val _state = MutableStateFlow(BetUiState())
    val state: StateFlow<BetUiState> = _state

    private var loadedSports = mutableSetOf<BetSport>()

    fun ensureLoaded() {
        if (_state.value.matches.isEmpty() && !_state.value.loading) load(_state.value.sport)
    }

    fun load(sport: BetSport = _state.value.sport, force: Boolean = false) {
        if (!force && sport in loadedSports && _state.value.matches.isNotEmpty() &&
            _state.value.sport == sport
        ) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, sport = sport) }
            runCatching { repo.getMarkets(sport) }
                .onSuccess { list ->
                    loadedSports.add(sport)
                    _state.update { it.copy(loading = false, matches = list, error = null) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "加载失败", matches = emptyList())
                    }
                }
        }
    }

    /**
     * 切球类 ⇒ **清单全部清空**（用户要求）。
     *
     * 之前是足球/篮球选项混在一张单里，切来切去容易看错；
     * 现在每切一次球类就从头开始，投注单里永远只含当前球类的选项。
     * 倍数**不清**（那是用户的习惯值），过关方式清空重选（球类不同不能串）。
     */
    fun switchSport(sport: BetSport) {
        if (sport == _state.value.sport) return
        _state.update { it.copy(picks = emptyMap(), sheetMatch = null, parlayMs = emptySet()) }
        load(sport)
    }

    /**
     * 点某一格：已选 ⇒ 取消；未选 ⇒ 加入。
     *
     * 🔴 键是「选项」不是「场次」，所以**同一场可以同时选中多个玩法/多个比分**（复式）。
     */
    fun togglePick(match: BetMatch, market: BetMarket, option: BetOption) {
        if (match.ended || !option.sellable) return
        val key = betPickKey(match.matchId, option.pool.code, option.key)
        _state.update { st ->
            val next = st.picks.toMutableMap()
            if (next.containsKey(key)) {
                next.remove(key)
            } else {
                next[key] = BetPick(
                    matchId = match.matchId,
                    sport = match.sport.name,
                    num = match.num,
                    league = match.league,
                    home = match.home,
                    away = match.away,
                    date = match.date,
                    time = match.time,
                    poolCode = option.pool.code,
                    poolTitle = option.pool.title,
                    lineLabel = market.lineLabel,
                    key = option.key,
                    label = option.label,
                    odds = option.odds,
                    line = option.line
                )
            }
            st.copy(picks = next)
        }
    }

    fun removePick(pickKey: String) = _state.update { st ->
        st.copy(picks = st.picks - pickKey)
    }

    fun clearPicks() = _state.update { it.copy(picks = emptyMap(), parlayMs = emptySet()) }

    fun setTimes(t: Int) = _state.update { it.copy(times = t.coerceIn(1, BetTicket.MAX_TIMES)) }

    /**
     * 切换一个关次（**多选**）。
     *
     * 🔴 允许全部取消（回到「未选」）：用户要求「单关默认选中去掉」，那就得能真的摘干净；
     *    注数会变 0、投注按钮灰着，但关次胶囊还在原地，再点一下就恢复。
     */
    fun toggleParlay(m: Int) = _state.update { st ->
        val cap = st.matchCount.coerceAtLeast(1)
        if (m !in 1..cap) return@update st
        val next = st.parlayMs.toMutableSet()
        if (next.contains(m)) next.remove(m) else next.add(m)
        st.copy(parlayMs = next)
    }

    /** 全选全部关次（= 竞彩的 M串(2^M-1)，如 3 场全选 = 3串7）。 */
    fun selectAllParlay() = _state.update { st ->
        val opts = Parlay.options(st.matchCount)
        st.copy(parlayMs = opts.toSet())
    }

    /** 只打单关。 */
    fun onlySingle() = _state.update { it.copy(parlayMs = setOf(1)) }

    fun openSheet(m: BetMatch) = _state.update { it.copy(sheetMatch = m) }

    fun closeSheet() = _state.update { it.copy(sheetMatch = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** UI 已经把 [BetUiState.pendingCopy] 写进剪贴板，这里清掉，避免重组时重复复制。 */
    fun consumeCopy() = _state.update { it.copy(pendingCopy = null) }

    /**
     * 下单：**校验注数 → 换 titanId → 落盘**。
     *
     * ## 🔴 三个关键顺序
     * 1. **先校验注数**：注数为 0 / 超上限都在建单之前挡住；
     * 2. **titanId 必须在落盘前写好**：结算完全依赖它（详见 [BetSettleParser]），
     *    事后再补是补不回来的（比赛已下架，竞彩页也查不到）；
     * 3. **映射失败不阻断下单**：拉不到竞彩页时 `titanId = 0`，注单仍可下，
     *    只是结算时需要手动录分 —— 因为网络抖动不该让用户白选一遍。
     *
     * 本版**不扣款、不判余额**（没有账户），本金只是写在注单上的数字。
     * 篮球不需要 titanId：竞彩编号（`周三301`）本身就是结算键。
     */
    fun placeBet() {
        val st = _state.value
        if (st.placing) return
        if (st.picks.isEmpty()) {
            _state.update { it.copy(message = "请先选择至少 1 个选项") }
            return
        }
        // 过关方式不再有默认值：一个都没勾时先提示，不要拿「0 注」这种技术文案打发用户
        if (st.effectiveMs.isEmpty()) {
            _state.update { it.copy(message = "请先选择过关方式（单关 / 2串1 …）") }
            return
        }
        val count = st.betCount
        if (count <= 0) {
            _state.update { it.copy(message = "当前过关方式没有有效组合，请调整后重试") }
            return
        }
        if (count > BetTicket.MAX_BETS) {
            _state.update {
                it.copy(message = "注数 $count 超过上限 ${BetTicket.MAX_BETS}，请减少选项或关次")
            }
            return
        }
        val stake = st.stake
        val ms = st.effectiveMs

        _state.update { it.copy(placing = true) }
        viewModelScope.launch {
            // ① 换 titanId（只对足球腿有意义）
            val refs = if (st.pickList.any { it.sport == BetSport.FOOTBALL.name }) {
                settleRepo.fetchTitanRefs()
            } else emptyMap()

            val enriched = st.pickList.map { p ->
                if (p.sport != BetSport.FOOTBALL.name) {
                    p
                } else {
                    val id = refs[BetSettleParser.keyOf(p.num, p.home, p.away)] ?: 0L
                    if (id > 0) p.copy(titanId = id) else p
                }
            }
            val unresolved = enriched.count { it.sport == BetSport.FOOTBALL.name && it.titanId == 0L }

            // ② 建单（本金与关次在此锁定，之后不再重算）
            val ticket = BetTicket(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                times = st.times,
                picks = enriched,
                parlayM = ms.max(),
                parlayMs = ms,
                statusRaw = TicketStatus.PENDING,
                stakeLocked = stake,
                note = if (unresolved > 0) "$unresolved 场未匹配到赛果源，结算时需手动录分" else ""
            )

            // ③ 落盘
            store.appendTicket(ticket)

            val copyText = formatTicketForShare(ticket)
            _state.update {
                it.copy(
                    picks = emptyMap(),
                    times = 1,
                    parlayMs = emptySet(),
                    sheetMatch = null,
                    placing = false,
                    pendingCopy = copyText,
                    message = "已下单 $count 注 · ${Parlay.legsText(ms)} · ¥${fmt(stake)}" +
                        if (unresolved > 0) "（$unresolved 场待手动录分）" else ""
                )
            }
        }
    }
}
