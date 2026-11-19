package com.example.jingcai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Up
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * 把网络/解析层的原始异常翻成人话。
 *
 * 🔴 旧实现直接把 `e.message` 铺在页面上，用户看到的是 `HTTP 567`、
 *    `Failed to connect to /...`、`java.net.UnknownHostException` —— 既看不懂，
 *    也判断不出「该等一会还是该换网络」。这里按关键字收敛成四类可执行的提示。
 *
 * 为什么按关键字而不是异常类型：这些异常是跨 `catch (e: Exception)` 传上来的字符串，
 * 类型信息在路上已经丢了（OkHttp 的 `IOException` 子类、JSON 的 `JsonSyntaxException`
 * 都并成了同一条 `String?`）。在这里回到类型体系要改十几个调用点，不划算。
 */
fun friendlyError(raw: String?): String {
    if (raw.isNullOrBlank()) return "加载失败，请稍后重试"
    val s = raw.lowercase()
    return when {
        s.contains("http 5") || s.contains("http 4") -> "服务器暂时不可用，请稍后重试"
        s.contains("timeout") || s.contains("timed out") -> "网络超时，请检查网络后重试"
        s.contains("failed to connect") || s.contains("unable to resolve host") ||
            s.contains("network is unreachable") || s.contains("connectexception") ||
            s.contains("unknownhost") -> "网络不可用，请检查网络后重试"
        s.contains("ssl") || s.contains("certificate") -> "安全连接失败，请检查系统时间"
        s.contains("json") || s.contains("parse") || s.contains("syntax") -> "数据解析失败，请稍后重试"
        s.contains("socket") || s.contains("stream") -> "连接中断，请稍后重试"
        else -> "加载失败，请稍后重试"
    }
}

/**
 * 整页错误态。带「重试」按钮。
 *
 * 🔴 旧实现是一行裸灰字，**没有任何出口**：源站偶尔抽风时用户只能干等，
 *    或者靠切日期/切页签「蹭」一次重拉 —— 这在真机上很难被普通用户想到。
 */
@Composable
fun ErrorText(msg: String?, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(friendlyError(msg), color = TextSub, textAlign = TextAlign.Center, fontSize = 13.sp)
        if (onRetry != null) {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onRetry,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 6.dp),
                border = BorderStroke(1.dp, Up.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Up)
            ) {
                Text("重试", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * 顶部 2dp 细进度条：后台静默刷新时用。
 *
 * 与 [Loading] 的分工：**有内容时永远不用整页转圈**（那会把用户正在看的东西抹掉），
 * 只在顶上挂一条细条，内容压暗即可 —— 这也是主流 App 切列表/切日期时的做法。
 */
@Composable
fun ThinProgressBar(modifier: Modifier = Modifier, color: Color = Primary) {
    LinearProgressIndicator(
        modifier = modifier.fillMaxWidth().height(2.dp),
        color = color,
        trackColor = Color.Transparent
    )
}

@Composable
fun EmptyText(msg: String = "暂无数据") {
    Text(
        text = msg,
        color = TextSub,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
    )
}

/** 赔率显示：0 显示「未开售」。 */
fun formatOdds(v: Double): String = if (v > 0) String.format("%.2f", v) else "未开售"

/** 涨跌箭头。 */
fun arrow(f: Int): String = when {
    f > 0 -> "↑"
    f < 0 -> "↓"
    else -> ""
}

/**
 * 「点击某场比赛可查看直播详情」提示：红色小字，**每 10 秒跳一下**（缩放 1↔1.08 + 透明度 1↔0.55）。
 *
 * 🔴 不要用 `rememberInfiniteTransition`：那是一直在动，用户明确反馈「眼睛受不了」。
 *    这里改成 [Animatable] + 协程循环：静止 10 秒 → 连跳 2 下（约 0.9 秒）→ 再静止，
 *    既能把视线拉过去，又不会一直干扰阅读。
 *
 * 性能：动画值只在 [graphicsLayer] 的 lambda 里读（延迟读取），
 * 只触发重绘、不触发重组；否则常驻的提示会让整页每秒重组几十次。
 */
@Composable
fun LiveDetailHint(
    text: String = "点击某场比赛可查看直播详情",
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // 容器宽 / 文字自然宽：只有量到两个值才能算「滑到最右边要位移多少」。
    var boxW by remember { mutableIntStateOf(0) }
    var textW by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }

    // 滚动节奏：回到最左边 → 匀速滑到最右侧 → 停 5s → 再回最左边，如此循环。
    //  ⚠️ 位移用 graphicsLayer.translationX（deferred read），不走 recomposition，
    //     否则每帧重组一次文本，长列表页面会掉帧。
    LaunchedEffect(boxW, textW) {
        if (boxW <= 0 || textW <= 0) return@LaunchedEffect
        // 文字比容器窄（当前就是这种）⇒ 右移 (boxW-textW)，让它**右边缘贴齐**；
        // 文字比容器宽（换更长的文案时）⇒ 左移露出尾部，仍是「跑到最右侧」。
        val travel = if (textW <= boxW) (boxW - textW).toFloat() else -(textW - boxW).toFloat()
        if (travel == 0f) {
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        val distanceDp = abs(travel) / density.density
        val durationMs = (distanceDp / HINT_SPEED_DP_PER_SEC * 1000f).toInt().coerceIn(900, 8_000)
        while (true) {
            offset.snapTo(0f)                       // 从最左侧起跑
            delay(HINT_RESTART_MS)
            offset.animateTo(travel, tween(durationMs, easing = LinearEasing))
            delay(HINT_HOLD_MS)                     // 到最右侧停 5s
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            // 必须裁切：位移期间文字会短暂越出所在行
            .clipToBounds()
            .onGloballyPositioned { boxW = it.size.width },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            modifier = Modifier
                // unbounded 让 Text 按**自然宽度**测量（不被父级宽度截断），
                // 这样才能通过 onTextLayout 拿到真实宽度去算位移。
                .wrapContentWidth(unbounded = true)
                .graphicsLayer { translationX = offset.value },
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = Up,
            maxLines = 1,
            softWrap = false,
            onTextLayout = { textW = it.size.width }
        )
    }
}

/** 到达最右侧后的停留时间（用户明确要求 5 秒）。 */
private const val HINT_HOLD_MS = 5_000L
/** 回到最左侧后、起跑前的短暂停顿，让「从头开始」看得出来。 */
private const val HINT_RESTART_MS = 500L
/** 滚动速度（dp/秒）：恒定速度比固定时长自然，长文案也不会一闪而过。 */
private const val HINT_SPEED_DP_PER_SEC = 26f
