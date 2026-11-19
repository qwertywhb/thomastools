package com.example.jingcai.data.network

/**
 * 「数据分析」解析自检（纯 JVM + jsoup，不用真机）。
 *
 * 夹具 `ANALYSIS_FIXTURE` 是 2026-11-09 实抓的 `zq.titan007.com/analysis/2997481cn.htm`
 * （墨西联 克雷塔罗 vs 普埃布拉），裁掉了多余数据行与 `select` 选项。
 *
 * 重点盯的四个坑：
 *  1. **排名徽标** `span.hp` 会把名次数字粘在队名前（`1普埃布拉`）⇒ 必须删掉再取文本；
 *  2. 比分单元格是 `1-2(1-0)` 一整串，比分与半场要拆开；无括号（友谊赛）时半场必须留空；
 *  3. 汇总行文案两套并存：交锋是「胜出 4 场,平局 2 场,输 4 场」，
 *     近期是「胜4平1负5」—— 同一个正则要能同时吃「出/局/无」三种后缀；
 *  4. 三张表的行 id 前缀不同（`trv_` / `trhn_` / `tran_`），混用会串行（主队表里出现客队比赛）。
 *
 * 跑法：
 * ```
 * java -cp "app/build/tmp/kotlin-classes/debug;<jsoup.jar>;<kotlin-stdlib.jar>" \
 *      com.example.jingcai.data.network.AnalysisParseSelfCheck
 * ```
 */
object AnalysisParseSelfCheck {

    private var pass = 0
    private var fail = 0

    @JvmStatic
    fun main(args: Array<String>) {
        println("==== 数据分析页解析自检 ====")

        val a = TitanAnalysisParser.parse(ANALYSIS_FIXTURE)
        check("能解析出分析结果", a != null, "got=null")
        if (a == null) return finish()

        // ① 队名（横版表头的 font.vander16 > b），不能串
        check("主队队名", a.homeTeam == "克雷塔罗", "got='${a.homeTeam}'")
        check("客队队名", a.awayTeam == "普埃布拉", "got='${a.awayTeam}'")

        // ② 对赛往绩：行数与字段
        check("交锋 3 行（夹具裁剪后）", a.h2h.rows.size == 3, "got=${a.h2h.rows.size}")
        val h1 = a.h2h.rows.getOrNull(0)
        check("交锋首行日期", h1?.date == "26-04-25", "got='${h1?.date}'")
        check("交锋首行联赛", h1?.league == "墨西联", "got='${h1?.league}'")
        // 🔴 排名徽标：夹具里主场是 `<span class="hp">1</span>普埃布拉`
        check("交锋首行主队（剔除排名徽标）", h1?.home == "普埃布拉", "got='${h1?.home}'")
        check("交锋首行客队", h1?.away == "克雷塔罗", "got='${h1?.away}'")
        check("交锋首行比分", h1?.score == "1-2", "got='${h1?.score}'")
        check("交锋首行半场", h1?.halfScore == "1-0", "got='${h1?.halfScore}'")
        check("交锋首行角球", h1?.corner == "3-12", "got='${h1?.corner}'")
        check("交锋首行赛果（本场主队视角=胜）", h1?.result == "胜", "got='${h1?.result}'")
        val h2 = a.h2h.rows.getOrNull(1)
        check("交锋第 2 行半场 0-1", h2?.halfScore == "0-1", "got='${h2?.halfScore}'")
        check("交锋第 2 行主队（无徽标时不吃数字）", h2?.home == "克雷塔罗", "got='${h2?.home}'")

        // ③ 交锋汇总：胜出/平局/输 三套后缀
        check("交锋汇总总场次", a.h2h.total == 10, "got=${a.h2h.total}")
        check("交锋汇总胜", a.h2h.win == 4, "got=${a.h2h.win}")
        check("交锋汇总平（平局 2）", a.h2h.draw == 2, "got=${a.h2h.draw}")
        check("交锋汇总负（输 4）", a.h2h.loss == 4, "got=${a.h2h.loss}")
        check("交锋胜率", a.h2h.winRate == "40%", "got='${a.h2h.winRate}'")
        check("交锋赢率", a.h2h.marginRate == "44.4%", "got='${a.h2h.marginRate}'")
        check("交锋大率", a.h2h.bigRate == "33.3%", "got='${a.h2h.bigRate}'")
        check("交锋有汇总", a.h2h.hasSummary, "hasSummary=false")

        // ④ 近期战绩：两块各自成套，不能串行
        check("主队近期 2 行（夹具裁剪后）", a.homeRecent.rows.size == 2, "got=${a.homeRecent.rows.size}")
        check("客队近期 2 行（夹具裁剪后）", a.awayRecent.rows.size == 2, "got=${a.awayRecent.rows.size}")
        check("主队近期标题", a.homeRecent.title == "克雷塔罗", "got='${a.homeRecent.title}'")
        check("客队近期标题", a.awayRecent.title == "普埃布拉", "got='${a.awayRecent.title}'")
        val hr = a.homeRecent.rows.getOrNull(0)
        check("主队近期首行日期", hr?.date == "26-10-31", "got='${hr?.date}'")
        check("主队近期首行主队", hr?.home == "华雷斯", "got='${hr?.home}'")
        check("主队近期首行比分 3-1", hr?.score == "3-1", "got='${hr?.score}'")
        check("主队近期首行赛果 负", hr?.result == "负", "got='${hr?.result}'")
        val ar = a.awayRecent.rows.getOrNull(0)
        check("客队近期首行日期", ar?.date == "26-10-31", "got='${ar?.date}'")
        check("客队近期首行主队 普埃布拉", ar?.home == "普埃布拉", "got='${ar?.home}'")

        // ⑤ 近期汇总：紧凑文案「近10场,胜4平1负5」
        check("主队近期总场次", a.homeRecent.total == 10, "got=${a.homeRecent.total}")
        check("主队近期胜", a.homeRecent.win == 4, "got=${a.homeRecent.win}")
        check("主队近期平", a.homeRecent.draw == 1, "got=${a.homeRecent.draw}")
        check("主队近期负", a.homeRecent.loss == 5, "got=${a.homeRecent.loss}")
        check("主队近期大球率（大:60%）", a.homeRecent.bigRate == "60%", "got='${a.homeRecent.bigRate}'")
        check("客队近期胜", a.awayRecent.win == 1, "got=${a.awayRecent.win}")
        check("客队近期平", a.awayRecent.draw == 3, "got=${a.awayRecent.draw}")
        check("客队近期负", a.awayRecent.loss == 6, "got=${a.awayRecent.loss}")

        // ⑥ 边界：空串 / 只有无关板块 ⇒ null（别抛异常）
        check("空 HTML 返回 null", TitanAnalysisParser.parse("") == null, "got=非 null")
        check(
            "没有两块板块时返回 null",
            TitanAnalysisParser.parse("<html><body><div class='porletP'><h2 class='fx_title2'>亚盘</h2></div></body></html>") == null,
            "got=非 null"
        )

        finish()
    }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            pass++
            println("  PASS  $name")
        } else {
            fail++
            println("  FAIL  $name  $detail")
        }
    }

    private fun finish() {
        println("---- 合计 $pass 通过 / $fail 失败 ----")
        if (fail > 0) {
            println("ALL FAIL")
            System.exit(1)
        }
        println("ALL PASS $pass/$pass")
    }
}
