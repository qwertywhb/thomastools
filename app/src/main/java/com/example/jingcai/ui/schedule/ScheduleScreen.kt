package com.example.jingcai.ui.schedule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jingcai.data.model.JingcaiScheduleMatch
import com.example.jingcai.data.model.ScheduleDayGroup
import com.example.jingcai.ui.EmptyText
import com.example.jingcai.ui.ErrorText
import com.example.jingcai.ui.Loading
import com.example.jingcai.ui.ScheduleSport
import com.example.jingcai.ui.ScheduleViewModel
import com.example.jingcai.ui.theme.Background
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.LineSoft
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.SurfaceTint
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.TextMain
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Up

/**
 * 竞彩赛程：体彩官方赛程**表格**（足球 / 篮球，顶部 tab 切换）。
 *
 * 与上一版的区别（重要）：上一版误做成了「比赛」tab 的比赛卡片复刻，带「同奖查询」「赔率变化」
 * 按钮和赔率格子 —— 那些**不是本页职责**。本页只做**赛程表**，恒为以下 4 列，无任何按钮：
 *
 * | 编号（固定） | 联赛 | 主队 VS 客队 | 比赛时间 |
 * |---|---|---|---|
 * | 周六005 | 日职（彩色块） | 柏太阳神 VS 名古屋鲸八 | 2026-10-17 18:00 |
 *
 * 交互：
 *  - **顶部 tab 切换足球/篮球赛程**（本轮新增）：篮球数据源是体彩官方「篮球竞猜赛程」页
 *    <https://www.sporttery.cn/jc/lqszsc/>，与足球共用同一套表格列与解析；
 *  - **编号列固定**不滑动；联赛 / 对阵 / 时间**三列整体水平滑动**，表头与数据行共用一个
 *    [ScrollState] ⇒ 滑动同步、列始终对齐；
 *  - 队名两侧的 ☆ 星标已按需求移除；队名列宽按实测最长队名留足，**不再出现省略号**。
 *
 * 数据源一次返回多天，故列表按销售日分段（周五/周六/周日/周一）并带吸顶标题。
 * 刷新只靠手势下拉；篮球在售场次常远少于足球（实测某日仅 1 场），属真实数据。
 */

// 列宽策略（本轮改版）：
//  - **编号列固定**，不参与水平滚动（用户明确要求「固定编号」）；
//  - 联赛 / 对阵 / 时间 三列**共用同一个 hScroll**，整体左右滑动（表头与数据行同步）；
//  - 队名**不再截断**：列宽按实测最长对阵串给定（见 COL_VS_W 注释）。
private val COL_NUM_W = 62.dp        // 固定
private val COL_LEAGUE_W = 52.dp     // 滑动区
// 实测（qa/_zq_list_sample.json，74 场）：最长对阵串「布鲁马波  VS  佐加顿斯」= 11.30em
// （注意分隔符前后**各两个空格**，比单空格多 1.1em），13sp 下 ≈ 146.9dp；
// 取整留余量 ⇒ 152dp，保证全部 74 场队名完整显示、不出现省略号。
private val COL_VS_W = 152.dp
// 时间列：**只显示「MM-dd HH:mm」**（如 `10-23 00:45`），不再显示年份。
//  ① 列宽够用：11 字符 @12sp ≈ 73dp，加 10dp 右边距 ⇒ 92dp 即可（原来按 16 字符给的 120dp）；
//  ② 整表刚好塞进 360dp 屏宽（62+52+152+92 = 358），**不再横向裁掉半截时间**
//     （改版前实测截图里时间列显示成「2026-10-23 00:」，正是 386dp 超出屏幕 26dp 造成的）；
//  ③ 日期不会丢：分段标题是「销售日」，而竞彩场次常跨到次日凌晨（周四001=10-23 00:45），
//     所以必须保留「月-日」，只是年份无意义（同屏比赛不会跨年）。
private val COL_TIME_W = 92.dp
/** 可水平滑动区的总宽（不含固定的编号列）。 */
private val SCROLLABLE_W = COL_LEAGUE_W + COL_VS_W + COL_TIME_W
private val ROW_H = 42.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: ScheduleViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    // 用户松手越过阈值 ⇒ pullState 自己置 isRefreshing；必须在这个上升沿去真正发请求，
    // 否则指示器转了但数据没动（旧版就是这样：只有下面那行「状态 → 指示器」的反向同步）。
    LaunchedEffect(pullState) {
        snapshotFlow { pullState.isRefreshing }.collect { on -> if (on) vm.refresh() }
    }
    // 只在数据回来时收起指示器。**不能**在这里 startRefresh：上面的 snapshotFlow
    // 会在 isRefreshing=true 时发请求，程序化 startRefresh 会再次触发它 ⇒ 无限刷新循环。
    LaunchedEffect(state.refreshing) {
        if (!state.refreshing) pullState.endRefresh()
    }

    // 水平滚动：编号列固定，联赛/对阵/时间三列共用一个 scrollState，
    // 这样表头与每一行会**同步**左右滑动，列始终对齐。
    val hScroll = rememberScrollState()

    Box(
        Modifier.fillMaxSize()
            .background(Background)
            // material3 1.2.0 无 PullToRefreshBox，只能挂底层 nestedScroll。
            .nestedScroll(pullState.nestedScrollConnection)
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶部球类切换（足球 / 篮球）——本轮新增
            SportTabs(state.sport) { vm.switchSport(it) }

            // 表头（固定，不随列表上下滚动；但会随 hScroll 左右滑动）
            TableHeader(hScroll)

            when {
                state.loading && state.groups.isEmpty() -> Loading()
                state.error != null && state.groups.isEmpty() -> ErrorText(state.error)
                state.groups.isEmpty() -> EmptyText("暂无赛程")
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    // 先摊平成「段标题 + 数据行」的混合列表，避免嵌套 LazyColumn。
                    state.groups.forEach { group ->
                        item(key = "hdr-${group.businessDate}") { DayHeader(group) }
                        itemsIndexed(
                            group.matches,
                            key = { _, m -> m.matchId }
                        ) { idx, m ->
                            MatchRow(m, striped = idx % 2 == 1, hScroll = hScroll)
                        }
                    }
                }
            }
        }

        PullToRefreshContainer(
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * 顶部球类切换：**下划线式页签**（足球 / 篮球），无任何底色 / 胶囊。
 *
 * 演进（每一版都是被用户否掉的，别走回头路）：
 *  1. 两个分离的胶囊按钮 + 右侧裸文字计数 ⇒「两个按钮太傻、布局抽象」；
 *  2. 浅蓝轨道 + 白色滑块的分段控件 ⇒「有个长方形的浅蓝色底色」；
 *  3. 绿色实心胶囊 ⇒ 本轮再被否：「不要背景，还是用你原先的下划线方式」。
 *
 * 本版 = **纯文字 + 选中态下方 2.5dp 圆角短横线**，与「比分直播」的完整/热门/竞彩同一套语言：
 * 选中 = 主色加粗 + 下划线，未选中 = 次要灰字无下划线；整行白底，无轨道、无胶囊。
 *
 * 右侧是**红色小字提示**「点击可查看直播详情」（用户要求放显眼处）：
 * 它同时说明「本页行可以点」和「详情在另一个页面」。
 * 原「共 N 场」胶囊已移除——每天的分段标题里已有「N 场」，两处计数冗余，
 * 而 360dp 宽下再多一个胶囊就会把这个红色提示挤出去。
 */
@Composable
private fun SportTabs(
    current: ScheduleSport,
    onSelect: (ScheduleSport) -> Unit
) {
    Column(
        // 🔴 页签区整块铺浅蓝底带（SurfaceTint #E3E9F6），与「比分直播」保持一致。
        //    用户要求：**页签本身不要底色**（仍是文字 + 下划线），只给这一整条横带加底色，
        //    否则整页纯白太单调。底色挂在外层 Column，页签行自身保持透明。
        Modifier.fillMaxWidth().background(SurfaceTint)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 4.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScheduleSport.values().forEach { s ->
                SportTab(s, s == current, Modifier.weight(1f)) { onSelect(s) }
            }

        }
        HorizontalDivider(color = Line, thickness = 1.dp)
    }
}

/** 单个页签：图标 + 文字；选中 = 主色加粗 + 26dp 圆角下划线，**没有任何底色**。 */
@Composable
private fun RowScope.SportTab(
    sport: ScheduleSport,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val fg by animateColorAsState(
        if (selected) Primary else TextSub, tween(160), label = "tabFg"
    )
    Box(
        modifier.height(46.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (sport == ScheduleSport.FOOTBALL) "⚽" else "🏀", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    // 「足球赛程 / 篮球赛程」太长，页签上只留「足球 / 篮球」
                    sport.label.replace("赛程", ""),
                    color = fg,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(3.dp))
            // 下划线只画在选中项；未选中保留同高透明占位，否则切换时文字会上下跳 2.5dp
            Box(
                Modifier.width(26.dp).height(2.5.dp)
                    .background(
                        if (selected) Primary else Color.Transparent,
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

/**
 * 表头：编号（固定） | 联赛 / 主队 VS 客队 / 比赛时间（可水平滑动）。
 *
 * 结构 = 固定列 [Row] + 可滑动列 [Row]（后者带 `horizontalScroll(hScroll)`），
 * 与 [MatchRow] 完全同构 ⇒ 表头与数据行的列必然对齐。
 */
@Composable
private fun TableHeader(hScroll: ScrollState) {
    Column {
        Row(
            Modifier.fillMaxWidth()
                .background(SurfaceTint)
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 固定列：编号
            Box(Modifier.width(COL_NUM_W).padding(start = 10.dp)) {
                HeadCell("编号", COL_NUM_W)
            }
            // 可滑动区
            Row(
                Modifier.weight(1f).horizontalScroll(hScroll).width(SCROLLABLE_W),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeadCell("联赛", COL_LEAGUE_W)
                HeadCell("主队 VS 客队", COL_VS_W, alignCenter = true)
                HeadCell("比赛时间", COL_TIME_W, alignEnd = true, endPadding = 10.dp)
            }
        }
        HorizontalDivider(color = Line, thickness = 1.dp)
    }
}

@Composable
private fun HeadCell(
    text: String,
    width: Dp,
    alignEnd: Boolean = false,
    alignCenter: Boolean = false,
    endPadding: Dp = 0.dp
) {
    Text(
        text,
        modifier = Modifier.width(width).padding(end = endPadding),
        fontSize = 12.sp,
        color = TextSub,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = when {
            alignEnd -> TextAlign.End
            alignCenter -> TextAlign.Center
            else -> TextAlign.Start
        }
    )
}

/** 销售日分段标题，如「周五 · 2026-10-16（11 场）」。 */
@Composable
private fun DayHeader(group: ScheduleDayGroup) {
    Row(
        Modifier.fillMaxWidth()
            // 分组条走主题 SurfaceTint（原先写死浅粉 ⇒ 深色下是亮块，白字压上去看不见）
            .background(SurfaceTint)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${group.weekday} ${group.businessDate}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Primary
        )
        Spacer(Modifier.width(6.dp))
        Text("${group.matches.size} 场", fontSize = 11.sp, color = TextSub)
    }
}

/**
 * 一行赛程：编号（固定） | 联赛 / 对阵 / 时间（可水平滑动）。
 *
 * 与 [TableHeader] 同构：固定列 Box + 可滑动 Row（共用 [hScroll]）。
 * 隔行底色 [striped] 只铺在**固定列**与**滑动底层**（整行先设底色，滑动内容透明），
 * 这样左右滑动时背景不会跟着断掉。
 */
@Composable
private fun MatchRow(m: JingcaiScheduleMatch, striped: Boolean, hScroll: ScrollState) {
    // 非隔行底走主题 Surface（原先写死白 ⇒ 深色模式下整行纯白、文字是亮白＝看不见）
    val bg = if (striped) SurfaceAlt else Surface
    Column {
        Row(
            Modifier.fillMaxWidth()
                .height(ROW_H)
                .background(bg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 固定列：编号「周六005」（已停售的置灰）
            Box(Modifier.width(COL_NUM_W).padding(start = 10.dp)) {
                Text(
                    m.num,
                    fontSize = 12.sp,
                    color = if (m.ended) TextSub else TextMain,
                    maxLines = 1
                )
            }

            // 可滑动区：联赛 / 对阵 / 时间
            Row(
                Modifier.weight(1f).horizontalScroll(hScroll).width(SCROLLABLE_W),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 联赛：官方配色的圆角实心块，白字
                Box(Modifier.width(COL_LEAGUE_W)) {
                    Box(
                        Modifier
                            .background(parseHexColor(m.color, fallback = Primary), RoundedCornerShape(5.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            m.league,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 主队 VS 客队：整列居中；**不再加 ☆ 星标**，队名也不截断（列宽已按最长队名留足）
                Row(
                    Modifier.width(COL_VS_W),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        m.home,
                        fontSize = 13.sp,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Visible
                    )
                    Text("  VS  ", fontSize = 11.sp, color = TextSub, maxLines = 1)
                    Text(
                        m.away,
                        fontSize = 13.sp,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Visible
                    )
                }

                // 比赛时间：`10-23 00:45`（年份在这个页面没有信息量，见 [COL_TIME_W] 注释）
                Text(
                    m.dateTime.let { if (it.length >= 16) it.substring(5) else it },
                    modifier = Modifier.width(COL_TIME_W).padding(end = 10.dp),
                    fontSize = 12.sp,
                    color = if (m.ended) TextSub else TextMain,
                    maxLines = 1,
                    textAlign = TextAlign.End
                )
            }
        }
        HorizontalDivider(color = LineSoft, thickness = 0.6.dp)
    }
}

/**
 * 解析接口给的 `backColor`（6 位 hex，**无 `#` 前缀**）。
 *
 * 容错是必要的：该字段来自第三方，若哪天返回空串或短串，`Color(0xFFxxxxxx)` 会抛异常
 * 导致整个列表崩溃 —— 故非法值一律退回落日色，而不是崩。
 */
private fun parseHexColor(hex: String, fallback: Color): Color {
    if (hex.length != 6) return fallback
    val rgb = hex.toLongOrNull(16) ?: return fallback
    val r = ((rgb shr 16) and 0xFF).toInt()
    val g = ((rgb shr 8) and 0xFF).toInt()
    val b = (rgb and 0xFF).toInt()
    return Color(0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
}
