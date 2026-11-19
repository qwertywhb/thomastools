package com.example.jingcai.ui.live

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.example.jingcai.data.repository.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 后台进球提醒的**闹钟唤醒**通道（在 [BackgroundGoalWatcher] 常驻轮询之外的第二条腿）。
 *
 * ## 为什么要它
 * 本机（vivo V2425A / Android 16）实测：息屏后台进程一律被 cgroup freezer 冻住
 * （`cgroup.freeze=1`、CPU 时间不再增长），**前台服务、PARTIAL_WAKE_LOCK、
 * `deviceidle whitelist` 三种常规手段全部无效**。唯一还能「把进程叫醒跑一段」的，
 * 是系统的 [AlarmManager]：闹钟到点会临时解冻进程执行本 Receiver。
 *
 * ## 策略
 *  - 退到后台（ON_STOP）时排一个 [INTERVAL_MS] 后的精确闹钟；
 *  - 闹钟到了 → [onReceive] 里 `goAsync()` 拉一次比分、比对、判进球 → 发通知；
 *  - 跑完立刻排下一个，**自我续约**；
 *  - 回到前台（ON_START）→ [cancel]，交给 [LiveViewModel] 的 5 秒 tick。
 *
 * ## 取舍（必须知道）
 *  - 不是「实时」：正常状态下最多延迟 [INTERVAL_MS]（1 分钟）；
 *  - 进入 Doze（息屏静置较久）后系统会把 `setExactAndAllowWhileIdle` 限流到约 **9 分钟**一次，
 *    这时延迟会明显变长 —— 这是 Android 的硬限制，App 侧绕不过。
 */
class GoalAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (!GoalAlarmScheduler.isNeeded()) {
            GoalAlarmScheduler.cancel(app)
            return
        }
        // goAsync：网络请求不能占用主线程，但 Receiver 返回后进程随时可能被再次冻结，
        // 所以必须显式告诉系统「还没干完」。
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                android.util.Log.i(TAG, "闹钟唤醒，拉一轮")
                BackgroundGoalWatcher.wakeOnce()
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "闹钟轮次失败", t)
            } finally {
                // 续约：下一轮
                if (GoalAlarmScheduler.isNeeded()) GoalAlarmScheduler.schedule(app)
                runCatching { pending.finish() }
            }
        }
    }

    private companion object {
        const val TAG = "GoalAlarm"
    }
}

/**
 * 闹钟的排期/取消（见 [GoalAlarmReceiver] 的 KDoc）。
 *
 * 权限说明：`USE_EXACT_ALARM`（API 33+）由系统**自动授予**、无需用户操作，
 * 因此这里不需要弹「允许精确闹钟」的设置页；低版本走 `setExactAndAllowWhileIdle`。
 */
object GoalAlarmScheduler {

    /** 闹钟间隔：1 分钟。比 5 秒轮询粗糙，但这是唯一能在冻结状态下跑起来的通道。 */
    private const val INTERVAL_MS = 60_000L

    private const val REQ = 8801

    /** 是否需要闹钟：开着进球提醒 + 有关注 + **当前不在前台**。 */
    fun isNeeded(): Boolean =
        UserPrefs.goalAlert.value &&
            UserPrefs.followed.value.isNotEmpty() &&
            !AppForegroundState.foreground

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        runCatching {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            when {
                canExact -> am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi(ctx))
                // 没拿到精确闹钟权限时降级：系统会做窗口对齐（可能多等几分钟），但总比完全不动强
                else -> am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi(ctx))
            }
            android.util.Log.i("GoalAlarm", "下一个闹钟已排：${INTERVAL_MS / 1000}s 后（exact=$canExact）")
        }.onFailure { android.util.Log.w("GoalAlarm", "排闹钟失败", it) }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pi(ctx)) }
    }

    private fun pi(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, REQ, Intent(ctx, GoalAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
