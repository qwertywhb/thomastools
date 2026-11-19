package com.example.jingcai.data.settle

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * **投注日**（下注归属的那一天），两个口径都写死在这里，记录页 / 账本页 / 投注汇报共用：
 *
 * 1. **以 12:00 为界**，不是自然日 0 点。用户原话：
 *    「12 点之后显示当日订单，12 之前显示前一天订单」——
 *    竞彩的比赛多是深夜到次日上午，凌晨 00:30 下的单在玩家心里属于「昨天晚上那批」，
 *    按自然日切成两天会把一晚上的注单拆散，第二天中午前又看不到。
 * 2. 时区固定 **Asia/Shanghai**：竞彩的销售日就是北京时间。
 *    用设备时区会让跨时区用户的日界整体错位。
 *
 * ```
 * 11-02 13:00 下单  ⇒ 投注日 11-02
 * 11-03 02:30 下单  ⇒ 投注日 11-02   ← 凌晨算前一天
 * 11-03 11:59 下单  ⇒ 投注日 11-02
 * 11-03 12:00 下单  ⇒ 投注日 11-03   ← 从这个点起是新的一天
 * ```
 *
 * ⚠️ 这里**没有**用 `java.time`：`minSdk 24` 且工程没开 desugaring，`LocalDate` 在 24/25 上会
 *    `NoClassDefFoundError`。一律走 [Calendar]。
 */
object BetDay {

    /** 销售日切换点（小时）。改这个值等于改口径，历史上已落盘的订单归属会整体平移。 */
    const val CUT_HOUR = 12

    private const val ZONE_ID = "Asia/Shanghai"
    private fun zone(): TimeZone = TimeZone.getTimeZone(ZONE_ID)

    /** `yyyy-MM-dd`，可直接字符串比较大小。 */
    fun of(tsMillis: Long): String {
        val c = Calendar.getInstance(zone())
        c.timeInMillis = tsMillis
        // 减掉 12 小时再取日期 ⇒ 12:00 前算前一天
        c.add(Calendar.HOUR_OF_DAY, -CUT_HOUR)
        return keyFormat().format(c.time)
    }

    /** 此刻的投注日。 */
    fun today(now: Long = System.currentTimeMillis()): String = of(now)

    /** 前一天 / 后一天。 */
    fun shift(key: String, deltaDays: Int): String {
        val c = Calendar.getInstance(zone())
        c.time = parseKey(key)
        c.add(Calendar.DAY_OF_MONTH, deltaDays)
        return keyFormat().format(c.time)
    }

    /**
     * 默认要展示哪一天：**今天**；今天一单都没有时退回「最近一个有单的日子」。
     *
     * 原因：中午刚换日、还没下注就进来，若死板显示空白的今天，用户只会以为记录丢了。
     * 用户手动切到哪一天就以手动为准，这个函数**只在首次进入时调用一次**。
     */
    fun pickDefault(keys: Collection<String>, todayKey: String = today()): String {
        if (keys.isEmpty()) return todayKey
        if (todayKey in keys) return todayKey
        // 只往前找，不看"未来"的日期（订单时间被系统时钟调快等异常情况）
        return keys.filter { it <= todayKey }.maxOrNull() ?: todayKey
    }

    /** `11-03 周二` */
    fun label(key: String): String = labelFormat().format(parseKey(key))

    /** `今天` / `昨天` / `前天`，都不匹配返回空串。 */
    fun relative(key: String, todayKey: String = today()): String = when (key) {
        todayKey -> "今天"
        shift(todayKey, -1) -> "昨天"
        shift(todayKey, -2) -> "前天"
        else -> ""
    }

    private fun keyFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { timeZone = zone() }
    private fun labelFormat() = SimpleDateFormat("MM-dd EEE", Locale.CHINA).apply { timeZone = zone() }

    private fun parseKey(key: String): Date =
        (SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { timeZone = zone() }).parse(key)
            ?: Date(0)
}
