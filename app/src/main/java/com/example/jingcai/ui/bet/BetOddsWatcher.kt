package com.example.jingcai.ui.bet

import com.example.jingcai.data.model.BetSport
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.betPickKey
import com.example.jingcai.data.repository.BetTicketStore
import com.example.jingcai.data.repository.BetRepository

/**
 * 赔率下降监控。
 *
 * ## 为什么值得做
 * 下注之后赔率往下走，说明**市场朝你这边压钱**（有人跟注），这是真实的盘口信号；
 * 反过来赔率上升往往意味着有不利消息。模拟投注里把它提示出来，用户能学到「赔率是会动的」。
 *
 * ## 口径
 * - 只监控**还没开赛**的腿（停售/开赛后赔率不再变化，也没意义）；
 * - 跌幅 = `(下注赔率 - 当前赔率) / 下注赔率`，超过 [THRESHOLD] 才提醒；
 * - 每条腿**只提醒一次**（去重键 `ticketId|pickKey` 存进 [BetTicketStore]），
 *   否则用户每次进投注页都会收到同一条。
 *
 * ## 🔴 为什么不用「按需查询」接口
 * 体彩没有「按 matchId 查赔率」的公开接口，只能整表拉 calculator。
 * 所以一次请求覆盖所有在售比赛，比逐场查更省流量，也不容易被 WAF 盯上。
 */
class BetOddsWatcher(
    private val store: BetTicketStore,
    private val repo: BetRepository = BetRepository()
) {
    /** 跌幅阈值：8%。再小就是正常波动，提醒会变成噪音。 */
    private val threshold = 0.08

    /**
     * 跑一轮检查。返回本次新发现的降赔项（已发通知）。
     */
    suspend fun check(): List<Triple<String, Double, Double>> {
        val tickets = store.loadTickets().filter { !it.settled }
        if (tickets.isEmpty()) return emptyList()

        val reminded = store.remindedKeys()
        // 只关心「还没提醒过」的腿，全部提醒过了就一次请求都不用发
        val watch = tickets.flatMap { t ->
            t.picks.mapNotNull { p ->
                val k = "${t.id}|${betPickKey(p.matchId, p.poolCode, p.key)}"
                if (k in reminded) null else Watch(t.id, k, p)
            }
        }
        if (watch.isEmpty()) return emptyList()

        // 按球类分组拉一次全表赔率
        val priceIndex = HashMap<String, Double>()
        for (sport in listOf(BetSport.FOOTBALL, BetSport.BASKETBALL)) {
            val ids = watch.filter { it.pick.sport == sport.name }.map { it.pick.matchId }.toSet()
            if (ids.isEmpty()) continue
            val markets = runCatching { repo.getMarkets(sport) }.getOrDefault(emptyList())
            for (m in markets) {
                if (m.matchId !in ids) continue
                for (mk in m.inline + m.expand) {
                    for (o in mk.options) {
                        if (o.odds > 0) {
                            priceIndex[betPickKey(m.matchId, mk.pool.code, o.key)] = o.odds
                        }
                    }
                }
            }
        }
        if (priceIndex.isEmpty()) return emptyList()

        val hits = ArrayList<Triple<String, Double, Double>>()
        val mark = ArrayList<String>()
        for (w in watch) {
            val now = priceIndex[betPickKey(w.pick.matchId, w.pick.poolCode, w.pick.key)] ?: continue
            val before = w.pick.odds
            if (before <= 0) continue
            // 已经停售/开赛的场次赔率可能被接口置 0，这里 now<=0 已被上面的 >0 挡掉
            val drop = (before - now) / before
            if (drop >= threshold) {
                hits += Triple(
                    // 与注单/通知同一口径：线在玩法名里（`让分 -9.5`），选项不带线
                    "${w.pick.home} ${poolTitleOf(w.pick)} ${w.pick.pickText}",
                    before,
                    now
                )
                mark += w.key
            }
        }
        // 无论有没有命中都记下来：命中过的不再重复提醒；未命中的下次还要继续看，
        // 所以**只标记命中的**（没命中的不写，避免「提醒过一次就再也不看了」）。
        store.markReminded(mark)
        if (hits.isNotEmpty()) BetNotify.notifyOddsDown(hits)
        return hits
    }

    private data class Watch(val ticketId: String, val key: String, val pick: com.example.jingcai.data.model.BetPick)
}
