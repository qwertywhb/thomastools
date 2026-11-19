package com.example.jingcai.ui.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.jingcai.MainActivity
import com.example.jingcai.R
import com.example.jingcai.data.repository.AppContextHolder
import com.example.jingcai.data.repository.UserPrefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 进球通知渠道（状态栏）。进球 / 取消进球都走它。 */
const val CHANNEL_GOAL = "goal_alert"
const val NOTIF_GOAL_ID = 7001
const val PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

/**
 * 前台服务（[GoalWatchService]）的**锚点通知**渠道与 id。
 *
 * 这条通知存在的唯一目的是满足「前台服务必须挂通知」的系统规定，用来换进程不被冻结；
 * 渠道建成 [NotificationManager.IMPORTANCE_NONE] ⇒ 状态栏**看不到**。
 * 🔴 渠道名必须沿用老版本用的 `goal_service`：vivo 覆盖安装会把它重置回可见，
 *    沿用同名 + 每次启动自愈（见 [GoalNotifier.ensureServiceChannelHidden]）才能把它压回去；
 *    换个新名字只会多出一条可见渠道。
 */
const val CHANNEL_SERVICE = "goal_service"
const val NOTIF_ANCHOR_ID = 7002

/** 一场关注比赛的进球事件（也可表示「进球被取消」）。 */
data class GoalEvent(
    val titanId: Long,
    val home: String,
    val away: String,
    val score: String,
    /** 进球方：1=主队，2=客队。**取消进球时指「被扣掉进球」的那一方**。 */
    val side: Int,
    val league: String,
    /**
     * 是否为「进球被取消」（比分回退 / VAR 改判）。
     *
     * true 时：弹窗换玫红配色 + 文案「取消进球」，状态栏文案同步换掉，震动也换成两下短震，
     * 这样不看屏幕也能凭手感区分「进球」和「取消进球」。
     */
    val cancelled: Boolean = false
)

/** 页内动画弹窗用：给每条事件一个稳定 id 以便队列增删。 */
data class PopupItem(val id: Int, val event: GoalEvent, val leaving: Boolean = false)

/**
 * 比分串拆成「主队进球数 / 分隔符 / 客队进球数」。
 *
 * 用途：用户要求「哪边进球，哪边比分红色」—— 一个 `"2-1"` 里的 `2` 和 `1` 属于不同球队，
 * 必须分别上色，所以先把两段拆开。分隔符兼容半角/全角/冒号/波浪号（不同数据源写法不一），
 * 拆不出来（比如只有一个总分）就整串当左段、右段留空，调用方自行隐藏。
 */
internal data class ScoreParts(val left: String, val sep: String, val right: String)

internal fun splitScore(score: String): ScoreParts {
    val i = score.indexOfFirst { it in "-:：－–—~ " }
    if (i <= 0 || i == score.lastIndex) return ScoreParts(score, "", "")
    return ScoreParts(score.substring(0, i), score[i].toString(), score.substring(i + 1))
}


/** 进球事件总线：ViewModel（前台）与后台服务都往这里 emit，页内弹窗 collect。 */
object GoalEventBus {
    private val _events = MutableSharedFlow<GoalEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<GoalEvent> = _events.asSharedFlow()
    fun emit(e: GoalEvent) { _events.tryEmit(e) }
}

/**
 * 比分直播页（LiveScreen）是否处于可见组合状态。
 * 用于进球的交付方式判定：**在页内 → 动画弹窗**；否则 → 状态栏通知。
 */
object LiveScreenVisibleState {
    private var _visible = false
    val visible: Boolean get() = _visible
    fun set(v: Boolean) { _visible = v }
}

/**
 * App 是否处于前台（由 `ProcessLifecycleOwner` 的 ON_START / ON_STOP 维护）。
 *
 * 为什么需要它：**Android 12+（targetSdk≥31）禁止从后台启动前台服务**，
 * 所以在 ON_STOP 里 `startForegroundService()` 会抛 `ForegroundServiceStartNotAllowedException` 直接崩。
 * 因此服务只在**App 处于前台时**启动（ON_START / 用户点关注这类前台交互），
 * 启动后常驻；前台期间它**不轮询**（交给 LiveViewModel，避免双份网络），
 * 只在 [AppForegroundState.foreground] 变 false（App 退到后台）时才自己拉数据。
 */
object AppForegroundState {
    @Volatile
    var foreground: Boolean = false
}

/**
 * 进球交付中枢：统一收口「页内 vs 状态栏」的判定，且受 [UserPrefs.goalAlert] 总开关约束。
 *  - **不论在哪，状态栏都发一条通知**（用户明确要求「App 内进球状态栏也提示」）。
 *  - 在比分直播页内：额外 emit 到总线，由页内动画弹窗接收（负责震动 + 音乐）；
 *    此时通知**不再重复震动/响铃**（`feedback=false`），否则一次进球两声两震。
 *  - 不在页内（其它页面 / 退到后台 / App 已杀）：只有通知，震动与音乐挂在通知这一侧。
 */
object GoalDeliverer {
    /**
     * 已经投递过的「比赛 id → 比分」。
     *
     * 🔴 为什么必须有这道闸：进球有**两条各自独立的检测链路**，而且它们维护的是
     * **两份互不同步的比分基线**：
     *  - 后台：[BackgroundGoalWatcher.lastScore] —— App 退到后台时由前台服务轮询维护；
     *  - 前台：[com.example.jingcai.ui.LiveViewModel.followScores] —— 比分直播页可见时由 5 秒 tick 维护。
     *
     * App 在后台时只有前一条在跑（后一条的 tick 已随 ON_STOP 停掉），它把 1-1 → 2-1 提醒了；
     * 用户点回 App，页内那条基线还停在 **1-1**，第一次 tick 拉到 2-1 就又判一次「进球」⇒
     * 通知响一声、进 App 再响一声（用户 2026-11-16 报障「进球后响了一声，然后点进 app 又响一次」）。
     *
     * 这里按「比赛 id + 比分串」做最后一道闸：同一个比分只投递一次。
     * 比分回退（取消进球 / VAR 改判）时值不同，不会被误吞；同一场再进一球也是新值。
     *
     * 刻意**不随关注列表清空**：清掉之后同一个比分又能再提醒一次，正好把重复提醒放回来。
     * 每场比赛只占一个 key，关注数最多几十场，不做回收也不会有体积问题。
     */
    private val delivered = HashMap<Long, String>()

    /**
     * 比分归一化，只用来做去重键。
     *
     * 🔴 两条链路拼比分的写法不同：后台是 `"$h-$a"`，前台直接用数据源的原始串
     * （可能是 `2-1`，也可能是 `2:1` / 全角冒号）。不归一化的话同一个球会算出两个不同的键，
     * 去重直接失效 —— 这正是要修的那个 bug 本身。
     */
    private fun normScore(s: String): String {
        val p = s.trim().split('-', ':', '：')
        if (p.size != 2) return s.trim()
        val h = p[0].trim().toIntOrNull() ?: return s.trim()
        val a = p[1].trim().toIntOrNull() ?: return s.trim()
        return "$h-$a"
    }

    fun onGoal(e: GoalEvent) = deliver(e.copy(cancelled = false))

    /** 进球被取消（比分回退）：与进球同一条交付链路，只是文案/配色/震动不同。 */
    fun onCancel(e: GoalEvent) = deliver(e.copy(cancelled = true))

    private fun deliver(e: GoalEvent) {
        if (!UserPrefs.goalAlert.value) return
        // 先去重再判页内/页外：重复事件连通知都不发，否则用户只是「通知不响」，通知栏照样堆两条。
        val key = normScore(e.score)
        val prev = delivered.put(e.titanId, key)
        if (prev == key) {
            android.util.Log.i(
                "GoalAlert",
                "跳过重复投递 id=${e.titanId} score=$key cancelled=${e.cancelled}"
            )
            return
        }
        // 🔴 必须**同时**满足「页内组合可见」和「App 在前台」才算页内弹窗。
        // 只判 visible 会漏：App 退到后台时 composable 并不会被 dispose，
        // LiveScreenVisibleState 还是 true ⇒ 进球被塞进页内弹窗（用户根本看不到），
        // 状态栏一条通知都不发。这个坑只有纯后台（前台服务轮询）链路才会暴露。
        val inPage = LiveScreenVisibleState.visible && AppForegroundState.foreground
        // 状态栏通知：页内页外都发（页内这条是静默的，不抢弹窗的震动/音乐）
        GoalNotifier.post(e, feedback = !inPage)
        if (inPage) GoalEventBus.emit(e)
        android.util.Log.i(
            "GoalAlert",
            "投递 id=${e.titanId} score=$key cancelled=${e.cancelled} inPage=$inPage"
        )
    }
}

/** 状态栏通知 + 震动/音乐（都由 [GoalNotifier.post] 的 feedback 参数决定是否触发）。 */
object GoalNotifier {
    /**
     * 建渠道（幂等，可重复调用）。
     *
     * 两个渠道：
     *  - [CHANNEL_GOAL]「进球提醒」—— 状态栏真正给用户看的进球/取消进球通知；
     *  - [CHANNEL_SERVICE]「后台运行」—— 前台服务锚点，`IMPORTANCE_NONE`，**不显示**。
     */
    fun ensureChannels(ctx: Context) {
        // 先清理上次进程被杀残留的锚点通知（系统不会自动收）
        purgeLegacyOnGoing(ctx)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // 进球/取消进球通知：不自带声音与震动（都由代码显式控制，好跟随设置里的开关）
        val goal = NotificationChannel(CHANNEL_GOAL, "进球提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            description = "关注比赛进球时推送"
        }
        nm.createNotificationChannel(goal)
        // 开赛提醒单独一条渠道：用户在系统设置里可以只关它、保留进球提醒（反之亦然）
        runCatching { KickoffAlert.ensureChannel(ctx) }
        // 渠道修复绝不能把 App 带崩：这里任何异常都只记日志（进球提醒本身不依赖它）
        runCatching { ensureServiceChannelHidden(nm) }
            .onFailure { android.util.Log.w("GoalNotifier", "锚点渠道自愈失败", it) }
    }

    /**
     * 保证前台服务锚点渠道是 `IMPORTANCE_NONE`（状态栏不显示）。
     *
     * ## 为什么不能无脑 `deleteNotificationChannel`
     * 系统有硬校验 `enforceDeletingChannelHasNoFgService`：**前台服务正在使用的渠道禁止删除**，
     * 删除会直接抛异常把进程带崩（实测 `NotificationManagerService$13.deleteNotificationChannel`）。
     * 所以这里分两步：**先原地降级**（不删渠道，应用允许把自己的 importance 调低），
     * 降不下去、且当前**没有**前台服务在跑时才删了重建。
     *
     * ## 为什么需要自愈
     * vivo 每次覆盖安装 APK 都会把 `goal_service` 的 importance 重置回可见（实测 `mOriginalImp=1 → mImportance=2`），
     * 老版本就是死在这里 ——「竞彩后台关注中」每次装机后又冒出来。
     */
    private fun ensureServiceChannelHidden(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val exist = nm.getNotificationChannel(CHANNEL_SERVICE)
        if (exist != null && exist.importance == NotificationManager.IMPORTANCE_NONE) return
        // ① 原地降级：渠道已存在时应用只能降 importance，但「降」正是我们要的方向
        runCatching { nm.createNotificationChannel(hiddenServiceChannel()) }
        val after = runCatching { nm.getNotificationChannel(CHANNEL_SERVICE) }.getOrNull()
        if (after == null || after.importance == NotificationManager.IMPORTANCE_NONE) return
        // ② 还是可见 ⇒ 只能删掉重建。前台服务正在用这条渠道时**绝不能删**（系统会抛异常），
        //    此时先放弃，等下次服务没跑（冷启动）再修。
        if (GoalWatchService.running) {
            android.util.Log.w("GoalNotifier", "锚点渠道仍可见但服务在跑，跳过删除，等下次冷启动自愈")
            return
        }
        runCatching {
            nm.cancel(NOTIF_ANCHOR_ID)
            nm.deleteNotificationChannel(CHANNEL_SERVICE)
            nm.createNotificationChannel(hiddenServiceChannel())
        }.onFailure { android.util.Log.w("GoalNotifier", "重置锚点渠道失败（不影响进球提醒）", it) }
    }

    /** 锚点渠道定义：完全静音、不显示、不显示角标。 */
    private fun hiddenServiceChannel(): NotificationChannel =
        NotificationChannel(CHANNEL_SERVICE, "后台运行", NotificationManager.IMPORTANCE_NONE).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            description = "保持后台进球提醒可用；本渠道不显示任何通知"
        }

    /** 本进程是否已经清理过残留的锚点通知（避免每次回前台都重复 cancel）。 */
    private var legacyPurged = false

    /**
     * 清理**上一次进程**留下的锚点通知。
     *
     * 前台服务通知在进程被系统杀掉 / 被强制停止后**不会自动从通知栏消失**，
     * 会一直挂着；下次启动先把 7002 取消，再由 [GoalWatchService] 重新挂。
     */
    fun purgeLegacyOnGoing(ctx: Context) {
        if (legacyPurged) return
        legacyPurged = true
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(NOTIF_ANCHOR_ID)
        }
    }

    /** API 33+ 需要 POST_NOTIFICATIONS 权限，未授权则无法弹出状态栏通知。 */
    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, PERM_POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * 发状态栏通知（进球 / 取消进球，点击跳回比分直播页）。
     *
     * @param feedback 是否顺带震动 + 音乐。页内弹窗自己会震会响，所以页内传 false 防止双份反馈。
     *
     * 🔴 通知 id 只由 [GoalEvent.titanId] 决定 ⇒ **同一场比赛的「取消进球」会顶掉刚才那条
     *    「进球！」通知**，通知栏不会同时留着一条已经作废的进球，比分也能对上。
     */
    fun post(e: GoalEvent, feedback: Boolean = true) {
        val ctx = AppContextHolder.ctx
        if (!canPost(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = "open_live"
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // 文案：进球 / 取消进球。取消那条把「已取消」写在正文里，避免只看标题误以为又进球了。
        // 这两行仍然是**兜底文案**：部分 ROM（含息屏/耳机播报）会忽略自定义版式、只读 title/text，
        // 那时至少还能看到完整信息。
        val title = if (e.cancelled) {
            "🚫 取消进球 ${e.home} ${e.score} ${e.away}"
        } else {
            "⚽ 进球！ ${e.home} ${e.score} ${e.away}"
        }
        val text = if (e.cancelled) {
            listOf(e.league, "进球已取消（VAR）").filter { it.isNotEmpty() }.joinToString(" · ")
        } else {
            e.league.ifEmpty { "关注比赛进球" }
        }
        val notif = NotificationCompat.Builder(ctx, CHANNEL_GOAL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setDefaults(0)
            // 自定义版式：小字号 + 不加粗 + 进球方染红（系统模板做不到，见 layout/notif_goal.xml）
            .setCustomContentView(buildContentView(ctx, e))
            .setCustomBigContentView(buildContentView(ctx, e))
            .build()
        nm.notify(NOTIF_GOAL_ID + (e.titanId % 1000).toInt(), notif)
        if (feedback) {
            vibrate(ctx, cancelled = e.cancelled)
            playGoalSound(ctx)
        }
    }

    /**
     * 构建状态栏通知的自定义版式（[R.layout.notif_goal]）。
     *
     * 三条诉求都靠它落地：
     *  1. **进球方文字染红** —— 主队占 [R.id.notif_home]、客队占 [R.id.notif_away]，
     *     只有 [GoalEvent.side] 指到的那一侧用红色，另一侧用常规墨色；
     *  2. **紧凑两行** —— 第一行「● 进球 + 联赛」、第二行「主队 比分 客队」；
     *  3. **内容抱团不撑宽** —— 第二行三段都是 `wrap_content` + 整行居中，长队名各自 `maxWidth` 兜住。
     *
     * 颜色按**系统深浅色模式**切换：通知面板背景由系统画，浅色面板配深墨/深红、深色面板配亮字，
     * 两套对比度都够（浅色 #0F172A on 白 ≈ 16:1、#C62828 on 白 ≈ 6.4:1）。
     * ⚠️ 不要再调回 #6B7280 那类浅灰：那就是用户说的「太淡」。
     */
    private fun buildContentView(ctx: Context, e: GoalEvent): RemoteViews {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val ink = if (night) 0xFFF8FAFC.toInt() else 0xFF0F172A.toInt()
        val sub = if (night) 0xFFA3AEC2.toInt() else 0xFF475569.toInt()
        val red = if (night) 0xFFFF6B6B.toInt() else 0xFFC62828.toInt()

        val rv = RemoteViews(ctx.packageName, R.layout.notif_goal)
        // 第一行：事件标签。两种事件都用**深红加粗**（进球是主角，取消也是必须立刻看到的事件），
        // 靠文字区分「进球 / 进球取消」；不用 ⚽/🚫 emoji —— 彩色字形会冲掉 setTextColor 的事件色。
        // ⚠️ 别改成灰色：浅底上灰字正是用户说的「太淡」，看一眼扫不到。
        rv.setTextViewText(R.id.notif_label, if (e.cancelled) "● 进球取消" else "● 进球")
        rv.setTextColor(R.id.notif_label, red)
        // 第二行：队名 + 比分，进球方（side=1 主队 / 2 客队）染红
        rv.setTextViewText(R.id.notif_home, e.home)
        rv.setTextColor(R.id.notif_home, if (e.side == 1) red else ink)
        rv.setTextViewText(R.id.notif_away, e.away)
        rv.setTextColor(R.id.notif_away, if (e.side == 2) red else ink)
        // 比分也按「哪边进球哪边红」拆开上色：2-1 里的 2 属于主队、1 属于客队
        val sp = splitScore(e.score)
        rv.setTextViewText(R.id.notif_score_home, sp.left)
        rv.setTextColor(R.id.notif_score_home, if (e.side == 1) red else ink)
        rv.setTextViewText(R.id.notif_score_away, sp.right)
        rv.setTextColor(R.id.notif_score_away, if (e.side == 2) red else ink)
        rv.setTextColor(R.id.notif_score_sep, ink)
        val hasRight = sp.right.isNotEmpty()
        rv.setViewVisibility(R.id.notif_score_sep, if (hasRight) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.notif_score_away, if (hasRight) View.VISIBLE else View.GONE)
        // 第一行右侧：联赛（取消进球时补一句「已取消」，避免只看红字误以为又进了一个）。
        // 紧凑版只有一行位置，文案要短：原来是「进球已取消（VAR）」，会把联赛挤成省略号。
        val league = if (e.cancelled) {
            listOf(e.league, "已取消").filter { it.isNotEmpty() }.joinToString(" · ")
        } else {
            e.league
        }
        rv.setTextViewText(R.id.notif_league, league)
        rv.setTextColor(R.id.notif_league, sub)
        rv.setViewVisibility(R.id.notif_league, if (league.isEmpty()) View.GONE else View.VISIBLE)
        return rv
    }

    /**
     * 显式震动。
     *  - 进球：**三段渐强**（短-短-长），总时长 ≈1.1s，够明显；
     *  - 取消进球：**两下短震**（120/120），明显但更「轻」，不看成屏幕也能靠手感区分这两种事件。
     * 受 [UserPrefs.vibrate] 控制。
     */
    fun vibrate(ctx: Context, cancelled: Boolean = false) {
        if (!UserPrefs.vibrate.value) return
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        val timings = if (cancelled) {
            longArrayOf(0, 120, 110, 120)
        } else {
            longArrayOf(0, 220, 90, 220, 90, 450)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amps = if (cancelled) {
                intArrayOf(0, 200, 0, 200)
            } else {
                intArrayOf(0, 190, 0, 255, 0, 255)
            }
            runCatching {
                val effect = VibrationEffect.createWaveform(timings, amps, -1)
                // 🔴 必须用 **USAGE_ALARM**（Android 13 / API 33+）：
                //    默认 USAGE_UNKNOWN 的震动在「静音模式」下会被 VibratorService 直接吞掉
                //    （`shouldVibrateForUser` 判 ringer=Silent 就丢弃），而用户正是**息屏休息 +
                //    手机静音**的场景，那才是最需要被叫醒的时候。闹钟类震动不受静音/通知音量影响，
                //    只有「勿扰 - 完全静音」能拦（系统级，App 无法绕过）。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
                } else {
                    v.vibrate(effect)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(timings, -1)
        }
    }

    /**
     * 进球音乐：播**球探网比分直播页的默认进球提示音**，走**媒体音频通道**（`USAGE_MEDIA`）。
     *
     * 音源 = 内置 [R.raw.goal_sound]，就是 `https://live.titan007.com/bf_img/sound.mp3`
     * （网页里 `Config.sound=0` ⇒ `flash_sound[0]="bf_img/sound"` ⇒ 该文件，用户指定要用它）。
     *
     * 内置资源不依赖系统媒体库 ⇒ 不会再出现「开了进球音乐却完全没声音」。
     *
     * 🔴 音量为**用户说了算**（2026-11-02 修正）：
     *  - 走 `USAGE_MEDIA` ⇒ 音量键、下拉控制中心调的**媒体音量**直接生效，调到最小就小、拉到 0 就没声；
     *  - **绝不 `setStreamVolume`**：早前为了让静音也能听见，走了闹钟流并把闹钟音量强行抬到 50%，
     *    结果是「用户按音量键调到 0／静音，进球还在响」——这正是本次反馈的问题，已彻底删除。
     *  - 下面再兜一层 [shouldStaySilent]：媒体音量 0、或手机处于静音/振动模式，直接不播。
     *
     * 音效本身只有约 0.5 秒，所以循环播 [PLAY_MS] 毫秒（≈3 声）再停 —— 只响一记太容易漏听。
     * 受 [UserPrefs.sound]（设置里的「进球音乐」开关）控制。
     */
    fun playGoalSound(ctx: Context) {
        if (!UserPrefs.sound.value) return
        if (shouldStaySilent(ctx)) {
            android.util.Log.i("GoalAlert", "playGoalSound 跳过：系统音量为 0 或处于静音/振动模式")
            return
        }
        runCatching {
            val p = player ?: buildPlayer(ctx)?.also { player = it } ?: return
            // 同一拍多场进球：先掐掉上次的，再从头播（不叠音）
            runCatching { if (p.isPlaying) p.pause() }
            runCatching { p.seekTo(0) }
            runCatching { p.start() }
            // 留一条可查的日志：以后「没声音」时先看这里（start 结果 + 时长/循环状态）
            android.util.Log.i(
                "GoalAlert",
                "playGoalSound playing=${runCatching { p.isPlaying }.getOrDefault(false)} " +
                    "dur=${runCatching { p.duration }.getOrDefault(-1)}ms looping=${p.isLooping}"
            )
            // 重新计时：连播一段时间后自动停（MediaPlayer 是循环播放的）
            handler.removeCallbacks(stopLoopRunnable)
            handler.postDelayed(stopLoopRunnable, PLAY_MS)
        }
    }

    /**
     * 用户是否已经把手机调成「不想听声音」的状态 —— 是则本方法不播。
     *
     * 判据只读不写（**绝不改系统音量**）：
     *  1. 铃声模式不是 NORMAL（静音 / 仅振动）⇒ 不播。用户按电源键切静音、或控制中心点了静音，
     *     就是明确不想被打扰；此时还响是最招人烦的。
     *  2. 媒体音量为 0（音量键按到底 / 控制中心把媒体拉到 0）⇒ 不播。
     *
     * 注意：这里**不看**闹钟音量 / 通知音量 —— 本 App 的提示音走媒体流，用户调的那条就是媒体流，
     * 拿别的流的数值来判断只会让「明明关了还响」再次发生。
     */
    private fun shouldStaySilent(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return@runCatching false
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return@runCatching true
        am.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0
    }.getOrDefault(false)

    /**
     * 构建播放器（一次性，之后缓存复用）。
     *
     * 🔴 必须**持有引用**（[player]）：只被局部变量引用时，播到一半被 GC 会打出
     * `MediaPlayer finalized without being released` 并直接哑掉（老版本踩过）。
     *
     * `openRawResourceFd` 拿到的 fd 在 `use{}` 里关掉是安全的：`setDataSource(fd,…)`
     * 内部会 dup 一份，之后与 AssetFileDescriptor 无关。
     */
    private fun buildPlayer(ctx: Context): MediaPlayer? {
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_MEDIA ⇒ 走 STREAM_MUSIC：音量键/控制中心直接可调，关到底就没声
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            ctx.resources.openRawResourceFd(R.raw.goal_sound).use { afd ->
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            p.prepare()
            // 音效很短（~0.5 秒），靠循环撑够 PLAY_MS
            p.isLooping = true
            p.setVolume(1f, 1f)
            p
        } catch (t: Throwable) {
            // 不能静默吞：本机「进球音乐完全没声音」就是被这里的 catch 掩盖了（音源取不到）
            android.util.Log.w("GoalAlert", "buildPlayer 失败：${t.javaClass.simpleName} ${t.message}")
            runCatching { p.release() }
            null
        }
    }

    /** 到时停播（用 pause 而不是 stop：stop 之后必须 prepare 才能再播，代价更大）。 */
    private val stopLoopRunnable = Runnable {
        runCatching {
            player?.let {
                if (it.isPlaying) it.pause()
                it.seekTo(0)
            }
        }
    }

    /** 主线程计时器（只用来「到点停播」，不依赖 composition 存活）。 */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 进球提示音播放器（缓存复用，见 [playGoalSound]）。 */
    private var player: MediaPlayer? = null

    /** 音效只有约 0.5 秒，循环播这么久（≈3 声）才够醒目。 */
    private const val PLAY_MS = 1_800L
}
