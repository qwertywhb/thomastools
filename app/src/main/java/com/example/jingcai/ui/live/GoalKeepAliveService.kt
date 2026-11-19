package com.example.jingcai.ui.live

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 息屏进球提醒的"守夜人"——**唯一能在本机（vivo）绕过后台冻结的手段**。
 *
 * ## 定位：可选增强，不是功能前置条件（2026-11-04 定稿）
 * 不开本服务，进球提醒照样能用：App 在前台、或用户亮屏用其他应用时，后台轮询 + 震动都正常；
 * 只有「手机黑屏静置」这一种场景收不到。所以**默认不开启、冷启动不主动去开**
 * （`UserPrefs.keepAliveWanted` 默认 false），只在用户于设置卡里走完说明页并点「去系统设置」后才启用。
 * 🔴 别把它改回"自动弹系统设置页"：别的手机上那等于莫名其妙被丢进无障碍设置。
 *
 * ## 为什么要靠无障碍服务（实测结论，不是猜测）
 * 本机息屏后会把 App 进程冻进 cgroup freezer（`cgroup.freeze=1`），以下手段**全部实测被拦**：
 *
 * | 手段 | 实测结果 |
 * |---|---|
 * | 进程内协程轮询 | 5s 后 freeze=1，`delay()` 不再唤醒 |
 * | PARTIAL_WAKE_LOCK | 无效，仍 freeze=1 |
 * | 前台服务（dataSync） | `isForeground=true`，但进程仍 freeze=1 |
 * | `deviceidle whitelist` | 无效 |
 * | 精确闹钟 setExactAndAllowWhileIdle | `dumpsys alarm` 记 `Reason=frozen`，到点投递不了 |
 * | 亮屏广播补发 | 广播同样不投递 |
 *
 * 而 **AccessibilityService 由 system_server 通过 bindService 长期持有**，进程不会被当 cached
 * 处理，重启也由系统负责 ⇒ 只要它活着，同进程内的 [BackgroundGoalWatcher] 轮询就不会停，
 * 进球时能正常发通知 + 震动。这是本地唯一能"零服务端"做到的方案。
 *
 * ## 代价（必须让用户知道）
 * 无障碍开关在系统那边，普通应用**无权自行打开**。所以本服务提供两条路（见 [enableOneTap]）：
 *  - 本机授过 `WRITE_SECURE_SETTINGS`（adb 一次性授权）⇒ **真一键**，静默写 Secure 设置；
 *  - 没授过 ⇒ 一键跳到系统无障碍列表页，用户点一下开关即可（不再是「设置→无障碍→找 App」四步）。
 * 开启后系统会常驻一条"已开启无障碍"的提示，这是 Android 的硬性要求，任何第三方应用都避不开。
 * 本服务**不读屏、不采集任何内容**（`canRetrieveWindowContent=false`），只借"被系统长期绑定"这一点。
 *
 * ## 它做什么
 * 不参与进球判定 —— 判定仍在 [BackgroundGoalWatcher]。这里只做两件事：
 * 1. 保证进程活着（绑定本身的效果）；
 * 2. 定期调 [BackgroundGoalWatcher.sync]，防止"Activity 生命周期没触发"时轮询没起来。
 */
class GoalKeepAliveService : AccessibilityService() {

    private var tickJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.i(TAG, "onServiceConnected 守夜服务已连接，开始保活")
        if (tickJob != null) return
        tickJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            var n = 0
            while (isActive) {
                delay(TICK_MS)
                n++
                // 幂等：该跑就启动、不该跑就停（用户关了提醒 / 没有关注 → 自动停，不白耗电）
                runCatching { BackgroundGoalWatcher.sync() }
                // 续期唤醒锁：否则 3 小时后自动过期 ⇒ CPU 进 suspend ⇒ delay 停摆
                runCatching { BackgroundGoalWatcher.renewWakeLock() }
                android.util.Log.i(
                    TAG,
                    "alive #$n freeze=${readFreeze()} watcher=${BackgroundGoalWatcher.isRunning}"
                )
            }
        }
    }

    /**
     * 读自己的 cgroup 冻结标志（排查用，正常应为 0）。
     *
     * 🔴 两个坑：
     *  1. 必须取 `0::` 那行（cgroup v2 统一层级）。**不能按「含 apps」模糊匹配** ——
     *     `/proc/self/cgroup` 里 `4:memory:/apps/com.example.jingcai` 也含 apps，
     *     命中它就会去读 `/sys/fs/cgroup/apps/com.example.jingcai/cgroup.freeze`（不存在）⇒ 永远 err。
     *  2. 即使路径正确，**app 侧通常仍读不到**（SELinux 只允许 shell/system_server 读该文件），
     *     所以日志里常见 `freeze=?`。这是正常的，不代表没被冻 ——
     *     真要确认请用 `adb shell cat /sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze`。
     */
    private fun readFreeze(): String = runCatching {
        val rel = java.io.File("/proc/self/cgroup").readLines()
            .firstOrNull { it.startsWith("0::") }
            ?.substringAfter("0::")?.trim()
            ?: return@runCatching "?"
        java.io.File("/sys/fs/cgroup$rel/cgroup.freeze").takeIf { it.exists() }
            ?.readText()?.trim() ?: "?"
    }.getOrDefault("?")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        android.util.Log.i(TAG, "onUnbind 守夜服务断开")
        tickJob?.cancel()
        tickJob = null
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "GoalKeepAlive"

        /** 每 30 秒对齐一次轮询状态（轮询本身是 5 秒一次，由 [BackgroundGoalWatcher] 负责）。 */
        private const val TICK_MS = 30_000L

        /** [enableOneTap] 结果：已**静默**开启（用户 0 步操作）。 */
        const val OPEN_QUIET = 1

        /** [enableOneTap] 结果：本机没有静默权限，已跳到系统无障碍页（用户在页面上点一下开关）。 */
        const val OPEN_MANUAL = 2

        /** [enableOneTap] 结果：连系统页都起不来。 */
        const val OPEN_FAILED = 0

        private fun comp(ctx: Context) = ComponentName(ctx, GoalKeepAliveService::class.java)

        /**
         * 列表里的某一条是不是「我们自己」。
         *
         * 🔴 **必须用 [ComponentName] 比对，不能比字符串**：实测把
         * `com.example.jingcai/com.example.jingcai.ui.live.GoalKeepAliveService` 写进
         * `enabled_accessibility_services` 后，系统会立刻把它**规范化**成
         * `com.example.jingcai/.ui.live.GoalKeepAliveService`（包名 + `.缩写类名`）。
         * 按全名做 `equals` 会一条都匹配不上 ⇒ 关闭时摘不掉自己、开启时重复追加。
         * [ComponentName.unflattenFromString] 会把以 `.` 开头的类名补全，正好抹平两种写法。
         */
        private fun isSelf(ctx: Context, raw: String): Boolean {
            val want = comp(ctx)
            val c = ComponentName.unflattenFromString(raw) ?: return false
            return c.packageName == want.packageName && c.className == want.className
        }

        /**
         * 无障碍服务是否已在系统里启用。
         *
         * 比对走 [isSelf]（ComponentName）。系统写入的列表里类名**可能**是
         * `pkg/.ui.live.Xxx` 这种缩写形式，直接比全名会漏判。
         */
        fun isEnabled(ctx: Context): Boolean {
            val cur = runCatching {
                Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull() ?: return false
            return cur.split(':').any { it.isNotBlank() && isSelf(ctx, it) }
        }

        /**
         * 是否具备「静默改写系统设置」的能力。
         *
         * [android.Manifest.permission.WRITE_SECURE_SETTINGS] 是 `signature|privileged|development`
         * 级权限，普通安装拿不到，但因为它带 `development` 标志，可以**一次性**用 adb 授予：
         * `adb shell pm grant com.example.jingcai android.permission.WRITE_SECURE_SETTINGS`。
         * 授过之后本 App 就能自己把无障碍打开，用户再也不用每次去系统设置里找。
         */
        fun canQuietEnable(ctx: Context): Boolean = runCatching {
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        /**
         * 一键开启无障碍（弹窗底部「开启无障碍」按钮的唯一入口）。
         *
         * 分三层，命中越靠前用户操作越少：
         * 1. 已经开了 → 直接返回，无动作；
         * 2. 有 WRITE_SECURE_SETTINGS → **静默写 Secure 设置**，0 步；
         * 3. 没有 → 跳系统无障碍页兜底（用户在页面上点一下开关即可）。
         *
         * 🔴 第 3 层**不能**用 `ACTION_ACCESSIBILITY_DETAILS_SETTINGS`（本服务开关详情页）：
         * 该 action 受 `android.permission.OPEN_ACCESSIBILITY_DETAILS_SETTINGS` 保护，
         * 普通应用一调用就 `SecurityException`（实测 vivo 上必抛），只能退回列表页。
         */
        fun enableOneTap(ctx: Context): Int {
            if (isEnabled(ctx)) return OPEN_QUIET
            if (enableQuietly(ctx)) return OPEN_QUIET
            return if (openSettings(ctx)) OPEN_MANUAL else OPEN_FAILED
        }

        /**
         * 静默开启（无权限时返回 false，不会跳任何页面）。
         *
         * 只改两个 Secure 项：把本服务追加进 `enabled_accessibility_services`，再把总开关
         * `accessibility_enabled` 置 1。system_server 里的 AccessibilityManagerService 有
         * SettingsObserver 监听这两项，写完后会自行 bind 服务（不用重启 App）。
         * 已存在就不重复追加，避免列表里堆重复项。
         */
        fun enableQuietly(ctx: Context): Boolean {
            if (!canQuietEnable(ctx)) return false
            return runCatching {
                val cr = ctx.contentResolver
                val cur = Settings.Secure.getString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val list = cur.split(':').filter { it.isNotBlank() }.toMutableList()
                // 已存在（无论全名还是 `pkg/.Cls` 缩写）就不重复追加
                if (list.none { isSelf(ctx, it) }) {
                    list.add(comp(ctx).flattenToString())
                    Settings.Secure.putString(
                        cr,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        list.joinToString(":")
                    )
                }
                // 顺序无所谓，但总开关必须显式置 1：有些 ROM 会把列表改了而总开关仍是 0。
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                isEnabled(ctx)
            }.getOrDefault(false)
        }

        /**
         * 「自愈」：只在**用户确实开着进球提醒**且本机授过 WRITE_SECURE_SETTINGS 时，
         * 把被 ROM 关掉的无障碍静默补开（冷启动调一次）。
         *
         * 为什么需要它：部分 ROM 重启 / 清理后会把三方无障碍关掉，用户会误以为「每次都要去开」。
         * 用户主动把「进球提醒」关掉时本函数**不介入**（[goalAlertOn] 由调用方传入现状），
         * 避免悄悄改系统设置。
         */
        fun ensureEnabledQuietly(ctx: Context, goalAlertOn: Boolean): Boolean {
            if (!goalAlertOn || isEnabled(ctx)) return false
            return enableQuietly(ctx)
        }

        /**
         * 一键**关闭**无障碍（底部按钮在「已开启」态时的动作）。
         *
         * 与开启对称：有 WRITE_SECURE_SETTINGS 就静默把本服务从列表里摘掉；
         * 没有就走系统页（用户自己关一下）。
         */
        fun disableOneTap(ctx: Context): Int {
            if (!isEnabled(ctx)) return OPEN_QUIET
            if (disableQuietly(ctx)) return OPEN_QUIET
            return if (openSettings(ctx)) OPEN_MANUAL else OPEN_FAILED
        }

        /**
         * 静默关闭本服务（无权限返回 false）。
         *
         * 🔴 只摘**自己**：列表里若还有别的无障碍服务（TalkBack、输入法、字体美化…），
         * 必须原样写回，并且**不能**把总开关 `accessibility_enabled` 置 0 —— 否则会顺手
         * 把别人正在用的无障碍一起关掉。只有列表被摘空时才关总开关。
         */
        fun disableQuietly(ctx: Context): Boolean {
            if (!canQuietEnable(ctx)) return false
            return runCatching {
                val cr = ctx.contentResolver
                val cur = Settings.Secure.getString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                // 只摘自己（全名/缩写都认），别人原样写回
                val rest = cur.split(':').filter { it.isNotBlank() && !isSelf(ctx, it) }
                Settings.Secure.putString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    if (rest.isEmpty()) null else rest.joinToString(":")
                )
                if (rest.isEmpty()) {
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
                }
                !isEnabled(ctx)
            }.getOrDefault(false)
        }

        /**
         * 跳系统无障碍设置页（兜底路径），跳到能返回 true。
         *
         * 只跳**列表页**：详情页 action 需要系统权限（见 [enableOneTap] 注释），普通应用用不了。
         * 列表页里本服务通常已经在「已下载的服务」分组里，用户点一下开关即可。
         */
        fun openSettings(ctx: Context): Boolean = runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }
}
