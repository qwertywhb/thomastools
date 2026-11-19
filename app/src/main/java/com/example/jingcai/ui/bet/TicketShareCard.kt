package com.example.jingcai.ui.bet

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.model.TicketStatus
import com.example.jingcai.data.settle.Parlay
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 注单**图片**分享（微信 / 朋友圈要的是图，不是文字）。
 *
 * ## 为什么不用 Compose 截图
 * `graphicsLayer.toImageBitmap()` 只能截「已经渲染在屏幕上的组合」，本页必须先把卡片
 * 真正显示出来才能截 —— 那会逼着我们为了分享去改页面布局（账单里的卡片是压缩版，
 * 分享图要的是完整信息）。这里改成**用原生 Canvas 直接画一张**：
 * 尺寸、留白、字号全部自己定，和页面布局彻底解耦，也顺便不受深色模式影响
 * （分享出去的图永远是浅色版，深色底在别人的微信里看着像截图失败）。
 *
 * ## 版式（1080px 宽，微信消息与朋友圈都能直接看清）
 * ```
 * 竞彩投注单            11-10 14:38
 * 单关 · 2 倍 · 共 1 注
 * ─────────────────────────
 * 周一001  英超
 * 曼城 vs 阿森纳             2:1
 * 胜平负  主胜 1.85           中
 * ─────────────────────────
 * 本金 ¥4.00        中奖 ¥7.40
 * 仅供娱乐 · 模拟投注，不涉及真实资金
 * ```
 *
 * 🔴 底部那行免责**必须留**：这张图会被转发到群里，脱离 App 的上下文后
 *    「竞彩」「¥7.40」很容易被当成真实投注记录。
 */
object TicketShareCard {

    /** 画布宽度。1080 是微信竖屏图片的「不缩放」阈值，再大只是徒增体积。 */
    private const val W = 1080
    private const val PAD = 56f

    /** 卡片左右内边距。 */
    private const val INNER = 44f

    // 品牌色与中性色（写死浅色：分享图不跟随深色模式，理由见类注释）
    private const val C_BG = 0xFFF2F4F7.toInt()
    private const val C_CARD = 0xFFFFFFFF.toInt()
    private const val C_TEXT = 0xFF1A1A1A.toInt()
    private const val C_SUB = 0xFF8A94A6.toInt()
    private const val C_LINE = 0xFFEDEFF3.toInt()
    private const val C_RED = 0xFFC62828.toInt()

    private val DATE_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    /**
     * 把注单画成 PNG 并交给系统分享。
     *
     * @return 是否成功发起分享。失败时不抛异常（分享失败不该把账单页带崩）。
     */
    fun share(ctx: Context, ticket: BetTicket): Boolean = runCatching {
        val file = render(ctx, ticket)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // 文字版同时带上：部分 App（如短信、备忘录）只收文字，图片会被丢掉
            putExtra(Intent.EXTRA_TEXT, formatTicketForShare(ticket))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(intent, "分享注单").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    /** 只画图不分享（调试口 / 自检用）。 */
    fun render(ctx: Context, ticket: BetTicket): File {
        val bmp = draw(ticket)
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        // 固定文件名：同一张单重复分享会覆盖，不会把 cacheDir 攒满
        val f = File(dir, "ticket_${ticket.id.take(12)}.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    // ============================== 绘制 ==============================

    fun draw(ticket: BetTicket): Bitmap {
        val groups = ticket.picks.groupBy { it.matchId }.values.toList()
        val headH = 168f
        val rowH = 158f
        val footH = 190f
        val cardH = headH + rowH * groups.size + footH
        val h = (PAD * 2 + cardH).toInt().coerceAtLeast(600)

        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(C_BG)

        val card = RectF(PAD, PAD, W - PAD, PAD + cardH)
        c.drawRoundRect(card, 30f, 30f, fill(C_CARD))

        val left = PAD + INNER
        val right = W - PAD - INNER
        var y = PAD

        // ── 头部 ──
        y += 52f
        c.drawRect(left, y - 30f, left + 7f, y + 14f, fill(C_RED))
        text(c, "竞彩投注单", left + 22f, y, 40f, C_TEXT, bold = true)
        textRight(c, DATE_FMT.format(Date(ticket.createdAt)), right, y, 26f, C_SUB)

        y += 52f
        val legs = Parlay.legsText(ticket.ms)
        text(
            c,
            "$legs · ${ticket.times} 倍 · 共 ${Parlay.betsOf(ticket)} 注",
            left, y, 28f, C_SUB
        )

        y += 34f
        c.drawRect(left, y, right, y + 2f, fill(C_LINE))

        // ── 每场一行 ──
        for (picks in groups) {
            val p0 = picks.first()
            y += 46f
            // 第一行：编号（红）+ 联赛（灰）
            if (p0.num.isNotEmpty()) {
                text(c, p0.num, left, y, 26f, C_RED, bold = true)
            }
            val numW = if (p0.num.isNotEmpty()) 110f else 0f
            text(c, p0.league, left + numW, y, 26f, C_SUB)

            // 右侧比分（全场比分在 "2:1|1:0" 的前半段）
            val scoreRaw = ticket.scores?.get(p0.matchId.toString()).orEmpty()
            val score = scoreRaw.substringBefore('|').trim()
            val mark = when (picks.firstOrNull()?.legSafe) {
                com.example.jingcai.data.model.LegResult.WIN -> "中" to C_RED
                com.example.jingcai.data.model.LegResult.LOSE -> "挂" to C_SUB
                com.example.jingcai.data.model.LegResult.VOID -> "退" to C_SUB
                else -> "" to C_SUB
            }
            if (mark.first.isNotEmpty()) {
                textRight(c, mark.first, right, y, 26f, mark.second, bold = true)
            }
            if (score.isNotEmpty()) {
                textRight(c, score, right - 52f, y, 26f, C_TEXT, bold = true)
            }

            // 第二行：对阵
            y += 46f
            val vs = "${p0.home} vs ${p0.away}"
            text(c, ellipsize(vs, 36f, bold = true, maxW = right - left - 80f), left, y, 36f, C_TEXT, bold = true)

            // 第三行：玩法（让分带主队视角的线）+ 选项 + 赔率（同场多玩法用「; 」分隔）
            y += 44f
            val pickLine = picks.groupBy { it.poolCode }.values.joinToString("; ") { same ->
                poolTitleOf(same.first()) + " " + same.joinToString("，") { it.pickText }
            }
            text(c, ellipsize(pickLine, 28f, maxW = right - left - 130f), left, y, 28f, C_SUB)
            textRight(c, "%.2f".format(picks.first().odds), right, y, 28f, C_SUB)

            y += 26f
            c.drawRect(left, y, right, y + 1.5f, fill(C_LINE))
        }

        // ── 底部：本金 / 派彩 ──
        y += 54f
        text(c, "本金", left, y, 26f, C_SUB)
        text(c, "¥%.2f".format(ticket.stake), left, y + 46f, 42f, C_TEXT, bold = true)
        val (label, amount, color) = when (ticket.status) {
            TicketStatus.WON -> Triple("中奖", "¥%.2f".format(ticket.payout), C_RED)
            TicketStatus.LOST -> Triple("未中奖", "—", C_SUB)
            TicketStatus.VOID -> Triple("已退款", "¥%.2f".format(ticket.payout), C_SUB)
            else -> Triple("待开奖", "—", C_SUB)
        }
        textRight(c, label, right, y, 26f, C_SUB)
        textRight(c, amount, right, y + 46f, 42f, color, bold = true)

        // ── 免责（见类注释，必须留）──
        y += 92f
        text(c, "仅供娱乐 · 模拟投注，不涉及真实资金", left, y, 24f, C_SUB)

        return bmp
    }

    // ============================== 画笔工具 ==============================

    private fun fill(color: Int): Paint = Paint().apply {
        this.color = color
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    /** 左对齐画一行字。基线即 [baseline]。 */
    private fun text(
        c: Canvas,
        s: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) {
        val p = Paint().apply {
            this.color = color
            textSize = size
            isAntiAlias = true
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        c.drawText(s, x, baseline, p)
    }

    /** 右对齐画一行字（右边界为 [x]）。 */
    private fun textRight(
        c: Canvas,
        s: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) {
        val p = Paint().apply {
            this.color = color
            textSize = size
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        c.drawText(s, x, baseline, p)
    }

    /**
     * 超宽就截断加省略号。
     *
     * 不能靠 Canvas 自己截（它没有 ellipsis）：先量宽，再按字符二分/递减去掉尾部。
     * 中英文混排时按字符数递减会偏慢，但一行最多几十个字，无所谓。
     */
    private fun ellipsize(s: String, size: Float, bold: Boolean = false, maxW: Float): String {
        val p = Paint().apply {
            textSize = size
            isAntiAlias = true
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && p.measureText(s.substring(0, end) + "…") > maxW) end--
        return s.substring(0, end) + "…"
    }
}
