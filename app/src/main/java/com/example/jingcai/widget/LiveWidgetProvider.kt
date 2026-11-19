package com.example.jingcai.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.jingcai.MainActivity
import com.example.jingcai.R
import com.example.jingcai.data.repository.LiveSnapshotStore
import com.example.jingcai.data.repository.UserPrefs

/**
 * 桌面比分小组件。
 *
 * ## 数据从哪来
 * 直接读 [LiveSnapshotStore]（比分直播每次整页刷新成功都会写一份），**不自己发请求**：
 * 小组件进程不带 App 的 OkHttp 配置、也没地方失败重试，让它自己联网只会多一份
 * 不可控的流量和限流风险。
 *
 * ## 显示什么
 * 优先显示**关注的比赛**（[UserPrefs.followed]），没有关注时退回「正在进行中」的前几场，
 * 再没有就显示占位文案。这个优先级解决的是「打开桌面看到三场跟我无关的球」。
 *
 * ## 刷新节奏
 * `updatePeriodMillis=30 分钟` 是系统下限，写更小也会被拉回 30 分钟。所以这里不追求实时，
 * 定位是「一眼看比分 + 一键进入」；点开后是 5 秒 tick 的比分直播页。
 */
class LiveWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    /** App 侧比分更新后主动推一次（见 [requestRefresh]），不用等 30 分钟。 */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, LiveWidgetProvider::class.java))
            ids.forEach { render(context, mgr, it) }
        }
    }

    private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_live)

        val snapshot = runCatching { LiveSnapshotStore.load(ctx) }.getOrNull()
        val all = snapshot?.matches.orEmpty()
        val followed = runCatching { UserPrefs.followed.value }.getOrDefault(emptySet())

        val picked = all.filter { it.titanId in followed }
            .ifEmpty { all.filter { it.state != 0 && it.state != -1 } }
            .take(3)

        val body = if (picked.isEmpty()) {
            ctx.getString(R.string.widget_placeholder)
        } else {
            picked.joinToString("\n") { m ->
                val score = m.score.ifBlank { "VS" }
                "${m.home} $score ${m.away}"
            }
        }
        views.setTextViewText(R.id.widget_body, body)

        // 整块可点：进入比分直播 tab
        val open = Intent(ctx, MainActivity::class.java).apply {
            putExtra("route", "live")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)

        mgr.updateAppWidget(id, views)
    }

    companion object {
        private const val ACTION_REFRESH = "com.example.jingcai.WIDGET_REFRESH"

        /**
         * 通知所有已放置的小组件重绘。
         *
         * 没有放到桌面时什么都不做（`getAppWidgetIds` 返回空），成本可忽略，
         * 所以可以在每次比分刷新后无条件调用。
         */
        fun requestRefresh(ctx: Context) {
            runCatching {
                ctx.sendBroadcast(Intent(ctx, LiveWidgetProvider::class.java).setAction(ACTION_REFRESH))
            }
        }
    }
}
