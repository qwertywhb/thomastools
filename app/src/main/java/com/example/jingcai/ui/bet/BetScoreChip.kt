package com.example.jingcai.ui.bet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jingcai.data.model.LegResult
import com.example.jingcai.ui.theme.Down
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Up

/**
 * 单场赛果比分块（订单卡上那个**一眼能看到的记分牌**）。
 *
 * ## 为什么单独做
 * 用户反馈「系统知道有没有中，用户不知道」——右上角的中/未圆章只说结果，
 * 不说**几比几**，用户还得自己去猜或翻直播。这里把 `BetTicket.scores` 里的
 * 赛果快照直接打成一块**深底白字**的记分牌，贴在每场对阵旁边：
 * 记录页（收起摘要 + 展开明细）与账本页共用，两处口径必须一致。
 *
 * ## 存量格式（见 [com.example.jingcai.data.model.BetTicket.scores]）
 * `scores[matchId]` = `"全场主:客|半场主:客"`，半场可缺省（`"2:1"`）。
 * 🔴 解析必须容忍脏数据：空串、缺冒号、非数字都当「没赛果」，绝不能抛。
 *    半场只有在**与全场不同**时才补一行（半全场玩法才看得出意义）。
 */
@Composable
fun MatchScoreChip(
    score: String?,
    modifier: Modifier = Modifier,
    /** 全场比分字号；记分牌会按它自适应。 */
    fontSize: TextUnit = 13.sp,
    /**
     * 是否补一行半场比分。
     *
     * 🔴 **只有「半全场」玩法才传 true**：其它玩法的半场比分与判定无关，
     *    早期数据里还会带假的 `|0:0`（录分没填半场），一律不显示，免得误导用户。
     */
    showHalf: Boolean = false,
    /**
     * 没有赛果时是否显示「待开奖」灰底占位。
     *
     * 🔴 账单页与注单详情都传 `!settled`（未开奖才显示占位）：卡右侧已有「待开奖」印章，
     *    这里只负责告诉用户「赛后这个位置会出现比分」。
     */
    showPending: Boolean = true
) {
    val full = parseScore(score?.substringBefore('|'))
    // 🔴 记分牌内边距**跟着字号走**，不是写死 9dp：
    //    13sp 时正好 = 9dp / 3.5dp（原值，直播详情页观感不变）；
    //    账单页现在用 11sp，写死 9dp 会让那个深色块比字大一大圈、显得笨重
    //    （用户 2026-11-17：「赛果不用占这么大的空间」）。
    val chipPadH = (fontSize.value * 0.69f).dp
    val chipPadV = (fontSize.value * 0.27f).dp
    // 🔴 必须写 `if/else`，**不能** `return@Column` 早退（这里踩过一次真崩溃）：
    //    Compose 编译器不会为 composable lambda 的提前返回补上 endGroup ⇒
    //    该 LazyColumn item 的 group 结构失衡，紧接着的组合直接抛
    //    `ArrayIndexOutOfBoundsException: length=0; index=-5`（SlotTableKt.key）。
    //    触发条件是「有未结算注单」—— 新下的单没有赛果，必走这一支，所以一下注就崩。
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (full == null) {
            // 还没有赛果：给个灰底占位，让用户知道「这里赛后会有比分」，而不是空白。
            // （账本页不需要：见 `showPending` 注释，那里未开奖就什么都不显示）
            if (showPending) {
                Text(
                    "待开奖",
                    fontSize = (fontSize.value * 0.8f).sp,
                    color = TextSub,
                    modifier = Modifier
                        .background(SurfaceAlt, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        } else {
            // 记分牌：深底白字，对比度拉满，远看也认得出是「比分」不是「选项」
            Text(
                "${full.first} : ${full.second}",
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 0.5.sp,
                modifier = Modifier
                    .background(BOARD, RoundedCornerShape(6.dp))
                    .padding(horizontal = chipPadH, vertical = chipPadV)
            )
            val half = if (showHalf) parseScore(score?.substringAfter('|', missingDelimiterValue = "")) else null
            if (half != null) {
                Text(
                    "半 ${half.first}:${half.second}",
                    fontSize = (fontSize.value * 0.72f).sp,
                    color = TextSub,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }
}

/** 记分牌底色：近黑的深蓝灰，和白字对比最强，又不像纯黑那么生硬。 */
private val BOARD = Color(0xFF1B2333)

/** `"2:1"` → `(2,1)`；脏数据一律 null。 */
private fun parseScore(s: String?): Pair<Int, Int>? {
    if (s.isNullOrBlank()) return null
    val parts = s.trim().split(':')
    if (parts.size != 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val a = parts[1].trim().toIntOrNull() ?: return null
    return h to a
}

/**
 * 单腿结果小徽标（中 / 挂 / 退 / 待）。
 *
 * 记录页展开后每个选项都要带它：只说整单中没中还不够，
 * 用户要知道**自己选的那一项**是中还是挂，才算真正「看得懂这单」。
 */
@Composable
fun LegChip(r: LegResult, modifier: Modifier = Modifier) {
    val (txt, bg, fg) = when (r) {
        // 半透明语义色底：浅色 / 深色两套底色上都能成立，不用写两份（原先浅色写死 #FFECEC/#E9F7EF）
        LegResult.WIN -> Triple("中", Up.copy(alpha = 0.14f), Up)
        LegResult.LOSE -> Triple("挂", Down.copy(alpha = 0.14f), Down)
        LegResult.VOID -> Triple("退", SurfaceAlt, TextSub)
        LegResult.PENDING -> Triple("待", SurfaceAlt, TextSub)
    }
    Text(
        txt, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = fg,
        modifier = modifier
            .background(bg, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}
