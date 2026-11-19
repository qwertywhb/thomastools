package com.example.jingcai.data.repository

import com.example.jingcai.data.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * 「动画直播」详情页数据源（titan007）。
 *
 *  - 动画全量 `GET https://livestatic.titan007.com/flashdata/get?id=<id>`（首屏一次）；
 *  - 动画增量 `GET .../flashdata/get?chid=<id>`（约 1 秒轮询，源站 HTTP 兜底；线上为 WS `_cgf_<id>`）；
 *  - 技术统计 `GET https://live.titan007.com/vbsxml/detailin.js?r=007`（含 `tT_f[<id>]` 的滚动快照，
 *    只有 12~13 场，完场早已滚出）；
 *  - 完场技术统计 `GET https://live.titan007.com/detail/<id>cn.htm`（服务端渲染的 `li.lists`）；
 *  - 动画直播本身**不走这里**：由 `LiveDetailScreen` 用 WebView 直接内嵌官方
 *    `flashDetail/flash.html`（见 [flashPageUrl]），本仓库只负责取场次头和技术统计。
 */
class LiveDetailRepository {

    private val liveStatic = "https://livestatic.titan007.com"
    private val live = "https://live.titan007.com"
    private val referer = "$live/"

    private fun ts(): String = Date().time.toString()

    suspend fun fetchFlashFull(titanId: Long): String = withContext(Dispatchers.IO) {
        ApiClient.get("$liveStatic/flashdata/get?id=$titanId&t=${ts()}", referer)
    }

    suspend fun fetchFlashChange(titanId: Long): String = withContext(Dispatchers.IO) {
        ApiClient.get("$liveStatic/flashdata/get?chid=$titanId&t=${ts()}", referer)
    }

    suspend fun fetchDetailIn(): String = withContext(Dispatchers.IO) {
        ApiClient.get("$live/vbsxml/detailin.js?r=007&t=${ts()}", referer)
    }

    /**
     * 场次详情页 HTML（完场技术统计的唯一来源）。
     * 直播中该板块由 JS 从 detailin.js 渲染，因此只有**完场**页面才带服务端渲染的 `li.lists` 统计行。
     */
    suspend fun fetchDetailPage(titanId: Long): String = withContext(Dispatchers.IO) {
        ApiClient.get("$live/detail/${titanId}cn.htm?t=${ts()}", referer)
    }

    /** 官方「动画直播」页（WebView 内嵌用）。坐标基准 568×352，页面逻辑宽 809px。*/
    fun flashPageUrl(titanId: Long, homeName: String, awayName: String): String {
        val h = java.net.URLEncoder.encode(homeName, "UTF-8")
        val g = java.net.URLEncoder.encode(awayName, "UTF-8")
        return "$live/flashDetail/flash.html?id=$titanId&h=$h&g=$g&t=0"
    }
}
