package com.example.jingcai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jingcai.data.repository.AppContextHolder
import com.example.jingcai.data.repository.BetTicketStore
import com.example.jingcai.data.repository.UserPrefs
import com.example.jingcai.data.repository.ThemeMode
import com.example.jingcai.ui.bet.BetBillScreen
import com.example.jingcai.ui.bet.BetLiveScreen
import com.example.jingcai.ui.bet.BetNotify
import com.example.jingcai.ui.bet.BetTicketDetailScreen
import com.example.jingcai.ui.bet.BetWinDialog
import com.example.jingcai.ui.bet.BetScreen
import com.example.jingcai.ui.bet.BetSettleRunner
import com.example.jingcai.ui.bet.formatTicketForShare
import com.example.jingcai.ui.follow.FollowScreen
import com.example.jingcai.ui.league.LeagueScreen
import com.example.jingcai.ui.live.AppForegroundState
import com.example.jingcai.ui.live.GoalEvent
import com.example.jingcai.ui.live.GoalDeliverer

import com.example.jingcai.ui.live.BackgroundGoalWatcher
import com.example.jingcai.ui.live.GoalAlarmScheduler
import com.example.jingcai.ui.live.GoalNotifier
import com.example.jingcai.ui.live.GoalKeepAliveService
import com.example.jingcai.ui.live.GoalWatchService
import com.example.jingcai.ui.live.LiveScreen
import com.example.jingcai.ui.live.SpfChangeScreen
import com.example.jingcai.ui.LiveViewModel
import com.example.jingcai.ui.livedetail.BbWebDetailScreen
import com.example.jingcai.ui.analysis.AnalysisScreen
import com.example.jingcai.ui.livedetail.LiveDetailScreen
import com.example.jingcai.ui.match.MatchScreen
import com.example.jingcai.ui.oddschange.OddsChangeScreen
import com.example.jingcai.ui.recentform.RecentFormScreen
import com.example.jingcai.ui.sameodds.SameOddsScreen
import com.example.jingcai.ui.schedule.ScheduleScreen
import com.example.jingcai.ui.nav.NavIcons
import com.example.jingcai.ui.theme.JingCaiTheme
import com.example.jingcai.ui.theme.LineSoft
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.update.UpdateHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 深链状态：状态栏进球通知点击后跳到比分直播页（冷启动 / 已在前台都能响应）。 */
object AppNavState {
    val openLive = MutableStateFlow(false)
    /** 派奖/降赔通知点击后跳到投注账本（与 [openLive] 同构）。 */
    val openLedger = MutableStateFlow(false)
    /**
     * 派奖/结算通知点击后要打开的**注单 id**（`null` = 无请求）。
     *
     * 与 [openLedger] 分开而不是塞一个 union：语义不同 —— 一个是「去账本看全部」，
     * 一个是「直接看这一单」。值为空时走账本那条路。
     */
    val openTicket = MutableStateFlow<String?>(null)
    /**
     * **仅调试包**的闪色演示请求（`null` = 无请求，`0` = 进球闪色，`1` = 取消进球闪色）。
     * 由 [MainActivity.demoGoalIfRequested] 写入、比分直播页消费，用于回归队名底色的两种颜色。
     */
    val debugFlash = MutableStateFlow<Int?>(null)
}

class MainActivity : ComponentActivity() {

    /**
     * 「屏幕亮起」监听：息屏期间进程被 ROM 冻结、进球推不出去，只能等**开屏解冻那一刻**
     * 立刻做一次全量比对，把漏掉的进球补成通知（见 [BackgroundGoalWatcher.onScreenOn]）。
     * `ACTION_SCREEN_ON` 是 protected broadcast，只能动态注册。
     */
    private val screenOnReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_ON) BackgroundGoalWatcher.onScreenOn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局 Context / 用户偏好 / 通知渠道初始化（必须在任何通知/震动之前）
        AppContextHolder.init(applicationContext)
        UserPrefs.init(applicationContext)
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenOnReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_ON),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 🔴 顺序不能反：先把 `goal_service` 渠道压成 IMPORTANCE_NONE，再启动锚点服务。
        //    反了的话服务会先拿着「可见」的渠道挂上通知，状态栏会闪一下「后台运行」。
        GoalNotifier.ensureChannels(applicationContext)
        // 模拟投注的派奖 / 降赔渠道（独立于进球渠道，用户可单独关）
        BetNotify.ensureChannels(applicationContext)
        // 冷启动是「App 在前台」的窗口期，是唯一能合法启动前台服务的时机（Android 12+ 限制后台启动）。
        // 服务起来后常驻，负责撑住进程不被冻结，否则息屏 5 秒后轮询就停摆（见 GoalWatchService）。
        GoalWatchService.sync(applicationContext)
        // 息屏增强的「自愈」：**只在用户明确开过之后**才做，且必须本机授过 WRITE_SECURE_SETTINGS。
        // 🔴 「可选增强」口径（2026-11-04）：keepAliveWanted 默认 false ⇒ 新装 / 没表过态的机器
        //    冷启动**绝不碰无障碍**，功能照常以「前台 + 亮屏」能力工作；用户点过开启才算授权，
        //    点过关闭也不会被偷偷开回来。别把这里改成无条件调用。
        runCatching {
            GoalKeepAliveService.ensureEnabledQuietly(
                applicationContext,
                UserPrefs.goalAlert.value && UserPrefs.keepAliveWanted.value
            )
        }
        // 冷启动：通知 PendingIntent 带 action=open_live / open_bet_ledger / open_bet_ticket
        handleNavIntent(intent)
        demoGoalIfRequested(intent)
        // **仅调试包**：`-e demo_dark 1|0` 强制深色/浅色，用来在系统仍是浅色时验证深色配色。
        // 正常路径走用户偏好（资料页「外观」三选），默认为跟随系统。
        val darkOverride: Boolean? =
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                intent?.getStringExtra("demo_dark")?.let { it == "1" }
            else null
        setContent {
            // 外观偏好在这里落地：切 StateFlow ⇒ 这里重组 ⇒ 整棵树换色，且**不重建 Activity**
            // （重建会丢导航栈，用户正看着的页会跳回首页）。
            val themeMode by UserPrefs.themeMode.collectAsState()
            val dark = darkOverride ?: when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.FOLLOW -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            JingCaiTheme(dark = dark) {
                AppNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleNavIntent(intent)
        demoGoalIfRequested(intent)
    }

    /**
     * 状态栏通知点击的分发口（冷启动 [onCreate] 与热启动 [onNewIntent] 共用）。
     *
     * ## 三条目标
     * - `open_live` → 比分直播；
     * - `open_bet_ledger` → 投注账本（多注结算合并成一条通知时走这里）；
     * - `open_bet_ticket` → **该注单的详情页**（单注结算，见 `BetNotify.openAppIntent`）。
     *
     * 🔴 用了 `FLAG_ACTIVITY_SINGLE_TOP or CLEAR_TOP` + `launchMode` 默认，热启动一定走
     *    [onNewIntent]；冷启动只走 [onCreate]。两处都要分发，漏一处就是「点通知没反应」。
     */
    private fun handleNavIntent(intent: Intent?) {
        when (intent?.action) {
            "open_live" -> AppNavState.openLive.value = true
            "open_bet_ledger" -> AppNavState.openLedger.value = true
            "open_bet_ticket" -> {
                intent.getStringExtra("ticket_id")?.takeIf { it.isNotBlank() }?.let {
                    AppNavState.openTicket.value = it
                } ?: run { AppNavState.openLedger.value = true }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOnReceiver) }
        super.onDestroy()
    }

    /** 调试用：发一条假的派奖通知，给「派奖通知」这一形态截图（release 不含此路径）。 */
    private fun demoPayoutNotify() {
        val p = com.example.jingcai.data.model.BetPick(
            matchId = 1, sport = "FOOTBALL", num = "周五001", league = "澳超",
            home = "布里斯班", away = "奥克兰FC", date = "2026-10-30", time = "17:00",
            poolCode = "had", poolTitle = "胜平负", lineLabel = "0", key = "h",
            label = "主胜", odds = 2.55
        )
        val t = com.example.jingcai.data.model.BetTicket(
            id = "demo", createdAt = System.currentTimeMillis(), times = 1,
            picks = listOf(p), parlayM = 1, payout = 14.69,
            statusRaw = com.example.jingcai.data.model.TicketStatus.WON,
            stakeLocked = 2.0, settledAt = System.currentTimeMillis()
        )
        // 🔴 必须**真写进注单库**：详情页是按 id 查库的，只造内存对象的话三处跳转全落到
        //    「这条注单不存在」的兜底卡上，看起来就像「没跳到订单详情」。先删再插保证幂等。
        BetTicketStore(applicationContext).let {
            it.removeTicket(t.id)
            it.appendTicket(t)
        }
        BetNotify.notifyPayout(listOf(t), 14.69)
        // 同一条命令顺带演示 App 内「中奖啦」弹窗（走真实事件通道，不是特制的假 UI）
        BetSettleRunner.winEvent.value = BetSettleRunner.WinEvent(listOf(t), 14.69)
    }

    /** 调试用：发一条假的降赔提醒。 */
    private fun demoOddsNotify() {
        BetNotify.notifyOddsDown(listOf(Triple("布里斯班 主胜", 2.55, 2.10)))
    }

    /**
     * 调试用：把库里真实注单的**复制文本**原样打到 logcat。
     *
     * 🔴 为什么需要这个口子：Android 10+ 禁止后台读剪贴板（`adb shell cmd clipboard get` 直接拒），
     * 「复制出去的文本对不对」在自动化里没有别的观测点。用户 2026-11-06 报的正是复制文本
     * （`3x1+4x1+5x1` 被压成 `5x1`），所以用真库注单跑一遍 [formatTicketForShare]，
     * 打出来的就是剪贴板里会有的字，可 grep、可回归：
     * ```
     * adb shell am start -n com.example.jingcai/.MainActivity -e demo_share 1
     * adb logcat -d -s JingcaiShare
     * ```
     */
    private fun demoShareDump() {
        val tickets = BetTicketStore(applicationContext).loadTickets()
        if (tickets.isEmpty()) {
            android.util.Log.i("JingcaiShare", "库里没有注单，先投一单再来")
            return
        }
        tickets.sortedByDescending { it.createdAt }.forEach { t ->
            android.util.Log.i(
                "JingcaiShare",
                "=== id=${t.id} ms=${t.ms} picks=${t.picks.size} x${t.times} ===\n" +
                    formatTicketForShare(t)
            )
        }
    }

    /**
     * 调试用：造一张**同场同玩法复选**的待开奖单（半全场「胜平」18 +「负平」20，15 倍 = 60 元）。
     *
     * 🔴 为什么需要这个样本：用户 2026-11-06 报的正是这种票的奖金口径 —— 两个档互斥、
     *    只可能中其中一个，所以「最高可中」必须是 600（中负平）而不是 540+600=1140。
     *    真实注单里很难手动凑出同玩法复选，只能造一张用来盯 UI。
     * ```
     * adb shell am start -n com.example.jingcai/.MainActivity -e demo_hafu 1
     * ```
     * 只写本机库、状态是待开奖；验证完在账单页等它满 24 小时自动清掉（手动删已下线）。
     */
    private fun demoHafuTicket() {
        fun leg(key: String, label: String, odds: Double) = com.example.jingcai.data.model.BetPick(
            matchId = 900001, sport = "FOOTBALL", num = "周六001", league = "英超",
            home = "阿森纳", away = "切尔西", date = "2099-01-01", time = "23:00",
            poolCode = "hafu", poolTitle = "半全场", lineLabel = "0", key = key,
            label = label, odds = odds
        )
        val t = com.example.jingcai.data.model.BetTicket(
            id = "demo_hafu",
            createdAt = System.currentTimeMillis(),
            times = 15,
            picks = listOf(leg("s1", "胜平", 18.0), leg("s2", "负平", 20.0)),
            parlayM = 1,
            parlayMs = listOf(1)
        )
        BetTicketStore(applicationContext).let {
            it.removeTicket(t.id)
            it.appendTicket(t)
        }
        android.util.Log.i("JingcaiShare", "demo_hafu 写入：stake=${t.stake} span=${com.example.jingcai.data.settle.BetCalculator.payoutSpan(t)}")
    }

    /**
     * 调试用：造三条账单样本，真机核对「已开奖只留 24 小时」这条口径。
     * ```
     * adb shell am start -n com.example.jingcai/.MainActivity -e demo_bill 1
     * ```
     * 三条分别是：
     *  - `demo_bill_won`     已开奖 **1 小时前** ⇒ 账单里应**看得见**，带派彩金额；
     *  - `demo_bill_old`     已开奖 **25 小时前** ⇒ 应被 `BetRetention` 自动清掉，**看不见**；
     *  - `demo_bill_pending` 待开奖 ⇒ 应一直看得见；
     *  - `demo_bill_lost`    已开奖 2 小时前（未中奖）⇒ 看得见，用来盯「未中」那套样式。
     *
     * 🔴 只写调试包（[demoGoalIfRequested] 里有 debuggable 判断）：这三条是有意为之的
     *    过期数据，绝不能进正式包。重复执行是幂等的（先按 id 删再插）。
     */
    private fun demoBillSamples() {
        val now = System.currentTimeMillis()
        val h = 60L * 60 * 1000

        fun leg(
            matchId: Long, num: String, home: String, away: String,
            key: String, label: String, odds: Double,
            result: com.example.jingcai.data.model.LegResult? = null
        ) = com.example.jingcai.data.model.BetPick(
            matchId = matchId, sport = "FOOTBALL", num = num, league = "英超",
            home = home, away = away, date = "2026-11-10", time = "20:00",
            poolCode = "had", poolTitle = "胜平负", lineLabel = "0",
            key = key, label = label, odds = odds, legResult = result
        )

        val won = com.example.jingcai.data.model.BetTicket(
            id = "demo_bill_won", createdAt = now - h, times = 1,
            picks = listOf(
                leg(910001, "周一001", "曼城", "阿森纳", "h", "主胜", 1.85,
                    com.example.jingcai.data.model.LegResult.WIN)
            ),
            parlayM = 1, parlayMs = listOf(1),
            statusRaw = com.example.jingcai.data.model.TicketStatus.WON,
            stakeLocked = 2.0, payout = 3.70, settledAt = now - h,
            scores = mapOf("910001" to "2:1|1:0")
        )
        val old = com.example.jingcai.data.model.BetTicket(
            id = "demo_bill_old", createdAt = now - 26 * h, times = 1,
            picks = listOf(
                leg(910002, "周一002", "利物浦", "埃弗顿", "a", "客胜", 3.10,
                    com.example.jingcai.data.model.LegResult.LOSE)
            ),
            parlayM = 1, parlayMs = listOf(1),
            statusRaw = com.example.jingcai.data.model.TicketStatus.LOST,
            stakeLocked = 2.0, payout = 0.0, settledAt = now - 25 * h,
            scores = mapOf("910002" to "1:0|1:0")
        )
        val lost = com.example.jingcai.data.model.BetTicket(
            id = "demo_bill_lost", createdAt = now - 3 * h, times = 1,
            picks = listOf(
                leg(910004, "周一004", "切尔西", "纽卡斯尔", "h", "主胜", 2.40,
                    com.example.jingcai.data.model.LegResult.LOSE)
            ),
            parlayM = 1, parlayMs = listOf(1),
            statusRaw = com.example.jingcai.data.model.TicketStatus.LOST,
            stakeLocked = 2.0, payout = 0.0, settledAt = now - 2 * h,
            scores = mapOf("910004" to "0:2|0:1")
        )
        val pending = com.example.jingcai.data.model.BetTicket(
            id = "demo_bill_pending", createdAt = now, times = 2,
            picks = listOf(leg(910003, "周二003", "热刺", "西汉姆", "h", "主胜", 2.05)),
            parlayM = 1, parlayMs = listOf(1),
            statusRaw = com.example.jingcai.data.model.TicketStatus.PENDING,
            stakeLocked = 4.0
        )

        BetTicketStore(applicationContext).let { s ->
            listOf(won, old, lost, pending).forEach {
                s.removeTicket(it.id)
                s.appendTicket(it)
            }
            val left = s.loadTickets().map { it.id }
            android.util.Log.i(
                "JingcaiBill",
                "demo_bill 写入后库内=${left.size} 条：$left（old 应已被清理，不在列表里）"
            )
        }
    }

    /**
     * 调试用：发一条**未中奖 / 已结算**通知。
     *
     * 之前只有 `demo_payout`（中奖），「未中奖通知 → 该单详情」这条深链一直没被真机点过 ——
     * 两个分支虽然共用 `openAppIntent`，但 `notifyPayout` 内部走的是另一个 builder，
     * 只有真发一次才能确认 `setContentIntent` 没漏。同样只写进调试包。
     */
    private fun demoLostNotify() {
        val p = com.example.jingcai.data.model.BetPick(
            matchId = 2, sport = "FOOTBALL", num = "周五002", league = "欧冠",
            home = "水晶宫", away = "霍芬海姆", date = "2026-11-06", time = "01:45",
            poolCode = "had", poolTitle = "胜平负", lineLabel = "0", key = "a",
            label = "客胜", odds = 3.45
        )
        val t = com.example.jingcai.data.model.BetTicket(
            id = "demo_lost", createdAt = System.currentTimeMillis(), times = 1,
            picks = listOf(p), parlayM = 1, payout = 0.0,
            statusRaw = com.example.jingcai.data.model.TicketStatus.LOST,
            stakeLocked = 2.0, settledAt = System.currentTimeMillis()
        )
        // 与 demoPayoutNotify 同口径：真写进库，否则详情页只会显示「这条注单不存在」
        BetTicketStore(applicationContext).let {
            it.removeTicket(t.id)
            it.appendTicket(t)
        }
        BetNotify.notifyPayout(listOf(t), 0.0)
    }

    /**
     * WebView 探针（仅调试包）。`-e probe_web 2997481`
     *
     * 目的：确认源站 WAF 是否只放行 Chromium 的 TLS/HTTP2 指纹 —— OkHttp 四种组合全被 RST，
     * 但手机浏览器能正常打开，所以用 WebView 复核。页面加载成功后落盘标题与正文长度。
     */
    private fun probeAnalysisWeb(id: String) {
        val out = java.io.File(getExternalFilesDir(null), "ana_web_$id.txt")
        runOnUiThread {
            try {
                val wv = android.webkit.WebView(this)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; V2227A) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                fun finish(msg: String) {
                    if (!done.compareAndSet(false, true)) return
                    runCatching { out.writeText(msg, Charsets.UTF_8) }
                    android.util.Log.i("JingcaiAnalysis", "web: $msg")
                }
                wv.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        view.evaluateJavascript(
                            "(function(){var b=document.body?document.body.innerText:'';" +
                                "return JSON.stringify({title:document.title,len:b.length,head:b.slice(0,160)})})()"
                        ) { v -> finish(v ?: "null") }
                    }

                    override fun onReceivedError(
                        view: android.webkit.WebView,
                        req: android.webkit.WebResourceRequest,
                        err: android.webkit.WebResourceError
                    ) {
                        if (req.isForMainFrame) {
                            finish("ERR ${err.errorCode} ${err.description} url=${req.url}")
                        }
                    }
                }
                wv.loadUrl("https://zq.titan007.com/analysis/${id}cn.htm")
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ finish("TIMEOUT 20s") }, 20_000)
            } catch (e: Exception) {
                runCatching { out.writeText("EX ${e.javaClass.simpleName}: ${e.message}", Charsets.UTF_8) }
            }
        }
    }

    /**
     * 分析页 HTML 探针（仅调试包）。`-e probe_ana 2997481`
     *
     * 落盘到 `Android/data/com.example.jingcai/files/ana_<id>.htm`，便于 `adb pull` 后写解析器。
     * 同时把 长度 / 前 120 字 / 异常 打到 logcat（vivo 常常吞掉，落盘是主证据）。
     */
    private fun probeAnalysisHtml(id: String) {
        Thread {
            val out = java.io.File(getExternalFilesDir(null), "ana_$id.htm")
            val sb = StringBuilder()
            val url = "https://zq.titan007.com/analysis/${id}cn.htm"
            val browserUa = "Mozilla/5.0 (Linux; Android 13; V2227A) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            // 组合矩阵：UA × 协议。源站 WAF 对 okhttp UA / h2 都可能直接 RST，逐组试出可用组合。
            val combos = listOf(
                "okhttp+h2" to ("okhttp/4.12.0" to listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1)),
                "okhttp+h1" to ("okhttp/4.12.0" to listOf(okhttp3.Protocol.HTTP_1_1)),
                "browser+h2" to (browserUa to listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1)),
                "browser+h1" to (browserUa to listOf(okhttp3.Protocol.HTTP_1_1))
            )
            var saved = false
            for ((tag, cfg) in combos) {
                val (ua, protocols) = cfg
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .protocols(protocols)
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", ua)
                        .header("Referer", "https://zq.titan007.com/")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        sb.append("[$tag] ${resp.code} ${resp.protocol} len=${bytes.size}\n")
                        if (resp.isSuccessful && bytes.isNotEmpty() && !saved) {
                            out.writeBytes(bytes)
                            saved = true
                        }
                    }
                } catch (e: Exception) {
                    sb.append("[$tag] ERR ${e.javaClass.simpleName}: ${e.message}\n")
                }
            }
            if (!saved) out.writeText(sb.toString(), Charsets.UTF_8)
            android.util.Log.i("JingcaiAnalysis", sb.toString())
        }.start()
    }

    /**
     * **仅调试包**的进球/取消进球弹窗演示钩子（release 包不生效）：
     * ```
     * adb shell am start -n com.example.jingcai/.MainActivity -a open_live \
     *   -e demo_goal "'霍芬海姆|2-1|沃尔夫斯堡|德甲|1'"
     * ```
     * 字段 = 主队|比分|客队|联赛|进球方(1主/2客)|是否取消(1=取消进球)，2 秒后走真实交付链路
     * [GoalDeliverer]（状态栏通知 + 页内卡片），用于给卡片截图/回归，不参与任何真实业务。
     * 多个事件用 `;;` 分隔可一次演示多场：`-e demo_goal "'A|1-0|B|英超|1;;C|2-2|D|西甲|2'"`。
     */
    private fun demoGoalIfRequested(intent: Intent?) {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        // 投注通知演示（**仅调试包**），用来给派奖 / 降赔两种通知截图，不参与任何业务：
        //   adb shell am start -n com.example.jingcai/.MainActivity -e demo_payout 1
        //   adb shell am start -n com.example.jingcai/.MainActivity -e demo_lost 1
        //   adb shell am start -n com.example.jingcai/.MainActivity -e demo_odds 1
        //   adb shell am start -n com.example.jingcai/.MainActivity -e demo_share 1   ← 复制文本打到 logcat
        //   adb shell am start -n com.example.jingcai/.MainActivity -e demo_hafu 1    ← 写入一张同玩法复选样本单
        //   adb shell am start -n com.example.jingcai/.MainActivity -e demo_bill 1    ← 写入账单三态样本（含一张 25 小时前开奖的，应被自动清掉）
        //   adb shell am start -n com.example.jingcai/.MainActivity -e demo_update available   ← 更新弹窗四态预览
        //     （available / downloading / ready / failed）
        when {
            intent?.getStringExtra("demo_payout") == "1" -> demoPayoutNotify()
            intent?.getStringExtra("demo_lost") == "1" -> demoLostNotify()
            intent?.getStringExtra("demo_odds") == "1" -> demoOddsNotify()
            intent?.getStringExtra("demo_share") == "1" -> demoShareDump()
            intent?.getStringExtra("demo_hafu") == "1" -> demoHafuTicket()
            intent?.getStringExtra("demo_bill") == "1" -> demoBillSamples()
        }
        // 分析页 HTML 探针（仅调试包）：-e probe_ana 2997481
        // 抓 zq.titan007.com/analysis/<id>cn.htm 存到外部 files 目录，用于确认可达性 + 拿真实 DOM 写解析器。
        intent?.getStringExtra("probe_ana")?.let { probeAnalysisHtml(it) }
        // WebView 探针（仅调试包）：-e probe_web 2997481
        intent?.getStringExtra("probe_web")?.let { probeAnalysisWeb(it) }
        // 闪色演示：-e demo_flash "1"（1=取消进球闪色，0=进球闪色），给当前第一场进行中的比赛打 30 秒底色
        intent?.getStringExtra("demo_flash")?.toIntOrNull()?.let { AppNavState.debugFlash.value = it }
        // 更新弹窗样式预览（仅调试包）：-e demo_update available|downloading|ready|failed
        intent?.getStringExtra("demo_update")?.let {
            com.example.jingcai.data.update.UpdateManager.demoPreview(it)
        }
        val raw = intent?.getStringExtra("demo_goal") ?: return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        // 首次投递的延时（毫秒）。回归「息屏能不能收到」时用 `-e demo_delay 20000`：
        // 冷启动 → 立刻 HOME + 息屏 → 到点触发时 App 已在后台且屏幕已关，正是要验的场景。
        val baseDelay = intent.getStringExtra("demo_delay")?.toLongOrNull() ?: 2_000L
        // 多个事件用 ";;" 分隔，彼此间隔 700ms 依次投递 —— 用来回归「多场进球时卡片层叠」。
        // 注意：am start 打到已在栈顶的实例时不保证回调 onNewIntent，回归请先 force-stop 再冷启动。
        raw.split(";;").forEachIndexed { i, one ->
            val p = one.split("|")
            if (p.size < 4) return@forEachIndexed
            val e = GoalEvent(
                titanId = 999_999L,
                home = p[0],
                away = p[2],
                score = p[1],
                side = p.getOrNull(4)?.toIntOrNull() ?: 1,
                league = p[3],
                cancelled = p.getOrNull(5)?.toIntOrNull() == 1
            )
            handler.postDelayed(
                { if (e.cancelled) GoalDeliverer.onCancel(e) else GoalDeliverer.onGoal(e) },
                baseDelay + i * 700L
            )
        }
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val context = LocalContext.current

    /**
     * 当前要展示的注单详情 id（`null` = 没打开）。
     *
     * 🔴 **详情是一层覆盖，不是 nav 路由**。三条入口——状态栏通知、中奖弹窗「立即查看」、
     * 投注记录「订单详情 ›」——原本都走 `navigate("betticket/{id}")`，实测点下去没反应：
     * 通知冷启动时 navigate 跑在 NavHost 首帧恢复之前，栈随后被恢复成 `match` 覆盖掉。
     * 换成这个纯状态后与 NavController 完全解耦，三个入口共用一条路，返回也天然回到用户原来那一页。
     */
    var detailTicketId by remember { mutableStateOf<String?>(null) }

    // 自动更新宿主：冷启动静默检查 GitHub Release，发现新版才弹窗。
    // 它自身不产出任何布局节点（Idle 时直接返回），所以放在这里不影响根布局。
    // 🔴 检查完全静默失败：GitHub 不通时用户不会有任何感知，也不会被反复打扰。
    UpdateHost()

    // 比分直播 VM（activity 作用域，与页面内同一实例）：5 秒 tick 由 app 前台生命周期驱动。
    // 🔴 前台服务**不能**在 ON_STOP 里首次启动（targetSdk 34 ⇒ Android 12+ 禁止后台启动前台服务，
    //    会抛 ForegroundServiceStartNotAllowedException）。所以只在 onCreate / ON_START 这些
    //    「App 还在前台」的窗口期拉起 [GoalWatchService]；它起来后**常驻**，
    //    唯一作用是撑住进程不被 cgroup freezer 冻住（实测息屏 5 秒后 cgroup.freeze=1，
    //    后台轮询协程直接停摆，进球一条通知都发不出来）。
    //    它挂的通知走 `goal_service` 渠道、importance=NONE ⇒ 状态栏**看不到**，
    //    「状态栏只留进球提示」仍然成立（渠道每次启动自愈重置，防 ROM 覆盖安装后回弹）。
    val liveVm = viewModel<LiveViewModel>()
    // 投注结算用的作用域：AppNav 是根组合，生命周期与应用一致
    val appScope = androidx.compose.runtime.rememberCoroutineScope()
    DisposableEffect(Unit) {
        val owner = ProcessLifecycleOwner.get()
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    AppForegroundState.foreground = true
                    liveVm.setActive()
                    // 建「进球提醒」渠道，并顺手把锚点渠道压回不可见（ROM 可能重置）
                    GoalNotifier.ensureChannels(context)
                    GoalWatchService.sync(context)
                    // 回到前台：闹钟唤醒取消（前台由 ViewModel 的 5s tick 负责）
                    GoalAlarmScheduler.cancel(context)
                    // 回到前台：后台轮询停掉，避免和 ViewModel 的 tick 双份请求
                    BackgroundGoalWatcher.sync()
                    // 模拟投注结算 / 降赔检查：回前台跑一轮（内部有互斥锁，重复触发安全）。
                    // 🔴 不做后台常驻：模拟投注不是实时业务，回到前台看一次足够，也省电。
                    appScope.launch { runCatching { BetSettleRunner.run(reason = "onStart") } }
                }
                Lifecycle.Event.ON_STOP -> {
                    AppForegroundState.foreground = false
                    liveVm.setInactive()
                    // 🔴 服务**不停**：它正是后台/息屏期间撑住进程的锚点。
                    //    只把进球判定交给进程内轮询（服务不参与业务逻辑）。
                    BackgroundGoalWatcher.sync()
                    // 🔴 关键：息屏后进程会被 ROM 冻住（前台服务也挡不住），
                    //    只能靠精确闹钟定时把进程叫醒跑一轮 —— 见 GoalAlarmScheduler。
                    GoalAlarmScheduler.schedule(context)
                }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // 用户半途关注比赛 / 打开任一提醒时，锚点服务要立刻起来（不必等下次冷启动）
    // 🔴 三个偏好都要看：只开「开赛提醒」时也得保活，否则息屏后进程被冻、到点提醒发不出去。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.combine(
            UserPrefs.followed, UserPrefs.goalAlert, UserPrefs.kickoffAlert
        ) { f, g, k -> f.isNotEmpty() && (g || k) }
            .collect { need -> GoalWatchService.sync(context, need) }
    }
    // 🔴 前台结算心跳：每 3 分钟扫一次「有没有注单该开奖了」。
    //
    // 为什么必须有：结算原本只在「回前台 / 进投注页 / 进账单页」三处触发，
    // 用户把 App 一直挂在前台看比分时，这三件事一件都不会发生 ——
    // 于是比赛打完了也不结算、更不会弹中奖弹窗（用户报障：「我一直开着 app，
    // 然后中奖了，他不自动弹出中奖弹窗」）。
    //
    // 为什么不会费流量：`BetSettleEngine.run()` 开头有预筛，没有任何腿「开赛已过 2 小时」
    // 时直接返回，**一个请求都不发**。注单都结算完之后这个循环等于空转。
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(SETTLE_TICK_MS)
            // 退后台不跑：后台没有网络权限保证，也会和进球提醒的轮询抢电。
            // 回前台时 ON_START 那条会立刻补跑一次，不会漏。
            if (AppForegroundState.foreground) {
                runCatching { BetSettleRunner.run(reason = "tick") }
            }
        }
    }
    // 状态栏进球通知点击 → 跳到比分直播页
    LaunchedEffect(Unit) {
        AppNavState.openLive.collect { if (it) { AppNavState.openLive.value = false; nav.go("live") } }
    }
    // 派奖 / 降赔通知点击 → 跳到投注账单
    LaunchedEffect(Unit) {
        AppNavState.openLedger.collect {
            if (it) { AppNavState.openLedger.value = false; nav.navigate("betbill") }
        }
    }
    // 单注派奖 / 结算通知点击 → **该注单的详情**（见 [detailTicketId] 的 KDoc：不走路由）。
    LaunchedEffect(Unit) {
        AppNavState.openTicket.collect { id ->
            if (id != null) {
                // 立刻清空事件源，避免任何一次重组把它再消费一遍
                AppNavState.openTicket.value = null
                detailTicketId = id
            }
        }
    }
    // 中奖弹窗（全 App 级）：结算跑完由 BetSettleRunner 推事件，哪一页都有可能触发
    var winEvent by remember { mutableStateOf<BetSettleRunner.WinEvent?>(null) }
    LaunchedEffect(Unit) {
        BetSettleRunner.winEvent.collect { e ->
            if (e != null) {
                // 立刻清空事件源：不清的话任何一次重组都会把弹窗再弹一遍
                BetSettleRunner.winEvent.value = null
                winEvent = e
            }
        }
    }

    var showExitDialog by remember { mutableStateOf(false) }

    // 🔴 BugFix（用户实测）：「左滑返回手势点太快，弹出退出框的时候底部一片空白」。
    // 根因**不是**比赛列表没抓到数据，而是 **返回栈被弹空**：`nav.popBackStack()`（无参）用
    // `currentDestination` 当 pop 目标，而这个值**要等下一帧才更新**。快速连按两次返回时，
    // 第二次调用仍拿着上一帧的目标（比如已被弹掉的 live），目标找不到就把整条栈一起弹穿，
    // 连 startDestination(match) 也被弹掉 ⇒ backQueue 只剩 graph ⇒ NavHost 没有任何目的地
    // ⇒ 内容区全空（底部 tab 还高亮着，因为 route 快照还停在 match，点它也不会恢复），
    // 紧接着 route 变 null 又掉进「弹退出框」分支 —— 于是「空白 + 退出框」同时出现。
    //
    // 修法：**不再用 popBackStack() 做兜底**，改成按当前路由做确定性分类，并且 300ms 内的
    // 连按直接吞掉（详情页那一路必须吞，否则同一帧两次 pop 仍会弹穿）。
    val lastBackAt = remember { longArrayOf(0L) }
    BackHandler {
        val now = android.os.SystemClock.elapsedRealtime()
        val rapid = now - lastBackAt[0] < 300
        lastBackAt[0] = now
        val cur = route
        when {
            // ⓪ 订单详情盖在上面 → 先把它关掉（不许穿透到下面的 tab / 退出框）
            detailTicketId != null -> detailTicketId = null
            // ① 首页 → 退出确认
            cur == null || cur == "match" -> showExitDialog = true
            // ② 任一 tab → 回「比赛」tab，绝不弹框、绝不 pop
            isTabRoute(cur) -> nav.go("match")
            // ③ 连按中的第二次：吞掉，避免同帧二次 pop
            rapid -> Unit
            // ④ 详情页（oddschange / livedetail）→ 正常回退
            else -> nav.popBackStack()
        }
    }

    // 兜底自愈：万一返回栈还是被弹空（currentDestination 为 null），强制回首页。
    // 否则用户会卡在「整页空白 + 点 tab 没反应」的死状态，只能杀进程。
    LaunchedEffect(nav) {
        var seenNonEmpty = false
        nav.currentBackStack.collect { list ->
            if (list.isNotEmpty()) {
                seenNonEmpty = true
            } else if (seenNonEmpty) {
                runCatching { nav.navigate("match") { launchSingleTop = true } }
            }
        }
    }

    Scaffold(
        bottomBar = {
            // 5 个主 tab 一律显示底部导航；详情页（赔率变化 oddschange/...）不显示。
            // 🔴 同奖查询的路由带查询参数：从比赛列表点「同奖查询」时 route 是
            //    "sameodds?h=2.35&d=3.10&a=2.80"，写 `route == "sameodds"` 判不上，
            //    整条底部导航会被隐藏 —— 这就是「同奖查询点进去底部 tab 不见了」的根因。
            if (isTabRoute(route) && detailTicketId == null) {
                BottomBar(route) { nav.go(it) }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // 🔴 外层 Box 已经吃掉 Scaffold 的 padding，这里**不能再 padding 一次**（否则四周多出一圈空白）
            // 桌面快捷方式 / 小组件进来时带目标 tab（见 res/xml/shortcuts.xml、widget）
            // 🔴 用 startDestination 而不是进来后再 navigate：
            //    直接建成该 tab 为根，返回键才会走到「回比赛 tab」而不是先闪一下首页。
            // 🔴 顶层 @Composable 拿不到 Activity.intent，只能从 LocalContext 反取：
            //    setContent 里的 LocalContext 就是宿主 Activity（ContextThemeWrapper 包装的）。
            val hostIntent = (LocalContext.current as? android.app.Activity)?.intent
            val startRoute = remember(hostIntent) {
                hostIntent?.getStringExtra("route")
                    ?.takeIf { it in setOf("match", "schedule", "live", "bet", "league") }
                    ?: "match"
            }
            NavHost(nav, startDestination = startRoute, modifier = Modifier.fillMaxSize()) {
                composable("match") {
                    MatchScreen(
                        onSameOdds = { m -> nav.toSameOdds(m) },
                        onOddsChange = { m -> nav.toOddsChange(m) },
                        onRecentForm = { m -> nav.toRecentForm(m) }
                    )
                }
                // 竞彩赛程：纯赛程表格（**不做任何跳转**，用户明确要求「只是赛程显示」）
                composable("schedule") { ScheduleScreen() }
                // 比分直播：足球（titan007）+ 篮球（qiumibao/188bifen 同源）两个球类页签
                composable("live") {
                    LiveScreen(
                        // 🔴 必须显式传入 activity 作用域的实例：NavHost 内的默认 `viewModel()`
                        // 取的是 NavBackStackEntry 的 ViewModelStore，会**另建一个** LiveViewModel，
                        // 结果就是两个实例各自 5 秒轮询（双份流量）且进球检测只在其中一个里跑。
                        vm = liveVm,
                        onOpenDetail = { id, home, away ->
                            nav.navigate("livedetail/$id/${Uri.encode(home)}/${Uri.encode(away)}")
                        },
                        onOpenBbDetail = { url, title ->
                            nav.navigate("bbdetail/${Uri.encode(url)}/${Uri.encode(title)}")
                        },
                        // 赔率子行右端「变动」图标 → 该场竞彩胜平负赔率变动页。
                        // 🔴 路由段不能为空（中文编号一律 encode；缺值用 "-" 占位，读回来再还原成空串）。
                        onOpenSpfChange = { m ->
                            nav.navigate(
                                "spfchange/${m.spid}/" + Uri.encode(m.jcOrder.ifEmpty { "-" }) +
                                    "/" + Uri.encode("${m.home} VS ${m.away}")
                            )
                        },
                        // 角球行「析」→ 数据分析（双方交手战绩 / 近期战绩）
                        onOpenAnalysis = { id, home, away ->
                            nav.navigate("analysis/$id/${Uri.encode(home)}/${Uri.encode(away)}")
                        },
                        // 页签栏星形 → 我的关注（关注的比赛 / 已投注场次 / 关注球队的下一场）
                        onOpenFollow = { nav.navigate("follow") }
                    )
                }
                // 我的关注：把散在比分直播长列表、账单页、球队筛选里的「我关心的」收到一屏
                composable("follow") {
                    FollowScreen(
                        onBack = { nav.popBackStack() },
                        onOpenDetail = { id, home, away ->
                            nav.navigate("livedetail/$id/${Uri.encode(home)}/${Uri.encode(away)}")
                        }
                    )
                }
                // 篮球直播详情：内嵌 zhibo8 的文字直播 + 数据（评论/站内导航由注入 CSS 隐藏）
                composable("bbdetail/{url}/{title}") { entry ->
                    val url = entry.arguments?.getString("url")?.let { Uri.decode(it) } ?: ""
                    val title = entry.arguments?.getString("title")?.let { Uri.decode(it) } ?: "篮球直播"
                    BbWebDetailScreen(url, title, onBack = { nav.popBackStack() })
                }
                // 动画直播详情：点比分直播某行进入（动画 + 本场技术统计）
                composable("livedetail/{titanId}/{home}/{away}") { entry ->
                    val id = entry.arguments?.getString("titanId")?.toLongOrNull() ?: 0L
                    val home = entry.arguments?.getString("home")?.let { Uri.decode(it) } ?: ""
                    val away = entry.arguments?.getString("away")?.let { Uri.decode(it) } ?: ""
                    LiveDetailScreen(id, home, away, onBack = { nav.popBackStack() })
                }
                // 数据分析：比分直播角球行「析」进入，只放双方交手战绩 + 近期战绩
                composable("analysis/{titanId}/{home}/{away}") { entry ->
                    val id = entry.arguments?.getString("titanId")?.toLongOrNull() ?: 0L
                    val home = entry.arguments?.getString("home")?.let { Uri.decode(it) } ?: ""
                    val away = entry.arguments?.getString("away")?.let { Uri.decode(it) } ?: ""
                    AnalysisScreen(id, home, away, onBack = { nav.popBackStack() })
                }
                // 同奖查询：二级页（无底部栏）。参数由 SavedStateHandle 注入 SameOddsViewModel，
                // 进去即按这场赔率自动查一次；顶栏给返回箭头 ⇒ 回到点进来的那一页。
                composable("sameodds?h={h}&d={d}&a={a}") {
                    SameOddsScreen(onBack = { nav.popBackStack() })
                }
                // 竞彩胜平负**赔率变动**（比分直播 → 竞彩赔率子行右端「变动」图标）
                // 页内点任一行赔率 → 同奖查询（与比赛卡片同一套二级页语义）。
                composable("spfchange/{spid}/{order}/{vs}") { entry ->
                    val spid = entry.arguments?.getString("spid") ?: ""
                    val order = entry.arguments?.getString("order")
                        ?.let { Uri.decode(it) }?.takeIf { it != "-" } ?: ""
                    val vs = entry.arguments?.getString("vs")?.let { Uri.decode(it) } ?: ""
                    SpfChangeScreen(
                        spid = spid,
                        order = order,
                        vsText = vs,
                        onBack = { nav.popBackStack() },
                        onSameOdds = { h, d, a ->
                            // 普通压栈：同奖查询已是二级页，返回键要回到这张赔率变动表，
                            // 不能像 tab 时代那样 popUpTo(首页) 把中间层弹掉。
                            nav.navigate("sameodds?h=$h&d=$d&a=$a")
                        }
                    )
                }
                // 模拟投注：足球（胜平负/让球/半全场/总进球/比分）+ 篮球（让分/大小分/胜负）
                composable("bet") {
                    BetScreen(onOpenBill = { nav.navigate("betbill") })
                }
                // 投注账单：今天买了什么 / 花了多少 / 中没中。
                // 已开奖的注单只保留 24 小时（BetTicketStore.applyRetention 自动清），
                // 所以这里没有累计盈亏、命中率、累计回报这些统计 —— 用户 2026-11-10 明确下线。
                composable("betbill") {
                    BetBillScreen(
                        onBack = { nav.popBackStack() },
                        onOpenDetail = { id -> detailTicketId = id }
                    )
                }
                // 某次投注的相关场次比分直播（复用比分直播页，只筛出这几场，点行仍可进详情）
                composable("betlive/{ticketId}") { entry ->
                    BetLiveScreen(
                        ticketId = entry.arguments?.getString("ticketId") ?: "",
                        onBack = { nav.popBackStack() },
                        onOpenDetail = { id, home, away ->
                            nav.navigate("livedetail/$id/${Uri.encode(home)}/${Uri.encode(away)}")
                        },
                        onOpenBbDetail = { url, title ->
                            nav.navigate("bbdetail/${Uri.encode(url)}/${Uri.encode(title)}")
                        }
                    )
                }
                composable("league") { LeagueScreen() }
                composable("oddschange/{order}/{vs}") { entry ->
                    val order = entry.arguments?.getString("order") ?: ""
                    val vs = entry.arguments?.getString("vs")?.let { Uri.decode(it) } ?: ""
                    OddsChangeScreen(order, vs, onBack = { nav.popBackStack() })
                }
                // 近期状态：比赛列表「近期状态」按钮进入（详情页，不进底部 tab，isTabRoute 不含它）
                composable("recentform/{mid}/{vs}") { entry ->
                    val mid = entry.arguments?.getString("mid")?.toLongOrNull() ?: 0L
                    val vs = entry.arguments?.getString("vs")?.let { Uri.decode(it) } ?: ""
                    RecentFormScreen(mid, vs, onBack = { nav.popBackStack() })
                }
            }
            // —— 订单详情：**盖在内容区上的一层**，不是 nav 路由 ——
            // 🔴 为什么不用 `betticket/{id}` 路由：通知冷启动与弹窗点击都是「从 App 外部/上层触发」，
            //    走 navigate 会和 NavHost 首帧的状态恢复打架（navigate 先执行、随后被恢复的栈覆盖），
            //    表现就是「点了通知 / 立即查看，没跳到订单详情」。改成纯状态驱动后与 nav 完全解耦，
            //    而且返回时能回到用户原来那一页（不需要先 go("bet") 垫一层）。
            detailTicketId?.let { id ->
                BetTicketDetailScreen(
                    ticketId = id,
                    onBack = { detailTicketId = null },
                    onOpenLive = { tid ->
                        // 先收起详情再进直播页，否则从直播页返回会又看到详情
                        detailTicketId = null
                        nav.navigate("betlive/$tid")
                    }
                )
            }
        }

    }

    // —— 中奖弹窗宿主（全屏，不受当前页面限制）——
    winEvent?.let { e ->
        BetWinDialog(
            won = e.tickets,
            amount = e.amount,
            onView = {
                winEvent = null
                // 单注 ⇒ 该单详情（覆盖层，见 [detailTicketId]）；多注 ⇒ 账单
                //（弹窗里的副标题也是这么写的，两边口径一致）
                if (e.tickets.size == 1) {
                    detailTicketId = e.tickets[0].id
                } else {
                    nav.go("bet")
                    nav.navigate("betbill")
                }
            },
            onDismiss = { winEvent = null }
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("退出应用") },
            text = { Text("确定要退出Thomas助手吗？") },
            // 🔴 主次：旧版「取消」「退出」都是主题主色（红），两个按钮一样重，
            //    误触率反而高。现在只有**危险动作**是红的，取消退成次要灰。
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    (context as? Activity)?.finish()
                }) { Text("退出", color = com.example.jingcai.ui.theme.Up) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("取消", color = com.example.jingcai.ui.theme.TextSub)
                }
            }
        )
    }
}

/**
 * 是否属于底部导航的 5 个主 tab（比赛 / 竞彩赛程 / 比分直播 / 模拟投注 / 资料）。
 *
 * ⚠️ 「同奖查询」自 2026-11-03 起**不再是 tab**（用户要求撤掉，功能从比赛卡片进），
 *    因此这里不再包含 sameodds；它作为二级页走「不显示底部栏 + 返回键回上一页」。
 */
private fun isTabRoute(r: String?): Boolean =
    r == "match" || r == "schedule" || r == "live" || r == "league" || r == "bet"

/**
 * 比赛 → 同奖查询：**压一层二级页**（该页已从底部 tab 撤掉，见 NavigationBar 注释）。
 *
 * 🔴 历史教训（别再改回去）：旧版曾把它当 tab 用（popUpTo(首页) + saveState）——
 *    那是「同奖查询还是底部 tab」时代的语义。现在 tab 已经取消，若仍 popUpTo 首页，
 *    从比分直播/赔率变化页点进来会把中间那层弹掉，返回键直接回到比赛列表，
 *    用户会以为「刚才那页丢了」。
 * 现在用普通 navigate 压栈 ⇒ 返回键永远回到「点进来的那一页」，与「赔率变化 / 近期状态」
 * 两个同款二级页一致。
 */
private fun NavHostController.toSameOdds(m: com.example.jingcai.data.model.Match) {
    val h = if (m.win > 0) String.format("%.2f", m.win) else ""
    val d = if (m.draw > 0) String.format("%.2f", m.draw) else ""
    val a = if (m.lose > 0) String.format("%.2f", m.lose) else ""
    navigate("sameodds?h=$h&d=$d&a=$a")
}

/** 比赛 → 赔率变化。 */
private fun NavHostController.toOddsChange(m: com.example.jingcai.data.model.Match) {
    val vs = Uri.encode("${m.home} VS ${m.away}")
    navigate("oddschange/${m.order}/$vs")
}

/** 比赛 → 近期状态（okooo matchId = [com.example.jingcai.data.model.Match.mid]）。 */
private fun NavHostController.toRecentForm(m: com.example.jingcai.data.model.Match) {
    val vs = Uri.encode("${m.home} VS ${m.away}")
    navigate("recentform/${m.mid}/$vs")
}

private fun NavHostController.go(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 底部主 tab 的规格。
 *
 * 每个 tab 都带**一对**图标：未选中用描边版、选中用实心版（见 [NavIcons]）。
 * 换形 + 换色 + 标签加粗三件套，才在「红白配色 + 小图标」的条件下依然一眼看出当前在哪页。
 */
private class TabSpec(
    val route: String,
    val label: String,
    val on: ImageVector,
    val off: ImageVector
)

/**
 * 前台结算心跳的间隔：3 分钟。
 *
 * 取 3 分钟的依据：注单从「比赛完场」到「结算完」只差一次请求，3 分钟内必能覆盖；
 * 再短没有意义（源站自己也不是秒级更新），再长用户就要盯着「待开奖」等太久。
 * 由于 [com.example.jingcai.data.settle.BetSettleEngine] 入口有预筛，空转轮次零流量。
 */
private const val SETTLE_TICK_MS = 3 * 60 * 1000L

/**
 * 5 个主 tab。「同奖查询」按用户要求**从底部 tab 撤掉**（只留功能）：入口只保留在
 * 比赛卡片的「同奖查询」按钮，进入后是一个带返回的二级页，见 [toSameOdds]。
 */
private val TABS = listOf(
    TabSpec("match", "比赛", NavIcons.MatchOn, NavIcons.MatchOff),
    TabSpec("schedule", "竞彩赛程", NavIcons.ScheduleOn, NavIcons.ScheduleOff),
    TabSpec("live", "比分直播", NavIcons.LiveOn, NavIcons.LiveOff),
    TabSpec("bet", "模拟投注", NavIcons.BetOn, NavIcons.BetOff),
    TabSpec("league", "资料", NavIcons.InfoOn, NavIcons.InfoOff)
)

/** 未选中的图标/文字色。主题的 [TextSub]（#5E5254）在底部栏偏重，这里单独取一档中性暖灰。 */
private val NavIdle = Color(0xFF8C8486)

/**
 * 底部导航：白底 + 一条 0.7dp 极细分隔线，**选中项没有任何背景块**（用户明确要求）。
 *
 * 布局上做紧凑化：M3 默认 80dp 偏高，这里 60dp + 外层 [navigationBarsPadding]，
 * 白底一直铺到屏幕最底（手势条下方），是现在主流 App 的观感。
 */
@Composable
private fun BottomBar(route: String?, onTab: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface)
            .navigationBarsPadding()
    ) {
        // 用线不用阴影：阴影落在米红底（[Background]）上会发灰，看着像「没洗干净」。
        HorizontalDivider(thickness = 0.7.dp, color = LineSoft)
        NavigationBar(
            containerColor = Surface,
            tonalElevation = 0.dp,
            // 🔴 关掉默认 windowInsets：外层 Column 已经吃过 navigationBarsPadding，
            //    不关就是吃两遍，底部多出一条空白。
            windowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.height(62.dp)
        ) {
            TABS.forEach { t ->
                NavItem(t, route == t.route) { onTab(t.route) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    tab: TabSpec,
    selected: Boolean,
    onClick: () -> Unit
) {
    // 🔴 360dp 屏 + 5 个 tab ⇒ 每格 72dp。系统字号一旦放大（1.3× 实测就翻车），
    //    4 个汉字会被撑到 57dp 以上，NavigationBarItem 自带的 label 会折成两行
    //    （「竞彩赛 / 程」），底部栏被顶高、还挤掉上方内容。三条对策：
    //    ① tab 标签**不吃系统字号缩放**（fontScale 钳到 1f）—— 标签是「图标 + 2~4 字」，
    //       无障碍靠图标与选中态，不必跟随系统字号；
    //    ② maxLines=1 + softWrap=false，物理上不可能折行；
    //    ③ 窄屏按「每格宽度 ÷ 4 字」回算字号（0.94 系数留余量），最小 9sp：
    //       宁可字小一点，也不要折行或被裁。
    val cfg = LocalConfiguration.current
    val tabCount = 5f
    val perItemDp = cfg.screenWidthDp / tabCount
    val labelSp = minOf(10.5f, (perItemDp - 8f) / 4f * 0.94f).coerceAtLeast(9f)
    val density = LocalDensity.current

    // 选中过渡：颜色 160ms 渐变 + 图标从 0.92 轻微「弹」到 1.0。
    // 不用 indicator 背景也能给出明确的选中反馈，是主流 App 底部栏的常规手感。
    val tint by animateColorAsState(
        targetValue = if (selected) Primary else NavIdle,
        animationSpec = tween(160),
        label = "navTint"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = tween(160),
        label = "navIconScale"
    )

    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = if (selected) tab.on else tab.off,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size(25.dp).scale(iconScale)
            )
        },
        label = {
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1f)
            ) {
                Text(
                    tab.label,
                    fontSize = labelSp.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = tint,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Primary,
            selectedTextColor = Primary,
            // 🔴 用户明确要求「不要加背景」：M3 默认会给选中项铺一层 [NavIndicator] 胶囊，
            //    这里置透明。选中感全部由「实心图标 + 主色 + 标签加粗 + 轻微放大」承担。
            indicatorColor = Color.Transparent,
            unselectedIconColor = NavIdle,
            unselectedTextColor = NavIdle
        )
    )
}
