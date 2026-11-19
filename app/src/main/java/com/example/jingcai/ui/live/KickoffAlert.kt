package com.example.jingcai.ui.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.jingcai.MainActivity
import com.example.jingcai.R
import com.example.jingcai.data.model.LiveMatch
import com.example.jingcai.data.repository.AppContextHolder
import com.example.jingcai.data.repository.UserPrefs
import java.util.Calendar

/**
 * 「比赛快开始了」提醒。
 *
 * ## 为什么单独一条链路，而不是塞进进球提醒
 * 进球提醒是**事件驱动**的（比分变了才发），开赛提醒是**时间驱动**的（到点就发），
 * 数据源也不同：前者必须联网拉 change.xml，后者只用本地时间就能判——省掉一次网络请求。
 * 但它俩共用同一个保活进程（[GoalWatchService] 的 wakelock 让协程不被冻住），
 * 否则息屏状态下「到点提醒」这条路径会被 ROM 一起冻死（和进球提醒同一个坑）。
 *
 * ## 提醒窗口
 * 只在「距开赛 `0 < diff ≤ lead` 分钟」这个窗口内提醒一次。窗口之外一律不动：
 *  - `diff ≤ 0`：已经开赛了，再提醒是噪音（用户此刻多半已经在看）；
 *  - `diff > lead`：还早，等到快开赛再说。
 *
 * 因此 App 在比赛开始前 3 分钟才被打开、或者一直挂着，**都会**在窗口内提醒一次。
 *
 * ## 去重
 * 落盘 key 是 `titanId@开赛时间戳`（见 [UserPrefs.kickoffDone]）：
 *  - 用 titanId 保证同一场不会每轮轮询都弹一次（轮询是 5 秒一轮，不去重会刷屏）；
 *  - 带上时间戳，是为了让**改期**的场次能按新时间再提醒一次。
 *
 * 🔴 去重集合只保留「当前仍在候场的场次」，已开赛 / 已取消关注的 key 每轮清掉，
 *    否则这个 Set 会随赛季无限增长（每天几十场，一年就是上万条写进 prefs）。
 */
object KickoffAlert {

    /** 独立渠道：用户可以在系统设置里只关开赛提醒、保留进球提醒。 */
    const val CHANNEL = "kickoff_alert"

    private const val TAG = "KickoffAlert"

    /** 通知 id 段（8100 起，与进球提醒的 7000+、锚点的 7002 不冲突）。 */
    private const val NOTIF_BASE = 8100

    /**
     * 现在是否该为这场发提醒。
     *
     * 纯函数（[now] 可注入）⇒ debug 自检可直接断言边界，不依赖系统时钟。
     *
     * @param kickoffAt 开赛时间戳（毫秒）
     * @param now 当前时间戳
     * @param leadMs 提前量（毫秒）
     */
    fun inWindow(kickoffAt: Long, now: Long, leadMs: Long): Boolean {
        val diff = kickoffAt - now
        return diff > 0 && diff <= leadMs
    }

    /**
     * 把源站的 `HH:mm` 解析成**今天最近一次**的开赛时间戳。
     *
     * ## 跨零点修正（必须做，否则凌晨场永远不提醒）
     * 源站的 `time` 只有时分、不带日期。今天 23:50 打开 App 看一场「02:00」的比赛，
     * 直接按「今天 02:00」算会得到一个 22 小时前的时间戳 ⇒ 判定为「早就开赛了」⇒ 永不提醒。
     * 规则：算出来的时间戳比现在早 6 小时以上，就认定它是次日凌晨场，+24 小时。
     * 6 小时这个阈值来自足球赛程形态——凌晨场最多提前 6 小时挂出来，
     * 而「上半场刚开赛 80 分钟」这种真正已经开始的场次不会误判（它的 diff 是负的且不足 6 小时）。
     *
     * @return 解析失败（源站给的是 `-` 等占位）返回 null，调用方跳过即可。
     */
    fun kickoffAt(time: String, now: Long = System.currentTimeMillis()): Long? {
        val m = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(time) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val mi = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || mi !in 0..59) return null
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, mi)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val t = c.timeInMillis
        return if (t < now - CROSS_DAY_TOLERANCE_MS) t + DAY_MS else t
    }

    /** 判定「这是次日凌晨场」的容差：比现在早 6 小时以上就当成明天。 */
    private const val CROSS_DAY_TOLERANCE_MS = 6 * 60 * 60_000L
    private const val DAY_MS = 24 * 60 * 60_000L

    /**
     * 检查一轮（**不联网**，只用传入的比赛时间）。
     *
     * 调用点：
     *  - 后台：[BackgroundGoalWatcher.fullRound] 已经拉到了整页数据，顺手调；
     *  - 前台：[com.example.jingcai.ui.viewmodel.LiveViewModel] 的 5 秒 tick。
     *
     * 两处都调同一个函数，靠落盘去重保证同一次开赛只会弹一条通知。
     *
     * @return 本轮实际发出的提醒条数（日志/自检用）
     */
    fun check(
        matches: Collection<LiveMatch>,
        now: Long = System.currentTimeMillis()
    ): Int {
        if (!UserPrefs.kickoffAlert.value) return 0
        val followed = UserPrefs.followed.value
        if (followed.isEmpty()) return 0
        val leadMs = UserPrefs.kickoffLeadMin.value * 60_000L
        val done = UserPrefs.kickoffDone().toMutableSet()
        // 本轮「仍可能候场」的 key：state==0 的关注场次。其余一律从去重集合里清掉。
        val alive = HashSet<String>()
        var sent = 0
        for (m in matches) {
            if (m.state != 0 || m.titanId !in followed) continue
            val kick = kickoffAt(m.time, now) ?: continue
            val key = "${m.titanId}@$kick"
            alive += key
            if (key in done) continue
            if (!inWindow(kick, now, leadMs)) continue
            if (post(m, kick, now)) sent++
            done += key
        }
        // 清理不再候场的 key（开赛了 / 取消关注了 / 改期了）
        if (done.size != alive.size || !done.containsAll(alive)) {
            UserPrefs.setKickoffDone(done.filterTo(HashSet()) { it in alive })
        }
        if (sent > 0) android.util.Log.i(TAG, "发出 $sent 条开赛提醒")
        return sent
    }

    /** 建渠道（幂等）。与进球提醒分开，用户才能只留其中一个。 */
    fun ensureChannel(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL, "开赛提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
                description = "关注的比赛开始前提醒"
            }
        )
    }

    /**
     * 发一条开赛通知。点击回到比分直播页（和进球提醒同一个落地页）。
     *
     * @return 是否真的发出去了（没通知权限时返回 false，**不写去重集合**，
     *   这样用户下次打开 App 授权后还能补上这一条，而不是永久丢掉）。
     */
    private fun post(m: LiveMatch, kickoffAt: Long, now: Long): Boolean {
        val ctx = runCatching { AppContextHolder.ctx }.getOrNull() ?: return false
        if (!GoalNotifier.canPost(ctx)) return false
        ensureChannel(ctx)
        val leftMin = ((kickoffAt - now) / 60_000L).toInt().coerceAtLeast(1)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = "open_live"
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "⏰ ${leftMin} 分钟后开赛"
        val text = listOf(m.league, "${m.home} vs ${m.away}")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setDefaults(0)
            .build()
        return runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
            nm.notify(NOTIF_BASE + (m.titanId % 1000).toInt(), notif)
            true
        }.getOrDefault(false)
    }
}
