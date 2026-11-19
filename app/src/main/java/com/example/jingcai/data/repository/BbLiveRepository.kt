package com.example.jingcai.data.repository

import com.example.jingcai.data.model.BbLiveMatch
import com.example.jingcai.data.model.BbPatch
import com.example.jingcai.data.network.ApiClient
import com.example.jingcai.data.network.BbLiveParser
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「比分直播 → 篮球」数据源（188bifen 篮球页同源，实测确认的三个接口）：
 *
 *  1. 全量 `https://matchs.qiumibao.com/live/all.htm`（~310KB，682 场全体育，其中篮球 ~256、
 *     **竞彩篮球 ~19**）——首屏与每 5 分钟兜底重建；
 *  2. 版本 `https://matchs.qiumibao.com/live/version.htm` → `{"url":"/live/2026/10/22/58738229.htm","v":58738229}`
 *     （几十字节，6 秒一次）——**版本号没变就不必拉增量**；
 *  3. 增量 `https://matchc.qiumibao.com<url>` → `{"data":[[…],…],"version":…}`
 *     （1~2KB，只含变更场次的精简行，字段映射见 [BbPatch]）。
 *
 * ⚠️ 两个域名都要带 `Referer: https://www.188bifen.com/lanqiubifen.htm`，
 *    否则 `version.htm` 直接返回空 —— 这是实测踩过的坑。
 */
class BbLiveRepository {

    private val allHost = "https://matchs.qiumibao.com"
    private val incHost = "https://matchc.qiumibao.com"
    private val referer = "https://www.188bifen.com/lanqiubifen.htm"

    /** 全量（含联赛色板）。返回已过滤的**竞彩篮球**，按「进行中 → 未开始 → 完赛」排序。 */
    suspend fun fetchAll(): List<BbLiveMatch> = withContext(Dispatchers.IO) {
        BbLiveParser.parseAll(ApiClient.get("$allHost/live/all.htm", referer))
    }

    /**
     * 拉当前版本号与增量文件路径。解析失败/字段缺失返回 null（调用方静默跳过本次 tick）。
     * @return `version to url`
     */
    suspend fun fetchVersion(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        val text = ApiClient.get("$allHost/live/version.htm", referer)
        val o = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return@withContext null
        val v = runCatching { o.get("v").asLong }.getOrDefault(0L)
        val url = runCatching { o.get("url").asString }.getOrDefault("")
        if (v <= 0L || url.isBlank()) null else v to url
    }

    /** 拉增量补丁（[path] 形如 `/live/2026/10/22/58738229.htm`）。 */
    suspend fun fetchPatch(path: String): List<BbPatch> = withContext(Dispatchers.IO) {
        BbLiveParser.parsePatch(ApiClient.get(incHost + path, referer))
    }
}
