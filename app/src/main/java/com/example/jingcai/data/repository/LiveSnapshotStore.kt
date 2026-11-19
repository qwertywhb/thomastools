package com.example.jingcai.data.repository

import android.content.Context
import com.example.jingcai.data.model.LiveMatch
import com.google.gson.Gson
import java.io.File

/**
 * 比分直播的「最后一次成功」本地快照。
 *
 * ## 为什么值得单独做一份
 * 比分直播默认就在「足球」页签、进来先等一次 60~200KB 的 bfdata 往返。地库/通勤路上
 * 打开时页面是空的，用户只能盯转圈。有了快照就能**秒出上次的比分**，网络回来后再原地刷新
 * （5 秒 tick 本来就会把比分纠正过来，所以「旧数据」在这里只存在一两秒，代价很小）。
 *
 * ## 存什么
 * 只存 [LiveUiState] 里真正重建页面所需的三样：`allMatches` + 热门集合 + 竞彩集合。
 * `groups` / `visibleMatches` 都是它们派生出来的，不重复存。
 *
 * ⚠️ 快照里的「进行中分钟数」是那一刻的，直接显示会停在旧分钟。渲染时以 5 秒 tick 的结果
 *    为准即可；这里不做额外校正（多写一套时间推算只会跟 tick 打架）。
 */
object LiveSnapshotStore {

    private const val DIR = "live_snapshot"
    private const val FILE = "football.json"

    /** 快照内容（Gson 直接序列化）。 */
    data class Payload(
        val matches: List<LiveMatch> = emptyList(),
        val hotIds: Set<String> = emptySet(),
        val jcIds: Set<String> = emptySet(),
        val savedAt: Long = 0L
    )

    private val gson = Gson()

    private fun file(ctx: Context): File =
        File(File(ctx.filesDir, DIR).apply { mkdirs() }, FILE)

    fun save(ctx: Context, p: Payload) {
        if (p.matches.isEmpty()) return
        runCatching { file(ctx).writeText(gson.toJson(p)) }
    }

    fun load(ctx: Context): Payload? = runCatching {
        val f = file(ctx)
        if (!f.exists()) return null
        gson.fromJson(f.readText(), Payload::class.java)?.takeIf { it.matches.isNotEmpty() }
    }.getOrElse {
        runCatching { file(ctx).delete() }
        null
    }
}
