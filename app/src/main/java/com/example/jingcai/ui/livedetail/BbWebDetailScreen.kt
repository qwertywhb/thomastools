package com.example.jingcai.ui.livedetail

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.jingcai.ui.ErrorText
import com.example.jingcai.ui.Loading
import com.example.jingcai.ui.theme.Background
import com.example.jingcai.ui.theme.Line
import com.example.jingcai.ui.theme.Primary
import com.example.jingcai.ui.theme.Surface
import com.example.jingcai.ui.theme.TextMain
import com.google.gson.Gson

/**
 * 篮球直播详情：**内嵌 zhibo8 移动版**（`https://m.zhibo8.com/zhibo/nba/<年>/match<id>vl.htm`）。
 *
 * 为什么直接用移动版而不是自己画、也不是改桌面版：
 *  - 移动版是响应式页面（实测 `bodyW == clientW == 360`，无横向溢出），
 *    自带「队徽 + 比分 + 完赛/节次」头部、`直播 / 数据 / 奖金 / 评论 / 赛况 / 战报` 页签，
 *    点「数据」就是技术统计 —— 正是用户要的「文字直播 + 数据」；
 *  - 桌面版 `www.zhibo8.com/...` 是 1020px 三栏（左正文 620 + 右评论 390），
 *    必须在 WebView 里注入大量 CSS 硬改才能塞进手机屏（上一版做法），又脆又难看。
 *
 * 唯一要注入的样式：**隐藏站点自己的顶栏**（直播吧 logo + 「打开 App」），
 * 因为 app 已经有一个返回栏，留着会上下两层头。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BbWebDetailScreen(
    url: String,
    title: String,
    onBack: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 站内跳转优先在 WebView 内回退（详情页里点"交战历史"会走站内链接）
    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { w ->
                w.stopLoading()
                w.loadUrl("about:blank")
                (w.parent as? ViewGroup)?.removeView(w)
                w.destroy()
            }
            webView = null
        }
    }

    Column(Modifier.fillMaxSize().background(Background)) {
        // 顶部返回栏（app 自己的，所以 WebView 里的直播吧导航要隐藏）
        Row(
            Modifier.fillMaxWidth().background(Surface).height(50.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("←", fontSize = 20.sp, color = TextMain, fontWeight = FontWeight.Bold)
            }
            Text(
                title,
                Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "刷新",
                Modifier.clickable {
                    error = null
                    loading = true
                    webView?.reload()
                }.padding(horizontal = 10.dp, vertical = 6.dp),
                fontSize = 12.sp,
                color = Primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        HorizontalDivider(color = Line, thickness = 1.dp)

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val isDebuggable =
                        (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        isVerticalScrollBarEnabled = true
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            // 🔴 必须 false：true 会让布局宽度固定成 980，整页缩成"蚂蚁字"
                            useWideViewPort = false
                            loadWithOverviewMode = false
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            textZoom = 100
                            cacheMode = WebSettings.LOAD_DEFAULT
                            // 页面里混着 http 的图片/接口，不放开会一片空白
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = MOBILE_UA
                        }
                        // 只装 console 转发，页面 JS 报错/自检日志直接进 logcat（tag = BbWeb）
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                                val t = m?.message() ?: return true
                                android.util.Log.d("BbWeb", t)
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            // 🔴 渲染进程崩溃时页面会变白且无法滑动（用户反馈「滑一点就滑不动」）。
                            //    监听崩溃事件后自动 reload 一次；仍未好就重载整个 WebView。
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: android.webkit.RenderProcessGoneDetail?
                            ): Boolean {
                                android.util.Log.e("BbWeb", "render gone, crash=${detail?.didCrash()}")
                                val w = view ?: return true
                                loading = true
                                w.reload()
                                return true
                            }

                            override fun onPageFinished(view: WebView?, u: String?) {
                                super.onPageFinished(view, u)
                                view?.evaluateJavascript(FIT_JS, null)
                                // 只在可调试包注入自检探针（AGP 8 未开 buildConfig，改用 ApplicationInfo 判断）
                                if (isDebuggable) view?.evaluateJavascript(PROBE_JS, null)
                                loading = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                err: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, err)
                                // 只有主文档失败才报错（子资源挂了不影响阅读）
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    error = "页面加载失败，请点右上角「刷新」重试"
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                // 🔴 用户要求「详情页里的页面不要跳转」：一切导航都拦掉，
                                //    只放行**同一份文档**（同 host + 同 path，允许改 #hash）。
                                //    页签（直播/数据/奖金…）是 Vue 内部 pushState，不走这里，因此不受影响。
                                val target = request?.url?.toString() ?: return true
                                val base = url
                                if (target.startsWith(base.substringBefore('#'))) return false
                                return true
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                }
            )
            when {
                loading -> Box(Modifier.fillMaxSize().background(Background)) { Loading() }
                error != null -> Box(Modifier.fillMaxSize().background(Background)) { ErrorText(error) }
            }
        }
    }
}

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

/**
 * 手机版只需三件事：① 藏掉站点顶栏（`.top-header`）；② 禁掉横向溢出；
 * ③ 藏掉列表末尾的「正在加载更多的数据...」占位块。
 * 🔴 **绝对不能给 `html,body` 加 `overflow-x:hidden`**：它会让 `html` 变成新的滚动容器，
 *    实测 `documentElement.scrollHeight == clientHeight`（都 689px）⇒ **整页根本无法滚动**，
 *    卡在顶部永远触发不到 `data-load-more`，用户看到的就是「滑一点就滑不动 + 一直转圈」。
 * 其余保持 zhibo8 移动版原样——它是响应式的，越少干预越稳。
 *
 * 🔴 关于 `.data-load-more`（用户反馈「往下滑一直提示正在加载更多数据」）：
 *    文字直播列表 `.live-box` 的**最后一项**就是它，它既是「还有更早的直播」的占位，
 *    也是滚动到底时触发下一页 XHR（`dingshi4m.qiumibao.com/.../page_10/<sid>.htm`）的哨兵。
 *    下一页一到就插在它前面，所以**只要还有历史，它就永远显示**——不是卡住，是站点设计。
 *    隐藏方式必须用 `opacity:0`：占位块**保留在布局里**（高 34px，位置不变），
 *    滚动监听/可见性判断照旧成立，实测隐藏前后内容增长速度与基线完全一致；
 *    用 `display:none` 会把它踢出布局，有掐断自动加载的风险。
 */
private val OVERRIDE_CSS = """
html,body{max-width:100%!important}
.top-header{display:none!important}
.top-area{padding-top:0!important;margin-top:0!important}
body{padding-top:0!important;margin-top:0!important}
.data-load-more{opacity:0!important}
""".trimIndent()

/** 注入样式（CSS 用 Gson 序列化成 JS 字符串字面量，避免引号/换行把脚本写坏）。 */
private val FIT_JS: String by lazy {
    val cssLiteral = Gson().toJson(OVERRIDE_CSS)
    """
    (function(){
      if (document.getElementById('jc-app-fit')) return;
      var s = document.createElement('style');
      s.id = 'jc-app-fit';
      s.type = 'text/css';
      s.appendChild(document.createTextNode($cssLiteral));
      (document.head || document.documentElement).appendChild(s);
    })();
    """.trimIndent()
}

/**
 * 自检探针（**仅 debug 构建**）：每 2 秒把「加载更多」占位块的真实状态打到 logcat，
 * 用来证明 ① 占位块已隐藏；② 滚动到底时内容**真的在追加**（不是被掐断）。
 * 正式包不会注入（`BuildConfig.DEBUG` 为 false）。
 */
private val PROBE_JS: String by lazy {
    """
    (function(){
      if (window.__jcProbe) return;
      window.__jcProbe = setInterval(function(){
        var m = document.querySelector('.data-load-more');
        var box = document.querySelector('.live-box');
        console.log('JCDBG op=' + (m ? getComputedStyle(m).opacity : '-') +
          ' h=' + (m ? Math.round(m.getBoundingClientRect().height) : '-') +
          ' lines=' + (box ? box.querySelectorAll('.line-content').length : '-') +
          ' txt=' + (document.body.innerText.indexOf('正在加载') >= 0));
      }, 2000);
    })();
    """.trimIndent()
}
