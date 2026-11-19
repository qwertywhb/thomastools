package com.example.jingcai.ui.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * **仅调试包**可用的进球注入器：`adb shell am broadcast` 一发就模拟一次进球。
 *
 * 为什么不用 `am start -e demo_goal`：本机实测 `am start` 打到已在栈顶的实例时**不保证**
 * 回调 `onNewIntent`（会打印 `Activity not started, its current task has been brought to the front`），
 * 于是 extras 丢了、演示事件根本不触发 —— 回归「息屏时能不能收到提醒」时它完全不可靠。
 * 广播与 Activity 生命周期无关，前台/后台/息屏任何状态都能触发。
 *
 * 用法（`-e delay` 是毫秒延时，方便先息屏再等）：
 * ```
 * adb shell am broadcast -a com.example.jingcai.DEBUG_GOAL \
 *   --es teams "曼城|2-0|阿森纳|英超|1" --el delay 25000
 * ```
 * 安全：`FLAG_DEBUGGABLE` 检查在**运行时**做，release 包里这个接收器直接空转。
 */
class DebugGoalReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val debuggable = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val raw = intent.getStringExtra("teams") ?: return
        val p = raw.split("|")
        if (p.size < 4) return
        val e = GoalEvent(
            // 默认固定 999999：同一场反复注入会互相顶掉（正好方便测「覆盖」）。
            // 想同时看到多条通知，用 `--el titanId 111` 换一个，通知 id 由它决定。
            titanId = intent.getLongExtra("titanId", 999_999L),
            home = p[0],
            away = p[2],
            score = p[1],
            side = p.getOrNull(4)?.toIntOrNull() ?: 1,
            league = p[3],
            cancelled = p.getOrNull(5)?.toIntOrNull() == 1
        )
        val delay = intent.getLongExtra("delay", 0L)
        android.util.Log.i("DebugGoal", "收到注入请求 delay=${delay}ms ${e.home} ${e.score} ${e.away}")
        Handler(Looper.getMainLooper()).postDelayed({
            android.util.Log.i("DebugGoal", "触发进球：${e.home} ${e.score} ${e.away} cancelled=${e.cancelled}")
            runCatching { if (e.cancelled) GoalDeliverer.onCancel(e) else GoalDeliverer.onGoal(e) }
        }, delay)
    }
}
