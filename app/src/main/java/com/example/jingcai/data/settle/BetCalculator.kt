package com.example.jingcai.data.settle

import com.example.jingcai.data.model.BetPick
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.LegResult
import com.example.jingcai.data.model.TicketStatus
import kotlin.math.round

/**
 * 一场比赛的赛果（结算输入）。
 *
 * [halfHome]/[halfAway] 是**半场比分**（半全场玩法必需）；足球来自 bfdata 的
 * `A[i][16]/[17]`，篮球来自 qiumibao 的 `[5][3]`。
 *
 * [cancelled] = 取消 / 延期 / 腰斩：竞彩规则按「该场赔率 1.0」参与过关，
 * 单关则退回本金 —— 也就是 [LegResult.VOID]。
 */
data class MatchScore(
    val fullHome: Int,
    val fullAway: Int,
    val halfHome: Int = 0,
    val halfAway: Int = 0,
    /** 是否已结束（只有结束了才拿它结算）。 */
    val finished: Boolean = true,
    val cancelled: Boolean = false
)

/** 一次结算的完整结果。 */
data class Settlement(
    val status: TicketStatus,
    val payout: Double,
    /** `pickKey(matchId|pool|key)` -> 单腿结果。 */
    val legs: Map<String, LegResult>,
    /** 人类可读说明（写进注单，便于事后核对）。 */
    val note: String = ""
)

/**
 * 投注计算与结算引擎（纯函数，不碰 IO，便于离线核对）。
 *
 * ## 过关口径（与竞彩一致）
 * 设选中 `n` 场，每场选了 `c_i` 个选项：
 * - **单关**（M=1）：注数 = `Σ c_i`，每注只含一场的一个选项；
 * - **M串1**（M≥2）：注数 = `Σ_{|S|=M} Π_{i∈S} c_i`（从 n 场里任取 M 场、
 *   每场任取一个选项的所有组合）。例：3 场各选 2 个打 2串1 = `C(3,2)×2×2 = 12` 注。
 * - **自由过关**（同时选多种关次，如「3串4」= 2串1 + 3串1）：注数 = 各关次注数之**和**。
 * - 本金 = `2 元 × 注数 × 倍数`（**注数已含复式展开**，所以复式不用再乘）。
 * - 单注奖金 = `2 元 × 倍数 × Π(命中腿的下注赔率)`；**取消的腿按 1.0 计**。
 *
 * ## 🔴 为什么注数要单独用公式算、不能靠枚举
 * 复式组合是指数级：10 场各选 3 个打 5串1 就是 `C(10,5)×3^5 = 61236` 注，
 * 先枚举再取 size 会直接把内存打爆。所以 [countBets] 走乘法计数，
 * 只有真的需要逐注算奖金（≤ [BetTicket.MAX_BETS] 注，下单时已挡）才枚举。
 */
object BetCalculator {

    // ============================== 注数 / 金额 ==============================

    /** 按场次分组（保持用户点选顺序）。 */
    private fun groups(picks: List<BetPick>): List<List<BetPick>> =
        picks.groupBy { it.matchId }.values.toList()

    /** 本单涉及的场次数。 */
    fun matchCount(picks: List<BetPick>): Int = groups(picks).size

    /** 该过关方式下的最大可选关次（= 场次数）。 */
    fun maxParlay(picks: List<BetPick>): Int = matchCount(picks).coerceAtLeast(1)

    /**
     * 注数（自由过关）。
     *
     * @param ms 关次集合，如 `[1]`（单关）、`[2,3]`（3串4）。重复 / 越界的关次会被忽略，
     *   场次不够时按 [countBetsOne] 的口径收敛到「场次数」（与竞彩一致：2 场打 3串1 收 2串1）。
     */
    fun countBets(picks: List<BetPick>, ms: List<Int>): Int {
        if (picks.isEmpty()) return 0
        val maxM = matchCount(picks)
        val valid = ms.filter { it >= 1 }.distinct().sorted().ifEmpty { listOf(1) }
        var total = 0L
        for (m in valid) {
            total += countBetsOne(picks, m.coerceAtMost(maxM))
            if (total > Int.MAX_VALUE) return Int.MAX_VALUE
        }
        return total.toInt()
    }

    /** 单个关次的注数（见类注释的公式）。 */
    fun countBetsOne(picks: List<BetPick>, m: Int): Int {
        val sizes = groups(picks).map { it.size }
        if (sizes.isEmpty()) return 0
        val k = m.coerceIn(1, sizes.size)
        if (k == 1) return sizes.sum()
        var total = 0L
        for (idx in combinations(sizes.size, k)) {
            var p = 1L
            for (i in idx) p *= sizes[i]
            total += p
            if (total > Int.MAX_VALUE) return Int.MAX_VALUE
        }
        return total.toInt()
    }

    /** 本金（元）= 2 × 注数 × 倍数。 */
    fun stakeOf(count: Int, times: Int): Double =
        BetTicket.STAKE_PER_BET.toDouble() * count * times.coerceAtLeast(1)

    /**
     * 可能中奖金额区间（元，两位小数）。
     *
     * [min] = **有奖时的最低派彩**（可能只中部分场次），[max] = 理论最高派彩。
     * 两者相等时 [fixed] 为 true，调用方只显示一个「固定奖金」。
     */
    data class PayoutSpan(val min: Double, val max: Double) {
        /** 金额确定（只有一个可能值）⇒ 只显示一个数。 */
        val fixed: Boolean get() = max - min < 0.005
    }

    /**
     * 互斥组：**同一场比赛 + 同一玩法**下的多个选项互相排斥，最多只有一个能中。
     *
     * ## 🔴 这是 2026-11-06 用户报障的根因
     * 旧版 [maxPayout] 是「逐注算奖金再求和」，前提是**所有注可以同时中**。
     * 这个前提只在「每个互斥组只买了 1 个选项」时成立。一旦同场同玩法复选：
     *  - 半全场买了「胜平」+「负平」（赔率 18 / 20，15 倍 = 2 注 60 元），
     *    现实中只可能中其中一注（540 或 600），旧算法却算出 `540 + 600 = 1140`；
     *  - 总进球买「5球」+「6球」+「7球」同理，三个档只中一个。
     *
     * ## 为什么分组维度是 (matchId, poolCode) 而不是 matchId
     * 同一场比赛的**不同玩法**互不排斥：买了「胜平负 主胜」和「比分 2:1」，
     * 2:1 主胜时两条都中（分属两注时两注一起中）。只有同一盘口内的不同档才互斥。
     */
    private fun exclusiveGroups(picks: List<BetPick>): List<List<BetPick>> =
        picks.distinctBy { Triple(it.matchId, it.poolCode, it.key) }
            .groupBy { it.matchId to it.poolCode }
            .values.toList()

    /** 金额收口：保留 2 位小数（与结算一致）。 */
    private fun money(v: Double): Double = round(v * 100) / 100

    /**
     * **可能中奖金额区间**：有奖时的最低派彩 ~ 理论最高派彩。
     *
     * ## 口径
     * 1. 单注奖金 = `2 元 × 倍数 × Π(该注各腿赔率)`（与结算完全一致，取消腿按 1.0）；
     * 2. 一注命中 ⇔ 它的**每一条腿**都在「本场本玩法开出的那个档」上；
     * 3. 一个「赛果状态」= 每个互斥组各自开出一个档 **或者一个档都没开**；
     * 4. 遍历全部状态，把每个状态下命中的注奖金累加：
     *    - 最大 = 全中（每组都开一个档，且挑最赚的那个组合）
     *    - 最小 = **有奖状态里的最低值**（允许只中一部分场次）
     *
     * ## 为什么要允许「整组不中」（2026-11-06 15:05 用户第二次报障）
     * 5 场各 1 个档打 `3x1,4x1,5x1`：
     *  - 最高 = 5 场全中 ⇒ 10 注 3x1 + 5 注 4x1 + 1 注 5x1 全中，16 注之**和**
     *    （用户原话：「最高奖金是中五场，包含多个 3x1 和多个 4x1 要有一个 5x1，要计算多个结果之和」）；
     *  - 最低 = **只中 3 场**（挑赔率乘积最小的那 3 场）⇒ 只拿 1 注 3x1
     *    （用户原话：「最低应该是 3 个赔率最小的 3x1」）。
     * 只枚举「每组都中」的话最低会退化成最高值，这张票就变成单一数字，报不出这个下限。
     *
     * ## 快速路径
     * 全部注都是单腿（单关票）时各组完全独立：最高 = Σ(各组最大赔率)、
     * 最低 = 只中一组（取各组的**最小**赔率中的最小值）。O(n) 出结果 ——
     * 大复式单关（如 8 场 × 半全场 9 档）状态数是 10^8，枚举会卡死。
     *
     * @return 枚举规模超过 [MAX_PAYOUT_ENUM] 或注数超 [BetTicket.MAX_BETS] 时返回 null，
     *   调用方据此隐藏这一行 —— **绝不退化成一个瞎猜的数字**。
     */
    fun payoutSpan(ticket: BetTicket): PayoutSpan? {
        val bets = combos(ticket.picks, ticket.ms)
        if (bets.isEmpty()) return null
        val unit = BetTicket.STAKE_PER_BET.toDouble() * ticket.times.coerceAtLeast(1)
        val groups = exclusiveGroups(ticket.picks)
        if (groups.isEmpty()) return null

        // 全部是单腿注（单关）：各组独立
        //   最高 = 每组都中，各取组内最大赔率后相加
        //   最低 = 只中一组，取「组内最小赔率」里最小的那个
        if (bets.all { it.size == 1 }) {
            var hi = 0.0
            var lo = Double.MAX_VALUE
            for (g in groups) {
                hi += g.maxOf { it.odds.coerceAtLeast(0.0) }
                lo = minOf(lo, g.minOf { it.odds.coerceAtLeast(0.0) })
            }
            return PayoutSpan(money(lo * unit), money(hi * unit))
        }

        // 状态数 = Π(各组档位数 + 1)；再乘注数就是内层检查次数
        var states = 1L
        for (g in groups) {
            states *= (g.size + 1)
            if (states > MAX_PAYOUT_ENUM) return null
        }
        if (states * bets.size > MAX_PAYOUT_ENUM) return null

        // 每个 pick 一个槽位，[sel] 标记「本场本玩法开的是这一档」
        val slot = HashMap<BetPick, Int>()
        for (g in groups) for (p in g) slot.getOrPut(p) { slot.size }
        val betLegs = Array(bets.size) { i -> IntArray(bets[i].size) { j -> slot.getValue(bets[i][j]) } }
        val betAmt = DoubleArray(bets.size) { i ->
            var one = unit
            for (p in bets[i]) one *= p.odds.coerceAtLeast(0.0)
            one
        }
        val sel = BooleanArray(slot.size)
        var hi = -1.0
        var lo = Double.MAX_VALUE

        fun eval() {
            var sum = 0.0
            for (i in betLegs.indices) {
                var hit = true
                val legs = betLegs[i]
                for (j in legs) {
                    if (!sel[j]) {
                        hit = false
                        break
                    }
                }
                if (hit) sum += betAmt[i]
            }
            if (sum > hi) hi = sum
            // 0 = 一个注都没中（没奖），不能当成「最低中奖金额」
            if (sum > 0.0 && sum < lo) lo = sum
        }

        fun dfs(gi: Int) {
            if (gi == groups.size) {
                eval()
                return
            }
            // 分支 0：本场本玩法一个档都没开 —— 「最低能中多少」全靠它
            dfs(gi + 1)
            for (p in groups[gi]) {
                val s = slot.getValue(p)
                sel[s] = true
                dfs(gi + 1)
                sel[s] = false
            }
        }
        dfs(0)

        if (hi < 0) return null
        if (lo == Double.MAX_VALUE) lo = hi
        return PayoutSpan(money(lo), money(hi))
    }

    /**
     * 理论最高奖金（[payoutSpan] 的上界）。保留给只需要一个数的调用方 / 自检。
     *
     * 注数超过 [BetTicket.MAX_BETS] 或赛果组合过多时返回 null，调用方据此隐藏这一行。
     */
    fun maxPayout(ticket: BetTicket): Double? = payoutSpan(ticket)?.max

    /** 赛果枚举的内层预算（组合数 × 注数），超了就返回 null 而不是卡住主线程。 */
    private const val MAX_PAYOUT_ENUM = 8_000_000L

    /**
     * 枚举全部注（每注 = 每场一个选项），自由过关时把各关次的注**拼在一起**。
     *
     * 注数超过 [BetTicket.MAX_BETS] 时返回空列表 —— 调用方（下单校验、结算）
     * 都应先用 [countBets] 判断，正常不会走到这里。
     */
    fun combos(picks: List<BetPick>, ms: List<Int>): List<List<BetPick>> {
        if (picks.isEmpty()) return emptyList()
        val maxM = matchCount(picks)
        val valid = ms.filter { it >= 1 }.distinct().sorted().ifEmpty { listOf(1) }
        if (countBets(picks, valid) > BetTicket.MAX_BETS) return emptyList()
        val out = ArrayList<List<BetPick>>()
        for (m in valid) {
            out += combosOne(picks, m.coerceAtMost(maxM))
            if (out.size > BetTicket.MAX_BETS) return emptyList()
        }
        return out
    }

    /** 单个关次的全部注。 */
    fun combosOne(picks: List<BetPick>, m: Int): List<List<BetPick>> {
        val gs = groups(picks)
        if (gs.isEmpty()) return emptyList()
        val k = m.coerceIn(1, gs.size)
        if (k == 1) return gs.flatMap { g -> g.map { listOf(it) } }

        val out = ArrayList<List<BetPick>>()
        for (idx in combinations(gs.size, k)) {
            var acc: List<List<BetPick>> = listOf(emptyList())
            for (i in idx) {
                acc = acc.flatMap { prefix -> gs[i].map { prefix + it } }
            }
            out += acc
            if (out.size > BetTicket.MAX_BETS) return emptyList()
        }
        return out
    }

    /** 从 `0 until n` 里取 k 个的全部组合（字典序）。 */
    private fun combinations(n: Int, k: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        val cur = IntArray(k)
        fun dfs(start: Int, depth: Int) {
            if (depth == k) {
                out += cur.toList()
                return
            }
            for (i in start..n - (k - depth)) {
                cur[depth] = i
                dfs(i + 1, depth + 1)
            }
        }
        if (k in 0..n) dfs(0, 0)
        return out
    }

    // ============================== 单腿判定 ==============================

    /**
     * 判定一个选项的输赢。
     *
     * 🔴 **认 `pick.key`，绝不认 `label`**：label 会带让球线、带冒号、带「其他」措辞，
     * 文案一改就静默判错（这是本项目最容易埋的雷，参 [BetPick] 的注释）。
     */
    fun judge(p: BetPick, s: MatchScore): LegResult {
        if (s.cancelled) return LegResult.VOID
        if (!s.finished) return LegResult.PENDING
        val line = p.line.toDoubleOrNull() ?: 0.0
        return when (p.poolCode) {
            // 胜平负：直接比全场比分
            "had" -> winIf(sign(s.fullHome.toDouble(), s.fullAway.toDouble(), p.key))

            // 让球胜平负：主队得分 + 让球线 后再比
            "hhad" -> winIf(sign(s.fullHome + line, s.fullAway.toDouble(), p.key))

            // 总进球：0..6 对应 s0..s6，7 球及以上 = s7
            "ttg" -> winIf(totalKey(s.fullHome + s.fullAway) == p.key)

            // 半全场：半场结果 + 全场结果拼成 hh/hd/ha/dh...
            "hafu" -> winIf(hafuKey(s) == p.key)

            // 比分：精确比分或三个「其他」
            "crs" -> winIf(crsMatch(p.key, s))

            // 篮球胜负：无平局（含加时），主>客为 h
            "mnl" -> winIf((if (s.fullHome > s.fullAway) "h" else "a") == p.key)

            // 篮球让分：让分后打平 = 走盘，退本金
            "hdc" -> {
                val d = s.fullHome + line - s.fullAway
                when {
                    d > 0 -> winIf(p.key == "h")
                    d < 0 -> winIf(p.key == "a")
                    else -> LegResult.VOID
                }
            }

            // 大小分：总分与盘口线比较，正好相等 = 走盘
            "hilo" -> {
                val d = (s.fullHome + s.fullAway) - line
                when {
                    d > 0 -> winIf(p.key == "h")
                    d < 0 -> winIf(p.key == "l")
                    else -> LegResult.VOID
                }
            }

            // 未知玩法：保持待开奖，绝不瞎判
            else -> LegResult.PENDING
        }
    }

    private fun winIf(ok: Boolean) = if (ok) LegResult.WIN else LegResult.LOSE

    /** 三元结果：1 主胜 / 0 平 / -1 客胜，按 `h` / `d` / `a` 返回。 */
    private fun sign(h: Double, a: Double, key: String): Boolean {
        val actual = when {
            h > a -> "h"
            h < a -> "a"
            else -> "d"
        }
        return actual == key
    }

    private fun totalKey(total: Int): String = if (total >= 7) "s7" else "s$total"

    private fun hafuKey(s: MatchScore): String {
        fun f(h: Int, a: Int) = when {
            h > a -> "h"
            h < a -> "a"
            else -> "d"
        }
        return f(s.halfHome, s.halfAway) + f(s.fullHome, s.fullAway)
    }

    /**
     * 体彩「比分」玩法实际开售的 29 个标准比分（主胜 12 / 平 5 / 客胜 12）。
     *
     * 三个「其他」（`s1sh` 胜其他 / `s1sd` 平其他 / `s1sa` 负其他）只有在
     * **方向对且实际比分不在本表内**时才命中 —— 少了这一步，1:0 会同时算中
     * 「1:0」和「胜其他」两个选项。
     */
    private val STD_CRS: Set<String> = buildSet {
        addAll(listOf("0:0", "1:1", "2:2", "3:3", "4:4"))
        for (a in 0..2) for (h in 1..5) if (h > a) add("$h:$a")
        for (h in 0..2) for (a in 1..5) if (a > h) add("$h:$a")
    }

    private val CRS_RE = Regex("^s(\\d{2})s(\\d{2})$")

    private fun crsMatch(key: String, s: MatchScore): Boolean {
        val actual = "${s.fullHome}:${s.fullAway}"
        return when (key) {
            "s1sh" -> s.fullHome > s.fullAway && actual !in STD_CRS
            "s1sd" -> s.fullHome == s.fullAway && actual !in STD_CRS
            "s1sa" -> s.fullHome < s.fullAway && actual !in STD_CRS
            else -> {
                val m = CRS_RE.find(key) ?: return false
                m.groupValues[1].toInt() == s.fullHome && m.groupValues[2].toInt() == s.fullAway
            }
        }
    }

    // ============================== 整单结算 ==============================

    /** 一个选项的稳定键，与投注单的 pickKey 同构：`matchId|玩法|选项键`。 */
    fun legKey(p: BetPick): String = "${p.matchId}|${p.poolCode}|${p.key}"

    /**
     * 整单结算。
     *
     * @param scores `matchId -> 赛果`。**缺失的场次会被当成「还没出结果」**（而不是
     *   当成 0:0），否则一次取数失败就会把整单判死。
     * @return 结算结果；只要还有任何一条腿待开奖，[Settlement.status] 就是 PENDING 且不派彩。
     */
    fun settle(ticket: BetTicket, scores: Map<Long, MatchScore>): Settlement {
        val legs = LinkedHashMap<String, LegResult>()
        for (p in ticket.picks) {
            val s = scores[p.matchId]
            legs[legKey(p)] = if (s == null) LegResult.PENDING else judge(p, s)
        }

        // 还有腿没出结果 ⇒ 通常不下任何结论（宁可等，也不能把待定的单判成未中奖）
        //
        // 🔴 串关例外（用户新增要求）：只要已经有一条腿判负，且**每一个关次的每一注都含这条腿**
        //    （[isDead]），整单就已经没有任何中奖可能了 —— 竞彩口径本来就是「一场没中，整单不中」。
        //    这时不该让用户对着「待开奖」干等剩下几场结束，直接提前落「未中奖」。
        //    判定必须是「所有注都死」而不是「有腿输」：3串4（2串1+3串1）挂一场时 2串1 还有救，
        //    过早盖棺会把还能中的单判死，这是不可逆的（`settledAt` 一写，[BetSettleEngine] 就不再碰它）。
        if (legs.values.any { it == LegResult.PENDING }) {
            if (isDead(ticket, legs)) {
                return Settlement(
                    TicketStatus.LOST, 0.0, legs,
                    "提前结算 · 已有场次未中，过关无中奖可能"
                )
            }
            return Settlement(TicketStatus.PENDING, 0.0, legs, "部分场次尚未出结果")
        }

        val t = ticket.times.coerceAtLeast(1)
        val bets = combos(ticket.picks, ticket.ms)
        if (bets.isEmpty()) {
            // 注数异常（超上限 / 无选项）：只把腿结果带回去，不给结论
            return Settlement(TicketStatus.PENDING, 0.0, legs, "组合数超出上限，无法结算")
        }

        var payout = 0.0
        var wonBets = 0
        var voidBets = 0

        for (bet in bets) {
            var prod = 1.0
            var lost = false
            for (p in bet) {
                when (legs[legKey(p)]) {
                    LegResult.LOSE -> {
                        lost = true
                    }
                    LegResult.WIN -> prod *= p.odds.coerceAtLeast(0.0)
                    // 取消的腿按 1.0 参与，不影响乘积
                    else -> Unit
                }
                if (lost) break
            }
            if (lost) continue

            // 全部腿都被取消 ⇒ 该注退款（本金原样退回），不算中奖
            val allVoid = bet.all { legs[legKey(it)] == LegResult.VOID }
            if (allVoid) {
                voidBets++
                payout += BetTicket.STAKE_PER_BET.toDouble() * t
            } else {
                wonBets++
                payout += BetTicket.STAKE_PER_BET * t * prod
            }
        }

        val status = when {
            wonBets > 0 -> TicketStatus.WON
            voidBets > 0 && voidBets == bets.size -> TicketStatus.VOID
            else -> TicketStatus.LOST
        }
        val note = buildString {
            append("${bets.size} 注")
            if (wonBets > 0) append(" · 中 $wonBets")
            if (voidBets > 0) append(" · 取消 $voidBets")
        }
        return Settlement(status, round2(payout), legs, note)
    }

    /**
     * 只有一半数据时的「可能已中」判定：用于赔率提醒、进度展示。
     * 只要还有腿能判 LOSE，整单就已死，不必等剩下的场次。
     */
    fun isDead(ticket: BetTicket, legs: Map<String, LegResult>): Boolean {
        val bets = combos(ticket.picks, ticket.ms)
        if (bets.isEmpty()) return false
        return bets.none { bet -> bet.none { legs[legKey(it)] == LegResult.LOSE } }
    }

    private fun round2(x: Double): Double = round(x * 100) / 100
}
