package com.example.jingcai.data.update

/**
 * 一次「发现新版本」的描述。全部字段都来自 GitHub Release（tag / body / assets / update.json）。
 */
data class UpdateInfo(
    /** 整数版本号，与 `PackageInfo.longVersionCode` 比较。 */
    val versionCode: Int,
    /** 展示用版本名，如 `1.2`。 */
    val versionName: String,
    /** APK 直链（GitHub 会 302 到 objects.githubusercontent.com，OkHttp 自动跟随）。 */
    val apkUrl: String,
    /** 更新说明（Release body 或 update.json 的 changelog）。 */
    val changelog: String = "",
    /** APK 字节数，用于估算进度；0 = 未知。 */
    val sizeBytes: Long = 0L,
    /** 可选 SHA-256（小写十六进制）。非空时下载完必须校验通过，否则丢弃。 */
    val sha256: String = "",
    /** 是否强制更新（只影响文案与「稍后」按钮是否可点）。 */
    val force: Boolean = false,
    /**
     * 远端版本号与本机**相同**，只是安装包内容变了（重新打过包）。
     *
     * 🔴 2026-11-06 用户报障「每次传新 APK 都不提示更新」的直接产物：
     *    只看 `versionCode` 时，只要发版时忘了 +1（或 Release 里塞的是上一版的
     *    `update.json`），新包就永远发现不了。现在同版本号但 SHA-256 不同也算有更新，
     *    排在 [UpdateManager.check] 里，这里只用来换文案（「新安装包」而不是「新版本」）。
     */
    val sameVersion: Boolean = false
) {
    val sizeText: String
        get() = when {
            sizeBytes <= 0L -> ""
            sizeBytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", sizeBytes / 1048576.0)
            else -> "${sizeBytes / 1024} KB"
        }
}
