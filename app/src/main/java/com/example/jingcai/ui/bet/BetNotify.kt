package com.example.jingcai.ui.bet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.jingcai.MainActivity
import com.example.jingcai.R
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.TicketStatus
import com.example.jingcai.data.repository.AppContextHolder

/**
 * 模拟投注的两种通知：**派奖到账**、**赔率下降**。
 *
 * ## 通道独立，不复用进球提醒
 * - `bet_payout` —— 派奖（中奖/退款），importance HIGH；
 * - `bet_odds_down` —— 赔率下降，importance DEFAULT。
 *
 * 分开的理由：用户可能只想留派奖通知、嫌降赔提醒吵。合成一个渠道就没法单独关。
 *
 * ## 版式
 * - **中奖** → 红底白字的自定义 RemoteViews（[R.layout.notif_payout] / [R.layout.notif_payout_big]），
 *   金额 24~28sp 加粗突出，展开后给本金 / 回报倍数 / 注单摘要；
 * - **未中奖、降赔** → 系统默认版式（不需要强调任何东西），但小图标都换成了对应语义的矢量图
 *   （奖杯 / 对勾环 / 向下箭头），不再借用 `stat_sys_download*` 那套「下载完成」图标。
 *
 * ## 🔴 通知不发在自己的业务代码里，而是收敛到这一处
 * 派奖由 [com.example.jingcai.data.settle.BetSettleEngine] 的结果驱动，降赔由
 * [BetOddsWatcher] 驱动；两处都只调这里的方法，不各自拼 PendingIntent。
 * 权限（Android 13+ 的 POST_NOTIFICATIONS）在 [canPost] 统一判，避免各处漏判导致静默失败。
 */
object BetNotify {

    const val CHANNEL_PAYOUT = "bet_payout"

    /**
     * 赔率下降渠道。
     *
     * 🔴 **带 `_v2` 后缀是有原因的，不要"清理"成 `bet_odds_down`**：
     *    第一版用的是 `bet_odds_down` + IMPORTANCE_DEFAULT，vivo 会把这种低重要级渠道静默收纳
     *    （通知进了系统、`dumpsys` 查得到，但通知栏看不到）。
     *    改成 HIGH 时发现：已存在的渠道**应用无法自行提高 importance**，
     *    连 `deleteNotificationChannel` 再重建都无效 —— 系统按 channel id 记住了旧配置
     *    （实测删后重建仍是 `mImportance=3`）。
     *    唯一可靠的办法是**换一个全新的 id**，所以这里带 `_v2`。
     */
    const val CHANNEL_ODDS = "bet_odds_down_v2"

    private const val ID_PAYOUT = 7101
    private const val ID_ODDS = 7102

    fun ensureChannels(ctx: Context = AppContextHolder.ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PAYOUT, "模拟投注 · 派奖", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "注单中奖或退款时提醒" }
            )
            // 清掉第一版的低重要级渠道（它已无法自行提升，留着只会在设置里看到一个永远不响的开关）
            runCatching { nm.deleteNotificationChannel("bet_odds_down") }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ODDS, "模拟投注 · 赔率下降", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "下注后有选项赔率明显下降时提醒" }
            )
        }
    }

    /** 是否有通知权限。Android 13+ 需要运行时授权。 */
    fun canPost(ctx: Context = AppContextHolder.ctx): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS") ==
                PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /** 品牌红：与 `Theme.kt` 的 Primary / 支付章同值，用于 `setColor` 与通知卡的强调色。 */
    private const val BRAND_RED = 0xFFC62828.toInt()

    /** 派奖通知：一批注单结算完就发一条（多条合并成一条，避免刷屏）。 */
    fun notifyPayout(settled: List<BetTicket>, total: Double) {
        if (settled.isEmpty()) return
        val ctx = AppContextHolder.ctx
        ensureChannels(ctx)
        if (!canPost(ctx)) return

        val won = settled.filter { it.status == TicketStatus.WON }
        // 中奖才用红底版式；未中奖仍走系统默认（用红底去「庆祝」一张输掉的单，比原来还糟）。
        val n = if (won.isEmpty()) {
            buildSettledNotification(ctx, settled)
        } else {
            buildWonNotification(ctx, won, total)
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_PAYOUT, n) }
    }

    /**
     * **中奖**通知：红底白字 + 金额突出的自定义版式。
     *
     * ## 为什么走 RemoteViews（而不是 setColorized）
     * - `setColor()` 只给状态栏小图标着色，卡片背景与文字**都不会变色**；
     * - `setColorized(true) + setColor(红)` 理论上 Android 10+ 能染整张卡，
     *   但 **vivo/OriginOS、MIUI 这类定制 ROM 直接忽略**（本项目实测无效）；
     * - 自定义 RemoteViews 是唯一跨 ROM 可靠的路子：背景、字号、颜色全在自己手里。
     *
     * ## 折叠 / 展开两套版式
     * 折叠态被系统按标准行高（≈64dp）裁切，所以只在 [R.layout.notif_payout] 里放
     * 「恭喜中奖 + 已派奖」和「¥ 金额」两行；本金、注单摘要、账本入口放进
     * [R.layout.notif_payout_big]，下拉才出现，且**前两行完全一致**，展开时画面不跳。
     *
     * ## 仍然保留 title / text
     * 锁屏、「耳机播报」、部分 ROM 会忽略自定义版式只读 title/text，
     * 那两行是兜底文案，不是冗余。
     */
    private fun buildWonNotification(
        ctx: Context,
        won: List<BetTicket>,
        total: Double
    ): Notification {
        // 金额取「中奖注单派彩之和」，与文案里 +¥x 严格同源；
        // 结算引擎给的 total 含作废单退回的本金，两者不等时以逐单派彩为准（那才是真正赢到的钱）。
        val byTickets = won.sumOf { it.payout }
        val amount = if (byTickets > 0) byTickets else total
        val stake = won.sumOf { it.stake }
        val countText = if (won.size == 1) "1 注中奖" else "${won.size} 注中奖"

        val collapsed = RemoteViews(ctx.packageName, R.layout.notif_payout)
        collapsed.setTextViewText(R.id.notif_payout_amount, fmt(amount))
        collapsed.setTextViewText(R.id.notif_payout_count, countText)

        val big = RemoteViews(ctx.packageName, R.layout.notif_payout_big)
        big.setTextViewText(R.id.notif_payout_amount_big, fmt(amount))
        big.setTextViewText(R.id.notif_payout_count_big, countText)
        big.setTextViewText(R.id.notif_payout_detail1, payoutDetail(won.size, amount, stake))
        big.setTextViewText(R.id.notif_payout_detail2, picksBrief(won.first()))

        return NotificationCompat.Builder(ctx, CHANNEL_PAYOUT)
            .setSmallIcon(R.drawable.ic_notify_win)
            .setColor(BRAND_RED)
            .setContentTitle("恭喜中奖 +¥${fmt(amount)}")
            .setContentText("$countText · 本金 ¥${fmt(stake)}")
            // ⚠️ 用了自定义版式就**不要再 setStyle**：BigTextStyle 会和 bigContentView 抢同一个槽位，
            //    在部分 ROM 上表现为「展开后被系统模板盖掉、红底消失」。
            .setCustomContentView(collapsed)
            .setCustomBigContentView(big)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // 单注中奖 ⇒ 点进来直接是那一单的详情（多注 ⇒ 账本，见 openAppIntent）
            .setContentIntent(openAppIntent(ctx, won.singleOrNull()?.id))
            .build()
    }

    /**
     * **未中奖 / 已结算**通知：系统默认样式。
     *
     * 只有图标换成了「对勾环」（原来借的是 `stat_sys_download_done` 下载完成图标，
     * 跟注单结算毫无关系）。没做自定义版式：这类通知不需要强调任何东西。
     */
    private fun buildSettledNotification(ctx: Context, settled: List<BetTicket>): Notification {
        val body = "${settled.size} 注已结算，未中奖"
        return NotificationCompat.Builder(ctx, CHANNEL_PAYOUT)
            .setSmallIcon(R.drawable.ic_notify_settle)
            .setContentTitle("注单已结算")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // 未中奖通知同样直达该单详情：用户要的是「看一眼输了哪几场」，不是再点一次列表
            .setContentIntent(openAppIntent(ctx, settled.singleOrNull()?.id))
            .build()
    }

    /** 展开态第一行明细：单注给「本金 + 回报倍数」，多注给「注数 + 本金合计」。 */
    private fun payoutDetail(count: Int, amount: Double, stake: Double): String {
        if (stake <= 0) return if (count == 1) "已派奖" else "共 $count 注中奖"
        if (count > 1) return "共 $count 注 · 本金合计 ¥${fmt(stake)}"
        val roi = amount / stake
        return "本金 ¥${fmt(stake)} · 回报 ${String.format("%.2f", roi)} 倍"
    }

    /**
     * 注单摘要：队名 + 玩法，最多两项。
     *
     * 多出来的项**不写「等 N 项」**：账本里"等"字已经被用户否掉过一次，
     * 这里用「＋N 项」保持同一约定，也避免和真实选项文案混在一起。
     */
    private fun picksBrief(t: BetTicket): String {
        val picks = t.picks
        if (picks.isEmpty()) return ""
        // 玩法名带上盘口线（`让分 -9.5`），选项只写「让客胜」——
        // 通知里也不允许出现「让客胜(+9.5)」那种「本队视角」的线。
        val head = picks.take(2).joinToString(" / ") {
            "${it.home} ${poolTitleOf(it)} ${it.pickText}"
        }
        return if (picks.size > 2) "$head ＋${picks.size - 2} 项" else head
    }

    /** 赔率下降提醒：`items` = (对阵+选项, 下注赔率, 当前赔率)。 */
    fun notifyOddsDown(items: List<Triple<String, Double, Double>>) {
        if (items.isEmpty()) return
        val ctx = AppContextHolder.ctx
        ensureChannels(ctx)
        if (!canPost(ctx)) return

        val first = items.first()
        val drop = (first.second - first.third) / first.second * 100
        val title = "赔率下降 ${String.format("%.0f", drop)}%"
        val body = buildString {
            append(first.first)
            append("  ")
            append(fmt(first.second))
            append(" → ")
            append(fmt(first.third))
            if (items.size > 1) append("（另有 ${items.size - 1} 项）")
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ODDS)
            .setSmallIcon(R.drawable.ic_notify_odds)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // 静音展示：渠道是 HIGH（为了不被系统收纳），但这条不该响铃震动 ——
            // 赔率变化属于「知道了就行」的信息，不值得打断用户。
            .setSilent(true)
            .setContentIntent(openAppIntent(ctx))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_ODDS, n) }
    }

    /**
     * 通知点击目标。
     *
     * ## 口径（用户要求：点通知要能直接看到那一单）
     * - **单注**结算 ⇒ 直接进该注单的详情页（action `open_bet_ticket` + `ticket_id`）；
     * - **多注**合并成一条通知 ⇒ 进投注账本（一次跳某一单会看不到其它几单）。
     *
     * 🔴 `requestCode` 必须跟着 ticketId 走：`PendingIntent` 的「同一个」判定只看
     *    action / data / type / class，**不看 extras**。固定 requestCode 时，
     *    前一条通知的 ticket_id 会被后一条用 FLAG_UPDATE_CURRENT 覆盖掉。
     */
    private fun openAppIntent(ctx: Context, ticketId: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            action = if (ticketId != null) "open_bet_ticket" else "open_bet_ledger"
            if (ticketId != null) putExtra("ticket_id", ticketId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx,
            ticketId?.hashCode() ?: 0,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun fmt(v: Double): String = String.format("%.2f", v)
}
