package com.example.jingcai.ui.oddschange

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jingcai.data.model.FixedBonusRow
import com.example.jingcai.ui.*
import com.example.jingcai.ui.theme.*

@Composable
fun OddsChangeScreen(order: String, vsText: String, vm: OddsChangeViewModel = viewModel(), onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.load(order, vsText) }
    val state by vm.state.collectAsState()

    // 🔴 由 [ToolPage] 统一提供：页面底色（Theme Background）+ 52dp 主色顶栏 + 可滚动内容区。
    //    旧版是 Scaffold+TopAppBar，在父 Scaffold 之内会二次申请 statusBars inset（顶部多一截空白）。
    //    内容区必须可滚动（用户实测）：胜平负的变动记录一多，两张表叠起来会超出屏高，
    //    原来的裸 Column 没有滚动 ⇒ 下面的「让球胜平负」被裁掉甚至完全看不到。
    ToolPage(
        title = "赔率变化",
        subtitle = state.data?.let { "${it.home} VS ${it.away}" } ?: vsText.ifEmpty { null },
        onBack = onBack
    ) {
        when {
            state.loading -> Loading()
            state.error != null -> ErrorText(state.error)
            state.data == null -> EmptyText()
            else -> {
                BonusTable("胜平负固定奖金", state.data!!.had)
                BonusTable("让球胜平负固定奖金（让球 ${state.data!!.handicap.ifEmpty { "-" }}）", state.data!!.hhad)
            }
        }
    }
}

@Composable
private fun BonusTable(title: String, rows: List<FixedBonusRow>) {
    // 段落标题收进卡内标题条（旧版是卡片外的裸 Text，三页里只有这页这么做 ⇒ 观感不统一）
    SectionCard(title) {
        Column {
            // 表头用 SurfaceAlt：与 SectionCard 标题条的 SurfaceTint 拉开一档，否则上下两条同色
            Row(Modifier.background(SurfaceAlt).padding(vertical = 8.dp)) {
                Head("发布时间", 0.40f); Head("胜", 0.20f); Head("平", 0.20f); Head("负", 0.20f)
            }
            if (rows.isEmpty()) {
                Text("无数据", color = TextSub, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            } else {
                // 隔行浅底 + 细分隔线：变动记录一多时，深色赔率与浅灰时间容易「糊成一片」，
                // 加隔行底之后每一条都是一行独立数据（用户反馈「每行数据颜色太淡」的表层修法）。
                // 隔行改用 SurfaceTint（比表头的 SurfaceAlt 深一档），表头与隔行不会混为一谈。
                rows.forEachIndexed { i, r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (i % 2 == 1) SurfaceTint else Color.Transparent)
                            .padding(vertical = 9.dp)
                    ) {
                        Text(r.updateTime, fontSize = 12.sp, color = TextSub, modifier = Modifier.weight(0.40f), maxLines = 1)
                        OddsVal(r.h, r.hf, 0.20f)
                        OddsVal(r.d, r.df, 0.20f)
                        OddsVal(r.a, r.af, 0.20f)
                    }
                    if (i != rows.lastIndex) HorizontalDivider(color = LineSoft, thickness = 0.6.dp)
                }
            }
        }
    }
}

@Composable
private fun RowScope.Head(text: String, w: Float) {
    Text(text, fontSize = 11.sp, color = TextSub, modifier = Modifier.weight(w), fontWeight = FontWeight.SemiBold)
}

@Composable
private fun RowScope.OddsVal(v: String, flag: Int, w: Float) {
    val color = when {
        flag > 0 -> Up
        flag < 0 -> Down
        else -> TextMain
    }
    Text("$v${arrow(flag)}", fontSize = 14.sp, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(w))
}
