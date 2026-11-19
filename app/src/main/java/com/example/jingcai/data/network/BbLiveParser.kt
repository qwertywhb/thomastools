package com.example.jingcai.data.network

import com.example.jingcai.data.model.BbLiveMatch
import com.example.jingcai.data.model.BbPatch
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 篮球比分解析（数据源：`matchs.qiumibao.com/live/all.htm` + 增量接口）。
 *
 * 源结构是 `{matches: [[38 个字段], …], leagues: {"联赛id": ["名称", …, 底色, …]}}`，
 * 字段语义见 [BbLiveMatch] 的 KDoc（由 188bifen 前端 JS 反推确认，非猜测）。
 *
 * 该文件只负责「JSON → 模型」，网络在 [com.example.jingcai.data.repository.BbLiveRepository]。
 * 一切解析异常都**降级为空/默认值**，绝不抛出——比分页宁可少几行，也不能整页崩。
 */
object BbLiveParser {

    private val QUARTER_RE = Regex("(\\d+)-(\\d+)")
    private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.US)
    private val DATE_KEY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val DATE_LABEL_FMT = SimpleDateFormat("MM-dd", Locale.US)
    private val WEEK_CN = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /**
     * 解析全量列表，只保留**竞彩篮球**（`[19]==2` 且 `[10][0]` 非空）。
     *
     * @param json `live/all.htm` 的响应体
     */
    fun parseAll(json: String): List<BbLiveMatch> {
        if (json.isBlank()) return emptyList()
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return emptyList()
        val leagues = runCatching { root.getAsJsonObject("leagues") }.getOrNull()
        val arr = runCatching { root.getAsJsonArray("matches") }.getOrNull() ?: return emptyList()
        val out = ArrayList<BbLiveMatch>(64)
        for (el in arr) {
            val m = el as? JsonArray ?: continue
            // [19] 球类：1 足球 / 2 篮球 / 3 电竞。**不要用队徽路径猜**（欧冠/女篮路径各不相同）。
            if (m.num(19) != 2) continue
            // [10] = ["周三301","北119",""]，[0] 非空才是竞彩场次。
            val jcNo = m.arr(10).str(0).trim()
            if (jcNo.isEmpty()) continue
            runCatching { build(m, jcNo, leagues) }.getOrNull()?.let { out.add(it) }
        }
        return out.sortedWith(compareBy({ it.sortRank }, { it.startTs }))
    }

    /**
     * 解析增量（`matchc.qiumibao.com/live/.../<v>.htm`）。
     *
     * 🔴 增量行布局与全量行**不同**（见 [BbPatch]）：`t[1]`=状态、`t[3]`=**客**队数组、
     * `t[4]`=**主**队数组、`t[6]`=节次、`t[12]`=分节比分。把增量当全量解析会得到一堆 0 分，
     * 务必走这里。队位顺序与全量一致（都是客在前），见 [build] 的说明。
     */
    fun parsePatch(json: String): List<BbPatch> {
        if (json.isBlank()) return emptyList()
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return emptyList()
        val data = runCatching { root.getAsJsonArray("data") }.getOrNull() ?: return emptyList()
        val out = ArrayList<BbPatch>(data.size())
        for (el in data) {
            val r = el as? JsonArray ?: continue
            runCatching {
                // 🔴 与全量同序：[3]=客队、[4]=主队。
                //    2026-11-16 实测 老鹰 vs 雄鹿（第3节）——[3] 老鹰 78 分、[4] 雄鹿 73 分，
                //    而 ESPN 该场 HOME=雄鹿、AWAY=老鹰；让分文本 `(+7.5)` 挂在 [4] 雄鹿
                //    （主队）身上 ⇒ [4] 肯定是主队。
                val away = r.arr(3)
                val home = r.arr(4)
                val (hq, aq) = parseQuarters(r.str(12))
                BbPatch(
                    matchId = r.long(0),
                    status = r.num(1),
                    homeScore = home.num(2),
                    awayScore = away.num(2),
                    homeHalf = home.num(3),
                    awayHalf = away.num(3),
                    period = r.str(6),
                    homeQuarters = hq,
                    awayQuarters = aq
                )
            }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    // ===== 内部 =====

    private fun build(m: JsonArray, jcNo: String, leagues: JsonObject?): BbLiveMatch {
        // 🔴 [5]=**客**队、[6]=**主**队（源站按英文站惯例「客 @ 主」排）。
        //    2026-11-16 用 ESPN 官方 scoreboard 逐场核对 8/8 全部如此：
        //    周一301 源站「魔术 vs 凯尔特人」而 ESPN 是 HOME 凯尔特人 / AWAY 魔术。
        //    曾经按「[5]=主」解析 ⇒ 队名主客颠倒 + 让分线（主队视角）挂到客队身上。
        val away = m.arr(5)
        val home = m.arr(6)
        // 🔴 源站**自己的让分标注** `[5][8]` / `[6][8]`（`(-7.5)` / `(+2.5)`）只挂在**主队**那一侧。
        //    2026-11-18 用 11 场（NBA 5 + 欧篮联 6）逐场核对体彩主队，11/11 命中：
        //      · 欧洲联赛：标注在 [5] ⇒ 主队在第一支（如 迪拜 `(-7.5)`）
        //      · NBA：    标注在 [6] ⇒ 主队在第二支（如 猛龙 `(+2.5)`）
        //    两边都空（未开售）时给 null，交给 ViewModel 用体彩队名兜底。
        val firstMark = away.str(8).isNotBlank()
        val secondMark = home.str(8).isNotBlank()
        val hostOnFirst: Boolean? = when {
            firstMark && !secondMark -> true
            secondMark && !firstMark -> false
            else -> null
        }
        val (hq, aq) = parseQuarters(m.str(20))
        val leagueId = m.num(1).toString()
        val lg = leagues?.get(leagueId) as? JsonArray
        val startTs = m.long(3)
        return BbLiveMatch(
            matchId = m.long(0),
            jcNo = jcNo,
            league = lg.str(0).ifBlank { "篮球" },
            color = lg.str(5),
            startTs = startTs,
            timeText = format(TIME_FMT, startTs),
            dateKey = format(DATE_KEY_FMT, startTs),
            dateLabel = dateLabel(startTs),
            home = home.str(1),
            away = away.str(1),
            homeScore = home.num(2),
            awayScore = away.num(2),
            homeHalf = home.num(3),
            awayHalf = away.num(3),
            homeQuarters = hq,
            awayQuarters = aq,
            status = m.num(2),
            period = m.str(17),
            detailPath = m.str(16),
            homeRank = rankOf(home),
            awayRank = rankOf(away),
            hostOnFirst = hostOnFirst
        )
    }

    /**
     * 队名数组 `[7]` = **排名**（足球行实测是 FIFA 世界排名：巴西 `6`、日本 `17`、中国 `95`；
     * 篮球行恒为空串）。
     *
     * 只收「纯数字」：源站偶尔会给 `秋1`、`第3` 这类带前缀的值，前缀对「一眼看名次」没用，
     * 反而把队名区挤掉一个字 ⇒ 与足球侧 [TitanLiveParser.rankOf] 保持同一口径。
     */
    private fun rankOf(team: JsonArray?): String {
        val raw = team.str(7).trim()
        if (raw.isEmpty()) return ""
        val n = Regex("(\\d+)$").find(raw)?.groupValues?.get(1) ?: return ""
        val v = n.toIntOrNull() ?: return ""
        return if (v > 0) v.toString() else ""
    }

    /**
     * `"一:23-25二:33-26…"` → `(主=[25,26,…], 客=[23,33,…])`（超过 4 节的加时也照收）。
     *
     * 🔴🔴 **分节比分的列序也是「客-主」，与球队数组 `[5]=客 / [6]=主` 同源**。
     *    2026-11-17 用两种口径交叉验证当前 76 场完赛（源站 `[20]`）：
     *     - 每节第一列前两节之和 **76/76** 等于 `[5][3]`（客队半场分），第二列等于 `[6][3]`（主队半场分）；
     *     - 每节第一列四节之和 **75/76** 等于 `[5][2]`（客队总分），第二列等于 `[6][2]`（主队总分），
     *       唯一不符的一场是加时（总分 145-143 而 `[20]` 里只列了四节，源站没把加时写进这串）。
     *    所以文本里**先客后主**，直接按「首列＝主」跑会得到「主客每节全反」——
     *    症状是队名对了、总分对了，唯独每节那一列互换（用户 11-17 报的正是这个）。
     *    ⚠️ 改这里等于同时改全量 `[20]` 与增量 `[12]`（两者是同一个渲染器格式化的同一串），
     *    别再按「先主后客」写回去。
     */
    private fun parseQuarters(text: String): Pair<List<Int>, List<Int>> {
        if (text.isBlank()) return emptyList<Int>() to emptyList()
        val home = ArrayList<Int>(5)
        val away = ArrayList<Int>(5)
        for (g in QUARTER_RE.findAll(text)) {
            // 首列 = 客队、次列 = 主队（见上方实测）
            away.add(g.groupValues[1].toIntOrNull() ?: 0)
            home.add(g.groupValues[2].toIntOrNull() ?: 0)
        }
        return home to away
    }

    private fun format(fmt: SimpleDateFormat, ts: Long): String =
        if (ts <= 0L) "" else fmt.format(Date(ts * 1000L))

    private fun dateLabel(ts: Long): String {
        if (ts <= 0L) return ""
        val d = Date(ts * 1000L)
        val cal = Calendar.getInstance().apply { time = d }
        val week = WEEK_CN[(cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]
        return "${DATE_LABEL_FMT.format(d)} $week"
    }

    // ===== JsonArray 取值容错（越界/null/类型不符一律给默认值） =====

    private fun JsonArray?.at(i: Int): JsonElement? =
        if (this != null && i >= 0 && i < size()) get(i) else null

    private fun JsonArray?.str(i: Int): String {
        val e = at(i) ?: return ""
        if (e.isJsonNull) return ""
        return runCatching { e.asString }.getOrDefault("")
    }

    private fun JsonArray?.num(i: Int): Int {
        val e = at(i) ?: return 0
        if (!e.isJsonPrimitive) return 0
        return runCatching { e.asInt }.getOrDefault(0)
    }

    private fun JsonArray?.long(i: Int): Long {
        val e = at(i) ?: return 0L
        if (!e.isJsonPrimitive) return 0L
        return runCatching { e.asLong }.getOrDefault(0L)
    }

    private fun JsonArray?.arr(i: Int): JsonArray? = at(i) as? JsonArray
}
