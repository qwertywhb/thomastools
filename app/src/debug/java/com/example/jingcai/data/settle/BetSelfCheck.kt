package com.example.jingcai.data.settle

import com.example.jingcai.data.model.BetPick
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.LegResult
import com.example.jingcai.data.model.TicketStatus

/**
 * 投注结算引擎的自检程序（**仅 debug 源集**，不进 release APK）。
 *
 * ## 为什么用 main 而不是 JUnit
 * 本工程没有 `src/test` 源集、也没有 junit 依赖，而构建走 `--offline`（加依赖会直接失败）。
 * 这些类（[BetCalculator] / [BetRetention] / [BetModels]）**全是纯 Kotlin，不碰 Android API**，
 * 所以可以直接编进 debug classes 用一个 main 跑 —— 验证力度和单元测试一样，
 * 却不用改任何构建配置。
 *
 * 跑法（在项目根目录）：
 * ```
 * java -cp "app/build/tmp/kotlin-classes/debug;<kotlin-stdlib.jar>" \
 *   com.example.jingcai.data.settle.BetSelfCheck
 * ```
 * 末尾输出 `ALL PASS n/n` 即通过；任何一项不符都会打印 `FAIL` 并以退出码 1 结束。
 *
 * 🔴 这些断言就是**口径本身**：改注数公式或判定规则前先看这里，
 *    否则很容易「改了一处、别处静默算错」。数字全部手算得出，不要用被测代码反推期望值。
 */
object BetSelfCheck {

    private var passed = 0
    private var failed = 0

    @JvmStatic
    fun main(args: Array<String>) {
        section("注数：单关 / M串1 / 复式")
        countCases()

        section("自由过关：3串4 / 4串11 的注数与命名")
        freeParlayCases()

        section("判定：足球六种玩法")
        judgeFootball()

        section("判定：篮球两种玩法")
        judgeBasketball()

        section("篮球主客对齐（数据源与体彩反序）")
        basketAlignCases()

        section("整单结算：待开奖 / 中 / 挂 / 退款")
        settleCases()

        section("保留策略：已开奖只留 24 小时，未开奖一直留")
        retentionCases()

        println()
        if (failed == 0) {
            println("ALL PASS $passed/$passed")
        } else {
            println("FAILED: $failed 项不符合，通过 $passed 项")
            kotlin.system.exitProcess(1)
        }
    }

    // ============================== 注数 ==============================

    private fun countCases() {
        // 1) 单关：3 场各 1 项 ⇒ 3 注
        check("单关 3 场各 1 项 = 3 注", BetCalculator.countBets(picks(1, 1, 1), listOf(1)), 3)

        // 2) 单关复式：3 场各 2 项 ⇒ 6 注（每场 2 注，互不组合）
        check("单关 3 场各 2 项 = 6 注", BetCalculator.countBets(picks(2, 2, 2), listOf(1)), 6)

        // 3) 2串1：3 场各 1 项 ⇒ C(3,2) × 1 = 3 注
        check("2串1 3 场各 1 项 = 3 注", BetCalculator.countBets(picks(1, 1, 1), listOf(2)), 3)

        // 4) 2串1 复式：3 场各 2 项 ⇒ C(3,2) × 2×2 = 12 注
        check("2串1 3 场各 2 项 = 12 注", BetCalculator.countBets(picks(2, 2, 2), listOf(2)), 12)

        // 5) 3串1：3 场各 1 项 ⇒ C(3,3) × 1 = 1 注
        check("3串1 3 场各 1 项 = 1 注", BetCalculator.countBets(picks(1, 1, 1), listOf(3)), 1)

        // 6) 3串1 复式：3 场各 2 项 ⇒ 1 × 2×2×2 = 8 注
        check("3串1 3 场各 2 项 = 8 注", BetCalculator.countBets(picks(2, 2, 2), listOf(3)), 8)

        // 7) 4 场各 1 项打 3串1 ⇒ C(4,3) = 4 注
        check("3串1 4 场各 1 项 = 4 注", BetCalculator.countBets(picks(1, 1, 1, 1), listOf(3)), 4)

        // 8) 场次不足时关次收敛到「场次数」（选 2 场打 3串1 ⇒ 按 2串1 算 = 1 注，不是 0 注也不是 2 注）
        //    🔴 收敛值 = 场次数，不是 1：2 场当然能打 2串1，只有 1 场时才是单关。
        check("2 场打 3串1 收敛为 2串1 = 1 注", BetCalculator.countBets(picks(1, 1), listOf(3)), 1)

        // 9) 本金 = 2 × 注数 × 倍数
        checkD("本金 12 注 × 5 倍 = 120", BetCalculator.stakeOf(12, 5), 120.0)

        // 10) 未传关次 / 空集合 ⇒ 按单关
        check("空关次集合按单关", BetCalculator.countBets(picks(1, 1, 1), emptyList()), 3)
    }

    // ============================== 自由过关 ==============================

    /**
     * 自由过关 = **同时勾多个关次**，注数是各关次之和。
     *
     * 竞彩的 `M串N` 里 N 就是这个总注数：`3串4` = 2串1×3 + 3串1×1 = 4 注。
     * 用户举的例子 `7x200` 同理：7 场按某组关次合计 200 注。
     */
    private fun freeParlayCases() {
        // —— 注数 ——
        // 3串4：3 场各 1 项，[2,3] ⇒ C(3,2) + C(3,3) = 3 + 1 = 4
        check("3串4 注数 = 4", BetCalculator.countBets(picks(1, 1, 1), listOf(2, 3)), 4)

        // 3串7：单关 + 2串1 + 3串1 = 3 + 3 + 1 = 7
        check("3串7 注数 = 7", BetCalculator.countBets(picks(1, 1, 1), listOf(1, 2, 3)), 7)

        // 4串5：3串1×4 + 4串1×1 = 5
        check("4串5 注数 = 5", BetCalculator.countBets(picks(1, 1, 1, 1), listOf(3, 4)), 5)

        // 4串11：2串1×6 + 3串1×4 + 4串1×1 = 11
        check("4串11 注数 = 11", BetCalculator.countBets(picks(1, 1, 1, 1), listOf(2, 3, 4)), 11)

        // 4串15：单关 4 + 2串1 6 + 3串1 4 + 4串1 1 = 15
        check("4串15 注数 = 15", BetCalculator.countBets(picks(1, 1, 1, 1), listOf(1, 2, 3, 4)), 15)

        // 自由过关加复式：3 场各 2 项打 2串1+3串1 = 12 + 8 = 20
        check("3串4 复式（各 2 项）= 20", BetCalculator.countBets(picks(2, 2, 2), listOf(2, 3)), 20)

        // 重复关次只算一次
        check("重复关次去重", BetCalculator.countBets(picks(1, 1, 1), listOf(2, 2, 3)), 4)

        // —— 命名（用户 2026-11-06 二次定稿：逐字写关次清单，**不合并成 5串16**）——
        check("命名 单关", Parlay.legsText(listOf(1)), "单关")
        check("命名 2x1", Parlay.legsText(listOf(2)), "2x1")
        check("命名 2x1,3x1", Parlay.legsText(listOf(2, 3)), "2x1,3x1")
        check("命名 单关,2x1,3x1", Parlay.legsText(listOf(1, 2, 3)), "单关,2x1,3x1")
        // 用户报障原话：「选了个3x1,4x1,5x1，不要显示成 5x16，就按 3x1,4x1,5x1 显示」
        check("命名 3x1,4x1,5x1（不写 5x16）", Parlay.legsText(listOf(3, 4, 5)), "3x1,4x1,5x1")
        check("命名 乱序去重", Parlay.legsText(listOf(5, 3, 4, 3)), "3x1,4x1,5x1")
        check(
            "注数行 3 场打 2x1,3x1 = 共 4 注",
            Parlay.detailOf(ticket(listOf(2, 3), picks(1, 1, 1))), "共 4 注"
        )
        check(
            "注数行 只有 1 注不显示",
            Parlay.detailOf(ticket(listOf(3), picks(1, 1, 1))), ""
        )

        // —— 最高可中（可能中奖金额）——
        // 赔率全 2.0、1 倍，单注奖金 = 2 × Π赔率：
        //   2x1 三注：每注 2×4=8 元 ⇒ 24；3x1 一注：2×8=16 元 ⇒ 合计 40（本金 8）
        checkD(
            "最高可中 3 场 2x1,3x1 = 40",
            BetCalculator.maxPayout(ticket(listOf(2, 3), picks(1, 1, 1)))!!, 40.0
        )
        // 用户案例：5 场打 3x1,4x1,5x1 = 16 注 ⇒ 10×16 + 5×32 + 1×64 = 384（本金 32）
        checkD(
            "最高可中 5 场 3x1,4x1,5x1 = 384",
            BetCalculator.maxPayout(ticket(listOf(3, 4, 5), picks(1, 1, 1, 1, 1)))!!, 384.0
        )
        checkD(
            "最高可中 单关 3 场 = 12",
            BetCalculator.maxPayout(ticket(listOf(1), picks(1, 1, 1)))!!, 12.0
        )
        // 5 场各 1 档打 3x1,4x1,5x1（用户 2026-11-06 15:05 的口径）：
        //   最高 = 中 5 场 ⇒ 10 注 3x1 + 5 注 4x1 + 1 注 5x1 全中之和 = 384
        //   最低 = **只中 3 场**（挑赔率最小的 3 场）⇒ 只拿 1 注 3x1 = 2×8 = 16
        // 🔴 只枚举「每组都中」会让最低退化等于最高，这张票就成了单一数字（用户否掉的那版）。
        val fiveSpan = BetCalculator.payoutSpan(ticket(listOf(3, 4, 5), picks(1, 1, 1, 1, 1)))!!
        checkD("5 场串关 最高 = 384（16 注之和）", fiveSpan.max, 384.0)
        checkD("5 场串关 最低 = 16（只中 3 场的一注 3x1）", fiveSpan.min, 16.0)
        check("5 场串关 不是固定值", fiveSpan.fixed, false)

        // 同一张票换成真实赔率，验证「最低 = 赔率乘积最小的那 3 场」：
        // 赔率 1.5/1.6/1.7/1.8/2.0 ⇒ 最小 3 场乘积 1.5×1.6×1.7 = 4.08，×2 元 = 8.16
        // 最高 = 2 元 × (e3 + e4 + e5) = 2 × (50.506 + 43.116 + 14.688) = 216.62
        val five2 = listOf(
            pick(matchId = 1, odds = 1.5),
            pick(matchId = 2, odds = 1.6),
            pick(matchId = 3, odds = 1.7),
            pick(matchId = 4, odds = 1.8),
            pick(matchId = 5, odds = 2.0)
        )
        val span2 = BetCalculator.payoutSpan(ticket(listOf(3, 4, 5), five2))!!
        checkD("5 场串关 最低 = 3 场最小乘积 8.16", span2.min, 8.16)
        checkD("5 场串关 最高 = 216.62", span2.max, 216.62)

        // —— 互斥复选：同场同玩法只可能中一个档（用户 2026-11-06 报障）——
        // 半全场买「胜平」18 +「负平」20，15 倍 ⇒ 2 注 60 元。
        // 中胜平 = 2×15×18 = 540；中负平 = 2×15×20 = 600。**绝不能是 540+600 = 1140**。
        val hafu = listOf(
            pick(matchId = 1, odds = 18.0, pool = "hafu", key = "h"),
            pick(matchId = 1, odds = 20.0, pool = "hafu", key = "a")
        )
        val hafuTicket = ticket(listOf(1), hafu, times = 15)
        checkD("互斥 半全场 本金 = 60", hafuTicket.stake, 60.0)
        val hafuSpan = BetCalculator.payoutSpan(hafuTicket)!!
        checkD("互斥 半全场 最低 = 540", hafuSpan.min, 540.0)
        checkD("互斥 半全场 最高 = 600", hafuSpan.max, 600.0)
        check("互斥 半全场 不是固定值", hafuSpan.fixed, false)

        // 总进球买 5/6/7 球三档（同玩法互斥），赔率 8/12/18，10 倍：160 / 240 / 360
        val ttg = listOf(
            pick(matchId = 1, odds = 8.0, pool = "ttg", key = "5"),
            pick(matchId = 1, odds = 12.0, pool = "ttg", key = "6"),
            pick(matchId = 1, odds = 18.0, pool = "ttg", key = "7")
        )
        val ttgSpan = BetCalculator.payoutSpan(ticket(listOf(1), ttg, times = 10))!!
        checkD("互斥 总进球 5/6/7 最低 = 160", ttgSpan.min, 160.0)
        checkD("互斥 总进球 5/6/7 最高 = 360", ttgSpan.max, 360.0)

        // 同场**不同玩法**不互斥：胜平负主胜 + 比分 2:1 分属两注，两注可以一起中
        //   最高 = 两注都中 = 2×2 + 2×8 = 20
        //   最低 = 只中胜平负那注 = 4（跨玩法的「2:1 必然主胜」属于真实世界约束，
        //          引擎不推断玩法间蕴含关系，只按同玩法互斥处理）
        val cross = listOf(
            pick(matchId = 1, odds = 2.0, pool = "had", key = "h"),
            pick(matchId = 1, odds = 8.0, pool = "bf", key = "2:1")
        )
        val crossSpan = BetCalculator.payoutSpan(ticket(listOf(1), cross))!!
        checkD("不同玩法可叠加 最低 = 4（只中胜平负）", crossSpan.min, 4.0)
        checkD("不同玩法可叠加 最高 = 20", crossSpan.max, 20.0)

        // 串关里的互斥：A 场半全场两档 × B 场一档打 2x1 = 2 注，也只能中 1 注
        val mix = listOf(
            pick(matchId = 1, odds = 18.0, pool = "hafu", key = "h"),
            pick(matchId = 1, odds = 20.0, pool = "hafu", key = "a"),
            pick(matchId = 2, odds = 2.0, pool = "had", key = "h")
        )
        val mixSpan = BetCalculator.payoutSpan(ticket(listOf(2), mix))!!
        checkD("串关互斥 2x1 最低 = 72", mixSpan.min, 72.0)
        checkD("串关互斥 2x1 最高 = 80", mixSpan.max, 80.0)
    }

    // ============================== 足球判定 ==============================

    private fun judgeFootball() {
        val s = MatchScore(fullHome = 2, fullAway = 1, halfHome = 1, halfAway = 0)

        checkLeg("胜平负 主胜命中", judge("had", "h", s), LegResult.WIN)
        checkLeg("胜平负 主胜不中（实际主胜选平）", judge("had", "d", s), LegResult.LOSE)

        // 让球 -1：2-1 让后 1-1 平 ⇒ 让平命中
        checkLeg("让球-1 让平命中", judge("hhad", "d", s, line = "-1"), LegResult.WIN)
        // 让球 -2：2-1 让后 0-1 客胜 ⇒ 让负命中
        checkLeg("让球-2 让负命中", judge("hhad", "a", s, line = "-2"), LegResult.WIN)

        // 总进球 3 ⇒ s3
        checkLeg("总进球 3 球命中", judge("ttg", "s3", s), LegResult.WIN)
        checkLeg("总进球 4 球不中", judge("ttg", "s4", s), LegResult.LOSE)

        // 半全场：半场 1-0（主胜）→ 全场 2-1（主胜）⇒ hh
        checkLeg("半全场 胜胜命中", judge("hafu", "hh", s), LegResult.WIN)
        checkLeg("半全场 平胜不中", judge("hafu", "dh", s), LegResult.LOSE)

        // 比分：2:1 精确命中
        checkLeg("比分 2:1 命中", judge("crs", "s02s01", s), LegResult.WIN)
        // 比分「胜其他」：2:1 是标准比分 ⇒ 不中
        checkLeg("比分 胜其他 不中（2:1 是标准比分）", judge("crs", "s1sh", s), LegResult.LOSE)

        // 6:0 不在标准比分表 ⇒ 胜其他命中，且 5:0 精确不中
        val big = MatchScore(fullHome = 6, fullAway = 0)
        checkLeg("比分 胜其他 命中（6:0 非标准）", judge("crs", "s1sh", big), LegResult.WIN)
        checkLeg("比分 5:0 不中（实际 6:0）", judge("crs", "s05s00", big), LegResult.LOSE)

        // 未结束 ⇒ 一律待开奖（不能提前判死）
        checkLeg(
            "未结束 ⇒ 待开奖",
            judge("had", "h", MatchScore(2, 1, finished = false)),
            LegResult.PENDING
        )
        // 取消 ⇒ 退款
        checkLeg(
            "取消 ⇒ 退款",
            judge("had", "h", MatchScore(0, 0, finished = true, cancelled = true)),
            LegResult.VOID
        )
    }

    // ============================== 篮球判定 ==============================

    private fun judgeBasketball() {
        val b = MatchScore(fullHome = 110, fullAway = 100)
        checkLeg("篮球胜负 主胜命中", judge("mnl", "h", b), LegResult.WIN)
        checkLeg("篮球胜负 客胜不中", judge("mnl", "a", b), LegResult.LOSE)

        // 让分 -8：110-8=102 > 100 ⇒ 让主胜命中
        checkLeg("篮球让分 -8 命中", judge("hdc", "h", b, line = "-8"), LegResult.WIN)
        // 让分 -10：110-10=100 = 100 ⇒ 走盘退款
        checkLeg("篮球让分 -10 走盘退款", judge("hdc", "h", b, line = "-10"), LegResult.VOID)

        // 大小分 210：总分 210 ⇒ 走盘
        checkLeg("大小分 210 走盘退款", judge("hilo", "h", b, line = "210"), LegResult.VOID)
        // 大小分 205：210 > 205 ⇒ 大于命中
        checkLeg("大小分 205 大于命中", judge("hilo", "h", b, line = "205"), LegResult.WIN)
    }

    // ============================== 篮球主客对齐 ==============================

    /**
     * 用户报障：「篮球开奖有些主客队搞反了，让分开奖错了」。
     *
     * 根因：比分源的**主队**是体彩的**客队**（实测 NBA 全反、NBL 一致）。
     * 下面全部用 **2026-11-02 的真实一场**做断言，期望值按「体彩口径」手算，不用被测代码反推：
     *
     * ```
     * 周一304  体彩：76人 VS 公牛      球米宝：公牛 107 : 126 76人
     *   主队(76人)=126  客队(公牛)=107
     *   · 胜负 主胜 ⇒ 76 人赢 19 分 ⇒ 中
     *   · 让分 主队 -8.50 ⇒ 126-8.5=117.5 > 107 ⇒ 让主胜中、让客胜不中
     *   · 大小分 238.50 ⇒ 233 < 238.5 ⇒ 大于（大分）不中、小分中
     * ```
     */
    private fun basketAlignCases() {
        // —— ① 数据源主客相反：必须翻 ——
        val bx = BasketScore(
            sourceHome = "公牛", sourceAway = "76人",
            score = MatchScore(fullHome = 107, fullAway = 126)
        )
        val s1 = bx.alignedTo(home = "76人", away = "公牛")
        check("反序对齐后主队分 = 126", s1.fullHome, 126)
        check("反序对齐后客队分 = 107", s1.fullAway, 107)

        // 用体彩口径的单据去判（这是真机上判错的那张）
        val pM = pick(304, 1.7, pool = "mnl", key = "h", home = "76人", away = "公牛",
            sport = "BASKETBALL")
        checkLeg("周一304 主胜（76人赢）⇒ 中", BetCalculator.judge(pM, s1), LegResult.WIN)

        val pHdcH = pick(304, 1.9, pool = "hdc", key = "h", line = "-8.50",
            home = "76人", away = "公牛", sport = "BASKETBALL")
        checkLeg("周一304 让主胜 -8.50 ⇒ 中", BetCalculator.judge(pHdcH, s1), LegResult.WIN)
        val pHdcA = pHdcH.copy(key = "a")
        checkLeg("周一304 让客胜 -8.50 ⇒ 不中", BetCalculator.judge(pHdcA, s1), LegResult.LOSE)

        val pHiloH = pick(304, 1.9, pool = "hilo", key = "h", line = "+238.50",
            home = "76人", away = "公牛", sport = "BASKETBALL")
        checkLeg("周一304 大分 238.50（总分233）⇒ 不中", BetCalculator.judge(pHiloH, s1), LegResult.LOSE)

        // —— 反证：**不翻**会判错。这正是用户看到的「主客搞反、让分开奖错了」——
        //    同一张「主胜」单据，用数据源原始口径就变成未中奖。
        checkLeg("旧口径不翻 ⇒ 同单被误判不中", BetCalculator.judge(pM, bx.score), LegResult.LOSE)

        // —— ② 不翻的情形：旧逻辑已经判对的那张（周一305 客胜）——
        //    体彩：步行者 VS 老鹰；球米宝：老鹰 108 : 104 步行者 ⇒ 老鹰(客)赢
        val b2 = BasketScore(
            sourceHome = "老鹰", sourceAway = "步行者",
            score = MatchScore(fullHome = 108, fullAway = 104)
        )
        val s2 = b2.alignedTo(home = "步行者", away = "老鹰")
        check("反序对齐后主队分 = 104", s2.fullHome, 104)
        val pA = pick(305, 2.1, pool = "mnl", key = "a", home = "步行者", away = "老鹰",
            sport = "BASKETBALL")
        checkLeg("周一305 客胜（老鹰赢）⇒ 中", BetCalculator.judge(pA, s2), LegResult.WIN)

        // —— ③ 同口径（NBL 实测一致）：绝不能翻 ——
        val b3 = BasketScore(
            sourceHome = "墨尔本联", sourceAway = "新破坏者",
            score = MatchScore(fullHome = 94, fullAway = 74)
        )
        val s3 = b3.alignedTo(home = "墨尔本联", away = "新破坏者")
        check("同口径主队分不变 = 94", s3.fullHome, 94)
        check("同口径客队分不变 = 74", s3.fullAway, 74)
        val pM3 = pick(301, 1.5, pool = "mnl", key = "h", home = "墨尔本联", away = "新破坏者",
            sport = "BASKETBALL")
        checkLeg("周一301 主胜（NBL 同口径）⇒ 中", BetCalculator.judge(pM3, s3), LegResult.WIN)

        // —— ④ 队名完全对不上：一律不翻，宁可保守 ——
        val b4 = BasketScore(
            sourceHome = "甲队", sourceAway = "乙队",
            score = MatchScore(fullHome = 100, fullAway = 90)
        )
        val s4 = b4.alignedTo(home = "丙队", away = "丁队")
        check("队名对不上 ⇒ 原样返回", s4.fullHome, 100)

        // —— ⑤ 全称/简称差异（源站给全称）也要能识别方向 ——
        val b5 = BasketScore(
            sourceHome = "达拉斯独行侠", sourceAway = "萨克拉门托国王",
            score = MatchScore(fullHome = 94, fullAway = 82)
        )
        val s5 = b5.alignedTo(home = "国王", away = "独行侠")
        check("全称/简称反向也对齐 = 82", s5.fullHome, 82)
        check("全称/简称反向也对齐（客）= 94", s5.fullAway, 94)

        // —— ⑥ 半场分必须跟着一起翻（将来加半场玩法不会静默错位）——
        val b6 = BasketScore(
            sourceHome = "公牛", sourceAway = "76人",
            score = MatchScore(fullHome = 107, fullAway = 126, halfHome = 50, halfAway = 62)
        )
        val s6 = b6.alignedTo(home = "76人", away = "公牛")
        check("半场主队分一起翻 = 62", s6.halfHome, 62)
        check("半场客队分一起翻 = 50", s6.halfAway, 50)
    }

    // ============================== 整单结算 ==============================

    private fun settleCases() {
        // ① 有一场没有赛果 ⇒ 整单待开奖，不派彩（绝不能当 0:0 判死）
        val t2 = ticket(ms = listOf(2), picks = listOf(pick(1, 2.0), pick(2, 3.0)))
        val r1 = BetCalculator.settle(t2, mapOf(1L to MatchScore(2, 1)))
        check("缺赛果 ⇒ 待开奖", r1.status, TicketStatus.PENDING)
        checkD("缺赛果 ⇒ 不派彩", r1.payout, 0.0)

        // ② 两条腿都中 ⇒ 2串1 派彩 2 × 1 × 2×3 = 12
        //   ⚠️ 第二场必须也选「主胜」并且真的主胜（2-0）；写成 0-0 是平局，选 h 会判挂
        val r2 = BetCalculator.settle(
            t2, mapOf(1L to MatchScore(2, 1), 2L to MatchScore(2, 0))
        )
        check("两腿全中 ⇒ 已中奖", r2.status, TicketStatus.WON)
        checkD("2串1 派彩 12", r2.payout, 12.0)

        // ③ 一条腿挂 ⇒ 未中奖，不派彩
        val t3 = ticket(
            ms = listOf(2),
            picks = listOf(pick(1, 2.0, key = "h"), pick(2, 3.0, key = "h"))
        )
        val r3 = BetCalculator.settle(
            t3, mapOf(1L to MatchScore(2, 1), 2L to MatchScore(0, 1))
        )
        check("一腿挂 ⇒ 未中奖", r3.status, TicketStatus.LOST)
        checkD("未中奖不派彩", r3.payout, 0.0)

        // ④ 单关 + 取消 ⇒ 退款（派彩 = 本金）
        val t4 = ticket(ms = listOf(1), picks = listOf(pick(1, 2.0)))
        val r4 = BetCalculator.settle(
            t4, mapOf(1L to MatchScore(0, 0, finished = true, cancelled = true))
        )
        check("单关取消 ⇒ 已作废", r4.status, TicketStatus.VOID)
        checkD("单关取消退本金 2", r4.payout, 2.0)

        // ⑤ 2串1 里一腿取消 ⇒ 该腿按 1.0，中奖腿赔率照乘：2 × 1 × 3 = 6
        val t5 = ticket(ms = listOf(2), picks = listOf(pick(1, 2.0), pick(2, 3.0)))
        val r5 = BetCalculator.settle(
            t5,
            mapOf(
                1L to MatchScore(0, 0, finished = true, cancelled = true),
                2L to MatchScore(2, 0)
            )
        )
        check("一腿取消一腿中 ⇒ 已中奖", r5.status, TicketStatus.WON)
        checkD("取消腿按 1.0：2×1×3 = 6", r5.payout, 6.0)

        // ⑥ 复式 2串1：3 场各 1 项，两场中一场挂 ⇒ 组合 {1,2}{1,3}{2,3} 只有 {1,2} 中
        //    ⇒ 派彩 2×2×3 = 12
        val t6 = ticket(
            ms = listOf(2),
            picks = listOf(pick(1, 2.0), pick(2, 3.0), pick(3, 4.0, key = "h"))
        )
        val r6 = BetCalculator.settle(
            t6,
            mapOf(
                1L to MatchScore(2, 0),
                2L to MatchScore(2, 0),
                3L to MatchScore(0, 1)
            )
        )
        check("3 场 2串1 一场挂 ⇒ 仍中 1 注", r6.status, TicketStatus.WON)
        checkD("中 1 注派彩 12", r6.payout, 12.0)

        // ⑦ 自由过关 3串4（[2,3]）：1 中、2 中、3 挂
        //    注 = {1,2}{1,3}{2,3}{1,2,3}；只有 {1,2} 中 ⇒ 派彩 12
        val t7 = ticket(
            ms = listOf(2, 3),
            picks = listOf(pick(1, 2.0), pick(2, 3.0), pick(3, 4.0, key = "h"))
        )
        val r7 = BetCalculator.settle(
            t7,
            mapOf(
                1L to MatchScore(2, 0),
                2L to MatchScore(2, 0),
                3L to MatchScore(0, 1)
            )
        )
        check("3串4 一场挂 ⇒ 仍中 1 注", r7.status, TicketStatus.WON)
        checkD("3串4 派彩 12（只有 2串1 那注中）", r7.payout, 12.0)

        // ⑧ 自由过关 3串4 全中 ⇒ {1,2}=12 + {1,3}=16 + {2,3}=24 + {1,2,3}=48 ⇒ 100
        val r8 = BetCalculator.settle(
            t7,
            mapOf(
                1L to MatchScore(2, 0),
                2L to MatchScore(2, 0),
                3L to MatchScore(2, 0)
            )
        )
        check("3串4 全中 ⇒ 已中奖", r8.status, TicketStatus.WON)
        checkD("3串4 全中派彩 100", r8.payout, 100.0)

        // ⑨ 串关「提前结算」（用户新增要求）：2串1 里一场已挂、另一场还没出赛果
        //    ⇒ 这一注必含挂的那场，整单已无中奖可能，不必再等
        val r9 = BetCalculator.settle(t2, mapOf(1L to MatchScore(0, 1)))
        check("2串1 一场挂 + 一场待开奖 ⇒ 提前未中奖", r9.status, TicketStatus.LOST)
        check("提前结算有标注", r9.note.contains("提前结算"), true)
        checkD("提前结算不派彩", r9.payout, 0.0)

        // ⑩ 3串1 同理：挂一场 + 缺一场 + 中一场
        val t10 = ticket(
            ms = listOf(3),
            picks = listOf(pick(1, 2.0), pick(2, 3.0), pick(3, 4.0))
        )
        val r10 = BetCalculator.settle(
            t10, mapOf(1L to MatchScore(2, 0), 2L to MatchScore(0, 1))
        )
        check("3串1 一场挂 ⇒ 提前未中奖", r10.status, TicketStatus.LOST)

        // ⑪ 🔴 反例（这条最重要，防"过度提前结算"）：
        //    3串4（[2,3]）挂一场、另一场待开奖 ⇒ {1,2} 这注 2串1 还活着，绝不能提前判死
        val r11 = BetCalculator.settle(
            t7, mapOf(1L to MatchScore(2, 0), 3L to MatchScore(0, 1))
        )
        check("3串4 挂一场 + 一场待开奖 ⇒ 仍待开奖", r11.status, TicketStatus.PENDING)

        // ⑫ 反例：单关多场，一场挂、一场待开奖 ⇒ 待开奖那注还有机会
        val t12 = ticket(
            ms = listOf(1),
            picks = listOf(pick(1, 2.0, key = "a"), pick(2, 3.0))
        )
        val r12 = BetCalculator.settle(t12, mapOf(1L to MatchScore(2, 0)))
        check("单关一场挂 + 一场待开奖 ⇒ 仍待开奖", r12.status, TicketStatus.PENDING)

        // ⑬ 旧字段 hiddenFlag 已下线（单子满 24 小时自动清理，不再有"隐藏"概念）：
        //    老存档里的 true 不能影响任何判定 —— 读出来照常参与结算与展示。
        val legacyHidden = ticket(ms = listOf(1), picks = listOf(pick(1, 2.0)))
            .copy(payout = 4.0, statusRaw = TicketStatus.WON, stakeLocked = 2.0,
                settledAt = 9, hiddenFlag = true)
        check("旧 hiddenFlag 不影响状态", legacyHidden.status, TicketStatus.WON)
        check("旧 hiddenFlag 不影响本金", legacyHidden.stake, 2.0)
    }

    // ============================== 保留策略 ==============================

    /**
     * 用户 2026-11-10 口径：只保留未开奖的订单，已开奖的保留 24 小时后
     * 连本金、派彩、赛果一起删掉（所以账单里做不出任何累计）。
     *
     * 时间基准固定 `NOW`，注单的 `settledAt` / `createdAt` 都按 `NOW ± 偏移` 构造 ——
     * 不读系统时钟，机器时间漂移也不会让断言偶发失败。
     */
    private fun retentionCases() {
        val now = 1_800_000_000_000L
        val h = 60L * 60 * 1000

        fun mk(id: String, status: TicketStatus, settledAgoH: Long, createdAgoH: Long = 240) =
            ticket(ms = listOf(1), picks = listOf(pick(1, 2.0))).copy(
                id = id,
                statusRaw = status,
                settledAt = if (settledAgoH < 0) 0L else now - settledAgoH * h,
                createdAt = now - createdAgoH * h
            )

        // ① 待开奖：无论多久都留（延期 / 腰斩的场次可能挂好几天）
        val pendingOld = mk("pending30d", TicketStatus.PENDING, -1, createdAgoH = 24 * 30)
        check("待开奖 30 天前的单仍保留", BetRetention.keep(listOf(pendingOld), now).size, 1)

        // ② 已开奖：23 小时留、25 小时删（24 小时口径）
        val won23 = mk("won23", TicketStatus.WON, 23)
        val won25 = mk("won25", TicketStatus.WON, 25)
        check("已中奖 23 小时 ⇒ 还在", BetRetention.keep(listOf(won23), now).size, 1)
        check("已中奖 25 小时 ⇒ 删除", BetRetention.keep(listOf(won25), now).size, 0)
        check("未中奖 25 小时 ⇒ 一样删", BetRetention.keep(listOf(mk("lost25", TicketStatus.LOST, 25)), now).size, 0)
        check("已作废 25 小时 ⇒ 一样删", BetRetention.keep(listOf(mk("void25", TicketStatus.VOID, 25)), now).size, 0)

        // ③ 边界：正好满 24 小时 ⇒ 删（判据是"严格大于才留"）
        check("正好满 24 小时 ⇒ 删除", BetRetention.keep(listOf(mk("won24", TicketStatus.WON, 24)), now).size, 0)
        // 少 1 毫秒 ⇒ 留
        val wonJust = mk("wonJust", TicketStatus.WON, -1, createdAgoH = 240)
            .copy(statusRaw = TicketStatus.WON, settledAt = now - BetRetention.KEEP_MS + 1)
        check("差 1 毫秒满 24 小时 ⇒ 还在", BetRetention.keep(listOf(wonJust), now).size, 1)

        // ④ 老存档：status 已开奖但 settledAt=0 ⇒ 按 createdAt 起算，不能长生不老
        val zombie = mk("zombie", TicketStatus.WON, -1, createdAgoH = 30)
        check("老存档已开奖无 settledAt（30 小时前下单）⇒ 删除", BetRetention.keep(listOf(zombie), now).size, 0)
        check(
            "老存档已开奖无 settledAt（2 小时前下单）⇒ 还在",
            BetRetention.keep(listOf(zombie.copy(id = "zombieNew", createdAt = now - 2 * h)), now).size, 1
        )

        // ⑤ 混合列表：只删该删的，其余原样保留且顺序不变
        val kept = BetRetention.keep(listOf(won25, pendingOld, won23), now)
        check("混合列表只删过期的", kept.size, 2)
        check("混合列表保序（第一条是待开奖）", kept.first().id, "pending30d")
        check("混合列表不重复", kept.map { it.id }.toSet().size, 2)
    }

    // ============================== 辅助 ==============================

    private fun judge(pool: String, key: String, s: MatchScore, line: String = ""): LegResult =
        BetCalculator.judge(pick(1, 2.0, pool = pool, key = key, line = line), s)

    /** 生成 `counts` 描述的选项列表：每场选项数依次为参数值。 */
    private fun picks(vararg counts: Int): List<BetPick> =
        counts.flatMapIndexed { i, c ->
            (0 until c).map { k -> pick(matchId = (i + 1).toLong(), odds = 2.0, key = "k$k") }
        }

    private fun pick(
        matchId: Long,
        odds: Double,
        pool: String = "had",
        key: String = "h",
        line: String = "",
        league: String = "测试联赛",
        home: String = "主队$matchId",
        away: String = "客队$matchId",
        sport: String = "FOOTBALL"
    ) = BetPick(
        matchId = matchId,
        sport = sport,
        num = "周${matchId}",
        league = league,
        home = home,
        away = away,
        date = "2026-10-30",
        time = "20:00",
        poolCode = pool,
        poolTitle = pool,
        lineLabel = line,
        key = key,
        label = key,
        odds = odds,
        line = line
    )

    /** [ms] = 过关方式集合（自由过关）；[parlayM] 只作旧字段兼容写入。 */
    private fun ticket(ms: List<Int>, picks: List<BetPick>, times: Int = 1) = BetTicket(
        id = "t${picks.hashCode()}",
        createdAt = 1,
        times = times,
        picks = picks,
        parlayM = ms.max(),
        parlayMs = ms
    )

    /** `yyyy-MM-dd`（北京时间）转 epoch ms。 */
    private fun day(s: String): Long = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }
        .parse(s)!!.time

    private fun section(name: String) {
        println("\n== $name ==")
    }

    private fun check(name: String, actual: Any?, expect: Any?) {
        if (actual == expect) {
            passed++
            println("  PASS $name")
        } else {
            failed++
            println("  FAIL $name  实际=$actual 期望=$expect")
        }
    }

    private fun checkD(name: String, actual: Double, expect: Double) {
        if (kotlin.math.abs(actual - expect) < 0.005) {
            passed++
            println("  PASS $name")
        } else {
            failed++
            println("  FAIL $name  实际=$actual 期望=$expect")
        }
    }

    private fun checkLeg(name: String, actual: LegResult, expect: LegResult) = check(name, actual, expect)
}
