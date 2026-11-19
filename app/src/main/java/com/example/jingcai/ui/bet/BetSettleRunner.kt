package com.example.jingcai.ui.bet

import android.content.Context
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.TicketStatus
import com.example.jingcai.data.repository.AppContextHolder
import com.example.jingcai.data.repository.BetTicketStore
import com.example.jingcai.data.settle.BetSettleEngine
import com.example.jingcai.data.settle.SettleOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 结算 / 降赔检查的统一入口（App 回到前台、进投注页、进账本页时各触发一次）。
 *
 * ## 为什么要有这层壳
 * 1. **防并发**：三个入口可能同时触发，结算必须串行 —— 两轮并发结算会各自读到
 *    「未结算」并各自派彩一次，统计被重复累加（这是整条链路最危险的竞态）。
 *    用 [mutex] 加锁即可；不用 `isRunning` 布尔，因为协程取消后布尔会卡住。
 * 2. **统一通知口径**：只在**真的结算出结果**时发通知，避免每次进页面都弹一次。
 *
 * ## 不做后台常驻
 * 模拟投注不是实时业务，回到前台看一次足够。强行后台轮询只会耗电，而且
 * 本项目已经有进球提醒的常驻前台服务，再加一条链路会显著增加复杂度与出错面。
 */
object BetSettleRunner {

    private val mutex = Mutex()

    /** 最近一次结果的缓存（账本页显示「刚刚更新」）。 */
    @Volatile
    var lastOutcome: SettleOutcome? = null
        private set

    /**
     * 待展示的「中奖啦」弹窗事件（`null` = 没有）。
     *
     * ## 为什么用全局 Flow 而不是让某个页面自己弹
     * 结算可能发生在任何页面（App 回前台、进投注页、进账本页都会跑一轮），
     * 中奖弹窗属于**全 App 级**的反馈，挂在页面里就漏了另外两条路径。
     * 由 [com.example.jingcai.MainActivity] 的 AppNav 统一收、统一弹。
     *
     * 🔴 消费方**必须**在处理后把它置回 null，否则重组会反复弹。
     */
    val winEvent = MutableStateFlow<WinEvent?>(null)

    /**
     * 一次中奖弹窗的数据。
     *
     * @param tickets 本次新结算出来的中奖注单
     * @param amount  合计派彩（逐单 `payout` 之和，与通知金额同源）
     */
    data class WinEvent(val tickets: List<BetTicket>, val amount: Double)

    /**
     * 跑一轮「结算 + 降赔」。`notify` = false 时不发通知（页面内自己展示）。
     *
     * @param reason 触发来源，只进日志。四个入口共用本方法，光看 `run ok` 一行分不清
     *   是心跳在跑还是用户又点了一次账单页 —— 排查「常驻前台不结算」时必须能区分。
     */
    suspend fun run(
        notify: Boolean = true,
        ctx: Context = AppContextHolder.ctx,
        reason: String = "?"
    ): SettleOutcome =
        mutex.withLock {
            val store = BetTicketStore(ctx)
            val engine = BetSettleEngine(store)
            // 先做一次性「篮球主客对齐」翻案：旧口径已经把错单写死成「未中奖」，
            // settledAt 一写 run() 就不再碰它，不先重开就永远错下去。
            runCatching { engine.repairBasketSideOnce() }
            val outcome = runCatching { engine.run() }
                .getOrDefault(SettleOutcome())
            lastOutcome = outcome
            // 前台心跳（MainActivity 每 3 分钟一轮）没有界面反馈，只有这条日志能证明它真的在跑。
            // 排查「一直开着 App 却不结算」时先看这里：没有任何一行 = 心跳没起来。
            // 不套 BuildConfig.DEBUG：本项目没开 buildConfig 特性，且这条日志在用户设备上也值得留着
            // —— 每 3 分钟一行，量可以忽略。
            android.util.Log.i(
                "BetSettle",
                "run ok [$reason]: settled=${outcome.settled.size} " +
                    "partial=${outcome.partiallyUpdated} unresolved=${outcome.unresolved}"
            )

            if (outcome.settled.isNotEmpty() && notify) {
                BetNotify.notifyPayout(outcome.settled, outcome.payout)
                // 🔴 只有「中奖」才弹 App 内弹窗。未中奖用红金全屏动效去庆祝一张输单，
                //    比不弹更糟（与通知「只有中奖才用红底」同一口径）。
                val won = outcome.settled.filter { it.status == TicketStatus.WON }
                if (won.isNotEmpty()) {
                    winEvent.value = WinEvent(won, won.sumOf { it.payout })
                }
            }
            // 降赔检查独立跑：它失败不影响结算结果
            //
            // 🔴 必须节流：只要有「还没提醒过的腿」，它每次都整表拉一遍 calculator
            //    （足球 + 篮球各一次）。前台心跳每 3 分钟调一次 run()，不节流就是
            //    「每 3 分钟拉一次全表赔率」，比结算本身贵得多。
            //    赔率变化不是秒级需求，10 分钟一次完全够（跌幅阈值本来也是 8% 级别）。
            val now = System.currentTimeMillis()
            if (now - lastOddsCheckAt >= ODDS_CHECK_GAP_MS) {
                lastOddsCheckAt = now
                runCatching { BetOddsWatcher(store).check() }
            }
            outcome
        }

    /** 上次实际跑降赔检查的时刻（进程内，不落盘 —— 重启后立刻检查一次是对的）。 */
    @Volatile
    private var lastOddsCheckAt = 0L

    /** 降赔检查的最小间隔，见 [run] 里的说明。 */
    private const val ODDS_CHECK_GAP_MS = 10 * 60 * 1000L
}
