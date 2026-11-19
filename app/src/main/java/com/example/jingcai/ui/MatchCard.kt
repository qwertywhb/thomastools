package com.example.jingcai.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jingcai.data.model.Match
import com.example.jingcai.data.repository.UserPrefs
import com.example.jingcai.ui.theme.*

/**
 * 单场比赛卡片（比赛列表共用）。
 *
 * ── 本轮视觉改版（用户：「一点现代化的布局，每个联赛名字都用自己不同的颜色背景」）─────
 * 结构仍是「左内容列 + 右操作列」，**尺寸全部沿用上一版的实测结论**（不许再压），只换视觉层：
 *
 *  1. **联赛胶囊用源站给的联赛色**（`Match.color`，来自 okooo `a.saiming` 的 `background-color`）：
 *     实心源色 + 6dp 圆角，与网页端/其它彩站一致，一眼认得出「澳超深棕 / 德乙品红 / 荷甲粉」。
 *     🔴 不要自己编色板 —— 只有源站没给颜色时才由 [LeagueColors] 按名字兜底。
 *  2. **左侧 4dp 联赛色条**：用 `drawBehind` 直接画在卡片内边（4dp 宽度落在原本 10dp 的
 *     内边距里，**不占内容宽度** —— 队名宽度余量只有 ≈6dp，绝不能因为装饰再挤掉 4dp）。
 *     画在卡片内 ⇒ 上下圆角被 Card 的 clip 自动裁掉，不用自己算圆角路径。
 *  3. **赔率区做成一块浅底圆角面板**（[SurfaceAlt]，10dp 圆角），左侧只放一个高亮标签
 *     「让球+1」（绿色加粗 11sp，在两行数值里垂直居中），右侧是「胜/平/负」列头
 *     + 上下两行 × 3 列数值：上行胜平负、下行让球胜平负。
 *     「胜平负」这条玩法标签仍按用户要求**不显示**（让球标签 + 列头已足够区分），
 *     但**每一列是什么**必须写出来 —— 列头（[OddsHeaderRow]）是 2026-11-11 补的，
 *     原因就是旧版两行裸数字只能靠「数字不一样」去猜哪行是让球，而竞彩最不能猜。
 *  4. 卡片：18dp 圆角 + 1dp [Line] 描边 + 3dp 阴影（按压时降到 1dp，配合 0.985 缩放）；
 *     单关仍用 1.6dp [SingleAccent] 亮红描边。
 *  5. 「单」「已截止」标签改为**静态**小标签：旧版是无限脉冲动画，一屏十几张卡就有十几个
 *     无限动画在跑，滚动时反而成了负担（与「往下滑出现空白」的性能问题同源）。
 *
 * ── 历史：Batch 2 曾把单卡压到 ≈87dp（卡内 spacedBy 3dp / 行高 13–15dp）。
 *    用户随后反馈「**每一条中内容上下挨得太紧**」——这是过度压缩的后果。
 *    本版**优先恢复垂直呼吸感**：内边距 10dp、卡内区块间距 7dp、行高 16–18dp，
 *    单卡高度 ≈125dp（含卡距）。字号不变（队名/赔率 11sp、卡头 9sp）。
 *
 * ── 卡头「文字没在框中垂直对齐」的历史修复（保留） ───────────────────────────
 * 现象：联赛胶囊 / 「单」/「已截止」标签里，白字挤在框的**底部**，框上半截空出一大片。
 * 两个叠加原因：① Compose 默认 `includeFontPadding = true`，中文回退字体的上下度量
 * 把 9sp 文字的行盒撑到 ≈16dp，而字形只有 ≈8.3dp 且贴着行盒底部绘制；
 * ② `Box(Modifier.background(...))` 靠包住 Text 取尺寸 ⇒ 框高 = 行盒高，于是「上留白 7dp、字贴底」。
 * 修法（两条同时用才稳）：① 卡内小字号统一用 [HeadSmallText] / [BodySmallText]
 * （`includeFontPadding = false`）；② 胶囊/标签显式 `height()` + `contentAlignment = Center`。
 */
@Composable
fun MatchCard(
    m: Match,
    onSameOdds: (Match) -> Unit,
    onOddsChange: (Match) -> Unit,
    onRecentForm: (Match) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(120), label = "cardScale")

    // 🔴 联赛底色**优先用源站给的**（Match.color ← okooo `a.saiming` 的 background-color），
    //    解析失败（老数据/字段缺失）才退到按名字哈希的兜底色板，绝不自己另编一套。
    val leagueColor = parseHexColorOrNull(m.color) ?: LeagueColors.solid(m.league)

    // 单关亮边框；非单关用 1dp 常规描边（旧版是「透明边框占位」，在加深后的页面底上
    // 卡片会显得没边界，现在一律有可见描边，两种卡片尺寸仍然完全一致）。
    val border = BorderStroke(
        if (m.single) 1.6.dp else 1.dp,
        if (m.single) SingleAccent else Line
    )

    // 按压缩放反馈：pointerInput + awaitEachGesture 只「监听」按压状态，绝不消费事件，
    // 因此不会抢占内部「同奖查询」「赔率变化」按钮的点击/水波纹。
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val press = PressInteraction.Press(down.position)
                    interaction.tryEmit(press)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    interaction.tryEmit(PressInteraction.Release(press))
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (pressed) 1.dp else 3.dp),
        border = border
    ) {
        // 外层横向排布：左侧内容列（weight 占满） + 右侧操作列（固定宽）。
        Row(
            Modifier
                .fillMaxWidth()
                // 左侧联赛色条：画在**内边距之前**的坐标系里 ⇒ 从卡片最左边起算，
                // 但它只占 0~4dp，而内容从 10dp 开始，两者不重叠、内容宽度零损失。
                .drawBehind {
                    drawRect(leagueColor, size = Size(4.dp.toPx(), size.height))
                }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                // 1) 头部：联赛 / 编号 / 标签 / 开赛时间（单行）
                //    三个「有底色」的元素统一 height(18.dp) + 居中，文字统一 includeFontPadding=false
                //    ⇒ 框高一致、框内文字与旁边的纯文字在同一水平线上。
                Row(
                    Modifier.fillMaxWidth().height(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LeaguePill(m.league.ifEmpty { "—" }, leagueColor)
                    Spacer(Modifier.width(8.dp))
                    // 竞彩编号「周四009」：用户要求**和「让球+X」同字号（11sp）+ 红色**。
                    // 旧版是 9sp 灰字（不起眼），中间版本加过淡底胶囊（用户觉得多余）；
                    // 现在就是纯文字 11sp 红字 SemiBold —— 与右侧让球标签同字号，形成左右两个
                    // 「联赛(源色) / 编号(红)」的主信息点。
                    Text(
                        m.order,
                        style = OrderText,
                        color = Up,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (m.single) {
                        Spacer(Modifier.width(4.dp))
                        MiniTag("单", bg = Up, color = Color.White, bold = true)
                    }
                    if (m.finished) {
                        Spacer(Modifier.width(4.dp))
                        MiniTag("已截止", bg = SurfaceAlt, color = TextSub)
                    }
                    Spacer(Modifier.weight(1f))
                    // 关注球队：★ 已关注 / ☆ 未关注。点击弹「主队 / 客队」二选一。
                    // 为什么放在这一行（而不是右侧操作列）：操作列宽 64dp 已经塞满 3 个按钮
                    // （3×32 + 2×4 = 104dp ≈ 卡高上限），再加第 4 个会把卡片撑高；
                    // 头部行本来就有富余空间，时间左边刚好空着。
                    TeamFollowStar(m)
                    Spacer(Modifier.width(6.dp))
                    // 开赛时间：完整 yyyy-MM-dd HH:mm（缺失时回退到仅时间）
                    Text(
                        m.fullTime.ifEmpty { m.time },
                        style = HeadSmallText, color = TextSub, maxLines = 1
                    )
                }

                // 2) 对阵：[主场 排名+队名] 中(比分/VS) [客场 排名+队名]，单行。
                //    赛果并入本行：放在**中间替换 VS**，而不是另占一行、也不另占右侧宽度——
                //    否则会压缩两侧队名的可用宽度，导致「纽卡斯尔喷气机」这类 7 字长队名被裁切。
                Row(
                    Modifier.fillMaxWidth().height(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TeamSide(m.home, m.homeRank, Modifier.weight(1f))
                    val center = if (m.finished && m.score.isNotEmpty()) m.score else "VS"
                    Text(
                        center,
                        color = if (center == "VS") TextSub else Up,
                        fontSize = if (center == "VS") 9.sp else 11.sp,
                        fontWeight = if (center == "VS") FontWeight.Normal else FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        maxLines = 1
                    )
                    TeamSide(m.away, m.awayRank, Modifier.weight(1f))
                }

                // 3) 赔率面板（用户本轮定版 + 列头）：
                //    「不用显示胜平负三个字，我知道这是胜平负和让球胜平负，只需要显示让球，
                //      让其高亮垂直居中显示，字体稍微放大」
                //    ⇒ 左侧只留一个标签「让球+1」：绿色高亮 + 加粗 + 11sp，
                //      在**整个数值区**里垂直居中（`Row` 的 verticalAlignment = Center）；
                //      右侧 = 列头（胜/平/负）+ 上下两行 × 3 列数值（上行胜平负、下行让球），列宽等分 ⇒ 三行严格共线。
                //
                //    🔴 **列头是本轮新增的**：旧版只有两行裸数字，用户只能靠「两行数字不一样」去猜
                //    哪行是胜平负、哪行是让球。竞彩恰恰最不能猜。列头 8sp/10dp 高，是能说清又不占地方的下限。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceAlt)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(HANDICAP_COL_W), contentAlignment = Alignment.Center) {
                        Text(
                            handicapLabel(m.handicap),
                            style = HandicapText,
                            // 用户要求「让球绿色字体」：让球行统一用跌/绿 [Down]，
                            // 与「涨跌」语义中的绿色同色值，整页配色收敛。
                            color = Down,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        OddsHeaderRow()
                        OddsValueRow(formatOdds(m.win), formatOdds(m.draw), formatOdds(m.lose))
                        OddsValueRow(formatOdds(m.hwin), formatOdds(m.hdraw), formatOdds(m.hlose))
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // 4) 右侧操作列：与左侧内容并排 ⇒ 不增加卡片高度。
            //
            //    🔴 本轮收拢：旧版三个等大实心块（赔率变化 / 同奖查询 / 近期状态）并排堆叠，
            //    整列 64dp 宽、三块底色一样重，主次不分，还把左侧队名挤窄了。
            //    现在只保留一个主按钮「同奖查询」，另外两个次要动作收进「更多 ▾」菜单。
            //    操作列 64 → 56dp，队名/赔率各多出 8dp 余量。
            Column(
                Modifier.width(56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ActionText("同奖查询", primary = true) { onSameOdds(m) }
                MoreActions(onOddsChange = { onOddsChange(m) }, onRecentForm = { onRecentForm(m) })
            }
        }
    }
}

/**
 * 关注球队的星标。
 *
 * 语义：**球队级**关注（不是比赛级）。已关注任一方 → 实心 ★ 主色；
 * 未关注 → 空心 ☆ 淡灰。点一下弹出「主队 / 客队」让用户选，避免在一张卡上
 * 放两个星标挤压队名宽度（队名区本来就只剩 ≈6dp 余量，见 [TeamSide] 的宽度分析）。
 */
@Composable
private fun TeamFollowStar(m: Match) {
    val followed by UserPrefs.followedTeams.collectAsState()
    val anyOn = (m.home.isNotEmpty() && m.home in followed) ||
        (m.away.isNotEmpty() && m.away in followed)
    var open by remember { mutableStateOf(false) }

    Box {
        Text(
            if (anyOn) "★" else "☆",
            fontSize = 13.sp,
            color = if (anyOn) Primary else TextSub.copy(alpha = 0.55f),
            maxLines = 1,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { open = true }
                .padding(horizontal = 3.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(m.home, m.away).filter { it.isNotBlank() }.forEach { team ->
                val on = team in followed
                DropdownMenuItem(
                    text = {
                        Text(
                            "${if (on) "★" else "☆"} $team",
                            fontSize = 13.sp,
                            color = if (on) Primary else TextMain
                        )
                    },
                    onClick = {
                        UserPrefs.toggleTeamFollow(team)
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * 联赛胶囊：**网页原色**（源站 `background-color`）平涂 + 自动配色的文字。
 *
 * 🔴 用户明确要求「不要渐变色，就用网页原色，并且长度稍微变长」：
 *  - 渐变（+22% 白的亮端）会让颜色偏离源站，肉眼比对网页时对不上 ⇒ 改回**纯色平涂**；
 *  - 左右内边距 6dp → 11dp，胶囊比原来长一截，短联赛名（如「英超」）不会挤成一个小方块，
 *    和右边 11sp 的编号/时间放在一起也更平衡。
 * 文字色由 [LeagueColors.textOn] 按亮度决定：常规联赛色用白字（与源站一致），
 * 极浅底色（如粉、浅紫）自动换深字，避免「白字看不见」。
 */
@Composable
private fun LeaguePill(name: String, color: Color) {
    Box(
        Modifier
            .height(18.dp)
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name,
            style = HeadSmallText,
            color = LeagueColors.textOn(color),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** 单侧球队：排名（如 [阿甲18]）与队名同行居中，保证对阵行只有一行高。 */
@Composable
private fun TeamSide(name: String, rank: String, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (rank.isNotEmpty()) {
            // 排名 7sp：最坏 `[联赛名2-3字+名次2位]`（如 [巴西甲18]）在 7sp 下 ≈33dp，
            // 与 11sp×7 字队名(77dp)+间距2dp 合计 ≈112dp < 单侧可用 ≈115dp ⇒ 不裁切。
            Text("[${rank}]", style = RankText, color = Up, maxLines = 1)
            Spacer(Modifier.width(2.dp))
        }
        // 队名 11sp：离线最坏宽度分析（7 全角字「纽卡斯尔喷气机」）要求 ≤ 单侧可用 115dp，
        // 11sp×7=77dp + 排名≈30dp + 间距2dp = 109dp ⇒ 余量 ≈6dp，不裁切。
        Text(name.ifEmpty { "—" }, style = BodySmallText, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * 一行 3 个赔率数值（等分列宽）。上行 = 胜平负，下行 = 让球胜平负；
 * 两行共用同一结构 ⇒ 三列数值上下严格共线。
 */
@Composable
private fun OddsValueRow(v1: String, v2: String, v3: String) {
    Row(
        Modifier.fillMaxWidth().height(17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OddsValue(v1, Modifier.weight(1f))
        OddsValue(v2, Modifier.weight(1f))
        OddsValue(v3, Modifier.weight(1f))
    }
}

/**
 * 赔率面板的列头：`胜 平 负`（8sp 灰字）。
 *
 * 三格用 `weight(1f)` 等分，与下面两行 [OddsValueRow] 的列宽算法**完全一致** ⇒ 列头与数值严格共线。
 * 高度钉 11dp：再矮字会被行盒裁掉，再高就是纯浪费 —— 它只是三类数字的索引，不是内容。
 */
@Composable
private fun OddsHeaderRow() {
    Row(
        Modifier.fillMaxWidth().height(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf("胜", "平", "负").forEach { label ->
            Text(
                label,
                style = OddsHeaderText,
                color = TextSub,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun OddsValue(value: String, modifier: Modifier = Modifier) {
    Text(
        value,
        style = BodySmallText,
        color = if (value == "未开售") TextSub else TextMain,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

/**
 * 次要动作入口：`更多 ▾` 菜单，收「赔率变化」和「近期状态」。
 *
 * 🔴 为什么不在卡片上直接摆三个按钮：三个等大实心块并排堆叠时，主次完全分不出来
 * （用户之前就把「赔率变化」当成了纯文字），而且整列要 64dp 宽，直接吃掉队名与赔率的余量。
 * 现在主按钮只有一个（同奖查询），这两个低频动作收进菜单，**图标/文字仍然常驻可见**，
 * 不靠长按才发现。
 */
@Composable
private fun MoreActions(onOddsChange: () -> Unit, onRecentForm: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Primary.copy(alpha = 0.10f))
                .clickable { open = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "更多 ▾",
                style = BodySmallText,
                color = Up,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("赔率变化", fontSize = 13.sp, color = TextMain) },
                onClick = { open = false; onOddsChange() }
            )
            DropdownMenuItem(
                text = { Text("近期状态", fontSize = 13.sp, color = TextMain) },
                onClick = { open = false; onRecentForm() }
            )
        }
    }
}

/**
 * 轻量操作按钮：Text + 可见底色 + 圆角 + 最小 32dp 触控高度，替代 Material Button/OutlinedButton。
 *
 * 根因（用户把「赔率变化」当成纯文字）：旧版次按钮底色写死 `#F0F1F6`，与卡片底色几乎同色，
 * 肉眼等于「没有底色」；而下面的「同奖查询」有 Primary 淡底，一眼就像按钮 ⇒ 主次误导。
 * 修法：两个按钮统一到 Primary 色系，靠「实心/淡底」拉开主次；
 * 本轮把次按钮淡底改成**实色** [LeagueColors] 之外的中性浅紫（`#E1E5FB`），
 * 避免半透明底在深色页面底上看不清。
 */
@Composable
private fun ActionText(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            // 次按钮底：半透明主色（原先写死 #F6E7E7 ⇒ 深色下是一排粉白块）
            .background(if (primary) Primary else Primary.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = BodySmallText,
            color = if (primary) Color.White else Up,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * 让球标签列宽：只放「让球+1」四个字。
 * 11sp 下 4 个全角字 ≈ 44dp，取 48dp 留 4dp 余量（最宽的情形是「让球+2」，等宽）。
 */
private val HANDICAP_COL_W = 48.dp

/**
 * 让球标签文案：「让球」+ 带符号盘口。
 *
 * 🔴 用户明确要求正号要显示（「让球1 修改为让球+1」）：源站 `data-rq` 正数不带 `+`，
 *    直接拼会得到「让球1」，读起来像是「让 1 个」和「受让 1 个」分不清。
 *    - `1`  → `让球+1`（主队让球）
 *    - `-1` → `让球-1`（主队受让）
 *    - `0`  → `让球0`（平手，不加号）
 *    - 空   → `让球`（源数据缺失时不显示无意义的「-」）
 */
private fun handicapLabel(handicap: String): String {
    val v = handicap.trim()
    return when {
        v.isEmpty() -> "让球"
        v == "0" || v == "+0" || v == "-0" -> "让球0"
        v.startsWith("-") -> "让球$v"
        else -> "让球+" + v.removePrefix("+")
    }
}

/**
 * 静态小标签（「单」「已截止」）：显式 18dp 高 + 居中，与联赛胶囊同高，卡头三个元素一条线。
 *
 * 🔴 旧版是无限脉冲（`rememberInfiniteTransition` + alpha 0.65↔1），一屏十几张卡就是十几个
 *    无限动画，滚动时既耗电又让标签忽明忽暗；改为静态后视觉更干净，也顺手减轻了列表负担。
 */
@Composable
fun MiniTag(text: String, bg: Color, color: Color, bold: Boolean = false) {
    Box(
        Modifier
            .height(18.dp)
            .background(bg, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, style = HeadSmallText, color = color, maxLines = 1,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * 卡内小字号统一文字样式（9sp）——卡头胶囊 / 标签 / 编号 / 时间 / 赔率行标签。
 *
 * 🔴 `includeFontPadding = false` 是「文字没在框中垂直对齐」的修复关键：
 * 中文回退字体的 top/bottom 度量会把行盒撑到 ≈1.8em（9sp 时 ≈16dp），而字形只有 ≈8.3dp，
 * 且字形贴着行盒底部绘制 ⇒ 任何 `background()` 包住文字的框都会「上留白 7dp、字贴底」。
 * 关闭字体额外行内边距后行盒回到 ≈13dp，上下留白各 ≈2dp，字形自然居中。
 */
private val HeadSmallText = TextStyle(
    fontSize = 9.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

/** 卡内常规字号统一文字样式（11sp）——队名 / 赔率数值 / 操作按钮。同样关闭字体额外行内边距。 */
private val BodySmallText = TextStyle(
    fontSize = 11.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

/**
 * 让球标签专用样式（11sp，比卡内其它小字 9sp 放大一档）。
 *
 * 用户要求「字体稍微放大」：这个标签现在是整块赔率面板里**唯一**的文字标签
 * （「胜平负」已按要求去掉），它同时承担「这是第几行」的提示作用，字号理应比注释性小字更大。
 */
private val HandicapText = TextStyle(
    fontSize = 11.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

/**
 * 竞彩编号样式（11sp）：用户要求「周几00几字号放大和让球一样」——
 * 两者同为 11sp，卡头（联赛/编号）与赔率面板（让球标签）形成同一视觉重量。
 */
private val OrderText = TextStyle(
    fontSize = 11.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

/** 排名字号（7sp，仅 [TeamSide] 用）。 */
private val RankText = TextStyle(
    fontSize = 7.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

/** 赔率列头（8sp）：只是索引，比正文再小一档，不能抢数值的注意力。 */
private val OddsHeaderText = TextStyle(
    fontSize = 8.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)
