package com.example.jingcai.data.repository

import com.example.jingcai.data.model.BbLiveMatch
import com.example.jingcai.data.network.ApiClient
import com.example.jingcai.data.network.BetSettleParser
import com.example.jingcai.data.settle.BasketScore
import com.example.jingcai.data.settle.MatchScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 结算数据源。
 *
 * - **映射**：`cp.titan007.com/buy/JingCai.aspx` → `竞彩编号|主队|客队 → titanId`
 *   （详见 [BetSettleParser] 的类注释，55/55 唯一命中）；
 * - **足球赛果**：复用比分直播的 `bfdata_ut.js`（按 titanId 精确取，含半场比分）；
 * - **篮球赛果**：复用篮球比分源，按**竞彩编号**（`周三301`）精确匹配。
 *
 * 🔴 三条必须知道的口径：
 *  1. 只有 `state == -1`（足球）/ `status == 8`（篮球）才算完场；
 *     足球 `state < -1` 是延期腰斩，按取消处理（[MatchScore.cancelled]）；
 *  2. 比分区间的半场分**必须一起取**，否则半全场玩法全判错；
 *  3. 取不到结果 ≠ 0:0。宁可返回空，让注单停在「待开奖」等人工，
 *     也不能拿 0:0 去判 —— 那会把中奖单直接判死。
 */
class BetSettleRepository {

    private val liveRepo = LiveScoreRepository()
    private val bbRepo = BbLiveRepository()

    private val jcPage = "https://cp.titan007.com/buy/JingCai.aspx"

    /** 拉一次竞彩页，得到 `编号|主队|客队 → titanId`。失败返回空表（不抛）。 */
    suspend fun fetchTitanRefs(): Map<String, Long> = withContext(Dispatchers.IO) {
        runCatching {
            val html = ApiClient.get(jcPage, jcPage)
            BetSettleParser.parseJingCai(html)
                .associate { BetSettleParser.keyOf(it) to it.titanId }
        }.getOrDefault(emptyMap())
    }

    /** 足球赛果：`titanId → MatchScore`。未结束的场次 `finished = false`。 */
    suspend fun fetchSoccerScores(): Map<Long, MatchScore> = withContext(Dispatchers.IO) {
        runCatching {
            // 只要比分，不拉角球技统（多 247KB，结算用不上）
            val raw = liveRepo.fetchBfData(includeCorners = false)
            val out = HashMap<Long, MatchScore>(raw.matches.size)
            for (m in raw.matches) {
                val full = BetSettleParser.parseScoreText(m.score) ?: continue
                out[m.titanId] = when {
                    // 完场
                    m.state == -1 -> MatchScore(
                        full.first, full.second, m.homeHalf, m.awayHalf, finished = true
                    )
                    // 延期 / 腰斩 / 异常：按取消，赔率 1.0 处理
                    m.state < -1 -> MatchScore(
                        0, 0, 0, 0, finished = true, cancelled = true
                    )
                    // 进行中 / 未开始
                    else -> MatchScore(
                        full.first, full.second, m.homeHalf, m.awayHalf, finished = false
                    )
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    /** 篮球赛果源（原始列表，匹配由调用方按竞彩编号做）。 */
    suspend fun fetchBasketMatches(): List<BbLiveMatch> = withContext(Dispatchers.IO) {
        runCatching { bbRepo.fetchAll() }.getOrDefault(emptyList())
    }

    /**
     * 篮球：`竞彩编号 → [BasketScore]`（比分 **+ 数据源的主客队名**）。
     *
     * 篮球的竞彩编号本身就是 `周三301` 这种完整编号（和体彩 `matchNumStr` 同一体系），
     * 所以**不需要** titanId 映射，直接用编号当键。
     *
     * 🔴 为什么不能直接返回 [MatchScore]：球米宝的主客顺序与体彩**可能相反**
     *    （实测 NBA 全反、NBL 一致）。比分必须连队名一起带出去，
     *    由 [BasketScore.alignedTo] 摆回体彩口径后再结算，
     *    否则让分会被算成「客 + 线 - 主」，方向整反（用户报障「让分开奖错了」）。
     *
     * 🔴 未开赛（`status == 1`）的场次**不产出**：它的 0:0 不是「已出结果 0:0」。
     *    以前会把 0:0 一起塞进赛果表，既不参与判定（finished=false）又会被
     *    [com.example.jingcai.data.settle.BetSettleEngine] 的快照写进注单，
     *    界面上就出现「比赛还没开始却显示 0:0」—— 未开赛就该是「待开奖」。
     */
    fun basketScores(list: List<BbLiveMatch>): Map<String, BasketScore> {
        val out = HashMap<String, BasketScore>()
        for (m in list) {
            if (m.jcNo.isBlank()) continue
            if (m.status == 1) continue
            // 0:0 且还没完赛 ⇒ 事实上还没开打（源站开赛后才会给真实比分）。
            // 存进赛果表只会让注单快照多一条「0:0」假比分，判定也完全用不上。
            if (m.status != 8 && m.homeScore == 0 && m.awayScore == 0) continue
            val finished = m.status == 8
            val cancelled = m.status == 9 || m.status == 0
            // 🔴 **先按源站自己的标注摆正主客，再交给 BasketScore**（2026-11-19 修）。
            //    欧洲联赛源站是「主 @ 客」，而体彩对欧洲队用极简简称（`巴萨` / `贝游击`），
            //    跟源站全称（`巴塞罗那` / `贝尔格莱德游击`）对不上 ⇒ [BasketScore.alignedTo]
            //    的队名判据必然漏翻，让分/胜负就被算反。标注（[BbLiveMatch.hostOnFirst]）
            //    是源站直接告诉我们的答案，不依赖译名，和显示侧 `alignToJc` 同源。
            val aligned = if (m.hostOnFirst == true) m.swapped() else m
            out[m.jcNo] = BasketScore(
                sourceHome = aligned.home,
                sourceAway = aligned.away,
                // 有标注 ⇒ 口径已确定，结算侧不要再拿队名猜一次（猜错就是把中奖单判死）
                hostKnown = m.hostOnFirst != null,
                score = MatchScore(
                    fullHome = aligned.homeScore,
                    fullAway = aligned.awayScore,
                    halfHome = aligned.homeHalf,
                    halfAway = aligned.awayHalf,
                    finished = finished || cancelled,
                    cancelled = cancelled
                )
            )
        }
        return out
    }
}
