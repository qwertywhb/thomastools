package com.example.jingcai.data.network

import com.example.jingcai.data.model.EventKind
import com.example.jingcai.data.model.FlashPoint
import com.example.jingcai.data.model.FlashSchedule
import com.example.jingcai.data.model.LineupMark
import com.example.jingcai.data.model.LineupPlayer
import com.example.jingcai.data.model.MatchEvent
import com.example.jingcai.data.model.MatchInjuries
import com.example.jingcai.data.model.MatchLineup
import com.example.jingcai.data.model.PlayerMark
import com.example.jingcai.data.model.TechSection
import com.example.jingcai.data.model.TechStat
import com.example.jingcai.data.model.TeamLineup
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 「动画直播」详情页解析器 —— **纯函数，不发网络请求**（方便离线自检）。
 *
 * 字段契约来自源站脚本实测（`flashDetail/script/eventNew.js`、`scoreOddsNew.js`）：
 *  - 记录分隔 `!`、字段分隔 `^`、子字段分隔 `,`（`_glflash.Domain="$$" / DataType="!" / SplitRecord="^" / SplitColumn=","`）。
 *  - **全量** `flashdata/get?id=`：段 [0]=场次头、[5]=当前球点。
 *  - **增量** `flashdata/get?chid=`：段 [0]=`sId^主分^客分^state^比赛时间`、段 [3]=球点。
 *  - 球点：`pId,teamID,pX,pY,eventId`（pX/pY 归一化 0..1）。
 *  - 技术统计 `detailin.js` 的 `tT_f[id]=[[kind,主,客,主%,客%],...]`，kind 索引 `techKindNameList`。
 *  - 完场技术统计：`detail/<id>cn.htm` 里**服务端渲染**好的 `li.lists`（`detailin.js` 只有 12~13 场滚动窗口，完场早已滚出）。
 */
object TitanDetailParser {

    /**
     * 源站 `techKindNameList`（`live.titan007.com/scoreOddsNew.js` 里的中文本），**下标即 kind**。
     * 全量 60 项都要保留：kind 47/52/53 是「预期进球(xG) / 得分机会 / 错失得分机会」，
     * 只有完场详情页内联的 `techStatisticData` 才带，`detailin.js` 的滚动快照里没有。
     */
    private val TECH_NAMES = listOf(
        "角球", "半场角球", "黄牌", "红牌", "射门", "射正", "进攻",
        "危险进攻", "射门不中", "射门被挡", "任意球", "控球率", "半场控球率",
        "传球", "传球成功率", "犯规", "越位", "头球", "头球成功", "救球", "铲球",
        "换人数", "过人", "界外球", "中柱", "角球数（加时）", "越位（加时）", "乌龙球数",
        "黄牌数（加时）", "守门员出击", "丟球", "成功抢断", "阻截", "成功传中", "助攻",
        "换人数（加时）", "长传", "短传", "先开球", "第一张黄牌", "最后一张黄牌",
        "第一个换人", "最后一个换人", "第一个角球", "最后一个角球", "第一个越位", "最后越位",
        "预期进球(xG)", "运动战预期进球", "定位球预期进球", "非点球预期进球", "射正预期进球",
        "得分机会", "错失得分机会", "禁区内射门", "禁区外射门", "对方禁区触球", "解围",
        "地面对抗成功", "争顶成功"
    )

    private val NAME_TO_KIND = TECH_NAMES.withIndex().associate { (i, n) -> n to i }

    /**
     * 「技术统计」展示顺序 —— 对齐官方「详细技术统计」里最常看的重要指标（11 行）：
     * 角球 / 射门 / 射正 / 任意球 / 控球率 / 传球成功率 / 犯规 / 越位 / xG / 得分机会 / 错失得分机会。
     * 与源站 `_detail.importantTechList` 同一批指标，只是把「头球」换成国内用户更关心的犯规/越位。
     */
    val TECH_ORDER = listOf(0, 4, 5, 10, 11, 14, 15, 16, 47, 52, 53)

    // ============ 动画：全量 ============

    /** 解析全量 flashdata 的段 [0]（场次头）。失败返回 null。*/
    fun parseFlashScheduleFull(txt: String): FlashSchedule? {
        val sec = txt.split('!')
        if (sec.isEmpty()) return null
        val a = sec[0].split('^')
        if (a.size < 9) return null
        return FlashSchedule(
            sId = a[0].toLongOrNull() ?: return null,
            homeTeamId = a.getOrNull(4) ?: "",
            guestTeamId = a.getOrNull(5) ?: "",
            homeScore = a.getOrNull(6)?.toIntOrNull() ?: 0,
            guestScore = a.getOrNull(7)?.toIntOrNull() ?: 0,
            state = a.getOrNull(8)?.toIntOrNull() ?: 0,
            // 全量段 [10] 是**开赛日期时间**（如 "2026,9,20,09,17,50"），不是当前分钟；
            // 分钟只由增量段 [4] 提供，故此处留空，避免状态栏显示成一长串日期。
            time = ""
        )
    }

    /**
     * 全量里的当前球点（段 [5]）。
     *
     * ⚠️ 当前**未被 UI 使用**：动画已改由 WebView 内嵌官方 `flashDetail/flash.html`。
     * 保留是因为它是球点协议（`pId,teamID,pX,pY,eventId`）的唯一书面记录，
     * 将来若要做离线回放可直接复用。
     */
    fun parseFlashInitialPoints(txt: String): List<FlashPoint> {
        val sec = txt.split('!')
        return parsePoints(sec.getOrNull(5) ?: "")
    }
    // ============ 动画：增量 ============

    /** 解析增量段 [0]：`sId^主分^客分^state^比赛时间`。失败返回 null。*/
    fun parseFlashScheduleChange(txt: String): FlashSchedule? {
        val sec = txt.split('!')
        if (sec.isEmpty()) return null
        val a = sec[0].split('^')
        if (a.size < 4) return null
        val sId = a[0].toLongOrNull() ?: return null
        return FlashSchedule(
            sId = sId,
            homeTeamId = "",
            guestTeamId = "",
            homeScore = a.getOrNull(1)?.toIntOrNull() ?: 0,
            guestScore = a.getOrNull(2)?.toIntOrNull() ?: 0,
            state = a.getOrNull(3)?.toIntOrNull() ?: 0,
            time = a.getOrNull(4) ?: ""
        )
    }

    /** 增量里的球点（段 [3]）。*/
    fun parseFlashChangePoints(txt: String): List<FlashPoint> {
        val sec = txt.split('!')
        return parsePoints(sec.getOrNull(3) ?: "")
    }

    /** `pId,teamID,pX,pY,eventId` 多条（`^` 分隔）；字段不足/非法坐标的行跳过。*/
    private fun parsePoints(sec: String): List<FlashPoint> {
        if (sec.isBlank()) return emptyList()
        val out = ArrayList<FlashPoint>()
        for (rec in sec.split('^')) {
            if (rec.isBlank()) continue
            val f = rec.split(',')
            if (f.size < 5) continue
            val pid = f[0].trim().toIntOrNull() ?: continue
            val x = f[2].trim().toFloatOrNull() ?: continue
            val y = f[3].trim().toFloatOrNull() ?: continue
            if (x !in 0f..1f || y !in 0f..1f) continue
            out.add(FlashPoint(pid, f[1].trim(), x, y, f[4].trim()))
        }
        return out
    }

    // ============ 技术统计 ============

    /**
     * 从 `detailin.js` 里取 `tT_f[<id>]` 并解析为按 [TECH_ORDER] 排序的 10 行。
     * 该场不在文件里 / 解析失败 → 空列表（调用方再退到 [parseTechFromDetailHtml]）。
     */
    fun parseTech(js: String, id: Long): List<TechStat> {
        // 外层数组形如 tT_f[2962688]=[[0,'3','4',43,57],[1,...]];
        val m = Regex("tT_f\\[$id\\]=(\\[\\[.*?\\]\\]);", RegexOption.DOT_MATCHES_ALL).find(js)
            ?: return emptyList()
        val byKind = HashMap<Int, TechStat>()
        for (em in Regex("\\[([^\\[\\]]*)\\]").findAll(m.groupValues[1])) {
            val parts = em.groupValues[1].split(',')
            if (parts.size < 5) continue
            val kind = parts[0].trim().toIntOrNull() ?: continue
            val name = TECH_NAMES.getOrNull(kind) ?: continue
            val (hp, gp) = bars(parts[3], parts[4])
            byKind[kind] = TechStat(
                kind = kind,
                name = name,
                home = cleanValue(parts[1]),
                guest = cleanValue(parts[2]),
                homePre = hp,
                guestPre = gp
            )
        }
        return TECH_ORDER.mapNotNull { byKind[it] }
    }

    // ============ 技术统计：完场（服务端渲染 HTML） ============

    /**
     * 从 `https://live.titan007.com/detail/<id>cn.htm` 的 HTML 里刮「本场技术统计」10 行。
     *
     * 完场页面是**服务端渲染**的，单行结构：
     * ```
     * <li class='lists'>
     *   <div class='barbox'><span class='Ht'><span class='bar' style='width:83%;'></span></span></div>
     *   <div class='data'><span class='red'>5</span><span>角球</span><span >1</span></div>
     *   <div class='barbox'><span class='Gt'><span class='bar' style='width:17%;'></span></span></div>
     * </li>
     * ```
     * 后面还有 19 个同 class 的 `li.lists` 是**事件时间轴**（无 `barbox`）→ 用 `barbox` 过滤。
     * 解析失败 / 页面里没有该板块（直播中页面由 JS 拉 detailin，不渲染）→ 空列表。
     */
    fun parseTechFromDetailHtml(html: String): List<TechStat> {
        if (html.isEmpty()) return emptyList()
        val byKind = HashMap<Int, TechStat>()
        for (chunk in html.split("<li class='lists'>")) {
            val blk = chunk.substringBefore("</li>")
            if (!blk.contains("barbox")) continue          // 事件时间轴行，跳过
            val dataHtml = Regex("class='data'>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                .find(blk)?.groupValues?.get(1) ?: continue
            val spans = Regex("<span[^>]*>([^<]*)</span>")
                .findAll(dataHtml).map { it.groupValues[1].trim() }.toList()
            if (spans.size < 3) continue
            val name = spans[1]
            val kind = NAME_TO_KIND[name] ?: continue
            val home = cleanValue(spans[0])
            val guest = cleanValue(spans[2])
            var homePre = Regex("class='Ht'>\\s*<span class='bar' style='width:\\s*(\\d+)%")
                .find(blk)?.groupValues?.get(1)?.toIntOrNull()
            var guestPre = Regex("class='Gt'>\\s*<span class='bar' style='width:\\s*(\\d+)%")
                .find(blk)?.groupValues?.get(1)?.toIntOrNull()
            if (homePre == null || guestPre == null) {
                // 条宽缺失时按数值自己算比例，保证条形图不空
                val h = num(home)
                val g = num(guest)
                val total = h + g
                homePre = if (total > 0) (h * 100 / total) else 50
                guestPre = 100 - homePre
            }
            byKind[kind] = TechStat(kind, name, home, guest, homePre, guestPre)
        }
        return TECH_ORDER.mapNotNull { byKind[it] }
    }

    // ============ 技术统计：详情页内联（全场 / 上半场 / 下半场） ============

    /**
     * 从详情页 HTML 内联的 `var techStatisticData = "[[0,[kind,主,客,主%,客%],...],[1,...],[2,...]]"`
     * 解析出**按 [TechSection] 分段**的技术统计。
     *
     * 这是**唯一能拿到完场统计**的来源（也是唯一带 xG / 得分机会的来源）：
     *  - `detailin.js` 的 `tT_f` 只有 12~13 场滚动窗口，完场早已滚出；
     *  - 完场页里的 `ul#teamTechDiv` 是**空的**，靠上面这个内联变量 + JS 渲染，
     *    所以旧的「刮 `li.lists` + `barbox`」在完场页上是**零行**（barbox 计数为 0）。
     *
     * 段下标：0 全场 / 1 上半场 / 2 下半场。进行中的比赛只下发第 0 段；未开赛为 `""`。
     */
    fun parseTechSections(html: String): Map<Int, List<TechStat>> {
        val m = Regex("var techStatisticData\\s*=\\s*\"(.*?)\";", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return emptyMap()
        val raw = m.groupValues[1].trim()
        if (raw.isEmpty() || raw == "[]") return emptyMap()
        val out = LinkedHashMap<Int, List<TechStat>>()
        for (seg in splitTopLevelArray(raw)) {
            val body = seg.trim().removePrefix("[").removeSuffix("]")
            val secIdx = body.substringBefore(',').trim().toIntOrNull() ?: continue
            if (secIdx !in TechSection.LABELS.indices) continue
            val rows = ArrayList<TechStat>()
            for (rm in Regex("\\[([^\\[\\]]*)\\]").findAll(body)) {
                val p = rm.groupValues[1].split(',')
                if (p.size < 5) continue
                val kind = p[0].trim().toIntOrNull() ?: continue
                val name = TECH_NAMES.getOrNull(kind) ?: continue
                val (hp, gp) = bars(p[3], p[4])
                rows.add(
                    TechStat(
                        kind = kind,
                        name = name,
                        home = cleanValue(p[1]),
                        guest = cleanValue(p[2]),
                        homePre = hp,
                        guestPre = gp
                    )
                )
            }
            out[secIdx] = TECH_ORDER.mapNotNull { k -> rows.firstOrNull { it.kind == k } }
        }
        return out
    }

    /**
     * 按 `[...],[...]` 的**顶层**逗号切分外层数组，内部嵌套的 `[` `]` 不参与切分。
     * 不能用正则 —— 数据里每一行本身也是 `[...]`，正则会切错。
     */
    private fun splitTopLevelArray(s: String): List<String> {
        val t = s.trim()
        val inner = if (t.startsWith("[") && t.endsWith("]")) t.substring(1, t.length - 1) else t
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < inner.length) {
            when (inner[i]) {
                '[' -> depth++
                ']' -> depth--
                ',' -> if (depth == 0) {
                    parts.add(inner.substring(start, i))
                    start = i + 1
                }
            }
            i++
        }
        if (start < inner.length) parts.add(inner.substring(start))
        return parts.filter { it.isNotBlank() }
    }

    /**
     * 统计值清洗：去引号 + 去空白；源站极早期（无样本）会下发 `NaN%`（典型是「传球成功率」），
     * 原样渲染会在统计表里出现一行「NaN%」—— 统一压成「—」。
     */
    private fun cleanValue(raw: String): String {
        val t = raw.trim().trim('\'').trim('"')
        return if (t.contains("NaN", ignoreCase = true)) "—" else t
    }

    /**
     * 条形图两侧比例。源站也可能下发 `NaN` 或双 0，此时 [cleanValue] 后值为「—」/「0」，
     * 直接画会得到空条或全满条；对半（50:50）比画错更诚实。
     */
    private fun bars(hpRaw: String?, gpRaw: String?): Pair<Int, Int> {
        val hp = hpRaw?.trim()?.toIntOrNull()
        val gp = gpRaw?.trim()?.toIntOrNull()
        return if (hp != null && gp != null && hp + gp > 0) hp to gp else 50 to 50
    }

    // ============ 首发 / 替补阵容 ============

    /**
     * 从详情页 HTML 的 `div#matchBox2` 刮「首发 + 替补」两侧名单（服务端渲染，未开赛/进行中/完场都有）。
     *
     * 结构（实测 `detail/<id>cn.htm`）：
     * ```
     * <div class="content">
     *   <div class="title">
     *     首发阵容
     *     <div class="homeN"><a>布鲁明</a> 4-2-3-1<a class="coach" title="毛里斯奧.索里亚">(主教练: …)</a></div>
     *     <div class="guestN"><a class="coach" title="大卫·冈萨雷斯">…</a><a>奥利恩特</a> 4-2-3-1</div>
     *   </div>
     *   <div id="matchBox2">
     *     <div class="plays">                    ← 首发
     *       <div class="home"> <div class="playBox"> <div class="play"> …
     *       <div class="guest"> …
     *     <div class="backupPlay backupPlay2">   ← 替补
     *       <div class="home"> <div class="play"> …
     *       <div class="bu_txt">替补</div>
     *       <div class="guest"> …
     *     <div class="backupPlay hurtPlay">      ← 伤停（**不要**，用户只要首发/替补）
     * ```
     * 首发球员号在 `em.num`、替补在 `div.name i`；队长有 `div.captain`；
     * 进球/红黄牌/换人/助攻是球员块里的 `img[title="66' 黄牌"]`。
     *
     * 页面里没有该板块（极早期页面 / 未出首发）→ null。
     */
    fun parseLineup(html: String): MatchLineup? {
        if (html.isEmpty() || !html.contains("matchBox2")) return null
        val doc = try {
            Jsoup.parse(html)
        } catch (_: Exception) {
            return null
        }
        val box = doc.getElementById("matchBox2") ?: return null
        // 阵型/教练在 `div.content > div.title`，是 matchBox2 的**兄弟**节点
        val title = box.parent()?.selectFirst("div.title")
        val home = parseTeamLineup(
            box.selectFirst("div.plays > div.home"),
            box.selectFirst("div.backupPlay2 > div.home"),
            title?.selectFirst("div.homeN")
        )
        val away = parseTeamLineup(
            box.selectFirst("div.plays > div.guest"),
            box.selectFirst("div.backupPlay2 > div.guest"),
            title?.selectFirst("div.guestN")
        )
        val lu = MatchLineup(home, away)
        return if (lu.isEmpty) null else lu
    }

    /**
     * 解析**伤停**名单（`div.backupPlay.hurtPlay`）。
     *
     * 与 [parseLineup] 读同一个详情页 HTML，所以调用方**不需要额外请求**：
     * 已经为了首发阵容把页面拉下来了，这里只是再切一次 DOM。
     *
     * 无该板块 / 两块都写着「暂无数据」→ 返回 null（**不留空壳**，
     * 否则 UI 上会挂一块只有标题的「伤停」）。
     *
     * 🔴 真实有数据时的 DOM 结构尚无样本（见 [MatchInjuries] 的说明），因此按
     *    「优先结构化行、退化为整块文本」的宽松策略提取，宁可少取也不错位。
     */
    fun parseInjuries(html: String): MatchInjuries? {
        if (html.isEmpty() || !html.contains("hurtPlay")) return null
        val doc = try {
            Jsoup.parse(html)
        } catch (_: Exception) {
            return null
        }
        val box = doc.selectFirst("div.hurtPlay") ?: return null
        val r = MatchInjuries(
            home = injuryLines(box.selectFirst("div.home")),
            away = injuryLines(box.selectFirst("div.guest"))
        )
        return if (r.isEmpty) null else r
    }

    /** 单侧伤停文本行。见 [parseInjuries] 的说明。 */
    private fun injuryLines(el: Element?): List<String> {
        if (el == null) return emptyList()
        val whole = el.text().trim()
        if (whole.isEmpty() || whole.contains("暂无数据")) return emptyList()
        // ① 有结构化行（球员通常一列一行）
        val items = el.select("li, tr, p")
        if (items.isNotEmpty()) {
            return items.map { it.text().trim() }.filter { it.isNotEmpty() && !it.contains("暂无数据") }
        }
        // ② 没有行元素：整块当一行（宁少不错，见模型注释）
        return listOf(whole)
    }

    private fun parseTeamLineup(startersBox: Element?, subsBox: Element?, head: Element?): TeamLineup {
        val headText = head?.text().orEmpty()
        // 阵型：`4-2-3-1` / `4-4-2`；队名里出现「3-4-2-1」这种串的概率可忽略
        val formation = Regex("\\d-\\d-\\d(?:-\\d)?").find(headText)?.value.orEmpty()
        val coach = head?.selectFirst("a.coach")?.let { a ->
            a.attr("title").trim().ifBlank {
                a.text().replace(Regex("[(（]?主教练[:：]?"), "").replace(Regex("[)）]"), "").trim()
            }
        }.orEmpty()
        return TeamLineup(
            formation = formation,
            coach = coach,
            starters = parseLineupPlayers(startersBox),
            subs = parseLineupPlayers(subsBox)
        )
    }

    /** 名字为空的行（源站偶尔给空壳 `div.play`）直接丢，避免名单里出现「空行」。*/
    private fun parseLineupPlayers(box: Element?): List<LineupPlayer> {
        if (box == null) return emptyList()
        val out = ArrayList<LineupPlayer>()
        for (play in box.select("div.play")) {
            val name = (play.selectFirst("div.name a") ?: play.selectFirst("div.name"))
                ?.text()?.trim().orEmpty()
            if (name.isEmpty()) continue
            val number = (
                play.selectFirst("em.num")?.text()
                    ?: play.selectFirst("div.name i")?.text()
                )?.trim().orEmpty()
            val marks = ArrayList<PlayerMark>()
            for (img in play.select("img")) {
                val raw = img.attr("title").ifBlank { img.attr("alt") }
                markOf(raw)?.let { marks.add(it) }
            }
            out.add(
                LineupPlayer(
                    number = number,
                    name = name,
                    captain = play.selectFirst("div.captain") != null,
                    marks = marks
                )
            )
        }
        return out
    }

    /**
     * `"66' 黄牌"` / `"45+9' 红牌"` / `"58' 换出"` / `"36' 助攻"` → [PlayerMark]。
     * 认不出的标记（如「扑出点球」「最佳球员」图标）返回 null —— 宁可不画，也不要画错。
     */
    private fun markOf(raw: String): PlayerMark? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val kind = when {
            // 「两黄变红」必须先于「黄牌/红牌」判断，否则会被拆成黄牌
            t.contains("两黄") -> EventKind.SECOND_YELLOW
            t.contains("乌龙") -> EventKind.OWN_GOAL
            t.contains("入球") || t.contains("进球") -> EventKind.GOAL
            t.contains("点球") -> EventKind.PENALTY
            t.contains("红牌") -> EventKind.RED
            t.contains("黄牌") -> EventKind.YELLOW
            t.contains("换入") || t.contains("上场") -> LineupMark.SUB_ON
            t.contains("换出") || t.contains("下场") -> LineupMark.SUB_OFF
            t.contains("助攻") -> LineupMark.ASSIST
            else -> return null
        }
        val minute = Regex("\\d+(?:\\+\\d+)?'").find(t)?.value.orEmpty()
        return PlayerMark(minute, kind)
    }

    // ============ 事件时间轴 ============

    /**
     * 从详情页 HTML 的 `ul#teamEventDiv` 刮事件时间轴（完场页是**服务端渲染**的，直播中为空，由 JS 用 `d_f` 渲染）。
     *
     * 单行结构（主队事件在左 span、时间在中 span、客队事件在右 span）：
     * ```
     * <li class='lists'><div class='data'>
     *   <span><a title='罗德里戈.內斯特'>…<i class="goalicon"></i></a>
     *         (<a>助攻:卢卡斯</a>)<img src='/images/bf_img2/1.png' title='入球' /></span>
     *   <span>6'</span>
     *   <span></span>
     * </div></li>
     * ```
     * 换人一行里是两个 `<a>`（先下场、后上场）+ `bf_img2/4.png`(下) / `5.png`(上) / `11.png`(换人)。
     */
    fun parseEventsFromDetailHtml(html: String): List<MatchEvent> {
        if (html.isEmpty() || !html.contains("teamEventDiv")) return emptyList()
        val doc = try {
            Jsoup.parse(html)
        } catch (_: Exception) {
            return emptyList()
        }
        val ul = doc.getElementById("teamEventDiv") ?: return emptyList()
        val out = ArrayList<MatchEvent>()
        for (li in ul.select("li.lists")) {
            val spans = li.select("div.data > span")
            if (spans.size < 3) continue
            val minute = spans[1].text().trim()
            if (minute.isEmpty()) continue
            out.addAll(parseEventSpan(spans[0], 1, minute))
            out.addAll(parseEventSpan(spans[2], 2, minute))
        }
        return out
    }

    /** 解析一侧的事件 span；空 span（该侧没事件）返回空列表。*/
    private fun parseEventSpan(span: Element, side: Int, minute: String): List<MatchEvent> {
        if (span.text().isBlank() && span.select("img").isEmpty()) return emptyList()
        val kind = eventKindOf(span.select("img")) ?: return emptyList()
        // 球员名：优先 a[title]（源站给换人/球员链接都带 title），退到 a 的文本；
        // 「助攻:xxx」去掉前缀，单独放进 player2。
        val names = span.select("a")
            .map { a -> a.attr("title").ifBlank { a.text() }.trim() }
            .filter { it.isNotEmpty() }
            .map { it.removePrefix("助攻:").trim() }
        val player: String
        val player2: String
        when {
            kind == EventKind.SUBSTITUTION -> {
                player = names.getOrNull(0) ?: ""
                player2 = names.getOrNull(1) ?: ""
            }
            else -> {
                player = names.getOrNull(0) ?: span.text().substringBefore('(').trim()
                player2 = names.getOrNull(1) ?: ""
            }
        }
        return listOf(MatchEvent(minute = minute, side = side, kind = kind, player = player, player2 = player2))
    }

    /** 图标 → 事件类型。`bf_img2/4.png`(下场) 与 `5.png`(上场) 是换人的组成部分，本身不算事件。*/
    private fun eventKindOf(imgs: List<Element>): Int? {
        for (img in imgs) {
            val title = img.attr("title").trim()
            val code = Regex("/bf_img2/(\\d+)\\.png")
                .find(img.attr("src"))?.groupValues?.get(1)?.toIntOrNull()
            val k = when {
                title == "入球" || title == "进球" -> EventKind.GOAL
                title == "红牌" -> EventKind.RED
                title == "黄牌" -> EventKind.YELLOW
                title == "换人" -> EventKind.SUBSTITUTION
                title == "两黄变红" -> EventKind.SECOND_YELLOW
                title == "入球无效" -> EventKind.CANCEL_GOAL
                title == "点球" -> EventKind.PENALTY
                title == "乌龙" -> EventKind.OWN_GOAL
                title == "射失点球" -> EventKind.MISS_PENALTY
                title == "视频裁判" -> EventKind.VAR
                code == 1 -> EventKind.GOAL
                code == 2 -> EventKind.RED
                code == 3 -> EventKind.YELLOW
                code == 11 -> EventKind.SUBSTITUTION
                else -> null
            }
            if (k != null) return k
        }
        return null
    }

    /**
     * 直播中的事件走 `detailin.js` 的 `d_f[<id>]`（详情页 HTML 此时还没渲染事件表）。
     *
     * 每条：`[kind, 分钟, 主队id, 球员id, 球员2id, side, '球员^繁中', '球员2^繁中', ?, 备注, 图, 图]`
     *  - kind 兼容 [EventKind]（1 入球 / 2 红牌 / 3 黄牌 / 11 换人 / 14 视频裁判 …）；
     *  - side 1 = 主队、2 = 客队；
     *  - 球员名是「简^繁」双语，取 `^` 之前。
     */
    fun parseEventsFromDetailIn(js: String, id: Long): List<MatchEvent> {
        val m = Regex("d_f\\[$id\\]=(\\[\\[.*?\\]\\]);", RegexOption.DOT_MATCHES_ALL).find(js)
            ?: return emptyList()
        val out = ArrayList<MatchEvent>()
        for (em in Regex("\\[([^\\[\\]]*)\\]").findAll(m.groupValues[1])) {
            val p = em.groupValues[1].split(',')
            if (p.size < 7) continue
            val kind = p[0].trim().toIntOrNull() ?: continue
            if (EventKind.label(kind).isEmpty()) continue
            val side = p[5].trim().toIntOrNull() ?: continue
            if (side !in 1..2) continue
            val minute = p[1].trim()
            if (minute.isEmpty()) continue
            out.add(
                MatchEvent(
                    minute = "$minute'",
                    side = side,
                    kind = kind,
                    player = cnName(p.getOrNull(6)),
                    player2 = cnName(p.getOrNull(7)),
                    memo = cnName(p.getOrNull(9))
                )
            )
        }
        return out
    }

    /** `'萨维尔^M.沙維亞'` → `萨维尔`（去掉引号、取简中那一半）。*/
    private fun cnName(s: String?): String {
        if (s == null) return ""
        val t = s.trim().trim('\'').trim('"')
        return t.substringBefore('^').trim()
    }

    /** 取字符串里第一个数字（`63%` → 63、`142` → 142），没有则 0。*/
    private fun num(s: String): Int =
        Regex("\\d+").find(s)?.value?.toIntOrNull() ?: 0
}
