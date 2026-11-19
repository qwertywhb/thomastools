package com.example.jingcai.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.jingcai.data.model.EventKind
import com.example.jingcai.data.model.LineupMark
import com.example.jingcai.data.model.LineupPlayer
import com.example.jingcai.data.model.LiveMatch
import com.example.jingcai.data.model.MatchEvent
import com.example.jingcai.data.model.MatchInjuries
import com.example.jingcai.data.model.MatchLineup
import com.example.jingcai.data.model.PlayerMark
import com.example.jingcai.data.model.TechSection
import com.example.jingcai.data.model.TechStat
import com.example.jingcai.data.model.TeamLineup
import com.example.jingcai.data.network.TitanDetailParser
import com.example.jingcai.data.repository.LiveDetailRepository
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.SurfaceTint
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Flat
import com.example.jingcai.ui.theme.Line
import androidx.compose.runtime.ReadOnlyComposable

/**
 * 长按比分弹出「事件时间轴 + 技术统计」（对齐 live.titan007.com 的赛事详情）。
 *
 * 数据来源（[LiveDetailRepository]）：
 *  - **已结束**：详情页 `detail/<id>cn.htm` 的 `ul#teamEventDiv` 是服务端渲染好的，直接刮；
 *  - **进行中**：该页事件表还没渲染，退到 `detailin.js` 的 `d_f[<id>]`（结构化，字段更干净）；
 *  - **技术统计**：按状态分流（与官方 `MakeTeamTechHtml()` 一致）—— 完场取页面内联
 *    `techStatisticData`（带分节），进行中取 `detailin.js` 的 `tT_f[<id>]`（实时，字段全）。
 *
 * 之所以不直接复用动画详情页：动画详情页要内嵌 809×600 的 WebView，长按只是想「瞄一眼谁进的球」，
 * 拉整个动画页太重。
 */

private val S_TEXT = Color(0xFF333333)
private val S_SUB = Color(0xFF999999)
private val S_LINE: Color
    @Composable @ReadOnlyComposable get() = Line
private val S_HOME = Color(0xFFFF6A00)
private val S_AWAY = Color(0xFFC62828)
private val S_SCORE = Color(0xFFF04545)

@Composable
fun MatchEventSheet(m: LiveMatch, onClose: () -> Unit) {
    val repo = remember { LiveDetailRepository() }
    var events by remember { mutableStateOf<List<MatchEvent>?>(null) }
    var tech by remember { mutableStateOf<List<TechStat>>(emptyList()) }
    var techStale by remember { mutableStateOf(false) }
    var lineup by remember { mutableStateOf<MatchLineup?>(null) }
    /** 伤停名单：与首发阵容读**同一份**详情页 HTML，不额外发请求。 */
    var injuries by remember { mutableStateOf<MatchInjuries?>(null) }
    var lineupChecked by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(m.titanId) {
        val html = try {
            repo.fetchDetailPage(m.titanId)
        } catch (_: Exception) {
            ""
        }
        // detailin.js 惰性拉一次：进行中的**事件**与**技术统计**都在同一份文件里，没必要请求两次
        var detailIn = ""
        suspend fun detailInJs(): String {
            if (detailIn.isEmpty()) {
                detailIn = try {
                    repo.fetchDetailIn()
                } catch (_: Exception) {
                    ""
                }
            }
            return detailIn
        }

        // 技术统计：与官方 `MakeTeamTechHtml()` 同口径 —— 完场才用页面内联 `techStatisticData`，
        // 进行中一律用 `tT_f`。反着来会拿到「开赛初期」的陈旧快照
        // （实测 2910953 内联值是角球 1-0 / 射门 1-0 / 控球 22%:78%，而 tT_f 是 6-8 / 17-14 / 31%:69%）。
        tech = if (m.state == -1) {
            TitanDetailParser.parseTechSections(html)[TechSection.ALL]
                ?: TitanDetailParser.parseTech(detailInJs(), m.titanId)
        } else {
            val liveRows = TitanDetailParser.parseTech(detailInJs(), m.titanId)
            if (liveRows.isNotEmpty()) liveRows else {
                // 进行中却没拿到实时源（该场不在 tT_f 的 12~13 场窗口内）⇒ 退回内联快照，
                // 数据会明显滞后，必须在表头下角标出「暂无实时统计」
                techStale = true
                TitanDetailParser.parseTechSections(html)[TechSection.ALL] ?: emptyList()
            }
        }

        var ev = TitanDetailParser.parseEventsFromDetailHtml(html)
        if (ev.isEmpty()) {
            // 直播中：详情页事件表由 JS 渲染，服务端 HTML 里没有 → 走增量文件
            ev = try {
                TitanDetailParser.parseEventsFromDetailIn(detailInJs(), m.titanId)
            } catch (_: Exception) {
                emptyList()
            }
        }
        events = ev
        // 首发/替补：与事件、完场技术统计同一份详情页（服务端渲染），不额外发请求
        lineup = TitanDetailParser.parseLineup(html)
        injuries = TitanDetailParser.parseInjuries(html)
        lineupChecked = html.isNotEmpty()
        failed = html.isEmpty() && ev.isEmpty()
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 44.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
        ) {
            // 标题栏
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.width(38.dp).height(38.dp).clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", fontSize = 17.sp, color = S_SUB)
                }
                Text(
                    "比赛事件",
                    Modifier.weight(1f),
                    fontSize = 15.sp, color = S_TEXT, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                // 与左侧关闭键等宽的透明占位，保证标题真正居中
                Spacer(Modifier.width(38.dp))
            }
            HorizontalDivider(color = S_LINE)

            // 比分头
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // 排名跟着队名走（与列表一致）：`曼城 [1]`；源站没给排名就只有队名
                    if (m.homeRank.isEmpty()) m.home else "${m.home} [${m.homeRank}]",
                    Modifier.weight(1f), fontSize = 13.sp, color = S_TEXT,
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.End, maxLines = 2
                )
                Column(
                    Modifier.padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(m.score, fontSize = 20.sp, color = S_SCORE, fontWeight = FontWeight.Bold)
                    Text(
                        statusLabel(m.state),
                        fontSize = 10.sp,
                        color = if (m.state in 1..4) S_SCORE else S_SUB
                    )
                }
                Text(
                    if (m.awayRank.isEmpty()) m.away else "${m.away} [${m.awayRank}]",
                    Modifier.weight(1f), fontSize = 13.sp, color = S_TEXT,
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Start, maxLines = 2
                )
            }
            HorizontalDivider(color = S_LINE)

            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            ) {
                when {
                    events == null -> Box(
                        Modifier.fillMaxWidth().height(140.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(strokeWidth = 2.dp) }

                    events!!.isEmpty() -> Text(
                        if (failed) "事件加载失败，请稍后重试" else "暂无事件",
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        color = S_SUB, fontSize = 13.sp, textAlign = TextAlign.Center
                    )

                    else -> events!!.forEach { e -> EventRow(e) }
                }

                if (tech.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    SectionBar("技术统计")
                    if (techStale) {
                        Text(
                            "暂无实时统计，以下为最近快照",
                            Modifier.fillMaxWidth()
                                // 中性底 + 语义橙字：浅色 / 深色两套底都能读（原先写死浅橙底 + 深橙字）
                                .background(SurfaceAlt)
                                .padding(vertical = 3.dp),
                            color = Flat, fontSize = 10.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    tech.forEach { s -> SheetTechRow(s) }
                } else if (techStale) {
                    // 比赛在打但两源都没数据：说「暂无实时统计」，别说「没有技术统计」
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "暂无实时统计",
                        Modifier.fillMaxWidth()
                            .background(SurfaceAlt)
                            .padding(vertical = 6.dp),
                        color = Flat, fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }

                // 首发 / 替补（排在技术统计下面）：与比分头一致的左右分列 —— 主队左、客队右
                val lu = lineup
                if (lu != null) {
                    Spacer(Modifier.height(6.dp))
                    SectionBar("首发阵容")
                    LineupTeams(m, lu)
                    if (lu.home.subs.isNotEmpty() || lu.away.subs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        SectionBar("替补")
                        LineupColumns(lu.home.subs, lu.away.subs)
                    }
                } else if (lineupChecked && m.state != 0) {
                    // 已开赛/完场却刮不到名单：说清楚，免得以为「首发没做」
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "暂无首发阵容",
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        color = S_SUB, fontSize = 11.sp, textAlign = TextAlign.Center
                    )
                }
                // 伤停：**只在真有名额时出现**。源站这块多数场次写「暂无数据」，
                // 无条件画一个只有标题的空块会让用户以为没加载出来。
                injuries?.let { inj ->
                    Spacer(Modifier.height(6.dp))
                    SectionBar("伤停")
                    InjuryColumns(inj.home, inj.away)
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/**
 * 伤停两列（主队左 / 客队右，与首发阵容同一套左右分列）。
 *
 * 每行是源站给的**原始文本**（球员名可能带伤情说明），不做拆分：见 [MatchInjuries] 的说明。
 * 一侧没有就写「—」：留白会被读成「这块还没加载完」。
 */
@Composable
private fun InjuryColumns(home: List<String>, away: List<String>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            if (home.isEmpty()) {
                Text("—", fontSize = 11.sp, color = S_SUB)
            } else {
                home.forEach { line ->
                    Text(line, fontSize = 11.sp, color = S_TEXT, lineHeight = 15.sp)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            if (away.isEmpty()) {
                Text("—", fontSize = 11.sp, color = S_SUB)
            } else {
                away.forEach { line ->
                    Text(
                        line, fontSize = 11.sp, color = S_TEXT, lineHeight = 15.sp,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

private fun statusLabel(state: Int): String = when {
    state == -1 -> "完场"
    state == 0 -> "未开始"
    state == 2 -> "中场"
    state in 1..4 -> "进行中"
    else -> "推迟"
}

/**
 * 一行事件：`时间 | 主队事件（右对齐到中缝） | 中缝 | 客队事件（左对齐到中缝）`。
 *
 * 🔴 原来主客两列都直接顶在屏幕中线上（中间 0 间隙），遇到「主队进球 ⚽ + 客队黄牌 ■」这种
 * 分居两侧的行，两个图标在视觉上粘成一坨，看不出谁是谁。现在中间留 24dp 中缝（竖线画正中），
 * 两侧事件各距中线约 12dp；左右两半都用 `weight(1f)` 等宽，保证所有行的中缝严格对齐。
 */
@Composable
private fun EventRow(e: MatchEvent) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            e.minute,
            Modifier.width(38.dp),
            fontSize = 11.sp, color = S_SUB, textAlign = TextAlign.End, maxLines = 1
        )
        Spacer(Modifier.width(6.dp))
        // 左半区：主队事件贴中缝左边界
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (e.side == 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 🔴 文字必须带 weight(fill = false)：否则 Row 先量无权重子项，
                    //    maxLines=2 的长球员名会吃光整行宽度，把后面的 Spacer + GlyphSlot 压成 0dp，
                    //    **进球 ⚽ 图标直接消失**（用户 2026-11-09 报障：「队员名字太长，进球那个足球
                    //    图标显示不出来」）。加权重后 ⚽ 先占 14dp，文字用剩下的宽度折行。
                    EventText(e, alignEnd = true, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(3.dp))
                    GlyphSlot(e.kind)
                }
            }
        }
        // 中缝：固定 24dp 宽的「河道」，竖线画在正中
        // —— 用固定槽而不是给两侧各加 padding，左右两列才能真正镜像对称
        //   （⚽ 字形自带左右空白，黄牌是 8dp 矩形，靠 padding 会算出不对称的视觉间距）
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(1.dp).height(16.dp).background(Color(0xFFD9D9D9)))
        }
        // 右半区：客队事件贴中缝右边界
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (e.side != 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphSlot(e.kind)
                    Spacer(Modifier.width(3.dp))
                    // 同左侧：带权重，保证 ⚽ / ⇄ / 牌 不会被长名字挤没
                    EventText(e, alignEnd = false, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

/**
 * 图标占位槽：固定 14dp 宽、图标居中。
 * 黄牌是 8dp 矩形、进球是 ⚽ 字形（自带左右空白），不统一宽度的话两侧图标到中缝的
 * **视觉**距离会一近一远，看起来还是歪的。固定槽宽后左右严格对称（各距中线 15dp）。
 */
@Composable
private fun GlyphSlot(kind: Int) {
    Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
        EventGlyph(kind)
    }
}

/** 事件文字：球员名（进球附助攻、换人附上下场）。*/
@Composable
private fun EventText(e: MatchEvent, alignEnd: Boolean, modifier: Modifier = Modifier) {
    val main = if (e.kind == EventKind.SUBSTITUTION && e.player2.isNotEmpty()) {
        // 换人：`上场 / 下场`（分隔符用斜杠 —— 前面已经有 ⇄ 图标，再写一次箭头会变成三个符号）
        "${e.player2} / ${e.player}"
    } else {
        e.player
    }
    // 源站有相当一部分黄牌/红牌**本来就不带球员名**（低级别联赛数据源只给牌不给牌主），
    // 这时只画图标、不写「黄牌」二字 —— 否则同一行里「黄牌」+ 黄牌图标重复两遍，看着像占位符。
    if (main.isEmpty()) return
    val extra = when {
        e.kind == EventKind.SUBSTITUTION -> ""
        e.player2.isNotEmpty() -> "(助攻:${e.player2})"
        else -> ""
    }
    Text(
        buildString {
            append(main)
            if (extra.isNotEmpty()) append(" $extra")
        },
        modifier,
        fontSize = 11.5.sp,
        color = if (e.kind == EventKind.CANCEL_GOAL) S_SUB else S_TEXT,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        lineHeight = 13.sp,
        maxLines = 2
    )
}

/**
 * 事件图标（不引图片资源，全部用几何图形画）：
 * 黄/红牌＝竖矩形、进球＝⚽、换人＝绿色⇄、其余（VAR/点球/乌龙…）＝小字标签。
 */
@Composable
private fun EventGlyph(kind: Int) {
    when (kind) {
        EventKind.YELLOW -> Box(
            Modifier.width(8.dp).height(11.dp)
                .background(Color(0xFFF5C518), RoundedCornerShape(1.dp))
        )
        EventKind.RED, EventKind.SECOND_YELLOW -> Box(
            Modifier.width(8.dp).height(11.dp)
                .background(Color(0xFFE23A3A), RoundedCornerShape(1.dp))
        )
        EventKind.GOAL, EventKind.PENALTY, EventKind.OWN_GOAL -> Text(
            "⚽", fontSize = 11.sp, lineHeight = 12.sp
        )
        EventKind.SUBSTITUTION -> Text(
            "⇄", fontSize = 12.sp, color = Color(0xFF2FA84F), fontWeight = FontWeight.Bold
        )
        EventKind.CANCEL_GOAL, EventKind.VAR -> Text(
            "VAR", fontSize = 8.sp, color = Color(0xFF8E6CC8), fontWeight = FontWeight.Bold
        )
        EventKind.MISS_PENALTY -> Text(
            "失点", fontSize = 8.sp, color = S_SUB
        )
        else -> Unit
    }
}

/** 弹层里的技术统计一行：主值（橙） | 名称（居中灰） | 客值（蓝）。*/
@Composable
private fun SheetTechRow(s: TechStat) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            s.home, Modifier.weight(1f),
            fontSize = 12.5.sp, color = S_HOME, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, maxLines = 1
        )
        Text(
            s.name, Modifier.width(96.dp),
            fontSize = 11.5.sp, color = S_SUB, textAlign = TextAlign.Center, maxLines = 1
        )
        Text(
            s.guest, Modifier.weight(1f),
            fontSize = 12.5.sp, color = S_AWAY, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start, maxLines = 1
        )
    }
}

/** 分区标题条（技术统计 / 首发阵容 / 替补 共用同一套视觉）。*/
@Composable
private fun SectionBar(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth()
            .background(SurfaceTint)
            .padding(vertical = 6.dp),
        color = TextSub, fontSize = 12.sp,
        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
    )
}

/**
 * 「首发阵容」的列头：`队名 4-2-3-1` + `主教练 xxx`，左右各占一半 —— 与下面的球员列严格同宽，
 * 这样名单看起来是两栏而不是一坨文字。
 */
@Composable
private fun LineupTeams(m: LiveMatch, lu: MatchLineup) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
        TeamHead(m.home, lu.home, S_HOME, Modifier.weight(1f).padding(end = 6.dp))
        Spacer(Modifier.width(1.dp))
        TeamHead(m.away, lu.away, S_AWAY, Modifier.weight(1f).padding(start = 6.dp))
    }
    LineupColumns(lu.home.starters, lu.away.starters)
}

@Composable
private fun TeamHead(name: String, t: TeamLineup, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            if (t.formation.isEmpty()) name else "$name ${t.formation}",
            fontSize = 10.5.sp, color = color, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (t.coach.isNotEmpty()) {
            Text(
                "主教练 ${t.coach}",
                fontSize = 9.sp, color = S_SUB,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 两栏名单（左主右客），中间一条 1dp 竖线。
 * `height(IntrinsicSize.Min)` 是竖线能撑满两栏较高一侧高度的前提（否则 `fillMaxHeight` 拿到 0）。
 */
@Composable
private fun LineupColumns(home: List<LineupPlayer>, away: List<LineupPlayer>) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .height(IntrinsicSize.Min)
    ) {
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            home.forEach { LineupRow(it) }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(S_LINE))
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            away.forEach { LineupRow(it) }
        }
    }
}

/** 名单一行：`号 | 姓名 | 队长 C | 标记（⚽ / 牌 / ⇅）`。姓名过长截断，不能把标记挤没。*/
@Composable
private fun LineupRow(p: LineupPlayer) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            p.number, Modifier.width(19.dp),
            fontSize = 9.5.sp, color = S_SUB, textAlign = TextAlign.End, maxLines = 1
        )
        Spacer(Modifier.width(4.dp))
        // 🔴 必须 `fill = false`：名字短时标记要紧跟名字（不是飘到列尾），名字长时仍能被截断留出
        //    图标位置 —— 与事件行里「长名字把 ⚽ 挤没」是同一个坑
        Text(
            p.name, Modifier.weight(1f, fill = false),
            fontSize = 11.sp, color = S_TEXT, lineHeight = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (p.captain) {
            Spacer(Modifier.width(2.dp))
            Text("C", fontSize = 7.5.sp, color = Color(0xFFE08A00), fontWeight = FontWeight.Bold)
        }
        p.marks.forEach { MarkGlyph(it) }
    }
}

/** 球员标记：黄/红牌＝小色块，进球＝⚽，换入/换出＝绿色 ↑ / 灰色 ↓，助攻＝「助」。*/
@Composable
private fun MarkGlyph(mark: PlayerMark) {
    Spacer(Modifier.width(3.dp))
    when (mark.kind) {
        EventKind.YELLOW -> Box(
            Modifier.width(5.dp).height(7.dp)
                .background(Color(0xFFF5C518), RoundedCornerShape(1.dp))
        )
        EventKind.RED, EventKind.SECOND_YELLOW -> Box(
            Modifier.width(5.dp).height(7.dp)
                .background(Color(0xFFE23A3A), RoundedCornerShape(1.dp))
        )
        EventKind.GOAL -> Text("⚽", fontSize = 8.sp, lineHeight = 9.sp)
        EventKind.OWN_GOAL -> Text("乌", fontSize = 7.sp, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
        EventKind.PENALTY -> Text("点", fontSize = 7.sp, color = Color(0xFF8E6CC8), fontWeight = FontWeight.Bold)
        LineupMark.SUB_ON -> Text("↑", fontSize = 9.sp, lineHeight = 9.sp, color = Color(0xFF2FA84F), fontWeight = FontWeight.Bold)
        LineupMark.SUB_OFF -> Text("↓", fontSize = 9.sp, lineHeight = 9.sp, color = S_SUB, fontWeight = FontWeight.Bold)
        LineupMark.ASSIST -> Text("助", fontSize = 7.sp, color = Color(0xFF3B82C4))
        else -> Unit
    }
}
