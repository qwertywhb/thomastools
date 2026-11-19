#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Round 7 验证门禁 —— 竞彩赛程表：去星标 / 队名显示全 / 固定编号 / 其余列水平滑动。

需求原文（用户）：
  「竞彩赛程列表中联赛后面五角星不要，把主队客队名称显示全，固定编号，其他可以水平滑动」

拆成 4 条验收口径：
  S1  队名两侧的 ☆ 星标去掉
  S2  主队/客队名称**完整显示**（不截断）—— 用列宽数值验算，不能只看有没有 Ellipsis
  S3  编号列**固定**，不参与水平滚动
  S4  联赛 / 对阵 / 时间 三列**可水平滑动**，且表头与数据行**同步**（共用一个 ScrollState）

另附回归保护 R：原有功能与防崩溃契约不被破坏。

运行： python -X utf8 qa/verify_round7.py
"""
import io
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "jingcai")
SO = os.path.join(SRC, "ui", "schedule", "ScheduleScreen.kt")

R = []


def ok(i, desc, cond, note=""):
    R.append((i, desc, bool(cond), note))


def read(p):
    return io.open(p, encoding="utf-8").read() if os.path.exists(p) else None


def strip_comments(src):
    """剥注释与字符串字面量（注释里的「不再加 ☆」会被否定断言误命中）。"""
    if src is None:
        return None
    src = re.sub(r"/\*[\s\S]*?\*/", "", src)
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    return src


so = read(SO)
so_code = strip_comments(so)

# ─────────────────── S1 星标去掉 ───────────────────
if so_code:
    ok("S1a", "代码中已无 ☆ / ★ 字形",
       "\u2606" not in so_code and "\u2605" not in so_code)
    ok("S1b", "无星标颜色常量残留（0xFFC9CDDA 灰星）",
       "C9CDDA" not in so_code)

# ─────────────────── S2 队名显示全（数值验算） ───────────────────
if so:
    m_vs = re.search(r"COL_VS_W\s*=\s*([\d.]+)\.dp", so)
    m_time = re.search(r"COL_TIME_W\s*=\s*([\d.]+)\.dp", so)
    ok("S2a", "存在队名列宽常量 COL_VS_W", m_vs is not None)
    ok("S2b", "存在时间列宽常量 COL_TIME_W", m_time is not None)
    ok("S2c", "队名字号 13.sp 未变", "fontSize = 13.sp" in so)
    # 队名不再用 Ellipsis（改为 Visible）
    ok("S2d", "队名 Text 不再使用 TextOverflow.Ellipsis",
       not re.search(r"m\.home[\s\S]{0,140}?Ellipsis", so)
       and not re.search(r"m\.away[\s\S]{0,140}?Ellipsis", so))
    ok("S2e", "队名使用 TextOverflow.Visible（显式要求完整显示）",
       so.count("TextOverflow.Visible") >= 2)

    # ---- 数值验算：拿真数据里最长的对阵串，对比列宽 ----
    sample = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_zq_list_sample.json")
    if os.path.exists(sample) and m_vs:
        try:
            d = json.load(io.open(sample, encoding="utf-8"))
            allm = [m for g in d["value"]["matchInfoList"] for m in g["subMatchList"]]

            def width_em(s):
                # 全角/汉字 = 1em；其余（数字、字母、空格）≈ 0.55em
                return sum(1.0 if ord(c) > 0x2E80 else 0.55 for c in s)

            # 对阵串 = 主队 + "  VS  " + 客队
            worst = max(allm, key=lambda m: width_em(
                m["homeTeamAbbName"] + "  VS  " + m["awayTeamAbbName"]))
            em = width_em(worst["homeTeamAbbName"] + "  VS  " + worst["awayTeamAbbName"])
            need_dp = em * 13.0                     # 13sp ⇒ 1em = 13dp
            col_dp = float(m_vs.group(1))
            ok("S2f", "最长对阵串在列宽内可完整显示",
               need_dp <= col_dp,
               "最长=%s(%s VS %s) 需 %.1fdp / 列宽 %.1fdp" % (
                   "%.2fem" % em, worst["homeTeamAbbName"], worst["awayTeamAbbName"],
                   need_dp, col_dp))
            # 时间列
            t_em = width_em("2026-10-17 18:00")
            t_need = t_em * 12.0 + 10.0             # 12sp + end padding 10dp
            ok("S2g", "时间串在列宽内可完整显示",
               m_time is not None and t_need <= float(m_time.group(1)),
               "需 %.1fdp / 列宽 %sdp" % (t_need, m_time.group(1) if m_time else "?"))
            # 统计有多少场的队名会超出（应为 0）
            over = sum(1 for m in allm
                       if width_em(m["homeTeamAbbName"] + "  VS  " + m["awayTeamAbbName"]) * 13.0
                       > col_dp)
            ok("S2h", "全量 74 场队名无一超宽（不截断）",
               over == 0, "超宽 %d / %d 场" % (over, len(allm)))
        except Exception as e:  # noqa
            ok("S2f", "样本可解析", False, repr(e))
    else:
        ok("S2f", "找到样本以验算队名宽度", False, sample)

# ─────────────────── S3 编号列固定 ───────────────────
if so:
    ok("S3a", "存在编号列宽常量 COL_NUM_W", "COL_NUM_W" in so)
    # 编号列必须是「不在 horizontalScroll 内」的独立 Box。
    # 注意 Box 修饰符里还带了 .padding(start=10.dp)，故不能用 [^)]* 卡死；
    # 用最小匹配到 { 为止，再看这段里有没有 horizontalScroll。
    fixed = re.search(
        r"Box\(Modifier\.width\(COL_NUM_W\)[^{]*\)\s*\{[\s\S]{0,220}?m\.num",
        so)
    ok("S3b", "编号渲染在不带 horizontalScroll 的固定 Box 内", fixed is not None)
    # 反向：编号所在 Box 附近不得出现 horizontalScroll
    if fixed:
        seg = so[fixed.start():fixed.start() + 260]
        ok("S3c", "编号 Box 内确实没有 horizontalScroll（固定不动）",
           "horizontalScroll" not in seg)
    ok("S3d", "表头编号列同样是固定 Box",
       re.search(r"Box\(Modifier\.width\(COL_NUM_W\)[\s\S]{0,130}?HeadCell\([\s\S]{0,60}?编号", so) is not None)

# ─────────────────── S4 其余列水平滑动 + 同步 ───────────────────
if so:
    ok("S4a", "存在共享的 ScrollState（rememberScrollState）",
       "rememberScrollState()" in so)
    ok("S4b", "滑动区总宽常量 SCROLLABLE_W = 联赛+对阵+时间",
       re.search(r"SCROLLABLE_W\s*=\s*COL_LEAGUE_W\s*\+\s*COL_VS_W\s*\+\s*COL_TIME_W", so) is not None)
    # 注意：统计时**只数使用处**，不能把 import 行的 `import ...horizontalScroll` 也算进来
    # （第一版就因此误报 3 处）。这里用带 `Modifier.` 前缀的完整签名计数。
    n_hscroll = len(re.findall(r"Modifier\.[\w.()]*horizontalScroll\(hScroll\)", so))
    ok("S4c", "表头与数据行都用同一个 hScroll 水平滚动（2 处）",
       n_hscroll == 2, "Modifier...horizontalScroll(hScroll) 出现 %d 次" % n_hscroll)
    ok("S4d", "hScroll 在 ScheduleScreen 内创建并下传给表头与行",
       re.search(r"val\s+hScroll\s*=\s*rememberScrollState\(\)", so) is not None
       and "TableHeader(hScroll)" in so
       and re.search(r"MatchRow\([^)]*hScroll\s*=\s*hScroll", so) is not None)
    ok("S4e", "表头/行滑动区都用 width(SCROLLABLE_W) 定宽（保证列对齐）",
       so.count("width(SCROLLABLE_W)") == 2,
       "width(SCROLLABLE_W) 出现 %d 次" % so.count("width(SCROLLABLE_W)"))
    # 结构同构：固定 Box + weight(1f).horizontalScroll(...).width(...)
    pat = r"Modifier\.weight\(1f\)\.horizontalScroll\(hScroll\)\.width\(SCROLLABLE_W\)"
    ok("S4f", "固定列 + 滑动区结构在表头与行中同构（列必然对齐）",
       len(re.findall(pat, so)) == 2,
       "同构签名出现 %d 次" % len(re.findall(pat, so)))

# ─────────────────── R 回归保护 ───────────────────
if so_code:
    ok("R1", "仍无任何按钮（Button/OutlinedButton）",
       "Button(" not in so_code and "OutlinedButton(" not in so_code)
    ok("R2", "仍无「同奖查询」「赔率变化」「赔率」标签",
       all(x not in so_code for x in ["同奖查询", "赔率变化", "赔率"]))
    ok("R3", "仍无顶部控件（日期切换/显示已截止/Switch）",
       all(x not in so_code for x in ["ArrowButton", "shiftDate", "显示已截止", "Switch("]))
    ok("R4", "仍保留下拉刷新（nestedScroll + startRefresh/endRefresh）",
       "nestedScroll(pullState.nestedScrollConnection)" in so
       and "startRefresh()" in so and "endRefresh()" in so)
    ok("R5", "仍无 DropdownMenu 嵌 LazyColumn（防崩溃契约）",
       not re.search(r"DropdownMenu\([\s\S]{0,900}?LazyColumn\(", so_code))
    ok("R6", "分段渲染未回退（item + itemsIndexed 摊平，全页 1 个 LazyColumn）",
       "item(key" in so and so.count("LazyColumn(") == 1,
       "LazyColumn 出现 %d 次" % so.count("LazyColumn("))
    ok("R7", "联赛彩色块仍用官方 backColor 解析",
       "parseHexColor(m.color" in so)
    ok("R8", "隔行底色只铺整行背景（滑动时不随内容断开）",
       re.search(r"\.height\(ROW_H\)\s*\n?\s*\.background\(bg\)", so) is not None)
    ok("R9", "4 列表头文字未变",
       all(('"%s"' % c) in so or c in so for c in ["编号", "联赛", "主队 VS 客队", "比赛时间"]))

# ─────────────────── H 编译门禁 ───────────────────
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
    ok("H1", ":app:compileDebugKotlin BUILD SUCCESSFUL",
       p.returncode == 0 and "BUILD SUCCESSFUL" in out, "returncode=%d" % p.returncode)
    ok("H2", "无编译 error", "e: " not in out)

# ─────────────────── 输出 ───────────────────
fails = [r for r in R if not r[2]]
w = max(len(r[0]) for r in R)
for i, desc, passed, note in R:
    line = "  [%s] %-*s  %s" % ("PASS" if passed else "FAIL", w, i, desc)
    if note:
        line += "   (%s)" % note
    print(line)

print("")
print("=" * 72)
print("Round 7 门禁（赛程表：固定编号+水平滑动）：%d/%d PASS" % (len(R) - len(fails), len(R)))
if fails:
    print("失败项：")
    for i, desc, _, note in fails:
        print("  - %s %s %s" % (i, desc, note))
print("=" * 72)
sys.exit(1 if fails else 0)
