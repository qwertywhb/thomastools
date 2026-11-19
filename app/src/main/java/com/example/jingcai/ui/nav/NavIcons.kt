package com.example.jingcai.ui.nav

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 底部导航的 5 个 tab 图标（Material Icons 原始 path，24dp 网格，零依赖）。
 *
 * ## 为什么每个图标都有两版
 * 选中切**实心**、未选中切**描边** —— 这是 Material 3 导航栏的标准语义，也是主流 App
 * （抖音 / 微博 / 懂球帝）的做法：换形比只换色更容易一眼看出「现在在哪一页」，
 * 红白配色下尤其明显（灰 → 红 的纯色差在强光下会糊）。
 *
 * ## 足球的特殊处理
 * Material 官方 `sports_soccer` 只有线稿版（filled 与 outlined 是同一条 path）。
 * 这里用**填充规则**造出实心版：同一条 path 走 [PathFillType.EvenOdd] ⇒ 外圆被填满、
 * 内部纹路（五边形/缝线）变挖空 ⇒ 视觉上就是「实心足球」，与线稿版形成明确形变。
 *
 * ## 选型时踩过的两个坑
 * 1. `live_tv` 的 filled 版**本身就是粗边框**（内框是反向子路径挖空），选中和未选中几乎一样，
 *    所以「比分直播」改用 `smart_display`（实心屏 + 挖空三角 / 线框屏 + 实心三角）。
 * 2. `casino`（骰子）在 24dp 下线框只剩一个空方框、点看不清，改用 `confirmation_number`（票券）。
 *
 * ## 为什么不用 `material-icons-extended`
 * 那个包把上千个图标拆成独立类，只为了 5 个图标要往包里塞十几 MB
 * （本项目 release 甚至没开 minify）。这里只保留 10 条 path 字符串，对包体量几乎零影响。
 *
 * ## path 来源
 * `@material-design-icons/svg` 的 `filled/` 与 `outlined/` 同名图标，直接照抄 `d`，
 * 不做任何手工改动 —— 手改过的图标在小尺寸下比例会歪。
 */
object NavIcons {

    private data class Path(val d: String, val fillType: PathFillType)

    private fun build(name: String, paths: List<Path>): ImageVector {
        val b = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        )
        paths.forEach { p ->
            b.addPath(
                pathData = PathParser().parsePathString(p.d).toNodes(),
                pathFillType = p.fillType,
                fill = SolidColor(Color.Black)   // 实际颜色由 Icon(tint = ...) 覆盖
            )
        }
        return b.build()
    }

    private val cache = HashMap<String, ImageVector>()

    @Synchronized
    private fun icon(name: String, paths: List<Path>): ImageVector =
        cache.getOrPut(name) { build(name, paths) }

    /** 比赛（今日赛事 / 胜平负卡片）：足球 —— 选中用 EvenOdd 实心填充，未选中线稿 */
    val MatchOn: ImageVector by lazy { icon("MatchOn", listOf(
            Path("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 3.3 1.35-.95a8.01 8.01 0 0 1 4.38 3.34l-.39 1.34-1.35.46L13 6.7V5.3zm-3.35-.95L11 5.3v1.4L7.01 9.49l-1.35-.46-.39-1.34a8.103 8.103 0 0 1 4.38-3.34zM7.08 17.11l-1.14.1A7.938 7.938 0 0 1 4 12c0-.12.01-.23.02-.35l1-.73 1.38.48 1.46 4.34-.78 1.37zm7.42 2.48c-.79.26-1.63.41-2.5.41s-1.71-.15-2.5-.41l-.69-1.49.64-1.1h5.11l.64 1.11-.7 1.48zM14.27 15H9.73l-1.35-4.02L12 8.44l3.63 2.54L14.27 15zm3.79 2.21-1.14-.1-.79-1.37 1.46-4.34 1.39-.47 1 .73c.01.11.02.22.02.34 0 1.99-.73 3.81-1.94 5.21z", PathFillType.EvenOdd)
        )) }
    val MatchOff: ImageVector by lazy { icon("MatchOff", listOf(
            Path("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 3.3 1.35-.95a8.01 8.01 0 0 1 4.38 3.34l-.39 1.34-1.35.46L13 6.7V5.3zm-3.35-.95L11 5.3v1.4L7.01 9.49l-1.35-.46-.39-1.34a8.103 8.103 0 0 1 4.38-3.34zM7.08 17.11l-1.14.1A7.938 7.938 0 0 1 4 12c0-.12.01-.23.02-.35l1-.73 1.38.48 1.46 4.34-.78 1.37zm7.42 2.48c-.79.26-1.63.41-2.5.41s-1.71-.15-2.5-.41l-.69-1.49.64-1.1h5.11l.64 1.11-.7 1.48zM14.27 15H9.73l-1.35-4.02L12 8.44l3.63 2.54L14.27 15zm3.79 2.21-1.14-.1-.79-1.37 1.46-4.34 1.39-.47 1 .73c.01.11.02.22.02.34 0 1.99-.73 3.81-1.94 5.21z", PathFillType.NonZero)
        )) }

    /** 竞彩赛程：日历 */
    val ScheduleOn: ImageVector by lazy { icon("ScheduleOn", listOf(
            Path("M19 4h-1V2h-2v2H8V2H6v2H5c-1.11 0-1.99.9-1.99 2L3 20a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 16H5V10h14v10zM9 14H7v-2h2v2zm4 0h-2v-2h2v2zm4 0h-2v-2h2v2zm-8 4H7v-2h2v2zm4 0h-2v-2h2v2zm4 0h-2v-2h2v2z", PathFillType.NonZero)
        )) }
    val ScheduleOff: ImageVector by lazy { icon("ScheduleOff", listOf(
            Path("M19 4h-1V2h-2v2H8V2H6v2H5c-1.11 0-1.99.9-1.99 2L3 20a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 16H5V10h14v10zm0-12H5V6h14v2zM9 14H7v-2h2v2zm4 0h-2v-2h2v2zm4 0h-2v-2h2v2zm-8 4H7v-2h2v2zm4 0h-2v-2h2v2zm4 0h-2v-2h2v2z", PathFillType.NonZero)
        )) }

    /** 比分直播：播放屏（`live_tv` 的 filled 版本身就是粗边框，选中看不出形变，已换成 smart_display） */
    val LiveOn: ImageVector by lazy { icon("LiveOn", listOf(
            Path("M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM9.5 16.5v-9l7 4.5-7 4.5z", PathFillType.NonZero)
        )) }
    val LiveOff: ImageVector by lazy { icon("LiveOff", listOf(
            Path("M9.5 7.5v9l7-4.5z", PathFillType.NonZero),
            Path("M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14.01H4V5.99h16v12.02z", PathFillType.NonZero)
        )) }

    /** 模拟投注：票券（骰子 24dp 下线框糊成一格空框，已弃用） */
    val BetOn: ImageVector by lazy { icon("BetOn", listOf(
            Path("M22 10V6a2 2 0 0 0-2-2H4c-1.1 0-1.99.89-1.99 2v4c1.1 0 1.99.9 1.99 2s-.89 2-2 2v4c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2v-4c-1.1 0-2-.9-2-2s.9-2 2-2zm-9 7.5h-2v-2h2v2zm0-4.5h-2v-2h2v2zm0-4.5h-2v-2h2v2z", PathFillType.NonZero)
        )) }
    val BetOff: ImageVector by lazy { icon("BetOff", listOf(
            Path("M22 10V6a2 2 0 0 0-2-2H4c-1.1 0-1.99.89-1.99 2v4c1.1 0 1.99.9 1.99 2s-.89 2-2 2v4c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2v-4c-1.1 0-2-.9-2-2s.9-2 2-2zm-2-1.46c-1.19.69-2 1.99-2 3.46s.81 2.77 2 3.46V18H4v-2.54c1.19-.69 2-1.99 2-3.46 0-1.48-.8-2.77-1.99-3.46L4 6h16v2.54zM11 15h2v2h-2zm0-4h2v2h-2zm0-4h2v2h-2z", PathFillType.NonZero)
        )) }

    /** 资料（联赛 / 榜单）：排行榜 */
    val InfoOn: ImageVector by lazy { icon("InfoOn", listOf(
            Path("M7.5 21H2V9h5.5v12zm7.25-18h-5.5v18h5.5V3zM22 11h-5.5v10H22V11z", PathFillType.NonZero)
        )) }
    val InfoOff: ImageVector by lazy { icon("InfoOff", listOf(
            Path("M16 11V3H8v6H2v12h20V11h-6zm-6-6h4v14h-4V5zm-6 6h4v8H4v-8zm16 8h-4v-6h4v6z", PathFillType.NonZero)
        )) }
}
