package com.example.jingcai.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 网络客户端：统一 OkHttp + User-Agent + Referer，支持 GBK 解码（okooo）。
 * 体彩接口需要 Referer: https://www.sporttery.cn/ 否则 WAF 拦截（567）。
 */
object ApiClient {
    /**
     * 🔴 UA = OkHttp 默认 UA（本项目依赖 `com.squareup.okhttp3:okhttp:4.12.0`，见 app/build.gradle，
     * 故取 `okhttp/4.12.0`，与网络栈一致）。
     *
     * okooo 的 WAF 对 UA 限流/拉黑（2026-10-23 实测）：
     *  - `Mozilla/5.0 (Linux; Android 13) ... Chrome/126.0 Mobile Safari/537.36` 连发即 **405**（8/8 全 405）；
     *  - `okhttp/4.12.0` / `okhttp/4.9.0` / `okhttp/3.14.9` / `Dalvik/2.1.0 (Linux; U; Android 13)` 连发全 **200**（6/6）；
     *  - 真实手机 Chrome UA 时好时坏（3×200 后 405）；桌面 Chrome UA 历史上最常被拦。
     * ⇒ 不要用浏览器 UA；限流是「UA + 频率」双重判定，换 UA 后 App 内短时间连点多场仍可能偶发 405，属预期内。
     * 其它站（sporttery / titan007 / qiumibao）对本 UA 全部 200，已逐站连发实测，无需按 host 分支。
     */
    private const val UA = "okhttp/4.12.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** GET 请求，返回字符串；[charset] 指定解码字符集（okooo 用 GBK）。 */
    fun get(url: String, referer: String? = null, charset: String = "UTF-8"): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (referer != null) b.header("Referer", referer)
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            return String(bytes, Charset.forName(charset))
        }
    }

    /**
     * GET 并返回 `(响应体, 服务器 Date 头解析出的 epoch 毫秒)`。
     * Date 头为 RFC1123（GMT），用于校正设备时钟漂移 —— titan007 的「进行 X'」以服务器时间为准，
     * 设备时钟若有偏差会让分钟数整体偏慢/偏快。Date 头缺失或解析失败时为 -1L。
     */
    fun getWithServerTime(url: String, referer: String? = null, charset: String = "UTF-8"): Pair<String, Long> {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (referer != null) b.header("Referer", referer)
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val serverMs = resp.header("Date")?.let { parseHttpDate(it) } ?: -1L
            return String(bytes, Charset.forName(charset)) to serverMs
        }
    }

    private val HTTP_DATE_FMT = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("GMT")
    }
    private fun parseHttpDate(s: String): Long? = try { HTTP_DATE_FMT.parse(s)?.time } catch (_: Exception) { null }
}
