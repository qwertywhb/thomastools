package com.example.jingcai.ui.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jingcai.AppNavState
import com.example.jingcai.data.model.LiveDayGroup
import com.example.jingcai.data.model.LiveMatch
import com.example.jingcai.ui.BbLiveViewModel
import com.example.jingcai.ui.EmptyText
import com.example.jingcai.ui.ErrorText
import com.example.jingcai.ui.LiveDetailHint
import com.example.jingcai.ui.Loading
import com.example.jingcai.ui.LiveSport
import com.example.jingcai.ui.LiveTab
import com.example.jingcai.ui.LiveViewModel
import com.example.jingcai.ui.theme.Background
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.SurfaceTint
import com.example.jingcai.ui.theme.TextMain
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.Up
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.runtime.key
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.jingcai.data.repository.UserPrefs
import com.example.jingcai.ui.live.GoalEventBus
import com.example.jingcai.ui.live.GoalEvent
import com.example.jingcai.ui.live.PopupItem
import com.example.jingcai.ui.live.LiveScreenVisibleState
import com.example.jingcai.ui.live.BackgroundGoalWatcher
import com.example.jingcai.ui.live.GoalNotifier
import com.example.jingcai.ui.live.PERM_POST_NOTIFICATIONS
import com.example.jingcai.ui.theme.Flat

/**
 * 比分直播（参考 live.titan007.com 重做）。
 *
 * 顶部三个子页签：**完整 / 热门 / 竞彩**（[LiveTab]，完整为默认）。
 * 三 tab 同源（同一份 `bfdata_ut.js` 全量数据，仅过滤条件不同）：
 *  - 完整 = 所有联赛；热门 = 热门联赛（`B[i][10] != 0`）；竞彩 = titan007 竞彩场次。
 * 每行只显示这部分数据（不要让球/胜平负；**角球、大小球、「析」**并到比分下方的元信息行）：
 *
 * ```
 * ┌──────┬──────┬──────┬───────────────────┐
 * │ 联赛  │ 时间  │ 状态  │ 主队  比分  客队    │   ← 主行（宽度自适应，永远一屏）
 * └──────┴──────┴──────┴───────────────────┘
 *      角旗 5-3  大小 0.92 2.5 0.88   析   ← 元信息行（固定 20dp）
 * ```
 *  - **元信息行**见 [MetaLine]：角球取自 `detail_ut.js` 的 `tc`（code 6 = 角球，
 *    已逐场与详情页比对确认），唯一不来自 bfdata 的字段（bfdata 里没有角球）；
 *    ⚠️ 同一份 `tc` 里的 code 45 是**半场角球**，现已不显示（用户要求下线）；
 *    角球前缀由「角球」二字改成**角旗图标**（见 [CornerIcon]）。
 *    大小球来自 `ch_goalbf3.xml`（竞彩场次再经 dx101 覆盖一次），空值显示「-」。
 *    🔴 两组数字**都不用红**（曾试过红色，真机上红点太多，用户要求改回）：角球数走「主文字色」、
 *    水位走「次灰」、盘口加粗走「主文字色」，红色只留给比分与走时。
 *  - **时间格**默认显示开赛时间（HH:mm，[LiveMatch.time]）；**官方举牌补时公布后**改显「补N'」
 *    （N＝手机版 livechange.txt 的 [LiveMatch.injuryTime]，未公布则时间列仍显示开赛时间）。
 *    🔴 颜色是**主文字色、不再是红**（用户要求「时间那列不要用红色」）：这一列是静态值，
 *    红色在整张表里只代表「会自己变的东西」，见 [TimeCell]。
 *  - **状态格**按 [LiveMatch.state] 显示：未开始→**留空**；进行中→「X」（**红色**加粗 +
 *    紧随其后的那颗每秒硬闪的黑色逗号，见 [BlinkComma]）——那颗逗号**就是撇号**，
 *    文案里不再自带 `'`（自带就是「一红一黑两个逗号」的重影），闪时是「79'」、灭时是「79」；
 *    X 来自 bfdata 的半场开始时刻，由服务器时间校正，避免整体偏慢约 1 分钟）；中场→「中」；
 *    补时→「45+X / 90+X」（X＝elapsedMin-45/90，与时间列「补N'」分工：时间列是官方举牌值、状态列是真实已过分钟，参照站截图）；
 *    完场→「完场」（灰）；延期/腰斩等（state < 0，如实测 -14）→「推迟」（琥珀色）。
 *  - **比分**每次 tick 由 change.xml 合并 ⇒ 同步（覆盖全部进行中比赛，竞彩 + 非竞彩）。
 *
 * 刷新：页面 RESUMED 时由 [LiveViewModel] 内部每 5 秒静默 tick；首次进入 / 切回 / 回前台立即整页刷新；
 * 另支持下拉刷新。切走或退后台即停 tick（省流量）。
 */

// ===== 列宽（dp） =====
// 🔴 本机实测：1260px / density 560dpi(=3.5) ⇒ **逻辑屏宽只有 360dp**。
//
// 【本轮布局重构：整表不再横向滚动】
// 旧版把「大小球」做成第 6 个表格列（108dp），于是内容宽 468dp > 屏宽 360dp，
// 整张表必须左右滑才能看到大小球 —— 而大小球恰恰是竞彩玩家最需要的列之一。
// 现在改为：**主行只放「一眼要看完」的五段**（★/联赛/时间/状态/对阵+比分），
// 比分区用 weight 吃满剩余宽度（任意屏宽自适应，不再有硬编码的 226dp 上限）；
// 大小球与角球一起下移到**主行下面那条元信息行**（[MetaLine]）。
//
// 左四列合计 24+42+38+34 = 138dp ⇒ 360dp 屏上给对阵区 222dp（旧版 226dp，基本持平）。
/**
 * 联赛色块宽度。
 *
 * 🔴 42dp 只够 4 字（9sp）—— 实测「墨西女超附」「亚的斯城杯」「埃塞高女联」「哥伦比亚乙 B」
 * 这些 5 字名在 42dp 下全部折成「前四字 + 末字」两行，同一列里时而一行时而两行，扫视很乱。
 * 46dp + [LeagueBlock] 里「≥5 字降到 8.5sp」⇒ 5 字名单行（5×8.5=42.5dp），4 字名仍用 9sp 保持清晰。
 * 代价是从比分区拿走 4dp（222 → 218dp），队名折行阈值基本不变。
 */
private val COL_LEAGUE_W = 46.dp
private val COL_TIME_W = 38.dp       // 比赛时间（开赛 HH:mm，所有行都显示）
private val COL_STATUS_W = 34.dp     // 状态：走时/完场/中/推迟（短文本）
private val ROW_MIN_H = 52.dp
private val HEADER_H = 34.dp         // 34dp（旧 40dp）：表头只是索引，不该吃掉首屏
/** 关注列宽（左侧星标）。 */
private val COL_FOLLOW_W = 24.dp
/**
 * 元信息行（角球图标 / 大小球 / 「析」）的固定行高。
 *
 * 🔴 旧版「只有析的那一行」实测占 **30dp**（行盒 + 内边距 + 底部留白），
 *    而它往往只托着一枚 10dp 的小胶囊 ⇒ 每行白扔 30dp，一屏少看 3 场。
 *    这里显式钉死 20dp，并把三类信息并到同一行。
 */
private val META_ROW_H = 20.dp

// ===== 颜色（全局主题 token；旧版本在页面内写死 #333/#999/#F4F7FB 等浅色，整体偏淡） =====
// 用户反馈「每行数据颜色都太淡了」：文字与分隔线一律改用 Theme 里加深后的 token，
// 隔行底 [SurfaceAlt] 与表头 [SurfaceTint] 拉出一档差距，表格才有「骨架」。
// 🔴 这些 token 走 @Composable get()：中性色现在由主题的组合局部提供（支持深色），
//    顶层 val 会在类加载时把浅色值固化下来，深色模式下整张表会还是浅底。
private val C_TEXT: Color
    @Composable @ReadOnlyComposable get() = TextMain
private val C_SUB: Color
    @Composable @ReadOnlyComposable get() = TextSub
private val C_SCORE: Color
    @Composable @ReadOnlyComposable get() = Up
/**
 * 「正在踢」的状态色（走时 X' / 补时）。
 *
 * 🔴 本轮**改回红色**（用户要求「状态那列红色字体」）：它和比分都是红，但两者承担的是同一件事 ——
 *    「这场正在发生」。靠**字号**区分主次（比分 13sp、走时 11sp）+ 走时右上角那颗每秒闪动的
 *    逗号（见 [StatusCell] 的 `BlinkComma`）来点明「它在动」，不会糊成一片。
 */
private val C_TICK: Color
    @Composable @ReadOnlyComposable get() = Up
/**
 * 走时右上角那颗闪烁逗号的颜色（用户要求「黑色」）。
 *
 * 取 [TextMain] 而不是写死 `Color.Black`：亮色主题下 TextMain 就是近黑（视觉上就是黑），
 * 深色主题下它自动转浅 —— 写死纯黑的话，深底上那颗逗号直接消失，功能等于没有。
 */
private val C_TICK_INK: Color
    @Composable @ReadOnlyComposable get() = TextMain
/** 中场「中」：同上，退成次要灰（它是暂停态，不该比走时更亮）。 */
private val C_HALF: Color
    @Composable @ReadOnlyComposable get() = TextSub
/** 推迟/腰斩：琥珀色。既不是「进行中」也不是「完场」，需要一个独立语义色。 */
private val C_POSTPONED = Color(0xFFC2601A)
private val C_ROW_LINE: Color
    @Composable @ReadOnlyComposable get() = Line
private val C_STRIPE: Color
    @Composable @ReadOnlyComposable get() = SurfaceAlt
private val C_HEADER_BG: Color
    @Composable @ReadOnlyComposable get() = SurfaceTint
private val C_HEADER_LINE: Color
    @Composable @ReadOnlyComposable get() = Line
private val C_GROUP_BG: Color
    @Composable @ReadOnlyComposable get() = SurfaceTint   // 日期分段标题：比表头再深一档，滚动时层级分明
private val C_GROUP_LINE: Color
    @Composable @ReadOnlyComposable get() = Line
/**
 * 竞彩胜平负赔率子行底色：比白底深一档、比隔行底 [C_STRIPE] 浅，形成「附注」层次。
 */
private val C_SPF_BG: Color
    @Composable @ReadOnlyComposable get() = SurfaceAlt
/** 赔率数值色：深墨蓝，刻意不用红/绿 —— 涨跌色是比分的语义，赔率是静态盘口，混用会误导。 */
private val C_SPF_VAL: Color
    @Composable @ReadOnlyComposable get() = TextMain
/**
 * 赔率**变动入口**的强调色（图标 + 「变动」二字）。
 *
 * 用亮蓝：深墨蓝的 [C_SPF_VAL] 太像正文，做入口会被当成「又一串赔率」；
 * 又不能用红/绿 —— 那是比分涨跌的语义。
 */
private val C_SPF_ACCENT = Color(0xFFC62828)
/** 进球闪色：进球方队名底色（取自参考截图实测 #BBBB22，橄榄黄绿），持续 30 秒。 */
private val C_GOAL_FLASH = Color(0xFFBBBB22)
/**
 * **进球被取消**时的队名底色：浅玫红。
 *
 * 与橄榄黄共用同一套「闪 30 秒」逻辑，只换颜色——用户要求「取消进球名称底色也要变」。
 * 选浅玫红而不是正红：它要停在表格里 30 秒，正红会把整张比分表压得喘不过气。
 */
private val C_GOAL_CANCEL_FLASH = Color(0xFFEF9A9A)

// ===== 红黄牌角标（参照 live.titan007.com：队名旁边的实心小方块 + 白色/黑色数字） =====
/** 黄牌底：饱和黄（取参考截图实测值），黑字。 */
private val C_CARD_YELLOW = Color(0xFFE9B40C)
/** 黄牌数字：近黑，黄底上对比约 9:1。 */
private val C_CARD_YELLOW_INK = Color(0xFF2A2200)
/** 红牌底：正红，白字。 */
private val C_CARD_RED = Color(0xFFE13327)
/** 角标描边：统一半透明黑，浅底表格里能把方块和背景分开，又不抢色。 */
private val C_CARD_LINE = Color(0x33000000)

// ===== 页内「进球」弹窗配色 =====
// 演进：白卡（太素）→ 橙红（「红色太显眼」）→ 靛蓝紫（不够暖）→ 橙黄（太抢眼）→ **淡绿**（当前，用户指定）。
// 淡绿底同理必须配**深墨绿字**：白字在 #B9EBC8 上对比不到 1.6，实测会糊得看不清。
/** 弹窗渐变左端：极淡薄荷绿。 */
private val C_GOAL_BG1 = Color(0xFFE6F9EC)
/** 弹窗渐变右端：淡绿。比左端深一档，形成轻微层次但整体仍是「淡」。 */
private val C_GOAL_BG2 = Color(0xFFA9E7BE)
/** 主文字：深墨绿（在淡绿上对比 ≈ 10:1，久看不刺）。 */
private val C_GOAL_INK = Color(0xFF14402A)
/** 次文字（GOAL / 联赛）：中绿。 */
private val C_GOAL_INK2 = Color(0xFF3E7A56)
/**
 * 进球方队名高亮：**正红**（用户要求「用红色字体，让人好分别是谁进球」）。
 *
 * 比原来的 #C62828 更亮一档：淡绿底上 #C62828 偏暗、和深墨绿的主文字差别不够大，
 * 远看两队名几乎同色；#D32F2F 在 #E6F9EC 上对比 ≈ 4.9:1，够亮够红又不刺眼。
 * 状态栏通知用同一个红（见 `GoalAlert.NOTIF_RED_*`），两处口径一致。
 */
private val C_GOAL_HL = Color(0xFFD32F2F)

// ===== 「取消进球」弹窗配色 =====
// 与进球卡片同版式、同停留时长，**只换色系**：淡绿 → 淡玫红，一眼区分「进球」和「进球取消」。
// 不用正红/血红：取消进球是「信息更正」，不是警报。
/** 取消弹窗渐变左端：极淡玫红。 */
private val C_CANCEL_BG1 = Color(0xFFFFEAEA)
/** 取消弹窗渐变右端：淡玫红。 */
private val C_CANCEL_BG2 = Color(0xFFF7B7B7)
/** 主文字：深枣红（淡玫红底上对比 ≈ 9:1）。 */
private val C_CANCEL_INK = Color(0xFF5A1512)
/** 次文字（NO GOAL / 联赛）：中枣红。 */
private val C_CANCEL_INK2 = Color(0xFF9B4A45)
/** 被取消进球的一方队名：深红（与进球卡片的高亮同色，保持「这是同一支球队」的连续性）。 */
private val C_CANCEL_HL = Color(0xFFB3261E)

/** 页内进球卡片停留时长：6s（原来 3.5s——用户反馈「停留时间放长一点」）。 */
private const val GOAL_POPUP_STAY_MS = 6000L
/** 退场动画时长；宿主先标记 leaving 播完动画再真正移除，否则会「啪」一下消失。 */
private const val GOAL_POPUP_EXIT_MS = 280L
/**
 * 页内进球卡片的**统一尺寸**（用户要求「长度和高度保持一致」）。
 *
 * 宽度固定 306dp、高度固定 78dp：不论队名长短、有没有联赛行，所有卡片都一样大，
 * 多张叠在一起时左右边缘严格对齐，不会一张宽一张窄。
 */
private val GOAL_POPUP_W = 306.dp
private val GOAL_POPUP_H = 78.dp
/** 多张卡片之间的垂直间距（后弹出来的排在前一张**下面**）。 */
private val GOAL_STACK_GAP = 8.dp

/**
 * 设置弹窗卡片底。
 *
 * 浅色下是「浅灰蓝」（用户反馈「背景太白」——纯白卡片在浅色表格上像一块亮板）；
 * 深色下走主题 [SurfaceAlt]，否则整张卡片会变成深色页面里的一块亮板（同一句话，换个方向踩）。
 */
private val C_SHEET_BG: Color
    @Composable @ReadOnlyComposable get() = SurfaceAlt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    vm: LiveViewModel = viewModel(),
    bbVm: BbLiveViewModel = viewModel(),
    /** 点击某场**足球**比赛行 → 进入「动画直播」详情页（titanId, 主队名, 客队名）。*/
    onOpenDetail: (Long, String, String) -> Unit = { _, _, _ -> },
    /** 点击某场**篮球**比赛行 → 进入内嵌 zhibo8 的直播详情（url, 「主 VS 客」标题）。*/
    onOpenBbDetail: (String, String) -> Unit = { _, _ -> },
    /**
     * 点竞彩赔率子行右侧的「变动」图标 → 打开该场**竞彩胜平负赔率变动**页。
     *
     * 🔴 只在 [LiveMatch.spid] 非空（竞彩页确实渲染了「变」链接）时才可能被触发；
     *    非竞彩场次根本不画这个入口。
     */
    onOpenSpfChange: (LiveMatch) -> Unit = {},
    /**
     * 点角球子行里的「析」→ 打开该场**数据分析**（双方交手战绩 / 近期战绩）。
     * 数据源 `zq.titan007.com/analysis/<titanId>cn.htm`，只取用户要的那两块。
     */
    onOpenAnalysis: (Long, String, String) -> Unit = { _, _, _ -> },
    /**
     * 只看这些**竞彩编号**的场次（投注记录 → 相关场次直播）。
     * null = 不筛选，就是正常比分直播页。
     */
    onlyNums: Set<String>? = null,
    /** 队名兜底匹配（同 [onlyNums]，两者取 OR）。 */
    onlyTeams: List<Pair<String, String>>? = null,
    /** 固定球类并**隐藏顶部页签**（投注记录进入时球类由投注内容决定）。null = 允许切换。 */
    fixedSport: LiveSport? = null,
    /** 打开「我的关注」聚合页（关注的比赛 / 已投注场次 / 关注球队的下一场）。 */
    onOpenFollow: () -> Unit = {}
) {
    // 球类页签（足球 / 篮球）：与「竞彩赛程」同一套绿色胶囊选中态，切换即换整页数据源。
    var sport by rememberSaveable { mutableStateOf(fixedSport ?: LiveSport.FOOTBALL) }
    if (fixedSport != null && sport != fixedSport) sport = fixedSport
    // ===== 进球提醒设置（进球提醒 / 震动 / 进球音乐，均可在设置里开关）=====
    var showSettings by remember { mutableStateOf(false) }
    val goalAlert by UserPrefs.goalAlert.collectAsState()
    val kickoffAlert by UserPrefs.kickoffAlert.collectAsState()
    val kickoffLead by UserPrefs.kickoffLeadMin.collectAsState()
    val vibrate by UserPrefs.vibrate.collectAsState()
    val sound by UserPrefs.sound.collectAsState()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* 授权结果无需处理：未授权则状态栏通知静默不发 */ }
    // 注意：LocalContext 只能在 @Composable 作用域里读；下面那个 requestNotifPermIfNeeded 是普通函数，
    // 在它内部写 LocalContext.current 会编译失败（Unresolved reference）。必须在这里先取好。
    val ctx = LocalContext.current
    fun requestNotifPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(ctx, PERM_POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                permLauncher.launch(PERM_POST_NOTIFICATIONS)
        }
    }

    // ── 下拉刷新（足球 / 篮球各自的 VM 都能刷）───────────────────────────
    // 两个 state 各订阅一次：StateFlow 多订阅者无额外成本，外层拿不到内层 composable 的 state。
    val footState by vm.state.collectAsState()
    val bbState by bbVm.state.collectAsState()
    val refreshing = if (sport == LiveSport.FOOTBALL) footState.refreshing else bbState.refreshing
    val pullState = rememberPullToRefreshState()

    // 用户松手越过阈值 ⇒ PullToRefreshState 自己置 isRefreshing；这里在那个上升沿去发请求。
    // 🔴 不要「只在 state.refreshing 时 startRefresh」：那是把指示器当结果，请求永远不会被触发。
    LaunchedEffect(pullState, sport) {
        snapshotFlow { pullState.isRefreshing }.collect { on ->
            if (on) {
                if (sport == LiveSport.FOOTBALL) vm.refresh() else bbVm.refresh()
            }
        }
    }
    // 同上：只在数据回来时收起指示器，绝不程序化 startRefresh（会与上面的 snapshotFlow 形成刷新循环）。
    LaunchedEffect(refreshing) {
        if (!refreshing) pullState.endRefresh()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            // material3 1.2.0 无 PullToRefreshBox，只能挂底层 nestedScroll（与赛程/比赛页同一套）
            .nestedScroll(pullState.nestedScrollConnection)
    ) {
        // 标记本页可见：决定进球**是否额外**弹页内卡片（状态栏通知始终都会发）
        DisposableEffect(Unit) {
            LiveScreenVisibleState.set(true)
            onDispose { LiveScreenVisibleState.set(false) }
        }
        Column(Modifier.fillMaxSize()) {
            if (fixedSport == null) SportTabs(sport) { sport = it }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (sport) {
                    LiveSport.FOOTBALL -> FootballLive(
                        vm = vm,
                        onOpenDetail = onOpenDetail,
                        onlyNums = onlyNums,
                        onlyTeams = onlyTeams,
                        hideTabBar = fixedSport != null,
                        onSettings = { showSettings = true },
                        onOpenFollow = onOpenFollow,
                        onRequestPerm = { requestNotifPermIfNeeded() },
                        onOpenSpfChange = onOpenSpfChange,
                        onOpenAnalysis = onOpenAnalysis
                    )
                    LiveSport.BASKETBALL -> BbLiveContent(
                        vm = bbVm,
                        onOpenDetail = onOpenBbDetail,
                        onlyNums = onlyNums,
                        onlyTeams = onlyTeams
                    )
                }
            }
        }
        PullToRefreshContainer(
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 页内进球动画弹窗（仅在本页组合时接收总线事件）。**垂直居中**显示（用户要求）。
        GoalPopupHost(modifier = Modifier.align(Alignment.Center))
    }

    // 进球提醒设置：居中弹窗（用户要求），只保留标题 + 四个开关 + 关闭按钮。
    if (showSettings) {
        Dialog(onDismissRequest = { showSettings = false }) {
            GoalAlertSettingsCard(
                goalAlert = goalAlert,
                kickoffAlert = kickoffAlert,
                kickoffLead = kickoffLead,
                vibrate = vibrate,
                sound = sound,
                onGoalAlert = {
                    UserPrefs.setGoalAlert(it)
                    if (it) requestNotifPermIfNeeded()
                },
                onKickoffAlert = {
                    UserPrefs.setKickoffAlert(it)
                    if (it) requestNotifPermIfNeeded()
                },
                onKickoffLead = { UserPrefs.setKickoffLeadMin(it) },
                onVibrate = { UserPrefs.setVibrate(it) },
                onSound = { UserPrefs.setSound(it) },
                onDismiss = { showSettings = false }
            )
        }
    }
}

/**
 * 进球提醒设置卡片（居中弹窗内容）——第四版：
 *
 *  - **变小**：卡片固定 264dp 宽（原先是撑满 Dialog 默认宽 ~360dp），内边距 14dp，行高 44dp；
 *  - **不再是纯白一片**：卡片底改浅灰蓝 [C_SHEET_BG]，开关行装在**一块白卡**里，
 *    形成「浅底 + 白块」的层次，白只占中间一小条；
 *  - **省高度**：图标从 46dp 大圆缩成标题左侧 16dp 小齿轮，标题左对齐，去掉所有描述文字；
 *  - 开关用 0.86 缩放（M3 默认 52×32dp 在这张卡里显得笨重）；
 *  - 底部两个按钮：左＝**息屏提醒**（未开启「开启息屏提醒」/ 已开启「关闭息屏提醒」），右＝「完成」。
 *    原来的「测试提醒」已按用户要求删除；
 *  - 「息屏提醒」行是**纯状态展示**（已开启 / 仅亮屏时，不可点，不会跳系统设置）。
 *
 * ## 可选增强（2026-11-04）
 * 「息屏提醒」不是功能前置条件，而是**增强项**：不开也能在亮屏 / 用其他应用时收到进球震动，
 * 开了才覆盖「黑屏放口袋」的场景。点「开启息屏提醒」**不会直接跳系统页**，
 * 而是先切到本卡内置的说明页（[ExplainLine] ×3 + 「暂不开启」/「去系统设置」），
 * 用户在说明页里点「去系统设置」才真正执行 [toggle]。
 * 🔴 别的手机上「一点就被甩进无障碍页、不知道在开什么」正是本次要修的问题，别把说明页删了。
 */
@Composable
private fun GoalAlertSettingsCard(
    goalAlert: Boolean,
    kickoffAlert: Boolean,
    kickoffLead: Int,
    vibrate: Boolean,
    sound: Boolean,
    onGoalAlert: (Boolean) -> Unit,
    onKickoffAlert: (Boolean) -> Unit,
    onKickoffLead: (Int) -> Unit,
    onVibrate: (Boolean) -> Unit,
    onSound: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier.width(264.dp)
            .background(C_SHEET_BG, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "进球提醒设置",
                tint = Primary,
                modifier = Modifier.width(16.dp).height(16.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text("进球提醒设置", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
        }
        // 系统级开关：状态不在本 App 的偏好里，而是「系统设置里无障碍服务是否开启」。
        // 开启动作统一走 [GoalKeepAliveService.enableOneTap]（静默优先，没权限再跳系统页）；
        // 从系统页回来时靠 ON_RESUME 刷新这里的状态。
        val c = LocalContext.current
        var keepAlive by remember { mutableStateOf(GoalKeepAliveService.isEnabled(c)) }
        // 「可选增强」的知情同意闸门：点「开启息屏提醒」先进说明页，说明页里点「去系统设置」才真正跳转。
        // 🔴 别把 explain 去掉直接调 toggle()：vivo 之外的手机会立刻弹到无障碍设置页，
        //    用户不知道自己在开什么（这正是本次反馈的问题）。
        var explain by remember { mutableStateOf(false) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) keepAlive = GoalKeepAliveService.isEnabled(c)
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }
        // 🔴 系统实际状态是唯一真相：每次进卡片把它与「用户想不想要」对齐一次。
        //    踩到的场景：没 WRITE_SECURE_SETTINGS 时点「关闭」会跳系统页、但用户没真关，
        //    此时 wanted 已被写成 false ⇒ 系统里开着、App 认为不要 ⇒ 冷启动自愈永久失效。
        DisposableEffect(Unit) {
            if (keepAlive != UserPrefs.keepAliveWanted.value) UserPrefs.setKeepAliveWanted(keepAlive)
            onDispose { }
        }
        // 息屏提醒开关**只由底部按钮**操作（状态行纯展示，点了不会跳系统设置）。
        // 开启/关闭都走 [GoalKeepAliveService] 的一键接口：有 WRITE_SECURE_SETTINGS 就静默改，
        // 没有才降级跳系统设置页。
        // 🔴 面向用户的文案一律说「息屏提醒」，不出现「无障碍」三个字（用户要求）。
        fun toggle() {
            if (keepAlive) {
                // 明确意图先落盘：即使这次要跳系统页，下次冷启动的「自愈」也不会把它偷偷开回来
                UserPrefs.setKeepAliveWanted(false)
                when (GoalKeepAliveService.disableOneTap(c)) {
                    GoalKeepAliveService.OPEN_QUIET -> {
                        keepAlive = false
                        Toast.makeText(c, "已关闭息屏提醒", Toast.LENGTH_SHORT).show()
                    }
                    GoalKeepAliveService.OPEN_MANUAL -> {
                        Toast.makeText(c, "在系统页把「竞彩」的开关关掉即可", Toast.LENGTH_LONG).show()
                        onDismiss()
                    }
                    else -> Toast.makeText(
                        c, "打不开系统设置，请到系统设置里关闭息屏提醒", Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                UserPrefs.setKeepAliveWanted(true)
                when (GoalKeepAliveService.enableOneTap(c)) {
                    GoalKeepAliveService.OPEN_QUIET -> {
                        keepAlive = true
                        Toast.makeText(c, "已开启息屏提醒：息屏也能收到进球通知", Toast.LENGTH_SHORT).show()
                    }
                    GoalKeepAliveService.OPEN_MANUAL -> {
                        // 跳到系统设置页：那边点开开关后返回，ON_RESUME 会把状态刷成「已开启」
                        Toast.makeText(c, "在系统页把「竞彩」的开关打开即可", Toast.LENGTH_LONG).show()
                        onDismiss()
                    }
                    else -> Toast.makeText(
                        c, "打不开系统设置，请到系统设置里开启息屏提醒", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        if (explain) {
            // ── 说明页：可选增强的「知情同意」──────────────────────────────
            // 别的手机上「一点就被甩进无障碍设置页」是真实体验，所以开启前必须先讲清三件事：
            //   ① 不开也能用（亮屏 / 用其他应用时照样震）；② 开了才管息屏静置；
            //   ③ 打开动作要用户自己在系统页拨一下开关（普通应用无权代开）。
            Column(
                Modifier.fillMaxWidth()
                    .background(Surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                // 文案长度有硬约束：卡片内可用宽度只放得下约 17 个汉字（11.5sp），
                // 超过就折行、还会剩一个孤字（实测「…震动提 / 醒」），所以每条都压到 16 字以内。
                ExplainLine("不开也能用", "亮屏或用别的应用时，照样震动")
                Spacer(Modifier.height(2.dp))
                ExplainLine("开了才管息屏", "黑屏放口袋里也能收到震动")
                Spacer(Modifier.height(2.dp))
                ExplainLine("要你手动拨一下", "跳到系统设置页，手动打开开关")
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(38.dp)
                        .background(SurfaceAlt, RoundedCornerShape(10.dp))
                        .clickable { explain = false },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂不开启",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSub,
                        maxLines = 1
                    )
                }
                Box(
                    Modifier.weight(1f).height(38.dp)
                        .background(Primary, RoundedCornerShape(10.dp))
                        .clickable { explain = false; toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "去系统设置",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
        } else {
            Column(Modifier.background(Surface, RoundedCornerShape(12.dp))) {
                SwitchRow("进球提醒", goalAlert, onGoalAlert)
                HorizontalDivider(color = Line, thickness = 0.6.dp, modifier = Modifier.padding(horizontal = 14.dp))
                // 开赛提醒是**独立开关**：用户可能只想被提醒「要开球了」，不想每进一个球都震一下。
                SwitchRow("开赛提醒", kickoffAlert, onKickoffAlert)
                // 提前量只在开启时出现：关着的开关配一排可选值，会让人以为「选了就会提醒」
                if (kickoffAlert) LeadRow(kickoffLead, onKickoffLead)
                HorizontalDivider(color = Line, thickness = 0.6.dp, modifier = Modifier.padding(horizontal = 14.dp))
                SwitchRow("震动", vibrate, onVibrate)
                HorizontalDivider(color = Line, thickness = 0.6.dp, modifier = Modifier.padding(horizontal = 14.dp))
                SwitchRow("进球音乐", sound, onSound)
                HorizontalDivider(color = Line, thickness = 0.6.dp, modifier = Modifier.padding(horizontal = 14.dp))
                // 纯状态展示行（不可点）：开关动作在底部按钮。
                // 未开启时写「仅亮屏时」而不是「未开启」—— 默认就是未开启，
                // 写「未开启」会让人以为功能坏了，写清楚它其实是**降级**而不是失灵。
                SystemRow("息屏提醒", keepAlive, offText = "仅亮屏时")
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 息屏提醒：已开启 → 直接关；未开启 → 先进说明页（不直接跳系统设置）
                Box(
                    Modifier.weight(1f).height(38.dp)
                        .background(
                            if (keepAlive) SurfaceTint else Primary.copy(alpha = 0.12f),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { if (keepAlive) toggle() else explain = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (keepAlive) "关闭息屏提醒" else "开启息屏提醒",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (keepAlive) TextSub else Primary,
                        maxLines = 1
                    )
                }
                Box(
                    Modifier.weight(1f).height(38.dp)
                        .background(Primary, RoundedCornerShape(10.dp))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("完成", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

/**
 * 设置里的一行**系统级状态**：无障碍开关由系统持有，本 App 只读。
 *
 * 🔴 按用户要求做成**纯展示、不可点**：点它不会跳系统无障碍设置，避免误触。
 * 真正的开关放在弹窗底部按钮（「开启息屏提醒」/「关闭息屏提醒」）。
 * 也不用 Switch：无障碍开关普通应用无权直接写，Switch 会「点了弹回」。
 *
 * [offText] 默认「未开启」；息屏提醒这一行传「仅亮屏时」——
 * 功能默认就处于「不开」状态，写「未开启」像是在报故障，写清楚它只是覆盖范围小一点。
 */
@Composable
private fun SystemRow(
    label: String,
    on: Boolean,
    offText: String = "未开启"
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = TextMain, modifier = Modifier.weight(1f))
        Text(
            if (on) "已开启" else offText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (on) C_GOAL_INK2 else TextSub,
            maxLines = 1
        )
    }
}

/**
 * 息屏增强说明页的一行：上「小标题」下「一句话解释」。
 *
 * 为什么不做成「标题：说明」左右一行 —— 卡片只有 264dp 宽，
 * 右边的解释普遍 16~18 字，左右排会各自折行错位；上下两行反而更好读。
 */
@Composable
private fun ExplainLine(title: String, sub: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "· $title",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = TextMain
        )
        Text(
            sub,
            fontSize = 11.5.sp,
            color = TextSub,
            modifier = Modifier.padding(start = 9.dp, top = 1.dp)
        )
    }
}

/** 设置里的一行开关：左标题、右 Switch（无描述文字、44dp 紧凑行高）。 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = TextMain, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.scale(0.86f)
        )
    }
}

/** 开赛提醒的可选提前量（分钟）。5 分钟＝刚够切到直播间，30 分钟＝够出门买点东西。 */
private val KICKOFF_LEADS = listOf(5, 15, 30)

/**
 * 「提前几分钟提醒」的一排小胶囊。
 *
 * 高度只给 30dp（不是 44dp 的标准行高）：它不是独立开关，而是**上一个开关的附属参数**，
 * 行高一致会让人误以为是并列的另一项设置。
 */
@Composable
private fun LeadRow(current: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("提前", fontSize = 12.sp, color = TextSub)
        Spacer(Modifier.width(8.dp))
        KICKOFF_LEADS.forEachIndexed { i, m ->
            if (i > 0) Spacer(Modifier.width(6.dp))
            val on = m == current
            Box(
                Modifier.width(52.dp).height(24.dp)
                    .background(if (on) Primary else SurfaceAlt, RoundedCornerShape(8.dp))
                    .clickable { onPick(m) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$m 分",
                    fontSize = 11.sp,
                    color = if (on) Color.White else TextSub,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 顶部球类切换（足球 / 篮球）：**下划线式页签，没有任何底色**。
 *
 * 与「竞彩赛程」同一套（用户两次明确要求「不要背景，用下划线方式」）：
 * 选中 = 主色加粗文字 + 下方 2.5dp 圆角短横线，未选中 = 次要灰字。
 * 这是本页第三版：① 三段文字页签（完整/热门/竞彩 那套）→ ② 绿色实心胶囊 → ③ 下划线。
 *
 * 右侧红色小字「点击可查看直播详情」是用户要求的提示：本页行点进去才是详情。
 */
@Composable
private fun SportTabs(current: LiveSport, onSelect: (LiveSport) -> Unit) {
    // 🔴 页签区整块铺浅蓝底带（SurfaceTint #E3E9F6）。用户明确要求：**页签本身不要底色**（仍是文字 + 下划线），
    //    只有「足球/篮球」这一整条横带加底色，避免整页纯白太单调。
    //    底色必须挂在外层 Column 上：页签行与提示行共用同一条底带，否则中间会露一道白缝。
    Column(
        Modifier.fillMaxWidth().background(SurfaceTint)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveSport.values().forEach { s ->
                SportTabPill(s, s == current, Modifier.weight(1f)) { onSelect(s) }
            }
        }
        // 🔴 提示**不能**和页签挤同一行：11 个汉字把足球/篮球页签压得只剩一半宽（用户已反馈）。
        //    这里单独占一行（整行宽 ⇒ 正常只有一行，窄屏才折成两行），见 [LiveHintBar]。
        LiveHintBar()
    }
    HorizontalDivider(color = Line, thickness = 1.dp)
}

/**
 * 页签下方的红色跳动提示行（整行宽，左对齐；窄屏最多折两行）。底色由外层底带提供，这里透明。
 *
 * 🔴 **可关闭**：这是一条一次性信息（「行点进去才是详情」），却原来常驻占掉首屏约 26dp，
 * 用户每进一次都要多滚一下才看到第一场比赛。右侧 ✕ 关掉后写盘（[UserPrefs.dismissLiveHint]），
 * 整个提示行连同底带的那点内边距一起消失，首屏直接多出一行比赛。
 */
@Composable
private fun LiveHintBar() {
    val dismissed by UserPrefs.liveHintDismissed.collectAsState()
    if (dismissed) return
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 单行 + 水平滚动（滚到最右停 5s 再回到最左），不再折行
        LiveDetailHint(modifier = Modifier.weight(1f))
        // ✕ 不能太小：24dp 是能稳定点中的下限，这里给 26dp 见方
        Box(
            Modifier.size(26.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { UserPrefs.dismissLiveHint() },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", fontSize = 12.sp, color = TextSub, maxLines = 1)
        }
    }
}

@Composable
private fun RowScope.SportTabPill(
    sport: LiveSport,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val fg by animateColorAsState(
        if (selected) Primary else TextSub, tween(160), label = "liveSportFg"
    )
    Box(
        // 38dp（旧 46dp）：页签只是「足球/篮球」二选一的开关，不该占掉首屏两行的高度。
        // 内容高度 = 13sp 文字 + 3dp 间距 + 2.5dp 下划线 ≈ 25dp，38dp 仍有充足留白。
        modifier.height(38.dp).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (sport == LiveSport.FOOTBALL) "⚽" else "🏀", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    sport.label,
                    color = fg,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(3.dp))
            // 选中才有下划线；未选中留同高透明占位，切换时文字不上下跳
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

/** 足球比分直播（titan007 数据源，原样保留：完整 / 热门 / 竞彩 三个子页签）。 */
@Composable
private fun FootballLive(
    vm: LiveViewModel,
    onOpenDetail: (Long, String, String) -> Unit,
    /** 竞彩赔率子行右端「变动」图标 → 赔率变动页。 */
    onOpenSpfChange: (LiveMatch) -> Unit = {},
    /** 角球行「析」→ 数据分析页。 */
    onOpenAnalysis: (Long, String, String) -> Unit = { _, _, _ -> },
    onlyNums: Set<String>? = null,
    onlyTeams: List<Pair<String, String>>? = null,
    /** 投注记录进入时隐藏「完整/热门/竞彩」子页签（本页只关心那几场）。 */
    hideTabBar: Boolean = false,
    /** 打开「进球提醒设置」居中弹窗。 */
    onSettings: () -> Unit = {},
    /** 打开「我的关注」聚合页。 */
    onOpenFollow: () -> Unit = {},
    /** 请求通知权限（关注首场 / 打开提醒总开关时调用）。 */
    onRequestPerm: () -> Unit = {}
) {
    val state by vm.state.collectAsState()
    val followed by UserPrefs.followed.collectAsState()
    /** 长按比分打开的事件弹层（null = 不显示）。*/
    var eventMatch by remember { mutableStateOf<LiveMatch?>(null) }
    /** 投注记录进入（筛选模式）不显示关注列，保持页面干净。 */
    val showFollow = onlyNums == null && onlyTeams == null
    // 滚动位置兜底：主列表用进程内记忆的「首个可见行下标」建初值（详见文件末尾 [LiveScrollMemo]）。
    // 投注记录进入的筛选列表不参与，避免两边位置串味。
    // ⚠️ 本项目的 compose-foundation 版本里 `rememberLazyListState` **只有 index 入参**（没有像素偏移），
    //    所以恢复后是「对齐到该行行首」，最多差半行，肉眼无感。
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (showFollow) LiveScrollMemo.index else 0
    )

    fun onToggleFollow(id: Long) {
        val willFollow = !UserPrefs.isFollowed(id)
        UserPrefs.toggleFollow(id)
        if (willFollow && UserPrefs.goalAlert.value) {
            onRequestPerm()
            // 关注集合变了 ⇒ 同步后台轮询的启停。sync() 幂等：App 在前台时它会直接停掉
            //（前台由 ViewModel 的 5s tick 负责），退到后台后才真正开始轮询。
            BackgroundGoalWatcher.sync()
        }
    }

    // 过滤后的分组（无筛选时**原样返回**，零额外开销）
    val groups = remember(state.groups, onlyNums, onlyTeams) {
        state.groups.filterTicketMatches(onlyNums, onlyTeams)
    }
    val filtering = onlyNums != null || onlyTeams != null

    // 切换 tab 时列表内容整体替换（过滤条件不同），把垂直滚动归位到顶部，
    // 否则仍停留在上一 tab 的滚动位置，看起来像「向下滑了一段」。
    // 🔴 必须比「上一次的 tab」再决定是否归顶，不能无条件 scrollToItem(0)：
    //    LaunchedEffect(state.tab) 在**每次重新进入组合**时都会重跑一遍（从动画直播 / 数据分析
    //    返回本页就是一次重新组合），而无条件归顶会立刻把 `rememberLazyListState` 刚从
    //    saveable 里恢复出来的位置清成 0 ⇒ 症状「点进动画直播再返回，列表跑到最上面」。
    //    记 tab 名而不是枚举本身：名字是 String，rememberSaveable 存 Bundle 最稳。
    var shownTabName by rememberSaveable { mutableStateOf(state.tab.name) }
    LaunchedEffect(state.tab) {
        val name = state.tab.name
        if (shownTabName != name) {
            shownTabName = name
            listState.scrollToItem(0)
        }
    }

    // 随时把主列表的滚动位置写进进程内记忆（只写普通变量、不触发重组）。
    // 从动画直播 / 数据分析返回本页时，上面 `rememberLazyListState(initial*)` 就能把位置
    // 直接建在记忆处 —— 不用「先渲染在顶部再跳回去」，也就没有一帧闪动。
    LaunchedEffect(listState, showFollow) {
        if (!showFollow) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            LiveScrollMemo.index = idx
        }
    }

    // **仅调试包**：队名闪色的回归钩子（`-e demo_flash "1"` = 取消进球闪色 / `"0"` = 进球闪色）。
    // 真实的「进球被取消」要等 VAR 改判，没法按需复现；这里借同一套字段打一次闪色用于核验配色。
    LaunchedEffect(Unit) {
        AppNavState.debugFlash.collect { v ->
            if (v != null) {
                vm.debugFlashFirst(cancelled = v == 1)
                AppNavState.debugFlash.value = null
            }
        }
    }

    // 已结束的比赛**自动摘掉关注**（用户要求「已结束的比赛去掉关注」）。
    // 查的是 allMatches 而不是当前 tab 的列表：热门/竞彩切换后仍能把完赛的关注清干净；
    // 清理后 `followed` 变化会再触发一次本 effect，此时集合已空 ⇒ 空转返回，不会来回抖。
    LaunchedEffect(state.allMatches, followed) {
        if (followed.isEmpty()) return@LaunchedEffect
        val finished = state.allMatches
            .asSequence()
            .filter { it.state == -1 && it.titanId in followed }
            .map { it.titanId }
            .toSet()
        if (finished.isNotEmpty()) UserPrefs.removeFollow(finished)
    }

    // 可见性：5 秒 tick 的开关已改由 MainActivity 的 ProcessLifecycle（app 前台）统一驱动，
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
        // 顶部子页签：完整 / 热门 / 竞彩（投注记录进入时不显示）
        if (!hideTabBar) TabBar(state.tab, { vm.setTab(it) }, onSettings, onOpenFollow)

        // 🔴 已按用户要求**去掉「共 N 场比赛」条**：页签切换本身就是筛选，条数不是必要信息，
        //    还白占了 34dp 的首屏高度。
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxH = maxHeight
            // 先落到局部变量：LazyColumn 的 item 作用域里 `maxWidth` 会被 LazyItemScope 抢掉
            val vpW = maxWidth
            // 🔴 本轮**去掉整表横向滚动**：大小球已从「第 6 列」下移到元信息行（见 [MetaLine]），
            //    内容宽恒等于屏宽 ⇒ 每一行都完整可见，不再有「要滑一下才看得到」的列。
            Column(Modifier.fillMaxSize()) {
                TableHeader(Modifier.fillMaxWidth(), showFollow)
                when {
                    // 🔴 筛选模式（投注记录进入）**不显示任何空文案**：
                    //    刚进来数据还在路上，先亮一句「暂无直播数据」会闪一下，用户已要求去掉。
                    //    数据没回来时只显示表头（干净），回来了就直接出行。
                    groups.isEmpty() && filtering -> Unit
                    state.loading && state.groups.isEmpty() ->
                        Box(Modifier.fillMaxWidth()) { Loading() }
                    state.error != null && groups.isEmpty() ->
                        Box(Modifier.fillMaxWidth()) { ErrorText(state.error) }
                    groups.isEmpty() ->
                        Box(Modifier.fillMaxWidth()) { EmptyText("暂无比分直播") }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().height(maxH),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        groups.forEach { group ->
                            if (group.businessDate.isNotEmpty() || group.weekday.isNotEmpty()) {
                                item(key = "lhdr-${group.businessDate}-${group.weekday}") {
                                    DayHeader(group)
                                }
                            }
                            itemsIndexed(
                                group.matches,
                                key = { _, m -> m.matchId }
                            ) { _, m ->
                                MatchRow(
                                    m = m,
                                    followed = followed.contains(m.titanId),
                                    onToggleFollow = { onToggleFollow(m.titanId) },
                                    showFollow = showFollow,
                                    onClick = { onOpenDetail(m.titanId, m.home, m.away) },
                                    onLongPress = { eventMatch = m },
                                    onOpenSpfChange = { onOpenSpfChange(m) },
                                    onOpenAnalysis = { onOpenAnalysis(m.titanId, m.home, m.away) },
                                    vpWidth = vpW
                                )
                            }
                        }
                    }
                }
            }
        }

        // 长按比分 → 比赛事件时间轴 + 技术统计（用户要求）
        eventMatch?.let { mm ->
            MatchEventSheet(m = mm, onClose = { eventMatch = null })
        }
    }
}

@Composable
private fun TabBar(
    tab: LiveTab,
    onSelect: (LiveTab) -> Unit,
    onSettings: () -> Unit = {},
    onOpenFollow: () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().background(Surface).height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabItem("完整", tab == LiveTab.ALL, onClick = { onSelect(LiveTab.ALL) })
        TabItem("热门", tab == LiveTab.HOT, onClick = { onSelect(LiveTab.HOT) })
        TabItem("竞彩", tab == LiveTab.JINGCAI, onClick = { onSelect(LiveTab.JINGCAI) })
        // 「我的关注」入口：星形常亮主色 —— 关注清单是这一页里唯一「我自己的」数据，
        // 用灰图标会被当成又一个设置项，找不着。
        Box(
            Modifier.width(40.dp).fillMaxHeight().clickable { onOpenFollow() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "我的关注",
                tint = Primary,
                modifier = Modifier.width(20.dp).height(20.dp)
            )
        }
        // 右侧「进球提醒设置」入口：改用**标准矢量齿轮**（Material Icons.Settings），
        // 原来的文字「⚙」在 18sp 下发虚、像个小太阳，不像设置。
        Box(
            Modifier.width(44.dp).fillMaxHeight().clickable { onSettings() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "进球提醒设置",
                tint = C_SUB,
                modifier = Modifier.width(21.dp).height(21.dp)
            )
        }
    }
    HorizontalDivider(color = C_HEADER_LINE, thickness = 1.dp)
}

@Composable
private fun RowScope.TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Primary else C_SUB
    Box(
        Modifier.weight(1f).fillMaxHeight().clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = color
            )
            if (selected) {
                Spacer(Modifier.height(3.dp))
                Box(Modifier.width(28.dp).height(2.5.dp).background(Primary))
            }
        }
    }
}

/**
 * 表头：关注★ / 联赛 / 时间 / 状态 / 主队-比分-客队。
 *
 * 🔴 **不再有「大小球」列头**：大小球已下移到行内的元信息行（[MetaLine]），
 * 与角球、「析」同处一条；表头只索引主行，主行有几列，表头就写几列。
 */
@Composable
private fun TableHeader(modifier: Modifier = Modifier, showFollow: Boolean = true) {
    Row(
        modifier.height(HEADER_H).background(C_HEADER_BG),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showFollow) HeadCell("★", COL_FOLLOW_W, alignCenter = true)
        HeadCell("联赛", COL_LEAGUE_W)
        HeadCell("时间", COL_TIME_W, alignCenter = true)
        HeadCell("状态", COL_STATUS_W, alignCenter = true)
        Text(
            "主队        比分        客队",
            Modifier.weight(1f),
            fontSize = 11.sp,
            color = C_TEXT,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
    HorizontalDivider(color = C_HEADER_LINE, thickness = 1.dp)
}

@Composable
private fun HeadCell(text: String, width: androidx.compose.ui.unit.Dp, alignCenter: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(width).padding(start = 4.dp),
        fontSize = 11.sp,
        color = C_TEXT,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        textAlign = if (alignCenter) TextAlign.Center else TextAlign.Start
    )
}

@Composable
private fun DayHeader(group: LiveDayGroup) {
    Row(
        Modifier.fillMaxWidth().background(C_GROUP_BG).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (group.weekday.isNotEmpty()) {
            Text(
                "${group.weekday} ${group.businessDate}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = C_TEXT
            )
            Spacer(Modifier.width(6.dp))
        }
        Text("${group.matches.size} 场", fontSize = 11.sp, color = C_TEXT)
    }
    HorizontalDivider(color = C_GROUP_LINE, thickness = 0.6.dp)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MatchRow(
    m: LiveMatch,
    followed: Boolean = false,
    onToggleFollow: () -> Unit = {},
    showFollow: Boolean = true,
    onClick: () -> Unit = {},
    /** 长按**整行任意位置**：弹出事件时间轴 + 技术统计（[MatchEventSheet]）。*/
    onLongPress: () -> Unit = {},
    /** 赔率子行右端的「变动」入口（仅竞彩场次）。 */
    onOpenSpfChange: () -> Unit = {},
    /** 角球子行里的「析」→ 数据分析页。 */
    onOpenAnalysis: () -> Unit = {},
    /** 可视区宽度：元信息行的「析」要靠它顶到屏幕右缘。 */
    vpWidth: Dp = 360.dp
) {
    // 🔴 非隔行底必须是主题的 Surface（原先写死 Color.White）：深色模式下整张表一半行是纯白，
    //    而队名用的是 C_TEXT（深色下是亮白）⇒ 白字压白底，整场队名「看不见」。
    val bg = if (m.striped) C_STRIPE else Surface
    Column {
        Row(
            // 手势挂在**整行**上（用户要求：长按那一行，不要只按比分）：
            // 之前挂在 50dp 的比分格 / 212dp 的比分区，手指偏到联赛、时间、状态或大小球列都不响应，
            // 实测命中手感很差。行内除关注星标外没有其它可点元素，长按整行不会和别的交互打架。
            Modifier.fillMaxWidth().background(bg)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 🔴 关注星标放**最左第一列**：主行内容按「★ → 联赛 → 时间 → 状态 → 对阵」阅读，
            //    星标在最左是唯一不会随队名长短、比分位数变动的位置，扫视时列对齐。
            // 🔴 **已结束的比赛不再有关注**（用户要求）：完场（state == -1）后进球提醒毫无意义，
            //    这里画同宽透明占位而不是整列不画，否则该行所有列会左移、表格错位。
            if (showFollow) {
                if (m.state == -1) {
                    Spacer(Modifier.width(COL_FOLLOW_W).defaultMinSize(minHeight = ROW_MIN_H))
                } else {
                    FollowCell(followed, onToggleFollow)
                }
            }
            // 联赛：实心色块（非圆角），白字、最多 2 行
            LeagueBlock(name = m.league, colorHex = m.color)
            // 时间：所有行都显示开赛时间 HH:mm
            TimeCell(m)
            // 状态：未开赛（留空）/ 走时 / 中场 / 完场 / 推迟（琥珀）—— 红色只留给比分，见 [StatusCell]
            StatusCell(m)
            // 主队 比分 客队（weight 吃满剩余宽度）；长按手势在 [MatchRow] 整行上，这里只负责显示
            VsCell(m)
        }
        // 元信息行：角球图标 / 大小球 / 「析」，**固定 20dp**（旧版「只有析」的那条占 30dp）。
        // 🔴 大小球从「第 6 列」搬到这里是本轮去横滑的关键：它原来占 108dp，
        //    把内容宽顶到 468dp > 屏宽 360dp，用户不横滑就看不到。
        MetaLine(m, showFollow = showFollow, bg = bg, vpWidth = vpWidth, onOpenAnalysis = onOpenAnalysis)
        // 竞彩胜平负赔率：贴在「主队 比分 客队」这一行**下面**（用户要求）。
        // 只在竞彩场次且该期确实有赔率时出现，非竞彩场次行高完全不变。
        if (m.spfH.isNotEmpty()) {
            SpfLine(
                m,
                showFollow = showFollow,
                onClick = onClick,
                onLongPress = onLongPress,
                onOpenChange = onOpenSpfChange
            )
        }
        HorizontalDivider(color = C_ROW_LINE, thickness = 0.6.dp)
    }
}

/**
 * 主行下面的**元信息行**：`(角旗) 5-3   大小 0.92 2.5 0.88 ……… 析`。
 *
 * ## 为什么三类信息并成一行
 * 旧版分成两条：有角球的走 [CornerLine]、没角球的走 [AnalysisOnlyLine]，
 * 而且大小球是主表第 6 列（108dp）。结果两个毛病：
 *  1. 内容宽 468dp > 屏宽 360dp ⇒ 大小球被推到屏幕外，**要横滑才看得见**；
 *  2. 「只有析」的那一行实测占 **30dp**，一屏少看三四场，而「析」还悬在行底右端，
 *     看起来像是**下一行**的操作（用户反馈过）。
 * 现在三者共用一条 20dp 的行：内容从左往右铺，`析` 用 weight 顶到右边，
 * 与左边内容处于同一条水平线上，归属关系一眼可辨。
 *
 * ## 各块的出现条件
 * - 角球：`state != 0 && homeCorner >= 0`（未开赛时一脚角球都没有，硬画 0-0 是假数据）；
 *   🔴 **半场角球已下线**（用户要求），别再把它加回来；
 * - 大小球：源站给了盘口或水位（竞彩场次几乎都有，非竞彩可能为空 ⇒ 整块不画）；
 * - 「析」：**恒在**（赛前看交手战绩恰恰最有用）。
 */
@Composable
private fun MetaLine(
    m: LiveMatch,
    showFollow: Boolean,
    bg: Color,
    /** 可视区宽度（`BoxWithConstraints.maxWidth`）：内容按屏宽排，超窄屏也不会把「析」挤出去。*/
    vpWidth: Dp,
    onOpenAnalysis: () -> Unit
) {
    /** 比分区起点 = 关注列（可能隐藏）+ 联赛 + 时间 + 状态。 */
    val vsStart = (if (showFollow) COL_FOLLOW_W else 0.dp) + COL_LEAGUE_W + COL_TIME_W + COL_STATUS_W
    val showCorner = m.state != 0 && m.homeCorner >= 0
    // 🔴 半场角球已**整块下线**（用户要求「不用显示半场角球」）：这条 20dp 的行里
    //    「角球图标 + 5-3 + 大小球三值 + 析」已经很满，半场角球是十场里只看一次的补充信息，
    //    留着只会把「析」挤到边缘、还让每行的宽度预算随有没有半场数据而抖动。
    // 🔴 源站对「没有盘口」的场次不是给空串，而是给 "-"（实测整条信息行变成「大小 - - -」）。
    //    用 isNotBlank 判断会把这类占位符当成真数据画出来 —— 未开赛的场次几乎全中，
    //    等于每行都多三个没意义的破折号。必须按「是否有真值」判断。
    val hasOu = hasPantValue(m.ouHome) || hasPantValue(m.ouGoal) || hasPantValue(m.ouAway)
    // 🔴 窄屏（< 340dp）降级：这一行把「角球 / 大小球三值 / 析」放在同一条里，
    //    360dp 实测算下来富余只有约 14dp（见 [OuGroup] 的字宽估算），320dp 机型必然溢出。
    //    所以窄屏改为「角球 / 半 / 大小 盘口」——丢掉两个水位，保住盘口和「析」入口。
    val narrow = vpWidth < 340.dp
    // 🔴 行高显式钉死：让「有角球 / 没角球 / 只有析」三种行高完全一致，
    //    列表滚动时行距不会忽疏忽密（旧版这条从 13dp 到 30dp 浮动）。
    //    contentAlignment 必须是 CenterStart：Row 自身高度只有文字那么高（约 13dp），
    //    不居中就会贴在 20dp 的顶部，与主行的基线错开。
    Box(
        Modifier.fillMaxWidth().height(META_ROW_H).background(bg),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier.width(vpWidth).padding(start = vsStart + 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showCorner) {
                CornerGroup(m.homeCorner, m.awayCorner)
            }
            if (hasOu) {
                if (showCorner) Spacer(Modifier.width(9.dp))
                OuGroup(m.ouHome, m.ouGoal, m.ouAway, compact = narrow)
            }
            // 「析」= 数据分析（双方交手 / 近期战绩）：绿色、整行最右边。
            // 独立 clickable 会消费点击，不会顺带触发整行的「进入动画直播」。
            Spacer(Modifier.weight(1f))
            AnalysisEntry(onOpenAnalysis)
        }
    }
}

/**
 * 大小球三元组：`大球水 盘口 小球水`，中间盘口加粗加深。
 *
 * 不再画旧版的三个带框单元格（36dp × 3 + 边框 = 108dp）：那套是为了填满一个**表格列**，
 * 搬到 20dp 高的元信息行里只需要「三个数 + 一个标签」，去掉竖线后一屏更干净。
 *
 * [compact] = 窄屏模式：只留盘口。三个值在 320dp 屏上会把这行挤爆（详见 [MetaLine] 的宽度估算）。
 *
 * 🔴 本行数字**保持原来的非红配色**：本轮曾按「角球和大小球红色表示」把两组数字改成红，
 *    真机上红色数量一多，整行和左边的比分抢注意力，用户随后要求**改回来** ⇒
 *    角球数用 [C_TEXT]、盘口用 [C_TEXT]、两侧水位用 [C_SUB]，红色仍然只留给「比分 / 走时」。
 *    ⚠️ 别只因为「想醒目」就把这两组染红，醒目度已经由角旗图标和加粗盘口解决了。
 */
@Composable
private fun OuGroup(home: String, goal: String, away: String, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("大小", fontSize = 8.sp, color = C_SUB, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        if (!compact) {
            Text(orDash(home), fontSize = 9.5.sp, color = C_SUB, maxLines = 1)
            Spacer(Modifier.width(3.dp))
        }
        // 盘口（2.5 / 3）是这一组的主值：加粗 + 主文字色，两侧水位退成次要灰
        Text(orDash(goal), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = C_TEXT, maxLines = 1)
        if (!compact) {
            Spacer(Modifier.width(3.dp))
            Text(orDash(away), fontSize = 9.5.sp, color = C_SUB, maxLines = 1)
        }
    }
}

/** 空值统一显示「-」，避免一行里出现「0.92   0.88」这种看不出少了一个值的排版。 */
private fun orDash(v: String): String = if (v.isBlank()) "-" else v

/**
 * 是否**真有盘口值**（决定整组「大小」画不画）。
 *
 * 🔴 与 [orDash] 的分工：`isBlank()` 挡不住源站自己给的占位符。
 * 实测未开赛的场次源站给的就是 `"-"`，用 isBlank 判断会让每行都渲染出「大小 - - -」——
 * 三段破折号既没信息量，又把信息行一半宽度占掉，还把右侧「析」挤到边缘。
 *
 * 判据取「含至少一个数字」而不是枚举破折号：真值一定是 `0.92` / `3/3.5` / `2.5` 这类，
 * 占位符（`-`、`--`、`—`、`－`）一个数字都没有。这样即使源站换一种破折号也照样拦住。
 */
private fun hasPantValue(v: String): Boolean = v.any { it.isDigit() }

/**
 * 元信息行右端的「析」入口（数据分析：双方交手 / 近期战绩）。
 *
 * 🔴 本轮**去掉浅绿底与细边框**（用户要求「析字去掉边框」）：一枚只有 11sp 的字，
 *    外面套一圈绿框时，框的视觉重量比字本身还大，整行看起来像有个按钮杵在那儿；
 *    去掉后它回到「一行注释里的一个绿色字」，反而更好点 —— 触摸区靠容器撑到整条 20dp 高，
 *    没有变小。
 *
 * 🔴 本轮再修**「太靠下、显示不全」**（用户反馈）：旧写法把 3dp 上下内边距加在**文字**上，
 *    11sp 的字量出来约 15dp，加上 6dp 内边距 = **21dp > 这条元信息行的 20dp**，
 *    溢出的底边正好被紧跟其后的 `HorizontalDivider` 盖住 ⇒ 「斤」的最后一横被切掉，
 *    而且文字因为内部排版把基线往下推，看着比左边的角球/大小球整体低一截。
 *    现在反过来：**容器**钉死 20dp 高、文字在里面居中，文字不参与撑高这一行，
 *    既不会越界被分隔线盖住，也不会有 1～2dp 的基线漂移；点击区还变大了（整条 20dp 都能点）。
 */
@Composable
private fun AnalysisEntry(onClick: () -> Unit) {
    Spacer(Modifier.width(10.dp))
    Box(
        Modifier.height(META_ROW_H)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "析",
            fontSize = 11.sp,
            color = Color(0xFF16A34A),
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * 角球：**图标 + 主-客**（`🚩 5-3`），不再是「角球 5-3」四个字的文字标签。
 *
 * 用户要求「角球不要用文字，直接用角球图标表示」。改图标省下的不只是两个字的宽度 ——
 * 这条 20dp 的信息行里，两个汉字的视觉重量远大于一枚 9dp 的小旗，
 * 原先「角球」两个字读起来像正文标签，把旁边的 5-3 都比下去了。
 *
 * 图标语义：角旗（竖旗杆 + 三角旗面），与 188bifen / titan007 的角球图标同一个符号。
 *
 * 🔴 数字保持**主文字色（非红）**：本轮曾试过染红，用户确认「还是该回去」——
 *    角球数和比分、走时同色时，一屏里红点太多，反而看不出哪块是实时的。
 *    旗子仍是灰的：它是「这组数字是角球」的注解。
 */
@Composable
private fun CornerGroup(home: Int, away: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CornerIcon()
        Spacer(Modifier.width(3.dp))
        Text(
            "$home-$away",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            color = C_TEXT,
            maxLines = 1
        )
    }
}

/**
 * 角旗图标（`Canvas` 手绘，不引第三方资源）。
 *
 * 尺寸 9dp：与 9.5sp 的数字同高，看上去是「一个符号 + 一个数」而不是「一块图形 + 一串字」。
 * 颜色走 [C_SUB]（次要灰）—— 它只是「5-3 是角球」的注解，主信息是数字。
 */
@Composable
private fun CornerIcon() {
    val ink = C_SUB
    Canvas(Modifier.size(9.dp)) {
        val w = size.width
        val h = size.height
        // 旗杆：贴左边，从底到顶
        drawLine(
            color = ink,
            start = Offset(w * 0.2f, h),
            end = Offset(w * 0.2f, 0f),
            strokeWidth = w * 0.14f,
            cap = StrokeCap.Round
        )
        // 旗面：从杆顶往下 30% 处的三角，占右边 75% 宽
        val flag = Path().apply {
            moveTo(w * 0.26f, h * 0.06f)
            lineTo(w * 0.98f, h * 0.26f)
            lineTo(w * 0.26f, h * 0.46f)
            close()
        }
        drawPath(flag, ink)
    }
}

/**
 * 竞彩胜平负赔率子行。
 *
 * ## 为什么单独占一行，而不是塞进比分格
 * 比分格只有 50dp，三组「标签+赔率」（2.05 / 3.00 / 3.15）挤进去每组不到 16dp，
 * 字号要压到 7sp 才不折行 —— 等于看不见。整行 468dp 扣掉左侧固定 134dp 还有 334dp，
 * 给三组值绰绰有余，还能保持 11sp 的可读字号。
 *
 * ## 对齐
 * 左侧缩进 = 关注 + 联赛 + 时间 + 状态四列宽（134dp），正好从**比分区**开始，
 * 与上面的「主队 比分 客队」垂直对齐，扫读时不会觉得是两段无关信息。
 *
 * 交互与主行一致（点击看直播详情、长按弹事件时间轴），不额外制造第二个点击语义。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpfLine(
    m: LiveMatch,
    showFollow: Boolean = true,
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
    /** 点右端「变动」图标：打开该场竞彩胜平负赔率变动页。 */
    onOpenChange: () -> Unit = {}
) {
    /** 关注 + 联赛 + 时间 + 状态 = 比分区起点。关注列在筛选模式（投注记录进入）下不画。 */
    val vsStart = (if (showFollow) COL_FOLLOW_W else 0.dp) + COL_LEAGUE_W + COL_TIME_W + COL_STATUS_W
    Row(
        Modifier
            .fillMaxWidth()
            .background(C_SPF_BG)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = 0.dp, end = 8.dp, top = 2.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 🔴 「竞彩」徽标放在**左侧缩进区**（右对齐到比分区起点），不占赔率区宽度。
        //    之前徽标挤在赔率前面，加上「胜/平/负」三组后整行正好顶到屏幕右缘，
        //    右边那个「变动」入口被裁掉一半（实测截图确认）。
        //    移到缩进区后赔率整体左移约 32dp，入口就能完整落在 360dp 视口内。
        Box(
            Modifier.width(vsStart - 6.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                "竞彩",
                fontSize = 8.5.sp,
                color = C_SUB,
                modifier = Modifier
                    .background(Surface, RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp, vertical = 0.5.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        SpfPair("胜", m.spfH)
        Spacer(Modifier.width(13.dp))
        SpfPair("平", m.spfD)
        Spacer(Modifier.width(13.dp))
        SpfPair("负", m.spfA)
        // 「赔率变动」入口紧贴「负」的右边（用户要求）。
        // 🔴 不能用 `Spacer(weight)` 顶到整行最右：整表宽 486dp 而手机视口只有 360dp，
        //    顶到右端等于把入口放到**屏幕外**，用户不横滑就永远看不到（实测踩过）。
        if (m.spid.isNotEmpty()) {
            Spacer(Modifier.width(5.dp))
            SpfChangeEntry(onClick = onOpenChange)
        }
    }
}

/**
 * 赔率变动入口：一枚 11dp 的**折线图**图标 + 「变动」。
 *
 * 🔴 图标用 Canvas 画、不用 emoji：emoji 在不同 ROM 上的宽度和基线差异很大，
 *    会把这条只有 2dp 垂直内边距的子行顶高，进而让整张表行高不齐。
 */
@Composable
private fun SpfChangeEntry(onClick: () -> Unit) {
    Row(
        Modifier
            .background(Surface, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(11.dp)) {
            val w = size.width
            val h = size.height
            // 先画一条浅色「底轴」：只画折线时，10dp 尺寸下三点几乎在一条斜线上，
            // 看起来就是一根斜杠「/」，完全读不出「走势图」（实测第一版就是这个观感）。
            drawLine(
                C_SPF_ACCENT.copy(alpha = 0.28f),
                Offset(0f, h * 0.96f), Offset(w, h * 0.96f),
                strokeWidth = w * 0.09f, cap = StrokeCap.Round
            )
            // 三个点：起点低 → 中间略抬 → 终点高，中间点刻意偏离起终点连线
            val pts = listOf(
                Offset(0.5f, h * 0.80f),
                Offset(w * 0.48f, h * 0.58f),
                Offset(w - 0.5f, h * 0.10f)
            )
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                lineTo(pts[1].x, pts[1].y)
                lineTo(pts[2].x, pts[2].y)
            }
            drawPath(
                path, C_SPF_ACCENT,
                style = Stroke(width = w * 0.135f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            pts.forEach { drawCircle(C_SPF_ACCENT, radius = w * 0.09f, center = it) }
        }
        Spacer(Modifier.width(2.dp))
        Text(
            "变动",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = C_SPF_ACCENT,
            maxLines = 1
        )
    }
}

/** 一组「标签 + 赔率值」，如 `胜 2.05`。 */
@Composable
private fun SpfPair(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 9.sp, color = C_SUB)
        Spacer(Modifier.width(2.dp))
        Text(
            value,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = C_SPF_VAL,
            maxLines = 1
        )
    }
}

/** 关注列：☆ 未关注 / ★ 已关注（橙色）。点击切换。 */
@Composable
private fun FollowCell(followed: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.width(COL_FOLLOW_W).defaultMinSize(minHeight = ROW_MIN_H)
            .clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (followed) "★" else "☆",
            fontSize = 16.sp,
            color = if (followed) Flat else C_SUB,
            maxLines = 1
        )
    }
}

/**
 * 页内进球动画弹窗宿主：collect [GoalEventBus]，每条进球挂一张卡片。
 * 停留 [GOAL_POPUP_STAY_MS]（6s）后先标记 leaving 播完 [GOAL_POPUP_EXIT_MS] 的退场动画再移除。
 * 仅在比分直播页组合时运行 ⇒ 自然只在「本页内」接收事件。
 * 收到即震动 + 播提示音（分别受 [UserPrefs.vibrate] / [UserPrefs.sound] 控制）。
 *
 * **多场同时进球**（用户要求）：
 *  - 所有卡片**同一尺寸**（[GOAL_POPUP_W] × [GOAL_POPUP_H]），不论队名长短、有没有联赛行；
 *  - **第 1 张**（最先来的那场）**垂直居中**；
 *  - 之后弹出来的依次排在前一张**下面**，间距 [GOAL_STACK_GAP]；同时最多 3 张。
 *
 * 为什么用 Column + 整体位移，而不是每张算绝对坐标：
 * Column 天然就是「一张接一张往下排」，第 1 张的位置只需把整列下移
 * (n-1)×(卡高+间距)/2 就能钉在屏幕正中——n=1 时位移为 0，加卡也不会让第 1 张挪位。
 */
@Composable
private fun GoalPopupHost(modifier: Modifier = Modifier) {
    val items = remember { mutableStateListOf<PopupItem>() }
    var seq by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        GoalEventBus.events.collect { e ->
            val id = ++seq
            // 同时最多挂 3 张：多场同开时（尤其一轮里好几场同时进球）不至于把比分表整块盖住。
            while (items.size >= 3) items.removeAt(0)
            items.add(PopupItem(id, e))
            // 取消进球用「两下短震」，进球用「三段渐强」——不看屏幕也能靠手感区分
            GoalNotifier.vibrate(ctx, cancelled = e.cancelled)
            GoalNotifier.playGoalSound(ctx)
            scope.launch {
                delay(GOAL_POPUP_STAY_MS)
                // 先置 leaving 让 AnimatedVisibility 播退场动画（可见性变化走 exit 分支）；
                // 直接 removeAll 会让卡片瞬间消失，6s 长停留的突兀感更明显。
                val idx = items.indexOfFirst { it.id == id }
                if (idx >= 0) items[idx] = items[idx].copy(leaving = true)
                delay(GOAL_POPUP_EXIT_MS)
                items.removeAll { it.id == id }
            }
        }
    }
    // 第 1 张钉在屏幕正中：整列先居中，再按「多出来的那几格」下移一半（见 KDoc）。
    val shift = (GOAL_POPUP_H + GOAL_STACK_GAP) * (items.size - 1) / 2
    Column(
        modifier = modifier.padding(horizontal = 16.dp).offset(y = shift),
        verticalArrangement = Arrangement.spacedBy(GOAL_STACK_GAP),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // key(item.id)：卡片增删时各自保留自己的动画状态（进场/退场），不会串味。
        items.forEach { item ->
            key(item.id) { GoalPopupCard(item.event, visible = !item.leaving) }
        }
    }
}

/**
 * 单张弹窗卡片，两种形态共用一套版式：
 *  - **进球**：淡绿渐变底 + 深墨绿字，标签「进球！/ GOAL」，橄榄足球正常旋转；
 *  - **取消进球**：淡玫红渐变底 + 深枣红字，标签「取消进球 / NO GOAL」，足球压暗且转得慢一半
 *    （用户要求：进球被取消时**也要弹窗**，只是提示取消进球）。
 *
 * 细节：
 *  - 足球用 `graphicsLayer { rotationZ }` 旋转（只影响绘制，不触发重新布局，36 帧能耗很低）；
 *  - **涉及的那一方队名用深红**（[GoalEvent.side]：进球方 / 被取消进球方），一眼看出是谁；
 *  - 比分放进白色胶囊，避免和队名糊成一片；
 *  - 入场 = 从上方滑入 + 淡入 + 轻微放大回弹（`scaleIn`）；
 *    出场由宿主先置 `leaving` 触发 [visible]=false，走 `exit` 分支（淡出 + 上移 + 微缩）。
 */
@Composable
private fun GoalPopupCard(e: GoalEvent, visible: Boolean = true, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "ballSpin")
    val rot by inf.animateFloat(
        0f, 360f,
        // 取消进球时放慢一倍：动作本身就暗示「这个球没了」
        infiniteRepeatable(tween(if (e.cancelled) 2400 else 1200, easing = LinearEasing)),
        label = "rot"
    )
    val bg1 = if (e.cancelled) C_CANCEL_BG1 else C_GOAL_BG1
    val bg2 = if (e.cancelled) C_CANCEL_BG2 else C_GOAL_BG2
    val ink = if (e.cancelled) C_CANCEL_INK else C_GOAL_INK
    val ink2 = if (e.cancelled) C_CANCEL_INK2 else C_GOAL_INK2
    val hl = if (e.cancelled) C_CANCEL_HL else C_GOAL_HL
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { -40 }) +
            fadeIn(tween(200)) +
            scaleIn(initialScale = 0.86f, animationSpec = tween(220)),
        exit = fadeOut(tween(200)) +
            slideOutVertically(targetOffsetY = { -24 }, animationSpec = tween(220)) +
            scaleOut(targetScale = 0.94f, animationSpec = tween(220))
    ) {
        Card(
            // 固定宽高：所有卡片一样大（用户要求「长度和高度保持一致」），叠在一起时边缘严格对齐。
            Modifier.width(GOAL_POPUP_W).height(GOAL_POPUP_H),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(bg1, bg2)))
                    .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 圆形白底 + 旋转足球（取消时压暗 + 转慢，视觉上「这个球作废了」）
                Box(
                    Modifier
                        .width(46.dp).height(46.dp)
                        .background(Color.White.copy(alpha = if (e.cancelled) 0.55f else 0.75f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "⚽",
                        fontSize = 27.sp,
                        modifier = Modifier.graphicsLayer {
                            rotationZ = rot
                            alpha = if (e.cancelled) 0.45f else 1f
                        }
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (e.cancelled) "取消进球" else "进球！",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (e.cancelled) "NO GOAL" else "GOAL",
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ink2
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            e.home,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = if (e.side == 1) hl else ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Box(
                            Modifier.padding(horizontal = 6.dp)
                                .background(Color.White.copy(alpha = if (e.cancelled) 0.7f else 0.85f), RoundedCornerShape(7.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            // 「哪边进球，哪边比分红」：比分串拆成主/客两段分别上色，
                            // 2-1 里的 2 是主队进球数、1 是客队进球数，各归各的颜色才看得懂是谁进的。
                            val sp = splitScore(e.score)
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = if (e.side == 1) hl else ink)) { append(sp.left) }
                                    if (sp.sep.isNotEmpty()) withStyle(SpanStyle(color = ink)) { append(sp.sep) }
                                    withStyle(SpanStyle(color = if (e.side == 2) hl else ink)) { append(sp.right) }
                                },
                                fontSize = 16.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            e.away,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = if (e.side == 2) hl else ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    // 联赛行**始终占位**（没有联赛就留空）：保证每张卡片内部的三行位置完全一致，
                    // 不会「有联赛的那张字偏上、没联赛的那张偏下」。
                    Box(Modifier.height(13.dp), contentAlignment = Alignment.CenterStart) {
                        if (e.league.isNotEmpty()) {
                            Text(
                                e.league, fontSize = 10.sp,
                                color = ink2, maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeagueBlock(name: String, colorHex: String) {
    Box(
        Modifier.width(COL_LEAGUE_W).fillMaxHeight().defaultMinSize(minHeight = ROW_MIN_H)
            .background(parseHexColor(colorHex)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name,
            color = Color.White,
            // 5 字及以上的联赛名降到 8.5sp 争取单行（46dp 内 5×8.5=42.5dp 放得下）；
            // 4 字以内维持 9sp，不为少数长名牺牲绝大多数的清晰度。
            fontSize = if (name.length >= 5) 8.5.sp else 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 时间格：默认显示开赛时间（[LiveMatch.time]，HH:mm）；**官方举牌补时公布后**改显粗体「补N'」
 * （N = [LiveMatch.injuryTime]，来自手机版 livechange.txt 字段 [21]），参照站截图时间列「补4'」。
 * 补时未公布（injuryTime 为空）时时间列照常显示开赛时间。
 *
 * 🔴 颜色本轮**从红改回主文字色**（用户要求「时间那列不要用红色」）：时间列是**静态**信息
 *    （开赛时刻 / 官方举牌值不会自己跳），红色在整张表里代表「正在变的东西」（比分、走时），
 *    一列不会动的数字染红，等于把红色的语义稀释掉 —— 用户扫一眼分不清哪块才是实时的。
 */
@Composable
private fun TimeCell(m: LiveMatch) {
    val sp = stoppageText(m)
    Box(
        Modifier.width(COL_TIME_W).defaultMinSize(minHeight = ROW_MIN_H),
        contentAlignment = Alignment.Center
    ) {
        Text(
            sp ?: m.time,
            fontSize = 11.sp,
            fontWeight = if (sp != null) FontWeight.Bold else FontWeight.Normal,
            color = C_TEXT,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 仅当**官方已举牌补时**（`state ∈ {1,3}` 且 [LiveMatch.injuryTime] 非空）时返回「补N'」文案，否则 null。
 * N 直接取官方举牌值（如 `"4"` → 「补4'」），不自行计算——补时没出来就老老实实显示开赛时间，
 * 这正是修复「补时没公布却显示 13'」的要点（旧实现误用 bfdata [22]/[23] 比赛统计）。
 */
private fun stoppageText(m: LiveMatch): String? {
    return if ((m.state == 1 || m.state == 3) && m.injuryTime.isNotEmpty()) "补${m.injuryTime}'" else null
}

/**
 * 状态格（参照 live.titan007.com）。
 *
 * ## 颜色（用户口径）
 * 用户明确要求「状态那列红色字体」，所以**走时是红的**（[C_TICK] = 主题的 `Up`）；
 * 同时他要求「时间那列不要用红色」—— 于是红色在整张表里的语义被收敛成一句话：
 * **「会自己变的东西才配红」**（比分、走时、角球/大小球这类实时数），静态的开赛时刻不配。
 *  - 走时（state 1..4）→ [C_TICK]（红）+ **加粗**，紧跟着一颗每秒闪的黑色逗号（[BlinkComma]）；
 *  - 中场（state == 2）→ [C_HALF]（次灰），暂停态不该比走时更亮；
 *  - 完场（state == -1）→ [C_HALF]，终态无需强调；
 *  - 推迟/腰斩（state < 0）→ [C_POSTPONED] 琥珀色（独立语义：既非进行中也非完场）；
 *  - 未开始（state == 0）→ **留空**（开赛时间已在「时间」列，不重复写「未开赛」）。
 *
 * ## 文案
 *  - 上半场：常规 →「X」；补时（>45）→「45+X」（X = elapsedMin-45）。
 *  - 下半场：常规 →「X」；补时（>90）→「90+X」。
 *  - 加时（state == 4）→「X」（elapsedMin 已含 +90）。
 *  - 🔴 **数字后面不带 `'`**：那个撇号由 [BlinkComma] 那枚会闪的逗号承担（见下方实现注释）。
 *  - 「补N'」在**时间列**显示，见 [TimeCell] / [stoppageText]：时间列是官方举牌值，状态列是真实已过分钟。
 * elapsedMin 来自 bfdata [12]（半场开始时刻）+ 服务器校正时间；change.xml 每 5 秒覆盖刷新。
 */
@Composable
private fun StatusCell(m: LiveMatch) {
    Box(
        Modifier.width(COL_STATUS_W).defaultMinSize(minHeight = ROW_MIN_H),
        contentAlignment = Alignment.Center
    ) {
        val txt: String
        val color: Color
        val bold: Boolean
        // 时钟**在走**才闪那颗逗号：上下半场 / 加时算，中场（state 2，暂停态）与完场都不算。
        var ticking = false
        when {
            m.state == -1 -> { txt = "完场"; color = C_HALF; bold = false }
            m.state in 1..4 -> {
                val e = m.elapsedMin
                // 🔴 **走时后面不再写那个撇号**（用户要求「去掉红色那个，用动态黑色的代替红色那个位置」）：
                //    旧文案是 `"79'"`，那个 `'` 是红色的，再叠上一颗每秒闪动的黑色逗号
                //    ⇒ 右上角同时出现「红撇 + 黑逗」两个记号，看着像重影。
                //    现在撇号交给 [BlinkComma] 去画：它就是那枚「会闪的撇号」，
                //    闪的时候是「79'」，灭的时候是「79」—— 正好用「一明一暗」表达「秒在走」。
                //    ⚠️ 别把 `'` 加回来，否则又变两个。
                txt = if (e <= 0) "进行中"
                else when (m.state) {
                    2 -> "中"                                   // 中场：参照站只显示「中」
                    1 -> if (e <= 45) "$e" else "45+${e - 45}"
                    3 -> if (e <= 90) "$e" else "90+${e - 90}"
                    else -> "$e"                               // 4 = 加时上半
                }
                color = if (m.state == 2) C_HALF else C_TICK
                bold = true
                ticking = m.state != 2
            }
            m.state < 0 -> { txt = "推迟"; color = C_POSTPONED; bold = true }
            else -> { txt = ""; color = C_SUB; bold = false }
        }
        // 这一层 Box 跟着文字走（不是跟着 34dp 的列宽走），逗号才能贴在**文字**右上角；
        // 贴列宽的话短文本（「81'」）会看着逗号离时间很远，像另一个元素。
        Box {
            Text(
                txt,
                // 🔴 补时（「45+6」「90+12」）必须降字号：它是状态列里唯一的 5～6 字符形态，
                //    11sp 粗体下实测宽约 32dp，而列宽只有 34dp —— 再赶上「90+12」就直接顶出列外，
                //    视觉上跟左边的时间「10:00」粘成一片（实测截图确认）。
                //    9.5sp 下「90+12」约 33dp 仍能落在列内，常规的「81'」「完场」「中」保持 11sp 不动。
                fontSize = when {
                    txt.contains('+') -> 9.5.sp
                    bold -> 11.sp
                    else -> 10.sp
                },
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                color = color,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
            if (ticking && txt.isNotEmpty()) {
                BlinkComma(Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

/**
 * 走时右上角那颗**每秒闪一次**的逗号（用户要求：「一秒闪动一次，直观表示时间在动」）。
 *
 * ## 为什么是逗号
 * 参照站（live.titan007.com）的走时后面就挂着一个像「'」的小记号，像秒针在跳。
 * 逗号比圆点更贴「右上角」这个位置 —— 它天然是句子末尾向上的笔画，读起来顺。
 *
 * ## 为什么用 alpha 而不是换成两帧文字
 * 换文字会让这行每 500ms 重新排版一次（LazyColumn 里十几行同时变 ⇒ 掉帧）。
 * 这里只改绘制层透明度（`graphicsLayer { alpha }` 在绘制阶段求值，**不触发重组**），
 * 排版一次都不重算。
 *
 * 颜色：亮色主题下就是黑色（[TextMain]）；深色主题自动转浅 —— 写死 `Color.Black`
 * 会在深底上看不见，那颗逗号就等于没画。
 */
@Composable
private fun BlinkComma(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "statusTick")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        // 前 500ms 实心、后 500ms 完全消失 —— **硬切**而不是渐隐：
        // 渐隐会长时间停在半透明灰（实测截图里像「有个脏点」），硬切才像秒针在跳。
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                1f at 0
                1f at 499
                0f at 500
                0f at 999
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "commaAlpha"
    )
    Text(
        ",",
        // 🔴 位置**就是原来那个红色撇号的位置**（用户要求「用动态黑色的代替红色那个位置」）：
        //    走时文案里已经不再带 `'`（见 [StatusCell]），这枚逗号是它唯一的替身。
        //    ⚠️ 别再加负的 y 偏移，也别把 `'` 写回文案 —— 前者会让逗号飘到数字头顶，
        //    后者会变回「一红一黑两个逗号」的重影。
        //
        //    实测校准（1260px / density 3.5，「90+1」那一行）：逗号右边缘贴在**文字排版右边缘**上，
        //    而数字的真实墨迹比排版右边缘窄 4～6px ⇒ 只给 0.5dp 时墨迹正好压住最后一个数字的右上角
        //    （截图里像给数字加了个重音符号）。让出 2dp 之后，墨迹起点落到数字右侧约 3px 处，
        //    与原撇号「数字 + 一小段间隙」的观感一致；纵向用 `y = 0` 时墨迹与原撇号重合（y 916..925）。
        modifier.offset(x = 2.dp).graphicsLayer { this.alpha = alpha },
        fontSize = 10.sp,
        lineHeight = 10.sp,
        fontWeight = FontWeight.Bold,
        color = C_TICK_INK,
        maxLines = 1
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.VsCell(m: LiveMatch) {
    // 🔴 宽度用 weight(1f) 而不是写死 226dp：主表不再横向滚动后，比分区应当**吃满屏幕剩下的宽度**，
    //    窄屏（320dp）自动变窄、宽屏（412dp）自动变宽，不再靠硬编码数字凑一屏。
    Row(
        Modifier.weight(1f).defaultMinSize(minHeight = ROW_MIN_H).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 进球闪色：进球方队名底色 #BBBB22 持续 30 秒（参照 live.titan007.com）；
        // 进球被取消时同样闪 30 秒，但换成玫红底色（flashCancel），一眼能分清「进球」还是「进球取消」。
        val flashActive = m.flashSide != 0 &&
            System.currentTimeMillis() - m.flashAt < 30_000L
        // 主队（右对齐到比分，最长两行，绝不压住比分）。
        // 版式：`主队名[黄][红]  比分  [黄][红]客队名` —— 角标与队名**零间距紧贴**（用户要求）。
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            TeamName(
                text = m.home,
                flash = flashActive && m.flashSide == 1,
                flashCancel = m.flashCancel,
                flashKey = m.flashAt,
                modifier = Modifier.weight(1f),
                align = TextAlign.End
            )
            // 联赛排名：紧贴队名（用户要求「队伍加排名」）。放在队名与红黄牌之间 ⇒
            // 主客两半都是「队名 → 排名 → 牌 → 比分」的镜像顺序，排名永远挨着自己的队名。
            RankTag(m.homeRank)
            // 角标必须贴**比分这一侧**：贴在队名外侧时，客队那一半会被屏幕右边缘切掉
            // （整表 524dp > 屏宽 360dp），用户不横向滑动就完全看不到客队牌。
            CardBadges(m.homeYellow, m.homeRed)
        }
        Spacer(Modifier.width(1.dp))
        // 比分（红、加粗、居中，固定 50dp）；进行到中场及以后在下方小字加括号显示半场比分。
        // 长按手势在 VsCell 整层（见上），这里只负责显示。
        Box(
            Modifier.width(50.dp).padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    m.score,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (m.state == 0) C_SUB else C_SCORE,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
                // 半场比分：仅中场(2)/下半场(3)/加时(4)/完场(-1) 显示；未开始/上半场(0/1)不显示（半场尚未产生）
                if (m.state >= 2 || m.state == -1) {
                    Text(
                        "(半 ${m.homeHalf}-${m.awayHalf})",
                        fontSize = 8.5.sp,
                        color = C_SUB,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Spacer(Modifier.width(1.dp))
        // 客队（左对齐，最长两行）
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            CardBadges(m.awayYellow, m.awayRed)
            RankTag(m.awayRank)
            TeamName(
                text = m.away,
                flash = flashActive && m.flashSide == 2,
                flashCancel = m.flashCancel,
                flashKey = m.flashAt,
                modifier = Modifier.weight(1f),
                align = TextAlign.Start
            )
        }
    }
}

/**
 * 队名（含进球闪色底）。
 *  - [flash] 为真时底色 [C_GOAL_FLASH]（#BBBB22）；
 *  - [flashCancel] 为真时改用 [C_GOAL_CANCEL_FLASH]（玫红）—— 同一个位置、同一个 30 秒窗口，
 *    只换颜色：橄榄黄＝刚进球，玫红＝这个球被取消了；
 *  - [flashKey]（= flashAt）变化时重置倒计时；即使之后没有任何重组，也由 [LaunchedEffect]
 *    在 30 秒窗口结束时自动熄灭（[flashKey] 相同时不重复起协程）。
 */
@Composable
private fun TeamName(
    text: String,
    flash: Boolean,
    flashKey: Long,
    modifier: Modifier = Modifier,
    align: TextAlign,
    flashCancel: Boolean = false
) {
    var lit by remember(flashKey) { mutableStateOf(flash) }
    LaunchedEffect(flashKey) {
        if (flash) {
            val remain = 30_000L - (System.currentTimeMillis() - flashKey)
            if (remain > 0) delay(remain)
            lit = false
        } else {
            lit = false
        }
    }
    Box(
        modifier
            .background(if (lit) (if (flashCancel) C_GOAL_CANCEL_FLASH else C_GOAL_FLASH) else Color.Transparent)
            // 水平只留 1dp：用户要求「红黄牌和球队名称紧挨着」，留白一多就看着像两块东西
            .padding(horizontal = 1.dp, vertical = 1.dp)
    ) {
        Text(
            text,
            Modifier.fillMaxWidth(),
            // 11.5sp（原 12sp）：本机屏宽只有 360dp，队名每侧只有 ~60dp，
            // 12sp 时每行只放 4.9 字（9 字队名必然 3 行被截），11.5sp 刚好 5.2 字、两行 10 字。
            fontSize = 11.5.sp,
            lineHeight = 12.5.sp,
            color = C_TEXT,
            // 用户要求「球队名称全部显示，不行显示两行」：一行放不下就折第二行。
            // 仍留 Ellipsis 兜底 —— 两行还放不下的极端长名（>10 字）宁可截断也不挤掉比分。
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = align
        )
    }
}

/**
 * 队名旁的红黄牌角标组：`[黄N][红M]`，**0 张就不画**（与源站一致，没牌的行完全不留空位）。
 *
 * 顺序固定「黄在前、红在后」：黄牌几乎每场都有、红牌少见，把稀有的放外侧不会打断扫读节奏。
 */
@Composable
private fun CardBadges(yellow: Int, red: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.5.dp)
    ) {
        if (yellow > 0) CardBadge("$yellow", C_CARD_YELLOW, C_CARD_YELLOW_INK)
        if (red > 0) CardBadge("$red", C_CARD_RED, Color.White)
    }
}

/**
 * 队名旁的**联赛排名**：`[3]`，7.5sp 红字，空串不画（源站没给排名的场次如杯赛/友谊赛）。
 *
 * ## 为什么做得这么小
 * 队名实得宽度只有 ~60dp（= 比分区每侧 83dp − 红黄牌 23dp）。`[11]` 在 7.5sp 下约 15dp，
 * 已经是能看清的下限；再大就变成「名字只剩 3 个字」，用户宁可不要排名。
 * 与竞彩赛程卡 [com.example.jingcai.ui.MatchCard] 的 `[排名]` 同一形状与色系（红），
 * 两个页面对「排名」的观感保持一致。
 *
 * ## 为什么不带阶段前缀
 * 源站原值有 `秋11`（秋季赛 11 名）、`北3`（北区）、`埃及超2` 这类「阶段+名次」，
 * 前缀在 60dp 里要吃掉一个字 ⇒ 解析层只留末尾数字（见 [TitanLiveParser.rankOf]）。
 */
@Composable
private fun RankTag(rank: String) {
    if (rank.isEmpty()) return
    Text(
        "[$rank]",
        fontSize = 7.5.sp,
        lineHeight = 8.sp,
        color = C_SCORE,
        maxLines = 1
    )
}

/**
 * 单个红/黄牌角标：12dp 高的实心小方块 + 数字。
 *
 * 尺寸刻意做得比文字还矮（队名 12sp ≈ 16dp 行高）：队名可用宽度只有 ~70dp，
 * 两个角标吃掉 24dp 就意味着少显示两个字，所以这里压到 8.5sp / 11dp 宽，
 * 远看是「一点颜色」，近看能读出张数 —— 和参考站的小图片角标是同一个信息密度。
 */
@Composable
private fun CardBadge(text: String, bg: Color, ink: Color) {
    Box(
        Modifier.height(12.dp).defaultMinSize(minWidth = 11.dp)
            .background(bg, RoundedCornerShape(1.5.dp))
            .border(0.5.dp, C_CARD_LINE, RoundedCornerShape(1.5.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 1.5.dp),
            fontSize = 8.5.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            color = ink,
            maxLines = 1
        )
    }
}

/**
 * 比分直播主列表的滚动位置（进程内记忆，不落盘、零 IO）。
 *
 * 为什么需要：`LazyListState` 的位置靠 NavHost 的 saveable 恢复，但它和**首帧布局**谁先到
 * 没有保证；从「动画直播」返回本页时列表正被 5s tick / 15s 全量重建刷新，首帧一旦被夹到 0，
 * 用户看到的就是「返回后跑到最上面」。这里额外在进程内留一份，供 `rememberLazyListState(initial*)`
 * 直接把位置建对（不靠事后跳转，因此不会闪一下）。
 *
 * 只记录**非筛选**的主列表；投注记录进入的筛选列表不参与，避免两边位置互相污染。
 */
private object LiveScrollMemo {
    @Volatile
    var index: Int = 0
}

/**
 * 解析源站 `background-color`（带 `#` 前缀）。容错：非法值退回灰色，绝不崩。
 */
private fun parseHexColor(hex: String): Color {
    val h = hex.removePrefix("#").trim()
    if (h.length != 6) return Color(0xFF888888)
    val rgb = h.toLongOrNull(16) ?: return Color(0xFF888888)
    val r = ((rgb shr 16) and 0xFF).toInt()
    val g = ((rgb shr 8) and 0xFF).toInt()
    val b = (rgb and 0xFF).toInt()
    return Color(0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
}
