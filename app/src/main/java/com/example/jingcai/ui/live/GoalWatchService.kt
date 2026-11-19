package com.example.jingcai.ui.live

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.jingcai.R
import com.example.jingcai.data.repository.UserPrefs

/**
 * 后台进球提醒的**防冻结锚点**：一个前台服务，本身不拉数据、不判断进球
 * （判定逻辑全在 [BackgroundGoalWatcher] / [LiveViewModel]，这里只做一件事——
 * **让进程不被系统冻住**）。
 *
 * ## 为什么必须有它（实测数据，不是推测）
 * 本机 vivo V2425A / Android 16 上，App 退到后台（含**息屏**）约 5 秒后：
 * ```
 * /sys/fs/cgroup/apps/uid_10324/pid_20732/cgroup.freeze = 1
 * ```
 * 进程被 cgroup freezer 冻住 ⇒ 协程的 `delay()` 不再被唤醒 ⇒ 5 秒轮询停摆
 * ⇒ 息屏期间进球一条通知都发不出来。`dumpsys isFrozen` 仍显示 false（别信它），
 * `deviceidle whitelist` 也免不掉。**前台服务是这台机器上唯一不被冻结的方式。**
 *
 * ## 那条「后台关注中」通知去哪了
 * Android 规定前台服务必须 `startForeground(id, notification)`，通知**删不掉**。
 * 所以这里把它的**渠道** [CHANNEL_SERVICE] 建成 `IMPORTANCE_NONE`：
 * 服务照常跑，通知在状态栏**完全不显示**。渠道每次 App 启动都会自愈重置
 * （见 [GoalNotifier.ensureServiceChannelHidden]）——vivo 每次覆盖安装 APK 会把渠道
 * importance 重置回可见，这是老版本「怎么还在」的根因，靠自愈兜住。
 *
 * ## 启动时机（关键）
 * Android 12+（targetSdk≥31）**禁止从后台启动前台服务**，在 ON_STOP 里首次启动会抛
 * `ForegroundServiceStartNotAllowedException`。所以只在这些「App 还在前台」的时刻启动：
 *  - [com.example.jingcai.MainActivity.onCreate]（冷启动）；
 *  - `ProcessLifecycleOwner` 的 ON_START（回前台）；
 *  - 用户关注比赛 / 打开进球提醒时（[sync] 由偏好变化驱动）。
 * 一旦起来了就**常驻**，退到后台、息屏都不断，靠它撑住进程。
 */
class GoalWatchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        // 必须在 5 秒内 startForeground，否则系统直接 ANR/崩溃，所以放 onCreate 第一件事
        startForegroundCompat()
        android.util.Log.i(TAG, "锚点服务启动（通知渠道已隐藏）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 没有关注 / 关了提醒 ⇒ 不需要锚点，立刻退出（不白占前台服务名额）
        if (!isNeeded()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        // 进程还活着不代表轮询在跑：交给统一入口按「前台/后台 + 偏好」决定
        BackgroundGoalWatcher.sync()
        return START_STICKY
    }

    override fun onDestroy() {
        android.util.Log.i(TAG, "锚点服务停止")
        running = false
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val n = anchorNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ANCHOR_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ANCHOR_ID, n)
        }
    }

    companion object {
        private const val TAG = "GoalWatchService"

        /**
         * 服务是否正在运行（进程内标志）。
         * [GoalNotifier.ensureServiceChannelHidden] 用它避开系统硬校验：
         * **前台服务正在使用的渠道禁止删除**（`enforceDeletingChannelHasNoFgService`），
         * 删了会直接抛异常把进程带崩。
         */
        @Volatile
        var running: Boolean = false
            private set

        /** 是否需要锚点：开着进球提醒，且至少关注了一场比赛。 */
        fun isNeeded(): Boolean =
            UserPrefs.followed.value.isNotEmpty() &&
                (UserPrefs.goalAlert.value || UserPrefs.kickoffAlert.value)

        /**
         * 总开关：**当前关闭**。
         *
         * 实测（vivo V2425A / Android 16）：前台服务确实能防冻结，但它把渠道 importance 从
         * `IMPORTANCE_NONE` 强制抬回可见（`mImportance=2`，应用侧降不回去），状态栏必然多一个图标 ——
         * 与「状态栏只留进球提示」冲突。改用 [BackgroundGoalWatcher] 里的 PARTIAL_WAKE_LOCK 方案后
         * 若仍压不住冻结，再把这里改回 true 当兜底。
         */
        private const val ENABLED = false

        /** 按当前偏好同步启停（幂等，可随手多次调用）。 */
        fun sync(ctx: Context, need: Boolean = isNeeded()) {
            if (!ENABLED) {
                stop(ctx)
                return
            }
            if (need) start(ctx) else stop(ctx)
        }

        private fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, GoalWatchService::class.java))
            }.onFailure {
                // 后台启动被系统拒绝（Android 12+ 限制）：不是致命错误，回前台会再试一次
                android.util.Log.w(TAG, "启动前台服务被拒（可能不在前台）", it)
            }
        }

        private fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, GoalWatchService::class.java)) }
        }

        /**
         * 锚点通知本体。渠道是 `IMPORTANCE_NONE` ⇒ 状态栏**看不到它**；
         * 内容写成中性空文案，万一某台 ROM 强行显示也不会是「竞彩后台关注中」这种话。
         */
        private fun anchorNotification(ctx: Context): Notification =
            NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_blank)
                .setContentTitle("")
                .setContentText("")
                .setSilent(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()
    }
}
