package com.example.jingcai.data.settle

import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.TicketStatus
import java.util.Calendar
import java.util.TimeZone

/**
 * 「投注日」口径自检（纯 JVM，不用真机）。
 *
 * 守的是用户明确要求的那条规则：
 * **12:00 之后算当天、12:00 之前算前一天**，并且记录页 / 账本页 / 投注汇报三处同口径。
 *
 * 为什么值得单独测：这种「差一天」的错误不会崩、不会报错，只会让某一晚的注单
 * 凭空从列表里消失（跑到前一天去），用户会以为记录丢了。
 *
 * 跑法：
 * ```
 * java -cp app/build/tmp/kotlin-classes/debug;<kotlin-stdlib.jar> \
 *      com.example.jingcai.data.settle.BetDaySelfCheck
 * ```
 */
object BetDaySelfCheck {

    private var pass = 0
    private var fail = 0

    @JvmStatic
    fun main(args: Array<String>) {
        println("==== 投注日（12:00 换日）自检 ====")

        // —— ① 12 点分界：当天 / 前一天 ——
        check("13:00 下单 ⇒ 当天", BetDay.of(ts(2026, 11, 3, 13, 0)), "2026-11-03")
        check("11:59 下单 ⇒ 前一天", BetDay.of(ts(2026, 11, 3, 11, 59)), "2026-11-02")
        check("12:00 整 ⇒ 当天（边界含）", BetDay.of(ts(2026, 11, 3, 12, 0)), "2026-11-03")
        check("00:30 凌晨单 ⇒ 前一天", BetDay.of(ts(2026, 11, 3, 0, 30)), "2026-11-02")
        check("23:59 深夜单 ⇒ 当天", BetDay.of(ts(2026, 11, 3, 23, 59)), "2026-11-03")

        // —— ② 翻页：跨月 / 跨年 ——
        check("前一天跨月", BetDay.shift("2026-11-01", -1), "2026-10-31")
        check("后一天跨月", BetDay.shift("2026-11-30", 1), "2026-12-01")
        check("后一天跨年", BetDay.shift("2026-12-31", 1), "2027-01-01")
        check("前一天跨年", BetDay.shift("2027-01-01", -1), "2026-12-31")

        // —— ③ 展示文案（顺带钉住周几，防止以后换 Locale 导致"周三"变"Wed"）——
        check("日期标题", BetDay.label("2026-11-03"), "11-03 周二")
        check("周一边界", BetDay.label("2026-11-02"), "11-02 周一")

        // —— ④ 默认落在哪一天 ——
        check("今天有单 ⇒ 今天", BetDay.pickDefault(listOf("2026-11-01", "2026-11-03"), "2026-11-03"), "2026-11-03")
        check(
            "今天没单 ⇒ 最近一个有单的日子",
            BetDay.pickDefault(listOf("2026-11-01", "2026-11-02"), "2026-11-03"), "2026-11-02"
        )
        check("一条单都没有 ⇒ 今天", BetDay.pickDefault(emptyList(), "2026-11-03"), "2026-11-03")
        check(
            "只有未来日期的脏数据 ⇒ 仍回今天",
            BetDay.pickDefault(listOf("2026-11-09"), "2026-11-03"), "2026-11-03"
        )

        check("相对日：今天", BetDay.relative("2026-11-03", "2026-11-03"), "今天")
        check("相对日：昨天", BetDay.relative("2026-11-02", "2026-11-03"), "昨天")
        check("相对日：3 天前（空）", BetDay.relative("2026-10-30", "2026-11-03"), "")

        // ⑤ 原先这里还断言「投注汇报按天/按月」的口径，那一整套统计已按用户要求下线
        //    （2026-11-10：不要累计盈亏 / 命中率 / 累计回报），报表随 BetLedger 一起删除。

        println("---- 通过 $pass / 失败 $fail ----")
        if (fail > 0) {
            println("BET_DAY_SELFCHECK: FAIL")
            System.exit(1)
        }
        println("BET_DAY_SELFCHECK: ALL PASS")
    }

    /** 北京时间构造时间戳（测试里不要依赖设备时区）。 */
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        c.clear()
        c.set(y, mo - 1, d, h, mi, 0)
        return c.timeInMillis
    }

    private fun ticket(id: String, createdAt: Long, stake: Double) = BetTicket(
        id = id,
        createdAt = createdAt,
        times = 1,
        picks = emptyList(),
        parlayM = 1,
        parlayMs = listOf(1),
        statusRaw = TicketStatus.PENDING,
        stakeLocked = stake
    )

    private fun check(name: String, actual: Any?, expect: Any?) {
        if (actual == expect) {
            pass++
            println("PASS  $name")
        } else {
            fail++
            println("FAIL  $name   实际=$actual 期望=$expect")
        }
    }
}
