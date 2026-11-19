package com.example.jingcai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 全局配色（第三代：支持深色）。
 *
 * ## 沿用第二代结论（别回退）
 * 浅色下三层要拉开：页面底 `#F5ECEC` → 卡片纯白 → 分隔线 `#E5D3D3`；
 * 文字 `#1C1416` / `#5E5254`（白底对比度 ≈5.4:1，过 WCAG AA）。
 * 语义色（涨 [Up] 红 / 跌 [Down] 绿 / 让球 [Flat] 橙）保持第二代加深后的值。
 *
 * ## 第三代改了什么
 * 中性色（底 / 卡 / 分隔线 / 文字）原来是 **顶层 `val`**，值在类加载时就固化了，
 * 于是 `isSystemInDarkTheme()` 完全无从下手 —— 深色模式下卡片还是白的。
 * 现在改成 **组合局部（[LocalNeutral]）驱动的 `@Composable` 属性**：
 * 调用点写法一个字都不用改（还是 `Surface` / `TextMain`），但取到的值随明暗切换。
 *
 * 🔴 代价：这些名字**只能在 `@Composable` 作用域里读**。顶层 `private val X = TextMain`
 *    这种写法会直接编译失败（这是好事：编译期就把「不跟随主题」的位置暴露出来，
 *    而不是等到深色模式上线后发现某块还是白的）。
 *
 * ## 品牌色为什么不跟着变
 * [Primary] `#C62828` 是「正红」，两侧都用它做**填充**（白字压在红上）。
 * 深色下把它调亮会让大面积色块发粉、和白色文字对比反而下降。
 * 真正需要在深底上可读的是「红字/红图标」，那类用法走 [Up]（深色下会调亮）。
 */

// ===== 品牌色（固定，不随明暗变）=====
val Primary = Color(0xFFC62828)       // 主色：正红
val PrimaryDark = Color(0xFF8E1B1B)   // 渐变深端：暗红
val Secondary = Color(0xFFE53935)

/** 胜方高亮蓝（近期状态表「客胜」侧）：与 okooo 网页 bluetxt 同语义。 */
val WinBlue = Color(0xFFC62828)

/** 强调绿：用于「已选中」这类需要一眼看见的状态。 */
val AccentGreen = Color(0xFFC62828)

/**
 * 中性色板：一套浅色、一套深色。
 *
 * 只放「随明暗切换」的颜色。品牌红 / 联赛源色这些有固定语义的不进来。
 */
data class NeutralPalette(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val surfaceTint: Color,
    val line: Color,
    val lineSoft: Color,
    val navIndicator: Color,
    val textMain: Color,
    val textSub: Color,
    val up: Color,
    val down: Color,
    val flat: Color,
    val singleAccent: Color,
    /** 「负」用的语义蓝（数据分析页队名赛果色）：与涨/跌的红绿分开，避免「负」被读成「跌停」。 */
    val loseBlue: Color
)

private val LightNeutral = NeutralPalette(
    background = Color(0xFFF5ECEC),    // 页面底：淡米红灰，让白卡浮起来
    surface = Color(0xFFFFFFFF),       // 卡片 / 行底
    surfaceAlt = Color(0xFFF8EFEF),    // 表头、隔行、块内浅底
    surfaceTint = Color(0xFFF0E0E0),   // 分段标题、选中底、更强一档的浅底
    line = Color(0xFFE5D3D3),          // 分隔线（表格骨架）
    lineSoft = Color(0xFFF1E5E5),      // 更轻的分隔线（卡内）
    navIndicator = Color(0xFFFBE0E0),
    textMain = Color(0xFF1C1416),
    textSub = Color(0xFF5E5254),
    up = Color(0xFFE02837),
    down = Color(0xFF0E8A55),
    flat = Color(0xFFC97A0B),
    singleAccent = Color(0xFFF0463F),
    loseBlue = Color(0xFF1565C0)
)

/**
 * 深色板。
 *
 * 不是把浅色直接反相 —— 页面底用**带红调的近黑**（#121011）而不是纯黑：
 * 纯黑会让红色卡片边缘出现硬边（OLED 上尤其明显），带一点红调能保持「这是同一个 App」。
 * 卡片 / 表头 / 分隔线按 4 档拉开（#1C1919 → #272222 → #3A3232），深色下才看得出表格骨架。
 *
 * 红绿橙三个语义色都调亮一档：浅色的 `#E02837` 在 #1C1919 上对比度只有 ≈3.2:1，
 * 比分/涨跌这种小字号会糊成一片。
 */
private val DarkNeutral = NeutralPalette(
    background = Color(0xFF121011),
    surface = Color(0xFF1C1919),
    surfaceAlt = Color(0xFF272222),
    surfaceTint = Color(0xFF332B2B),
    line = Color(0xFF3A3232),
    lineSoft = Color(0xFF2A2424),
    navIndicator = Color(0xFF3A2222),
    textMain = Color(0xFFF3EDED),
    textSub = Color(0xFFA99C9C),
    up = Color(0xFFFF6B6B),
    down = Color(0xFF4CD787),
    flat = Color(0xFFF0A93B),
    singleAccent = Color(0xFFFF6B6B),
    loseBlue = Color(0xFF6EA8FF)
)

private val LocalNeutral = staticCompositionLocalOf { LightNeutral }

// ===== 中性色（随明暗切换）=====
// 调用点写法与第二代完全一致（`Surface` / `TextMain` …），只是变成 @Composable 取值。
val Background: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.background
val Surface: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.surface
val SurfaceAlt: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.surfaceAlt
val SurfaceTint: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.surfaceTint
val Line: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.line
val LineSoft: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.lineSoft
val NavIndicator: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.navIndicator
val TextMain: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.textMain
val TextSub: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.textSub

/** 涨（红）——深色下调亮一档。 */
val Up: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.up

/** 跌（绿）——深色下调亮一档。 */
val Down: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.down

/** 让球（橙）——深色下调亮一档。 */
val Flat: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.flat

/** 单关边框：比 [Up] 稍亮一档。 */
val SingleAccent: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.singleAccent

/** 赛果「负」的蓝（深色下调亮一档，否则在 #1C1919 上发黑）。 */
val LoseBlue: Color
    @Composable @ReadOnlyComposable get() = LocalNeutral.current.loseBlue

private fun lightScheme(p: NeutralPalette) = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Secondary,
    onSecondary = Color.White,
    background = p.background,
    onBackground = p.textMain,
    surface = p.surface,
    onSurface = p.textMain,
    surfaceVariant = p.surfaceAlt,
    onSurfaceVariant = p.textSub,
    error = p.up
)

private fun darkScheme(p: NeutralPalette) = darkColorScheme(
    primary = Color(0xFFFF6B6B),
    onPrimary = Color(0xFF2A0A0A),
    secondary = Color(0xFFEF5350),
    onSecondary = Color(0xFF2A0A0A),
    background = p.background,
    onBackground = p.textMain,
    surface = p.surface,
    onSurface = p.textMain,
    surfaceVariant = p.surfaceAlt,
    onSurfaceVariant = p.textSub,
    error = p.up
)

/**
 * 全局主题。
 *
 * @param dark 是否深色。默认跟随系统；调试口 `-e demo_dark 1|0` 可强制（见 MainActivity）。
 */
@Composable
fun JingCaiTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (dark) DarkNeutral else LightNeutral
    CompositionLocalProvider(LocalNeutral provides palette) {
        MaterialTheme(
            // Material3 那一套（Card / Dialog / OutlinedButton 的默认色）也要跟着切，
            // 否则自绘的卡片变深了、系统组件还亮着，同一个页面出现两种明度。
            colorScheme = if (dark) darkScheme(palette) else lightScheme(palette),
            content = content
        )
    }
}
