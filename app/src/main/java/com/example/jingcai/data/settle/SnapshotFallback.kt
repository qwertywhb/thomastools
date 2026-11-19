package com.example.jingcai.data.settle

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 「源站滚出该场次后，用注单内已存比分兜底」的判据。
 *
 * 抽成纯函数（无 IO、无 Android 依赖）是为了能跑自检 —— 这段逻辑一旦判错，
 * 后果是**把没打完的比赛按中途比分派彩**，比不结算严重得多，必须有回归网兜住。
 *
 * ## 为什么需要它（用户报障：「昨天结束的比赛，账单还没开奖」）
 * 篮球比分源 `matchs.qiumibao.com/live/all.htm` 只保留**当前期次**：实测 11-13 抓取时，
 * 11-13 早上的 NBA（周四306~309）还在且 `status=8`，而 11-12 的 NBL（周四301/302）
 * **整条消失**。足球 bfdata 同样只覆盖近日。
 *
 * 于是一条必然发生的路径：比赛打完 → 用户当晚没开 App → 次日打开时源站已移除该场
 * ⇒ 结算引擎按编号/titanId 全查不到 ⇒ 整单永远停在「待开奖」。
 * 而注单自己的 `scores` 里其实**存着当时抓到的比分**，只是没人再用它。
 *
 * ## 三条防线（缺一条就可能误判）
 * 1. 只在「源站查不到」时被调用 —— 源站有数据永远以源站为准，进行中的比赛碰不到它；
 * 2. 要求**开赛已过 [WAIT_MS]（5 小时）**：篮球最长约 3 小时，5 小时后还没完场状态的场次
 *    实际上已经滚出列表。延期/改期到点仍是 0:0，而 0:0 不会被写进快照，所以兜不到；
 * 3. 快照里没有比分就返回 null，继续 PENDING 等人工录分 —— 宁可不结算，不可误判。
 *
 * ⚠️ 残留风险：若源站在**比赛进行中**就移除场次，快照可能是中途比分。
 *    实际观测不到（进行中的场次一直留在 all.htm，实测当天还有 11 场 `status=2`），
 *    因此不为它加更复杂的判据；真遇到由「手动录分」兜底。
 */
internal object SnapshotFallback {

    /**
     * 允许兜底的最短等待：**开赛 + 5 小时**。
     *
     * 取值依据：篮球一场（含加时与中场）最长约 3 小时，足球含补时/加时/点球约 2.5 小时。
     * 5 小时留足余量，同时保证「次日打开 App」时立刻能结算，不必等一整天。
     */
    const val WAIT_MS: Long = 5 * 60 * 60 * 1000L

    /**
     * 「可能已经完赛」的判据：**开赛 + 2 小时**（结算入口的流量闸门用）。
     *
     * 用途只是决定**要不要发网络请求**，不参与判定：早一点查最多多拉一次，
     * 晚一点查就会让用户盯着「待开奖」干等。足球 1h50m 完、篮球 2h 出头完，
     * 2 小时后开始拉，第一次基本就能拿到完场状态。
     *
     * 🔴 **必须严格小于 [WAIT_MS]**：预筛比兜底窗口还晚的话，
     *    「源站已移除该场次」这条路径连请求都不会发，兜底永远轮不到。
     *    该约束由 `SnapshotFallbackSelfCheck` 断言守住。
     */
    const val SUSPECT_OVER_MS: Long = 2 * 60 * 60 * 1000L

    /**
     * `"2026-11-12"` + `"16:30"` → 开赛毫秒。
     *
     * 解析不了返回 null（调用方据此**放弃兜底**，保持待开奖）。
     * `isLenient = false` 是关键：默认宽松模式下 `"2026-02-30"` 会被悄悄算成 3 月 2 日，
     * 这里宁可判失败也不要一个猜测出来的时间。
     */
    fun kickoffMillis(date: String, time: String): Long? {
        if (date.isBlank() || time.isBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .apply { isLenient = false }
                .parse("$date $time")?.time
        }.getOrNull()
    }

    /**
     * 解析快照格式：`"全场主:客"`，含半全场玩法时是 `"全场主:客|半场主:客"`。
     *
     * 🔴 [needHalf] 为真而快照没有半场段时返回 null：半全场缺半场比分算不出来，
     *    拿 0:0 顶上等于瞎猜（写快照时正是为了不写假半场才省掉那一段）。
     */
    fun parse(raw: String, needHalf: Boolean): MatchScore? {
        val seg = raw.split('|')
        val full = seg.getOrNull(0)?.split(':') ?: return null
        val fh = full.getOrNull(0)?.toIntOrNull() ?: return null
        val fa = full.getOrNull(1)?.toIntOrNull() ?: return null
        val half = seg.getOrNull(1)?.split(':')
        if (needHalf && half == null) return null
        return MatchScore(
            fullHome = fh,
            fullAway = fa,
            halfHome = half?.getOrNull(0)?.toIntOrNull() ?: 0,
            halfAway = half?.getOrNull(1)?.toIntOrNull() ?: 0,
            finished = true
        )
    }

    /**
     * 综合判定：能兜就返回 `finished = true` 的比分，否则 null。
     *
     * @param raw     注单 `scores[matchId]` 的原始值（可能为 null）
     * @param kickoff 开赛毫秒（[kickoffMillis] 的结果，可能为 null）
     * @param now     当前毫秒
     * @param needHalf 该场是否含半全场玩法
     */
    fun usable(raw: String?, kickoff: Long?, now: Long, needHalf: Boolean): MatchScore? {
        if (raw.isNullOrBlank()) return null
        if (kickoff == null) return null
        // 负数（未开赛 / 时间解析出错）在这里一并被挡掉
        if (now - kickoff < WAIT_MS) return null
        return parse(raw, needHalf)
    }
}
