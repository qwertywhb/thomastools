package com.example.jingcai.ui.update

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.jingcai.data.update.UpdateManager
import com.example.jingcai.data.update.UpdateManager.Phase
import kotlinx.coroutines.delay

/**
 * 更新弹窗宿主：挂在 [com.example.jingcai.MainActivity] 的 AppNav 根部，全 App 只有一份。
 *
 * 职责有三件：
 * 1. **冷启动静默检查**（延后 3 秒，避开首页首屏的比分请求抢带宽）；
 * 2. 把 [UpdateManager.phase] 渲染成对话框；
 * 3. 消费 [UpdateManager.hint] 弹 Toast（只在手动检查时有话可说）。
 *
 * 🔴 无更新 / 检查失败时**不渲染任何节点**：用户明确要求「访问不通就不提示更新」，
 *    所以这里绝不能出现「检查更新失败」之类的常驻提示。
 *
 * ## 视觉口径（2026-11-09 重做）
 * 从「Material AlertDialog + 三段文字」改成**深色记分牌**：红色渐变头版 + 大字版本号 +
 * 一道斜条纹（球衣/跑道质感），正文只留「更新内容」，底部一枚主按钮。
 *
 * 🔴 **不写任何解释性文字**。用户原话：「不要显示多余文字，比如下载完成后会打开系统安装界面，
 *    覆盖安装即可，本地数据不会丢」。因此这里删掉了：
 *    - 「下载完成后会打开系统安装界面…」整句
 *    - 「如果提示不允许安装，按引导打开『允许来自此来源的应用』…」整句
 *    - 「安装包已下载完成」这类重复标题
 *    这些都是**一次性会说清、之后就是噪音**的内容。真有权限问题时系统自己会弹引导页，
 *    比我们在 App 里预先解释更准。
 */
@Composable
fun UpdateHost() {
    val ctx = LocalContext.current
    val phase by UpdateManager.phase.collectAsState()
    val hint by UpdateManager.hint.collectAsState()

    // 冷启动静默检查：没有配置更新源时 UpdateManager 内部直接 return，零请求
    LaunchedEffect(Unit) {
        delay(3_000)
        UpdateManager.check(ctx, manual = false)
    }

    LaunchedEffect(hint) {
        hint?.let {
            runCatching { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
            UpdateManager.clearHint()
        }
    }

    when (val p = phase) {
        is Phase.Available -> {
            val remote = p.info.versionName.ifBlank { p.info.versionCode.toString() }
            val localName = UpdateManager.versionName(ctx)
            AvailableCard(
                // 同版本号重打包：不能写「发现新版本 v1.11 / 当前 v1.11」，否则用户以为是 bug
                versionLabel = "V$remote",
                tag = if (p.info.sameVersion) "新安装包" else "新版本",
                currentLabel = if (p.info.sameVersion) "本机 v$localName · 版本号相同"
                else "本机 v$localName",
                changelog = p.info.changelog,
                sizeText = p.info.sizeText,
                force = p.info.force,
                onConfirm = { UpdateManager.download(ctx, p.info) },
                // 点「稍后」不记忆：下次启动 App 仍会提示（用户明确要「本次忽略、下次还提示」）
                onDismiss = { UpdateManager.dismiss() }
            )
        }

        is Phase.Downloading -> ProgressCard(
            percent = p.percent,
            versionLabel = "V" + p.info.versionName.ifBlank { p.info.versionCode.toString() },
            sizeText = p.info.sizeText
        )

        is Phase.Ready -> ReadyCard(
            versionLabel = "V" + p.info.versionName.ifBlank { p.info.versionCode.toString() },
            onInstall = { UpdateManager.install(ctx, p.file) },
            onDismiss = { UpdateManager.dismiss() }
        )

        is Phase.Failed -> FailedCard(
            message = p.message,
            onDismiss = { UpdateManager.dismiss() }
        )

        else -> Unit
    }
}

// ============================== 四个状态 ==============================

@Composable
private fun AvailableCard(
    versionLabel: String,
    tag: String,
    currentLabel: String,
    changelog: String,
    sizeText: String,
    force: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    DarkDialog(onDismissRequest = { if (!force) onDismiss() }) {
        ScoreHeader(
            tag = tag,
            versionLabel = versionLabel,
            subLabel = currentLabel,
            sizeText = sizeText
        )
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
            if (changelog.isNotBlank()) {
                SectionLabel("更新内容")
                Spacer(Modifier.height(10.dp))
                // 更新说明可能很长（多行列表），限高滚动，别把弹窗撑出屏幕
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        changelog.trim(),
                        fontSize = 13.sp, color = OnDark, lineHeight = 20.sp
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!force) {
                    GhostButton("稍后", Modifier.width(96.dp), onDismiss)
                    Spacer(Modifier.width(10.dp))
                }
                PrimaryButton("立即更新", Modifier.weight(1f), onConfirm)
            }
        }
    }
}

@Composable
private fun ProgressCard(percent: Int, versionLabel: String, sizeText: String) {
    // 下载中不允许关闭，避免半截文件
    DarkDialog(onDismissRequest = {}) {
        ScoreHeader(
            tag = "下载中",
            versionLabel = "$percent%",
            subLabel = versionLabel,
            sizeText = sizeText,
            accent = true
        )
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 20.dp)) {
            // 自绘进度条：Material 的 LinearProgressIndicator 带内边距和默认轨道色，
            // 在深色卡上会露出一圈浅灰，这里自己画一条干净的。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(TrackBg)
            ) {
                if (percent > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth(percent / 100f)
                            .fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(AccentRed, BrandRed)))
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyCard(versionLabel: String, onInstall: () -> Unit, onDismiss: () -> Unit) {
    DarkDialog(onDismissRequest = onDismiss) {
        ScoreHeader(
            tag = "可安装",
            versionLabel = versionLabel,
            subLabel = "下载完成",
            sizeText = "",
            accent = true
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostButton("稍后", Modifier.width(96.dp), onDismiss)
                Spacer(Modifier.width(10.dp))
                PrimaryButton("立即安装", Modifier.weight(1f), onInstall)
            }
        }
    }
}

@Composable
private fun FailedCard(message: String, onDismiss: () -> Unit) {
    DarkDialog(onDismissRequest = onDismiss) {
        // 🔴 失败**不用红色头版**：红色在本 App 是主色（更新按钮、进行中），
        //    拿它当「失败」会让人误以为是个正常操作界面。这里换成石墨色，
        //    红色只留在「更新失败」这枚小标签上做信号。
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color(0xFF2C313C), Color(0xFF1A1D24))))
        ) {
            Canvas(Modifier.matchParentSize()) {
                val step = 30.dp.toPx()
                val stroke = 3.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.018f),
                        start = Offset(x, size.height),
                        end = Offset(x + size.height, 0f),
                        strokeWidth = stroke
                    )
                    x += step
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 18.dp, top = 14.dp, bottom = 18.dp)
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFD93A3A))
                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                ) {
                    Text(
                        "更新失败", color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(message, fontSize = 15.sp, color = OnDark, lineHeight = 22.sp)
            }
        }
        // 石墨底的头部与按钮区明度差太小，加一条 1dp 分隔线把两段切开
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262A33)))
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text("知道了", color = OnDark, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ============================== 骨架与零件 ==============================

/**
 * 统一外壳：深色圆角卡。
 *
 * `usePlatformDefaultWidth = false` 才能让卡片自己决定宽度（默认会被压到屏宽的 ~90% 且带系统内边距，
 * 深色卡贴边会显得脏）。
 */
@Composable
private fun DarkDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(CardBg)
        ) { content() }
    }
}

/**
 * 头版：红色渐变 + 斜条纹 + 大字版本号（记分牌质感）。
 *
 * 版本号用 40sp / Black，是这个弹窗唯一「大」的东西 —— 用户扫一眼就知道升到哪个版本，
 * 不用读句子。[accent] 为 true 时数字换成荧光红（下载中显示百分比、可安装显示版本号）。
 */
@Composable
private fun ScoreHeader(
    tag: String,
    versionLabel: String,
    subLabel: String,
    sizeText: String,
    accent: Boolean = false
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(BrandRed, BrandDeep)))
    ) {
        // 斜纹：白色 2.5% 透明度、3dp 宽、间距 30dp。
        // 🔴 第一版用的 5.5% / 6dp / 17dp 在深红底上成了「红斑马」，抢掉了版本号的注意力；
        //    纹理的作用只是让平涂的色块不那么死，越淡越好。
        Canvas(Modifier.matchParentSize()) {
            val step = 30.dp.toPx()
            val stroke = 3.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    color = Color.White.copy(alpha = 0.025f),
                    start = Offset(x, size.height),
                    end = Offset(x + size.height, 0f),
                    strokeWidth = stroke
                )
                x += step
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 18.dp, top = 14.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tag.isNotEmpty()) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.20f))
                            .padding(horizontal = 7.dp, vertical = 2.5.dp)
                    ) {
                        Text(
                            tag, color = Color.White, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (sizeText.isNotBlank()) {
                    Text(sizeText, color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                versionLabel,
                color = if (accent) Color(0xFFFFD9D9) else Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 40.sp
            )
            if (subLabel.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subLabel,
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = OnDarkSub,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp
    )
}

@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFFFF4A4A), BrandRed)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, OutlineColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = OnDarkSub, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

// 深色卡配色（不跟全局浅色主题联动：这一层刻意反色，才有「转播弹窗」的分量）
private val CardBg = Color(0xFF14161C)
private val BrandRed = Color(0xFFC62828)
private val BrandDeep = Color(0xFF781111)
private val AccentRed = Color(0xFFFF5252)
private val OnDark = Color(0xFFEFF1F5)
private val OnDarkSub = Color(0xFF8B90A0)
private val OutlineColor = Color(0xFF333844)
private val TrackBg = Color(0xFF2A2E38)
