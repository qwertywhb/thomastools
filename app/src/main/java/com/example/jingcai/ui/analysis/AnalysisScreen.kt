package com.example.jingcai.ui.analysis

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.jingcai.data.model.AnalysisRow
import com.example.jingcai.data.model.AnalysisSection
import com.example.jingcai.data.model.MatchAnalysis
import com.example.jingcai.data.network.TitanAnalysisParser
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.LineSoft
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.Background
import com.example.jingcai.ui.theme.TextMain
import com.example.jingcai.ui.theme.TextSub
import com.example.jingcai.ui.theme.SurfaceAlt
import com.example.jingcai.ui.theme.Up
import com.example.jingcai.ui.theme.LoseBlue

/**
 * 「数据分析」二级页 —— 只放两块：**双方交手战绩** 与 **近期战绩**。
 *
 * ## 为什么数据要用 WebView 取，不用 OkHttp
 * 源站 `zq.titan007.com` 的 WAF 只放行 Chromium 的 TLS/HTTP2 指纹。实测（2026-11-09，本机与真机各一轮）：
 * | 客户端 | 结果 |
 * |---|---|
 * | OkHttp + `okhttp/4.12.0` + h2 | `StreamResetException: PROTOCOL_ERROR` |
 * | OkHttp + `okhttp/4.12.0` + h1 | `IOException: unexpected end of stream` |
 * | OkHttp + 移动 Chrome UA + h2 / h1 | 同上两条 |
 * | 系统浏览器 / Chrome | 正常 200 |
 * ⇒ 数据仍由**原生 Compose 渲染**（不是网页套壳），WebView 只当「取 HTML 的传输层」：
 * 它加载页面后把两块板块的 `outerHTML` 分块回传，原生用 [TitanAnalysisParser] 解析。
 *
 * ## 只取两块
 * 页面 30 个板块里用户只要「对赛往绩」「近期战绩」，所以 JS 里按 `h2.fx_title2` 的**文案**
 * 找 `div.porletP`，两块拼起来回传（约 40~60KB），比整页 400KB 小一个数量级。
 */
@Composable
fun AnalysisScreen(
    titanId: Long,
    homeName: String,
    awayName: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val url = remember(titanId) { "https://zq.titan007.com/analysis/${titanId}cn.htm" }

    var analysis by remember(titanId) { mutableStateOf<MatchAnalysis?>(null) }
    var loading by remember(titanId) { mutableStateOf(true) }

    AnalysisHtmlLoader(url) { html ->
        analysis = html?.let { runCatching { TitanAnalysisParser.parse(it) }.getOrNull() }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(PageBg)) {
        AnalysisTopBar(homeName, awayName, onBack)
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = Color(0xFF9AA7B4))
                    Spacer(Modifier.height(10.dp))
                    Text("正在加载数据分析…", color = C_SUB, fontSize = 12.sp)
                }
            }

            analysis != null -> {
                val a = analysis!!
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 交手战绩里没有「我们」这一侧的概念，按**本场主队**着色（用户要求）
                    item { H2hCard(a.h2h, a.homeRecent.title) }
                    item { RecentCard(a.homeRecent, a.awayRecent) }
                    item { SourceHint() }
                }
            }

            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("数据分析加载失败", color = C_TEXT, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "源站对非浏览器内核有访问限制，可改用浏览器打开",
                        color = C_SUB, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "用浏览器打开",
                        Modifier.clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF2D7FF9))
                            .clickable {
                                // 不要直接 ACTION_VIEW：本 App 的 deeplink 可能抢走 http(s) 链接，
                                // 用 chooser 让用户明确选浏览器
                                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                runCatching { ctx.startActivity(Intent.createChooser(i, "打开数据分析")) }
                            }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        color = Color.White, fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * 取源站 HTML：隐藏 WebView 加载 → JS 抽出两块板块 → 分块回传（Binder 单次事务有 1MB 上限，
 * 整页 400KB 一次性回传风险太大，这里 20KB 一段）。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun AnalysisHtmlLoader(url: String, onDone: (String?) -> Unit) {
    val cb = rememberUpdatedState(onDone)
    val finished = remember(url) { AtomicBoolean(false) }
    var web by remember(url) { mutableStateOf<WebView?>(null) }

    fun finish(html: String?) {
        if (finished.compareAndSet(false, true)) cb.value(html)
    }

    // 25 秒拿不到就认失败（源站页面带大量外链，真正需要的是 SSR 出来的两张表）
    LaunchedEffect(url) {
        delay(25_000)
        finish(null)
    }
    DisposableEffect(url) {
        onDispose {
            web?.let {
                runCatching { it.stopLoading() }
                runCatching { it.removeJavascriptInterface(BRIDGE_NAME) }
                runCatching { it.destroy() }
            }
        }
    }

    AndroidView(
        modifier = Modifier.size(1.dp).alpha(0f),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                // 只要 DOM：关掉图片能省掉页面里一多半请求，两块表格都是 SSR 的，不依赖图片
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; V2227A) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                addJavascriptInterface(
                    AnalysisBridge { html -> finish(html.ifBlank { null }) },
                    BRIDGE_NAME
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) {
                        view.evaluateJavascript(EXTRACT_JS, null)
                    }

                    // 🔴 只允许「首屏那份 titan007 文档」在 WebView 里加载。
                    //    实测：分析页带外链广告位，页面里的 `intent://` / `market://` / 站外链接
                    //    会把整个 App 甩到第三方 App（真机复现过一次，直接跳出本 App 的开屏广告）。
                    //    非 http(s) 一律丢弃；主文档跳出 titan007 也丢弃 ⇒ 抽不到表就当失败，
                    //    绝不让广告把用户带走。
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: android.webkit.WebResourceRequest
                    ): Boolean {
                        val u = request.url?.toString().orEmpty()
                        val http = u.startsWith("http://") || u.startsWith("https://")
                        if (!http) return true
                        if (request.isForMainFrame && !u.contains("titan007.com")) return true
                        return false
                    }

                    // 子资源失败（图片/统计脚本）不当作失败：主文档能到就有表
                    override fun onReceivedError(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        error: android.webkit.WebResourceError
                    ) {
                        if (request.isForMainFrame) finish(null)
                    }
                }
                loadUrl(url)
                web = this
            }
        }
    )
}

private const val BRIDGE_NAME = "JingcaiAna"

/**
 * JS：按标题文案找板块，把 `outerHTML` 分块推回原生。
 * - 用 `textContent.indexOf` 而不是 `querySelector('#porlet_8')`：板块序号随页面排序变；
 * - `parentNode` 回溯到 `div.porletP`（标题的父节点就是板块容器，但多包一层也不会错）；
 * - 段与段之间用 `\u0001` 分隔，原生侧可据此拆分（目前只需拼接成一份 HTML 交给 jsoup）。
 */
private val EXTRACT_JS = """
(function(){
  var hs=document.querySelectorAll('h2.fx_title2');
  var blocks=[];
  for(var i=0;i<hs.length;i++){
    var t=hs[i].textContent||'';
    if(t.indexOf('对赛往绩')<0 && t.indexOf('近期战绩')<0) continue;
    var p=hs[i].parentNode;
    while(p && p.nodeType===1 && !((p.className||'')+'').match(/porletP/)) p=p.parentNode;
    if(!p || p.nodeType!==1) p=hs[i].parentNode;
    if(p) blocks.push(p.outerHTML||'');
  }
  var out=blocks.join('\u0001');
  var bridge=window.$BRIDGE_NAME;
  if(!out || !bridge){ if(bridge) bridge.finish(); return; }
  var i=0, step=20000;
  var timer=setInterval(function(){
    if(i>=out.length){ clearInterval(timer); bridge.finish(); }
    else { bridge.chunk(out.substring(i,i+step)); i+=step; }
  }, 8);
})();
""".trimIndent()

/** JS 桥：`chunk` 在 JavaBridge 线程被高频调用，`finish` 表示传完。*/
private class AnalysisBridge(private val onFinish: (String) -> Unit) {
    private val buf = StringBuilder()
    private var done = false

    @JavascriptInterface
    fun chunk(s: String) {
        synchronized(this) { if (!done) buf.append(s) }
    }

    @JavascriptInterface
    fun finish() {
        val html: String?
        synchronized(this) {
            html = if (done) null else { done = true; buf.toString() }
        }
        if (html != null) onFinish(html)
    }
}

@Composable
private fun AnalysisTopBar(home: String, away: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Surface)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹", Modifier.clickable { onBack() }.padding(horizontal = 8.dp),
                fontSize = 26.sp, color = C_TEXT, fontWeight = FontWeight.Bold
            )
            Text("数据分析", fontSize = 16.sp, color = C_TEXT, fontWeight = FontWeight.Bold)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                home, Modifier.weight(1f), fontSize = 14.sp, color = C_TEXT,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End
            )
            Text(
                "VS", Modifier.padding(horizontal = 12.dp),
                fontSize = 12.sp, color = C_SUB, fontWeight = FontWeight.Medium
            )
            Text(
                away, Modifier.weight(1f), fontSize = 14.sp, color = C_TEXT,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start
            )
        }
        HorizontalDivider(color = Line)
    }
}

/**
 * 「双方交手」卡：标题 + 汇总（近 10 场 4胜2平4负 · 胜率 40%）+ 行。
 *
 * [focusHome] = 本场主队名：交手记录里主客位置每场都可能反过来，所以按**队名**定位到本场主队那一侧，
 * 再用**那一场的赛果**给队名上色（胜红 / 平黑 / 负蓝）。
 */
@Composable
private fun H2hCard(s: AnalysisSection, focusHome: String) {
    SectionCard(title = "双方交手", summary = summaryText(s)) {
        if (s.rows.isEmpty()) {
            EmptyLine("暂无交锋记录")
        } else {
            s.rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = LineSoft, thickness = 0.6.dp)
                AnalysisLine(r, focusHome)
            }
        }
    }
}

/** 「近期战绩」卡：同一张卡里上下两段（主队 / 客队），每段带自己的汇总。*/
@Composable
private fun RecentCard(home: AnalysisSection, away: AnalysisSection) {
    SectionCard(title = "近期战绩", summary = "") {
        TeamRecentBlock(home, "主队")
        if (home.rows.isNotEmpty() && away.rows.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color(0xFFE8E8E8), thickness = 0.8.dp)
            Spacer(Modifier.height(6.dp))
        }
        TeamRecentBlock(away, "客队")
    }
}

/**
 * 近期战绩的一段：段头（`主队` 标签 + 队名 + 汇总）+ 该队的比赛行。
 *
 * `主队`/`客队` 只是**分区标签**，与赛果无关，所以两段用**同一套中性配色**；
 * 真正带颜色的只有行内队名（胜红 / 平黑 / 负蓝）。
 */
@Composable
private fun TeamRecentBlock(s: AnalysisSection, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            tag, Modifier.clip(RoundedCornerShape(3.dp)).background(ChipBg)
                .padding(horizontal = 5.dp, vertical = 1.dp),
            fontSize = 9.5.sp, color = ChipFg, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(6.dp))
        Text(
            s.title.ifEmpty { "—" }, Modifier.weight(1f),
            fontSize = 13.sp, color = C_TEXT, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(summaryText(s), fontSize = 10.sp, color = C_SUB, maxLines = 1)
    }
    if (s.rows.isEmpty()) {
        EmptyLine("暂无近期战绩")
        return
    }
    s.rows.forEachIndexed { i, r ->
        if (i > 0) HorizontalDivider(color = LineSoft, thickness = 0.6.dp)
        AnalysisLine(r, s.title)
    }
}

@Composable
private fun SectionCard(title: String, summary: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 14.sp, color = C_TEXT, fontWeight = FontWeight.Bold)
            if (summary.isNotEmpty()) Text(summary, fontSize = 10.sp, color = C_SUB, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = Color(0xFFEDEDED))
        Spacer(Modifier.height(2.dp))
        content()
    }
}

/**
 * 一行战绩：
 * ```
 * 26-04-25 墨西联            半1-0 · 角3-12   胜
 *        普埃布拉     1-2     克雷塔罗
 * ```
 * 用两行而不是一张 7 列表格：本机视口 360dp，7 列必然横向滚动，而横滑时最想看的队名会被挤出屏幕。
 *
 * [focusName] 是本段要盯的队（主队段=主队、客队段=客队、交手=本场主队）。历史对阵里主客位置
 * 会互换，所以**按队名匹配**而不是按左右位置。
 *
 * 🔴 颜色语义：**队名按该场赛果上色 —— 胜红 / 平黑 / 负蓝**（用户 2026-11-09 要求，
 * 参照球探「近期战绩」表的读法：一眼扫队名颜色就知道这支赢了输了）。
 * 赛果由**比分反推**（`2-0`），不用源站的「胜负」列 —— 交手记录的主客顺序会翻转，
 * 源站那一列是「该行主队视角」，直接用会在客队视角下把胜负判反。
 * 右侧的 胜/平/负 **标签统一中性色**，只作文字标注，不再承担颜色语义。
 */
@Composable
private fun AnalysisLine(r: AnalysisRow, focusName: String = "") {
    val homeFocus = sameTeam(r.home, focusName)
    val awayFocus = !homeFocus && sameTeam(r.away, focusName)
    // 有焦点队就按它算赛果（标签与队名同源）；焦点队不在这行、或比分缺失时退回源站给的结果
    val result = if (homeFocus || awayFocus) {
        resultOf(r.score, homeFocus).ifEmpty { r.result }
    } else r.result
    val nameColor = nameColorOf(result)
    val nameBold = homeFocus || awayFocus
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.date, fontSize = 10.5.sp, color = C_SUB, maxLines = 1)
            if (r.league.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    // 联赛小标签：底走主题（原先写死 #F4F6F8 ⇒ 深色下是一串白块），字也跟随次文字色
                    r.league, fontSize = 9.sp, color = ChipFg,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp))
                        .background(ChipBg).padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            val extra = buildString {
                if (r.halfScore.isNotEmpty()) append("半 ").append(r.halfScore)
                if (r.corner.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append("角 ").append(r.corner)
                }
            }
            if (extra.isNotEmpty()) {
                Text(extra, fontSize = 9.5.sp, color = C_SUB, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            ResultTag(result)
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                r.home, Modifier.weight(1f),
                fontSize = 12.5.sp,
                color = if (homeFocus) nameColor else C_TEXT,
                fontWeight = if (homeFocus && nameBold) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End
            )
            Text(
                r.score, Modifier.padding(horizontal = 10.dp),
                fontSize = 13.sp, color = ScoreRed, fontWeight = FontWeight.Bold, maxLines = 1
            )
            Text(
                r.away, Modifier.weight(1f),
                fontSize = 12.5.sp,
                color = if (awayFocus) nameColor else C_TEXT,
                fontWeight = if (awayFocus && nameBold) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start
            )
        }
    }
}

/**
 * 赛果小标签：**胜/平/负统一中性配色**。
 *
 * 颜色语义已经交给队名（胜红/平黑/负蓝），标签再上色会出现「同一行两套红绿蓝」，
 * 既抢视线又容易误读，所以标签只作文字标注。
 */
@Composable
private fun ResultTag(result: String) {
    if (result.isEmpty()) {
        Spacer(Modifier.width(18.dp))
        return
    }
    Text(
        result,
        Modifier.width(20.dp).clip(RoundedCornerShape(3.dp)).background(ChipBg),
        color = ChipFg, fontSize = 10.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(vertical = 18.dp),
        color = C_SUB, fontSize = 12.sp, textAlign = TextAlign.Center
    )
}

/** 源站署名（数据来源，用户能对上号）。*/
@Composable
private fun SourceHint() {
    Text(
        "数据来源：球探体育（zq.titan007.com）",
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        color = Color(0xFFB0B8C0), fontSize = 9.sp, maxLines = 1
    )
}

/** 汇总文案：`近10场 4胜2平4负 · 胜率40%`；源站没给汇总时返回空串。*/
private fun summaryText(s: AnalysisSection): String {
    if (!s.hasSummary) return ""
    val sb = StringBuilder("近${s.total}场 ")
    sb.append("${s.win}胜${s.draw}平${s.loss}负")
    if (s.winRate.isNotEmpty()) sb.append(" · 胜率").append(s.winRate)
    return sb.toString()
}

/**
 * 队名是否指同一支队。
 *
 * 源站同一支队在不同表格里会写全称/简称（`国际圣卡洛斯` / `圣卡洛斯`），直接 `==` 会漏标，
 * 所以先精确比，再退化到包含关系（去掉空格后）。少于 2 个字的短名不参与包含判断，避免「一」这种误命中。
 */
private fun sameTeam(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return true
    val x = a.replace(" ", "")
    val y = b.replace(" ", "")
    if (x.length < 2 || y.length < 2) return false
    return x.contains(y) || y.contains(x)
}

/**
 * 由比分反推「[focusIsHome] 这一侧」的赛果：`胜 / 平 / 负`。
 *
 * 不用源站的「胜负」列：交手记录里主客顺序会翻转，源站那一列是「该行主队视角」，
 * 直接用会把客队视角的胜败判反。比分拿不到（推迟/腰斩/空）时退回源站值。
 */
private fun resultOf(score: String, focusIsHome: Boolean): String {
    val m = Regex("^\\s*(\\d+)\\s*[-:]\\s*(\\d+)\\s*$").find(score) ?: return ""
    val h = m.groupValues[1].toInt()
    val a = m.groupValues[2].toInt()
    if (h == a) return "平"
    val focusWin = if (focusIsHome) h > a else a > h
    return if (focusWin) "胜" else "负"
}

/** 队名颜色：**胜红 / 平黑 / 负蓝**（用户 2026-11-09 指定的口径，参照球探近期战绩表）。*/
@Composable
private fun nameColorOf(result: String): Color = when (result) {
    "胜" -> WinRed
    "负" -> LoseBlue
    "平" -> DrawBlack
    else -> C_TEXT
}

// 🔴 这几个原来是写死的浅色页（#F2F4F7 底 + #333 正文）：深色模式下整页还是亮的，
//    而且正文黑字一旦跟随深色底就会看不见。现在全部改走主题 token，两套配色只维护一份。
private val PageBg: Color
    @Composable @ReadOnlyComposable get() = Background
private val C_TEXT: Color
    @Composable @ReadOnlyComposable get() = TextMain
private val C_SUB: Color
    @Composable @ReadOnlyComposable get() = TextSub
private val ScoreRed: Color
    @Composable @ReadOnlyComposable get() = Up

/** 队名赛果色。平＝正文色（浅色下近黑、深色下亮白，靠加粗区分）。*/
private val WinRed: Color
    @Composable @ReadOnlyComposable get() = Up
private val DrawBlack: Color
    @Composable @ReadOnlyComposable get() = TextMain

/** 标签统一配色（主队/客队/胜平负 都用它，不再承担颜色语义）。*/
private val ChipBg: Color
    @Composable @ReadOnlyComposable get() = SurfaceAlt
private val ChipFg: Color
    @Composable @ReadOnlyComposable get() = TextSub
