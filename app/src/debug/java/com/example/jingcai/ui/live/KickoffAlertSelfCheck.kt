package com.example.jingcai.ui.live

import java.util.Calendar

/**
 * 开赛提醒的**纯函数**自检（debug 源集，不进 release）。
 *
 * 只测两个不碰 Android 的东西：[KickoffAlert.inWindow] 与 [KickoffAlert.kickoffAt]。
 * 提醒的「发不发通知」「去重集合」属于 IO 副作用，靠真机验证（见 `-e demo_kickoff`），
 * 这里只锁住最容易算错的**时间边界**——那段逻辑一旦错一天，用户就整晚收不到提醒，
 * 而且在 App 上看不出任何异常。
 *
 * 跑法（Windows，classpath 必须反斜杠）：
 * ```
 * set MSYS_NO_PATHCONV=1
 * java -cp "app/build/tmp/kotlin-classes/debug;<kotlin-stdlib.jar>" \
 *      com.example.jingcai.ui.live.KickoffAlertSelfCheckKt
 * ```
 */
private var pass = 0
private var fail = 0

private fun check(name: String, actual: Any?, expect: Any?) {
    if (actual == expect) {
        pass++
    } else {
        fail++
        println("    FAIL  $name：期望 $expect，实际 $actual")
    }
}

fun main() {
    val min = 60_000L
    val hour = 60 * min

    // 基准时间 = 2026-11-10 19:00:00（用 Calendar 造，避开时区与夏令时差异）
    val base = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 10, 19, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ── ① 提醒窗口 ──
    // 判据是 `0 < diff <= lead`：正好在边界上要提醒，差 1 毫秒就不能
    check("距开赛正好 15 分钟 ⇒ 提醒", KickoffAlert.inWindow(base + 15 * min, base, 15 * min), true)
    check("距开赛 15 分 1 毫秒 ⇒ 不提醒", KickoffAlert.inWindow(base + 15 * min + 1, base, 15 * min), false)
    check("距开赛 1 分钟 ⇒ 提醒", KickoffAlert.inWindow(base + 1 * min, base, 15 * min), true)
    check("正好开赛（diff=0）⇒ 不提醒", KickoffAlert.inWindow(base, base, 15 * min), false)
    check("已开赛 30 分钟 ⇒ 不提醒", KickoffAlert.inWindow(base - 30 * min, base, 15 * min), false)
    check("还早 16 分钟 ⇒ 不提醒", KickoffAlert.inWindow(base + 16 * min, base, 15 * min), false)
    check("提前量 1 分钟也能触发", KickoffAlert.inWindow(base + min, base, 1 * min), true)

    // ── ② 解析 HH:mm ──
    check("20:00 解析成今天 20:00", KickoffAlert.kickoffAt("20:00", base), base + hour)
    check("19:30 解析成今天 19:30", KickoffAlert.kickoffAt("19:30", base), base + 30 * min)
    check("全角冒号 20：00 也认", KickoffAlert.kickoffAt("20：00", base), base + hour)
    check("带多余空格的 ' 9:05 '", KickoffAlert.kickoffAt(" 9:05 ", base), base.let {
        // 今天 09:05 比 19:00 早 10 小时 ⇒ 超过 6 小时容差 ⇒ 认为是次日 09:05
        it + 14 * hour + 5 * min
    })

    // ── ③ 跨零点：凌晨场必须落到**次日**，否则永远不提醒 ──
    val late = base + 4 * hour + 50 * min // 23:50
    check("23:50 看 02:00 ⇒ 次日 02:00", KickoffAlert.kickoffAt("02:00", late), base + 7 * hour)
    check("00:30 看 01:00 ⇒ 今天 01:00（未超容差）", KickoffAlert.kickoffAt("01:00", base + 5 * hour + 30 * min), base + 6 * hour)
    // 05:30 看 00:00：今天 00:00 比现在早 5.5 小时，**没超** 6 小时容差 ⇒ 仍是今天（已开赛）
    check("05:30 看 00:00 ⇒ 今天 00:00", KickoffAlert.kickoffAt("00:00", base + 10 * hour + 30 * min), base + 5 * hour)

    // ── ④ 非法输入一律 null（源站未定档时给 '-'） ──
    check("'-' ⇒ null", KickoffAlert.kickoffAt("-", base), null)
    check("'--:--' ⇒ null", KickoffAlert.kickoffAt("--:--", base), null)
    check("'待定' ⇒ null", KickoffAlert.kickoffAt("待定", base), null)
    check("'25:00' ⇒ null", KickoffAlert.kickoffAt("25:00", base), null)
    check("'20:99' ⇒ null", KickoffAlert.kickoffAt("20:99", base), null)

    println()
    println("KickoffAlertSelfCheck: PASS=$pass FAIL=$fail")
    if (fail > 0) throw AssertionError("有 $fail 项失败")
}
