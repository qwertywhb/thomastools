package com.example.jingcai.ui.bet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jingcai.data.model.BetPick
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.settle.MatchScore
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.TextMain
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Up

/**
 * 手动录分结算弹窗。
 *
 * ## 为什么必须有这个出口
 * 赛果只有 `bfdata_ut.js` 一个来源，而它只覆盖**当天+近日**的比赛。用户隔天才打开 App 时，
 * 老比赛早已滚出窗口，注单会永远停在「待开奖」—— 没有兜底就变成死单，
 * 单子会一直挂在「待开奖」，永远等不到结果。
 *
 * ## 🔴 为什么是「主/客两个数字框」而不是一个「2-1」输入框
 * 上一版是单框输入 `2-1`，实测在手机上很难填：分隔符要切到符号键盘、`-` 命中率低，
 * 而且不同人对「主-客」还是「客-主」理解不一，填反了会静默判错。
 * 现在拆成 `主队 [__] : 客队 [__]`，只输数字，方向由标签写死，填错的概率接近于零。
 *
 * ## 口径
 * - 全场必填；半场只有**本单含半全场玩法**时才显示且必填；
 * - 「取消/腰斩」勾选后该场按赔率 1.0 处理（退款）。
 */
@Composable
fun BetManualSettleDialog(
    ticket: BetTicket,
    onDismiss: () -> Unit,
    onConfirm: (Map<Long, MatchScore>) -> Unit
) {
    val matches = remember(ticket.id) {
        ticket.picks.groupBy { it.matchId }.values.map { it.first() }
    }
    val hasHafu = remember(ticket.id) { ticket.picks.any { it.poolCode == "hafu" } }

    // 四张表：全场主/客、半场主/客
    val fh = remember(ticket.id) { mutableStateMapOf<Long, String>() }
    val fa = remember(ticket.id) { mutableStateMapOf<Long, String>() }
    val hh = remember(ticket.id) { mutableStateMapOf<Long, String>() }
    val ha = remember(ticket.id) { mutableStateMapOf<Long, String>() }
    val cancel = remember(ticket.id) { mutableStateMapOf<Long, Boolean>() }

    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 🔴 禁止点击外部关闭：这个弹窗要逐场录比分，一不小心点到边上就把已填的内容全丢了
        //    （实测在键盘弹出再收起的过程中极易误触）。只能点「取消」或系统返回键退出。
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        title = {
            Text("手动录分结算", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "按「主队进球 : 客队进球」填写，填完点「结算」立即派彩。",
                    fontSize = 11.sp, color = TextSub
                )
                if (hasHafu) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "本单含「半全场」玩法，半场比分必须填，否则该腿会判错。",
                        fontSize = 11.sp, color = Up, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Line, thickness = 1.dp)
                matches.forEach { p ->
                    ScoreRow(
                        pick = p,
                        fullHome = fh[p.matchId] ?: "",
                        fullAway = fa[p.matchId] ?: "",
                        halfHome = hh[p.matchId] ?: "",
                        halfAway = ha[p.matchId] ?: "",
                        cancelled = cancel[p.matchId] == true,
                        showHalf = hasHafu,
                        onFullHome = { fh[p.matchId] = it },
                        onFullAway = { fa[p.matchId] = it },
                        onHalfHome = { hh[p.matchId] = it },
                        onHalfAway = { ha[p.matchId] = it },
                        onCancel = { cancel[p.matchId] = it }
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error!!, fontSize = 11.sp, color = Up, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Text(
                "结算",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primary,
                modifier = Modifier.clickable {
                    val out = HashMap<Long, MatchScore>()
                    var bad: String? = null
                    for (p in matches) {
                        if (cancel[p.matchId] == true) {
                            out[p.matchId] = MatchScore(0, 0, 0, 0, finished = true, cancelled = true)
                            continue
                        }
                        val a = fh[p.matchId]?.toIntOrNull()
                        val b = fa[p.matchId]?.toIntOrNull()
                        if (a == null || b == null) {
                            bad = "「${p.home} VS ${p.away}」的全场比分没填完"
                            break
                        }
                        val c = hh[p.matchId]?.toIntOrNull()
                        val d = ha[p.matchId]?.toIntOrNull()
                        if (hasHafu && (c == null || d == null)) {
                            bad = "「${p.home} VS ${p.away}」含半全场玩法，半场比分必填"
                            break
                        }
                        out[p.matchId] = MatchScore(
                            fullHome = a, fullAway = b,
                            halfHome = c ?: 0, halfAway = d ?: 0,
                            finished = true
                        )
                    }
                    if (bad != null) error = bad else onConfirm(out)
                }.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        },
        dismissButton = {
            Text(
                "取消",
                fontSize = 13.sp, color = TextSub,
                modifier = Modifier.clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        },
        containerColor = Surface
    )
}

@Composable
private fun ScoreRow(
    pick: BetPick,
    fullHome: String,
    fullAway: String,
    halfHome: String,
    halfAway: String,
    cancelled: Boolean,
    showHalf: Boolean,
    onFullHome: (String) -> Unit,
    onFullAway: (String) -> Unit,
    onHalfHome: (String) -> Unit,
    onHalfAway: (String) -> Unit,
    onCancel: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pick.num, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Primary,
                modifier = Modifier.background(SurfaceAlt, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "${pick.home} VS ${pick.away}",
                fontSize = 11.sp, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (cancelled) "已标取消" else "取消",
                fontSize = 10.sp, fontWeight = if (cancelled) FontWeight.Bold else FontWeight.Normal,
                color = if (cancelled) Up else TextSub,
                modifier = Modifier.clickable { onCancel(!cancelled) }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        if (!cancelled) {
            Spacer(Modifier.height(5.dp))
            ScoreLine("全场", fullHome, fullAway, onFullHome, onFullAway)
            if (showHalf) {
                Spacer(Modifier.height(4.dp))
                ScoreLine("半场", halfHome, halfAway, onHalfHome, onHalfAway)
            }
        }
    }
}

/** 一行比分输入：`标签  主队 [n]  :  客队 [n]`。 */
@Composable
private fun ScoreLine(
    label: String,
    home: String,
    away: String,
    onHome: (String) -> Unit,
    onAway: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.5.sp, color = TextSub, modifier = Modifier.width(28.dp))
        NumBox(home, onHome)
        Text(" : ", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextSub)
        NumBox(away, onAway)
    }
}

/**
 * 单个进球数输入框。
 *
 * 🔴 只收数字、最多 2 位：比分不可能是 100:0，两位足够；限长可以顺带把
 *    「用户误粘贴一长串」挡掉，避免 `toIntOrNull()` 溢出成 null 后提示不清不楚。
 */
@Composable
private fun NumBox(value: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .width(46.dp)
            .height(32.dp)
            .background(SurfaceAlt, RoundedCornerShape(6.dp))
            .border(1.dp, if (value.isBlank()) Line else Primary.copy(alpha = .6f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = { raw -> onChange(raw.filter { it.isDigit() }.take(2)) },
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = TextMain, textAlign = TextAlign.Center
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        )
    }
}

/**
 * 该单是否提供「录分结算」入口：**只要还没结算就给**。
 *
 * 🔴 不要收窄成「存在 titanId==0 的腿」：自动结算失败的形态不止一种 ——
 *    比赛滚出 `bfdata_ut.js` 窗口（titanId 有值但取不到比分）、足球源临时不可用、
 *    篮球源编号对不上，这些情况 titanId 都不为空，收窄后就再也点不出录分口，
 *    注单会永久停在「待开奖」。
 *    这里把判断交给用户：赛果出来了就自己录，没开赛就别点。
 */
fun needsManualSettle(t: BetTicket): Boolean = !t.settled
