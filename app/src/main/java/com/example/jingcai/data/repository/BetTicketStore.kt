package com.example.jingcai.data.repository

import android.content.Context
import com.example.jingcai.data.model.BetTicket
import com.example.jingcai.data.settle.BetRetention
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * 投注记录存储（SharedPreferences + Gson）。
 *
 * ## 只有注单，没有账户
 * 本 App 不涉及资金账户，所以这里**只存注单**：本金 / 派彩是注单自身的字段，
 * 不需要「先扣余额、再派彩到账」这套链路（上一版的 `BetAccountStore` 已删除）。
 *
 * ## 🔴 只要「未开奖」的注单
 * 用户 2026-11-10 定稿：**已开奖的注单只保留 24 小时，之后自动删除**，
 * 本金 / 派彩 / 赛果快照整条一起走 —— 账单只回答「今天买了什么、花了多少、中没中」，
 * 不做任何跨天累计（累计盈亏 / 命中率 / 累计回报已在同一轮里整体下线）。
 * 实现在 [applyRetention]，挂在所有读取路径的出口上，见那里的注释。
 *
 * ## 🔴 单一存档
 * 账单页、订单详情、结算引擎、降赔监控全都 new 这个类读写同一份 prefs。
 * 千万别再往别处写第二份：一旦两处各存一份，下单后账单页就看不到新单，
 * 结算也改不到账单页读的那份（这是最容易出现的「两边数据不一样」事故）。
 * 旧档 `bet_slip_store` 的数据由 [migrateLegacyOnce] 一次性搬过来。
 *
 * ## 赔率提醒去重
 * [remindedKeys] 记录「已经提醒过降赔的腿」，避免同一条腿每次刷新都弹一次。
 */
class BetTicketStore(context: Context) {

    private val appCtx = context.applicationContext
    private val prefs = appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<BetTicket>>() {}.type

    /**
     * 一次性迁移：更早的版本把注单存在 `bet_slip_store`，上一版存在 `bet_account_store`。
     *
     * 不迁移的话，升级后老用户的历史记录会「凭空消失」（其实还在旧 prefs 里，只是没人读）。
     * 优先搬上一版的账户档（它里面是最新的注单），其次搬最早的注单档；
     * 只在**新档还没有注单**时搬，搬完不删旧档（万一迁移出错还能回退）。
     */
    private fun migrateLegacyOnce() {
        if (prefs.contains(KEY_TICKETS)) return
        val legacy = listOf(LEGACY_ACCOUNT, LEGACY_SLIP)
            .firstNotNullOfOrNull { name ->
                appCtx.getSharedPreferences(name, Context.MODE_PRIVATE).getString(KEY_TICKETS, null)
            } ?: return
        prefs.edit().putString(KEY_TICKETS, legacy).apply()
    }

    init {
        migrateLegacyOnce()
    }

    // ============================== 注单 ==============================

    fun loadTickets(): List<BetTicket> {
        val raw = prefs.getString(KEY_TICKETS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()

        // ① 整份解析成功 → 正常路径
        val whole = runCatching { gson.fromJson<List<BetTicket>>(raw, listType) }.getOrNull()
        if (whole != null) return applyRetention(whole.filterNotNull())

        // ② 整份失败 → 逐条拆开救，能救多少救多少，并**先把原始 JSON 备份下来**
        val salvaged = runCatching { salvage(raw) }.getOrNull().orEmpty()
        backupBroken(raw)
        if (salvaged.isNotEmpty()) {
            // 救回来的立刻回写主档：此后主档就是「干净」的，写路径不会再把好数据带坏
            saveTickets(salvaged)
        }
        // ③ 一条都没救回时**不动主档**：留着下一次（比如升级到修好字段映射的版本）还能再读
        return applyRetention(salvaged)
    }

    /** 把 JSON 数组拆成元素逐个反序列化，坏的元素跳过而不是整份报废。 */
    private fun salvage(raw: String): List<BetTicket> {
        val arr = JsonParser.parseString(raw).asJsonArray
        val out = ArrayList<BetTicket>(arr.size())
        arr.forEach { el ->
            runCatching { gson.fromJson(el, BetTicket::class.java) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /**
     * 备份「解析不了的原始 JSON」。
     *
     * 只留最近一次（同一 key 覆盖）：这里的目的不是历史上所有坏档，而是
     * 「万一这次读失败把主档冲了，还能捞回来」——留一份足够，多了反而占空间。
     */
    private fun backupBroken(raw: String) {
        prefs.edit()
            .putString(KEY_BROKEN, raw)
            .putLong(KEY_BROKEN_AT, System.currentTimeMillis())
            .apply()
    }

    /** 是否存在「上次读取失败、已备份但尚未恢复」的注单。 */
    fun hasBrokenBackup(): Boolean = prefs.contains(KEY_BROKEN)

    /**
     * 只读地取当前主档（不触发 [loadTickets] 的备份副作用）。
     * 供 [restoreBroken] 合并用 —— 那里再用 loadTickets 会把刚读出的好备份覆盖掉。
     */
    private fun currentSafe(): List<BetTicket> {
        val raw = prefs.getString(KEY_TICKETS, null) ?: return emptyList()
        runCatching { gson.fromJson<List<BetTicket>>(raw, listType) }.getOrNull()
            ?.let { return it.filterNotNull() }
        return runCatching { salvage(raw) }.getOrNull().orEmpty()
    }

    /**
     * 从备份里尽量恢复注单。
     * @return 实际恢复的条数（0 = 备份也没有可用的记录）。
     */
    fun restoreBroken(): Int {
        val raw = prefs.getString(KEY_BROKEN, null) ?: return 0
        val salvaged = runCatching { salvage(raw) }.getOrNull().orEmpty()
        if (salvaged.isEmpty()) return 0
        // 与现有记录按 id 合并，避免把「备份后新存的单」冲掉
        val merged = (salvaged + currentSafe()).distinctBy { it.id }
        saveTickets(merged)
        prefs.edit().remove(KEY_BROKEN).remove(KEY_BROKEN_AT).apply()
        return salvaged.size
    }

    private fun saveTickets(list: List<BetTicket>) {
        prefs.edit().putString(KEY_TICKETS, gson.toJson(list)).apply()
    }

    fun appendTicket(ticket: BetTicket) {
        saveTickets(listOf(ticket) + loadTickets())
    }

    fun removeTicket(id: String): List<BetTicket> {
        val list = loadTickets().filterNot { it.id == id }
        saveTickets(list)
        return list
    }

    /**
     * 就地替换一条注单（结算回写用）。
     *
     * 🔴 用 `map` 重建整个列表再整存，而不是「按 id 找下标再改」——
     *    Gson 反序列化出来的 `List` 不保证可变，`list[i] = x` 会抛
     *    `UnsupportedOperationException`。
     */
    fun updateTicket(id: String, transform: (BetTicket) -> BetTicket): List<BetTicket> {
        val list = loadTickets().map { if (it.id == id) transform(it) else it }
        saveTickets(list)
        return list
    }

    // ============================== 保留策略：已开奖只留 24 小时 ==============================

    /**
     * 删掉「已开奖且满 24 小时」的注单（本金 / 派彩 / 赛果快照整条一起走）。
     *
     * 🔴 挂在 [loadTickets] 的出口，而不是「App 启动时跑一次」：
     *    只要读注单就一定先过这道筛，账单页 / 订单详情 / 结算引擎 / 看直播拿到的是
     *    同一份已清理的数据，不会出现「列表里没有、详情页还能查到」。
     * 🔴 **只有真删了才写盘**：结算引擎每轮都会读一次，没东西可删时不能无脑 write。
     *
     * 判据本身在 [BetRetention.keep]（纯函数，便于 debug 自检）。
     */
    private fun applyRetention(list: List<BetTicket>): List<BetTicket> {
        if (list.isEmpty()) return list
        val kept = BetRetention.keep(list)
        if (kept.size == list.size) return list
        saveTickets(kept)
        pruneReminded(kept)
        return kept
    }

    /**
     * 注单被清掉后，它在 [remindedKeys] 里的「已提醒降赔」记录也一起清。
     *
     * 不做的话这个 Set 只增不减：注单删了，键还留着，日积月累会变成 prefs 里最大的一块。
     */
    private fun pruneReminded(kept: List<BetTicket>) {
        val reminded = remindedKeys()
        if (reminded.isEmpty()) return
        val alive = kept.mapTo(HashSet()) { it.id }
        val next = reminded.filterTo(HashSet()) { it.substringBefore('|') in alive }
        if (next.size != reminded.size) {
            prefs.edit().putStringSet(KEY_REMINDED, next).apply()
        }
    }

    // ============================== 赔率提醒去重 ==============================

    /** 已经提醒过降赔的腿键集合（`ticketId|pickKey`）。 */
    fun remindedKeys(): Set<String> =
        prefs.getStringSet(KEY_REMINDED, emptySet())?.toSet() ?: emptySet()

    fun markReminded(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val next = remindedKeys() + keys
        prefs.edit().putStringSet(KEY_REMINDED, next).apply()
    }

    // ============================== 一次性修复标记 ==============================

    /**
     * 篮球主客对齐修复是否已经做过（见 `BetSettleEngine.repairBasketSideOnce`）。
     *
     * 存标记而不是每次启动都重开：重开会让「已结算」的注单退回待开奖再算一遍，
     * 虽然结果幂等，但派彩通知会重复弹、写盘也不必要。
     */
    var basketSideRepaired: Boolean
        get() = prefs.getBoolean(KEY_BB_SIDE_FIXED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BB_SIDE_FIXED, value).apply()
        }

    companion object {
        /**
         * 存档名。
         *
         * 🔴 用**新名字**而不是复用 `bet_slip_store`：那个旧档里还躺着两版之前的注单，
         *    一旦复用，[migrateLegacyOnce] 看到「已经有 tickets 键」就会跳过迁移，
         *    用户最近（上一版账户制）下的单反而搬不过来。
         */
        private const val PREF = "bet_ticket_store"
        private const val KEY_TICKETS = "tickets"
        private const val KEY_REMINDED = "reminded"

        /** 解析失败时备份的原始 JSON（见 [loadTickets] 的三条策略）。 */
        private const val KEY_BROKEN = "tickets_broken_raw"
        private const val KEY_BROKEN_AT = "tickets_broken_at"

        /** 篮球主客对齐修复的一次性标记。 */
        private const val KEY_BB_SIDE_FIXED = "bb_side_fixed_v1"

        /** 上一版（账户制）的存档名 —— 注单最新，优先搬它。 */
        private const val LEGACY_ACCOUNT = "bet_account_store"

        /** 最早的注单档（第二版）。 */
        private const val LEGACY_SLIP = "bet_slip_store"
    }
}
