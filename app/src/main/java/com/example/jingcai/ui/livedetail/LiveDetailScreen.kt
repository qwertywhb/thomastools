package com.example.jingcai.ui.livedetail

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.jingcai.data.model.EventKind
import com.example.jingcai.data.model.FlashSchedule
import com.example.jingcai.data.model.LineupMark
import com.example.jingcai.data.model.LineupPlayer
import com.example.jingcai.data.model.MatchInjuries
import com.example.jingcai.data.model.MatchLineup
import com.example.jingcai.data.model.PlayerMark
import com.example.jingcai.data.model.TechSection
import com.example.jingcai.data.model.TechStat
import com.example.jingcai.data.model.TeamLineup
import com.example.jingcai.data.network.ApiClient
import com.example.jingcai.data.network.TitanDetailParser
import com.example.jingcai.data.repository.LiveDetailRepository
import kotlinx.coroutines.delay
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.Background
import com.example.jingcai.ui.theme.TextMain
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.Flat

/**
 * 「动画直播」详情页（对齐 live.titan007.com/detail/<id>cn.htm）。
 *
 * 只做两件事（用户要求）：
 *  1. **动画直播**：**WebView 内嵌官方 `flashDetail/flash.html`** —— 自己拿 Canvas 画做不到
 *     进攻折线只留最近 3 条、角球/进球特效，所以改回官方页面（echarts + lottie + WebSocket）；
 *     页面逻辑宽 809px（球场 500px + 时间轴 81px），故把 viewport 改写成 `width=809` 再由
 *     `loadWithOverviewMode` 整体缩放到手机宽度。
 *  2. **本场技术统计**：官方「本场技术统计」同样的 10 行（角球/半场角球/黄牌/射门/射正/进攻/
 *     危险进攻/射门不中/控球率/半场控球率），主队橙条（左）、客队蓝条（右）。
 *
 * 数据源：直播中走 `detailin.js`（滚动窗口），完场走 `detail/<id>cn.htm` 服务端渲染 HTML —— 见 [TitanDetailParser]。
 * 进入方式：比分直播列表点某行（[com.example.jingcai.ui.live.LiveScreen] 的 MatchRow）。
 */

private val HomeOrange = Color(0xFFFF6A00)
private val AwayBlue = Color(0xFFC62828)

// 正文 / 次文字 / 页底改走主题（原先写死 #333 / #999 / #F5F6F8 ⇒ 深色模式下整页还是亮的，
// 而黑字一旦跟随深色底就又看不见）。动画本身在 WebView 里，仍是官方的深色页面，不受影响。
private val C_TEXT: Color
    @Composable @ReadOnlyComposable get() = TextMain
private val C_SUB: Color
    @Composable @ReadOnlyComposable get() = TextSub
private val C_BG: Color
    @Composable @ReadOnlyComposable get() = Background

/** 官方动画页的逻辑宽度（`eventNew.js` 里 `infoWidth = 809`）与其总高（球场 500 + 时间轴 81 + 余量）。*/
private const val PAGE_W = 809f

/**
 * 视口高度：官方声明的是 500（球场）+ 81（时间轴）= 581，但实测底部时间轴仍会被切掉一条，
 * 说明页面里还有额外的高度（内联 `document.writeln` 的容器 + 时间轴自身 padding）。
 * 给到 600 后完整显示；多出来的几像素是页面自身的深色底，看不出来。
 */
private const val PAGE_H = 600f

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/**
 * 注入到官方 `flash.html` 的 `<style>`（替换 `</head>`）。
 *
 * 1. `body{margin:0}`：官方页无 reset，body 自带 8px margin ⇒ 球场右移、底部时间轴被切一条。
 * 2. **进攻/危险进攻/控球阴影对齐（用户实测「阴影好像偏上了」）**：
 *    官方 CSS 是 `background: url(jg.svg) no-repeat right top; background-size: 100%`，
 *    即「按盒子宽度等比缩放 + 顶端对齐」。实测盒子里子 `394×470`，图 `jg.svg` 是 229×217，
 *    按宽度缩放后高度只有 `394/1.055 ≈ 373` ⇒ **盒子底部空出 ~97px**，阴影只在球场上半截，
 *    看着就是「偏上」。改成 `100% 100%` 让它铺满盒子（盒子本身已按 `top:3%; height:94%`
 *    在球场内居中），阴影上下就与球场对齐了。
 */
private val PATCH_HEAD = """
<style>
body{margin:0;background:#12321F;}
.ant .liveBox .attackBG,
.ant .liveBox .DAttackBG,
.ant .liveBox .ctrlBG{background-size:100% 100% !important;}
</style></head>
"""

@Composable
fun LiveDetailScreen(
    titanId: Long,
    homeName: String,
    awayName: String,
    onBack: () -> Unit
) {
    val repo = remember { LiveDetailRepository() }

    var schedule by remember { mutableStateOf<FlashSchedule?>(null) }
    var techSections by remember { mutableStateOf<Map<Int, List<TechStat>>>(emptyMap()) }
    var techLoaded by remember { mutableStateOf(false) }
    var techSection by remember { mutableIntStateOf(TechSection.ALL) }
    /**
     * 「没有实时源」标记：**进行中**的比赛却只能退回详情页内联快照
     * （= 该场不在 `detailin.js` 的 `tT_f` 12~13 场滚动窗口里）。
     * 那份内联值是服务端渲染时写死的开赛初期快照，会明显滞后，
     * 必须明确标成「暂无实时统计」，不能让它冒充实时数据（用户报的错就在这）。
     * 完场本来就该用内联，不算 stale。
     */
    var techStale by remember { mutableStateOf(false) }

    /**
     * 首发 / 替补名单（详情页 `div#matchBox2`）。
     * 与技术统计共用同一份 `detail/<id>cn.htm`（约 65KB），所以拉取统一走下面的 [detailPage] 缓存，
     * 别各自再发一次请求。
     */
    var lineup by remember { mutableStateOf<MatchLineup?>(null) }
    /** 伤停名单：与首发阵容共用同一份详情页 HTML（[loadLineup] 里一起解析）。 */
    var injuries by remember { mutableStateOf<MatchInjuries?>(null) }
    var lineupLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(titanId) {
        // 1) 场次头（比分 / 状态）—— 首屏一次
        try {
            schedule = TitanDetailParser.parseFlashScheduleFull(repo.fetchFlashFull(titanId))
        } catch (_: Exception) {
            // 取不到就显示 0-0 / 空状态，动画仍由 WebView 自行加载
        }

        /**
         * 2) 技术统计：**来源按比赛状态切换，与官方 `scoreOddsNew.js` 的 `MakeTeamTechHtml()` 同口径**
         *    （它第一行就是 `if (state == -1) { 用 techStatisticData }`，否则一律用 `tT_f`）。
         *
         *  - **进行中 / 未开赛** → `detailin.js` 的 `tT_f[<id>]`：~30s 增量、字段全（含 xG / 得分机会）；
         *  - **完场** → 详情页内联 `techStatisticData`：`tT_f` 只有 12~13 场滚动窗口，完场早已滚出；
         *    而完场页的 `ul#teamTechDiv` 本身是空的（JS 拿内联变量渲染），只有它带「全场/上半场/下半场」分节。
         *
         * 🔴 顺序反过来（先 HTML）会把**开赛初期的陈旧快照**当成实时统计：
         * 实测 2910953（格雷米奥 1-0 巴拉纳竞技，进行中）页面内联值是
         * 角球 1-0 / 射门 1-0 / 控球 22%:78% / 传球 0-7 / xG 0.05，
         * 同一时刻 `tT_f` 是 角球 6-8 / 射门 17-14 / 控球 31%:69% / 传球 220:495 / xG 1.31:1.08，
         * 后者才与官方网页显示一致（用户报的「技术统计是错的」就是这条）。
         */
        var finalLoaded = false

        /**
         * 详情页 HTML 的短时缓存：技术统计（完场/兜底）与首发名单读的是同一个页面，
         * 不缓存就会在首屏连发两次 65KB。TTL 30s 足够让两处共享一次请求。
         */
        var detailHtml = ""
        var detailHtmlAt = 0L
        suspend fun detailPage(): String {
            val now = System.currentTimeMillis()
            if (detailHtml.isNotEmpty() && now - detailHtmlAt < 30_000L) return detailHtml
            val s = try {
                repo.fetchDetailPage(titanId)
            } catch (_: Exception) {
                ""
            }
            if (s.isNotEmpty()) {
                detailHtml = s
                detailHtmlAt = now
            }
            return s
        }

        suspend fun loadTech(): Boolean {
            val finished = schedule?.state == -1
            // 完场只取一次，避免每 60 秒重复拉 75KB 页面
            if (finished && finalLoaded) return true

            // 1) 进行中：先走增量文件（官方同源的实时统计）
            if (!finished) {
                val live = try {
                    TitanDetailParser.parseTech(repo.fetchDetailIn(), titanId)
                } catch (_: Exception) {
                    emptyList()
                }
                if (live.isNotEmpty()) {
                    techSections = mapOf(TechSection.ALL to live)
                    techStale = false
                    techLoaded = true
                    return true
                }
            }

            // 2) 完场，或该场已滚出 tT_f 窗口：用详情页内联分段
            val secs = try {
                TitanDetailParser.parseTechSections(detailPage())
            } catch (_: Exception) {
                emptyMap()
            }
            if (secs.isNotEmpty()) {
                techSections = secs
                // 进行中却只能拿到内联 ⇒ 该场不在实时窗口内，标为「暂无实时统计」
                techStale = !finished
                techLoaded = true
                if (finished) finalLoaded = true
                return true
            }

            // 3) 双源都空：完场后 tT_f 可能仍在窗口内，再兜一次
            val live = try {
                TitanDetailParser.parseTech(repo.fetchDetailIn(), titanId)
            } catch (_: Exception) {
                emptyList()
            }
            if (live.isNotEmpty()) {
                techSections = mapOf(TechSection.ALL to live)
                techStale = false
            } else if (!finished) {
                // 比赛在打，但实时源没有、内联也没有 ⇒ 如实标「暂无实时统计」
                techStale = true
            }
            techLoaded = true
            return live.isNotEmpty()
        }
        loadTech()

        /**
         * 3) 首发 / 替补：名单本身一场只变一次（最多红黄牌、换人图标在变），
         *    所以首屏拉一次、之后每 5 分钟跟一次；完场后名单固定，不再请求。
         */
        suspend fun loadLineup() {
            if (schedule?.state == -1 && lineup != null) return
            val html = detailPage()
            if (html.isNotEmpty()) {
                lineup = TitanDetailParser.parseLineup(html)
                injuries = TitanDetailParser.parseInjuries(html)
            }
            lineupLoaded = true
        }
        loadLineup()

        // 4) 轻量轮询：比分/状态 5 秒；技术统计 60 秒（完场后不再刷新）
        var tick = 0
        while (true) {
            delay(5_000)
            try {
                TitanDetailParser.parseFlashScheduleChange(repo.fetchFlashChange(titanId))?.let { s ->
                    schedule = (schedule ?: s).copy(
                        homeScore = s.homeScore, guestScore = s.guestScore, state = s.state
                    )
                }
            } catch (_: Exception) {
                // 单次失败忽略
            }
            tick++
            if (tick % 12 == 0) loadTech()
            if (tick % 60 == 0) loadLineup()
        }
    }

    Column(Modifier.fillMaxSize().background(C_BG)) {
        TopBar(homeName, awayName, schedule, onBack)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ===== 动画直播（官方页面内嵌） =====
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xFF12321F))
            ) {
                FlashWebView(
                    url = remember(titanId, homeName, awayName) {
                        repo.flashPageUrl(titanId, homeName, awayName)
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(PAGE_W / PAGE_H)
                )
            }

            // ===== 本场技术统计（全场 / 上半场 / 下半场） =====
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Surface)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    val rows = techSections[techSection]
                        ?: techSections[TechSection.ALL]
                        ?: emptyList()
                    Text(
                        "本场技术统计",
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        color = C_TEXT, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    // 进行中但没有实时源：表格里若是开场快照，明确标出来，别让人当实时数据看
                    if (techStale && rows.isNotEmpty()) {
                        Text(
                            "暂无实时统计，以下为最近快照",
                            Modifier.fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                // 中性底 + 语义橙字（原先浅橙底写死 ⇒ 深色下是一块亮条）
                                .background(SurfaceAlt)
                                .padding(vertical = 4.dp),
                            color = Flat, fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    // 时段切换：只有拿到半场分段（完场）才出现；直播中只有「全场」一段，显示 tab 反而是死按钮
                    if (techSections.size > 1) {
                        TechSectionTabs(
                            available = techSections.keys,
                            selected = techSection,
                            onSelect = { techSection = it }
                        )
                    }
                    HorizontalDivider(color = Line)
                    when {
                        rows.isNotEmpty() -> rows.forEach { s -> TechRow(s) }
                        !techLoaded -> Box(
                            Modifier.fillMaxWidth().height(80.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(strokeWidth = 2.dp) }
                        // 比赛在打却两源都没数据 ⇒ 是「没有实时统计」，不是「这场比赛没有统计」
                        techStale -> Text(
                            "暂无实时统计",
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            color = Color(0xFFB26B00), fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        else -> Text(
                            "暂无技术统计",
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            color = C_SUB, fontSize = 13.sp, textAlign = TextAlign.Center
                        )
                    }
                }
            }
            // ===== 首发阵容 / 替补（用户要求：排在技术统计下面） =====
            val lu = lineup
            if (lu != null) {
                Spacer(Modifier.height(10.dp))
                LineupCard("首发阵容", homeName, awayName, lu.home, lu.away, starters = true)
                if (lu.home.subs.isNotEmpty() || lu.away.subs.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LineupCard("替补", homeName, awayName, lu.home, lu.away, starters = false)
                }
            } else if (lineupLoaded && schedule?.state != 0) {
                // 已开赛/完场却刮不到名单：说清楚，别让人以为漏做了
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Surface)
                ) {
                    Text(
                        "暂无首发阵容",
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        color = C_SUB, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                }
            }
            // 伤停：只在源站真有名单时才画（多数场次这块是「暂无数据」）
            injuries?.let { inj ->
                Spacer(Modifier.height(10.dp))
                InjuryCard(homeName, awayName, inj.home, inj.away)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 内嵌官方动画页。
 *
 * 两个必须做的事：
 *  1. `shouldInterceptRequest` 把主文档的 viewport 由 `width=device-width` 改写成 `width=809`
 *     —— 官方页面是按 809×500 固定像素排版的，用手机宽度排版会把球场挤成一条；
 *     子资源（同目录下的 script 与 css，走相对路径）仍按原 URL 解析，不受影响。
 *  2. 拦截一切页面跳转（`shouldOverrideUrlLoading` 返回 true）—— 动画页点球员会跳外链，
 *     我们要留在本页（用户要求本页只看动画 + 技术统计）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FlashWebView(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(false) }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        textZoom = 100
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = DESKTOP_UA
                    }
                    setBackgroundColor(AColor.parseColor("#12321F"))
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val u = request.url?.toString() ?: return null
                            if (!u.contains("/flashDetail/flash.html")) return null
                            return try {
                                val html = ApiClient.get(u, "https://live.titan007.com/")
                                    .replace(
                                        "content=\"width=device-width, initial-scale=1\"",
                                        "content=\"width=809\""
                                    )
                                    // 官方页面没有 reset，body 自带 8px margin —— 会让球场右移、
                                    // 底部时间轴被切掉一条。整页逻辑尺寸固定 809×581（球场 500 + 时间轴 81），
                                    // 只清 body 的 margin 即可严格对齐；不要再加 overflow:hidden（会让页面白屏）。
                                    .replace("</head>", PATCH_HEAD)
                                WebResourceResponse(
                                    "text/html", "utf-8", html.byteInputStream()
                                )
                            } catch (_: Exception) {
                                null   // 自己取失败就让 WebView 正常加载
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean = true

                        override fun onPageFinished(view: WebView, url: String) {
                            ready = true
                        }
                    }
                    loadUrl(url)
                }
            }
        )

        if (!ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun TopBar(
    homeName: String,
    awayName: String,
    schedule: FlashSchedule?,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(Surface)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", Modifier.clickable { onBack() }.padding(horizontal = 8.dp),
                fontSize = 26.sp, color = C_TEXT, fontWeight = FontWeight.Bold)
            Text("动画直播", fontSize = 16.sp, color = C_TEXT, fontWeight = FontWeight.Bold)
            // 状态放在标题栏右侧 —— 动画区右上角是官方的「GOAL / 角球」特效位，会被盖住
            val st = statusText(schedule)
            if (st.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                Text(
                    st,
                    Modifier.background(HomeOrange.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    color = HomeOrange, fontSize = 11.sp, fontWeight = FontWeight.Medium
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                homeName, Modifier.weight(1f), fontSize = 14.sp, color = C_TEXT,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End
            )
            Text(
                "${schedule?.homeScore ?: 0} - ${schedule?.guestScore ?: 0}",
                Modifier.padding(horizontal = 12.dp), fontSize = 17.sp,
                color = Color(0xFFF04545), fontWeight = FontWeight.Bold
            )
            Text(
                awayName, Modifier.weight(1f), fontSize = 14.sp, color = C_TEXT,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start
            )
        }
        HorizontalDivider(color = Line)
    }
}

/** 状态文案（只用 state，可靠）：0 未开始 / 1 上半场 / 2 中场 / 3 下半场 / 4 加时 / -1 完场 / 其余 推迟。*/
private fun statusText(s: FlashSchedule?): String {
    if (s == null) return ""
    return when (s.state) {
        0 -> "未开始"
        1 -> "上半场"
        2 -> "中场"
        3 -> "下半场"
        4 -> "加时"
        -1 -> "完场"
        else -> "推迟"
    }
}

/**
 * 「全场 / 上半场 / 下半场」胶囊切换（参照官方详情页 `ChangeTeamTechDetail`）。
 * 只渲染**确实有数据**的时段：直播中只有全场，硬塞两个半场按钮点进去是「暂无数据」。
 */
@Composable
private fun TechSectionTabs(available: Set<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            Modifier.background(SurfaceAlt, RoundedCornerShape(50)).padding(2.dp)
        ) {
            TechSection.LABELS.forEachIndexed { idx, label ->
                if (idx !in available) return@forEachIndexed
                val on = idx == selected
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) Color.White else Color.Transparent)
                        .clickable { onSelect(idx) }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        label,
                        color = if (on) C_TEXT else Color(0xFF888888),
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * 首发/替补卡片：白底圆角，与「本场技术统计」同一种卡片；内部左主右客两栏 + 中间竖线。
 *
 * [starters] = true 时额外画队头（队名 + 阵型 + 主教练），替补卡不重复队头（上面刚看过）。
 */
@Composable
private fun LineupCard(
    title: String,
    homeName: String,
    awayName: String,
    home: TeamLineup,
    away: TeamLineup,
    starters: Boolean
) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(10.dp)).background(Surface)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                title,
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                color = C_TEXT, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (starters) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    TeamHeadCell(homeName, home, HomeOrange, Modifier.weight(1f).padding(end = 8.dp))
                    TeamHeadCell(awayName, away, AwayBlue, Modifier.weight(1f).padding(start = 8.dp))
                }
                HorizontalDivider(color = Line)
            }
            LineupTwoColumns(
                left = if (starters) home.starters else home.subs,
                right = if (starters) away.starters else away.subs
            )
        }
    }
}

/** 队头：`布鲁明 4-2-3-1` + `主教练 xxx`（字号比名单略小，不抢球员名）。*/
@Composable
private fun TeamHeadCell(name: String, t: TeamLineup, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            if (t.formation.isEmpty()) name else "$name ${t.formation}",
            color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (t.coach.isNotEmpty()) {
            Text(
                "主教练 ${t.coach}",
                color = C_SUB, fontSize = 10.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 两栏名单：左主右客，中间 1dp 竖线。
 * `height(IntrinsicSize.Min)` 是竖线撑满较高一栏的前提（否则 `fillMaxHeight` 高度是 0）。
 */
@Composable
private fun LineupTwoColumns(left: List<LineupPlayer>, right: List<LineupPlayer>) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).height(IntrinsicSize.Min)
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            left.forEach { LineupRow(it) }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            right.forEach { LineupRow(it) }
        }
    }
}

/** 名单一行：`号 | 姓名 | C | 标记`。姓名用 `fill=false` 的 weight —— 名字长时要截断，不能把标记挤出屏幕。*/
@Composable
private fun LineupRow(p: LineupPlayer) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            p.number, Modifier.width(22.dp),
            color = C_SUB, fontSize = 10.5.sp, textAlign = TextAlign.End, maxLines = 1
        )
        Spacer(Modifier.width(5.dp))
        Text(
            p.name, Modifier.weight(1f, fill = false),
            color = C_TEXT, fontSize = 12.5.sp, lineHeight = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (p.captain) {
            Spacer(Modifier.width(2.dp))
            Text("C", fontSize = 9.sp, color = Color(0xFFE08A00), fontWeight = FontWeight.Bold)
        }
        p.marks.forEach { LineupMarkGlyph(it) }
    }
}

/** 球员标记：黄/红牌＝色块，进球＝⚽，换入/换出＝↑/↓，助攻＝「助」。*/
@Composable
private fun LineupMarkGlyph(m: PlayerMark) {
    Spacer(Modifier.width(4.dp))
    when (m.kind) {
        EventKind.YELLOW -> Box(
            Modifier.width(6.dp).height(8.dp)
                .background(Color(0xFFF5C518), RoundedCornerShape(1.dp))
        )
        EventKind.RED, EventKind.SECOND_YELLOW -> Box(
            Modifier.width(6.dp).height(8.dp)
                .background(Color(0xFFE23A3A), RoundedCornerShape(1.dp))
        )
        EventKind.GOAL -> Text("⚽", fontSize = 9.5.sp, lineHeight = 10.sp)
        EventKind.OWN_GOAL -> Text("乌", fontSize = 8.sp, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
        EventKind.PENALTY -> Text("点", fontSize = 8.sp, color = Color(0xFF8E6CC8), fontWeight = FontWeight.Bold)
        LineupMark.SUB_ON -> Text("↑", fontSize = 10.sp, lineHeight = 10.sp, color = Color(0xFF2FA84F), fontWeight = FontWeight.Bold)
        LineupMark.SUB_OFF -> Text("↓", fontSize = 10.sp, lineHeight = 10.sp, color = C_SUB, fontWeight = FontWeight.Bold)
        LineupMark.ASSIST -> Text("助", fontSize = 8.sp, color = Color(0xFF3B82C4))
        else -> Unit
    }
}

/** 技术统计一行：主队橙条（右对齐向左伸） | 主值 | 名称 | 客值 | 客队蓝条（左对齐向右伸）。*/
@Composable
private fun TechRow(s: TechStat) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).height(10.dp), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.fillMaxWidth((s.homePre / 100f).coerceIn(0f, 1f)).height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(HomeOrange)
            )
        }
        Text(s.home, Modifier.width(44.dp), color = HomeOrange, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1)
        // 名称列 88dp：最长的「错失得分机会」6 字 × 12sp = 72dp，原来给 72dp 会被吃掉一个「会」字
        Text(s.name, Modifier.width(88.dp), color = C_TEXT, fontSize = 12.sp,
            textAlign = TextAlign.Center, maxLines = 1)
        Text(s.guest, Modifier.width(44.dp), color = AwayBlue, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Start, maxLines = 1)
        Box(Modifier.weight(1f).height(10.dp), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier.fillMaxWidth((s.guestPre / 100f).coerceIn(0f, 1f)).height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(AwayBlue)
            )
        }
    }
}

/**
 * 伤停卡片（与 [LineupCard] 同一套外观：白底圆角 + 顶部两队列头）。
 *
 * 每行是源站原始文本，不做字段拆分 —— 理由见 [com.example.jingcai.data.model.MatchInjuries]。
 * 一侧为空写「—」，不留白（留白会被读成「还在加载」）。
 */
@Composable
private fun InjuryCard(
    homeName: String,
    awayName: String,
    home: List<String>,
    away: List<String>
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(10.dp)).background(Surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                homeName, fontSize = 12.sp, color = C_TEXT, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text("伤停", fontSize = 11.sp, color = C_SUB, fontWeight = FontWeight.Bold)
            Text(
                awayName, fontSize = 12.sp, color = C_TEXT, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = Line, thickness = 0.6.dp)
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.weight(1f)) {
                if (home.isEmpty()) {
                    Text("—", fontSize = 11.5.sp, color = C_SUB)
                } else {
                    home.forEach { Text(it, fontSize = 11.5.sp, color = C_TEXT, lineHeight = 16.sp) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                if (away.isEmpty()) {
                    Text("—", fontSize = 11.5.sp, color = C_SUB)
                } else {
                    away.forEach {
                        Text(
                            it, fontSize = 11.5.sp, color = C_TEXT, lineHeight = 16.sp,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}
