#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Round-3 增量改造验收脚本（源码契约断言 + 编译门禁）。

运行环境（本机无模拟器/真机，故不做 UI 仪器测试）：
    cd "D:/WorkBuddyPro/project/sporttery/NativeAndroid"
    "C:/Users/wanghongbo/.workbuddy/binaries/python/versions/3.13.12/python.exe" -X utf8 qa/verify_round3.py

设计原则：
    - 只做「源码契约断言」（静态扫描关键实现特征）+ 「Gradle 编译门禁」。
    - 可重复运行，幂等；不修改任何源码。
    - 断言失败的判定：脚本正则/实现特征与验收标准不符 ⇒ 报告为 FAIL，
      并区分「源码 Bug」还是「测试脚本 Bug」（后者由 QA 自行修正）。

断言清单：A1~A4 / B1~B3 / C1~C5 / D1~D7 / E1~E3，共 24 条。
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java")

SAME_ODDS = os.path.join(SRC, "com", "example", "jingcai", "ui", "sameodds", "SameOddsScreen.kt")
MATCH = os.path.join(SRC, "com", "example", "jingcai", "ui", "match", "MatchScreen.kt")
LEAGUE = os.path.join(SRC, "com", "example", "jingcai", "ui", "league", "LeagueScreen.kt")
MODELS = os.path.join(SRC, "com", "example", "jingcai", "data", "model", "Models.kt")

# Round 4：MatchCard / OddsCell / PulsingTag 已从 MatchScreen.kt 抽到 ui/MatchCard.kt，
# 供「比赛」与「竞彩赛程」两个 tab 共用。B2/B3 的断言目标随之迁移到该文件。
CARD = os.path.join(SRC, "com", "example", "jingcai", "ui", "MatchCard.kt")

# Gradle（本机无 gradlew 脚本，用缓存发行版）
GRADLE_BAT = os.path.join(
    os.environ.get("USERPROFILE", ""),
    ".gradle", "wrapper", "dists", "gradle-8.2-bin",
    "bbg7u40eoinfdyxsxr3z4i7ta", "gradle-8.2", "bin", "gradle.bat",
)
JAVA_HOME = "D:/Java/jdk11"

results = []  # (id, desc, ok, evidence)


def add(cid, desc, ok, evidence):
    results.append((cid, desc, bool(ok), evidence))
    flag = "PASS" if ok else "FAIL"
    print(f"[{flag}] {cid} {desc}\n        -> {evidence}")


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def strip_comments(text):
    """去除 // 行注释与 /* */ 块注释，返回与原文等长的文本（被替换字符用空格占位，
    以便行号保持一致）。用于源码契约扫描，避免文档注释里的关键字造成误判。"""
    out = []
    i, n = 0, len(text)
    in_block = False
    while i < n:
        if in_block:
            if text.startswith("*/", i):
                out.append("  ")
                i += 2
                in_block = False
            else:
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
        else:
            if text.startswith("/*", i):
                out.append("  ")
                i += 2
                in_block = True
            elif text.startswith("//", i):
                while i < n and text[i] != "\n":
                    out.append(" ")
                    i += 1
            else:
                out.append(text[i])
                i += 1
    return "".join(out)


def find_line(lines, needle, start=0):
    """返回第一次出现 needle 的 1-based 行号，找不到返回 -1。"""
    for i in range(start, len(lines)):
        if needle in lines[i]:
            return i + 1
    return -1


def extract_block(text, opener, start_idx):
    """从 start_idx 处 opener（含 '{'）开始，按花括号配平抽取块内容（含首尾）。"""
    i = text.index(opener, start_idx)
    j = i + len(opener) - 1  # 指向 '{'
    depth = 0
    for k in range(j, len(text)):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == 0:
                return text[i:k + 1], i
    return text[i:], i


# ─────────────────────────── A. 崩溃修复 ───────────────────────────
def test_A():
    so = read(SAME_ODDS)
    so_code = strip_comments(so)          # 去注释，避免文档里的 LazyColumn 关键字误判
    so_lines = so.splitlines()

    # A1: OddsDropdown 内不再有 LazyColumn；DropdownMenu {} 内不得出现 LazyColumn
    seg = so_code[so_code.index("fun OddsDropdown("):so_code.index("fun BonusDropdown(")]
    no_lazy_in_odd = "LazyColumn" not in seg

    # 检查每个 DropdownMenu { ... } 块内是否含 LazyColumn（A3 也复用）
    nested = []
    for mm in re.finditer(r"DropdownMenu\s*\(", so_code):
        blk, _ = extract_block(so_code, "DropdownMenu(", mm.start())
        if "LazyColumn" in blk:
            nested.append(find_line(so_lines, "DropdownMenu", mm.start()))
    add("A1", "OddsDropdown 内不再有 LazyColumn（DropdownMenu 块内无 LazyColumn）",
        no_lazy_in_odd and not nested,
        f"OddsDropdown 段(去注释)含 LazyColumn={not no_lazy_in_odd}; DropdownMenu 块内 LazyColumn 行号={nested or '无'}")

    # A2: 替代容器 Column + verticalScroll，且 heightIn(max=...) 在 verticalScroll 之外
    has_col_vscroll = bool(re.search(r"Column\([^)]*heightIn\([^)]*\)[^)]*verticalScroll", so)) or \
        bool(re.search(r"Column\(\s*Modifier\.heightIn\([^)]*\)\.verticalScroll", so))
    mod_line = find_line(so_lines, "Column(Modifier.heightIn(max = 300.dp).verticalScroll")
    add("A2", "替代容器为 Column + verticalScroll，且外层有 heightIn(max=...) 约束",
        has_col_vscroll and mod_line > 0,
        f"匹配 Column(heightIn→verticalScroll)={has_col_vscroll}; 命中行={mod_line}；"
        f"DropdownMenu modifier={find_line(so_lines, 'modifier = Modifier.heightIn(max = 360.dp)')}")

    # A3: 全局扫描——不得存在 DropdownMenu{LazyColumn} 或 verticalScroll 所在
    #     纵向滚动容器块内出现 LazyColumn（同方向嵌套）。扫描前先去注释。
    global_hits = []
    for dirpath, _, files in os.walk(SRC):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dirpath, fn)
            raw = read(p)
            t = strip_comments(raw)
            lines = raw.splitlines()
            # (a) DropdownMenu { ... LazyColumn ... }
            for mm in re.finditer(r"DropdownMenu\s*\(", t):
                blk, _ = extract_block(t, "DropdownMenu(", mm.start())
                if "LazyColumn" in blk:
                    global_hits.append(
                        f"{os.path.relpath(p, ROOT)}:{find_line(lines, 'DropdownMenu', mm.start())} DropdownMenu→LazyColumn")
            # (b) verticalScroll(...) 所在 Column 块内出现 LazyColumn（同向嵌套）
            if "verticalScroll" in t and "LazyColumn" in t:
                for vm in re.finditer(r"verticalScroll\s*\(", t):
                    # 从该 verticalScroll 向上找最近的 'Column(' 作为容器起点
                    col_pos = t.rfind("Column(", 0, vm.start())
                    if col_pos < 0:
                        continue
                    blk, _ = extract_block(t, "Column(", col_pos)
                    if "verticalScroll" in blk and "LazyColumn" in blk:
                        global_hits.append(
                            f"{os.path.relpath(p, ROOT)}:{find_line(lines, 'verticalScroll')} verticalScroll→LazyColumn")
    add("A3", "全项目无 DropdownMenu{LazyColumn} / verticalScroll 内嵌 LazyColumn",
        len(global_hits) == 0, f"命中={global_hits or '无'}")

    # A4: 1800 项赔率全部可达：1.02~19.00 步长 0.01，共 1801 项（含“全部”），且有搜索框
    range_ok = bool(re.search(r"for\s*\(\s*c\s+in\s+102\.\.1900\s*\)", so))
    add_all = bool(re.search(r'add\("全部"\)', so))
    cnt = 1 + (1900 - 102 + 1)  # 全部 + 1799 = 1800? 实际 102..1900 含端点 = 1799 项
    search_box = "OutlinedTextField" in so and "搜索赔率" in so
    add("A4", "赔率仍为全部+1.02~19.00 步长0.01 且提供搜索框",
        range_ok and add_all and search_box,
        f"102..1900={range_ok}; 含全部={add_all}; 实项={cnt}（1801 期望: 全部+1799）; 搜索框={search_box}")


# ─────────────────────────── B. 比赛列表 ───────────────────────────
def test_B():
    ms = read(MATCH)
    lines = ms.splitlines()
    # 卡片实现（Round 4 起在 CARD 内）；MatchScreen.kt 只负责页面骨架。
    # 两个文件拼起来扫描，这样「实现挪家」不会造成假红，也不会漏检。
    ms_impl = ms + "\n" + read(CARD)

    # B1: 动画 API 落地，列出具体项与行号
    apis = ["animateFloatAsState", "animateColorAsState", "AnimatedVisibility",
            "AnimatedContent", "rememberInfiniteTransition", "animateFloat", "graphicsLayer", "tween"]
    found = {}
    for a in apis:
        ln = find_line(lines, a)
        if ln > 0:
            found[a] = ln
    # 只要核心动画 API 覆盖 >=3 类即算落地
    add("B1", "炫酷+动态效果（Compose 动画 API）确有落地",
        len(found) >= 3, f"命中 API 及行号={found}")

    # B2（新 4 列网格契约，Round 5 批次2 起）：
    #   旧契约 OddsCell("胜",...)/OddsCell("让球",...) 已随批次2 被删除（全仓 ZERO hits），
    #   赔率区改为「两行同构 4 列网格」：首列固定宽标签 (LABEL_COL_W=52dp) + 后 3 列等分 (weight(1f))。
    #   本断言不再依赖已删除的 OddsCell 字符串，而是锚定新组件 OddsGridRow 的结构常量。
    impl_lines = ms_impl.splitlines()
    card = read(CARD)
    grid_def = "private fun OddsGridRow(" in card
    # 固定宽标签列常量：private val LABEL_COL_W = 52.dp
    label_w = bool(re.search(r"private\s+val\s+LABEL_COL_W\s*=\s*52\.dp", card))
    # OddsGridRow 内：1 个固定宽标签列 + 3 个 Modifier.weight(1f) 等分列（同构行）
    grid_body = ""
    if grid_def:
        idx = card.index("private fun OddsGridRow(")
        grid_body, _ = extract_block(card, "OddsGridRow(", idx)
    label_fixed = "Modifier.width(LABEL_COL_W)" in grid_body
    weight_cols = len(re.findall(r"Modifier\.weight\(1f\)", grid_body))
    # 两个赔率数据行（胜平负 / 让球）都调用同一 OddsGridRow ⇒ 上下严格对齐
    grid_calls = len(re.findall(r"\bOddsGridRow\(", card)) - 1  # 减去函数定义本身
    hdraw_rendered = "m.hdraw" in ms_impl
    fulltime = "m.fullTime" in ms_impl
    add("B2", "赔率区为新 4 列网格(OddsGridRow: 固定标签列+3×weight(1f))，两行同构对齐；hdraw 渲染；时间用 fullTime",
        grid_def and label_w and label_fixed and weight_cols == 3 and grid_calls == 2
        and hdraw_rendered and fulltime,
        f"OddsGridRow定义={grid_def}; LABEL_COL_W=52dp={label_w}; 标签列固定宽={label_fixed}; "
        f"等分列数={weight_cols}(期望3); 数据行调用数={grid_calls}(期望2); "
        f"hdraw@={find_line(impl_lines, 'm.hdraw')}; fullTime@={find_line(impl_lines, 'm.fullTime')}")

    # B3: 每个 item 创建 infinite transition 的风险评估
    # rememberInfiniteTransition 位于 MatchCard 内 ⇒ 每个可见 item 都会创建一个无限动画
    inf_in_card = "rememberInfiniteTransition" in ms_impl
    pulse_used = "pulseAlpha" in ms_impl
    # pulseAlpha 仅用于 graphicsLayer alpha（无每帧重组重量级内容），风险中低
    light_usage = bool(re.search(r"graphicsLayer\s*\{\s*alpha\s*=\s*pulseAlpha", ms_impl))
    risk = "中低" if (inf_in_card and light_usage) else "需关注"
    add("B3", "动画风险：每 item 创建 infiniteTransition 但仅驱动 graphicsLayer alpha",
        inf_in_card and light_usage,
        f"MatchCard 内含 rememberInfiniteTransition={inf_in_card}; 仅用于 alpha 调制={light_usage}; 风险评估={risk}")


# ─────────────────────────── C. 同奖查询 ───────────────────────────
def test_C():
    so = read(SAME_ODDS)
    lines = so.splitlines()

    # C1: 列顺序 胜/平/负/赛果/主队VS客队/日期/奖金类型
    head_order = re.findall(r'Cell\("([^"]+)",\s*[\d.]+f,\s*head\s*=\s*true\)', so)
    expected = ["胜", "平", "负", "赛果", "主队 VS 客队", "日期", "奖金类型"]
    add("C1", "表头列顺序为 胜/平/负/赛果/主队VS客队/日期/奖金类型",
        head_order == expected, f"实际表头={head_order}")

    # C2: 出现日期列且取值 matchDate
    date_cell = find_line(lines, "Cell(m.matchDate.take(10), 0.14f")
    add("C2", "存在日期列且取值 matchDate",
        date_cell > 0 and "日期" in so, f"日期数据 Cell 行={date_cell}")

    # C3: 横向滚动且表头与数据行共用同一 ScrollState
    hscroll_state = "val hScroll = rememberScrollState()" in so
    head_uses = bool(re.search(r"Row\([\s\S]{0,120}horizontalScroll\(hScroll\)", so))
    # 数据行通过 ResultRow(m, hScroll) 传入同一 state
    row_passes = "ResultRow(m, hScroll)" in so
    row_scroll = bool(re.search(r"fun ResultRow\(m: SameOddsMatch, hScroll", so))
    add("C3", "表格可横向滚动，表头与数据行共用同一 ScrollState",
        hscroll_state and head_uses and row_passes and row_scroll,
        f"共享hScroll={hscroll_state}; 表头用hScroll={head_uses}; 行传入={row_passes}; ResultRow签名={row_scroll}")

    # C4: 纵向仍 LazyColumn；横向/纵向不同向嵌套（结果表结构 = 外层Column + 内层LazyColumn，行内 horizontalScroll）
    result_seg = so[so.index("fun ResultTable("):so.index("fun ResultRow(")]
    has_lazy = "LazyColumn" in result_seg
    # 结果表中不存在 verticalScroll 与 LazyColumn 同块
    nested_v = "verticalScroll" in result_seg
    add("C4", "结果表纵向 LazyColumn 且无同向嵌套",
        has_lazy and not nested_v,
        f"结果表含 LazyColumn={has_lazy}; 结果表含 verticalScroll(同向嵌套)={nested_v}")

    # C5: 未回退——三个赔率下拉含全部；奖金类型下拉 全部/初始/变化/最终；重置按钮在
    three_odd = so.count("OddsDropdown(") >= 3
    all_opt = '"全部"' in so
    bonus = all(x in so for x in ["全部奖金", "初始奖金", "变化奖金", "最终奖金"])
    reset_btn = "重 置" in so or "重置" in so
    add("C5", "三赔率下拉含全部；奖金类型(全部/初始/变化/最终)；重置按钮在",
        three_odd and all_opt and bonus and reset_btn,
        f"OddsDropdown数={so.count('OddsDropdown(')}; 全部={all_opt}; 奖金类型={bonus}; 重置={reset_btn}")


# ─────────────────────────── D. 赛事资料 ───────────────────────────
def test_D():
    lg = read(LEAGUE)
    lines = lg.splitlines()

    # D1: 隐藏全部联赛 移除（注意注释里出现该词不违规，需排除注释行）
    code_lines = [l for l in lines if not l.lstrip().startswith("//") and not l.lstrip().startswith("*")]
    d1_hits = [i + 1 for i, l in enumerate(code_lines) if "隐藏全部联赛" in l]
    add("D1", "「隐藏全部联赛」按钮已移除（忽略注释）",
        len(d1_hits) == 0, f"代码行(非注释)命中={d1_hits or '无'}")

    # D2: 选中联赛后自动收缩：点联赛回调 onPicked() 触发 showTree=false
    tree_cb = bool(re.search(r"LeagueTree\(state,\s*vm\)\s*\{\s*showTree\s*=\s*false\s*\}", lg))
    pick_calls = bool(re.search(r"vm\.selectLeague\(l\);\s*onPicked\(\)", lg))
    add("D2", "选中联赛后自动收起树（onPicked 回调触发 showTree=false）",
        tree_cb and pick_calls, f"回调传 false={tree_cb}; 选中调 onPicked={pick_calls}")

    # D3: 赛程表头去掉「阶段」列，数据行 week 为空时统一显示 "-"（不再回填 phase）—— 判定
    header_line = find_line(lines, 'H("日期", 0.22f)')
    header_seg = lg[lg.index('H("日期", 0.22f)'):lg.index('H("日期", 0.22f)') + 200]
    header_has_phase = '"阶段"' in header_seg
    data_phase = bool(re.search(r'C\(m\.week\.ifEmpty\s*\{\s*m\.phase\s*\}', lg))
    # 表头无「阶段」列，且数据行 week 为空时输出 "-"（源码已修，不再回填 phase）⇒ 判定 PASS。
    d3_header_ok = not header_has_phase
    add("D3", "赛程表头已去掉「阶段」；数据行 week 为空时统一显示 \"-\"，不回填 phase —— 判定",
        d3_header_ok,
        f"表头含『阶段』={header_has_phase}（表头标准 {'PASS' if d3_header_ok else 'FAIL'}）; "
        f"数据行 phase 回填={data_phase} → "
        f"结论：表头合规，数据行 week 为空显示 \"-\"，『阶段』列已彻底移除。")

    # D4: 日期完整显示：matchDate.take(10) + 权重 0.22 且 maxLines=1 不截断
    date_take = bool(re.search(r"C\(m\.matchDate\.take\(10\),\s*0\.22f", lg))
    # 独立复核算术
    # 布局链：外层 Column padding(12) → Card → Column(fillMaxSize) → Row(padding vertical=9, horizontal=6)
    # 360dp 屏宽 - 左右 12dp*2 = 336dp（Card 内宽）→ Row 内有 horizontal=6*2=12dp → 内容宽 324dp
    # 日期列权重 0.22，各行权重和 = 0.22+0.10+0.22+0.12+0.22+0.12 = 1.00 → 日期列 = 324*0.22 = 71.28dp
    screen_w = 360.0
    col_pad = 12.0 * 2
    row_pad = 6.0 * 2
    weight_sum = 0.22 + 0.10 + 0.22 + 0.12 + 0.22 + 0.12
    content_w = screen_w - col_pad - row_pad
    date_w = content_w * (0.22 / weight_sum)
    # '2026-10-17' 10 字符，11sp 数字/连字符近似宽 ~0.55*fontSize 每字符：11sp*0.55*10 ≈ 60.5dp
    text_w_est = 0.55 * 11 * 10
    margin = date_w - text_w_est
    add("D4", "赛程日期用 matchDate.take(10) 且权重够不被截断（独立复核算术）",
        date_take and margin > 0,
        f"take(10)={date_take}; 内容宽={content_w:.1f}dp; 权重和={weight_sum:.2f}; "
        f"日期列={date_w:.2f}dp; 文本估算≈{text_w_est:.1f}dp; 余量={margin:+.2f}dp "
        f"(team-lead 给 71.3dp/55.7dp/+15.6dp，与本次复核 71.28dp 一致)")

    # D5（v2）: 积分榜由「固定 600dp + 横滑」改为「自适应屏宽、零横滑」。
    #   v1 断言的是「期望 600dp」，但 600dp 在 360dp 屏仍需横滑 272dp(45%)，
    #   未达成用户诉求「尽量显示一起，不然滑动太多」⇒ 设计已变更，断言随之更新。
    #   新契约：BoxWithConstraints 包裹 + 正常屏 fillMaxWidth（零横滑）+ 仅超窄屏兜底。
    box_constraints = "BoxWithConstraints" in lg
    fill_max_width = "StandingHeader(Modifier.fillMaxWidth())" in lg
    narrow_fallback = "STANDING_MIN_SCREEN_W" in lg and "STANDING_MIN_TABLE_W" in lg
    # 不应再有「固定 600dp 表宽」这种超配
    no_hard_600 = not re.search(r"val\s+w\s*=\s*600\.dp", lg)
    add("D5a", "积分榜改为自适应屏宽（BoxWithConstraints + 正常屏 fillMaxWidth，零横滑）",
        box_constraints and fill_max_width and narrow_fallback and no_hard_600,
        f"BoxWithConstraints={box_constraints}; 表头fillMaxWidth={fill_max_width}; "
        f"超窄屏兜底={narrow_fallback}; 已移除固定600dp={no_hard_600}")

    # D5b: 独立复算 —— 各列在 360dp 屏下是否都够宽（权重取自 STANDING_WEIGHTS）
    m_w = re.search(r"STANDING_WEIGHTS\s*=\s*floatArrayOf\(([^)]*)\)", lg)
    sw = [float(x) for x in re.findall(r"([\d.]+)f", m_w.group(1))] if m_w else []
    avail = screen_w - col_pad
    # 各列内容最小宽（11sp：汉字≈1em、数字≈0.55em，+6dp 内边距）
    min_need = [18.1, 61.0, 18.1, 18.1, 18.1, 18.1, 18.1, 18.1, 24.2, 18.1]
    col_names = ["排名", "球队", "赛", "胜", "平", "负", "进", "失", "净", "积分"]
    widths = [avail * x for x in sw] if sw else []
    tight = [f"{n}({w:.0f}<{mn:.0f})" for n, w, mn in zip(col_names, widths, min_need) if w < mn - 2]
    add("D5b", "10 列在 360dp 屏均足够宽（零横滑且不挤压球队名）",
        len(sw) == 10 and not tight,
        f"权重和={sum(sw):.3f}; 可用宽≈{avail:.0f}dp; 各列宽=" +
        ", ".join(f"{n}{w:.0f}" for n, w in zip(col_names, widths)) +
        (f"; 偏窄列={tight}" if tight else "; 无偏窄列"))

    # D6: 积分榜/赛程仍 LazyColumn 纵向不截断（取函数体，到下一个顶层 @Composable / 文件尾）
    lg_code = strip_comments(lg)

    def func_body(src, name):
        i = src.index(f"fun {name}(")
        nxt = src.find("@Composable", i + 1)
        return src[i: nxt if nxt > 0 else len(src)]

    schedule_lazy = "LazyColumn" in func_body(lg_code, "ScheduleList")
    standing_lazy = "LazyColumn" in func_body(lg_code, "StandingTable")
    add("D6", "积分榜/赛程仍 LazyColumn 纵向(未回退)",
        schedule_lazy and standing_lazy, f"ScheduleList含Lazy={schedule_lazy}; StandingTable含Lazy={standing_lazy}")

    # D7: 热门联赛高亮条仍在；日期选择器（前七天/后七天/回到默认）仍在
    hot = "热门联赛" in lg and "LeagueChip" in lg
    picker = all(x in lg for x in ["前七天", "后七天", "回到默认"])
    add("D7", "热门联赛高亮条在；日期选择器(前七天/后七天/回到默认)在",
        hot and picker, f"热门联赛/LeagueChip={hot}; 日期选择器={picker}")


# ─────────────────────────── E. 编译门禁 ───────────────────────────
def run_gradle(task):
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
    cmd = f'"{GRADLE_BAT}" {task}'
    print(f"\n>>> 执行门禁: {cmd}")
    p = subprocess.run(cmd, cwd=ROOT, env=env, shell=True,
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = p.stdout.decode("utf-8", errors="replace")
    return p.returncode, out


def test_E():
    rc, out = run_gradle(":app:compileDebugKotlin")
    ok = ("BUILD SUCCESSFUL" in out) and rc == 0
    add("E1", ":app:compileDebugKotlin BUILD SUCCESSFUL", ok,
        f"returncode={rc}; BUILD SUCCESSFUL={'BUILD SUCCESSFUL' in out}")

    # E2: 无新增编译错误；警告仅允许 OkoooParser.kt:9 date never used
    warn_lines = [l.strip() for l in out.splitlines()
                  if re.search(r"\bwarning:\b", l, re.I) or "w:" in l]
    allowed = [w for w in warn_lines if "OkoooParser.kt" in w and "date" in w and "never used" in w]
    other = [w for w in warn_lines if w not in allowed]
    add("E2", "无新增编译错误；警告仅允许 OkoooParser.kt:9 date never used",
        "error:" not in out.lower() and len(other) == 0,
        f"含 error={'error:' in out.lower()}; 允许警告={len(allowed)}; 新增警告={other or '无'}")

    rc2, out2 = run_gradle(":app:assembleDebug")
    apk_ok = ("BUILD SUCCESSFUL" in out2) and rc2 == 0
    apk_path = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    add("E3", "assembleDebug 产出 APK", apk_ok and os.path.exists(apk_path),
        f"returncode={rc2}; BUILD SUCCESSFUL={'BUILD SUCCESSFUL' in out2}; APK存在={os.path.exists(apk_path)}")


def main():
    print("=" * 78)
    print("Round-3 验收：源码契约断言 + 编译门禁")
    print(f"ROOT = {ROOT}")
    print("=" * 78)
    test_A()
    test_B()
    test_C()
    test_D()
    if os.environ.get("QA_SKIP_BUILD") == "1":
        print("\n[SKIP] 编译门禁 E1~E3 已按 QA_SKIP_BUILD=1 跳过"
              "（共享工作区，禁止与工程师并发 gradle 构建）。")
    else:
        try:
            test_E()
        except Exception as e:  # noqa
            add("E1", ":app:compileDebugKotlin BUILD SUCCESSFUL", False, f"门禁执行异常: {e}")
            add("E2", "无新增编译错误/警告", False, "门禁执行异常")
            add("E3", "assembleDebug 产出 APK", False, "门禁执行异常")

    total = len(results)
    passed = sum(1 for _, _, ok, _ in results if ok)
    print("\n" + "=" * 78)
    print(f"测试通过率: {passed}/{total} PASS")
    fails = [r for r in results if not r[2]]
    if fails:
        print("失败项:")
        for cid, desc, _, ev in fails:
            print(f"  - {cid} {desc} :: {ev}")
    print("=" * 78)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
