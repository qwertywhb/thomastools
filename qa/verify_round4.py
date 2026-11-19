#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
⚠️ 本套件**已被 verify_round5.py 取代（SUPERSEDED）**，请勿据此判断回归。

原因：Round 4 把「竞彩赛程」理解成了「复刻『比赛』tab 的比赛卡片列表」，本套件的
B2 / D3 / D4 等断言都是围绕那个**错误口径**写的（断言「复用 MatchCard」「取 today()」）。
Round 5 用户澄清：竞彩赛程应当是**体彩官方赛程表格**（4 列、无按钮、数据来自
getMatchListV1.qry、覆盖多天到周一）—— 与本套件的前提相反。

因此本套件现在会稳定报 3 项红（B2/D3/D4），那是**需求变更导致的口径过期，不是代码回归**。
新的验收口径全部由 `qa/verify_round5.py`（73 项，含活体接口断言）承载。

保留本文件仅作历史留档。若要跑有效回归，请跑 verify_round5.py。

--- 以下为 Round 4 原始说明（口径已作废）---

Round 4 验证门禁 —— 「竞彩赛程」纯列表 tab。

需求原文（用户）：
  「加一个tab ，竞彩赛程  列表只要这部分，只显示列表，不要上面东西」
经澄清后的 4 条验收口径：
  A. 新增「竞彩赛程」tab，列表复用「比赛」tab 的比赛卡片列表
  B. 去掉顶部内容 = 日期切换条(‹ 日期 ›) + 「显示已截止」开关（+ 隐藏提示文案）
  C. 原「比赛」tab 保留不动（底部变成 4 个 tab）
  D. 默认加载今天 + 支持下拉刷新

本脚本是**独立静态验证**：直接从源码文本/结构断言，不依赖人工结论。
运行： python -X utf8 qa/verify_round4.py
"""
import io
import os
import re
import sys

# 直接以「口径已过期」退出，避免误判为回归。
print(__doc__.strip().splitlines()[0])
print("")
print("=" * 68)
print("verify_round4.py 已作废（SUPERSEDED）—— 口径被 Round 5 推翻。")
print("请运行： python -X utf8 qa/verify_round5.py")
print("=" * 68)
sys.exit(0)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "jingcai")

R = []          # (id, 描述, 是否通过, 备注)


def ok(i, desc, cond, note=""):
    R.append((i, desc, bool(cond), note))


def read(p):
    full = os.path.join(SRC, p)
    if not os.path.exists(full):
        return None
    return io.open(full, encoding="utf-8").read()


main = read("MainActivity.kt")
sched = read(os.path.join("ui", "schedule", "ScheduleScreen.kt"))
match = read(os.path.join("ui", "match", "MatchScreen.kt"))
card = read("ui/MatchCard.kt")
vms = read(os.path.join("ui", "ViewModels.kt"))

# 否定式断言（「本页不该出现某控件」）一律用「剥注释+剥字符串」的版本。
# 第一次跑就踩到：ScheduleScreen.kt 的 KDoc 里为了说明「本页没有什么」，写着
# 「无 日期 切换条」「无 显示已截止 开关」「无 已隐藏 N 场 提示」——
# 拿全文做 in 断言时，这些说明文字被当成「代码里真的存在该控件」，一次产生 3 个假红。


def strip_comments(src):
    """剥掉 Kotlin 注释与字符串字面量，只留结构代码。"""
    if src is None:
        return None
    src = re.sub(r"/\*[\s\S]*?\*/", "", src)               # 块注释 / KDoc
    src = re.sub(r"//[^\n]*", "", src)                         # 行注释
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)             # 三引号字符串
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)      # 普通字符串
    return src


sched_code = strip_comments(sched)
main_code = strip_comments(main)
match_code = strip_comments(match)

# ---------------------------------------------------------------- A 新 tab 存在
ok("A1", "MainActivity.kt 存在", main is not None)
ok("A2", "ScheduleScreen.kt 存在", sched is not None)
ok("A3", "MatchCard.kt 存在（抽公共卡片）", card is not None)

if main:
    ok("A4", '注册了 composable("schedule") 路由', 'composable("schedule")' in main)
    ok("A5", "底部导航含「竞彩赛程」项", "竞彩赛程" in main)
    ok("A6", "点「竞彩赛程」跳 schedule 路由",
       re.search(r'竞彩赛程"\s*,\s*route\s*==\s*"schedule"', main) is not None
       or 'nav.go("schedule")' in main)
    # 4 个 tab：统计 NavItem( 出现次数
    n_items = main.count("NavItem(")
    # 减掉 NavItem 函数自身的声明（"private fun ... NavItem("）
    n_items -= len(re.findall(r"fun\s+[\w.]*NavItem\(", main))
    ok("A7", "底部导航共 4 项", n_items == 4, "实测=%d" % n_items)
    ok("A8", "bottomBar 可见性判断包含 schedule",
       re.search(r'route == "schedule"', main) is not None)
    ok("A9", "竞彩赛程排在「比赛」之后、「同奖查询」之前",
       main.index("竞彩赛程") < main.index("同奖查询")
       and main.index('"比赛"') < main.index("竞彩赛程"))

# ---------------------------------------------------------------- C 旧 tab 保留
if main and match:
    ok("C1", '原「比赛」tab 仍在（NavItem 含 "比赛"）', '"比赛"' in main)
    ok("C2", 'match 路由仍注册', 'composable("match")' in main)
    ok("C3", "startDestination 仍是 match（首页不变）",
       'startDestination = "match"' in main)
    ok("C4", "MatchScreen 仍保留自己的日期切换条（未被误删）",
       "ArrowButton" in match and "shiftDate" in match)
    ok("C5", "MatchScreen 仍保留「显示已截止」开关",
       "显示已截止" in match)

# ---------------------------------------------------------------- B 只显示列表
if sched:
    # 必须存在的列表要素
    ok("B1", "竞彩赛程使用 LazyColumn 渲染列表", "LazyColumn(" in sched)
    ok("B2", "竞彩赛程复用公共 MatchCard", "MatchCard(m," in sched)

    # 必须**不存在**的顶部控件
    ok("B3", "竞彩赛程无日期切换箭头（ArrowButton）", "ArrowButton" not in sched_code)
    ok("B4", "竞彩赛程无箭头字形", "\u2039" not in sched_code and "\u203a" not in sched_code)
    ok("B5", "竞彩赛程无日期切换逻辑（shiftDate）", "shiftDate" not in sched_code)
    ok("B6", "竞彩赛程无「显示已截止」开关", "显示已截止" not in sched_code)
    ok("B7", "竞彩赛程无 Switch 控件", "Switch(" not in sched_code)
    ok("B8", "竞彩赛程无「已隐藏 N 场」提示", "已隐藏" not in sched_code)
    ok("B9", "竞彩赛程无 TextField/搜索框等顶部输入", "TextField(" not in sched_code)

    # 顶部不应有常驻文字标题（只允许 三态提示 + 卡片内文字）
    # 检查 Column 下的直接子级文本：这里用「不出现裸 Text( 在列表之外」的近似；
    # 更可靠的是确认页面结构只有 Box { when { ... } ; 指示器 }
    ok("B10", "竞彩赛程页面根为 Box（无顶部 Row 控件条）",
       re.search(r"Box\(\s*\n?\s*Modifier\.fillMaxSize\(\)", sched) is not None)

# ---------------------------------------------------------------- D 今天 + 下拉刷新
if vms:
    ok("D1", "新增 ScheduleViewModel", "class ScheduleViewModel" in vms)
    ok("D2", "新增 ScheduleUiState", "data class ScheduleUiState" in vms)
    ok("D3", "默认日期取 today()",
       re.search(r"data class ScheduleUiState\([\s\S]{0,120}?date: String = today\(\)", vms) is not None)
    ok("D4", "取数固定传 today()（无日期参数）",
       re.search(r"repo\.getMatches\(today\(\)\)", vms) is not None)
    ok("D5", "提供 refresh() 供下拉刷新", "fun refresh()" in vms)
    # ScheduleViewModel 不得有「已截止过滤」——因为页面没有开关可恢复
    seg = vms[vms.find("class ScheduleViewModel"):]
    seg = seg[:seg.find("// ===== 同奖查询")] if "// ===== 同奖查询" in seg else seg
    ok("D6", "ScheduleViewModel 不过滤已截止场次（否则静默丢数据）",
       "finished" not in seg and "showEnded" not in seg)
    ok("D7", "刷新时保留旧列表（有 refreshing 态）", "refreshing" in seg)

if sched:
    ok("D8", "使用 pulltorefresh 原语", "pulltorefresh" in sched)
    ok("D9", "下拉刷新绑定了 nestedScroll",
       "nestedScroll(pullState.nestedScrollConnection)" in sched)
    ok("D10", "刷新状态与 UI 同步（startRefresh/endRefresh）",
       "startRefresh()" in sched and "endRefresh()" in sched)

# ---------------------------------------------------------------- E 防回归（历史坑）
if sched and card:
    ok("E1", "无 DropdownMenu 内嵌 LazyColumn（历史崩溃坑）",
       not re.search(r"DropdownMenu\([\s\S]{0,900}?LazyColumn\(", (sched_code or "") + (strip_comments(card) or "")))
# match 与 schedule 共用同一份卡片实现（避免样式漂移）
if card and match:
    ok("E2", "MatchCard 只有一份实现（MatchScreen 内无重复定义）",
       "private fun MatchCard(" not in match)
    ok("E3", "MatchScreen 通过 import 复用公共 MatchCard",
       card.count("fun MatchCard(") == 1)

# ---------------------------------------------------------------- 输出
fails = [r for r in R if not r[2]]
width = max(len(r[0]) for r in R)
for i, desc, passed, note in R:
    tag = "PASS" if passed else "FAIL"
    line = "  [%s] %-*s  %s" % (tag, width, i, desc)
    if note:
        line += "   (%s)" % note
    print(line)

print("")
print("=" * 68)
print("Round 4 门禁：%d/%d PASS" % (len(R) - len(fails), len(R)))
if fails:
    print("失败项：")
    for i, desc, _, note in fails:
        print("  - %s %s %s" % (i, desc, note))
print("=" * 68)
sys.exit(1 if fails else 0)
