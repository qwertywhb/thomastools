package com.example.jingcai.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.jingcai.data.model.BbDayGroup
import com.example.jingcai.data.model.BbJcOdds
import com.example.jingcai.data.model.BbLiveMatch
import com.example.jingcai.data.model.BbOddSide
import com.example.jingcai.data.model.bbDayItemKey
import com.example.jingcai.data.model.bbSectionItemKey
import com.example.jingcai.ui.BbLiveViewModel
import com.example.jingcai.ui.EmptyText
import com.example.jingcai.ui.ErrorText
import com.example.jingcai.ui.Loading
import com.example.jingcai.ui.theme.LeagueColors
import com.example.jingcai.ui.theme.Down
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.SurfaceTint
import com.example.jingcai.ui.theme.TextMain
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Up
import com.example.jingcai.ui.theme.parseHexColorOrNull

/**
 * 比分直播 → **篮球**（数据源与 https://www.188bifen.com/lanqiubifen.htm 篮球页同源，
 * 字段语义见 [com.example.jingcai.data.model.BbLiveMatch]）。
 *
 * 只显示**竞彩篮球**（源数据 256 场篮球里，每天约 6~19 场带竞彩编号）。
 *
 * 布局：**三行式行内信息**（不是横向表格）。
 * ```
 * 周三309   NBA   10:00                                  完赛
 * 勇士                105 : 127                湖人
 * 分节  一33-34 二19-32 三16-36 四37-25  半52-66
 * ```
 *  - 第一行：竞彩编号（蓝）+ 联赛（源站色胶囊）+ 开赛时间 + 右对齐状态；
 *  - 第二行：主队 · 大号比分 · 客队（篮球比分是三位数，比分列单独占宽，绝不和队名抢宽度）；
 *  - 第三行：源站 `[20]` 的分节比分 + 半场比分（已开赛才显示）。
 *
 * 🔴 为什么不用 188bifen 那种「编号/联赛/时间/主队/客队/状态」单行横向表格：
 *    手机 360dp 下，五个固定列吃掉后两队名只剩 ~65dp，「伊斯坦布尔」只能显示成「伊斯坦…」，
 *    而三位数比分还会把邻居挤掉一位（实测「105-127」显示成「105-12」）。改成三行后
 *    队名有 ~135dp、比分独享 78dp，信息一个不少且不用横向滑动。
 */
@Composable
fun BbLiveContent(
    vm: BbLiveViewModel,
    onOpenDetail: (url: String, title: String) -> Unit,
    /** 只看这些竞彩编号 / 队名对的场次（投注记录 → 相关场次直播）；null = 不筛选。 */
    onlyNums: Set<String>? = null,
    onlyTeams: List<Pair<String, String>>? = null
) {
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()

    // 过滤后的分区（无筛选时原样返回）
    val sections = remember(state.sections, onlyNums, onlyTeams) {
        state.sections.filterTicketSections(onlyNums, onlyTeams)
    }
    val filtering = onlyNums != null || onlyTeams != null

    // 5 秒/6 秒 tick 的开关已改由 MainActivity 的 ProcessLifecycle（app 前台）统一驱动；
    // 这里只负责「回到本页立即整页刷新」。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.fullRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize()) {
        // 已按用户要求去掉「共 N 场比赛」条（与足球页一致）。
        when {
            // 与足球页一致：筛选模式下不弹「暂无」文案（数据没回来时只留空，别闪一句废话）
            sections.isEmpty() && filtering -> Unit
            state.loading && state.all.isEmpty() -> Loading()
            state.error != null && state.all.isEmpty() -> ErrorText(state.error)
            state.all.isEmpty() -> EmptyText("暂无竞彩篮球比分")
            sections.isEmpty() -> EmptyText("暂无竞彩篮球比分")
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                sections.forEachIndexed { si, section ->
                    // 分区标题：未结束区不显示，只有「已结束 / 延期·异常」才有
                    if (section.title.isNotEmpty()) {
                        item(key = bbSectionItemKey(si, section)) { BbSectionHeader(section.title) }
                    }
                    section.groups.forEach { group ->
                        // 🔴 key 必须带**分区序号**（见 bbDayItemKey 注释）：
                        //    同一天会同时出现在「进行中」和「已结束」两个分区，
                        //    只用 dateLabel 会生成重复 key ⇒ LazyColumn 抛异常 ⇒ 篮球页闪退。
                        item(key = bbDayItemKey(si, group)) { BbDayHeader(group) }
                        itemsIndexed(group.matches, key = { _, m -> m.matchId }) { idx, m ->
                            BbRow(
                                m = m,
                                odds = state.odds[m.jcNo],
                                striped = idx % 2 == 1,
                                onClick = {
                                    val url = m.zhibo8Url
                                    if (url.isNotEmpty()) onOpenDetail(url, "${m.home} VS ${m.away}")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 比分列固定宽：`105 : 127` 这种三位数比分的专属宽度。 */
private val BB_SCORE_W = 78.dp
/**
 * 元信息行高度。
 * 🔴 必须**大于**联赛标签的高度（18dp），否则标签顶满整行、上下没有余量，
 *    看上去就是「编号 / 标签 / 时间没对齐」（用户反馈过两次）。22dp ⇒ 标签上下各留 2dp。
 */
private val BB_ROW_TOP_H = 22.dp
private val BB_ROW_TEAM_H = 26.dp

/**
 * 元信息行三个元素的统一样式。
 * 🔴 `includeFontPadding = false` 是垂直居中的关键：Compose 默认会加字体上下留白，
 *    10sp 的中文/数字光学中心会整体下移 1~2dp，和已经去掉留白的联赛标签对不齐。
 */
private val BB_META_STYLE = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeight = 13.sp
)

/** 分区标题（`已结束` / `延期·异常`）：居中小字灰底条，与 m.188bifen 篮球页的「已结束」一致。 */
@Composable
private fun BbSectionHeader(title: String) {
    Box(
        Modifier.fillMaxWidth().background(SurfaceAlt).padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title, fontSize = 11.sp, color = TextSub, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider(color = Line, thickness = 0.6.dp)
}

@Composable
private fun BbDayHeader(group: BbDayGroup) {
    Row(
        // 分组条走主题 SurfaceTint（原先写死浅粉 ⇒ 深色下是亮块，配 TextMain 亮白字＝看不清）
        Modifier.fillMaxWidth().background(SurfaceTint).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(group.dateLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMain)
        Spacer(Modifier.width(6.dp))
        Text("${group.matches.size} 场竞彩", fontSize = 11.sp, color = TextMain)
    }
    HorizontalDivider(color = Line, thickness = 0.6.dp)
}

@Composable
private fun BbRow(m: BbLiveMatch, odds: BbJcOdds?, striped: Boolean, onClick: () -> Unit) {
    val bg = if (striped) SurfaceAlt else Surface
    Column(Modifier.fillMaxWidth().background(bg).clickable { onClick() }.padding(vertical = 5.dp)) {
        // ① 元信息行：编号 · 联赛 · 时间（全部**左对齐**串排） ……… 状态（右）
        //    ⚠️ 曾经做成「编号靠左 + 联赛/时间整体居中 + 状态靠右」的三段式（用户先要求居中），
        //    实测居中后左侧空一大块、右侧又贴着状态，横向重心散开很难看 ⇒ 用户改回左对齐。
        //    这里用 Row + weight(1f) 撑开：左侧三元素自然贴左，状态被推到最右。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(BB_ROW_TOP_H),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                m.jcNo,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828),
                maxLines = 1,
                style = BB_META_STYLE
            )
            Spacer(Modifier.width(6.dp))
            LeagueChip(m.league, m.color)
            Spacer(Modifier.width(6.dp))
            Text(
                m.timeText.ifEmpty { "-" },
                fontSize = 10.sp,
                color = TextSub,
                maxLines = 1,
                style = BB_META_STYLE
            )
            Spacer(Modifier.weight(1f))
            val live = m.isLive
            if (m.statusText.isNotEmpty()) {
                Text(
                    m.statusText,
                    fontSize = 10.sp,
                    fontWeight = if (live) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        live -> Up
                        m.status == 9 || m.status == 0 -> Up
                        else -> TextSub
                    },
                    maxLines = 1,
                    style = BB_META_STYLE
                )
            }
        }
        // ② 队名 · 比分 · 队名
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(BB_ROW_TEAM_H),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 主队：队名 + 「主」标记（+ 排名），整体贴到比分左侧
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    m.home,
                    // weight(fill = false)：队名按内容自适应，超长时才收缩（否则标记会被挤出屏外）
                    Modifier.weight(1f, fill = false),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
                BbSideTag("主")
                BbRankTag(m.homeRank)
            }
            Box(Modifier.width(BB_SCORE_W), contentAlignment = Alignment.Center) {
                Text(
                    if (m.started) "${m.homeScore} : ${m.awayScore}" else "VS",
                    fontSize = if (m.started) 15.sp else 11.sp,
                    fontWeight = if (m.started) FontWeight.Bold else FontWeight.Normal,
                    color = if (m.started) Up else TextSub,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
            // 客队：队名 + 「客」标记（+ 排名），从左往右紧跟比分
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    m.away,
                    Modifier.weight(1f, fill = false),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start
                )
                BbSideTag("客")
                BbRankTag(m.awayRank)
            }
        }
        // ③ 分节比分（已开赛才显示）
        if (m.started && m.homeQuarters.isNotEmpty()) {
            Text(
                "分节  " + quarterLine(m),
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 1.dp),
                fontSize = 9.sp,
                color = TextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // ④ 竞彩三玩法赔率：**默认折叠**成一薄条，点开才展开（用户要求）。
        //    说明：折叠条本身也带一行极简摘要（让分线 / 大小线），既省高度又不至于「整块消失、
        //    不知道这行有没有盘口」；完整三项赔率仍在展开后才给。
        if (odds != null && odds.any) {
            BbOddsSection(odds, m.home, m.away)
        }
    }
    HorizontalDivider(color = Line, thickness = 0.6.dp)
}

/**
 * 队名后面的「主 / 客」小标记。
 *
 * ## 为什么必须有
 * 篮球的主客顺序在**两个数据源里是相反的**（体彩与球米宝实测 NBA 全场次相反，
 * 见 [BbJcOdds.flippedFor] 注释）—— 用户看名单时无法判断谁主场，尤其下注前要看主客。
 * 直接在队名后贴一个字，成本最低、歧义最小。
 *
 * ## 为什么不做成彩色胶囊
 * 同一行已经有「红色比分 + 红色竞彩编号」，再加两块彩色标签会三处抢焦点。
 * 这里用与表头同档的浅底 + 次要灰字：远看是队名的后缀，近看才读到「主/客」。
 */
@Composable
private fun BbSideTag(text: String) {
    Box(
        Modifier.padding(start = 3.dp).height(13.dp)
            .background(SurfaceTint, RoundedCornerShape(2.dp))
            .padding(horizontal = 2.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 8.5.sp,
            lineHeight = 9.sp,
            color = TextSub,
            maxLines = 1,
            style = BB_META_STYLE
        )
    }
}

/**
 * 排名角标 `[3]`（无排名则整块不画）。
 *
 * 🔴 当前**篮球源站不提供排名**（见 [BbLiveMatch.homeRank]），所以篮球列表里基本看不到它；
 *    这里保留渲染路径，是为了「源站给了就显示」，以及将来接积分榜数据源时只改解析层。
 *    样式与足球页 [RankTag] 一致（方括号 + 7.5sp 灰），两边看起来是同一个东西。
 */
@Composable
private fun BbRankTag(rank: String) {
    if (rank.isEmpty()) return
    Text(
        "[$rank]",
        Modifier.padding(start = 2.dp),
        fontSize = 7.5.sp,
        lineHeight = 8.sp,
        color = TextSub,
        maxLines = 1,
        style = BB_META_STYLE
    )
}

/**
 * 竞彩赔率区：**默认折叠**成一根薄条，点一下才展开完整三玩法（用户要求）。
 *
 * ## 为什么折叠
 * 一张卡最多 4 行（编号 / 队名比分 / 分节 / 三行赔率），一屏只能放三四场；
 * 大多数人扫比分时并不看盘口。折叠后每场省下约 40dp，同屏场次几乎翻倍。
 *
 * ## 为什么折叠条还留一行摘要
 * 如果折起来只剩「竞彩 ▾」，用户没法判断这行到底有没有盘口，只能一条条点开试。
 * 摘要只放**两个盘口线**（让分线 / 大小分线），不带赔率数字，既够判断又不占宽度。
 *
 * ## 🔴 主客口径
 * 体彩与球米宝对篮球的主客顺序相反（实测 NBA 全场次相反）。摘要里的让分线按
 * [BbJcOdds.flippedFor] 先判断要不要翻面，再用**队名**标注（展开后才显示两队名字）。
 *
 * ## 交互
 * 点薄条展开/收起，**不会**冒泡到整行的「打开直播详情」—— 收起状态也能一眼看出是收起还是展开。
 * 展开后的三行赔率沿用原来的点击语义（点哪都算行点击 → 详情），不再另设交互。
 */
@Composable
private fun BbOddsSection(o: BbJcOdds, rowHome: String, rowAway: String) {
    // 按竞彩编号记住展开状态：列表滚出屏幕再滚回来仍保持（rememberSaveable + LazyColumn 的 key）
    var expanded by rememberSaveable(o.num) { mutableStateOf(false) }
    val flip = o.flippedFor(rowHome, rowAway)
    val hdcLine = (if (flip) o.hdcLineAway else o.hdcLine).trim()
    val hiloLine = o.hiloLine.trim().trimStart('+')
    val summary = buildList {
        if (hdcLine.isNotEmpty()) add("让分 $hdcLine")
        if (hiloLine.isNotEmpty()) add("大小 $hiloLine")
    }.joinToString(" · ").ifEmpty { "胜平负赔率" }

    Column(
        Modifier.fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(SurfaceTint.copy(alpha = 0.55f))
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "竞彩",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828),
                maxLines = 1
            )
            Spacer(Modifier.width(6.dp))
            Text(
                summary,
                Modifier.weight(1f),
                fontSize = 9.sp,
                color = TextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (expanded) "收起 ▴" else "展开 ▾",
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFC62828),
                maxLines = 1
            )
        }
        if (expanded) {
            BbOddsRows(o, rowHome, rowAway, flip, hiloLine)
        }
    }
}

/**
 * 展开态的三行赔率：**让分 / 大小 / 胜负**，每行左右各一个「选项 + 赔率」。
 *
 * ## 🔴 两侧**用队名**而不是「主/客」
 * 体彩与球米宝对篮球的主客顺序并不一致（实测 NBA 全场次相反、NBL 一致）。也就是说
 * 比分行的左队到底是主还是客，两家给不出统一答案 —— 写死「主 / 客」必然在一半的场次上标反，
 * 而「让 8.5 分挂在受让那队身上」这种错误一眼就能看出假。用队名则两种口径下都不会错：
 * 谁拿到哪条线、哪个赔率，写的是名字本身。
 *
 * ## 主客翻转
 * [flip]（由 [BbJcOdds.flippedFor] 算出）为真时把两侧的线/赔率对调。
 *
 * ## 赔率配色
 * 涨红 / 跌绿（与 App 其它赔率处一致），无变动用正文墨色。未开售显示「—」占位。
 */
@Composable
private fun BbOddsRows(o: BbJcOdds, rowHome: String, rowAway: String, flip: Boolean, hiloLine: String) {
    val hdcLineLeft = if (flip) o.hdcLineAway else o.hdcLine
    val hdcLineRight = if (flip) o.hdcLine else o.hdcLineAway
    val hdcLeft = if (flip) o.hdcAway else o.hdcHome
    val hdcRight = if (flip) o.hdcHome else o.hdcAway
    val mnlLeft = if (flip) o.mnlAway else o.mnlHome
    val mnlRight = if (flip) o.mnlHome else o.mnlAway

    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 3.dp)) {
        BbOddsRow("让分", withLine(rowHome, hdcLineLeft), hdcLeft, withLine(rowAway, hdcLineRight), hdcRight)
        BbOddsRow("大小", withLine("大", hiloLine), o.hiloOver, withLine("小", hiloLine), o.hiloUnder)
        BbOddsRow("胜负", rowHome, mnlLeft, rowAway, mnlRight)
    }
}

/** `主` + `-8.50` ⇒ `主 -8.50`；线为空就只留标签，不出现空括号/多余空格。 */
private fun withLine(label: String, line: String): String =
    if (line.isBlank()) label else "$label $line"

@Composable
private fun BbOddsRow(
    label: String,
    leftText: String,
    left: BbOddSide,
    rightText: String,
    right: BbOddSide
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 9.sp,
            color = TextSub,
            maxLines = 1,
            modifier = Modifier.width(24.dp)
        )
        BbOddsCell(leftText, left, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        BbOddsCell(rightText, right, Modifier.weight(1f))
    }
}

@Composable
private fun BbOddsCell(text: String, side: BbOddSide, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            fontSize = 9.5.sp,
            color = TextSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            if (side.sellable) String.format("%.2f", side.odds) else "—",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                !side.sellable -> TextSub
                side.trend > 0 -> Up
                side.trend < 0 -> Down
                else -> TextMain
            },
            maxLines = 1
        )
        if (side.sellable && side.trend != 0) {
            Text(
                if (side.trend > 0) "↑" else "↓",
                fontSize = 8.sp,
                color = if (side.trend > 0) Up else Down,
                maxLines = 1
            )
        }
    }
}

/** 联赛胶囊：底色用源站色（`leagues[id][5]`），文字色按底色亮度自动取深/浅——浅蓝底上的白字看不清。 */
@Composable
private fun LeagueChip(name: String, colorHex: String) {
    val color = parseHexColorOrNull(colorHex)
    val bg = color ?: LeagueColors.solid(name)
    // 🔴 高度写死 18dp + includeFontPadding=false：只给 `vertical = 1.dp` 的内边距时，
    //    Compose 默认的字体上下留白（includeFontPadding）会把 9sp 的中文挤到框底，
    //    看起来「文字没垂直居中 / 下沿被切掉」（用户反馈的原图就是这样）。
    Box(
        Modifier.height(18.dp).clip(RoundedCornerShape(4.dp)).background(bg)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name,
            color = LeagueColors.textOn(bg),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            style = TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeight = 11.sp
            )
        )
    }
}

/** 分节比分文本：`一33-34  二19-32  三16-36  四37-25  半52-66`（加时用「加」）。 */
private fun quarterLine(m: BbLiveMatch): String {
    val labels = arrayOf("一", "二", "三", "四", "加", "加")
    val sb = StringBuilder()
    m.homeQuarters.forEachIndexed { i, h ->
        if (i > 0) sb.append("  ")
        sb.append(labels.getOrElse(i) { "" }).append(h).append("-").append(m.awayQuarters.getOrElse(i) { 0 })
    }
    if (m.homeHalf > 0 || m.awayHalf > 0) {
        sb.append("  半").append(m.homeHalf).append("-").append(m.awayHalf)
    }
    return sb.toString()
}
