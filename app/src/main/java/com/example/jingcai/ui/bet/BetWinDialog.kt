package com.example.jingcai.ui.bet

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.jingcai.R
import com.example.jingcai.data.model.BetTicket
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * App 内「中奖啦」弹窗 —— **无卡片版**，完全对齐用户给的参照图。
 *
 * ## 版式（🔴 别再给它加卡片背景）
 * 弹窗内容**自己没有底板**：半透明黑遮罩 → 从奖杯背后射出的大范围金色放射光 →
 * 奖杯 → 「中奖啦！」→ 一行说明（含竞彩足球 / 竞彩篮球）→ 金额 → 通栏红按钮 → 右上角红圆 ✕。
 * 文字直接压在暗遮罩上，靠**深色投影**保可读性，这是参照图的做法，也是它好看的原因：
 * 下层的比赛列表被压暗但仍隐约可见，比一张实心卡片更有「爆炸式」的中奖感。
 *
 * 历史口径（勿回退）：
 * - 最早是满屏拉伸 + 全屏居中散光 ⇒「太丑」；
 * - 之后改浅色奶油卡片 ⇒ 用户再否：「弹窗越来越丑了，参照这个图，弹窗本身没背景，modal 背景是浅黑色」。
 *
 * ## 触发时机
 * 结算跑完、[com.example.jingcai.data.settle.SettleOutcome] 里出现中奖注单时，
 * 由 [BetSettleRunner] 推一条事件出来，AppNav 负责弹。只在**真的有新注单中奖**时弹一次
 * —— 结算过的单 `settledAt` 已写死，不会重复触发。
 *
 * @param won      本次中奖的注单（一条或多条）
 * @param amount   中奖合计（[BetTicket.payout] 之和，与通知里的金额同源）
 * @param onView   「立即查看」：单注直达该单详情，多注进账本
 * @param onDismiss 关闭（点 ✕ 或点空白处）
 */
@Composable
fun BetWinDialog(
    won: List<BetTicket>,
    amount: Double,
    onView: () -> Unit,
    onDismiss: () -> Unit
) {
    if (won.isEmpty()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        // 入场：奖杯弹一下，文字/按钮错开淡入（一起出现会像静态海报）
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }

        val bump by animateFloatAsState(
            targetValue = if (shown) 1f else .72f,
            animationSpec = spring(dampingRatio = .55f, stiffness = Spring.StiffnessLow),
            label = "bump"
        )
        val fade by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = tween(380, delayMillis = 180, easing = FastOutSlowInEasing),
            label = "fade"
        )
        val buttonFade by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = tween(320, delayMillis = 300, easing = FastOutSlowInEasing),
            label = "btn"
        )
        // 光线缓慢自转：静态光会像贴图，转起来才「活」
        val spin by rememberInfiniteTransition(label = "rays").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(28_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "spin"
        )

        // 奖杯中心相对屏幕高度的比例：光必须和奖杯同心，否则看起来像「贴了张图」
        val rayCenterYRatio = .375f

        // 半透明黑遮罩：点击空白处关闭 ⇒ 用无涟漪的 clickable（整屏水波纹很脏）
        Box(
            Modifier
                .fillMaxSize()
                .background(SCRIM)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            // —— 放射光：圆心对准奖杯，铺满上半屏 ——
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height * rayCenterYRatio)
                val spinRad = Math.toRadians(spin.toDouble())

                // 画一道光。角度方向靠**多层同心扇形叠加**实现羽化：
                // 单层扇形 = 两个硬边三角，屏幕上就是「动画集中线」那种生硬划痕（踩过两次）。
                // 6 层由宽到窄、每层只出一小份 alpha，边缘自然就过渡掉了。
                fun ray(mid: Double, halfAngle: Double, len: Float, a: Float) {
                    val p1 = Offset(
                        c.x + len * cos(mid - halfAngle).toFloat(),
                        c.y + len * sin(mid - halfAngle).toFloat()
                    )
                    val p2 = Offset(
                        c.x + len * cos(mid + halfAngle).toFloat(),
                        c.y + len * sin(mid + halfAngle).toFloat()
                    )
                    val tip = Offset(
                        c.x + len * cos(mid).toFloat(),
                        c.y + len * sin(mid).toFloat()
                    )
                    val path = Path().apply {
                        moveTo(c.x, c.y)
                        lineTo(p1.x, p1.y)
                        lineTo(p2.x, p2.y)
                        close()
                    }
                    // 顺着光线方向淡出。🔴 收尾必须用 Transparent，不能收尾到「遮罩色」
                    // —— 遮罩带不透明度，那样会二次叠色，屏幕上浮出一个硬边圆盘。
                    drawPath(
                        path,
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0f to GOLD.copy(alpha = a),
                                .22f to GOLD.copy(alpha = a * .96f),
                                .52f to GOLD.copy(alpha = a * .55f),
                                .80f to GOLD.copy(alpha = a * .16f),
                                1f to Color.Transparent
                            ),
                            start = c,
                            end = tip
                        )
                    )
                }

                // 主光：只铺到屏宽量级，不追到屏幕最外角 —— 太长了就变「蜘蛛脚」，
                // 而且会一路扫过文字区把对比度打散。
                val rMain = size.minDimension * 1.06f
                val n = 28
                val step = 2 * Math.PI / n
                val layers = listOf(
                    .44f to .060f, .36f to .060f, .28f to .070f,
                    .20f to .075f, .13f to .080f, .06f to .090f
                )
                repeat(n) { i ->
                    val mid = spinRad + i * step
                    // 奇偶交替长短，避免 28 道等长像齿轮
                    val len = rMain * if (i % 2 == 0) 1f else .82f
                    layers.forEach { (hr, a) -> ray(mid, step * hr, len, a) }
                }
                // 外围辅光：错开半格、极淡，只给「光还在往外溢」的暗示
                val rOuter = max(size.width, size.height) * .62f
                repeat(n) { i ->
                    val mid = spinRad + i * step + step / 2
                    ray(mid, step * .30f, rOuter, .022f)
                    ray(mid, step * .12f, rOuter, .030f)
                }

                // 中心光团：奖杯背后那圈暖白光，把奖杯和光「焊」在一起，
                // 否则光条会从奖杯上穿过去，看起来脏。
                // 🔴 半径要足够大、末段 alpha 要足够低：半径小时渐变收得太快，
                //    会在屏幕上看到一条清楚的圆弧边界（第一版像罩了个玻璃球）。
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color(0x99FFF4D2),
                            .18f to Color(0x66FFE9A8),
                            .38f to GOLD.copy(alpha = .20f),
                            .62f to GOLD.copy(alpha = .07f),
                            .82f to GOLD.copy(alpha = .02f),
                            1f to Color.Transparent
                        ),
                        center = c,
                        radius = rMain * .74f
                    ),
                    radius = rMain * .74f,
                    center = c
                )

                // 下半屏压暗：文字与按钮区必须干净，否则金光穿过白字会读不出来。
                // 参照图能读，是因为它的光在下方已经很淡了。
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            .34f to Color.Transparent,
                            .62f to Color(0x59000000),
                            1f to Color(0x8C000000)
                        ),
                        startY = 0f,
                        endY = size.height
                    )
                )
            }

            // —— 内容：没有卡片，直接浮在遮罩上 ——
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(.88f)
                    .widthIn(max = 400.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.bet_wintip),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(186.dp)
                        .aspectRatio(312f / 360f)
                        .scale(bump)
                        .alpha(if (shown) 1f else .2f)
                )

                Spacer(Modifier.height(if (shown) 6.dp else 22.dp))

                Text(
                    "中奖啦！",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp,
                    style = TextStyle(
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFFFFFAD2), Color(0xFFFFD23C), Color(0xFFFFA112))
                        ),
                        // 金色字要有光晕感：暗底上用暖色投影往外散
                        shadow = Shadow(
                            color = Color(0xE6803C00),
                            offset = Offset(0f, 2f),
                            blurRadius = 18f
                        )
                    )
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    winTitle(won),
                    fontSize = 16.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        shadow = Shadow(Color(0xB3000000), Offset(0f, 2f), 8f)
                    ),
                    modifier = Modifier.alpha(fade)
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    "${fmt(amount)}元！",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    style = TextStyle(
                        shadow = Shadow(Color(0xCC000000), Offset(0f, 3f), 12f)
                    ),
                    modifier = Modifier.alpha(fade)
                )

                Spacer(Modifier.height(26.dp))

                // —— 立即查看 ——
                Text(
                    "立即查看",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(buttonFade)
                        .background(VIEW_RED, RoundedCornerShape(8.dp))
                        .clickable(onClick = onView)
                        .padding(vertical = 16.dp)
                )
            }

            // —— 关闭：屏幕右上角红圆（参照图同位置）——
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 20.dp)
                    .size(32.dp)
                    .background(CLOSE_RED, CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text("✕", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 说明文案：带上**项目**（足球 / 篮球）。
 *
 * 用户口径：「中奖弹窗说明是足球还是篮球」—— 一注里可能混着篮球和足球（串关是允许跨项的），
 * 那时不能说成某一种，退回「N 注」的中性说法。
 */
private fun winTitle(won: List<BetTicket>): String {
    val sports = won.flatMap { it.picks }.map { it.sport }.distinct()
    val label = when {
        sports.size != 1 -> null
        sports[0] == "BASKETBALL" -> "竞彩篮球"
        sports[0].isBlank() -> null
        else -> "竞彩足球"
    }
    return when {
        label == null && won.size == 1 -> "恭喜您投注的注单中"
        label == null -> "恭喜您投注的 ${won.size} 注中"
        won.size == 1 -> "恭喜您投注的${label}喜中"
        else -> "恭喜您投注的 ${won.size} 注${label}喜中"
    }
}

/**
 * 遮罩：**半透明黑**（60%）。
 *
 * 🔴 用户口径三次变更的最终结论：不要实心卡片，也不要淡白纱 ——
 * 「弹窗本身没背景，整体 modal 背景是浅黑色」。60% 时下层页面隐约可见但完全不抢戏。
 */
private val SCRIM = Color(0x99000000)

/** 放射光金：偏橙一点，压在黑遮罩上才有「炸开」的温度。 */
private val GOLD = Color(0xFFFFC733)

/** 「立即查看」按钮红：参照图就是这种高饱和正红。 */
private val VIEW_RED = Color(0xFFE8231A)

/** 关闭按钮红：比按钮再深一点，不跟按钮抢视线。 */
private val CLOSE_RED = Color(0xFFD8241C)
