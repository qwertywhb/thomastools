package com.example.jingcai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jingcai.ui.theme.Background
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.Surface as SurfaceColor
import com.example.jingcai.ui.theme.SurfaceTint
import com.example.jingcai.ui.theme.TextMain

/** 工具页统一顶栏高度（三页同高，保证切换页面时头部不跳）。 */
private val TOP_BAR_H = 52.dp

/**
 * 「赔率变化 / 同奖查询 / 近期状态」三页统一外壳。
 *
 * 🔴 不用 Scaffold：这三页都在 MainActivity 的父 Scaffold 之内，父 Scaffold 的
 *    contentWindowInsets 已经把状态栏高度算进 padding；再用 Scaffold+TopAppBar
 *    会二次申请 statusBars inset ⇒ 顶部多出一截空白。这里自绘顶栏，绝不碰 windowInsets。
 *
 * 三页共用它带来的一致项：**页面底色 [Background]**、顶栏高度 52dp / 主色 [Primary]、
 * 顶栏下方可选固定区 [header]（近期状态的 TabRow）、内容区 12dp 边距 + 10dp 块间距。
 *
 * 🔴 [scrollable] = false 只留给「内容里自带 LazyColumn」的页面：
 *    外层再套 `verticalScroll` 会让 LazyColumn 收到**无限高**约束，直接抛
 *    `IllegalStateException: Vertically scrollable component was measured with an
 *    infinity maximum height constraints`（10-23 实测：点「同奖查询」即闪退）。
 *    同奖查询已把结果表改成普通 Column（整页一起滚），所以三个工具页现在都用默认 true 了。
 */
@Composable
fun ToolPage(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    /** true 时 subtitle 与 title 同一行（右对齐小字），顶栏仍是 52dp 单行。 */
    inlineSubtitle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(Background)) {
        Surface(color = Primary, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(TOP_BAR_H),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    TextButton(onClick = onBack) {
                        Text("‹ 返回", color = Color.White, fontSize = 14.sp)
                    }
                } else {
                    // 无返回键（tab 页）：左侧留白与右侧对称，标题不会贴边
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    if (inlineSubtitle && subtitle != null) {
                        // 单行顶栏：标题 + 紧跟的小字副标题（weight(fill = false) 让它只吃剩余宽度，
                        // 长队名用省略号收尾，绝不换行顶高顶栏）
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                subtitle,
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = .85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    } else {
                        Text(
                            title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = .85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.width(if (onBack != null) 0.dp else 12.dp))
            }
        }

        // 顶栏正下方、内容上方的整宽固定区（如 TabRow）：不随内容滚动
        header?.invoke()

        val scrollState = rememberScrollState()
        Column(
            (if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}

/**
 * 统一数据块：白卡 + 可选顶部浅色标题条。三页所有数据块都用它。
 *
 * 圆角 14dp / 海拔 2dp 是三页折中的那套值（旧版分别是 16+3、16+3、12+2）——
 * 统一后切换页面不再出现「卡片胖瘦不一」的观感差。
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor)
    ) {
        Column {
            if (title != null) {
                // 标题条底 = SurfaceTint：卡内表头请用 SurfaceAlt，否则两者同色「糊成一片」
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(SurfaceTint)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                }
            }
            content()
        }
    }
}
