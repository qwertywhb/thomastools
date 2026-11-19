package com.example.jingcai.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局 Application Context 持有者：供通知 / 震动等拿不到 Context 的地方使用。
 * 在 [com.example.jingcai.MainActivity.onCreate] 里初始化。
 */
object AppContextHolder {
    lateinit var ctx: Context
        private set
    fun init(context: Context) { ctx = context.applicationContext }
}

/**
 * 外观（深色模式）三态。
 *
 * 默认 [FOLLOW] 跟随系统 —— 这也是加这个枚举之前的唯一行为，所以老用户升级后观感不变。
 * [code] 是**落盘值**，改它会让已存偏好在升级后失效，只能追加不能重排。
 */
enum class ThemeMode(val code: Int, val label: String) {
    FOLLOW(0, "跟随系统"),
    LIGHT(1, "浅色"),
    DARK(2, "深色");

    companion object {
        /** 未知 code 一律回到 [FOLLOW]，不抛异常（坏掉的偏好不该让 App 起不来）。 */
        fun of(code: Int): ThemeMode = values().firstOrNull { it.code == code } ?: FOLLOW
    }
}

/**
 * 用户偏好（关注比赛 + 进球提醒设置），SharedPreferences 持久化。
 *
 *  - [followed]：关注的比赛 titanId 集合（足球比分直播每场可关注）。
 *  - [goalAlert]：进球提醒**总开关**——关闭后关注比赛进球不再有任何提示（页内弹窗 / 状态栏都不发）。
 *  - [vibrate]：震动开关——进球提示（页内弹窗与状态栏通知）是否伴随震动。
 *
 * 全部走 StateFlow，UI 直接 collect 即可响应式刷新。
 */
object UserPrefs {
    private const val NAME = "jingcai_user_prefs"
    private const val K_FOLLOWED = "followed"
    private const val K_FOLLOW_TEAMS = "followed_teams"
    private const val K_GOAL = "goal_alert"
    private const val K_VIBRATE = "vibrate"
    private const val K_SOUND = "sound"
    private const val K_KEEPALIVE = "keepalive_wanted"
    private const val K_THEME = "theme_mode"
    private const val K_KICKOFF = "kickoff_alert"
    private const val K_KICKOFF_LEAD = "kickoff_lead_min"
    private const val K_KICKOFF_DONE = "kickoff_reminded"
    private const val K_HINT_SEEN = "live_hint_dismissed"

    /** 开赛提醒默认提前量：15 分钟。够走到厕所，也不至于早到忘了。 */
    const val DEFAULT_KICKOFF_LEAD = 15

    /**
     * 提前量的可选范围（分钟）。
     *
     * 上限 60：再早就成「今天有球」的日程播报，不是开赛提醒了；
     * 下限 1：必须留出至少一分钟，否则提醒和开赛同时到达，等于没提醒。
     */
    val KICKOFF_LEAD_RANGE = 1..60

    private lateinit var prefs: SharedPreferences
    private val _followed = MutableStateFlow<Set<Long>>(emptySet())
    private val _followedTeams = MutableStateFlow<Set<String>>(emptySet())
    private val _goalAlert = MutableStateFlow(true)
    private val _vibrate = MutableStateFlow(true)
    private val _sound = MutableStateFlow(true)
    private val _keepAliveWanted = MutableStateFlow(false)
    private val _themeMode = MutableStateFlow(ThemeMode.FOLLOW)
    private val _kickoffAlert = MutableStateFlow(true)
    private val _kickoffLeadMin = MutableStateFlow(DEFAULT_KICKOFF_LEAD)
    private val _hintDismissed = MutableStateFlow(false)

    val followed: StateFlow<Set<Long>> get() = _followed

    /**
     * 关注的**球队**（队名集合）。
     *
     * 与 [followed]（关注某一场比赛）是两件事：比赛级关注只影响进球提醒；
     * 球队级关注用来在比赛列表里「只看我关心的队」，并给比赛卡打标。
     * 存队名而不是 id：竞彩源站没有稳定的球队 id，硬造一套映射反而会随改名失配。
     */
    val followedTeams: StateFlow<Set<String>> get() = _followedTeams
    val goalAlert: StateFlow<Boolean> get() = _goalAlert
    val vibrate: StateFlow<Boolean> get() = _vibrate
    val sound: StateFlow<Boolean> get() = _sound

    /**
     * 用户**是否明确要过**「息屏增强」（即允许本 App 借用无障碍服务保活）。
     *
     * ## 语义（2026-11-04 改为「可选增强」）
     * 这是**用户主动选择**的增强项，不是功能前置条件：不开也能正常收到进球提醒
     * （App 在前台 / 亮屏用其他应用时都能震），只是息屏静置时收不到。
     *
     * 🔴 默认 **false**：新装或没表过态的机器，冷启动**绝不**去写无障碍设置。
     * 冷启动自愈（[com.example.jingcai.ui.live.GoalKeepAliveService.ensureEnabledQuietly]）
     * 必须同时看 `goalAlert && keepAliveWanted`，两个都为 true 才会静默补开 ——
     * 用户点过「关闭」也不会被偷偷开回来。
     */
    val keepAliveWanted: StateFlow<Boolean> get() = _keepAliveWanted

    /** 外观（跟随系统 / 浅色 / 深色）。由 [com.example.jingcai.MainActivity] 读来喂给主题。 */
    val themeMode: StateFlow<ThemeMode> get() = _themeMode

    /**
     * 开赛提醒总开关（默认**开**）。
     *
     * 与 [goalAlert] 是**两条独立开关**：进球提醒管「已经在踢」，开赛提醒管「马上要开」。
     * 用户完全可能只想被提醒开赛、不想被进球刷屏（或反之），合在一起会让其中一类被迫跟着开。
     */
    val kickoffAlert: StateFlow<Boolean> get() = _kickoffAlert

    /** 提前几分钟提醒开赛（默认 [DEFAULT_KICKOFF_LEAD] 分钟）。 */
    val kickoffLeadMin: StateFlow<Int> get() = _kickoffLeadMin

    /**
     * 比分直播页顶部那条「点击某场比赛可查看直播详情」的提示是否已被用户关掉。
     *
     * 为什么需要：那句话是**一次性信息**，却常驻在页签下面，占掉首屏将近 26dp，
     * 用户每进一次都得多滚一下才看到第一场比赛。关掉后永久不再出现（清数据/重装才复原）。
     */
    val liveHintDismissed: StateFlow<Boolean> get() = _hintDismissed

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        _followed.value = prefs.getStringSet(K_FOLLOWED, emptySet())
            ?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        _followedTeams.value = prefs.getStringSet(K_FOLLOW_TEAMS, emptySet())?.toSet() ?: emptySet()
        _goalAlert.value = prefs.getBoolean(K_GOAL, true)
        _vibrate.value = prefs.getBoolean(K_VIBRATE, true)
        _sound.value = prefs.getBoolean(K_SOUND, true)
        _keepAliveWanted.value = prefs.getBoolean(K_KEEPALIVE, false)
        _themeMode.value = ThemeMode.of(prefs.getInt(K_THEME, ThemeMode.FOLLOW.code))
        _kickoffAlert.value = prefs.getBoolean(K_KICKOFF, true)
        // 越界的旧值一律回默认：提醒提前量写错了会让「提醒永不触发」且用户看不出原因
        _kickoffLeadMin.value = prefs.getInt(K_KICKOFF_LEAD, DEFAULT_KICKOFF_LEAD)
            .takeIf { it in KICKOFF_LEAD_RANGE } ?: DEFAULT_KICKOFF_LEAD
        _hintDismissed.value = prefs.getBoolean(K_HINT_SEEN, false)
    }

    /** 关掉比分直播页顶部那条常驻提示（永久，直到清数据）。 */
    fun dismissLiveHint() {
        _hintDismissed.value = true
        if (::prefs.isInitialized) prefs.edit().putBoolean(K_HINT_SEEN, true).apply()
    }

    fun isFollowed(id: Long): Boolean = _followed.value.contains(id)

    fun isTeamFollowed(name: String): Boolean = _followedTeams.value.contains(name)

    /** 关注 / 取消关注一支球队（队名精确匹配）。 */
    fun toggleTeamFollow(name: String) {
        if (name.isBlank() || !::prefs.isInitialized) return
        val set = _followedTeams.value.toMutableSet()
        if (!set.add(name)) set.remove(name)
        _followedTeams.value = set
        prefs.edit().putStringSet(K_FOLLOW_TEAMS, set).apply()
    }

    /** 关注 / 取消关注切换；持久化到 SharedPreferences。 */
    fun toggleFollow(id: Long) {
        val set = _followed.value.toMutableSet()
        if (!set.add(id)) set.remove(id)
        _followed.value = set
        prefs.edit().putStringSet(K_FOLLOWED, set.map { it.toString() }.toSet()).apply()
    }

    /**
     * 批量取消关注（**比赛已结束**时由页面 / 后台服务清理）。
     * 集合没有实际变化就不写盘，避免每次刷新都触发一次 IO + StateFlow 重组。
     */
    fun removeFollow(ids: Set<Long>) {
        if (ids.isEmpty() || !::prefs.isInitialized) return
        val cur = _followed.value
        val next = cur - ids
        if (next.size == cur.size) return
        _followed.value = next
        prefs.edit().putStringSet(K_FOLLOWED, next.map { it.toString() }.toSet()).apply()
    }

    fun setGoalAlert(on: Boolean) {
        _goalAlert.value = on
        prefs.edit().putBoolean(K_GOAL, on).apply()
    }

    fun setVibrate(on: Boolean) {
        _vibrate.value = on
        prefs.edit().putBoolean(K_VIBRATE, on).apply()
    }

    /** 进球音乐开关（页内弹窗播放「消息提示音」；关掉则只弹卡片不响）。 */
    fun setSound(on: Boolean) {
        _sound.value = on
        prefs.edit().putBoolean(K_SOUND, on).apply()
    }

    /** 记录「用户是否想保持无障碍开启」，供冷启动自愈判断（点过关闭就不再自动开）。 */
    fun setKeepAliveWanted(on: Boolean) {
        _keepAliveWanted.value = on
        prefs.edit().putBoolean(K_KEEPALIVE, on).apply()
    }

    /**
     * 切换外观并落盘。StateFlow 先改，`MainActivity` 在同一个组合周期里就会换配色，
     * 不用重建 Activity（重建会丢导航栈，用户正在看的页会跳回首页）。
     */
    fun setThemeMode(mode: ThemeMode) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        if (::prefs.isInitialized) prefs.edit().putInt(K_THEME, mode.code).apply()
    }

    fun setKickoffAlert(on: Boolean) {
        if (_kickoffAlert.value == on) return
        _kickoffAlert.value = on
        if (::prefs.isInitialized) prefs.edit().putBoolean(K_KICKOFF, on).apply()
    }

    /** 设置开赛提前量（分钟）。越界值直接忽略，保持旧值，**不要**静默夹到边界。 */
    fun setKickoffLeadMin(min: Int) {
        if (min !in KICKOFF_LEAD_RANGE || min == _kickoffLeadMin.value) return
        _kickoffLeadMin.value = min
        if (::prefs.isInitialized) prefs.edit().putInt(K_KICKOFF_LEAD, min).apply()
    }

    /**
     * 已经提醒过的开赛（`titanId@开赛时间戳` 的集合）。
     *
     * 带时间戳是因为**比赛会改期**：改期后应该按新时间再提醒一次，
     * 只用 titanId 去重会让「改到明天」的场次永远不再提醒。
     */
    fun kickoffDone(): Set<String> =
        if (!::prefs.isInitialized) emptySet()
        else prefs.getStringSet(K_KICKOFF_DONE, emptySet())?.toSet() ?: emptySet()

    fun setKickoffDone(keys: Set<String>) {
        if (!::prefs.isInitialized) return
        prefs.edit().putStringSet(K_KICKOFF_DONE, keys).apply()
    }
}
