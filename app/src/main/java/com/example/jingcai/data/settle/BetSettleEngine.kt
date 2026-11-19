package com.example.jingcai.data.settle

import com.example.jingcai.data.model.BetPick
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.LegResult
import com.example.jingcai.data.model.TicketStatus
import com.example.jingcai.data.repository.BetTicketStore
import com.example.jingcai.data.repository.BetSettleRepository

/** 一次自动结算的结果（给 UI / 通知用）。 */
data class SettleOutcome(
    /** 本次刚结算完的注单。 */
    val settled: List<BetTicket> = emptyList(),
    /** 本次派彩总额（元）。 */
    val payout: Double = 0.0,
    /** 有腿刚出结果但仍然有待开奖的注单（只更新腿结果，不改状态）。 */
    val partiallyUpdated: Int = 0,
    /** 数据源没取到，无法结算的注单数。 */
    val unresolved: Int = 0
) {
    val changed: Boolean get() = settled.isNotEmpty() || partiallyUpdated > 0
}

/**
 * 自动结算引擎：**扫描未结算注单 → 取赛果 → 判定 → 回写**。
 *
 * ## 取数口径（这是整条链路最脆的一环）
 * - 足球：`pick.titanId` 是**下单时**通过竞彩页换出来的 titan007 id，按 id 精确取 bfdata 的比分，
 *   不做任何队名匹配（详见 [com.example.jingcai.data.network.BetSettleParser] 的类注释）；
 * - 篮球：竞彩编号本身就是 `周三301`，直接当键。
 *
 * ## 🔴 三条绝不能妥协的规则
 * 1. **取不到赛果 ≠ 0:0**：缺失的场次保持 [LegResult.PENDING]，整单停在「待开奖」。
 *    用 0:0 兜底会把中奖单直接判死，这是最难排查也最伤用户的错。
 * 2. **只结算一次**：已结算的注单（`settledAt > 0`）直接跳过，否则派彩金额会被重复累加进统计。
 * 3. **派彩只在写回时落一次**：`payout` 是注单自身的字段，跟状态同一次写盘，
 *    不存在「钱加了但单还是待开奖」的中间态（本 App 没有账户余额）。
 *
 * ## 结算时机
 * 由 [com.example.jingcai.ui.bet.BetSettleRunner] 在 App 回到前台时与投注页进入时触发，
 * 不做后台常驻轮询 —— 模拟投注不是实时业务，回到前台看一次足够，也省电。
 */
class BetSettleEngine(
    private val store: BetTicketStore,
    private val repo: BetSettleRepository = BetSettleRepository()
) {

    /**
     * 跑一轮结算。
     *
     * @param force 为 true 时连**没有新赛果**的注单也重算一遍腿结果（「重新结算」按钮用）。
     */
    suspend fun run(force: Boolean = false): SettleOutcome {
        val tickets = store.loadTickets()
        val pending = tickets.filter { !it.settled }
        if (pending.isEmpty()) return SettleOutcome()

        // 🔴 预筛：所有待开奖的腿都还没到「可能完赛」的时刻 ⇒ 一定结不出来，**一个请求都别发**。
        //    这条是为前台心跳准备的（见 MainActivity 的 3 分钟结算轮询）：
        //    没有它，App 每 3 分钟就要白拉一次篮球全量 360KB + 足球 98KB。
        //    加上它之后，绝大多数轮次是纯本地计算、零流量，只有真到点了才会请求。
        if (!force && !anyLegMayBeOver(pending, System.currentTimeMillis())) return SettleOutcome()

        // 只要有足球腿就要 bfdata；篮球腿要篮球源。按需拉，避免无谓请求。
        val needSoccer = pending.any { t -> t.picks.any { it.isSoccer() } }
        val needBasket = pending.any { t -> t.picks.any { !it.isSoccer() } }

        val soccer = if (needSoccer) repo.fetchSoccerScores() else emptyMap()
        val basket = if (needBasket) repo.basketScores(repo.fetchBasketMatches()) else emptyMap()

        val settledNow = ArrayList<BetTicket>()
        var totalPayout = 0.0
        var partial = 0
        var unresolved = 0

        for (t in pending) {
            // ① 先用**注单里已经判过**的腿做一次「提前结算」判定。
            //
            // 🔴 为什么不能只靠下面按比分重算：旧版本在「一条腿判负、另一条腿还没结果」时
            //    只写腿结果、状态仍停在「待开奖」（那时还没有提前结算这回事）。
            //    这些存量单如果今天接口再也取不到赛果（老比赛早已滚出数据窗口），
            //    就会永远卡在「已经有一腿未中，却还显示待开奖」，而且永远不会自己好。
            //    这一步完全不依赖接口 —— 有负腿、且每一注都含它，就能直接盖棺。
            val early = earlyLostByLegs(t)
            if (early != null) {
                store.updateTicket(t.id) { early }
                totalPayout += early.payout
                settledNow += early
                continue
            }

            val scores = HashMap<Long, MatchScore>(t.picks.size)
            // 半全场必须有半场比分才敢判，逐 matchId 记一下（见 [SnapshotFallback.parse]）。
            val needHalfIds = t.picks.filter { it.poolCode == "hafu" }.map { it.matchId }.toSet()
            val now = System.currentTimeMillis()
            var usedFallback = false
            for (p in t.picks) {
                // 🔴 篮球按**队名**对齐后再用（见 [BasketScore.alignedTo]）。
                //    解析层的「客 @ 主」错序已于 2026-11-16 修正，此处正常应恒等；
                //    保留对齐是为了数据源再改顺序时仍能自愈（用户报障过「让分开奖错了」）。
                val live = if (p.isSoccer()) soccer[p.titanId]
                           else basket[p.num]?.alignedTo(p.home, p.away)
                if (live != null) {
                    scores[p.matchId] = live
                    continue
                }
                // 源站已把这场滚出列表（老比赛）：用注单里当时抓到的比分兜底。
                val fb = snapshotFallback(t, p, now, p.matchId in needHalfIds)
                if (fb != null) {
                    scores[p.matchId] = fb
                    usedFallback = true
                }
            }

            // 一条腿都取不到 ⇒ 整单原样留着，计入 unresolved（「暂无赛果，稍后自动重试」）
            if (scores.isEmpty()) {
                unresolved++
                continue
            }

            val r = BetCalculator.settle(t, scores)
            val scoreSnapshot = snapshot(t, scores)

            if (r.status == TicketStatus.PENDING) {
                // 有部分腿出了结果：先把腿结果写回去（账本细节能显示哪些已中/已挂），状态不动
                val merged = t.picks.map { p ->
                    val leg = r.legs[BetCalculator.legKey(p)] ?: LegResult.PENDING
                    if (leg != p.legSafe) p.copy(legResult = leg) else p
                }
                val changed = merged.indices.any { merged[it] != t.picks[it] }
                if (changed || force) {
                    store.updateTicket(t.id) {
                        it.copy(picks = merged, scores = mergeScores(it.scores, scoreSnapshot))
                    }
                    partial++
                }
                continue
            }

            // —— 结算完成：状态、腿结果、派彩一次写盘 ——
            val stamped = t.copy(
                picks = t.picks.map { p ->
                    p.copy(legResult = r.legs[BetCalculator.legKey(p)] ?: LegResult.PENDING)
                },
                statusRaw = r.status,
                payout = r.payout,
                settledAt = System.currentTimeMillis(),
                scores = mergeScores(t.scores, scoreSnapshot),
                // 用了兜底就必须写清楚，否则用户会以为「比赛还没打完系统就派彩了」
                note = if (usedFallback) {
                    "自动结算 · 该场次已滚出数据源，按订单内最后一次抓到的比分".let {
                        if (r.note.isBlank()) it else "$it · ${r.note}"
                    }
                } else r.note
            )
            store.updateTicket(t.id) { stamped }
            totalPayout += r.payout
            settledNow += stamped
        }

        return SettleOutcome(settledNow, totalPayout, partial, unresolved)
    }

    /**
     * 一次性修复：把**已结算且含篮球腿**的注单退回「待开奖」，让新口径重算一遍。
     *
     * ## 为什么必须做
     * 旧版本拿球米宝的「主:客」直接当体彩的「主:客」，而两家对 NBA 的主客顺序是**相反**的
     * （见 [BasketScore] 的对照表）。错单已经被写成 `statusRaw=LOST` + `settledAt>0`，
     * 而 [run] 对 `settledAt > 0` 的注单一律跳过 —— 只改判定逻辑不重开，用户那张
     * 「76 人赢了却判未中奖」的单会**永远错下去**，修了等于没修。
     *
     * ## 口径
     * - 只动**含篮球腿**的单：足球走 titanId 精确取数，不存在主客错位，重算纯属浪费；
     * - 重开时把 `payout / settledAt / note / scores / legResult` 全部清空 ——
     *   旧 `scores` 是按错口径存的（比分挂在错误的主队上），留着会让界面继续显示反的比分；
     * - 用 [BetTicketStore.basketSideRepaired] 保证只跑一次，避免每次启动重算 + 重复派彩通知。
     *
     * @return 被重开的注单数（0 = 不需要 / 已经做过）。
     */
    fun repairBasketSideOnce(): Int {
        if (store.basketSideRepaired) return 0
        val tickets = store.loadTickets()
        var n = 0
        for (t in tickets) {
            val hasBasket = t.picks.any { !it.isSoccer() }
            if (!hasBasket) continue
            // 🔴 未结算的单也要清：它的 `scores` / `legResult` 同样是按错口径算的
            //    （比分挂在数据源的主队上 ⇒ 界面「尼克斯 VS 篮网」旁边显示 90:103，
            //    而体彩口径是 103:90）。不清就会「状态修好了、比分还是反的」。
            //    状态不动，随后的 run() 会按新口径重算。
            if (!t.settled) {
                if (!t.scores.isNullOrEmpty() || t.picks.any { it.legResult != null }) {
                    store.updateTicket(t.id) {
                        it.copy(scores = null, picks = it.picks.map { p -> p.copy(legResult = null) })
                    }
                }
                continue
            }
            // 🔴 手动录分的单不重开：比分是用户照着卡片队名亲手输的，主客本来就对
            //    （错的只是自动取数那条链路）。重开反而会把用户已经确认的结论打回待开奖，
            //    而自动数据源未必还能取到那场比赛的赛果 ⇒ 变成永远卡住。
            //    `orEmpty()` 不能省：旧存档经 Gson Unsafe 反序列化后 note 可能是 null。
            if (t.note.orEmpty().startsWith("手动录分")) continue
            store.updateTicket(t.id) {
                it.copy(
                    picks = it.picks.map { p -> p.copy(legResult = null) },
                    statusRaw = TicketStatus.PENDING,
                    payout = 0.0,
                    settledAt = 0L,
                    note = "",
                    scores = null
                )
            }
            n++
        }
        store.basketSideRepaired = true
        return n
    }

    /** 手动录分结算（数据源拿不到时的兜底）。 */
    fun settleManually(ticket: BetTicket, scores: Map<Long, MatchScore>): Pair<BetTicket?, Double> {
        val r = BetCalculator.settle(ticket, scores)
        if (r.status == TicketStatus.PENDING) return null to 0.0
        val stamped = ticket.copy(
            picks = ticket.picks.map { p ->
                p.copy(legResult = r.legs[BetCalculator.legKey(p)] ?: LegResult.PENDING)
            },
            statusRaw = r.status,
            payout = r.payout,
            settledAt = System.currentTimeMillis(),
            scores = mergeScores(ticket.scores, snapshot(ticket, scores)),
            note = "手动录分 · " + r.note
        )
        store.updateTicket(ticket.id) { stamped }
        return stamped to r.payout
    }

    // ============================== 工具 ==============================

    /**
     * 只用注单里**已经落盘**的腿结果判断整单必死（不依赖接口）。
     *
     * 条件与 [BetCalculator.settle] 里的提前结算完全同一套：至少一条腿 LOSE，且
     * **每一个关次的每一注都含一条负腿**（[BetCalculator.isDead]）。
     * 单关 / 自由过关只要还有"不含负腿"的注能中，这里就不返回 —— 宁可继续等。
     */
    private fun earlyLostByLegs(t: BetTicket): BetTicket? {
        if (t.picks.none { it.legSafe == LegResult.LOSE }) return null
        val legs = t.picks.associate { BetCalculator.legKey(it) to it.legSafe }
        if (!BetCalculator.isDead(t, legs)) return null
        return t.copy(
            statusRaw = TicketStatus.LOST,
            payout = 0.0,
            settledAt = System.currentTimeMillis(),
            note = "提前结算 · 已有场次未中，过关无中奖可能"
        )
    }

    /**
     * 是否存在「可能已经完赛」的待开奖腿 —— 前台心跳的流量闸门。
     *
     * 判据取「开赛已过 [SnapshotFallback.SUSPECT_OVER_MS]（2 小时）」而不是精确完赛时间：结算毕竟要请求网络
     * （篮球全量 360KB + 足球 98KB），只要有一丝可能结了就要去查，但**没到点一次都别查**。
     * 取 2 小时而不是篮球的 2.5 小时，是因为用户明确要求「比赛一结束就弹中奖」：
     * 足球 1h50m 完、篮球 2h 出头完，2 小时后开始拉，第一次就基本能拿到完场状态。
     *
     * ⚠️ 时间解析失败时返回 true（别省这次请求）：宁可多拉一次，也不能因为存档里
     *    日期格式异常而让这张单永远不结算。
     */
    private fun anyLegMayBeOver(tickets: List<BetTicket>, now: Long): Boolean {
        for (t in tickets) {
            for (p in t.picks) {
                if (p.legSafe != LegResult.PENDING) continue
                val kick = SnapshotFallback.kickoffMillis(p.date, p.time) ?: return true
                if (now - kick >= SnapshotFallback.SUSPECT_OVER_MS) return true
            }
        }
        return false
    }

    private fun BetPick.isSoccer(): Boolean = sport == "FOOTBALL"

    /**
     * 源站已把该场次滚出列表时，用**注单里存的比分快照**兜底。
     *
     * 判据（5 小时窗口、半全场必须有半场、无比分不兜底）全部在 [SnapshotFallback] 里，
     * 那边是纯函数、可跑自检；这里只负责「取注单里的快照 + 取开赛时间 + 调判据」。
     */
    private fun snapshotFallback(
        t: BetTicket,
        p: BetPick,
        now: Long,
        needHalf: Boolean
    ): MatchScore? = SnapshotFallback.usable(
        raw = t.scores?.get(p.matchId.toString()),
        kickoff = SnapshotFallback.kickoffMillis(p.date, p.time),
        now = now,
        needHalf = needHalf
    )

    /**
     * 赛果快照：`matchId -> "全场主:客|半场主:客"`，便于离线还原注单。
     *
     * 🔴 **半场段只在该场含「半全场」玩法时才写**：否则手动录分没填半场会留下
     *    `"2:1|0:0"` 这种假半场，展示出来等于骗用户「半场真是 0:0」。
     *    没有半全场玩法时半场对判定毫无影响，直接省掉该段（`"2:1"`）。
     */
    private fun snapshot(t: BetTicket, scores: Map<Long, MatchScore>): Map<String, String> {
        val needHalf = t.picks.filter { it.poolCode == "hafu" }.map { it.matchId }.toSet()
        return t.picks.map { it.matchId }.distinct().mapNotNull { id ->
            scores[id]?.let { s ->
                val full = "${s.fullHome}:${s.fullAway}"
                id.toString() to if (id in needHalf) "$full|${s.halfHome}:${s.halfAway}" else full
            }
        }.toMap()
    }

    private fun mergeScores(old: Map<String, String>?, add: Map<String, String>): Map<String, String> =
        (old ?: emptyMap()) + add
}
