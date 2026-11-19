package com.example.jingcai.data.network

import com.example.jingcai.data.model.BbJcOdds
import com.example.jingcai.data.model.BbOddSide
import org.json.JSONObject

/**
 * 体彩篮球 calculator 接口 → `竞彩编号 to 三玩法赔率`。
 *
 * 接口：`/gateway/jc/basketball/getMatchCalculatorV1.qry?poolCode=mnl,hilo,hdc&channel=c`
 *
 * 🔴 三个坑（与 [BetParser] 同源，别重复踩）：
 *  1. `poolCode` 是**过滤器**：请求里不写某个玩法，对应字段就是 `{}`，会被误判成「没开这玩法」；
 *  2. 涨跌是独立字段（`hf/af/lf`），值为 `"1"/"-1"/"0"`，不要用赔率差值自己算；
 *  3. `goalLineValue` 是**主队视角**，客队是符号相反的同一盘口。
 *
 * 解析全程不抛：单场异常跳过，宁可少几行赔率，也不能让比分列表崩。
 */
object BbJcOddsParser {

    fun parse(root: JSONObject): Map<String, BbJcOdds> {
        val v = root.optJSONObject("value") ?: return emptyMap()
        val days = v.optJSONArray("matchInfoList") ?: return emptyMap()
        val out = LinkedHashMap<String, BbJcOdds>()
        for (di in 0 until days.length()) {
            val day = days.optJSONObject(di) ?: continue
            val subs = day.optJSONArray("subMatchList") ?: continue
            for (si in 0 until subs.length()) {
                val m = subs.optJSONObject(si) ?: continue
                if (m.optString("isHide") == "1") continue
                val num = m.optString("matchNumStr").trim()
                if (num.isEmpty()) continue
                runCatching { build(num, m) }.getOrNull()
                    ?.takeIf { it.any }
                    ?.let { out[num] = it }
            }
        }
        return out
    }

    private fun build(num: String, m: JSONObject): BbJcOdds {
        val hdc = m.optJSONObject("hdc")
        val hilo = m.optJSONObject("hilo")
        val mnl = m.optJSONObject("mnl")
        return BbJcOdds(
            num = num,
            homeTeam = m.optString("homeTeamAbbName").trim(),
            awayTeam = m.optString("awayTeamAbbName").trim(),
            hdcLine = lineOf(hdc),
            hdcHome = side(hdc, "h", "hf"),
            hdcAway = side(hdc, "a", "af"),
            hiloLine = lineOf(hilo),
            hiloOver = side(hilo, "h", "hf"),
            hiloUnder = side(hilo, "l", "lf"),
            mnlHome = side(mnl, "h", "hf"),
            mnlAway = side(mnl, "a", "af")
        )
    }

    /** 盘口线：优先 `goalLineValue`（带正负、两位小数），退回 `goalLine`。 */
    private fun lineOf(o: JSONObject?): String {
        if (o == null) return ""
        return o.optString("goalLineValue").ifEmpty { o.optString("goalLine") }.trim()
    }

    private fun side(o: JSONObject?, oddsKey: String, trendKey: String): BbOddSide {
        if (o == null) return BbOddSide(0.0)
        val odds = o.optString(oddsKey).toDoubleOrNull() ?: 0.0
        val trend = o.optString(trendKey).toIntOrNull() ?: 0
        return BbOddSide(odds = if (odds > 0) odds else 0.0, trend = if (odds > 0) trend else 0)
    }
}
