#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Round 5 验证门禁 —— 「竞彩赛程」体彩官方赛程**表格**。

需求原文（用户）：
  「竞彩赛程不是比赛，只需要按这种表格显示出来不需要同奖查询和赔率变化，而且
    https://www.sporttery.cn/jc/zqszsc/ 这个上面赛程都到周一了，参照这个接口
    https://webapi.sporttery.cn/gateway/uniform/football/getMatchListV1.qry?clientCode=3001」

验收口径（表格 4 列，无按钮）：
  A. 数据源 = getMatchListV1.qry?clientCode=3001（**不是** okooo，也不是 Match 卡片）
  B. 表格列 = 编号 / 联赛 / 主队 VS 客队 / 比赛时间
  C. 联赛列是**官方 backColor 着色块**（不是自己映射的颜色）
  D. **无**「同奖查询」「赔率变化」按钮，**无**赔率格子
  E. 一次返回**多天**并按销售日分组（用户明确提到「赛程都到周一了」）
  F. 仍无顶部控件（日期切换条 / 显示已截止开关）；刷新走下拉

运行： python -X utf8 qa/verify_round5.py
"""
import io
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "jingcai")

R = []


def ok(i, desc, cond, note=""):
    R.append((i, desc, bool(cond), note))


def read(p):
    full = os.path.join(SRC, p)
    if not os.path.exists(full):
        return None
    return io.open(full, encoding="utf-8").read()


def strip_comments(src):
    """剥掉 Kotlin 注释与字符串字面量，只留结构代码。

    （Round 4 教训）KDoc 里为说明「本页没有什么」写下的「无『显示已截止』开关」等文字，
    会被「not in」断言当成真代码 —— 一切否定断言只跑剥注释后的版本。
    """
    if src is None:
        return None
    src = re.sub(r"/\*[\s\S]*?\*/", "", src)
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    return src


main = read("MainActivity.kt")
sched = read(os.path.join("ui", "schedule", "ScheduleScreen.kt"))
vms = read(os.path.join("ui", "ViewModels.kt"))
repo = read(os.path.join("data", "repository", "JingCaiRepository.kt"))
models = read(os.path.join("data", "model", "Models.kt"))
# 阶段2 起赔率网格组件 OddsGridRow 迁到共用文件 ui/MatchCard.kt。
card = read(os.path.join("ui", "MatchCard.kt"))

sched_code = strip_comments(sched)
repo_code = strip_comments(repo)

# ─────────────────────────── A. 数据源 ───────────────────────────
if repo:
    ok("A1", "Repository 含 getJingcaiSchedule()", "fun getJingcaiSchedule()" in repo)
    ok("A2", "调用 getMatchListV1.qry", "getMatchListV1.qry" in repo)
    ok("A3", "带 clientCode=3001", "getMatchListV1.qry?clientCode=3001" in repo)
    ok("A4", "解析 matchInfoList（多天分组）", 'optJSONArray("matchInfoList")' in repo)
    ok("A5", "解析 subMatchList", 'optJSONArray("subMatchList")' in repo)
    # 关�. 字段名逐一核对（这些字面量在 code 里是字符串，被剥掉了 ⇒ 用原文）
    for cid, field, why in [
        ("A6", "matchNumStr", "编号"),
        ("A7", "leagueAbbName", "联赛"),
        ("A8", "backColor", "联赛配色"),
        ("A9", "homeTeamAbbName", "主队"),
        ("A10", "awayTeamAbbName", "客队"),
        ("A11", "matchDate", "比赛日期"),
        ("A12", "matchTime", "比赛时间"),
    ]:
        ok(cid, "解析字段 %s（%s）" % (field, why), '"%s"' % field in repo)
    # 竞彩赛程不得再走 okooo
    seg = repo[repo.find("getJingcaiSchedule"):]
    seg = seg[:seg.find("// ===== 联赛分组")] if "// ===== 联赛分组" in seg else seg
    ok("A13", "竞彩赛程未使用 okooo（切到体彩官方接口）", "okooo" not in seg)

if models:
    ok("A14", "新增 JingcaiScheduleMatch 模型", "data class JingcaiScheduleMatch" in models)
    ok("A15", "新增 ScheduleDayGroup 模型（按天分组）", "data class ScheduleDayGroup" in models)
    ok("A16", "JingcaiScheduleMatch 有 dateTime 便捷属性",
       "val dateTime" in models or "dateTime" in models)

# ─────────────────────────── B. 表格 4 列 ───────────────────────────
if sched:
    ok("B1", "有表头组件 TableHeader", "private fun TableHeader(" in sched)
    # 表头 4 个中文列名（原文匹配，因为被剥掉了字符串）
    for cid, col in [
        ("B2", "编号"),
        ("B3", "联赛"),
        ("B4", "主队 VS 客队"),
        ("B5", "比赛时间"),
    ]:
        ok(cid, "表头含列「%s」" % col, ('"%s"' % col) in sched or col in sched)
    ok("B6", "表头有 4 个 HeadCell 调用",
       sched.count("HeadCell(") - 1 == 4, "HeadCell 调用数(减定义)=%d" % (sched.count("HeadCell(") - 1))
    ok("B7", "有行组件 MatchRow", "private fun MatchRow(" in sched)
    ok("B8", "行内渲染编号 m.num", "m.num" in sched)
    ok("B9", "行内渲染联赛 m.league", "m.league" in sched)
    ok("B10", "行内渲染主客队 m.home / m.away", "m.home" in sched and "m.away" in sched)
    ok("B11", "行内渲染时间 m.dateTime", "m.dateTime" in sched)
    ok("B12", "有固定表头（不随列表滚动）",
       sched.find("TableHeader()") < sched.find("LazyColumn("))
    ok("B13", "行间有分隔线 HorizontalDivider", "HorizontalDivider(" in sched)

# ─────────────────────────── C. 官方配色 ───────────────────────────
if sched:
    ok("C1", "联赛块使用 m.color 着色", "m.color" in sched)
    ok("C2", "有 hex 解析函数 parseHexColor", "private fun parseHexColor(" in sched)
    ok("C3", "hex 解析带容错回退（非法值不崩）",
       "return fallback" in sched)
    ok("C4", "非法长度（!=6）提前回退", "!= 6" in sched or "length != 6" in sched)
    ok("C5", "联赛块是圆角背景", "RoundedCornerShape(" in sched)
    ok("C6", "联赛块文字为白色", "Color.White" in sched)

# ─────────────────────────── D. 无按钮 / 无赔率 ───────────────────────────
# 注：旧赔率格子组件 OddsCell 已在批次2 从全仓删除（ZERO hits），
#     故 D3 的原 token「OddsCell」已失效（断言恒真、不再有区分度）。
#     本项按新 4 列网格契约改写：赛程页不得出现新网格组件 OddsGridRow / LABEL_COL_W 常量。
if sched_code:
    for cid, kw, why in [
        ("D1", "同奖查询", "按钮"),
        ("D2", "赔率变化", "按钮"),
        ("D4", "formatOdds", "赔率格式化"),
        ("D5", "赔率", "中文标签"),
        ("D6", "让球", "让球赔率"),
        ("D7", "Button(", "任何按钮控件"),
        ("D8", "OutlinedButton(", "任何描边按钮"),
    ]:
        ok(cid, "赛程页无「%s」（%s）" % (kw, why), kw not in sched_code)
    # D3（改写）：赛程页不得引用新赔率网格组件/其列宽常量（旧 OddsCell 亦不得回退）
    ok("D3", "赛程页无赔率网格（新契约 OddsGridRow / LABEL_COL_W 均不出现；旧 OddsCell 亦不回退）",
       ("OddsGridRow" not in sched_code and "LABEL_COL_W" not in sched_code
        and "OddsCell" not in sched_code),
       "OddsGridRow@=%s; LABEL_COL_W@=%s; OddsCell@=%s" % (
           "命中" if "OddsGridRow" in sched_code else "无",
           "命中" if "LABEL_COL_W" in sched_code else "无",
           "命中" if "OddsCell" in sched_code else "无"))
    ok("D9", "赛程页不引用 MatchCard（不再复刻比赛卡片）", "MatchCard" not in sched_code)
    ok("D10", "赛程页不引用 Match 模型", "data.model.Match" not in sched_code)

# ─────────────────────────── E. 多天分组 ───────────────────────────
if vms:
    ok("E1", "ScheduleUiState 持有 groups（多天分组）",
       re.search(r"data class ScheduleUiState\([\s\S]{0,200}?groups: List<ScheduleDayGroup>", vms) is not None)
    ok("E2", "ViewModel 调 getJingcaiSchedule()", "repo.getJingcaiSchedule()" in vms)
    ok("E3", "ViewModel 未回退到 getMatches(date)",
       "getMatches(" not in vms[vms.find("class ScheduleViewModel"):vms.find("// ===== 同奖查询")])
    ok("E4", "有 totalCount 汇总属性", "totalCount" in vms)
    ok("E5", "有 refreshing 态（下拉刷新）", "refreshing" in vms)

if sched:
    ok("E6", "按 groups 分段渲染（forEach）", "state.groups.forEach" in sched)
    ok("E7", "有日期分段标题组件 DayHeader", "private fun DayHeader(" in sched)
    ok("E8", "分段标题含 weekday 与 businessDate",
       "weekday" in sched and "businessDate" in sched)
    # 关键：分组渲染必须用 item{}+itemsIndexed{}，不能嵌套 LazyColumn
    ok("E9", "分段用 item{}+itemsIndexed{}（未嵌套 LazyColumn）",
       re.search(r"item\(key\s*=", sched) is not None
       and sched.count("LazyColumn(") == 1,
       "LazyColumn 出现次数=%d" % sched.count("LazyColumn("))

# ─────────────────────────── F. 仍无顶部控件 ───────────────────────────
if sched_code:
    ok("F1", "无日期切换箭头 ArrowButton", "ArrowButton" not in sched_code)
    ok("F2", "无 ‹ / › 箭头字形", "\u2039" not in sched_code and "\u203a" not in sched_code)
    ok("F3", "无 shiftDate 日期平移", "shiftDate" not in sched_code)
    ok("F4", "无「显示已截止」开关", "显示已截止" not in sched_code)
    ok("F5", "无 Switch 控件", "Switch(" not in sched_code)
    ok("F6", "下拉刷新绑定 nestedScroll",
       "nestedScroll(pullState.nestedScrollConnection)" in sched)
    ok("F7", "刷新态同步 startRefresh/endRefresh",
       "startRefresh()" in sched and "endRefresh()" in sched)
    ok("F8", "页面仍不使用 LazyColumn 嵌 DropdownMenu（防崩溃）",
       not re.search(r"DropdownMenu\([\s\S]{0,900}?LazyColumn\(", sched_code))

# ─────────────────────────── G. 真接口契约（活体） ───────────────────────────
# 优先用本地样本；没有就**现场拉一次**（本组是本轮最关键的断言：确认接口真的返回到周一、
# 配色真的是 6 位 hex、matchId 真的可作 key —— 这些都只能靠真数据证明，不能靠读代码）。
live_checked = False
live_note = "未取到数据"
sample = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_zq_list_sample.json")

if not os.path.exists(sample):
    try:
        subprocess.run(
            ["curl", "-s", "--noproxy", "*",
             "-H", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36",
             "-H", "Referer: https://www.sporttery.cn/jc/zqszsc/",
             "https://webapi.sporttery.cn/gateway/uniform/football/getMatchListV1.qry?clientCode=3001",
             "-o", sample],
            capture_output=True, timeout=60
        )
    except Exception:
        pass

if os.path.exists(sample):
    try:
        d = json.load(io.open(sample, encoding="utf-8"))
        v = d["value"]
        groups = v["matchInfoList"]
        allm = [m for g in groups for m in g["subMatchList"]]
        weekdays = [g.get("weekday") for g in groups]
        # 用户原话「赛程都到周一了」⇒ 必须含周一
        ok("G1", "接口返回多天且覆盖到周一",
           "周一" in weekdays, "weekdays=%s" % weekdays)
        ok("G2", "分组数 >= 4 天", len(groups) >= 4, "天=%d" % len(groups))
        ok("G3", "matchNumStr 形如「周六005」",
           all(re.match(r"^周[一二三四五六日]\d{3}$", m["matchNumStr"]) for m in allm),
           "样例=%s" % allm[0]["matchNumStr"])
        ok("G4", "backColor 均为 6 位 hex（parseHexColor 前提）",
           all(re.match(r"^[0-9A-Fa-f]{6}$", m["backColor"]) for m in allm))
        ok("G5", "组内已按编号升序（Kotlin sortedBy 是幂等的）",
           all([m["matchNumStr"] for m in g["subMatchList"]] ==
               sorted(m["matchNumStr"] for m in g["subMatchList"]) for g in groups))
        ok("G6", "matchId 全局唯一（可作 LazyColumn key）",
           len(set(m["matchId"] for m in allm)) == len(allm),
           "%d 场 / %d 唯一" % (len(allm), len(set(m["matchId"] for m in allm))))
        ok("G7", "同联赛配色稳定一致",
           len({(m["leagueAbbName"], m["backColor"]) for m in allm}) ==
           len({m["leagueAbbName"] for m in allm}))
        # 确认存在「跨日场次」⇒ 说明不能按 matchDate 重分组，必须沿用接口 businessDate 分组
        cross = sum(1 for g in groups for m in g["subMatchList"]
                    if m["matchDate"] != g["businessDate"])
        ok("G8", "存在跨日场次 ⇒ 沿用接口 businessDate 分组是对的",
           cross > 0, "跨日 %d / %d" % (cross, len(allm)))
        # 截图里的样本行必须能在这份真数据里找到（证明 4 列都能填满）
        hit = [m for m in allm if m["matchNumStr"] == "周六005"]
        ok("G9", "截图样板「周六005」在数据中且 4 列字段非空",
           len(hit) == 1 and all(
               str(hit[0].get(k, "")).strip() for k in
               ["matchNumStr", "leagueAbbName", "homeTeamAbbName",
                "awayTeamAbbName", "matchDate", "matchTime", "backColor"]),
           ("周六005 = %s %s | %s VS %s | %s %s" % (
               hit[0]["leagueAbbName"], hit[0]["backColor"],
               hit[0]["homeTeamAbbName"], hit[0]["awayTeamAbbName"],
               hit[0]["matchDate"], hit[0]["matchTime"])) if hit else "未找到")
        live_checked = True
        live_note = "%d 天 / %d 场" % (len(groups), len(allm))
    except Exception as e:  # noqa
        ok("G1", "活体样本可解析", False, repr(e))
else:
    ok("G1", "取到接口样本", False, "sample=%s" % sample)

# ─────────────────────────── H. 编译门禁 ───────────────────────────
GRADLE = os.path.join(
    os.environ.get("USERPROFILE", ""),
    ".gradle", "wrapper", "dists", "gradle-8.2-bin",
    "bbg7u40eoinfdyxsxr3z4i7ta", "gradle-8.2", "bin", "gradle.bat"
)
if os.environ.get("QA_SKIP_BUILD") == "1":
    print("[SKIP] 编译门禁 H1~H2 已按 QA_SKIP_BUILD=1 跳过"
          "（共享工作区，禁止与工程师并发 gradle 构建）。")
elif os.path.exists(GRADLE):
    env = dict(os.environ)
    env["JAVA_HOME"] = "D:/Java/jdk11"
    p = subprocess.run(
        [GRADLE, "--offline", ":app:compileDebugKotlin"],
        cwd=ROOT, capture_output=True, text=True, env=env, shell=False
    )
    out = (p.stdout or "") + (p.stderr or "")
    ok("H1", ":app:compileDebugKotlin BUILD SUCCESSFUL",
       p.returncode == 0 and "BUILD SUCCESSFUL" in out,
       "returncode=%d" % p.returncode)
    ok("H2", "无编译 error", "e: " not in out)
else:
    ok("H1", "找到 gradle 发行版", False, GRADLE)

# ─────────────────────────── 输出 ───────────────────────────
fails = [r for r in R if not r[2]]
w = max(len(r[0]) for r in R)
for i, desc, passed, note in R:
    line = "  [%s] %-*s  %s" % ("PASS" if passed else "FAIL", w, i, desc)
    if note:
        line += "   (%s)" % note
    print(line)

print("")
print("=" * 70)
print("Round 5 门禁：%d/%d PASS   活体接口=%s" %
      (len(R) - len(fails), len(R), live_note))
if fails:
    print("失败项：")
    for i, desc, _, note in fails:
        print("  - %s %s %s" % (i, desc, note))
print("=" * 70)
sys.exit(1 if fails else 0)
