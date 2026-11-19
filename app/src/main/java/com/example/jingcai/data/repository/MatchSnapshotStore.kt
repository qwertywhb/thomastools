package com.example.jingcai.data.repository

import android.content.Context
import com.example.jingcai.data.model.Match
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 比赛列表的「最后一次成功」本地快照。
 *
 * ## 为什么需要
 * 冷启动必须等一次网络往返才有内容：地铁、电梯、地库、早高峰弱网都会看到「一片错误」。
 * 缓存让 App **先有画面再变新**——进页面立刻渲染上次的列表，抓取成功后原地替换。
 *
 * ## 为什么不放 SharedPreferences
 * 一天的比赛列表有几十上百条，JSON 通常 30~80 KB。SharedPreferences 是整份 XML 在内存里，
 * 写一次会把全部 key 重新序列化并在主线程 apply，属于典型的「小数据用 SP、大数据用文件」反例。
 * 这里直接写 `filesDir` 下的文件，写入放 IO 线程。
 *
 * ## 为什么按日期分文件
 * 用户会左右翻日期，翻回昨天时也应当秒开。文件名带日期 ⇒ 天然多天共存，且不需要清理策略
 * （超过 7 天的在读的时候顺手删掉，见 [prune]）。
 *
 * ⚠️ 快照只是「上次看到的样子」，**不能**拿它判「已开赛」：时间相关的判断一律用当前的
 *    `nowMs`（`Match.startedAt(nowMs)`），否则昨天的「未开赛」今天还会显示成待开赛。
 */
object MatchSnapshotStore {

    private const val DIR = "match_snapshot"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private val gson = Gson()
    private val type = object : TypeToken<List<Match>>() {}.type

    private fun file(ctx: Context, date: String): File =
        File(File(ctx.filesDir, DIR).apply { mkdirs() }, "$date.json")

    /** 保存一份快照；任何 IO / 序列化异常都吞掉——缓存失败绝不影响主流程。 */
    fun save(ctx: Context, date: String, matches: List<Match>) {
        if (matches.isEmpty()) return
        runCatching {
            file(ctx, date).writeText(gson.toJson(matches, type))
            prune(ctx)
        }
    }

    /**
     * 读取某天的快照。
     * @return `(列表, 保存时刻)`；没有/损坏时返回 null（损坏的文件顺手删掉）。
     */
    fun load(ctx: Context, date: String): Pair<List<Match>, Long>? = runCatching {
        val f = file(ctx, date)
        if (!f.exists()) return null
        val list: List<Match> = gson.fromJson(f.readText(), type) ?: return null
        if (list.isEmpty()) return null
        list to f.lastModified()
    }.getOrElse {
        runCatching { file(ctx, date).delete() }
        null
    }

    /** 只留最近 7 天的快照，避免无限堆积。 */
    private fun prune(ctx: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        File(ctx.filesDir, DIR).listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) runCatching { f.delete() }
        }
    }
}
