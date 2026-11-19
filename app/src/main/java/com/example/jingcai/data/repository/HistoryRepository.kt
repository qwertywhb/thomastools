package com.example.jingcai.data.repository

import com.example.jingcai.data.model.HistoryData
import com.example.jingcai.data.network.ApiClient
import com.example.jingcai.data.network.OkoooHistoryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「近期状态」数据源：澳客 okooo history 页。
 *
 * 🔴 **UA 敏感**：必须走 [ApiClient] 的 okhttp UA（`okhttp/4.12.0`）—— okooo WAF 对 UA 限流：
 *    Chrome 浏览器 UA（桌面/部分移动）连发即 **HTTP 405**（2026-10-23 实测移动 Chrome UA 8/8 全 405，
 *    okhttp UA 6/6 全 200）。若换网络栈，勿用浏览器 UA。限流是「UA + 频率」双重判定，
 *    短时间连点多场仍可能偶发 405，属预期内。
 */
class HistoryRepository {

    private val okooo = "https://www.okooo.cn"

    /** 拉取指定场次（okooo matchId，即 [com.example.jingcai.data.model.Match.mid]）的近期状态。 */
    suspend fun fetch(mid: Long): HistoryData = withContext(Dispatchers.IO) {
        val html = ApiClient.get("$okooo/soccer/match/$mid/history/", "$okooo/soccer/match/$mid/", "GBK")
        OkoooHistoryParser.parse(html)
    }
}
