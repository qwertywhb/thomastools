package com.example.jingcai.data.crash

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录。
 *
 * ## 为什么需要
 * 本 App 的所有数据都来自**第三方网页/接口的 DOM 约定**（titan007 / okooo / sporttery）。
 * 源站改一个 class 名、少一个 `td`，解析器就可能抛异常；而 Compose 的异常发生在主线程，
 * 直接就是「闪退，什么都没有」。没有日志的话，用户唯一能提供的信息是「刚才退了」——
 * 完全无法定位。
 *
 * ## 装法是「旁路」而不是「拦截」
 * 记完日志必须把异常**原样交回上一个 handler**（系统的），不能吞：
 * 吞掉会留下一个状态已脏的进程继续跑，比闪退更难查，系统也没法弹「应用已停止」。
 *
 * ## 只留最近一次
 * 用户要的是一个能看的最近现场，不是历史归档。每次覆盖写 [FILE]，UI 展示后由用户手动清除。
 */
object CrashLog {

    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"

    private fun file(ctx: Context): File =
        File(File(ctx.filesDir, DIR).apply { mkdirs() }, FILE)

    /** 在 [android.app.Application.onCreate] 里调用一次。 */
    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            runCatching { write(ctx, thread, err) }
            prev?.uncaughtException(thread, err)
        }
    }

    private fun write(ctx: Context, thread: Thread, err: Throwable) {
        val sw = StringWriter()
        err.printStackTrace(PrintWriter(sw))
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val header = buildString {
            appendLine("时间: $ts")
            appendLine("线程: ${thread.name}")
            appendLine("版本: ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()}")
            appendLine("机型: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("------")
        }
        file(ctx).writeText(header + sw.toString())
    }

    fun read(ctx: Context): String? = runCatching {
        file(ctx).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun hasLog(ctx: Context): Boolean = runCatching { file(ctx).exists() }.getOrDefault(false)

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}
