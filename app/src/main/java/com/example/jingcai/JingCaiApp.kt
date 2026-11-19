package com.example.jingcai

import android.app.Application
import com.example.jingcai.data.crash.CrashLog
import com.example.jingcai.data.repository.AppContextHolder
import com.example.jingcai.data.repository.UserPrefs

/**
 * 应用入口：把「必须有 Context 才能做」的初始化提前到这里。
 *
 * 🔴 为什么不能全放在 `MainActivity.onCreate`：
 *   ViewModel 的 `init` 会读 [AppContextHolder.ctx]（离线快照）与 [UserPrefs]（关注集合）。
 *   ViewModel 由 Compose 创建，理论上晚于 Activity.onCreate，但「晚于」只是**当前**的时序，
 *   一旦以后加了 `ContentProvider` / `App Startup` / 更快的第一帧路径，就会踩到
 *   `lateinit property ctx has not been initialized`。Application.onCreate 永远在最前面，
 *   这里定死顺序才稳。
 */
class JingCaiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
        UserPrefs.init(this)
        // 崩溃兜底：必须尽早装，越早之后抛的异常都能留下现场
        CrashLog.install(this)
    }
}
