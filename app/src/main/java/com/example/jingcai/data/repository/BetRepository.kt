package com.example.jingcai.data.repository

import com.example.jingcai.data.model.BetMatch
import com.example.jingcai.data.model.BetSport
import com.example.jingcai.data.network.ApiClient
import com.example.jingcai.data.network.BetParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 模拟投注数据源：体彩官方 calculator 接口。
 *
 * 一次请求拿全玩法（这是本页最关键的选型决定，见 [BetParser] 的说明）：
 *  - 足球 `poolCode=hhad,had,ttg,hafu,crs`
 *  - 篮球 `poolCode=mnl,hilo,hdc`（**不含 wnm 胜分差** —— 用户明确只要大小分/胜负/让分胜负）
 *
 * ⚠️ 与竞彩赛程的 `getMatchListV1` 不同，本接口**只返回在售场次**（`matchStatus=Selling`），
 *    已停售的不在列表里 —— 所以投注页永远看不到已封盘的比赛，这是预期行为，不要当 Bug 修。
 */
class BetRepository {

    private val sporttery = "https://webapi.sporttery.cn"
    private val referer = "https://www.sporttery.cn/"

    private fun urlFor(sport: BetSport): String = when (sport) {
        BetSport.FOOTBALL ->
            "$sporttery/gateway/jc/football/getMatchCalculatorV1.qry?poolCode=hhad,had,ttg,hafu,crs&channel=c"
        BetSport.BASKETBALL ->
            "$sporttery/gateway/jc/basketball/getMatchCalculatorV1.qry?poolCode=mnl,hilo,hdc&channel=c"
    }

    suspend fun getMarkets(sport: BetSport): List<BetMatch> = withContext(Dispatchers.IO) {
        val root = JSONObject(ApiClient.get(urlFor(sport), referer))
        if (!root.optBoolean("success", true)) {
            throw IllegalStateException(root.optString("errorMessage").ifEmpty { "接口返回失败" })
        }
        when (sport) {
            BetSport.FOOTBALL -> BetParser.parseFootball(root)
            BetSport.BASKETBALL -> BetParser.parseBasketball(root)
        }
    }
}
