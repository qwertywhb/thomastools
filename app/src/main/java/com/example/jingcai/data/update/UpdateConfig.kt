package com.example.jingcai.data.update

/**
 * 自动更新的「出厂设置」——**只需要改下面两行**。
 *
 * ## 用法
 * 1. 在 GitHub 建一个**公开**仓库（例如 `jingcai-apk`），不需要任何服务器、不需要 Actions；
 * 2. 每次发版：`Releases → Draft a new release`，Tag 写 `v2`（数字 = 新的 versionCode），
 *    把 `app-release.apk` 和 `update.json` 一起拖进 assets，Publish；
 * 3. 把 [OWNER] / [REPO] 填成你的，重新打包一次即可。
 *
 * ## 🔴 发版三件事（少做一件就会「传了新包却不提示」）
 * 1. `app/build.gradle` 的 **`versionCode` +1** —— 版本号是唯一稳定的比较依据；
 * 2. **`update.json` 里的 `versionCode` 跟着改** —— 把上一版的 json 原样拖进来的老坑真实发生过
 *    （v9 那个 Release 挂的是 v8 的 APK 和 v8 的 json，客户端判定「还是 8」）；
 *    现在客户端取 `max(tag 数字, json.versionCode)` 兜底，但两边不一致本身就是错的；
 * 3. 若确实不想改版本号（只重打包），**也让资产内容变** —— 客户端会比对 APK 的 SHA-256，
 *    同版本号但内容不同会提示「发现新的安装包」。
 *
 * ## 🔴 Tag 必须写「v + versionCode」
 * 客户端要拿整数比较版本（`versionName` 是字符串，`1.10` 会被判成比 `1.9` 旧）。
 * Tag 写 `v3` 就表示「versionCode=3」。若 Tag 不是纯数字，则**必须**在 `update.json`
 * 里显式写 `"versionCode": 3`，否则本模块判定「拿不到可比版本号」直接放弃。
 *
 * ## 留空 = 完全不检查
 * 两个常量留空时 [configured] 为 false，App **不会发任何请求**，界面上也不会出现更新相关的任何东西。
 * 这样既能先合代码、后填仓库，也不会在没发布的时候骚扰用户。
 */
object UpdateConfig {

    /** 🔴 改成你的 GitHub 用户名（或组织名）。 */
    const val OWNER = "qwertywhb"

    /** 🔴 改成放 APK 的仓库名。 */
    const val REPO = "thomastools"

    /**
     * 版本 JSON 的**资产名**：跟 APK 一起挂在同一个 Release 里。
     * 内容示例见本文件的 [SAMPLE]。
     */
    const val ASSET_JSON = "update.json"

    /**
     * 🔴 这里**故意没有「检查间隔」常量**。
     *
     * 用户明确的节奏是：**每次启动 App 查一次，运行中不查；点「稍后」下次启动还要提示**。
     * 所以节流不落在时间窗上，而是「进程内只查一次」的内存标记（见 `UpdateManager.check`）。
     * 曾经的 `AUTO_INTERVAL_MS = 6 小时` 就是「v6 都发布了、开 App 却不提示」的元凶——
     * 它静默吞掉新版本，用户看不到任何反馈，只会以为更新坏了。
     *
     * 请求量：一次冷启动 1 次 `GET /releases/latest`，GitHub 未登录限流是 60 次/小时/IP，
     * 正常使用一天最多几十次，够用；且不进后台轮询。
     */
    val configured: Boolean
        get() = OWNER.isNotBlank() && REPO.isNotBlank()

    /**
     * `update.json` 的完整示例（字段都可省略，省略的那项回落到 Release 自身的 tag/body/apk 资产）：
     *
     * ```json
     * {
     *   "versionCode": 3,
     *   "versionName": "1.2",
     *   "apk": "app-release.apk",
     *   "changelog": "1. 比分直播支持篮球赔率\n2. 修复账本崩溃",
     *   "sha256": "9f2c...（可选，填了就必须对得上，否则丢弃安装包）",
     *   "force": false
     * }
     * ```
     */
    const val SAMPLE = ""
}
