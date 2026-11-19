package com.example.jingcai.data.network

import com.example.jingcai.data.model.Match
import org.jsoup.Jsoup

/** 解析 okooo 竞彩页（GBK 已由 ApiClient 解码为字符串）。 */
object OkoooParser {

    fun parse(html: String, date: String): List<Match> {
        val doc = Jsoup.parse(html)
        val blocks = doc.select("div[class*=\"touzhu_1\"]")
        val list = mutableListOf<Match>()
        // 开赛时刻解析器：源站时间串就是北京时间，固定 Asia/Shanghai，
        // 免得设备在别的时区时「已开赛」判早/判晚几个小时。
        // ⚠️ SimpleDateFormat 非线程安全 ⇒ 每次 parse 建一个（整页只建一次，代价可忽略）。
        val kickoffFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        }
        for (n in blocks) {
            val mid = n.attr("data-mid").toLongOrNull() ?: continue
            val spf = n.selectFirst("div[class*=\"shenpf\"]")
            val rq = n.selectFirst("div[class*=\"rangqiuspf\"]")

            fun wz(sec: org.jsoup.nodes.Element?, i: Int) = sec?.selectFirst("div[data-wz=\"$i\"]")

            fun pv(sec: org.jsoup.nodes.Element?, i: Int): Double {
                val d = wz(sec, i) ?: return 0.0
                val sp = d.attr("data-sp").toDoubleOrNull()
                if (sp != null && sp > 0) return sp
                val p = d.selectFirst("[class*=\"peilv\"]") ?: return 0.0
                return p.text().trim().toDoubleOrNull() ?: 0.0
            }

            fun fn(sec: org.jsoup.nodes.Element?, i: Int): String {
                val d = wz(sec, i) ?: return ""
                val z = d.selectFirst("[class*=\"zhum\"]") ?: return ""
                return z.text().trim()
            }

            fun rk(sec: org.jsoup.nodes.Element?, i: Int): String {
                val d = wz(sec, i) ?: return ""
                val paim = d.selectFirst("[class*=\"paim\"]") ?: return ""
                val p2 = paim.selectFirst("p.p2") ?: paim.selectFirst("p")
                val p1 = paim.selectFirst("p.p1")
                val r = p2?.text()?.trim()?.replace("[", "")?.replace("]", "") ?: ""
                val lg = p1?.text()?.trim() ?: ""
                return if (r.isNotEmpty() && r != "-") lg + r else ""
            }

            val timeEl = n.selectFirst("div[class*=\"shijian\"]")
            val full = timeEl?.attr("title")?.replace("比赛时间:", "")?.trim() ?: ""
            val time = timeEl?.attr("mTime")?.takeIf { it.isNotBlank() }
                ?: if (full.length >= 16) full.substring(11, 16) else ""

            val scoreEl = n.selectFirst("div[rsel=\"gengd_weiks\"] p.p1") ?: n.selectFirst("div[class*=\"more\"] p.p1")
            val score = scoreEl?.text()?.trim() ?: ""

            // 联赛名 + **联赛底色**：源站把两者都写在同一个 `a.saiming` 上，形如
            //   <a class="saiming aochao" style="background-color:#009900;" title="J联赛">J联赛</a>
            // 这个颜色是 okooo 给每个联赛固定配好的（澳超 #3F1C10 / 德乙 #DB31EE / 荷甲 #FF6699 …），
            // 直接拿来用即可 —— 自己另编色板会让用户「认不出联赛」。
            val leagueEl = n.selectFirst("a[class*=\"saiming\"]")
            val league = leagueEl?.attr("title") ?: ""
            val leagueColor = parseBgColor(leagueEl?.attr("style") ?: "")

            list.add(
                Match(
                    mid = mid,
                    order = n.attr("data-ordercn"),
                    league = league,
                    time = time,
                    fullTime = if (full.length >= 16) full.substring(0, 16) else full,
                    home = fn(spf, 0).ifEmpty { n.attr("data-hname") },
                    away = fn(spf, 2).ifEmpty { n.attr("data-aname") },
                    homeRank = rk(spf, 0),
                    awayRank = rk(spf, 2),
                    single = (wz(spf, 0)?.attr("data-dg") ?: "") == "1",
                    finished = n.attr("data-end") == "1",
                    handicap = n.attr("data-rq"),
                    win = pv(spf, 0), draw = pv(spf, 1), lose = pv(spf, 2),
                    hwin = pv(rq, 0), hdraw = pv(rq, 1), hlose = pv(rq, 2),
                    score = if (score.contains(":")) score else "",
                    color = leagueColor,
                    // 开赛时刻：fullTime「yyyy-MM-dd HH:mm」解析成 epoch ms（失败为 0 ⇒ 由 finished 兜底）
                    kickoffMs = if (full.length >= 16) {
                        runCatching { kickoffFmt.parse(full.substring(0, 16))?.time ?: 0L }.getOrDefault(0L)
                    } else 0L
                )
            )
        }
        // 排序：先按**真实开赛时间**（fullTime 形如 "yyyy-MM-dd HH:mm"，定宽 ⇒ 字典序即时间序），
        // 时间相同的再按竞彩编号。
        //
        // 🔴 不能直接 `sortedBy { it.order }`（用户实测「周四的比赛排在周五下面」）：
        //    order 是「周四001 / 周五002」这种中文字符串，Kotlin 按 Unicode 码位比较，
        //    码位顺序是 一(4E00) < 三(4E09) < 二(4E8C) < 五(4E94) < 六(516D) < 四(56DB) < 天 < 日，
        //    于是「周五」整体排在「周四」前面。按时间排就没这个问题，也更符合直觉。
        return list.sortedWith(
            compareBy(
                { if (it.fullTime.length >= 16) it.fullTime else "9999-12-31 23:59" },
                { it.order }
            )
        )
    }

    /**
     * 从 `style="background-color:#009900;"` 里取 6 位 hex。
     *
     * 容错：源站也可能写成 `background-color: #009900` / `rgb(0,153,0)` / 直接没有 style，
     * 取不到就返回空串，由 `LeagueColors` 兜底 —— 解析器绝不因为一个装饰字段抛异常。
     */
    private fun parseBgColor(style: String): String {
        if (style.isBlank()) return ""
        val hex = Regex("background-color\\s*:\\s*#([0-9a-fA-F]{6})").find(style)
        if (hex != null) return "#" + hex.groupValues[1].uppercase()
        val rgb = Regex("background-color\\s*:\\s*rgb\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)")
            .find(style) ?: return ""
        fun c(g: String) = (g.toIntOrNull() ?: 0).coerceIn(0, 255)
        return String.format("#%02X%02X%02X", c(rgb.groupValues[1]), c(rgb.groupValues[2]), c(rgb.groupValues[3]))
    }
}
