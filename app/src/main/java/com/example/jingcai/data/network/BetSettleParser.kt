package com.example.jingcai.data.network

import com.example.jingcai.data.settle.MatchScore
import org.jsoup.Jsoup

/**
 * 投注结算相关的解析：**竞彩编号 → titan007 比赛 id**，以及赛果换算。
 *
 * ## 为什么要这个映射（本项目最关键的一次选型）
 * 自动结算必须能「按比赛精确取到赛果」。试过的三条路：
 *  1. 体彩官方赛果接口 `getMatchResultV1.qry` —— **被 WAF 拦死（403）**，
 *     换 Referer / Origin / 全套浏览器头都没用，`V1` 路径本身被拉黑。
 *  2. 拿队名去 titan007 全量比分里模糊匹配 —— **实测只有 8/55**。
 *     根因：体彩用极简简称（`马竞`、`巴黎圣曼`、`莱红牛`、`谢菲联`），
 *     titan007 用全称（`马德里竞技`、`巴黎圣日耳曼`、`莱比锡红牛`、`谢菲尔德联队`），
 *     「包含关系」和编辑距离都救不回来。
 *  3. **走 `cp.titan007.com/buy/JingCai.aspx`**（本实现）—— 这个页面的队名
 *     与体彩**完全同源**（同一份中文简称），并且同一个 `tr` 里同时有
 *     竞彩编号、主客队名、`HomeTeam_{titanId}`。
 *     实测「`周X + 编号` + 双队名完全一致」**55/55 唯一命中**。
 *
 * 所以：投注时用这个映射把 `titanId` 写进注单，结算时按 id 精确取赛果，
 * 彻底不碰队名模糊匹配。
 */
object BetSettleParser {

    /** 一个可投注比赛在 titan007 侧的引用。 */
    data class TitanRef(
        /** 竞彩编号，形如 `周五001`（`tr@name` + 行首 3 位数字）。 */
        val num: String,
        val home: String,
        val away: String,
        val titanId: Long
    )

    /**
     * 匹配键：`001|布里斯班|奥克兰FC`。
     *
     * 🔴 **只用编号里的数字部分（后 3 位），不带「周五」前缀**：
     *    两边的星期写法偶尔会有差异（`周五` / `星期五` / 空），带上前缀就会整批 miss；
     *    而同一天内「3 位编号 + 主客双队名」已经完全唯一（实测 55/55）。
     *    队名里的空格一律去掉（两边风格差异只在空格）。
     */
    fun keyOf(num: String, home: String, away: String): String =
        "${numDigits(num)}|${norm(home)}|${norm(away)}"

    fun keyOf(ref: TitanRef): String = keyOf(ref.num, ref.home, ref.away)

    /** 取编号里的数字部分；`周五001` / `001` / `周五 001` 都得到 `001`。 */
    private fun numDigits(num: String): String =
        num.filter { it.isDigit() }.padStart(3, '0').takeLast(3)

    private fun norm(s: String): String = s.replace(Regex("\\s+"), "")

    /**
     * 解析 `JingCai.aspx`（UTF-8）→ 全部可投注比赛。
     *
     * 行结构（实测）：
     * ```html
     * <tr name="周五" matchid="5569001">
     *   <td><img .../>001</td>            ← 编号，`text()` 只剩 "001"（img 无文本）
     *   <td>...联赛...</td>
     *   <td>...开赛时间(display:none)...</td>
     *   <td>...截止时间...</td>
     *   <td><a id="HomeTeam_3041385">布里斯班</a></td>
     *   ...
     *   <td><a id="GuestTeam_3041385">奥克兰FC</a></td>
     * ```
     *
     * 🔴 同一份 HTML 里**编号会重复**（当天 001-023，次日又从 001 起），
     * 所以键里必须带星期（`tr@name`）——默认的 `tr@matchid` 是销售流水号，
     * 与体彩 `matchId` 完全是两套编号（`5569001` vs `2042355`），拿来对是 0 命中。
     */
    fun parseJingCai(html: String): List<TitanRef> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<TitanRef>()
        for (tr in doc.select("tr[matchid]")) {
            val week = tr.attr("name").trim()
            val dayNum = tr.select("td").firstOrNull()?.text()?.filter { it.isDigit() } ?: ""
            if (dayNum.isBlank()) continue
            val num = week + dayNum
            val homeA = tr.selectFirst("a[id^=HomeTeam_]") ?: continue
            val awayA = tr.selectFirst("a[id^=GuestTeam_]") ?: continue
            val titanId = homeA.id().removePrefix("HomeTeam_").toLongOrNull() ?: continue
            val home = homeA.text().trim()
            val away = awayA.text().trim()
            if (home.isBlank() || away.isBlank()) continue
            out += TitanRef(num, home, away, titanId)
        }
        return out
    }

    /** `"2-1"` / `"2:1"` → 主客进球；解析不出来返回 null。 */
    fun parseScoreText(raw: String): Pair<Int, Int>? {
        val parts = raw.trim().split('-', ':', '：')
        if (parts.size < 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val a = parts[1].trim().toIntOrNull() ?: return null
        return h to a
    }

    /** 由全场比分 + 可选半场比分组装结算输入。 */
    fun toScore(full: Pair<Int, Int>, half: Pair<Int, Int>? = null, cancelled: Boolean = false): MatchScore =
        MatchScore(
            fullHome = full.first,
            fullAway = full.second,
            halfHome = half?.first ?: 0,
            halfAway = half?.second ?: 0,
            finished = true,
            cancelled = cancelled
        )
}
