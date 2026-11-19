package com.example.jingcai.data.repository

import com.example.jingcai.data.model.*
import com.example.jingcai.data.network.ApiClient
import com.example.jingcai.data.network.OkoooParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 所有数据源统一入口。 */
class JingCaiRepository {

    private val sporttery = "https://webapi.sporttery.cn"
    private val referer = "https://www.sporttery.cn/"
    private val okooo = "https://www.okooo.cn"

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(ApiClient.get(url, referer))
    }

    // ===== 比赛列表（okooo，GBK） =====
    suspend fun getMatches(date: String): List<Match> = withContext(Dispatchers.IO) {
        val html = ApiClient.get("$okooo/jingcai/$date/", "$okooo/jingcai/", "GBK")
        OkoooParser.parse(html, date)
    }

    // ===== 同奖查询 =====
    suspend fun getSameOdds(h: String?, d: String?, a: String?, bonus: Int): SameOddsResult =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder("$sporttery/gateway/uniform/football/searchOddsV1.qry?channel=c&type=")
            if (bonus > 0) sb.append("&bonus=").append(bonus)
            sb.append("&single=0")
            if (!h.isNullOrEmpty()) sb.append("&h=").append(h)
            if (!a.isNullOrEmpty()) sb.append("&a=").append(a)
            if (!d.isNullOrEmpty()) sb.append("&d=").append(d)
            val root = getJson(sb.toString())
            val v = root.optJSONObject("value") ?: return@withContext SameOddsResult(0, 0, 0, "", "", "", emptyList())
            val matches = mutableListOf<SameOddsMatch>()
            val ml = v.optJSONArray("matchList") ?: org.json.JSONArray()
            for (i in 0 until ml.length()) {
                val m = ml.optJSONObject(i) ?: continue
                val odds = m.optJSONArray("odds")
                val o = odds?.optJSONObject(0) ?: JSONObject()
                matches.add(
                    SameOddsMatch(
                        home = m.optString("homeTeamAbbName"),
                        away = m.optString("awayTeamAbbName"),
                        league = m.optString("leaguesAbbName"),
                        matchDate = o.optString("matchDate"),
                        h = o.optString("h"),
                        d = o.optString("d"),
                        a = o.optString("a"),
                        status = o.optString("status").toIntOrNull() ?: 0,
                        score = m.optString("sectionsNo999")
                    )
                )
            }
            SameOddsResult(
                winNum = v.optString("winNum").toIntOrNull() ?: 0,
                drawNum = v.optString("drawNum").toIntOrNull() ?: 0,
                loseNum = v.optString("loseNum").toIntOrNull() ?: 0,
                hRate = v.optString("hRate"),
                dRate = v.optString("dRate"),
                aRate = v.optString("aRate"),
                matches = matches
            )
        }

    // ===== 赔率变化（先映射编号→matchId） =====
    suspend fun getFixedBonus(order: String): FixedBonusData? = withContext(Dispatchers.IO) {
        // 1) 编号 → 体彩 matchId
        val mapRoot = getJson("$sporttery/gateway/jc/football/getMatchCalculatorV1.qry?poolCode=hhad,had&channel=c")
        var matchId = 0L
        val days = mapRoot.optJSONObject("value")?.optJSONArray("matchInfoList") ?: org.json.JSONArray()
        outer@ for (di in 0 until days.length()) {
            val subs = days.optJSONObject(di)?.optJSONArray("subMatchList") ?: continue
            for (si in 0 until subs.length()) {
                val m = subs.optJSONObject(si) ?: continue
                if (m.optString("matchNumStr") == order) {
                    matchId = m.optString("matchId").toLongOrNull() ?: 0L
                    break@outer
                }
            }
        }
        if (matchId == 0L) return@withContext null

        // 2) 查固定奖金变动
        val root = getJson("$sporttery/gateway/uniform/football/getFixedBonusV1.qry?clientCode=3001&matchId=$matchId")
        val oh = root.optJSONObject("value")?.optJSONObject("oddsHistory") ?: return@withContext null

        fun rows(listName: String): List<FixedBonusRow> {
            val arr = oh.optJSONArray(listName) ?: return emptyList()
            val out = mutableListOf<FixedBonusRow>()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                out.add(
                    FixedBonusRow(
                        updateTime = (r.optString("updateDate") + " " + r.optString("updateTime")).trim(),
                        h = r.optString("h"), d = r.optString("d"), a = r.optString("a"),
                        hf = r.optString("hf").toIntOrNull() ?: 0,
                        df = r.optString("df").toIntOrNull() ?: 0,
                        af = r.optString("af").toIntOrNull() ?: 0
                    )
                )
            }
            return out
        }

        val hhad = rows("hhadList")
        val handicap = if (hhad.isNotEmpty()) {
            val first = oh.optJSONArray("hhadList")?.optJSONObject(0)
            first?.optString("goalLine") ?: ""
        } else ""

        FixedBonusData(
            home = oh.optString("homeTeamAllName"),
            away = oh.optString("awayTeamAllName"),
            league = oh.optString("leagueAbbName"),
            handicap = handicap,
            had = rows("hadList"),
            hhad = hhad
        )
    }

    // ===== 竞彩赛程（体彩官方：足球/篮球共用一套解析） =====
    /**
     * 拉取竞彩**足球**赛程。接口一次返回**多天**（实测 4 天：周五 ~ 周一），
     * 外层 `matchInfoList` 已是按销售日分好组的，直接沿用其分组即可，不要自己按日期重切
     * —— 因为「销售日」与「开赛日」不是一回事（周五的场次可能在北京时间周六凌晨开赛）。
     */
    suspend fun getJingcaiSchedule(): List<ScheduleDayGroup> = withContext(Dispatchers.IO) {
        parseMatchInfoList(getJson("$sporttery/gateway/uniform/football/getMatchListV1.qry?clientCode=3001"))
    }

    /**
     * 拉取竞彩**篮球**赛程 —— 体彩官方「篮球竞猜赛程」页
     * <https://www.sporttery.cn/jc/lqszsc/> 的数据源。
     *
     * 与足球的**唯一差别是网关路径 `football` -> `basketball`**；返回 JSON 的
     * `value.matchInfoList[].subMatchList[]` 字段完全同名同义（`matchNumStr` / `leagueAbbName` /
     * `backColor` / `homeTeamAbbName` / `awayTeamAbbName` / `matchDate` / `matchTime` /
     * `matchStatus`），因此直接复用 [ScheduleDayGroup] / [JingcaiScheduleMatch]，不新建模型。
     *
     * 实测注意（2026-10-19，避免以后误判成 Bug）：
     *  - 篮球在售场次通常远少于足球（当日仅 1 场美职女篮，`totalCount=1`），页面稀疏是**真实数据**；
     *  - 该接口**不支持日期参数**：带 `matchDate` / `businessDate` 要么返回统一错误页、要么返空数组，
     *    所以只能拿「当前在售」这一份，与官方网页默认视图一致。
     */
    suspend fun getBasketballSchedule(): List<ScheduleDayGroup> = withContext(Dispatchers.IO) {
        parseMatchInfoList(getJson("$sporttery/gateway/uniform/basketball/getMatchListV1.qry?clientCode=3001"))
    }

    /** 足球/篮球赛程共用的 JSON 解析（两者结构完全相同，见 [getBasketballSchedule] 说明）。 */
    private fun parseMatchInfoList(root: JSONObject): List<ScheduleDayGroup> {
        val v = root.optJSONObject("value") ?: return emptyList()
        val groups = v.optJSONArray("matchInfoList") ?: return emptyList()
        val out = mutableListOf<ScheduleDayGroup>()
        for (gi in 0 until groups.length()) {
            val g = groups.optJSONObject(gi) ?: continue
            val subs = g.optJSONArray("subMatchList") ?: org.json.JSONArray()
            val list = mutableListOf<JingcaiScheduleMatch>()
            for (si in 0 until subs.length()) {
                val m = subs.optJSONObject(si) ?: continue
                list.add(
                    JingcaiScheduleMatch(
                        matchId = m.optString("matchId").toLongOrNull() ?: 0L,
                        num = m.optString("matchNumStr"),
                        league = m.optString("leagueAbbName"),
                        color = m.optString("backColor"),
                        home = m.optString("homeTeamAbbName"),
                        away = m.optString("awayTeamAbbName"),
                        date = m.optString("matchDate"),
                        time = m.optString("matchTime"),
                        ended = m.optString("matchStatus") != "Selling"
                    )
                )
            }
            // 组内按官方编号升序（周五002 排在 周五010 前）。
            // 注意不能按字符串排 —— 编号里数字部分是 3 位零填充，字符串序恰好可用；
            // 但仍显式排序，避免接口顺序变化导致列表乱序。
            out.add(
                ScheduleDayGroup(
                    businessDate = g.optString("businessDate"),
                    weekday = g.optString("weekday"),
                    matches = list.sortedBy { it.num }
                )
            )
        }
        // 注意：这是**普通块体函数**（不再是 withContext lambda 的尾表达式），必须显式 return。
        return out
    }

    // ===== 联赛分组 =====
    suspend fun getLeagueGroups(): List<LeagueGroup> = withContext(Dispatchers.IO) {
        val root = getJson("$sporttery/gateway/uniform/football/league/getLeagueListV1.qry")
        val v = root.optJSONObject("value") ?: return@withContext emptyList()

        fun parseLeagueArr(arr: org.json.JSONArray): List<LeagueInfo> {
            val list = mutableListOf<LeagueInfo>()
            for (i in 0 until arr.length()) {
                val lg = arr.optJSONObject(i) ?: continue
                val seasons = mutableListOf<LeagueSeason>()
                val sl = lg.optJSONArray("seasonList") ?: org.json.JSONArray()
                for (j in 0 until sl.length()) {
                    val s = sl.optJSONObject(j) ?: continue
                    seasons.add(LeagueSeason(s.optString("seasonId").toIntOrNull() ?: 0, s.optString("seasonName")))
                }
                val name = lg.optString("leagueAbbCnName")
                val id = lg.optString("uniformLeagueId").toLongOrNull() ?: 0L
                if (name.isNotEmpty() && id > 0) list.add(LeagueInfo(name, id, seasons))
            }
            return list
        }

        val groups = mutableListOf<LeagueGroup>()
        groups.add(LeagueGroup("热门", parseLeagueArr(v.optJSONArray("hot") ?: org.json.JSONArray())))
        for (sec in listOf("normal", "other")) {
            val secArr = v.optJSONArray(sec) ?: continue
            for (i in 0 until secArr.length()) {
                val area = secArr.optJSONObject(i) ?: continue
                val areaName = area.optString("countryCnName")
                if (areaName.isEmpty()) continue
                val leagues = mutableListOf<LeagueInfo>()
                val countries = area.optJSONArray("countryList") ?: org.json.JSONArray()
                for (j in 0 until countries.length()) {
                    val c = countries.optJSONObject(j) ?: continue
                    leagues.addAll(parseLeagueArr(c.optJSONArray("leagueList") ?: org.json.JSONArray()))
                }
                if (leagues.isNotEmpty()) groups.add(LeagueGroup(areaName, leagues))
            }
        }
        groups
    }

    // ===== 赛程赛果 =====
    suspend fun getMatchResult(leagueId: Long, seasonId: Int, start: String, end: String): List<ScheduleDay> =
        withContext(Dispatchers.IO) {
            val root = getJson(
                "$sporttery/gateway/uniform/football/league/getMatchResultV1.qry?seasonId=$seasonId&uniformLeagueId=$leagueId&startDate=$start&endDate=$end"
            )
            val ml = root.optJSONObject("value")?.optJSONArray("matchList") ?: return@withContext emptyList()
            val days = mutableListOf<ScheduleDay>()
            for (di in 0 until ml.length()) {
                val d = ml.optJSONObject(di) ?: continue
                val subs = d.optJSONArray("subMatchList") ?: org.json.JSONArray()
                val matches = mutableListOf<ScheduleMatch>()
                for (si in 0 until subs.length()) {
                    val m = subs.optJSONObject(si) ?: continue
                    matches.add(
                        ScheduleMatch(
                            matchDate = d.optString("matchDate"),
                            matchTime = m.optString("matchTime"),
                            home = m.optString("homeAbbCnName"),
                            away = m.optString("awayAbbCnName"),
                            phase = m.optString("phaseName"),
                            week = m.optString("gameweek"),
                            score = m.optString("sectionsNo999"),
                            status = m.optString("wbsjMatchScDesc")
                        )
                    )
                }
                days.add(ScheduleDay(d.optString("matchDate"), matches))
            }
            days
        }

    // ===== 积分榜 =====
    suspend fun getTables(leagueId: Long, seasonId: Int): List<StandingRow> = withContext(Dispatchers.IO) {
        val root = getJson(
            "$sporttery/gateway/uniform/football/league/getTablesV2.qry?seasonId=$seasonId&uniformLeagueId=$leagueId"
        )
        val phases = root.optJSONObject("value")?.optJSONArray("totalTables") ?: return@withContext emptyList()
        val rows = mutableListOf<StandingRow>()
        for (pi in 0 until phases.length()) {
            val groups = phases.optJSONObject(pi)?.optJSONArray("groups") ?: continue
            for (gi in 0 until groups.length()) {
                val tables = groups.optJSONObject(gi)?.optJSONArray("tables") ?: continue
                for (ti in 0 until tables.length()) {
                    val t = tables.optJSONObject(ti) ?: continue
                    rows.add(
                        StandingRow(
                            rank = t.optString("ranking"),
                            team = t.optString("abbCnName"),
                            played = t.optString("totalLegCnt").toIntOrNull() ?: 0,
                            win = t.optString("winGoalMatchCnt").toIntOrNull() ?: 0,
                            draw = t.optString("drawMatchCnt").toIntOrNull() ?: 0,
                            lose = t.optString("lossGoalMatchCnt").toIntOrNull() ?: 0,
                            goal = t.optString("goalCnt").toIntOrNull() ?: 0,
                            loss = t.optString("lossGoalCnt").toIntOrNull() ?: 0,
                            net = t.optString("netGoal").toIntOrNull() ?: 0,
                            points = t.optString("points")
                        )
                    )
                }
            }
            break
        }
        rows
    }
}
