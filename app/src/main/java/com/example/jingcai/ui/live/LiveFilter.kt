package com.example.jingcai.ui.live

/**
 * 比分直播的「只看这次投注的场次」过滤器。
 *
 * 用法：投注记录 → 点某条 → 「查看相关场次比分直播」进入的页面，
 * 会把该次投注涉及的场次作为 [onlyNums] / [onlyTeams] 传下来，
 * 比分直播页（足球 / 篮球）只渲染命中的行，其余逻辑（5 秒 tick、点行进详情）完全不变。
 *
 * 匹配策略（**两条 OR，宁可多匹配也不要漏**）：
 *  1. **竞彩编号**：篮球 `jcNo` 与投注 `num` 都是「周五301」这种格式，直接比；
 *     足球的 `jcNum`/`num` 有时为空或是数字 id，所以这一步只是加分项；
 *  2. **队名对**：主客都「相等或互为包含」就算命中（`珀斯` ⊂ `珀斯光荣`），
 *     并额外接受**主客对调**（不同数据源的主客顺序可能相反）。
 */
internal fun List<com.example.jingcai.data.model.LiveDayGroup>.filterTicketMatches(
    onlyNums: Set<String>?,
    onlyTeams: List<Pair<String, String>>?
): List<com.example.jingcai.data.model.LiveDayGroup> {
    if (onlyNums == null && onlyTeams == null) return this
    return mapNotNull { g ->
        val ms = g.matches.filter { m ->
            ticketMatchHit(
                num = m.jcNum.ifBlank { m.num },
                home = m.home, away = m.away,
                onlyNums = onlyNums, onlyTeams = onlyTeams
            )
        }
        if (ms.isEmpty()) null else g.copy(matches = ms)
    }
}

/** 篮球版：`sections → groups → matches` 三级过滤，空分组/空分区整段丢掉。 */
internal fun List<com.example.jingcai.data.model.BbSection>.filterTicketSections(
    onlyNums: Set<String>?,
    onlyTeams: List<Pair<String, String>>?
): List<com.example.jingcai.data.model.BbSection> {
    if (onlyNums == null && onlyTeams == null) return this
    return mapNotNull { s ->
        val gs = s.groups.mapNotNull { g ->
            val ms = g.matches.filter { m ->
                ticketMatchHit(
                    num = m.jcNo,
                    home = m.home, away = m.away,
                    onlyNums = onlyNums, onlyTeams = onlyTeams
                )
            }
            if (ms.isEmpty()) null else g.copy(matches = ms)
        }
        if (gs.isEmpty()) null else s.copy(groups = gs)
    }
}

/** 单场是否命中本次投注。 */
internal fun ticketMatchHit(
    num: String,
    home: String,
    away: String,
    onlyNums: Set<String>?,
    onlyTeams: List<Pair<String, String>>?
): Boolean {
    if (onlyNums == null && onlyTeams == null) return true
    if (num.isNotBlank() && onlyNums != null && num in onlyNums) return true
    if (onlyTeams != null) {
        return onlyTeams.any { (h, a) ->
            (teamEq(home, h) && teamEq(away, a)) || (teamEq(home, a) && teamEq(away, h))
        }
    }
    return false
}

/**
 * 队名是否算同一支球队。两层判定（任一成立即可）：
 *
 *  1. 相等 / 一方是另一方的子串 —— 容忍「珀斯」⊂「珀斯光荣」这种简称；
 *  2. **字符集合 Jaccard ≥ 0.6** —— 容忍同队不同音译：
 *     竞彩叫「沃夫斯堡」、titan007 叫「沃尔夫斯堡」（少一个「尔」），
 *     交集 {沃,夫,斯,堡} / 并集 {沃,尔,夫,斯,堡} = 0.8 ⇒ 命中。
 *     而「曼城」vs「曼联」= 1/3 ≈ 0.33 ⇒ 不会误判。
 */
private fun teamEq(a: String, b: String): Boolean {
    val x = a.trim()
    val y = b.trim()
    if (x.isEmpty() || y.isEmpty()) return false
    if (x == y) return true
    // 太短的名字不做包含匹配（「曼」这种会误命中一堆队）
    if (x.length >= 2 && y.length >= 2) {
        if (x.contains(y) || y.contains(x)) return true
        val sx = x.toCharArray().toSet()
        val sy = y.toCharArray().toSet()
        val inter = sx.intersect(sy).size.toDouble()
        val union = sx.union(sy).size.toDouble()
        if (union > 0 && inter / union >= 0.6) return true
    }
    return false
}
