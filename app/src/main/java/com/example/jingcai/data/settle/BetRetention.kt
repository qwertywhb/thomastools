package com.example.jingcai.data.settle

import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.TicketStatus

/**
 * 注单保留策略：**已开奖只留 24 小时，未开奖的一直留**。
 *
 * 用户 2026-11-10 口径：
 * 「模拟投注只保留未开奖的订单，已开奖的订单只保留 24 小时，然后自动删除，
 *  投入的金额和开奖数据也一并删除，就是说没有累计赢亏和命中率类似投入累计回报注单和统计这些」。
 *
 * ## 为什么单独一个文件
 * 这是**纯逻辑、不碰 Android API**：既让 [com.example.jingcai.data.repository.BetTicketStore]
 * 在每次读取的出口统一过筛，也让 debug 自检能直接构造注单断言（不用起 Context）。
 * 删数据这件事必须有断言兜着 —— 判错的代价是「用户的中奖记录凭空消失」。
 *
 * ## 🔴 判「已开奖」用 status，不用 settledAt
 * 老存档里存在 `status=WON/LOST` 但 `settledAt=0` 的行（第三版只写了 payout）。
 * 只看 settledAt 的话这批行永远不满足「满 24 小时」，会成为永不消失的僵尸单；
 * 所以这类行退回按 `createdAt` 起算。
 *
 * ## 🔴 边界是 `> cutoff`（严格大于）
 * 满 24 小时整点就删。`settledAt == cutoff` 属于「已满 24 小时」，必须删掉，
 * 否则会出现「刚好差 1 毫秒留下来」的抖动。
 */
object BetRetention {

    /** 已开奖注单的保留时长：24 小时。 */
    const val KEEP_MS = 24L * 60 * 60 * 1000

    /**
     * 筛掉过期注单（不修改入参、不写盘）。
     *
     * @param now 当前时间，可注入以便自检；生产调用不传。
     */
    fun keep(list: List<BetTicket>, now: Long = System.currentTimeMillis()): List<BetTicket> {
        if (list.isEmpty()) return list
        val cutoff = now - KEEP_MS
        return list.filter { t ->
            if (t.status == TicketStatus.PENDING) {
                true
            } else {
                val at = if (t.settledAt > 0) t.settledAt else t.createdAt
                at > cutoff
            }
        }
    }
}
