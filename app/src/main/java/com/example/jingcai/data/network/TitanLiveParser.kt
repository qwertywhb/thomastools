package com.example.jingcai.data.network

import com.example.jingcai.data.model.JcSpfChange
import com.example.jingcai.data.model.LiveChange
import com.example.jingcai.data.model.LiveMatch
import com.example.jingcai.data.model.RawLive
import org.jsoup.Jsoup
import java.util.Calendar
import java.util.TimeZone

/**
 * 「比分直播」解析器 —— **纯函数，不发网络请求**（方便离线自检）。
 *
 * 四个数据源，全部经实测确认契约：
 *  1. [parseBfData]：解析 `livestatic.titan007.com/vbsxml/bfdata_ut.js`（全量 288 场，含队名/联赛/颜色/
 *     比分/时间）。同时解析同文件的 `B[i]` 数组得到**热门联赛**集合（[RawLive.hotSclassIds]）。
 *     这是「完整」tab 的数据源；「热门」「竞彩」tab 只是它的两种过滤视图。
 *  2. [parseJcIds]：解析 `cp.titan007.com/buy/JingCai.aspx`，得到 titanId -> 竞彩编号 的映射。
 *     竞彩 tab = 全量里 titanId 在该集合的场次；同时用于把大小球（dx101）关联到竞彩场次。
 *     🔴 **要拉多期**：默认页只有「当前一期」（实测 19 行、仅 10 场落在 bfdata 里），
 *     昨天那期要用 `?typeID=101&oddstype=2&date=yyyy-m-d` 单独取（实测 25 场全部命中）。
 *     见 [com.example.jingcai.data.repository.LiveScoreRepository.loadHistJc]。
 *  3. [parseOverUnder]：解析 `cp.titan007.com/data/dx101.xml` 的大小球盘口（key = 竞彩编号，仅竞彩）。
 *  4. [parseGoalBf3]：解析 `livestatic.titan007.com/vbsxml/ch_goalbf3.xml` 的大小球盘口（key = titanId，
 *     **覆盖全部比赛**，参考站每 4 秒轮询）。[applyGoalBf3] 用它覆盖 dx101，使「完整/热门」tab 也有大小球。
 *
 * 实时更新走 [parseChanges] / [applyChanges]：源是 `cp.titan007.com/info/match/data/change.xml`，
 * **覆盖全部进行中比赛（竞彩 + 非竞彩）**，每 5 秒 tick 合并比分 / 状态 / 进行分钟数。
 *
 * ⚠️ **官方补时（「补N'」）不来自上面的任何源**——bfdata / change.xml 都没有举牌补时字段。
 * 它唯一来自**手机版** `https://m.titan007.com/phone/livechange.txt`（移动站 `ScoreWs.js` 的 `zqlivechange`
 * 轮询流），见 [parseLiveChange] / [applyInjuryTime]：字段 [21] = `injuryTime`，未宣布即为空串。
 * 之前误用 bfdata `[22]/[23]`（比赛统计）当补时，导致「补时没出来却显示 13'」的 bug，已废弃。
 *
 * ===== bfdata_ut.js 字段契约（A[i] 以 `^` 分隔，已逐行对照 live.titan007.com 截图验证） =====
 *  [0] titanId  [1] 联赛色  [2] 联赛名  [5] 主队  [8] 客队  [11] 开赛时间(HH:mm)
 *  [13] 状态(0未/1上/2中/3下/-1完/负数为延期腰斩)  [14] 主进球  [15] 客进球
 *  [16] 主半场  [17] 客半场  [22] 主队排名  [23] 客队排名  [45] 联赛 sclassID
 *  B[i]：`B[i][4]`=sclassID、`B[i][10]`!=0 即热门联赛。
 */
object TitanLiveParser {

    /** 大小球公司号（dx101.xml 里实测全覆盖竞彩场次）。 */
    private const val OU_COMPANY_ID = "3"

    /**
     * 盘口换算表（源自 titan007 `GoalCn`），**逐字照抄**。
     * 索引 = `Math.round(goal * 4)`。
     */
    private val GOAL_CN = arrayOf(
        "0", "0/0.5", "0.5", "0.5/1", "1", "1/1.5", "1.5", "1.5/2", "2", "2/2.5", "2.5", "2.5/3",
        "3", "3/3.5", "3.5", "3.5/4", "4", "4/4.5", "4.5", "4.5/5", "5", "5/5.5", "5.5", "5.5/6",
        "6", "6/6.5", "6.5", "6.5/7", "7", "7/7.5", "7.5", "7.5/8", "8", "8/8.5", "8.5", "8.5/9",
        "9", "9/9.5", "9.5", "9.5/10", "10", "10/10.5", "10.5", "10.5/11", "11", "11/11.5", "11.5",
        "11.5/12", "12", "12/12.5", "12.5", "12.5/13", "13", "13/13.5", "13.5", "13.5/14", "14"
    )

    // ==================================================================================
    // 源 1：bfdata_ut.js —— 全量比赛 + 热门联赛集合
    // ==================================================================================

    /**
     * 由 bfdata 的 [12]（当前半场开始时刻，格式 `Y,M,D,h,mi,s` 的 JS 0 起月份）算出进行分钟数。
     * 这是 live.titan007.com 显示「83' / 45+1'」的真源：
     *  - state 1/2（上半场 / 中场）：基准 0；
     *  - state 3（下半场）：基准 45（下半场开始时刻已是 14:03 这种，增量叠加）；
     *  - state 4（加时上半）：基准 90。
     * 用 Asia/Shanghai 时区解析，不受设备时区影响。非进行中（state 不在 1~4）返回 0。
     */
    /**
     * 由 bfdata 的 [12]（当前半场开始时刻）算出进行分钟数。
     * [nowMs] 默认用设备时钟；由仓库层传入 titan007 服务器校正后的时间（修正设备时钟漂移，
     * 否则进行分钟数会整体偏慢约 1 分钟，参见 [com.example.jingcai.data.repository.LiveScoreRepository]）。
     */
    private fun computeElapsedMin(state: Int, dateStr: String, nowMs: Long = System.currentTimeMillis()): Int {
        if (state !in 1..4) return 0
        val p = dateStr.split(',')
        if (p.size < 6) return 0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            // Calendar 的 month 与 JS Date 同为零起，故 p[1] 直接传入。
            cal.set(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt())
        } catch (_: Exception) {
            return 0
        }
        val base = when (state) { 3 -> 45; 4 -> 90; else -> 0 }
        val mins = ((nowMs - cal.timeInMillis) / 60_000L).toInt()
        return if (mins < 0) 0 else base + mins
    }

    /** 排名字段末尾的名次数字（`9` → `9`、`秋11` → `11`、`埃及超2` → `2`、空/无数字 → 空串）。 */
    private val RANK_TAIL_RE = Regex("(\\d+)\\s*$")

    /** HTML 标签（`<font color="#FF0000">` / `</font>` / `<b>` …）。 */
    private val HTML_TAG_RE = Regex("<[^>]*>")

    /**
     * 队名 / 联赛名清洗：**剥掉源站混进来的 HTML 标签**。
     *
     * 🔴 bfdata 的部分队名带 `<font color=...>` 高亮片段（实测
     *    `巴勒斯坦 U17<font color="#FF0000">`、`塔吉克斯坦 U19<font color="#0000FF">`），
     *    原样塞进 Compose 的 `Text` 会把尖括号当正文画出来 ⇒「比分直播」列表里出现
     *    「巴勒斯坦 U17<font…」（用户 2026-11-17 报障）。参考站是用 innerHTML 渲染的，
     *    所以它能吃 tag、我们不能。
     *
     * 兜底：标签没写全（缺 `>`）时，从第一个 `<` 起整段丢掉 —— 否则残留的 `<font color=`
     * 比丢掉半截队名更难看；实测数据里这种残缺只出现在被截断的右半截。
     */
    private fun cleanName(raw: String): String {
        val noTag = HTML_TAG_RE.replace(raw, "").replace('\u00A0', ' ')
        val lt = noTag.indexOf('<')
        return (if (lt >= 0) noTag.substring(0, lt) else noTag).trim()
    }

    /**
     * bfdata 的排名字段归一成**纯名次**。
     *
     * 源站同一字段混了两种格式（实测 750 场逐行统计）：
     *  - 单阶段联赛：纯数字 `9`；
     *  - 分阶段 / 分区联赛：`秋11`（秋季赛第 11）、`北3`（北区第 3）、`埃及超2`。
     * 前缀是阶段/分区名，对「一眼看排名」没用，反而把只有 60dp 的队名区挤掉一个字 ⇒ 只留末尾数字。
     * `0` / 纯 0 视为无效（源站没排名时给 0），返回空串而不是显示「第 0 名」。
     */
    private fun rankOf(raw: String): String {
        val m = RANK_TAIL_RE.find(raw.trim()) ?: return ""
        val n = m.groupValues[1].trimStart('0')
        return if (n.isEmpty()) "" else n
    }

    /**
     * 解析 `bfdata_ut.js`。返回 [RawLive]（[RawLive.matches] 已含初值：比分来自 [14][15]、状态来自 [13]）。
     * 解析失败 / 字段不足的行直接跳过，绝不抛异常。
     */
    fun parseBfData(js: String, nowMs: Long = System.currentTimeMillis()): RawLive {
        val matches = ArrayList<LiveMatch>()
        val hot = HashSet<String>()

        // A[i]="...".split('^') —— 比赛
        val aRe = Regex("A\\[(\\d+)\\]=\"([^\"]+)\"")
        for (m in aRe.findAll(js)) {
            val d = m.groupValues[2].split('^')
            if (d.size < 16) continue
            val titanId = d[0].toLongOrNull() ?: continue
            val league = cleanName(d[2])
            val color = d[1]
            val time = d[11]
            val home = cleanName(d[5])
            val away = cleanName(d[8])
            val sclassId = if (d.size > 45) d[45] else ""
            val state = d[13].toIntOrNull() ?: 0
            val scoreH = d[14].toIntOrNull() ?: 0
            val scoreA = d[15].toIntOrNull() ?: 0
            // ⚠️ bfdata 没有「官方补时」字段：其 [22]/[23] 实测是**联赛排名**（`9` / `秋11` / `北3`），
            //    既不是补时也不是射门/角球。补时一律来自手机版 livechange.txt 的字段 [21]。
            val homeRank = rankOf(if (d.size > 22) d[22] else "")
            val awayRank = rankOf(if (d.size > 23) d[23] else "")
            // 未开始且 0-0 → 显示 "-"；其余显示 "主-客"（完场/进行中都有真实比分）。
            val score = if (state == 0 && scoreH == 0 && scoreA == 0) "-" else "$scoreH-$scoreA"
            // 半场比分：bfdata [16]=主半场进球、[17]=客半场进球（整数，未到半场/缺字段则 0）。
            val homeHalf = if (d.size > 16) d[16].toIntOrNull() ?: 0 else 0
            val awayHalf = if (d.size > 17) d[17].toIntOrNull() ?: 0 else 0
            // 红黄牌：字段索引来自源站 jcLive.js 的增量分支
            // （`D[6]!=A[i][18]` → redcard1_、`D[12]!=A[i][20]` → yellow1_），不是猜的。
            val homeRed = if (d.size > 18) d[18].toIntOrNull() ?: 0 else 0
            val awayRed = if (d.size > 19) d[19].toIntOrNull() ?: 0 else 0
            val homeYellow = if (d.size > 20) d[20].toIntOrNull() ?: 0 else 0
            val awayYellow = if (d.size > 21) d[21].toIntOrNull() ?: 0 else 0
            // 进行中初始进行分钟数：直接由 bfdata 的 [12]（当前半场开始时刻）算出，保证首屏即有红「83'」。
            // change.xml 不带时钟（D[8] 实为开赛时间），分钟数只信这里；state 变化时 applyChanges 用 halfStart 重算。
            val halfStart = if (d.size > 12) d[12] else ""
            val elapsedMin = computeElapsedMin(state, halfStart, nowMs)
            matches.add(
                LiveMatch(
                    titanId = titanId,
                    matchId = titanId.toString(),
                    num = "",
                    league = league,
                    color = color,
                    sclassId = sclassId,
                    time = time,
                    home = home,
                    away = away,
                    homeRank = homeRank,
                    awayRank = awayRank,
                    score = score,
                    homeHalf = homeHalf,
                    awayHalf = awayHalf,
                    homeYellow = homeYellow,
                    awayYellow = awayYellow,
                    homeRed = homeRed,
                    awayRed = awayRed,
                    ouHome = "-", ouGoal = "-", ouAway = "-",
                    elapsedMin = elapsedMin,
                    halfStart = halfStart,
                    state = state,
                    spfH = "", spfD = "", spfA = "",
                    handicap = "",
                    rqH = "", rqD = "", rqA = "",
                    striped = false,
                    jcNum = "",
                    swap = false
                )
            )
        }

        // B[i]="...".split('^') —— 联赛；B[i][10] != "0" 为热门联赛 → 记录其 sclassID(B[i][4])
        val bRe = Regex("B\\[(\\d+)\\]=\"([^\"]+)\"")
        for (m in bRe.findAll(js)) {
            val d = m.groupValues[2].split('^')
            if (d.size > 10 && d[10] != "0" && d.size > 4) hot.add(d[4])
        }

        return RawLive(matches, hot, emptySet())
    }

    // ==================================================================================
    // 源 2：JingCai.aspx —— titanId -> 竞彩编号
    // ==================================================================================

    /**
     * 竞彩页里一场比赛的元信息。
     *
     * @param matchId 站点内部竞彩 matchid（`tr@matchid`，形如 `5572007`），dx101 与赔率表都用它做键
     * @param spid 赔率变动窗口 id（「变」字链接里的 `spid`），一场一个，胜平负/让球共用
     * @param order **官方编号**「周一007」：`tr@name` 是「周一」，首格编号是「007」
     */
    data class JcRow(val matchId: String, val spid: String, val order: String)

    /** `tr@matchid` 行里首格的编号（图片 + 三位数字）。 */
    private val JC_NO_RE = Regex("^\\d{3}$")

    /** 「变」字链接：`ShowOddsWinow('/Handle/JcSp.aspx?oddsType=3&spid=257049&typeID=105', ...)` */
    private val JC_SPID_RE = Regex("spid=(\\d+)")

    /**
     * 解析 `JingCai.aspx` 每行的**元信息**（内部 matchid / spid / 官方编号），键为 titanId。
     *
     * 实测结构：
     * ```
     * <tr id="row_5572007" name="周一" ... matchid="5572007">
     *   <td ...><img src="/images/e.gif"/>007</td>
     *   ... <a id="HomeTeam_3013744">拉科</a> ...
     *   <table class='spfItem'><tr id="spfTr_5572007">
     *      ... <a onclick="ShowOddsWinow('/Handle/JcSp.aspx?oddsType=3&spid=257049&typeID=105',...)">变</a>
     * ```
     * ⚠️ `spid` 必须从 **`spfTr_{matchId}` 内部**取：紧跟的 `rqTr_{matchId}`（让球）里也有一个
     *    「变」链接，虽然 spid 相同、但 `typeID=101`，取错了查到的就是让球赔率。
     */
    fun parseJcRows(html: String): Map<String, JcRow> {
        val doc = Jsoup.parse(html)
        val out = HashMap<String, JcRow>()
        for (tr in doc.select("tr[matchid]")) {
            val mid = tr.attr("matchid").trim()
            if (mid.isEmpty()) continue
            val no = tr.selectFirst("td")?.text()?.trim()?.takeIf { JC_NO_RE.matches(it) } ?: ""
            val row = JcRow(
                matchId = mid,
                spid = tr.selectFirst("tr[id=\"spfTr_$mid\"] a[onclick*=ShowOddsWinow]")
                    ?.attr("onclick")?.let { JC_SPID_RE.find(it)?.groupValues?.get(1) } ?: "",
                order = tr.attr("name").trim() + no
            )
            tr.selectFirst("a[id^=HomeTeam_]")?.id()?.removePrefix("HomeTeam_")?.let { out[it] = row }
            tr.selectFirst("a[id^=GuestTeam_]")?.id()?.removePrefix("GuestTeam_")?.let { out[it] = row }
        }
        return out
    }

    /** 只要 `titanId -> 竞彩内部 matchid` 的旧入口（dx101 大小球关联继续用它）。 */
    fun parseJcIds(html: String): Map<String, String> =
        parseJcRows(html).mapValues { it.value.matchId }

    /**
     * 解析**竞彩胜平负赔率变动**记录（`Handle/JcSp.aspx?oddsType=3&spid=…&typeID=105`）。
     *
     * 返回体是一张只有四列的小表：
     * ```html
     * <tr bgcolor=white align=center height=22>
     *   <td><b>2.87</b></td><td><b>4</b></td><td><b>1.85</b></td><td>11-01 22:04</td>
     * </tr>
     * ```
     * 🔴 源站是**时间倒序**（最新一条在最上面），这里统一翻成**正序**，否则图表横轴会从右往左走。
     *    「只有一条记录」的场次（多数未变动场次）原样返回。
     */
    fun parseJcSpfHistory(html: String): List<JcSpfChange> {
        val list = JC_SP_ROW_RE.findAll(html).map { m ->
            JcSpfChange(
                time = m.groupValues[4].trim(),
                h = m.groupValues[1].trim(),
                d = m.groupValues[2].trim(),
                a = m.groupValues[3].trim()
            )
        }.toList()
        // `MM-dd HH:mm` 定宽 ⇒ 字典序即时间序；首 > 尾说明是倒序，翻正。
        return if (list.size > 1 && list.first().time > list.last().time) list.reversed() else list
    }

    /** JcSp.aspx 的一条记录：胜/平/负 + 变化时间。 */
    private val JC_SP_ROW_RE = Regex(
        "<td><b>([^<]*)</b></td>\\s*<td><b>([^<]*)</b></td>\\s*<td><b>([^<]*)</b></td>\\s*<td>([^<]*)</td>"
    )

    /**
     * 主入口：把**编号 / 官方编号 / 赔率变动 id / 胜平负赔率**一次性写进 [matches]。
     *
     * `spid` 与 `jcOrder` 与赔率同源同页，必须在这一次 copy 里一起写 ——
     * 否则会出现「有赔率但点不动变动图标」的半套状态。
     */
    fun applySpf(
        matches: List<LiveMatch>,
        rows: Map<String, JcRow>,
        spf: Map<String, Triple<String, String, String>>
    ): List<LiveMatch> = matches.map { m ->
        val row = rows[m.titanId.toString()] ?: return@map m
        val s = spf[row.matchId]
        m.copy(
            jcNum = row.matchId,
            jcOrder = row.order.ifEmpty { m.jcOrder },
            spid = row.spid.ifEmpty { m.spid },
            spfH = s?.first ?: m.spfH,
            spfD = s?.second ?: m.spfD,
            spfA = s?.third ?: m.spfA
        )
    }

    /**
     * 解析同页面内嵌的**竞彩胜平负赔率**（每行末尾那张 `spfItem` 小表）。
     *
     * 实测结构（逐字照抄，别再猜）：
     * ```
     * <table class='spfItem'><tr id="spfTr_5572007">
     *   <td id="cell_5572007_52" title="主胜"><span id="sp_5572007_52">2.05</span></td>
     *   <td id="cell_5572007_53" title="平局"><span id="sp_5572007_53">3.00</span></td>
     *   <td id="cell_5572007_54" title="客胜"><span id="sp_5572007_54">3.15</span></td>
     * </tr></table>
     * ```
     * ⚠️ 紧跟其后的 `rqTr_{matchId}` 是**让球**胜平负（`_1/_2/_3`），别混进来；
     *    这里只认 `tr[id^=spfTr_]` 里的 span。
     * ⚠️ 完场/停售场次的 td 没有 `bgcolor`（不可点），但 span 里的赔率**仍然保留**——
     *    这正是历史场次也能显示赔率的原因（实测 11-01 那期 25 场全部有赔率）。
     *
     * 返回 `matchId -> Triple(主胜, 平, 客胜)`；三项缺一即整条丢弃（不做半套展示）。
     */
    fun parseSpf(html: String): Map<String, Triple<String, String, String>> {
        val doc = Jsoup.parse(html)
        val out = HashMap<String, Triple<String, String, String>>()
        for (tr in doc.select("tr[id^=spfTr_]")) {
            val mid = tr.id().removePrefix("spfTr_").trim()
            if (mid.isEmpty()) continue
            fun v(suffix: String): String =
                tr.selectFirst("span[id=sp_" + mid + "_" + suffix + "]")
                    ?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: ""
            val h = v("52")
            val d = v("53")
            val a = v("54")
            if (h.isEmpty() || d.isEmpty() || a.isEmpty()) continue
            out[mid] = Triple(h, d, a)
        }
        return out
    }

    // ==================================================================================
    // 源 3：dx101.xml —— 大小球（key = 竞彩编号）
    // ==================================================================================
    /** 大小球公司号：3（实测竞彩场次全覆盖）。 */

    /**
     * 解析 dx101.xml。返回 `竞彩编号 -> Triple(主水, 盘口, 客水)`。
     * 每行 `<i>companyId,matchNum,oddsId,home,goal,away</i>`；只取 [OU_COMPANY_ID]，BOM 先剥掉。
     */
    fun parseOverUnder(xml: String): Map<String, Triple<String, String, String>> {
        val text = xml.replace("\uFEFF", "")
        val out = HashMap<String, Triple<String, String, String>>()
        val re = Regex("<i>([^<]+)</i>")
        for (m in re.findAll(text)) {
            val parts = m.groupValues[1].split(",")
            if (parts.size < 6) continue
            if (parts[0].trim() != OU_COMPANY_ID) continue
            val matchNum = parts[1].trim()
            if (matchNum.isEmpty()) continue
            out[matchNum] = Triple(parts[3].trim(), goalToCn(parts[4].trim()), parts[5].trim())
        }
        return out
    }

    /** 盘口数值 → 中文盘口（如 `2.75 -> 2.5/3`）。越界则原样返回。 */
    private fun goalToCn(goal: String): String {
        val g = goal.toDoubleOrNull() ?: return goal
        val idx = Math.round(g * 4.0).toInt()
        return if (idx in GOAL_CN.indices) GOAL_CN[idx] else goal
    }

    /**
     * 把 dx101 大小球按 `titanId -> 竞彩编号` 映射填入 [matches]（仅竞彩场次有值，其余保持三个 `-`）。
     */
    fun applyOverUnder(
        matches: List<LiveMatch>,
        ou: Map<String, Triple<String, String, String>>,
        jcMap: Map<String, String>
    ): List<LiveMatch> = matches.map { m ->
        val jc = jcMap[m.titanId.toString()]
        val o = if (jc != null) ou[jc] else null
        if (o == null) {
            m
        } else {
            m.copy(ouHome = o.first, ouGoal = o.second, ouAway = o.third, jcNum = jc ?: "")
        }
    }

    // ==================================================================================
    // 比分增量：change.xml（5 秒 tick 用，覆盖全部进行中比赛）
    // ==================================================================================

    /**
     * 解析 `change.xml`（cp.titan007，CDATA，`D = text.split("^")`）：
     * `D[0]`=titanId、`D[1]`=state、`D[2]`=主进球、`D[3]`=客进球。
     * ⚠️ `D[8]` 实测是**开赛时间 HH:mm**（如 `13:30`），不是进行时钟 —— 别拿来当分钟数。
     * 字段数 < 4 的条目整条丢弃，绝不抛异常。
     */
    fun parseChanges(xml: String): List<LiveChange> {
        val text = xml.replace("\uFEFF", "")
        val out = ArrayList<LiveChange>()
        val re = Regex("<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>")
        for (m in re.findAll(text)) {
            val parts = m.groupValues[1].split("^")
            if (parts.size < 4) continue
            val tid = parts[0].trim()
            if (tid.isEmpty()) continue
            val state = parts[1].trim().toIntOrNull() ?: continue
            val home = parts[2].trim().toIntOrNull() ?: 0
            val away = parts[3].trim().toIntOrNull() ?: 0
            // 红黄牌：D[6]/D[7]=主/客红，D[12]/D[13]=主/客黄（见 jcLive.js 的 redcard/yellow 分支）。
            // 空串 = 本条没带该字段 ⇒ 保持 -1（apply 时跳过），绝不当成 0 张。
            fun card(i: Int): Int =
                parts.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: -1
            out.add(
                LiveChange(
                    titanId = tid, state = state, home = home, away = away,
                    homeRed = card(6), awayRed = card(7),
                    homeYellow = card(12), awayYellow = card(13)
                )
            )
        }
        return out
    }

    /**
     * 把 [changes] 按 **titanId** 并入 [matches] 的比分 / 状态（后到覆盖先到）。
     * `state == 0`（未开始）比分保持 `-`；否则 `"主-客"`。
     * 状态发生变化时用 [LiveMatch.halfStart]（bfdata [12] 半场开始时刻）**重算**进行分钟数
     * —— change.xml 不带时钟，这是唯一正确的分钟来源。
     * ⚠️ 只覆盖 [changes] 中**存在**的场次；缺失的场次保持原状（不会因某次增量没回传而误清状态）。
     */
    fun applyChanges(matches: List<LiveMatch>, changes: List<LiveChange>, nowMs: Long = System.currentTimeMillis()): List<LiveMatch> {
        if (changes.isEmpty()) return matches
        val byTitan = HashMap<String, LiveChange>()
        for (c in changes) byTitan[c.titanId] = c
        return matches.map { m ->
            val c = byTitan[m.titanId.toString()]
            if (c == null) {
                m
            } else {
                val score = if (c.state == 0) "-" else "${c.home}-${c.away}"
                val elapsed =
                    if (c.state != m.state) computeElapsedMin(c.state, m.halfStart, nowMs) else m.elapsedMin
                // 进球闪色：比分上涨的一侧（主=1 / 客=2）；无新进球保留原闪色（30 秒窗口由 UI 判定）。
                // 比分**回退** = 进球被取消（VAR 改判）：同样闪 30 秒，但用 flashCancel=true 走另一套底色。
                val (oh, oa) = parseScore(m.score)
                val side = when {
                    c.home > oh -> 1
                    c.away > oa -> 2
                    c.home < oh -> 1
                    c.away < oa -> 2
                    else -> 0
                }
                // 🔴 state=0（未开始）时比分被写成 "-"，解析成 0-0，会把进行中的比分看成「回退」。
                //    这是唯一需要排除的情形；正常比赛比分不会凭空清空。
                val cancel = c.state != 0 && side != 0 &&
                    (c.home < oh || c.away < oa)
                val flashSide = if (side != 0) side else m.flashSide
                val flashAt = if (side != 0) nowMs else m.flashAt
                val flashCancel = if (side != 0) cancel else m.flashCancel
                m.copy(
                    score = score,
                    state = c.state,
                    elapsedMin = elapsed,
                    flashSide = flashSide,
                    flashAt = flashAt,
                    flashCancel = flashCancel,
                    // 红黄牌：只在增量**带了该字段**（>= 0）时覆盖，否则保留 bfdata 的初值
                    homeRed = if (c.homeRed >= 0) c.homeRed else m.homeRed,
                    awayRed = if (c.awayRed >= 0) c.awayRed else m.awayRed,
                    homeYellow = if (c.homeYellow >= 0) c.homeYellow else m.homeYellow,
                    awayYellow = if (c.awayYellow >= 0) c.awayYellow else m.awayYellow
                )
            }
        }
    }

    /** `"1-2"` → `(1, 2)`；`"-"` 或非法 → `(0, 0)`。公开以便 ViewModel 的 [mergeFlash] 复用进球检测。 */
    fun parseScore(score: String): Pair<Int, Int> {
        if (score == "-") return 0 to 0
        val p = score.split('-')
        if (p.size != 2) return 0 to 0
        return (p[0].trim().toIntOrNull() ?: 0) to (p[1].trim().toIntOrNull() ?: 0)
    }

    // ==================================================================================
    // 源 3.5：手机版 livechange.txt —— 官方举牌补时（**唯一正确来源**）
    // ==================================================================================

    /**
     * 解析**手机版 titan007** 的 `livechange.txt`（`https://m.titan007.com/phone/livechange.txt`），
     * 返回 `titanId -> 官方补时(injuryTime 字符串)`。
     *
     * 该文件是移动站 `ScoreWs.js` 实时轮询的变更流（`zqScoreChange` 把整段按 `!` 切分，每段 `new QtliveChange`，
     * `QtliveChange` 再按 `^` 切分字段）：
     *  - 段内字段 [0] = `scheduleid`（= titanId）；[21] = `injuryTime`（补时，**未宣布则为空串**）；
     *  - 整段以 `!` 拼接多场，单场又以 `^` 分隔字段。
     *
     * 返回映射**只含 injuryTime 非空**的场次（未宣布补时的场次不进映射，调用方据此决定是否回落）。
     * 解析失败 / 字段不足 / 非法 titanId 的段直接跳过，绝不抛异常。
     */
    fun parseLiveChange(txt: String): Map<Long, String> {
        val out = HashMap<Long, String>()
        for (rec in txt.split('!')) {
            if (rec.isEmpty()) continue
            val f = rec.split('^')
            if (f.size < 2) continue
            val tid = f[0].toLongOrNull() ?: continue
            val inj = if (f.size > 21) f[21].trim() else ""
            if (inj.isNotEmpty()) out[tid] = inj
        }
        return out
    }

    /**
     * 把官方补时 [map] 并入 [matches]（按 titanId）。
     *  - 命中且值不同 → 覆盖 [LiveMatch.injuryTime]；
     *  - 未命中 → **保持原值不变**（移动站是增量流，没出现的场次沿用上一次的值，不回退）。
     *  真正的回落由 [LiveScreen] 的 [状态] 判定保证：仅 `state ∈ {1,3}` 才显示「补N'」，其余回退时间列显示开赛时间。
     */
    fun applyInjuryTime(matches: List<LiveMatch>, map: Map<Long, String>): List<LiveMatch> = matches.map { m ->
        val inj = map[m.titanId]
        if (inj == null || inj == m.injuryTime) m else m.copy(injuryTime = inj)
    }

    // ==================================================================================
    // 源 4：ch_goalbf3.xml —— 大小球（key = titanId，**覆盖全部比赛**，不只竞彩）
    // ==================================================================================

    /**
     * 解析 `ch_goalbf3.xml`（`livestatic.titan007.com/vbsxml/ch_goalbf3.xml?r=007`）。
     * 参考站 `commonWs.js` 每 4 秒轮询此文件，覆盖**所有**比赛的大小球（dx101 只覆盖竞彩）。
     * 每行 `<m>...</m>` 以逗号分隔，实测契约：
     *  `[0]`=titanId（关联键，= bfdata `A[i][0]`）`[10]`=盘口(数值) `[11]`=大球水 `[12]`=小球水。
     * 返回 `titanId(字符串) -> Triple(大球水, 盘口中文, 小球水)`。
     */
    fun parseGoalBf3(xml: String): Map<String, Triple<String, String, String>> {
        val text = xml.replace("\uFEFF", "")
        val out = HashMap<String, Triple<String, String, String>>()
        val re = Regex("<m>([^<]+)</m>")
        for (m in re.findAll(text)) {
            val parts = m.groupValues[1].split(",")
            if (parts.size < 13) continue
            val tid = parts[0].trim()
            if (tid.isEmpty()) continue
            out[tid] = Triple(parts[11].trim(), goalToCn(parts[10].trim()), parts[12].trim())
        }
        return out
    }

    /**
     * 把 ch_goalbf3 的「全量大小球」按 titanId 填入 [matches]，覆盖 `applyOverUnder`（dx101，仅竞彩）的结果。
     * 无大小球的场次（ch_goalbf3 无对应行）保持三个 `-`。
     */
    fun applyGoalBf3(
        matches: List<LiveMatch>,
        goalBf3: Map<String, Triple<String, String, String>>
    ): List<LiveMatch> = matches.map { m ->
        val g = goalBf3[m.titanId.toString()]
        if (g == null) m else m.copy(ouHome = g.first, ouGoal = g.second, ouAway = g.third)
    }

    // ==================================================================================
    // 源 5：detail_ut.js —— 角球 / 半场角球（**覆盖全部比赛**，一处就够）
    // ==================================================================================

    /**
     * 一场比赛的角球统计。
     *
     * - [home]/[away]：全场角球（`tc` 里的 code `6`）；
     * - [halfHome]/[halfAway]：半场角球（code `45`）；
     * - `-1` = 该场没有这一项（未开赛 / 源站还没出技统），与「0 个角球」严格区分。
     */
    data class Corners(val home: Int, val away: Int, val halfHome: Int, val halfAway: Int)

    /**
     * `tc` 数组里两条我们需要的统计项编码。**逐场比对详情页 `techStatisticData` 实测确认**：
     *  - `6`  = 角球（2910963：tc `6,15,2` ↔ 详情页 `[0,'15','2']`；3010084：`6,7,2` ↔ `'7','2'`）；
     *  - `45` = 半场角球（2910963：`45,5,1` ↔ 详情页下一组 `[0,'5','1']`）；
     *  - 顺带记录：`3`=射门、`4`=射正、`11`=黄牌——它们的值也和详情页逐项对上，
     *    说明这套编码不是猜的（猜错就是「角球显示成射门」这种静默错数据）。
     */
    private const val TC_CODE_CORNER = "6"
    private const val TC_CODE_HALF_CORNER = "45"

    /** `tc[i]="2910963^6,15,2;45,5,1;11,3,4;..."`（`^` 后是 `code,主,客` 以 `;` 分隔的列表）。 */
    private val TC_RE = Regex("tc\\[(\\d+)\\]=\"([^\"]*)\"")

    /**
     * 解析 `livestatic.titan007.com/vbsxml/detail_ut.js`（~247KB，全量 288 场的技术统计快照）。
     *
     * 返回 `titanId -> [Corners]`，**只含角球数据齐全的场次**（缺 6 或 45 的当场缺项给 -1）。
     * 结构：`var tc=Array(288); tc[0]="2910957^6,7,0;45,2,0;..."`。
     * 解析失败 / 字段不足的条目整条跳过，绝不抛异常（沿用本文件一贯的容错风格）。
     */
    fun parseCorners(js: String): Map<String, Corners> {
        val out = HashMap<String, Corners>()
        for (m in TC_RE.findAll(js)) {
            val parts = m.groupValues[2].split('^')
            if (parts.size < 2) continue
            val tid = parts[0].trim()
            if (tid.isEmpty()) continue
            var h = -1; var a = -1; var hh = -1; var ha = -1
            for (kv in parts[1].split(';')) {
                val p = kv.split(',')
                if (p.size < 3) continue
                val v1 = p[1].trim().toIntOrNull() ?: continue
                val v2 = p[2].trim().toIntOrNull() ?: continue
                when (p[0].trim()) {
                    TC_CODE_CORNER -> { h = v1; a = v2 }
                    TC_CODE_HALF_CORNER -> { hh = v1; ha = v2 }
                    else -> Unit
                }
            }
            if (h < 0 || a < 0) continue
            out[tid] = Corners(h, a, hh, ha)
        }
        return out
    }

    /**
     * 把角球按 titanId 并入 [matches]。没有数据的场次保持 `-1`（**不写 0**）。
     * `tc` 里查不到的场次（未开赛 / 冷门联赛）整行不动。
     */
    fun applyCorners(matches: List<LiveMatch>, corners: Map<String, Corners>): List<LiveMatch> {
        if (corners.isEmpty()) return matches
        return matches.map { m ->
            val c = corners[m.titanId.toString()] ?: return@map m
            if (c.home == m.homeCorner && c.away == m.awayCorner &&
                c.halfHome == m.homeHalfCorner && c.halfAway == m.awayHalfCorner
            ) {
                m
            } else {
                m.copy(
                    homeCorner = c.home, awayCorner = c.away,
                    homeHalfCorner = c.halfHome, awayHalfCorner = c.halfAway
                )
            }
        }
    }
}
