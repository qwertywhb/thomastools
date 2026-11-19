package com.example.jingcai.ui.live

import com.example.jingcai.data.model.LiveMatch
import com.example.jingcai.data.network.TitanLiveParser
import com.example.jingcai.data.repository.AppContextHolder
import com.example.jingcai.data.repository.LiveScoreRepository
import com.example.jingcai.data.repository.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App 退到后台时的进球轮询器（**进程内协程**：判定逻辑在这里，不放在 Service 里）。
 *
 * ## 和 [GoalWatchService] 的分工
 * 本类只负责「拉数据 + 判进球 + 交付」，**不负责让进程活着**。
 * 让进程活着是 [GoalWatchService]（前台服务）的事 —— 它挂一条 `IMPORTANCE_NONE` 的锚点通知，
 * 状态栏看不见，但进程不会被 cgroup freezer 冻住。
 *
 * ## 为什么必须有那个锚点（实测，不是猜测）
 * 没有前台服务时，本机（vivo V2425A / Android 16）退到后台或**息屏**约 5 秒后：
 * `/sys/fs/cgroup/apps/<uid>/<pid>/cgroup.freeze = 1`，`delay()` 再也不被唤醒，
 * 息屏期间进球一条通知都发不出来（用户实测报障「关闭屏幕，进球状态栏没提示」）。
 * `dumpsys activity processes` 的 `isFrozen` 仍显示 false，**别信它，要看 cgroup**；
 * `dumpsys deviceidle whitelist +包名` 也**不能**免掉这个冻结。
 *
 * ## 轮询策略（与前台口径一致）
 *  - 常规只拉 `change.xml`（1 个请求，几十 KB），每 [FULL_INTERVAL_MS] 才整页校准一次；
 *  - 比分**上涨**＝进球、比分**回退**＝进球被取消，两者都走 [GoalDeliverer]；
 *  - 每次拉取都套 [FETCH_TIMEOUT_MS]：OkHttp 的阻塞调用不受协程取消影响，不封顶会楔死循环。
 *  - 前台期间本类不工作，全部交给 [LiveViewModel] 的 5 秒 tick（避免双份网络请求）。
 */
object BackgroundGoalWatcher {
    private const val TAG = "GoalWatcher"
    private val repo = LiveScoreRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 拉取专用 scope（与 [scope] 分离）。
     *
     * 🔴 `withTimeoutOrNull { repo.fetchBfData() }` **拦不住**里面的 OkHttp `execute()`
     * （阻塞调用不响应协程取消），必须丢到独立 scope 里 `async` 再 `await()`。
     */
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 后台轮询当前是否在跑（守夜服务用来对齐状态、排查日志用）。 */
    val isRunning: Boolean get() = job != null

    /**
     * 续期 wakelock（由 [GoalKeepAliveService] 每 30 秒调一次）。
     *
     * 为什么要续：wakeLock 带 [WAKELOCK_TIMEOUT_MS] 自动过期（防止异常退出时白耗电），
     * 但正常值守时轮询协程一直在跑、不会重新走 [holdWakeLock] ⇒ 一旦过期，CPU 就会进
     * suspend，`delay()` 停摆，息屏提醒又失效了。续期让它"只要还在值守就一直有效"。
     */
    fun renewWakeLock() {
        if (job != null) holdWakeLock(renew = true)
    }

    /** titanId → 上一次看到的 (主进球, 客进球)。 */
    private val lastScore = HashMap<Long, Pair<Int, Int>>()
    /** titanId → 队名 / 联赛（通知文案用，来自整页拉取）。 */
    private val meta = HashMap<Long, LiveMatch>()
    /** 上次整页拉取的时间；0 = 还没拉过。 */
    private var lastFullMs = 0L

    /**
     * 按当前状态同步启停：**App 在后台 + 开着进球提醒 + 有关注比赛**才跑。
     *
     * 幂等，可以随便多次调用（ON_START / ON_STOP / 用户点关注 都调这一个）。
     */
    fun sync() {
        // 开赛提醒也是时间驱动、且同样怕进程被冻 ⇒ 只要开着其中任一提醒就得跑起来。
        // 🔴 不能只看 goalAlert：用户完全可能关掉进球刷屏、只留「快开赛了」这一条。
        val need = !AppForegroundState.foreground &&
            UserPrefs.followed.value.isNotEmpty() &&
            (UserPrefs.goalAlert.value || UserPrefs.kickoffAlert.value)
        if (need) start() else stop()
    }

    private fun start() {
        if (job != null) return
        // 每次进入后台都重建基线：否则「前台期间发生的进球」会在首轮被当成新进球重复提醒。
        lastScore.clear()
        lastFullMs = 0L
        holdWakeLock()
        job = scope.launch { loop() }
        android.util.Log.i(TAG, "start 后台轮询（持 wakelock）followed=${UserPrefs.followed.value.size}")
    }

    private fun stop() {
        if (job != null) android.util.Log.i(TAG, "stop 后台轮询")
        job?.cancel()
        job = null
        lastScore.clear()
        releaseWakeLock()
    }

    /**
     * 持 [PowerManager.PARTIAL_WAKE_LOCK]：**防 cgroup 冻结的关键**。
     *
     * 系统的 `CachedAppOptimizer` 只冻「cached」进程（oom adj ≥ 900）。持 wakelock 会把进程
     * 抬到非 cached 档，于是不被冻 ⇒ 息屏后 5 秒轮询继续跑。
     * 这比前台服务干净：**不需要任何常驻通知**（前台服务会强制把渠道 importance 抬回可见，
     * 实测压不住，会在状态栏留一个图标）。
     *
     * 超时设 [WAKELOCK_TIMEOUT_MS]：万一轮询协程异常退出而没走到 [releaseWakeLock]，
     * wakelock 也会自己过期，不至于把电池耗干。
     */
    private fun holdWakeLock(renew: Boolean = false) {
        if (wakeLock?.isHeld == true) return
        val ctx: android.content.Context = runCatching { AppContextHolder.ctx }.getOrNull() ?: return
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK, "jingcai:goal_watch"
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKELOCK_TIMEOUT_MS) }
        }
        android.util.Log.i(TAG, if (renew) "wakelock renewed" else "wakelock acquired")
    }

    private fun releaseWakeLock() {
        val w = wakeLock ?: return
        wakeLock = null
        runCatching { if (w.isHeld) w.release() }
        android.util.Log.i(TAG, "wakelock released")
    }

    /** 后台轮询期间的 wakelock（见 [holdWakeLock]）。 */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** wakelock 自动过期时间（轮询期间远小于它，正常都靠 [releaseWakeLock] 收尾）。 */
    private const val WAKELOCK_TIMEOUT_MS = 3 * 60 * 60_000L

    private suspend fun loop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(POLL_MS)
            try {
                oneRound()
            } catch (t: Throwable) {
                // 取消信号必须原样抛出，不能被兜底 catch 吞掉
                if (t is kotlinx.coroutines.CancellationException) throw t
                // 单轮失败只丢这一轮：解析越界等问题不能把整个轮询协程带走
                android.util.Log.w(TAG, "round 失败（下一轮重试）", t)
            }
        }
    }

    /**
     * 「屏幕亮起」时的补发入口（由 `MainActivity` 动态注册的 `ACTION_SCREEN_ON` 调用）。
     *
     * ## 为什么需要它（用户实测报障）
     * 息屏后进程被 ROM 冻住，期间的进球没有任何办法实时推送（前台服务 / wakelock / 精确闹钟
     * 三种手段实测全部被冻结拦下，见类注释）。但用户**开屏的那一刻**进程一定会被解冻，
     * 这就是补发的时机：立刻做一次**全量比对**（[fullRound]，不是增量 —— `change.xml`
     * 只保留最近变化，冻结几分钟后早就滚掉了），把漏掉的进球补成通知。
     *
     * 这是「尽最大努力」通道：进程若已被系统杀掉，内存里的基线就没了，
     * 首轮只记基线不误报（[fullRound] 里的 `prev == null` 分支），那种情况补不回来。
     */
    fun onScreenOn() {
        if (AppForegroundState.foreground) return
        if (!UserPrefs.goalAlert.value || UserPrefs.followed.value.isEmpty()) return
        android.util.Log.i(TAG, "screen on → 立刻全量比对，补发息屏期间漏掉的进球")
        scope.launch {
            // 与常驻轮询/闹钟串行，避免同一场进球被补发两遍
            roundMutex.withLock { fullRound(UserPrefs.followed.value) }
        }
    }

    private suspend fun oneRound() {
        // 用户把两种提醒都关了 / 取消全部关注 ⇒ 直接停（不占资源）
        val noGoal = !UserPrefs.goalAlert.value
        val noKick = !UserPrefs.kickoffAlert.value
        if (UserPrefs.followed.value.isEmpty() || (noGoal && noKick)) {
            stop()
            return
        }
        // 回到前台：进球交给 LiveViewModel 的 5s tick，这里停掉避免双份网络
        if (AppForegroundState.foreground) {
            stop()
            return
        }
        roundMutex.withLock { doRound() }
    }

    /**
     * 闹钟唤醒（[GoalAlarmReceiver]）时调用：**跑一轮就返回**，不碰常驻协程的 job 状态。
     *
     * 与 [oneRound] 的区别：这里绝不能调 [stop] —— 如果进程没被冻（亮屏后台），
     * 常驻协程还在跑，一 stop 就把它掐了。
     * 用 [roundMutex] 串行化，避免闹钟与常驻轮询同时比对同一场比分 ⇒ 同一次进球发两条通知。
     */
    suspend fun wakeOnce() {
        if (!UserPrefs.goalAlert.value || UserPrefs.followed.value.isEmpty()) return
        if (AppForegroundState.foreground) return
        roundMutex.withLock { doRound() }
    }

    /** 真正的一轮比对（调用方已保证「在后台 + 需要提醒」）。 */
    private suspend fun doRound() {
        val followed = UserPrefs.followed.value
        val goal = UserPrefs.goalAlert.value
        val now = System.currentTimeMillis()
        val needFull = lastScore.isEmpty() || now - lastFullMs > FULL_INTERVAL_MS
        if (needFull) {
            fullRound(followed, checkGoals = goal)
        } else if (goal) {
            deltaRound(followed)
        } else if (now - lastFullMs > KICKOFF_TICK_MS) {
            // 只要开赛提醒：**不必**每 5 秒拉整页，一分钟校准一次足够（提醒精度就是分钟级）
            fullRound(followed, checkGoals = false)
        }
        // 开赛提醒统一用 meta 里的场次元数据（不额外发请求）；前后台都走这里
        if (UserPrefs.kickoffAlert.value) {
            KickoffAlert.check(meta.values)
        }
    }

    /** 串行化「常驻轮询」与「闹钟唤醒」两条通道，防止重复提醒。 */
    private val roundMutex = kotlinx.coroutines.sync.Mutex()

    /** 在独立 scope 里跑一次拉取，超时即放弃等待（返回 null）。 */
    private suspend fun <T> fetchOrNull(block: suspend () -> T): T? {
        val d = fetchScope.async { runCatching { block() } }
        return withTimeoutOrNull(FETCH_TIMEOUT_MS) { d.await() }?.getOrNull()
    }

    /**
     * 整页校准：补元数据 + 全量比对。
     *
     * @param checkGoals 是否比对进球。`false` = 只为「开赛提醒」刷新场次元数据
     *   （用户把进球提醒关了，比分涨落对他无意义，省掉解析与通知）。
     *   元数据本身照常更新 —— 开赛时间就在里面。
     */
    private suspend fun fullRound(followed: Set<Long>, checkGoals: Boolean = true) {
        // 后台值守只要比分：关掉角球技统那 247KB（每 3 分钟一轮，夜里很贵）
        val raw = fetchOrNull { repo.fetchBfData(includeCorners = false) } ?: return
        // 顺手收集「已完场」的关注 id，清掉它们（已结束的比赛不再提醒）
        val finished = HashSet<Long>()
        for (m in raw.matches) {
            val id = m.titanId
            if (id !in followed) continue
            if (m.state == -1) {
                finished.add(id)
                continue
            }
            meta[id] = m
            // 只要开赛提醒：比分涨落一律不管，直接下一场
            if (!checkGoals) continue
            val (h, a) = TitanLiveParser.parseScore(m.score)
            // "-" 是未开始的占位比分（parseScore → 0-0），不能参与「进球/取消」判定
            if (m.score == "-") {
                lastScore[id] = h to a
                continue
            }
            val prev = lastScore[id]
            // 首次见到该场只记基线、不误报（进行中的既有比分不是「刚进的球」）
            if (prev != null && (h > prev.first || a > prev.second)) {
                val side = if (h > prev.first) 1 else 2
                runCatching {
                    GoalDeliverer.onGoal(GoalEvent(id, m.home, m.away, "$h-$a", side, m.league))
                }
            } else if (prev != null && (h < prev.first || a < prev.second)) {
                // 比分回退 = 进球被取消（VAR 改判）
                val side = if (h < prev.first) 1 else 2
                runCatching {
                    GoalDeliverer.onCancel(GoalEvent(id, m.home, m.away, "$h-$a", side, m.league))
                }
            }
            lastScore[id] = h to a
        }
        if (finished.isNotEmpty()) UserPrefs.removeFollow(finished)
        lastFullMs = System.currentTimeMillis()
    }

    /** 增量：只拉 change.xml（1 个请求），只比对关注场次。 */
    private suspend fun deltaRound(followed: Set<Long>) {
        val xml = fetchOrNull { repo.fetchRawChanges() } ?: return
        val changes = TitanLiveParser.parseChanges(xml)
        for (c in changes) {
            val id = c.titanId.toLongOrNull() ?: continue
            if (id !in followed) continue
            // state=0 未开始：比分无意义，跳过（与前台 applyChanges 口径一致）
            if (c.state == 0) continue
            // state=-1 已完场：收尾那一拍不提醒
            if (c.state == -1) continue
            val prev = lastScore[id] ?: continue
            if (c.home > prev.first || c.away > prev.second) {
                val mm = meta[id] ?: continue
                val side = if (c.home > prev.first) 1 else 2
                runCatching {
                    GoalDeliverer.onGoal(
                        GoalEvent(id, mm.home, mm.away, "${c.home}-${c.away}", side, mm.league)
                    )
                }
            } else if (c.home < prev.first || c.away < prev.second) {
                val mm = meta[id] ?: continue
                val side = if (c.home < prev.first) 1 else 2
                runCatching {
                    GoalDeliverer.onCancel(
                        GoalEvent(id, mm.home, mm.away, "${c.home}-${c.away}", side, mm.league)
                    )
                }
            }
            lastScore[id] = c.home to c.away
        }
    }

    /** 轮询间隔（与前台 tick 同节奏）。 */
    private const val POLL_MS = 5_000L

    /** 单次拉取的硬超时。 */
    private const val FETCH_TIMEOUT_MS = 20_000L

    /** 整页校准间隔（3 分钟；中间全走增量）。 */
    private const val FULL_INTERVAL_MS = 3 * 60_000L

    /**
     * 「只开赛提醒」模式下的校准间隔：60 秒。
     *
     * 开赛提醒的精度就是分钟级（提前量最小 1 分钟），3 分钟一校准会把「提前 15 分钟」
     * 拖成实际提前 12 分钟才弹。60 秒既能保证准点，又比进球模式的 5 秒省得多。
     */
    private const val KICKOFF_TICK_MS = 60_000L
}
