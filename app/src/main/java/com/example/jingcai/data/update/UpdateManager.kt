package com.example.jingcai.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 更新流程的状态机：检查 → 下载 → 拉起系统安装器。
 *
 * ## 🔴 这里**不可能**做到「静默安装」
 * 普通应用没有 `INSTALL_PACKAGES` 权限（系统签名才有）。所以最后一步一定是系统安装器弹窗，
 * 用户点一次「安装」。这不是实现偷懒，是 Android 的安全边界；想真静默只有 root / 系统应用 /
 * 设备管理员（Device Owner，需企业级 MDM 才能配）三条路。
 *
 * ## 一、检查：静默失败
 * 无网 / GitHub 被墙 / 404 / 403 限流 ⇒ **什么也不发生**（用户明确要求「访问不通就不提示更新」）。
 * 只有手动点「检查更新」时才用 Toast 告知结果，否则自动检查失败用户完全无感。
 *
 * ## 二、下载
 * 落到 `getExternalFilesDir()/update/`，先写 `.part` 再改名；下载完做两道校验：
 * 文件头必须是 `PK`（APK 是 zip），以及（若 update.json 提供）SHA-256 必须一致。
 * 校验不过直接删文件并报错，绝不把可疑文件推给安装器。
 *
 * ## 三、安装
 * `FileProvider` + `ACTION_VIEW(application/vnd.android.package-archive)`。
 * 8.0+ 还要「安装未知应用」开关（按应用授权），没开就先跳设置页。
 * 版本号比现有低时系统会拒绝安装，这是防降级的正常保护。
 */
object UpdateManager {

    sealed interface Phase {
        data object Idle : Phase
        data object Checking : Phase
        data class Available(val info: UpdateInfo) : Phase
        data class Downloading(val info: UpdateInfo, val percent: Int) : Phase
        data class Ready(val info: UpdateInfo, val file: File) : Phase
        data class Failed(val message: String) : Phase
    }

    private const val PREF = "update_state"
    private const val KEY_LAST = "lastCheckAt"
    private const val KEY_APK_SIG = "localApkSig"

    /**
     * 上一次检查的结论（一行纯文本）。**只用于排障**：vivo 等 ROM 会把应用 logcat 吞掉，
     * 「明明传了新包却不提示」时，`adb shell run-as … cat shared_prefs/update_state.xml`
     * 就能直接看到远端/本地版本号和两边的 SHA-256，不用再猜。
     */
    private const val KEY_LAST_RESULT = "lastResult"
    private const val TAG = "UpdateCheck"

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    /** 手动检查/下载失败时的一句提示（UI 用 Toast 消费后清空）。 */
    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint

    /**
     * **仅调试包**的弹窗预览：直接塞一个假的 [Phase]，用来给更新弹窗截图/回归。
     *
     * 为什么需要它：GitHub 上的版本号一旦与本地相同、且 SHA 也相同，正常流程永远走不到
     * `Available` 分支，改完 UI 就没法看效果（要么真发一版，要么把 versionCode 改小）。
     * 调用方必须自己校验 `FLAG_DEBUGGABLE`，见 `MainActivity.demoGoalIfRequested`。
     */
    fun demoPreview(kind: String) {
        val info = UpdateInfo(
            versionCode = 99,
            versionName = "1.13",
            apkUrl = "",
            changelog = "· 更新弹窗改为深色记分牌风格\n" +
                "· 比分直播长队名不再挤掉进球图标\n" +
                "· 数据分析支持未开赛场次",
            sizeBytes = 11_171_136L,
            force = false
        )
        _phase.value = when (kind) {
            "downloading" -> Phase.Downloading(info, 47)
            "ready" -> Phase.Ready(info, File(""))
            "failed" -> Phase.Failed("下载中断，请检查网络后重试")
            else -> Phase.Available(info)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /**
     * 本次进程是否已经自动查过。
     *
     * 用**内存标记**而不是「上次检查时间」落盘：进程存活期间绝不重复请求，
     * 进程一重启（用户重新打开 App）就重新查 —— 这正是「每次启动看一次、运行中不打扰」。
     */
    @Volatile
    private var checkedThisProcess = false

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 关闭更新弹窗。
     *
     * 🔴 **纯关闭，什么都不记**（用户明确要求）：这次点「稍后」，下次启动 App 还会照常提示。
     *    只在本次运行里不再弹 —— 因为进程内只检查一次，关掉就没人再问。
     */
    fun dismiss(ctx: Context? = null) {
        if (job?.isActive == true) return
        _phase.value = Phase.Idle
    }

    fun clearHint() {
        _hint.value = null
    }

    /** 当前装的版本号；取不到返回 0（不会因此误提示更新，因为 0 < 任何真实版本）。 */
    fun versionCode(ctx: Context): Int = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)

    fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    fun check(ctx: Context, manual: Boolean) {
        val app = ctx.applicationContext
        if (!UpdateConfig.configured) {
            if (manual) _hint.value = "更新源还没配置（UpdateConfig 里的 OWNER / REPO）"
            return
        }
        if (job?.isActive == true) return
        if (!manual) {
            // 🔴 用户明确要求的节奏：**每次冷启动查一次，运行中不再打扰**。
            //    以前是「6 小时内只查一次」⇒ v6 都发布了，用户开 App 还是不提示（没有任何反馈，
            //    看着像坏了）。现在去掉时间窗，改用**进程内一次性标记**：
            //    进程还活着就绝不重复请求；进程被杀/重启（= 用户又一次启动 App）就再查一次。
            //    点「稍后」不会记任何东西 ⇒ 下次启动照常提示。
            if (checkedThisProcess) {
                Log.d(TAG, "本次进程已查过，跳过")
                return
            }
            checkedThisProcess = true
        }
        job = scope.launch {
            if (manual) _phase.value = Phase.Checking
            val info = UpdateRepository.fetchLatest()
            // 拿不到：自动检查彻底静默；手动检查给一句话，别让按钮像卡死
            if (info == null) {
                Log.d(TAG, "拉取 release 失败（手动=$manual）")
                if (manual) {
                    _phase.value = Phase.Idle
                    _hint.value = "检查失败：网络不通或该仓库还没有 Release"
                }
                return@launch
            }
            prefs(app).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            val local = versionCode(app)

            // 🔴 「同一个 versionCode 又打了一次包」也必须能发现。
            //    用户 2026-11-06 报障「每次给 GitHub 传新 APK，打开 App 都不提示更新」，
            //    查下来有两类：① Release 里塞了上一版的 update.json（v9 挂的就是 v8 的资产，
            //    json 还写着 8）；② 直接替换同一 Release 的 apk 资产、tag 不动。
            //    这两种情况下 versionCode 根本没变 —— 只能靠**比对安装包内容**识别。
            //    远端哈希来自资产的 `digest` 或 update.json 的 sha256，本地哈希来自已安装的 APK。
            val localSha = if (info.versionCode == local && info.sha256.isNotBlank()) {
                withContext(Dispatchers.IO) { localApkSha(app) }
            } else ""
            val repack = info.versionCode == local &&
                localSha.isNotBlank() &&
                !info.sha256.equals(localSha, ignoreCase = true)

            if (info.versionCode < local || (info.versionCode == local && !repack)) {
                Log.d(TAG, "已是最新：远端 v${info.versionCode} / 本地 v$local（内容一致=$localSha）")
                // 落一行诊断：vivo 等 ROM 会吞掉 logcat，出问题只能靠这条 prefs 复盘
                prefs(app).edit()
                    .putString(KEY_LAST_RESULT, "已是最新 remote=${info.versionCode} local=$local " +
                        "remoteSha=${info.sha256} localSha=$localSha")
                    .apply()
                if (manual) {
                    _phase.value = Phase.Idle
                    _hint.value = "已是最新版本 v${versionName(app)}"
                }
                return@launch
            }
            // 用户点「稍后」不留任何痕迹 ⇒ 下次启动照常提示；这里也不再按版本号拦截
            Log.d(TAG, "发现更新 v${info.versionCode}（本地 v$local，同版本重打包=$repack）")
            prefs(app).edit()
                .putString(KEY_LAST_RESULT, "有新版本 remote=${info.versionCode} local=$local " +
                    "repack=$repack remoteSha=${info.sha256} localSha=$localSha")
                .apply()
            _phase.value = Phase.Available(if (repack) info.copy(sameVersion = true) else info)
        }
    }

    fun download(ctx: Context, info: UpdateInfo) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            _phase.value = Phase.Downloading(info, 0)
            try {
                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "update").apply { mkdirs() }
                // 先删旧包：避免磁盘上堆一堆历史版本，也避免「明明下载了新的却装了旧的」
                dir.listFiles()?.forEach { if (it.name.endsWith(".apk") || it.name.endsWith(".part")) it.delete() }
                val target = File(dir, "jingcai-${info.versionCode}.apk")
                val ok = withContext(Dispatchers.IO) { fetchTo(info, target) { pct ->
                    _phase.value = Phase.Downloading(info, pct)
                } }
                if (!ok) {
                    target.delete()
                    _phase.value = Phase.Failed("下载失败，请稍后再试")
                    return@launch
                }
                if (info.sha256.isNotBlank() && !sha256(target).equals(info.sha256, ignoreCase = true)) {
                    target.delete()
                    _phase.value = Phase.Failed("安装包校验不通过（SHA-256 不一致），已丢弃")
                    return@launch
                }
                _phase.value = Phase.Ready(info, target)
                install(ctx, target)
            } catch (e: Exception) {
                _phase.value = Phase.Failed("下载失败：${e.message ?: "网络异常"}")
            }
        }
    }

    /** 拉起系统安装器；返回 false 表示还没给「安装未知应用」权限（已跳设置页）。 */
    fun install(ctx: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0) {
            _hint.value = "安装包不在了，请重新下载"
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            openInstallPermission(ctx)
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        }.getOrElse { e ->
            _hint.value = "无法打开安装器：${e.message}"
            false
        }
    }

    private fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { _hint.value = "请到系统设置里允许本应用安装应用" }
        }
    }

    /**
     * 流式下载，边下边报百分比。返回 false 表示失败（HTTP 非 2xx / 内容不像 APK）。
     * 进度只在 1% 变化时回调，避免 StateFlow 被高频值刷爆（10MB 包 ≈ 数百次回调）。
     */
    private fun fetchTo(info: UpdateInfo, target: File, onProgress: (Int) -> Unit): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        val req = Request.Builder().url(info.apkUrl).header("User-Agent", "jingcai-updater/1.0").build()
        downloadClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            var lastPct = -1
            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        // APK 必须是 zip：头两字节 "PK"，下错文件（HTML 错误页）当场识别
                        if (done == 0L && (n < 2 || buf[0] != 0x50.toByte() || buf[1] != 0x4B.toByte())) {
                            part.delete()
                            return false
                        }
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        }
        if (part.length() <= 0) return false
        return part.renameTo(target)
    }

    private fun sha256(f: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }.getOrDefault("")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 已安装 APK 的 SHA-256（用于「同版本号重打包」识别）。
     *
     * 直接读 `applicationInfo.sourceDir`（本应用自己的包，不需要任何权限），
     * 结果按「路径 + 大小 + mtime」缓存到 prefs —— 10 MB 的包算一次约几十毫秒，
     * 但没必要每次冷启动都重算；覆盖安装后 mtime 变了缓存自然失效。
     *
     * 取不到（非标准安装目录 / IO 失败）时返回空串，调用方据此**不做**内容比对，
     * 宁可不提示，也不要拿一个假哈希去误弹更新框。
     */
    private fun localApkSha(ctx: Context): String {
        val path = runCatching { ctx.applicationInfo.sourceDir }.getOrNull() ?: return ""
        val f = File(path)
        if (!f.exists()) return ""
        val sig = "${f.length()}@${f.lastModified()}"
        val p = prefs(ctx)
        p.getString(KEY_APK_SIG, null)?.let { cached ->
            val sep = cached.indexOf('|')
            if (sep > 0 && cached.substring(0, sep) == sig) return cached.substring(sep + 1)
        }
        val sha = sha256(f)
        if (sha.isNotBlank()) p.edit().putString(KEY_APK_SIG, "$sig|$sha").apply()
        return sha
    }

    /** 手动检查的即时反馈（不依赖 Activity 也能弹，用应用级 Toast）。 */
    fun toast(ctx: Context, msg: String) {
        runCatching { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
