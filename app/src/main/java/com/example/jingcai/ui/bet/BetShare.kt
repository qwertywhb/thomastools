package com.example.jingcai.ui.bet

import com.example.jingcai.data.model.BetPick
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.shortLine
import com.example.jingcai.data.model.shortTotalLine
import com.example.jingcai.data.settle.Parlay

/**
 * 把一张投注单整理成**可以直接发给别人**的纯文本。
 *
 * 版式按用户给的样例（**一场一行 + 玩法分组 + 末尾过关方式**）：
 *
 * ```
 * 周五002 波鸿 VS 沃夫斯堡 胜平负：平; 比分：2:4，1:2; 总进球：1球，2球; 半全场：胜平;
 * 周五001 布里斯班 VS 珀斯 胜平负：平;
 * 2x1    66倍
 * ```
 *
 * 拼接规则（逐条对齐样例）：
 *  1. 每场一行：`编号 主队 VS 客队 玩法：选项; 玩法：选项;`，**行尾保留分号**；
 *  2. **同一玩法的多个选项用全角逗号 `，`** 连接（`比分：2:4，1:2`）；
 *  3. 不同玩法之间用 `; `（半角分号 + 空格）；
 *  4. 最后一行是**过关方式**：`{关次清单}` + 4 个空格 + `{倍数}倍`
 *     —— 单关写 `单关`；多关次逐字列全，如 `3x1,4x1,5x1`（用户 2026-11-06 定稿：
 *     **不要合并成 `5x16` 这种竞彩名**）。
 *
 * 🔴 纯文本，无金额、无标题、无 HTML —— 微信/短信粘过去不会变形。
 * 🔴 分组用 `groupBy`，`picks` 是 LinkedHashMap ⇒ 场次与玩法的顺序 = 用户点选的先后。
 */
fun formatTicketForShare(ticket: BetTicket): String {
    val byMatch = ticket.picks.groupBy { it.matchId }.values
    val sb = StringBuilder()
    byMatch.forEach { picks ->
        val p0 = picks.first()
        sb.append(p0.num).append(' ').append(p0.home).append(" VS ").append(p0.away).append(' ')
        // 同一场内再按玩法分组：`胜平负：平; 比分：2:4，1:2;` / `让分 -9.5：让客胜;`
        sb.append(
            picks.groupBy { it.poolCode }.values.joinToString("; ") { samePool ->
                poolTitleOf(samePool.first()) + "：" + samePool.joinToString("，") { it.pickText }
            }
        ).append(';')
        sb.append('\n')
    }
    // 过关方式：逐字使用**用户勾选的那几个关次**（`3x1,4x1,5x1`），不做任何合并：
    //   单关            ⇒ `单关`
    //   3x1+4x1+5x1     ⇒ `3x1,4x1,5x1`（不写 `5x16`：那个 N 是注数，用户对不上）
    //   注数不进文案，App 内另有「共 N 注」
    sb.append(Parlay.legsText(ticket.ms)).append("    ").append(ticket.times).append('倍')
    return sb.toString()
}

/**
 * 玩法名。**盘口线挂在这里、且只挂一次**：
 *  - 篮球让分 ⇒ `让分 -9.5`、大小分 ⇒ `大小分 227.5`（都是那条唯一的盘口线）；
 *  - 足球让球胜平负 ⇒ `让球胜平负(-1)`（否则「让平」脱离让球线没有意义）。
 *
 * 🔴 篮球的线不再拼进选项文案（`让客胜(+9.5)` / `大于 227.5`）：两侧一正一负并排会被读成
 *    「让分反了」，大小分则是一条线写两遍。选项侧统一走
 *    [com.example.jingcai.data.model.BetPick.pickText]（`让主胜` / `大`）。
 * 🔴 括号只在「让球线不是 0/空 **且不等于玩法名**」时加：篮球的让分/大小分/胜负
 *    其 `lineLabel` 就是玩法名本身，加了会输出 `大小分(大小分)` 这种重复。
 */
internal fun poolTitleOf(p: BetPick): String = when {
    p.poolCode == "hdc" && p.line.trim().isNotEmpty() -> "${p.poolTitle} ${shortLine(p.line)}"
    p.poolCode == "hilo" && p.line.trim().isNotEmpty() -> "${p.poolTitle} ${shortTotalLine(p.line)}"
    p.lineLabel.isNotBlank() && p.lineLabel != "0" && p.lineLabel != p.poolTitle ->
        "${p.poolTitle}(${p.lineLabel})"
    else -> p.poolTitle
}
