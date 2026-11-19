package com.example.jingcai.data.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 从 GitHub Releases 读版本信息。
 *
 * ## 为什么用「API + Release 资产」而不是 raw 文件
 * 用户明确要求「apk 和 json 都放 GitHub、不要走 jsDelivr」。放在 Release 资产里的好处是
 * 一个 Release 就是一次发版快照，APK 与 update.json 天然同版本，不会出现「json 说 1.2、
 * apk 还是 1.1」的错配；raw 分支文件则会随提交漂移。
 *
 * ## 请求次数
 * 一次检查最多 2 个请求：① `GET /repos/{o}/{r}/releases/latest`；② 若该 Release 里挂了
 * [UpdateConfig.ASSET_JSON]，再 GET 它的下载地址。没有 json 也能用（回落到 tag / body / 首个 .apk 资产）。
 *
 * ## 🔴 一切异常都吞掉，返回 null
 * 用户要求「访问不通就不提示更新」。GitHub 在国内经常不可达，这里的失败必须是**完全静默**
 * 的：不弹窗、不 Toast、不重试、不写日志到界面。调用方据此当作「没有更新」处理。
 */
object UpdateRepository {

    /** GitHub API 强制要求 User-Agent，缺失直接 403；这里不用浏览器 UA，避免被当成爬虫。 */
    private const val UA = "jingcai-updater/1.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 拉最新 Release；无网络 / 404 / 403 限流 / 解析失败 一律 null。 */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!UpdateConfig.configured) return@withContext null
        runCatching { fetchOnce() }.getOrNull()
    }

    private fun fetchOnce(): UpdateInfo? {
        val api = String.format(
            Locale.US,
            "https://api.github.com/repos/%s/%s/releases/latest",
            UpdateConfig.OWNER, UpdateConfig.REPO
        )
        val body = getText(api) ?: return null
        val root = JsonParser.parseString(body).asJsonObject

        val tag = str(root, "tag_name")
        val notes = str(root, "body")

        // 资产表：名字 → (下载直链, 字节数, SHA-256)。GitHub 的资产顺序不可依赖，按名字取。
        val assets = LinkedHashMap<String, Asset>()
        root.getAsJsonArray("assets")?.forEach { e ->
            val o = runCatching { e.asJsonObject }.getOrNull() ?: return@forEach
            val name = str(o, "name")
            val url = str(o, "browser_download_url")
            if (name.isNotEmpty() && url.isNotEmpty()) {
                assets[name] = Asset(
                    url = url,
                    size = o.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    // `digest` 形如 `sha256:9296…`（GitHub 2025 年起对资产直接给哈希）。
                    // 有了它，**同一个 versionCode 重新打包**也能被识别出来 —— 见 [UpdateManager.check]。
                    sha256 = str(o, "digest").removePrefix("sha256:").trim().lowercase(Locale.US)
                )
            }
        }

        val firstApk = assets.keys.firstOrNull { it.endsWith(".apk", ignoreCase = true) }.orEmpty()
        var info = UpdateInfo(
            versionCode = versionCodeOfTag(tag),
            versionName = tag.trimStart('v', 'V'),
            apkUrl = assets[firstApk]?.url.orEmpty(),
            changelog = notes,
            sizeBytes = assets[firstApk]?.size ?: 0L,
            sha256 = assets[firstApk]?.sha256.orEmpty()
        )

        // update.json 优先：版本号 / 说明 / 校验值都能显式指定，不依赖 tag 写法
        assets[UpdateConfig.ASSET_JSON]?.url?.let { url ->
            getText(url)?.let { txt ->
                runCatching { JsonParser.parseString(txt).asJsonObject }.getOrNull()
                    ?.let { info = merge(info, it, assets) }
            }
        }

        // 缺「可比版本号」或缺 APK 直链 ⇒ 当作没有更新（宁可不提示，也不提示一个装不了的版本）
        return info.takeIf { it.versionCode > 0 && it.apkUrl.isNotEmpty() }
    }

    private fun merge(base: UpdateInfo, j: JsonObject, assets: Map<String, Asset>): UpdateInfo {
        val directUrl = str(j, "apkUrl")
        val apkName = str(j, "apk")
        val url = directUrl.ifBlank { assets[apkName]?.url ?: base.apkUrl }
        val size = assets[apkName]?.size ?: base.sizeBytes
        // 🔴 **tag 与 update.json 的版本号取较大值**，不是「json 无条件覆盖 tag」。
        //    事故（2026-11-06 排查）：v9 那个 Release 里挂的是 v8 的 APK + v8 的 update.json
        //    （两者 digest 与 v8 逐字节相同），json 里还写着 `versionCode: 8`；老逻辑用 json
        //    覆盖 tag ⇒ 远端报 8，装 8 的机器判定「已是最新」，用户怎么传新包都不提示。
        //    取 max 后 tag=v9 能兜住这种「复制上一版发版」的手滑。
        val jsonCode = j.get("versionCode")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        return base.copy(
            versionCode = maxOf(jsonCode, base.versionCode),
            versionName = str(j, "versionName").ifBlank { base.versionName },
            apkUrl = url,
            changelog = str(j, "changelog").ifBlank { base.changelog },
            sizeBytes = size,
            // json 里显式给的 sha256 优先（作者手填的是发布包的哈希）；没有就沿用资产 digest
            sha256 = str(j, "sha256").trim().lowercase(Locale.US).ifBlank { base.sha256 },
            force = j.get("force")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        )
    }

    /** GitHub Release 里的一个资产。 */
    private data class Asset(val url: String, val size: Long, val sha256: String)

    /**
     * 从 Tag 取整数版本号：只认「v3」「3」这种**纯数字**写法。
     *
     * 🔴 不猜 `v1.2.3` 该映射成几号：猜错会导致「永远提示有更新」（版本号偏低）或
     *    「永远不提示」（偏高）。拿不到就让调用方依赖 update.json 的显式 versionCode。
     */
    private fun versionCodeOfTag(tag: String): Int =
        tag.trim().trimStart('v', 'V').toIntOrNull() ?: 0

    private fun str(o: JsonObject, key: String): String =
        o.get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

    private fun getText(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    }.getOrNull()
}
