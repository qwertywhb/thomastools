# -*- coding: utf-8 -*-
import io
p = 'app/src/main/java/com/example/jingcai/data/repository/JingCaiRepository.kt'
s = io.open(p, encoding='utf-8').read()
i = s.find('    // ===== 竞彩赛程')
j = s.find('    // ===== 联赛分组')
assert i != -1 and j != -1 and i < j, (i, j)
old = s[i:j]
assert old.count('getJingcaiSchedule') == 1, old.count('getJingcaiSchedule')
assert 'football/getMatchListV1.qry' in old

new = '''    // ===== 竞彩赛程（体彩官方：足球/篮球共用一套解析） =====
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
        out
    }

'''
io.open(p, 'w', encoding='utf-8', newline='\n').write(s[:i] + new + s[j:])
print('OK 已替换，新长度', len(s[:i] + new + s[j:]))
