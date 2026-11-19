package com.example.jingcai.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * 联赛底色的**兜底色板** + 通用取色工具。
 *
 * ⚠️ 首选永远是**源站颜色**：okooo 的 `a.saiming` 上直接写着
 * `style="background-color:#009900"`（每个联赛固定一种），解析器已抓进 `Match.color`。
 * 用户明确否掉过「自己编一套配色」的做法 —— 自造色板和源站对不上，反而让人认不出联赛
 * （截图里源站是 澳超深棕 #3F1C10 / 德乙品红 #DB31EE / 荷甲粉 #FF6699 / 德甲紫 #990099…）。
 *
 * 本文件的用途只剩两件事：
 *  1. [parseHexColorOrNull]：把源站 hex 解析成 [Color]（解析失败返回 null，绝不崩）；
 *  2. [solid]：源站**没给颜色**时（字段缺失、冷门联赛、以后换数据源）按联赛名做稳定哈希
 *     从 24 色深色板里挑一个 —— 至少保证「同名恒同色」，不会一屏全是同一种灰。
 *
 * [textOn]：按底色亮度选白字/黑字。#FF6699 这类中等亮度用白字（与源站一致），
 * 亮度 > 0.62 的极浅底色（如淡黄）自动换深色字，避免白字看不见。
 */
object LeagueColors {

    /** 24 色兜底调色板：深色高饱和，白字可读。 */
    private val PALETTE: List<Color> = listOf(
        Color(0xFFA61B3C), Color(0xFF6C4CE0), Color(0xFF8E44AD), Color(0xFFC2306B),
        Color(0xFFE02837), Color(0xFFD2551F), Color(0xFFB26A05), Color(0xFF7E7308),
        Color(0xFF5E8C1B), Color(0xFF1B8F5A), Color(0xFF0E8C8C), Color(0xFF17696F),
        Color(0xFF8E3B1F), Color(0xFF6B2140), Color(0xFF5A4A44), Color(0xFF8C4A2F),
        Color(0xFFB03A2E), Color(0xFF7D3CB5), Color(0xFF2E7D32), Color(0xFF00695C),
        Color(0xFF3B2F2C), Color(0xFFAD1457), Color(0xFF4527A0), Color(0xFF7A4E2A)
    )

    /** 源站没给颜色时的兜底：按联赛名稳定哈希取色（同名恒同色）。 */
    fun solid(league: String): Color {
        val name = league.trim()
        if (name.isEmpty()) return PALETTE[20]
        // 高位参与取模：中文名差异集中在低位，直接 `h % 24` 会让「XX甲 / XX乙」频繁撞色。
        return PALETTE[(hash(name) shr 11) % PALETTE.size]
    }

    /** 胶囊渐变的亮端：同色系 +22% 白，保留「是这个联赛的颜色」的辨识度。 */
    fun soft(color: Color, fraction: Float = 0.22f): Color = lerp(Color.White, color, fraction)

    /** 底色上的文字色：极浅底用深字，其余用白字（与源站观感一致）。 */
    fun textOn(bg: Color): Color =
        if (bg.luminance() > 0.62f) Color(0xFF1A1A1A) else Color.White

    private fun hash(name: String): Int {
        var h = 0
        for (c in name) h = (h * 31 + c.code) and 0x7FFFFFFF
        return h
    }
}

/**
 * 解析 `#RRGGBB` / `RRGGBB`，非法值返回 null。
 *
 * 与 `ScheduleScreen` / `LiveScreen` 里的同名私有函数不同：那两个是「解析失败退回某个默认色」，
 * 这里刻意返回 null，好让调用方区分「没有颜色」与「黑色」。
 */
fun parseHexColorOrNull(hex: String): Color? {
    val h = hex.trim().removePrefix("#")
    if (h.length != 6) return null
    val rgb = h.toLongOrNull(16) ?: return null
    val r = ((rgb shr 16) and 0xFF).toInt()
    val g = ((rgb shr 8) and 0xFF).toInt()
    val b = (rgb and 0xFF).toInt()
    return Color(0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
}
