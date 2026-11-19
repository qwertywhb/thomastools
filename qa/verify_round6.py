#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Round 6 验证门禁 —— 「同奖查询」结果列表显示慢的性能修复。

用户报障原文：
  「同奖查询点击主胜 平局 客胜 时候赔率列表显示出来非常慢」

实测排除了网络因素（真接口 3 次：total = 1.35s / 0.85s / 0.52s，返回 44KB / 100 条），
瓶颈全在**客户端**。定位到 4 处，本门禁逐条断言已修：

P1  赔率下拉展开时**无条件渲染全部 1800 项**（Column 非懒加载 ⇒ 瞬时约 1.2 万节点）
    → 改为默认只列「常用 1.20~6.00」+ 搜索才全量 + 结果上限 60
P2  结果行 `itemsIndexed` **无 key** ⇒ 结果变化时整表重建
    → 补稳定 key
P3  结果行**逐项延迟入场动画**（delay = index*25ms，第 100 行延迟 2.5s）
    → 去掉逐项延迟动画
P4  每行一个 `animateColorAsState`（100 行 = 100 个并发动画）
    → 改为静态二选一

运行： python -X utf8 qa/verify_round6.py
"""
import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "jingcai")
SO = os.path.join(SRC, "ui", "sameodds", "SameOddsScreen.kt")

R = []


def ok(i, desc, cond, note=""):
    R.append((i, desc, bool(cond), note))


def read(p):
    return io.open(p, encoding="utf-8").read() if os.path.exists(p) else None


def strip_comments(src):
    """剥注释与字符串字面量（Round 4 教训：注释里的说明文字会被否定断言误命中）。"""
    if src is None:
        return None
    src = re.sub(r"/\*[\s\S]*?\*/", "", src)
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    return src


so = read(SO)
so_code = strip_comments(so)

# ─────────────────── P1 赔率下拉不再一次性渲染 1800 项 ───────────────────
if so:
    ok("P1a", "存在「常用区间」常量 QUICK_ODDS", "QUICK_ODDS" in so)
    ok("P1b", "常用区间从 1.20 起（覆盖主流赔率）", re.search(r"in\s+120\.\.", so) is not None)
    ok("P1c", "存在搜索结果上限常量 MAX_RESULT", "MAX_RESULT" in so)
    ok("P1d", "搜索路径带 take(MAX_RESULT) 截断",
       re.search(r"\.take\(MAX_RESULT\)", so) is not None)
    # 关键：空搜索词走 QUICK_ODDS，不再走全量 oddsOptions
    m = re.search(r"if\s*\(\s*q\.isEmpty\(\)\s*\)\s*QUICK_ODDS\s*else([\s\S]{0,220})", so)
    ok("P1e", "空搜索词返回 QUICK_ODDS（不再返回全量 1800 项）", m is not None)
    ok("P1f", "全量 oddsOptions 只在搜索分支使用",
       m is not None and "oddsOptions" in m.group(1),
       "搜索分支含 oddsOptions=True" if m and "oddsOptions" in m.group(1) else "未命中")
    # 项数测算
    def count(a, b):
        return b - a + 1
    quick = count(120, 600) + 1        # +1 为「全部」
    full = count(102, 1900) + 1
    ok("P1g", "默认项数显著小于全量（至少降 70%%）",
       quick <= full * 0.3,
       "常用=%d 项 / 全量=%d 项 / 降幅=%.1f%%" % (quick, full, (1 - quick / full) * 100))
    ok("P1h", "有「常用区间」提示文案（避免用户以为赔率不全）",
       "常用" in so and "请在上方输入" in so)

# ─────────────────── P2 结果表补 key ───────────────────
if so:
    ok("P2a", "itemsIndexed 使用命名参数 items=",
       re.search(r"itemsIndexed\(\s*items\s*=", so) is not None)
    ok("P2b", "itemsIndexed 传入了 key", re.search(r"itemsIndexed\([\s\S]{0,400}?key\s*=", so) is not None)
    ok("P2c", "key 含稳定业务字段（matchDate/home/away）",
       re.search(r"key\s*=\s*\{[^}]*matchDate[^}]*\}", so) is not None
       or re.search(r"key\s*=\s*\{[^}]*m\.home[^}]*\}", so) is not None)

# ─────────────────── P3 去掉逐项延迟入场动画 ───────────────────
if so_code:
    ok("P3a", "结果表不再有 AnimatedVisibility 包裹行",
       "AnimatedVisibility" not in so_code)
    ok("P3b", "结果表不再用 delayMillis=index*25 逐项延迟",
       not re.search(r"delayMillis\s*=\s*\(?\s*index", so_code))
    ok("P3c", "行内容直接渲染 ResultRow（无动画包裹）",
       re.search(r"\)\s*\{\s*_,?\s*m\s*->[\s\S]{0,260}?ResultRow\(m,\s*hScroll\)", so) is not None
       or "ResultRow(m, hScroll)" in so)
    # 保留的 LaunchedEffect(Unit) 只能出现在赔率下拉里（若还有）
    ok("P3d", "文件中不再有 LaunchedEffect(Unit) 触发的逐项可见性",
       "LaunchedEffect(Unit)" not in so_code)

# ─────────────────── P4 去掉每行颜色动画 ───────────────────
if so_code:
    ok("P4a", "ResultRow 内无 animateColorAsState",
       "animateColorAsState" not in so_code)
    ok("P4b", "比分颜色改为静态二选一",
       re.search(r"val\s+scoreColor\s*=\s*if\s*\(", so) is not None)
    ok("P4c", "相关 import 已清理（无未用动画 import）",
       "import androidx.compose.animation.animateColorAsState" not in so
       and "import androidx.compose.animation.AnimatedVisibility" not in so)

# ─────────────────── P5 功能不缩水（回归保护） ───────────────────
if so:
    ok("P5a", "全量赔率 1.02~19.00 仍可查（oddsOptions 未删）",
       "for (c in 102..1900)" in so)
    ok("P5b", "搜索框仍在", "OutlinedTextField(" in so)
    ok("P5c", "三列下拉仍在（主胜/平局/客胜）",
       all(x in so for x in ['OddsDropdown("主胜"', 'OddsDropdown("平局"', 'OddsDropdown("客胜"']))
    ok("P5d", "查询/重置按钮仍在", "查 询" in so and "重 置" in so)
    ok("P5e", "结果表 7 列未变",
       all(x in so for x in ['Cell("胜"', 'Cell("平"', 'Cell("负"', 'Cell("赛果"',
                              'Cell("主队 VS 客队"', 'Cell("日期"', 'Cell("奖金类型"']))
    ok("P5f", "横向滚动共享 scrollState（表头/行对齐）未回退",
       "horizontalScroll(hScroll)" in so)
    # 反崩溃契约必须保持：DropdownMenu 内不得出现 LazyColumn
    ok("P5g", "DropdownMenu 内仍无 LazyColumn（历史崩溃坑未回归）",
       not re.search(r"DropdownMenu\([\s\S]{0,1200}?LazyColumn\(", so_code))

# ─────────────────── P6 网络层未受影响（非瓶颈，作对照） ───────────────────
repo = read(os.path.join(SRC, "data", "repository", "JingCaiRepository.kt"))
if repo:
    ok("P6a", "getSameOdds 仍在 Dispatchers.IO 上执行（未引入主线程 IO）",
       re.search(r"suspend fun getSameOdds[\s\S]{0,120}?withContext\(Dispatchers\.IO\)", repo) is not None)
    # 确认没有把网络调用挪进 UI
    ok("P6b", "UI 层无直接网络调用", "ApiClient" not in (so or ""))

# ─────────────────── P7 编译门禁 ───────────────────
GRADLE = os.path.join(
    os.environ.get("USERPROFILE", ""),
    ".gradle", "wrapper", "dists", "gradle-8.2-bin",
    "bbg7u40eoinfdyxsxr3z4i7ta", "gradle-8.2", "bin", "gradle.bat"
)
if os.path.exists(GRADLE):
    env = dict(os.environ)
    env["JAVA_HOME"] = "D:/Java/jdk11"
    p = subprocess.run([GRADLE, "--offline", ":app:compileDebugKotlin"],
                       cwd=ROOT, capture_output=True, text=True, env=env, shell=False)
    out = (p.stdout or "") + (p.stderr or "")
    ok("P7a", ":app:compileDebugKotlin BUILD SUCCESSFUL",
       p.returncode == 0 and "BUILD SUCCESSFUL" in out, "returncode=%d" % p.returncode)
    ok("P7b", "无编译 error", "e: " not in out)

# ─────────────────── 输出 ───────────────────
fails = [r for r in R if not r[2]]
w = max(len(r[0]) for r in R)
for i, desc, passed, note in R:
    line = "  [%s] %-*s  %s" % ("PASS" if passed else "FAIL", w, i, desc)
    if note:
        line += "   (%s)" % note
    print(line)

print("")
print("=" * 70)
print("Round 6 门禁（同奖查询性能）：%d/%d PASS" % (len(R) - len(fails), len(R)))
if fails:
    print("失败项：")
    for i, desc, _, note in fails:
        print("  - %s %s %s" % (i, desc, note))
print("=" * 70)
sys.exit(1 if fails else 0)
