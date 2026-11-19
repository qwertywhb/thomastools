package com.example.jingcai.ui.live

import com.example.jingcai.data.model.BbDayGroup
import com.example.jingcai.data.model.BbSection
import com.example.jingcai.data.model.bbDayItemKey
import com.example.jingcai.data.model.bbSectionItemKey

/**
 * 篮球比分列表的 **LazyColumn key 唯一性**自检（纯 JVM，不用跑真机）。
 *
 * 背景：2026-11-03 用户点「比分直播 → 篮球」直接闪退，日志是
 * ```
 * IllegalArgumentException: Key "bbhdr-11-03 周二" was already used.
 * ```
 * 原因是 `buildSections()` 把同一天拆进了「进行中」和「已结束」两个分区，
 * 而 key 只用了 `dateLabel`。当天上午打完、晚上还有场的日子**必然复现**，
 * 不是偶发。
 *
 * 这类崩溃一旦上线就是「点了就闪退」，且 Compose 无法 try/catch 兜住
 * （异常抛在 measure 阶段），所以只能用自检把规则钉死在这里。
 *
 * 跑法：
 * ```
 * java -cp app/build/tmp/kotlin-classes/debug;<kotlin-stdlib.jar> \
 *      com.example.jingcai.ui.live.BbListKeySelfCheck
 * ```
 */
object BbListKeySelfCheck {

    private var pass = 0
    private var fail = 0

    @JvmStatic
    fun main(args: Array<String>) {
        println("==== 篮球列表 key 唯一性自检 ====")

        // 真实场景：同一天既在「进行中」又在「已结束」
        val sameDay = BbDayGroup("11-03 周二", emptyList())
        val pending = BbSection("", listOf(sameDay))
        val finished = BbSection("已结束", listOf(sameDay))
        val sections = listOf(pending, finished)

        // ① 现在的规则：唯一
        val keys = ArrayList<String>()
        sections.forEachIndexed { si, s ->
            if (s.title.isNotEmpty()) keys.add(bbSectionItemKey(si, s))
            s.groups.forEach { g -> keys.add(bbDayItemKey(si, g)) }
        }
        check("同一天跨两个分区 ⇒ key 不重复", keys.size == keys.toSet().size, "keys=${keys}")
        check("日期组 key 互不相同", keys.count { it.startsWith("bbhdr-") } == 2, "keys=$keys")
        check("分区标题 key 互不相同", keys.count { it.startsWith("bbsec-") } == 1, "keys=$keys")

        // ② 反证：退回「只用 dateLabel」必定撞（说明这行测试真的在守关键点）
        val oldKeys = sections.flatMap { s ->
            buildList {
                if (s.title.isNotEmpty()) add("bbsec-${s.title}")
                s.groups.forEach { add("bbhdr-${it.dateLabel}") }
            }
        }
        check("旧规则（只用 dateLabel）确实会重复", oldKeys.size != oldKeys.toSet().size, "keys=$oldKeys")

        // ③ 同一天出现在三个分区（进行中 / 已结束 / 延期）也不能撞
        val postponed = BbSection("延期 / 异常", listOf(sameDay))
        val three = listOf(pending, finished, postponed)
        val k3 = ArrayList<String>()
        three.forEachIndexed { si, s ->
            if (s.title.isNotEmpty()) k3.add(bbSectionItemKey(si, s))
            s.groups.forEach { g -> k3.add(bbDayItemKey(si, g)) }
        }
        check("同一天跨三个分区 ⇒ key 不重复", k3.size == k3.toSet().size, "keys=$k3")

        println("---- 通过 $pass / 失败 $fail ----")
        if (fail > 0) {
            println("BB_LIST_KEY_SELFCHECK: FAIL")
            System.exit(1)
        }
        println("BB_LIST_KEY_SELFCHECK: ALL PASS")
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
