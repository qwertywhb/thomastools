package com.example.jingcai.data.model

/**
 * 模拟投注（参考 okooo 竞彩 / 竞彩篮球混合过关）的数据模型。
 *
 * 数据源统一走**体彩官方 calculator 接口**，一次请求拿全所有玩法：
 *  - 足球：`jc/football/getMatchCalculatorV1.qry?poolCode=hhad,had,ttg,hafu,crs`
 *  - 篮球：`jc/basketball/getMatchCalculatorV1.qry?poolCode=mnl,hilo,hdc`
 *
 * 🔴 为什么不用 okooo 页面：okooo 只内联渲出胜平负 / 让球（篮球是让分/大小分/胜负），
 *    比分 / 总进球 / 半全场要二次点开才加载，且是 GBK + DOM 抓取。官方接口是结构化 JSON，
 *    赔率、涨跌标志（`hf`/`df`/`af`）、让球线（`goalLine`）全都现成，一次请求全覆盖。
 */
enum class BetSport(val label: String, val icon: String) {
    FOOTBALL("足球", "⚽"),
    BASKETBALL("篮球", "🏀")
}

/**
 * 玩法类型。
 *
 * [inline] = true  ⇒ 直接铺在比赛卡片上（足球：胜平负 / 让球胜平负；篮球：让分 / 大小分 / 胜负）；
 * [inline] = false ⇒ 收进「全部玩法」弹窗（足球：比分 / 总进球 / 半全场）。
 *
 * ⚠️ 篮球的「胜分差」本次**不做**（用户明确：篮球只要大小分、胜负、让分胜负）。
 */
enum class BetPool(val code: String, val title: String, val inline: Boolean) {
    HAD("had", "胜平负", true),
    HHAD("hhad", "让球胜平负", true),
    MNL("mnl", "胜负", true),
    HDC("hdc", "让分", true),
    HILO("hilo", "大小分", true),
    CRS("crs", "比分", false),
    TTG("ttg", "总进球", false),
    HAFU("hafu", "半全场", false);

    companion object {
        fun of(code: String): BetPool? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 一个可点击的投注项。
 *
 * [key] 是**结算判定用的稳定键**，不是展示文案：
 *  - 胜平负 / 让球 / 胜负 / 让分：`h` / `d` / `a`
 *  - 大小分：`h`（大于）/ `l`（小于）
 *  - 比分：官方键原样 `s01s02`，另有 `s1sh`（胜其他）/ `s1sd`（平其他）/ `s1sa`（负其他）
 *  - 总进球：`s0`…`s7`（s7 = 7 球及以上）
 *  - 半全场：`hh`/`hd`/`ha`/`dh`/`dd`/`da`/`ah`/`ad`/`aa`（前=半场，后=全场）
 *
 * 🔴 结算必须认 `key` 而不是 `label`：label 会带让球线（「让主胜(+1.5)」）、带冒号（「1:0」），
 *    一旦文案调整（改字号/加空格/换「其他」措辞）就会静默判错。
 */
data class BetOption(
    val pool: BetPool,
    val key: String,
    val label: String,
    /** 赔率；<= 0 表示未开售（该格仍要占位，但不可点）。 */
    val odds: Double,
    /** 涨跌：1 涨（红↑）/ -1 跌（绿↓）/ 0 无。 */
    val trend: Int,
    /**
     * 该玩法本场的**盘口线**（结算必需）：
     *  - 让球胜平负 `-1` / 让分 `+1.5` / 大小分 `185.5`；
     *  - 胜平负 / 比分 / 总进球 / 半全场为空串。
     *
     * 🔴 必须随选项一起带进注单：结算时无法回查（比赛早已下架），
     *    而篮球的让分线会封盘/调整，事后按「当时的线」判才算公平。
     */
    val line: String = ""
) {
    val sellable: Boolean get() = odds > 0
}

/**
 * 一个玩法行：左侧一条竖排标签 + 右侧若干选项格。
 *
 * [lineLabel] 对应参考图最左那列：
 *  - 足球胜平负 = `0`，让球胜平负 = `-1` / `+1`（来自接口 `goalLine`）；
 *  - 篮球 = `让分` / `大小分` / `胜负`；
 *  - 弹窗内 = `比分` / `总进球` / `半全场`。
 */
data class BetMarket(
    val pool: BetPool,
    val lineLabel: String,
    val options: List<BetOption>,
    /** 网格列数：内联足球 3 / 篮球 2；弹窗 比分 5 / 总进球 4 / 半全场 3。 */
    val columns: Int,
    /**
     * 分组换行：每组元素个数，渲染时**每组都从新的一行开始**。
     *
     * 🔴 比分玩法专用（参考图的排法是「主胜区 / 平区 / 客胜区」各起一行）：
     *    `[13, 5, 13]` ⇒ 主胜区 12 个比分 + 胜其他 占 3 行（5/5/3）、平区 4 个 + 平其他 占 1 行、
     *    客胜区 12 个 + 负其他 占 3 行。
     *    若按连续 5 列平铺，胜其他会和 0:0 挤在同一行，与参考图不一致。
     *    空列表 = 不分段（总进球 / 半全场 / 内联玩法都是这种）。
     */
    val groupSizes: List<Int> = emptyList(),
    /**
     * 让分**盘口线**（主队视角，如 `-9.50`）。空串 = 该玩法没有线（胜负 / 比分 / 足球胜平负）。
     *
     * 🔴 篮球让分只有**一条**线、且永远是主队视角（用户 2026-11-17 定的口径，与体彩官方表格
     *    「主队让分值」那一列一致）。所以线放在**玩法名这一处**，选项文案只写「让主胜/让客胜」，
     *    绝不各自带线 —— 否则界面上会出现「让主胜 +7.5 / 让客胜 -7.5」并排，
     *    看起来像两边各让一半，其实那是同一个盘口的两侧。
     */
    val lineText: String = ""
)

/** 一场可投注比赛。字段与官方 calculator 接口一一对应，不做二次加工。 */
data class BetMatch(
    val sport: BetSport,
    val matchId: Long,
    /** 官方编号，如「周五001」。 */
    val num: String,
    val league: String,
    /** 联赛名块底色，6 位 hex（无 `#`），来自接口 `backColor`。 */
    val color: String,
    val home: String,
    val away: String,
    /** 形如 `[澳超9]`；接口没有则为空串。 */
    val homeRank: String,
    val awayRank: String,
    /** 销售日 yyyy-MM-dd（顶层分组字段，注单结算要用它回查赛果）。 */
    val businessDate: String,
    val date: String,
    val time: String,
    /** 是否已停售。 */
    val ended: Boolean,
    /** 卡片上直接铺开的玩法（按展示顺序）。 */
    val inline: List<BetMarket>,
    /** 「全部玩法」弹窗里的玩法（按展示顺序）。 */
    val expand: List<BetMarket>
) {
    /** 是否至少有一个可投注选项。 */
    val hasSellable: Boolean get() = inline.any { m -> m.options.any { it.sellable } }
}

/**
 * 投注单里一个选项的唯一键：`matchId|玩法|选项键`。
 *
 * 🔴 用「选项」而不是「场次」做键 —— 本版允许**同一场选多个选项**
 *    （同场不同玩法、甚至同玩法复选）。用 matchId 当键会让同场第二个选择把第一个顶掉，
 *    正是上一版「同场只能选一个」的限制。
 */
fun betPickKey(matchId: Long, poolCode: String, optionKey: String): String =
    "$matchId|$poolCode|$optionKey"

/**
 * 投注单里的一个选择。
 *
 * 冗余存了队名/编号/时间：记录是**历史凭证**，即使比赛从列表下架、接口改版，
 * 以后在别处展示时也必须能完整还原当初投的是什么，不能靠 matchId 回查。
 */
data class BetPick(
    val matchId: Long,
    val sport: String,
    val num: String,
    val league: String,
    val home: String,
    val away: String,
    val date: String,
    val time: String,
    val poolCode: String,
    val poolTitle: String,
    val lineLabel: String,
    val key: String,
    val label: String,
    /** 下注当时的盘口线（见 [BetOption.line]）；非让球类玩法为空。 */
    val odds: Double,
    val line: String = "",
    /**
     * titan007 比赛 id（结算用）。`0` = 下单时没匹配到。
     *
     * 🔴 为什么必须在下单时存：体彩 calculator 的 `matchId` 是体彩内部 id（如 `2042355`），
     *    和 titan007 的 id 体系完全不同；赛后 calculator 接口也查不到已停售场次。
     *    下单时用「竞彩编号后 3 位 + 双队名」到 `cp.titan007.com/buy/JingCai.aspx` 换出 titanId
     *    （实测 55/55 唯一命中，队名与体彩**完全同源**），之后就能按 id 精确取赛果，
     *    再也不用拿队名去模糊匹配（体彩用极简简称「马竞」「巴黎圣曼」，
     *    titan007 用全称「马德里竞技」「巴黎圣日耳曼」，模糊匹配实测只有 8/55）。
     */
    val titanId: Long = 0L,
    /**
     * 单腿结算结果。**可空**而不是给默认枚举：Gson 反序列化旧存档时走 Unsafe 分配、
     * 不走 Kotlin 默认值，非空字段会变成 `null` 并直接 NPE。读取一律走 [legSafe]。
     */
    val legResult: LegResult? = null
) {
    /** 安全的单腿结果（旧数据 / 反序列化 null ⇒ 视为待结算）。 */
    val legSafe: LegResult get() = legResult ?: LegResult.PENDING

    /**
     * 展示用的选项文案 —— **篮球让分的统一口径就在这里**。
     *
     * 用户 2026-11-17 的规则（与体彩官方表格一致）：
     *  1. 让分只有**一条**线，且永远是**主队视角**（`-9.50` = 主队让 9.5 分），
     *     这条线只在「玩法名」那一处出现一次（见 `BetMarket.lineText` / `poolTitleOf`）；
     *  2. 选项本身只写 `让主胜` / `让客胜`（大小分写 `大` / `小`），**绝不带线**。
     *     旧版给两侧各拼自己的线（主队 `-9.50`、客队 `+9.50`），屏幕上就成了
     *     「让主胜 -9.5 / 让客胜 +9.5」并排 —— 一正一负像是两边各让一半，
     *     其实是同一个盘口的两侧，用户直接读成「让分反了」。
     *
     * 让分文案**由 `key` 推导**、不读 [label]：存量注单的 label 是旧解析器拼的
     * 「让客胜(+9.50)」，读 label 会让新旧注单显示不一致，还得清洗历史数据。
     * 其它玩法保留 label 原样（`2:4` / `平`），只把历史遗留的括号线剥掉。
     */
    val pickText: String
        get() = when {
            poolCode == "hdc" -> if (key == "h") "让主胜" else "让客胜"
            // 大小分同理：选项只写「大 / 小」，线在玩法名那一处（`大小分 227.5`）。
            // 存量注单的 label 是「大于 227.50」—— 按 key 推导后自动变成「大」，零迁移。
            poolCode == "hilo" -> if (key == "h") "大" else "小"
            else -> {
                val i = label.indexOf('(')
                if (i > 0) label.substring(0, i).trim() else label
            }
        }
}

/**
 * 一张投注记录 = **投了什么 + 几倍 + 过关方式 + 结算状态**。
 *
 * ── 演进历史（别再走回头路）────────────────────────────────────────────
 *  第一版：有账户 / 余额 / 注单状态 / 结算；第二版按要求**全部去掉**（只记内容与倍数）；
 *  第三版做「串关 + 账本 + 自动结算 + 赔率提醒」时把余额加了回来；
 *  第四版按要求**再次去掉账户与余额**（本 App 不涉及资金账户），
 *  只保留「本金 / 派彩 / 结算状态」这些**单张注单自身**的数字，
 *  账本退化成纯统计（投入 / 回报 / 盈亏 / 命中率 / ROI / 按天按月）；
 *  第五版（2026-11-10）按要求把**整套统计也去掉**：累计盈亏 / 命中率 / 累计投入 /
 *  累计回报 / 按天按月 / 联赛统计全部下线，账单只回答「今天买了什么、花了多少、中没中」；
 *  配套地，**已开奖的注单只保留 24 小时**（见 [com.example.jingcai.data.settle.BetRetention]），
 *  之后连本金、派彩、赛果一起删除 —— 所以它也做不出跨天累计了。
 *
 * ── 🔴 反序列化兼容（本项目最容易被忽略的坑）──────────────────────────────
 *  旧存档里没有下面这些新字段。Gson 对**没有无参构造**的 Kotlin data class 走
 *  `Unsafe` 分配，**不会执行 Kotlin 默认值**：新字段会变成 `0` / `null`，
 *  非空类型字段拿到 `null` 之后第一次访问就 NPE。
 *  所以这里一律用「基本类型 + 可空 + 派生属性兜底」：
 *    - [parlayM]/[parlayMs] 用 [ms] 兜底（都没有 ⇒ 单关）；
 *    - [statusRaw] 用 [status] 兜底（null ⇒ 待开奖）；
 *    - [stakeLocked] 用 [stake] 兜底（0 ⇒ 按旧口径 2×选项数×倍数 推出来）。
 */
data class BetTicket(
    val id: String,
    val createdAt: Long,
    val times: Int,
    val picks: List<BetPick>,
    /**
     * 主关次：`1` = 单关，`M` = M 串 1。旧存档为 0，读 [m]。
     *
     * 🔴 多关次（自由过关 3串4 等）时这里只存**最大关次**，真正的关次集合看 [parlayMs]。
     *    保留这个字段的唯一意义是让老存档 / 老逻辑还能读出「这是几串」。
     */
    val parlayM: Int = 0,
    /**
     * 过关方式集合（自由过关）：`[2,3]` = 2串1 + 3串1 = 竞彩的「3串4」；`[1]` = 单关。
     * 可空 + 派生兜底，见类注释的 Gson 坑。
     */
    val parlayMs: List<Int>? = null,
    /** 结算状态（可空，见类注释）。读 [status]。 */
    val statusRaw: TicketStatus? = null,
    /** 建单时锁定的本金（元）。0 = 旧存档，按旧口径推导。 */
    val stakeLocked: Double = 0.0,
    /** 派彩金额（元）。获胜注的奖金之和；作废单为退回的本金。 */
    val payout: Double = 0.0,
    /** 结算完成时间（epoch ms）；0 = 未结算。 */
    val settledAt: Long = 0L,
    /**
     * 赛果快照：`matchId(字符串) -> "全场主:客|半场主:客"`，例如 `"2:1|1:0"`。
     * 存下来是因为赛果只在完场后的一小段时间内可查，账本与注单详情必须能离线还原。
     */
    val scores: Map<String, String>? = null,
    /** 结算备注（手动录分 / 自动取数失败原因等）。 */
    val note: String = "",
    /**
     * **已下线**（2026-11-10）：原「是否在列表里隐藏」的开关。
     *
     * 用户在那一轮把「删除 / 隐藏 / 清空 / 管理」整套手动整理动作都去掉了 ——
     * 已开奖的注单满 24 小时由 [com.example.jingcai.data.settle.BetRetention] 自动清理，
     * 账单里只剩「今天 + 还没开奖」的单，不需要再收起任何东西。
     *
     * 🔴 字段**必须留着**：老存档里有 `hiddenFlag=true` 的行，删字段会让 Gson 反序列化
     *    多出一个未知键（无害，但保持字段能让旧档原样读回）。现在它**不参与任何判定**。
     */
    val hiddenFlag: Boolean? = null
) {
    /** 实际生效的过关方式（旧存档 0 ⇒ 单关）。 */
    val m: Int get() = if (parlayM < 1) 1 else parlayM

    /**
     * 实际生效的关次集合（升序去重）。
     *
     * - 新单：直接用 [parlayMs]（可能同时打 2串1 + 3串1）；
     * - 旧存档：`parlayMs == null` ⇒ 退回单值 [m]。
     */
    val ms: List<Int>
        get() {
            val raw = parlayMs?.filter { it in 1..MAX_TIMES } ?: emptyList()
            return if (raw.isEmpty()) listOf(m) else raw.distinct().sorted()
        }

    /** 实际生效的状态（旧存档 null ⇒ 待开奖）。 */
    val status: TicketStatus get() = statusRaw ?: TicketStatus.PENDING

    /**
     * 本单本金（元）。
     *
     * 新单 = 下单时锁定的值；旧存档（`stakeLocked == 0`）= 按旧口径
     * `2 元 × 选项数 × 倍数` 推出来 —— 旧版就是"选项数即注数"，两者等价。
     */
    val stake: Double get() = if (stakeLocked > 0) stakeLocked else
        (STAKE_PER_BET * picks.size * times.coerceAtLeast(1)).toDouble()

    /** 已结算（不论中没中）。 */
    val settled: Boolean get() = settledAt > 0

    /** 净盈亏（元）：派彩 - 本金。未结算时为 0。 */
    val profit: Double get() = if (settled) payout - stake else 0.0

    /** 赛果表的安全视图。 */
    val scoreMap: Map<String, String> get() = scores ?: emptyMap()

    /** 是否被手动隐藏（旧存档 null ⇒ 正常显示）。 */
    val hidden: Boolean get() = hiddenFlag == true

    companion object {
        /** 单注金额（元）。 */
        const val STAKE_PER_BET = 2
        const val MAX_TIMES = 99
        /** 单票最多注数：超过就拒绝下单（复式组合会指数膨胀）。 */
        const val MAX_BETS = 2000
    }
}

/** 注单状态。 */
enum class TicketStatus(val label: String) {
    /** 待开奖（还有腿没出赛果）。 */
    PENDING("待开奖"),
    /** 中奖（至少有一注命中）。 */
    WON("已中奖"),
    /** 未中奖（全部腿已结算但没有任何一注命中）。 */
    LOST("未中奖"),
    /** 作废（全部腿都取消/延期，本金原样退回）。 */
    VOID("已作废");

    companion object {
        /** 按名字取（Gson 反序列化出 null 时兜底用，避免崩溃）。 */
        fun of(name: String?): TicketStatus =
            entries.firstOrNull { it.name == name } ?: PENDING
    }
}

/** 单腿结算结果。 */
enum class LegResult(val label: String) {
    PENDING("待开奖"),
    WIN("命中"),
    LOSE("未中"),
    /** 取消 / 延期 / 腰斩 —— 该腿按赔率 1.0 参与串关，单关则退本金。 */
    VOID("取消")
}

/**
 * 盘口线的**紧凑显示**：`-15.50` ⇒ `-15.5`，`-7.00` ⇒ `-7`，`+2` 原样。
 *
 * 只用于 UI 那一格 32dp 宽的标签：接口给两位小数（`goalLineValue = "-10.50"`），
 * 6 个字符在 32dp 里会折行，「让分 / -10.50」变成三行、把整行撑高。
 * 结算、注单存储一律用**原值**，绝不走这个函数。
 */
fun shortLine(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    val sign = when {
        t.startsWith("-") -> "-"
        t.startsWith("+") -> "+"
        else -> ""
    }
    val num = t.removePrefix("-").removePrefix("+").toDoubleOrNull() ?: return t
    val body = if (num % 1.0 == 0.0) {
        num.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", num).trimEnd('0').trimEnd('.')
    }
    return sign + body
}

/**
 * 大小分盘口的紧凑显示：接口给 `+175.50`，**去掉那个 `+`**（`175.5`）。
 *
 * 总分盘口既不是「让」也不是「受让」，`+` 在这里没有任何语义 —— 留着反而和让分的
 * `-7.5 / +2.5` 长得一样，读起来像「大分受让 175.5 分」。只影响展示，存储与结算用原值。
 */
fun shortTotalLine(raw: String): String = shortLine(raw).removePrefix("+")
