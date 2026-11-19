package com.example.jingcai.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jingcai.data.model.JcSpfChange
import com.example.jingcai.data.repository.LiveScoreRepository
import com.example.jingcai.ui.EmptyText
import com.example.jingcai.ui.ErrorText
import com.example.jingcai.ui.Loading
import com.example.jingcai.ui.SectionCard
import com.example.jingcai.ui.SpfChangeViewModel
import com.example.jingcai.ui.ToolPage
import com.example.jingcai.ui.theme.*

/**
 * 竞彩**胜平负赔率变动**页。
 *
 * 入口：比分直播 → 竞彩赔率子行右端的「变动」图标（只有竞彩场次、且源站渲染了「变」链接才有）。
 * 数据源：titan007 `Handle/JcSp.aspx?oddsType=3&spid=…&typeID=105`（胜平负；101 是让球，别混）。
 *
 * ## 页面构成
 *  1. **走势图**：三条折线（胜/平/负），横轴按时间正序；
 *  2. **变动记录表**：时间正序的逆序（最新在上），每行的 `胜/平/负` 都可点 ——
 *     点哪一行就按**那一行**的三值去查历史同奖（用户要求：点某一行赔率跳到同奖查询）。
 *
 * 🔴 为「一条记录都没有」留了明确文案：不少场次开盘后从未变动，源站只返回一条初始值，
 *    这时图表画一个点、表格一行，不报错也不空白。
 */
@Composable
fun SpfChangeScreen(
    spid: String,
    order: String,
    vsText: String,
    onBack: () -> Unit,
    /** 点某一行赔率 → 同奖查询（h, d, a 原样透传，空串表示该列不参与查询）。 */
    onSameOdds: (String, String, String) -> Unit,
    vm: SpfChangeViewModel = viewModel()
) {
    LaunchedEffect(spid) { vm.load(spid) }
    val state by vm.state.collectAsState()

    val subtitle = listOf(order, vsText).filter { it.isNotBlank() }.joinToString("   ")
    val rq = state.typeId == LiveScoreRepository.TYPE_RQ
    ToolPage(
        title = "竞彩赔率变动",
        subtitle = subtitle.ifEmpty { null },
        onBack = onBack,
        // 玩法分段：胜平负 / 让球。**共用同一个 spid**，切一次就重拉一次（typeID 105 ↔ 101）。
        header = {
            Row(
                Modifier.fillMaxWidth().background(Surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TypeChip("胜平负", !rq) { vm.switchType(LiveScoreRepository.TYPE_SPF) }
                TypeChip("让球", rq) { vm.switchType(LiveScoreRepository.TYPE_RQ) }
                Spacer(Modifier.weight(1f))
                Text(
                    if (rq) "含让球数的胜平负" else "不让球",
                    fontSize = 11.sp, color = TextSub, maxLines = 1
                )
            }
        }
    ) {
        when {
            state.loading -> Loading()
            state.error != null -> ErrorText(state.error!!)
            state.rows.isEmpty() -> EmptyText(
                if (rq) "该场暂无让球赔率变动记录" else "该场暂无赔率变动记录"
            )
            else -> Column(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SpfTrendCard(state.rows)
                SpfChangeTable(state.rows, onSameOdds)
                Text(
                    "点任意一行的赔率 → 按该组胜平负赔率查历史同奖",
                    fontSize = 11.sp,
                    color = TextSub,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
    }
}

/**
 * 玩法分段胶囊。
 *
 * 只有两个选项（胜平负 / 让球），所以不用 Material 的 SegmentedButton —— 那个在
 * 本项目 compose 版本上还要额外依赖，两个 Box 更省事，样式也更好对齐页面其余胶囊。
 */
@Composable
private fun TypeChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp)
            .background(if (on) Primary else SurfaceAlt, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = if (on) Color.White else TextSub,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// ============================== 走势图 ==============================

/** 三条线的配色：胜红 / 平琥珀 / 负蓝。刻意避开绿（绿在本 App 是「跌」的语义）。 */
private val C_H = Color(0xFFD64545)
private val C_D = Color(0xFFD9901F)
private val C_A = Color(0xFF8E44AD)

/**
 * 赔率走势折线图。
 *
 * 🔴 纵轴范围必须用**三条线的全局 min/max**：只看单条线各自缩放，三条线的相对高低就是假象。
 * 🔴 只有一条记录时不画线、画点 —— 一条线段的 Canvas 会因 `points.size - 1 == 0` 而除零。
 */
@Composable
private fun SpfTrendCard(rows: List<JcSpfChange>) {
    val hs = rows.map { it.hVal }
    val ds = rows.map { it.dVal }
    val asList = rows.map { it.aVal }
    val all = hs + ds + asList
    val lo = all.minOrNull() ?: 0.0
    val hi = all.maxOrNull() ?: 0.0
    // 上下各留 6% 余量，否则最值会贴边压住网格线
    val pad = ((hi - lo) * 0.06).coerceAtLeast(0.02)
    val yMin = lo - pad
    val yMax = hi + pad

    SectionCard("赔率走势（${rows.size} 次记录）") {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot("胜", C_H)
                Spacer(Modifier.width(12.dp))
                LegendDot("平", C_D)
                Spacer(Modifier.width(12.dp))
                LegendDot("负", C_A)
                Spacer(Modifier.weight(1f))
                Text(
                    "最新 ${rows.last().h} / ${rows.last().d} / ${rows.last().a}",
                    fontSize = 10.5.sp,
                    color = TextSub,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(Modifier.height(132.dp)) {
                // 纵轴：上中下三个刻度（不在 Canvas 里画字，避免引入 TextMeasurer 依赖）
                Column(
                    Modifier.width(32.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    AxisLabel(yMax)
                    AxisLabel((yMax + yMin) / 2)
                    AxisLabel(yMin)
                }
                Spacer(Modifier.width(6.dp))
                Canvas(Modifier.weight(1f).fillMaxHeight()) {
                    val w = size.width
                    val h = size.height
                    fun yOf(v: Double): Float =
                        (h - ((v - yMin) / (yMax - yMin)).toFloat() * h).coerceIn(0f, h)
                    // 网格：三条水平细线，和左侧三个刻度对齐
                    for (i in 0..2) {
                        val y = h * i / 2f
                        drawLine(
                            Color(0x14000000), Offset(0f, y), Offset(w, y),
                            strokeWidth = 1.dp.toPx() * 0.5f
                        )
                    }
                    // 变化点：只有 1 个记录时 x 居中
                    val n = rows.size
                    fun xOf(i: Int): Float =
                        if (n <= 1) w / 2f else w * i / (n - 1).toFloat()

                    fun series(vals: List<Double>, color: Color) {
                        // 🔴 线宽/半径必须走 dp→px：Canvas 里的裸数字是**像素**，
                        //    在 560dpi（3.5x）机型上 2.2f 只有 0.6dp，细到几乎看不见（实测踩过）。
                        val lineW = 1.8.dp.toPx()
                        val dotR = 2.2.dp.toPx()
                        if (n == 1) {
                            drawCircle(color, radius = dotR, center = Offset(xOf(0), yOf(vals[0])))
                            return
                        }
                        val path = Path()
                        vals.forEachIndexed { i, v ->
                            val p = Offset(xOf(i), yOf(v))
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        drawPath(
                            path, color,
                            style = Stroke(width = lineW, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        // 拐点圆点必须画在**线之后**，否则被线盖住一半（老坑）
                        vals.forEachIndexed { i, v ->
                            drawCircle(color, radius = dotR, center = Offset(xOf(i), yOf(v)))
                        }
                    }
                    series(hs, C_H)
                    series(ds, C_D)
                    series(asList, C_A)
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().padding(start = 38.dp)) {
                Text(rows.first().time, fontSize = 10.sp, color = TextSub)
                Spacer(Modifier.weight(1f))
                if (rows.size > 1) Text(rows.last().time, fontSize = 10.sp, color = TextSub)
            }
        }
    }
}

@Composable
private fun AxisLabel(v: Double) {
    Text(fmtOdds(v), fontSize = 9.5.sp, color = TextSub, maxLines = 1)
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .background(color, RoundedCornerShape(999.dp))
        )
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 10.5.sp, color = TextMain, fontWeight = FontWeight.Bold)
    }
}

// ============================== 变动记录表 ==============================

/**
 * 变动记录：**最新在上**（与源站弹窗一致，看最新价最省眼），点击整行 → 同奖查询。
 *
 * 🔴 每行传出去的是**该行自己的**三值，不是当前值：用户要查的是「这个赔率组合历史上还开过哪些奖」，
 *    传当前值等于每一行都查同一个结果。
 */
@Composable
private fun SpfChangeTable(rows: List<JcSpfChange>, onSameOdds: (String, String, String) -> Unit) {
    val newestFirst = rows.asReversed()
    SectionCard("变动记录") {
        Column {
            Row(Modifier.background(SurfaceAlt).padding(vertical = 8.dp)) {
                HeadText("变化时间", 0.36f)
                HeadText("胜", 0.16f)
                HeadText("平", 0.16f)
                HeadText("负", 0.16f)
                HeadText("同奖", 0.16f)
            }
            newestFirst.forEachIndexed { i, r ->
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (i % 2 == 1) SurfaceTint else Color.Transparent)
                            .clickable { onSameOdds(r.h, r.d, r.a) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier.weight(0.36f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(r.time, fontSize = 11.5.sp, color = TextSub, maxLines = 1)
                            if (i == 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "最新",
                                    fontSize = 8.5.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(C_H, RoundedCornerShape(2.dp))
                                        .padding(horizontal = 3.dp, vertical = 0.5.dp),
                                    maxLines = 1
                                )
                            }
                        }
                        OddsCell(r.h, C_H, 0.16f)
                        OddsCell(r.d, C_D, 0.16f)
                        OddsCell(r.a, C_A, 0.16f)
                        Text(
                            "查询 ›",
                            fontSize = 10.5.sp,
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(0.16f),
                            maxLines = 1
                        )
                    }
                    if (i != newestFirst.lastIndex) {
                        HorizontalDivider(color = LineSoft, thickness = 0.6.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeadText(text: String, w: Float) {
    Text(
        text,
        fontSize = 11.sp,
        color = TextSub,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(w),
        maxLines = 1
    )
}

@Composable
private fun RowScope.OddsCell(v: String, color: Color, w: Float) {
    Text(
        v,
        fontSize = 13.sp,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(w),
        maxLines = 1
    )
}

/** 赔率展示统一 2 位小数（源站有的给 `3`，有的给 `3.00`，混着看很乱）。 */
private fun fmtOdds(v: Double): String = String.format("%.2f", v)
