package com.example.jingcai.data.network

import com.example.jingcai.data.model.LiveMatch

/**
 * 角球解析自检（纯 JVM，不用真机）。
 *
 * 背景：足球比分直播要显示「角球 / 半场角球」，数据来自 `detail_ut.js` 的 `tc` 数组。
 * 这里最容易错的两件事，靠肉眼完全看不出来：
 *  1. **编码认错**：`tc` 是 `code,主,客` 的编码表（`6`=角球、`45`=半场角球、`3`=射门…），
 *     认错一位就是「角球显示成射门」，数值全都「看着合理」，线上不会报错；
 *  2. **把「没数据」当成 0**：未开赛的场次如果落成 0-0，整页都是假的「角球 0-0」。
 *
 * 断言里的数字取自实测样本（已与详情页 `techStatisticData` 逐项比对）：
 *  - 2910963（巴西国际 1-0 科里蒂巴）：`tc` = `6,15,2;45,5,1;11,3,4;3,19,6;4,6,1`
 *    ↔ 详情页 `角球 15-2 / 半场角球 5-1 / 黄牌 3-4 / 射门 19-6 / 射正 6-1`。
 *
 * 跑法：
 * ```
 * java -cp app/build/tmp/kotlin-classes/debug;<kotlin-stdlib.jar> \
 *      com.example.jingcai.data.network.CornerParseSelfCheck
 * ```
 */
object CornerParseSelfCheck {

    private var pass = 0
    private var fail = 0

    @JvmStatic
    fun main(args: Array<String>) {
        println("==== 角球解析自检 ====")

        // ① 真实样本：三项编码各就各位
        val real = "var tc=Array(288);\n" +
            "tc[3]=\"2910963^6,15,2;45,5,1;11,3,4;3,19,6;4,6,1;14,64%,36%\";\n"
        val m1 = TitanLiveParser.parseCorners(real)
        val c1 = m1["2910963"]
        check("真实样本能解析出该场", c1 != null, "map=$m1")
        check("角球取 code 6（15-2）", c1?.home == 15 && c1?.away == 2, "got=$c1")
        check("半场角球取 code 45（5-1）", c1?.halfHome == 5 && c1?.halfAway == 1, "got=$c1")
        check("射门(code 3) 没被当成角球", c1?.home != 19, "got=$c1")

        // ② 缺 45 ⇒ 半场角球是 -1（无数据），不是 0
        val noHalf = TitanLiveParser.parseCorners("tc[0]=\"111^6,4,0;3,9,9\"")
        check("缺半场角球 ⇒ -1", noHalf["111"]?.halfHome == -1 && noHalf["111"]?.halfAway == -1, "got=${noHalf["111"]}")

        // ③ 0 是合法值：0-0 角球必须保留为 0，不能当成「没有」
        val zero = TitanLiveParser.parseCorners("tc[0]=\"222^6,0,0;45,0,0\"")
        check("0-0 角球保留为 0", zero["222"]?.home == 0 && zero["222"]?.away == 0, "got=${zero["222"]}")
        check("0-0 半场角球保留为 0", zero["222"]?.halfHome == 0, "got=${zero["222"]}")

        // ④ 缺 6（只有射门等）⇒ 整场不出现（宁可不显示，也不要瞎猜）
        val noCorner = TitanLiveParser.parseCorners("tc[0]=\"333^3,8,6;4,3,1\"")
        check("没有角球项 ⇒ 该场不入表", !noCorner.containsKey("333"), "map=$noCorner")

        // ⑤ 脏数据不抛异常
        val dirty = TitanLiveParser.parseCorners(
            "tc[0]=\"444^6,;,abc\";\ntc[1]=\"\";tc[2]=\"555\";tc[3]=\"666^6,x,y\""
        )
        check("脏数据全部丢弃且不崩", dirty.isEmpty(), "map=$dirty")

        // ⑥ 多场同文件互不干扰
        val multi = TitanLiveParser.parseCorners(
            "tc[0]=\"1^6,1,2;45,0,1\";tc[1]=\"2^6,9,9;45,4,4\";tc[2]=\"3^6,0,7\""
        )
        check("多场各自解析", multi.size == 3 && multi["1"]?.home == 1 && multi["2"]?.home == 9 && multi["3"]?.away == 7, "map=$multi")

        // ⑦ applyCorners：命中写入 / 未命中保持 -1 / 空映射原样返回
        val base = listOf(
            match("2910963"),
            match("9999999")
        )
        val applied = TitanLiveParser.applyCorners(base, m1)
        check("命中场次写入角球", applied[0].homeCorner == 15 && applied[0].awayCorner == 2, "got=${applied[0].homeCorner}-${applied[0].awayCorner}")
        check("命中场次写入半场角球", applied[0].homeHalfCorner == 5 && applied[0].awayHalfCorner == 1, "got=${applied[0].homeHalfCorner}")
        check("未命中场次保持 -1（不显示）", applied[1].homeCorner == -1 && applied[1].awayCorner == -1, "got=${applied[1].homeCorner}")
        check("空映射 ⇒ 原样返回", TitanLiveParser.applyCorners(base, emptyMap()) === base)

        println("---- 通过 $pass / 失败 $fail ----")
        if (fail > 0) {
            println("CORNER_PARSE_SELFCHECK: FAIL")
            System.exit(1)
        }
        println("CORNER_PARSE_SELFCHECK: ALL PASS")
    }

    /** 最小可用的 [LiveMatch]，只关心 titanId。 */
    private fun match(id: String) = LiveMatch(
        titanId = id.toLong(), matchId = id, num = "", league = "测试", color = "#888888",
        time = "10:00", home = "主", away = "客", homeRank = "", awayRank = "",
        score = "0-0", ouHome = "-", ouGoal = "-", ouAway = "-",
        spfH = "", spfD = "", spfA = "", handicap = "", rqH = "", rqD = "", rqA = "",
        striped = false
    )

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
