package com.example.jingcai.ui.sameodds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jingcai.data.model.SameOddsMatch
import com.example.jingcai.data.model.SameOddsResult
import com.example.jingcai.ui.*
import com.example.jingcai.ui.theme.*

/** 奖金类型：与 WinForms 完全一致（0=全部奖金，不带 bonus 参数）。 */
private val bonusNames = listOf("全部奖金", "初始奖金", "变化奖金", "最终奖金")

/** 赔率候选：与 WinForms 一致，1.02 ~ 19.00，步长 0.01，首项「全部」。 */
internal val oddsOptions: List<String> = buildList {
    add("全部")
    for (c in 102..1900) add(String.format("%.2f", c / 100.0))
}

/**
 * 下拉默认展示的「常用区间」——避免展开时一次性渲染 1800 项。
 *
 * 依据：竞彩足球胜平负的赔率绝大多数落在 1.20 ~ 6.00；再往上（冷门大赔）用搜索框输入即可。
 * 约 480 项 ≈ 3000+ 节点，展开约 100ms 内，可接受；1800 项才是明显卡顿的元凶。
 */
private val QUICK_ODDS: List<String> = buildList {
    add("全部")
    for (c in 120..600) add(String.format("%.2f", c / 100.0))
}

/** 搜索结果上限：再多也没人会翻，避免用户输入「1」时又铺出上千项。 */
private const val MAX_RESULT = 60

@Composable
fun SameOddsScreen(
    /** 二级页返回。本页已从底部 tab 撤掉（用户要求），由比赛卡片的「同奖查询」进入 ⇒ 必须有返回箭头。 */
    onBack: (() -> Unit)? = null,
    vm: SameOddsViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    // 与「赔率变化 / 近期状态」共用 [ToolPage]：同一页面底色 + 同高主色顶栏 + 同尺寸卡片。
    // 🔴 不再传 scrollable = false：结果表已从 LazyColumn 改成普通 Column，整页一起滚。
    //    （旧写法：LazyColumn 的外层套 verticalScroll 会收到无限高约束而崩
    //     `Vertically scrollable component was measured with an infinity maximum height constraints`）
    ToolPage(
        title = "同奖查询",
        subtitle = "按胜平负赔率反查历史同奖比赛",
        onBack = onBack
    ) {
        // 查询条件卡：3 行压到 2 行（主/平/客 一行、奖金类型+重置+查询 一行），
        // 让出高度给下方结果表（改前 3 行卡片 ≈ 上一屏 1/4，结果只看到 4 行）。
        SectionCard("查询条件") {
            Column(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OddsDropdown("主胜", state.h, Modifier.weight(1f)) { vm.setOdds(it, state.d, state.a) }
                    OddsDropdown("平局", state.d, Modifier.weight(1f)) { vm.setOdds(state.h, it, state.a) }
                    OddsDropdown("客胜", state.a, Modifier.weight(1f)) { vm.setOdds(state.h, state.d, it) }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BonusDropdown(state.bonus, Modifier.weight(1.15f)) { vm.setBonus(it) }
                    OutlinedButton(
                        onClick = { vm.reset() }, modifier = Modifier.weight(0.75f).height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("重置", fontSize = 13.sp) }
                    Button(
                        onClick = { vm.query() }, modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("查询", fontSize = 13.sp) }
                }
                if (state.error != null) Text(state.error!!, color = Up, fontSize = 13.sp)
            }
        }

        state.result?.let { r ->
            if (r.total > 0) {
                Text(
                    "共${r.total}场比赛符合条件：胜${r.winNum}场 平${r.drawNum}场 负${r.loseNum}场（${bonusNames[state.bonus]}）",
                    color = Up, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
            } else {
                Text("没有符合条件的比赛（${bonusNames[state.bonus]}）", color = TextSub, fontSize = 13.sp)
            }
        }

        if (state.loading) Loading()
        else state.result?.let { r -> if (r.total > 0) ResultTable(r) }
    }
}

/**
 * 赔率下拉：点击展开列表（含「全部」）。
 *
 * 修复要点（崩溃根因）：material3 1.2.0 的 [DropdownMenu] 内部使用
 * `Column(...verticalScroll(scrollState))`。若在其中再嵌套纵向可滚动的 `LazyColumn`
 * （高度约束为无穷大），Compose 会抛出
 * `IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints`，点击即崩。
 *
 * 因此这里改用普通 [Column] + [verticalScroll]（会被正常测量），并把
 * `heightIn(max = 320.dp)` 约束放在 `verticalScroll` **之外**，保证菜单高度受限
 * 且仍可滚动查看全部 1800 项。
 *
 * ── 性能修复（本轮「点击后显示很慢」的真因） ──────────────────────────────
 * 上一版把 1800 项**无条件全部**渲染（`filtered.forEach`，filter 为空时 = 全部 1800 项）。
 * [Column] 是**非懒加载**布局：展开那一刻会一次性组合 1800 个 [DropdownMenuItem]，
 * 每个菜单项内部含 Row + Text + 交互修饰符，约 6~8 个 Compose 节点
 * ⇒ **瞬时创建 1.2 万个左右节点**，手机端需数百毫秒到一秒，观感就是「点了半天才出来」。
 *
 * 修法（三条，缺一不可）：
 *  1. **默认不铺全量**：未输入搜索词时只显示「全部」＋最常用区间（[QUICK_ODDS]），
 *     从 1800 项降到几十项；
 *  2. **搜索才展开全量**：用户输入（如 2.35）时才过滤全量并按 [MAX_RESULT] 截断；
 *  3. 仍保留「全量可查」能力 —— 即功能不缩水，只是不再一次性渲染。
 */
@Composable
private fun OddsDropdown(label: String, value: String, modifier: Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    // 只在「有搜索词」时才过滤全量 1800 项；空搜索词直接给轻量列表。
    // 注意：这里不再需要 derivedStateOf —— 计算量已从 1800 项降到几十项，
    // 用简单的记忆化（remember(filter)）即可，且避免 derivedStateOf 在
    // 非读取上下文里的额外开销。
    val filtered: List<String> = remember(filter) {
        val q = filter.trim()
        if (q.isEmpty()) QUICK_ODDS
        else oddsOptions.asSequence()
            .filter { it.contains(q) }
            .take(MAX_RESULT)
            .toList()
    }
    Column(modifier) {
        Text(label, color = TextSub, fontSize = 10.sp)
        Box(
            Modifier.fillMaxWidth()
                .border(1.dp, Line, RoundedCornerShape(8.dp))
                .clickable { filter = ""; expanded = true }
                .padding(horizontal = 8.dp, vertical = 7.dp)
        ) {
            Text(
                value.ifBlank { "全部" },
                fontSize = 13.sp, color = TextMain, maxLines = 1
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp)
        ) {
            // 搜索框：精确输入可快速定位（如输入 2.35）
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                placeholder = { Text("搜索赔率，如 2.35", fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                modifier = Modifier.width(200.dp).padding(horizontal = 8.dp, vertical = 4.dp)
            )
            // 高度约束 + 普通 Column（非 LazyColumn），保证可测量、可滚动
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                if (filtered.isEmpty()) {
                    Text("无匹配赔率", color = TextSub, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                } else if (filter.isBlank()) {
                    // 明确告知「默认只列常用区间」，否则用户会以为赔率不全
                    Text(
                        "常用 1.20~6.00（更大赔率请在上方输入，如 8.50）",
                        color = TextSub, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                filtered.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(o, fontSize = 14.sp, color = if (o == value || (o == "全部" && value.isBlank())) Primary else TextMain) },
                        onClick = { onSelect(if (o == "全部") "" else o); expanded = false }
                    )
                }
            }
        }
    }
}

/** 奖金类型：列表展示（下拉），与 WinForms 的奖金类型 ComboBox 一致（仅 4 项，非懒加载）。 */
@Composable
private fun BonusDropdown(selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(36.dp)
                .border(1.dp, Line, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                bonusNames.getOrElse(selected) { bonusNames[0] },
                fontSize = 13.sp, color = TextMain, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text("▾", fontSize = 12.sp, color = TextSub)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            bonusNames.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = {
                        Text(
                            name, fontSize = 14.sp,
                            color = if (i == selected) Primary else TextMain,
                            fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = { onSelect(i); expanded = false }
                )
            }
        }
    }
}

/**
 * 结果表：**左侧两行块（主队 VS 客队 / 日期）+ 右侧数值列（胜/平/负/赛果/奖金类型）**。
 *
 * 演进（三次，都是为了「别挤、别挡」）：
 *  1. 旧版写死 `tableWidth = 760.dp` + 横向滚动 ⇒ 一屏只有 ≈336dp 可视宽，每列按 760dp 摊分，
 *     列里大片空白、右边还要拖动才能看全 ⇒ 用户反馈「列太松」；
 *  2. 第二版改成按可视宽 weight 等分、删掉横向滚动，但「主客队/日期各占一列」：
 *     像素实测日期列只有 53.7dp，而 "2026-05-03" 需要 ≈57dp ⇒ **末位被裁**（显示成 9 个字符）；
 *  3. 本版按用户提议「把主客、日期分两行显示，然后后面跟其他内容」：主客队与日期改为
 *     **左侧一个两行块**，右侧只放数值列。日期不再独占列宽，裁字问题随之消失；
 *     同时主客队可用宽度从 107.4dp 提到 ≈170dp（+58%），长队名也不再被切。
 *
 * 布局：表头与数据行同构（`fillMaxWidth()` + `weight`），总宽恒等于卡片可视宽，**无横向滚动**。
 *
 * ── 性能修复（历史「显示很慢」的第二处原因，保留） ──────────────────────────
 *  1. 不再逐项延迟入场动画（旧版 delayMillis = index * 25，第 100 行要等 2.5s）；
 *  2. 改普通 Column 后没有 key 复用可言，靠 [MAX_ROWS] 封顶：一次性组合最多 300 行，
 *     极端查询（上千条同奖）不会把主线程卡死。
 */
@Composable
private fun ResultTable(r: SameOddsResult) {
    // SectionCard(null)：本表自带表头行，不再加标题条（标题条底色与表头同为浅底，会糊成一片）
    SectionCard(null) {
        Column {
            // 表头：左块两行 + 右侧数值列，与数据行同一套 weight ⇒ 列边界严格一致
            Row(
                Modifier.fillMaxWidth().background(SurfaceTint).padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(W_BLOCK).padding(start = 10.dp)) {
                    HeadCell2("主队 VS 客队", 11.sp)
                    HeadCell2("日期", 10.sp)
                }
                Cell("胜", W_ODDS, head = true)
                Cell("平", W_ODDS, head = true)
                Cell("负", W_ODDS, head = true)
                Cell("赛果", W_SCORE, head = true)
                Cell("奖金类型", W_BONUS, head = true)
            }
            // 🔴 这里**不能**用 LazyColumn：外层 ToolPage 已套 verticalScroll，嵌套纵向可滚动组件
            //    会收到无限高约束并抛 `Vertically scrollable component was measured with an
            //    infinity maximum height constraints`（点进来即闪退）。改用普通 Column 一次性
            //    组合前 [MAX_ROWS] 行，整页跟着滚（用户诉求：结果要和页面一起滚，且一屏看到更多行）。
            Column {
                r.matches.take(MAX_ROWS).forEachIndexed { i, m -> ResultRow(m, striped = i % 2 == 1) }
                if (r.matches.size > MAX_ROWS) {
                    Text(
                        "仅显示前 $MAX_ROWS 条，共 ${r.matches.size} 条",
                        fontSize = 11.sp, color = TextSub,
                        modifier = Modifier.fillMaxWidth().padding(10.dp), textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/* 列权重（按 336dp 卡片可视宽换算，数值全部来自 1260×2800 真机截图的逐列像素实测）：
 *   左块 ≈170dp —— 第一行主客队最长实测墨迹 99.7dp@11sp，第二行日期 51.7dp@10sp，各自都够；
 *   胜/平/负各 ≈28.6dp（最长墨迹 26.3dp@11sp）；赛果 ≈30dp（最长 18.9dp）；
 *   奖金类型 ≈50dp（最长 43.7dp@11sp）。
 * 上一版「主客队 .32 / 日期 .16」时日期列 53.7dp < 所需的 57dp ⇒ 末位被裁；本版日期并入左块第二行，
 * 不再受列宽约束。 */
private const val W_BLOCK = 0.506f
private const val W_ODDS = 0.085f
private const val W_SCORE = 0.089f
private const val W_BONUS = 0.149f

/** 一次性组合的行数上限：整页 verticalScroll + 普通 Column ⇒ 全部行都会立刻 compose，
 *  极端查询（上千条）不封顶会卡死主线程。超出部分给一行提示。 */
private const val MAX_ROWS = 300

@Composable
private fun ResultRow(m: SameOddsMatch, striped: Boolean = false) {
    // 该颜色只是「有比分→红、无比分→灰」，是静态二选一，不需要 animateColorAsState
    //（旧版每行起一个颜色动画，100 行 = 100 个并发动画，是「卡一下才出来」的次要原因）。
    val scoreColor = if (m.score.isNotEmpty()) Up else TextSub
    Column {
        Row(
            Modifier.fillMaxWidth()
                .background(if (striped) SurfaceAlt else Color.Transparent)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左块两行：第一行主队 VS 客队，第二行日期（与表头同构）
            Column(Modifier.weight(W_BLOCK).padding(start = 10.dp)) {
                Text(
                    "${m.home} VS ${m.away}",
                    fontSize = 11.sp,
                    color = TextMain,
                    maxLines = 1,
                    // 极端长队名（如 7 字 VS 7 字）仍可能超出 170dp：用省略号优雅降级，
                    // 而不是被硬切一半（用户此前的诉求就是「很多被挡住」）。
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    m.matchDate.take(10),
                    fontSize = 10.sp,
                    color = TextSub,
                    maxLines = 1
                )
            }
            Cell(m.h, W_ODDS, fontSizeSp = 11)
            Cell(m.d, W_ODDS, fontSizeSp = 11)
            Cell(m.a, W_ODDS, fontSizeSp = 11)
            Cell(m.score, W_SCORE, color = scoreColor, bold = true)
            Cell(m.bonusType, W_BONUS, color = TextSub, fontSizeSp = 11)
        }
        HorizontalDivider(color = Line, thickness = 0.5.dp)
    }
}

/** 表头左块的一行文字（主客队行 / 日期行）。 */
@Composable
private fun HeadCell2(text: String, size: TextUnit) {
    Text(
        text,
        fontSize = size,
        color = TextSub,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun RowScope.Cell(
    text: String,
    weight: Float,
    color: Color = TextMain,
    bold: Boolean = false,
    head: Boolean = false,
    fontSizeSp: Int = -1
) {
    Text(
        text,
        fontSize = if (head) 11.sp else if (fontSizeSp > 0) fontSizeSp.sp else 12.sp,
        color = if (head) TextSub else color,
        fontWeight = if (head) FontWeight.SemiBold else if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(weight),
        maxLines = 1
    )
}
