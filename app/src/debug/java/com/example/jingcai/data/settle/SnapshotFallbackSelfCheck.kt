package com.example.jingcai.data.settle

/**
 * 快照兜底自检（纯 JVM，不用真机）。
 *
 * ## 为什么这段必须有回归网
 * [SnapshotFallback] 的作用是「源站滚出该场次后，拿注单里存的比分把单结掉」。
 * 判宽松了 = **比赛没打完就按中途比分派彩**（比不结算严重得多，而且不可逆）；
 * 判严格了 = 又回到「永远待开奖」。两个方向都只能靠断言锁住，肉眼看不出来。
 *
 * 断言里的数字取自真实注单（2026-11-12 周四301 阿德莱德 101-93 墨尔本联）：
 *  - 「大于 193.50」按 101+93=194 ⇒ WIN（用户报障那张单，兜底后派彩 53.78）
 *  - 开赛时间 `2026-11-12 16:30`，次日打开 App 时已过 19 小时 ⇒ 必须能兜住
 *
 * 跑法：
 * ```
 * java -cp app/build/tmp/kotlin-classes/debug;<kotlin-stdlib.jar> \
 *      com.example.jingcai.data.settle.SnapshotFallbackSelfCheck
 * ```
 */
object SnapshotFallbackSelfCheck {

    private var pass = 0
    private var fail = 0

    /** 真实注单的开赛时间（2026-11-12 16:30 北京时间）。 */
    private val KICK = SnapshotFallback.kickoffMillis("2026-11-12", "16:30")!!

    @JvmStatic
    fun main(args: Array<String>) {
        println("==== 快照兜底自检 ====")
        println("开赛毫秒 = $KICK")

        // ① 时间解析：非法输入必须返回 null，不能「猜一个」
        check("正常日期能解析", KICK > 0, "kick=$KICK")
        check("空日期 → null", SnapshotFallback.kickoffMillis("", "16:30") == null)
        check("空时间 → null", SnapshotFallback.kickoffMillis("2026-11-12", "") == null)
        check("只有日期没有时间 → null", SnapshotFallback.kickoffMillis("2026-11-12", "  ") == null)
        // isLenient=false 的关键点：宽松模式下 2 月 30 日会被悄悄算成 3 月 2 日
        check(
            "非法日期 2026-02-30 → null（不许顺延）",
            SnapshotFallback.kickoffMillis("2026-02-30", "16:30") == null,
            "got=${SnapshotFallback.kickoffMillis("2026-02-30", "16:30")}"
        )
        check(
            "非法时间 25:61 → null",
            SnapshotFallback.kickoffMillis("2026-11-12", "25:61") == null
        )

        // ② 快照解析
        val s1 = SnapshotFallback.parse("101:93", needHalf = false)
        check("普通快照 101:93", s1?.fullHome == 101 && s1?.fullAway == 93, "got=$s1")
        check("普通快照默认算完场", s1?.finished == true, "got=$s1")
        check("普通快照不带半场（0:0 占位）", s1?.halfHome == 0 && s1?.halfAway == 0, "got=$s1")

        val s2 = SnapshotFallback.parse("2:1|1:0", needHalf = false)
        check("含半场段能拆出半场", s2?.halfHome == 1 && s2?.halfAway == 0, "got=$s2")

        check("乱码 → null", SnapshotFallback.parse("abc", false) == null)
        check("缺客队 → null", SnapshotFallback.parse("101", false) == null)
        check("空串 → null", SnapshotFallback.parse("", false) == null)
        check("非数字 → null", SnapshotFallback.parse("x:y", false) == null)

        // ③ 半全场：缺半场段一律不兜（否则等于按 0:0 猜半场）
        check(
            "需半全场但快照无半场 → null",
            SnapshotFallback.parse("101:93", needHalf = true) == null
        )
        check(
            "需半全场且快照有半场 → 可用",
            SnapshotFallback.parse("101:93|52:48", needHalf = true)?.halfHome == 52
        )

        // ④ 5 小时窗口：这是「不误判」的主防线
        val raw = "101:93"
        check(
            "开赛 + 4h59m59s → 不兜底",
            SnapshotFallback.usable(raw, KICK, KICK + SnapshotFallback.WAIT_MS - 1, false) == null
        )
        check(
            "开赛 + 5h 整 → 兜底（边界含等号）",
            SnapshotFallback.usable(raw, KICK, KICK + SnapshotFallback.WAIT_MS, false) != null
        )
        check(
            "开赛 + 19h（次日打开 App）→ 兜底",
            SnapshotFallback.usable(raw, KICK, KICK + 19 * 3600_000L, false)?.fullHome == 101
        )
        check(
            "未开赛（now 早于开赛）→ 不兜底",
            SnapshotFallback.usable(raw, KICK, KICK - 3600_000L, false) == null
        )

        // ⑤ 缺数据一律不兜底：宁可待开奖，不可误判
        check("没有快照 → null", SnapshotFallback.usable(null, KICK, KICK + 19 * 3600_000L, false) == null)
        check("快照是空串 → null", SnapshotFallback.usable("", KICK, KICK + 19 * 3600_000L, false) == null)
        check("开赛时间解析失败 → null", SnapshotFallback.usable(raw, null, Long.MAX_VALUE, false) == null)

        // ⑥ 两个时间常量的关系：预筛必须早于兜底窗口，否则「源站移除」路径永远轮不到
        check(
            "预筛阈值（2h）严格小于兜底窗口（5h）",
            SnapshotFallback.SUSPECT_OVER_MS < SnapshotFallback.WAIT_MS,
            "suspect=${SnapshotFallback.SUSPECT_OVER_MS} wait=${SnapshotFallback.WAIT_MS}"
        )
        check(
            "预筛阈值覆盖最长的常规比赛（≥2h）",
            SnapshotFallback.SUSPECT_OVER_MS >= 2 * 3600_000L,
            "got=${SnapshotFallback.SUSPECT_OVER_MS}"
        )

        // ⑦ 端到端：用户那张单的判定（大于 193.50，比分 101:93 ⇒ 194 大）
        val real = SnapshotFallback.usable(raw, KICK, KICK + 19 * 3600_000L, false)
        check("真实场景兜底成功", real != null, "got=$real")
        if (real != null) {
            val pick = com.example.jingcai.data.model.BetPick(
                matchId = 2042717L,
                sport = "BASKETBALL",
                num = "周四301",
                league = "澳篮联",
                home = "阿德莱德",
                away = "墨尔本联",
                date = "2026-11-12",
                time = "16:30",
                poolCode = "hilo",
                poolTitle = "大小分",
                lineLabel = "大小分",
                key = "h",
                label = "大于 +193.50",
                odds = 1.66,
                line = "+193.50"
            )
            val r = BetCalculator.judge(pick, real)
            check(
                "大于 193.50 + 101:93（总分 194）→ WIN",
                r == com.example.jingcai.data.model.LegResult.WIN,
                "got=$r"
            )
        }

        println("---- 通过 $pass / 失败 $fail ----")
        if (fail > 0) {
            println("SNAPSHOT_FALLBACK_SELFCHECK: FAIL")
            System.exit(1)
        }
        println("SNAPSHOT_FALLBACK_SELFCHECK: ALL PASS")
    }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            pass++
            println("PASS  $name")
        } else {
            fail++
            println("FAIL  $name   $detail")
        }
    }
}
