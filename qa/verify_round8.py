#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Round-8 independent verification for the Android 竞彩 App "比分直播" tab.

Run:
    C:/Users/wanghongbo/.workbuddy/binaries/python/envs/default/Scripts/python.exe -X utf8 qa/verify_round8.py

Design contract for this script
-------------------------------
* It NEVER launches / inspects the App parser.  All HTML/XML values are re-derived
  from the FROZEN byte samples with an INDEPENDENT reference implementation
  written here (regex based), then compared against two independent oracles:
    - ORACLE-1 : the user's original requirement screenshot
                 (clipboard-2026-10-16T06-30-16-827Z-ab2b59aa.png, ~14:30 CST)
    - ORACLE-2 : the engineer's device capture (qa/live_tab_right.png, 15:33 CST)
* Pixel checks use Pillow and re-measure the geometry from raw pixels.
* Device (logcat / package) checks read the evidence files captured live during
  this QA round; they do not touch the device.
* Any FAIL -> non-zero exit code.

User requirements under test
----------------------------
  R1 no leading checkbox in the live table
  R2 no "亚欧析荐" column
  R3 page auto-refreshes every 5 s
  R4 (leftover) at max right-scroll the "大小球" first character must not be clipped
"""

import os
import re
import sys

import numpy as np
from PIL import Image

QA = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(QA)
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "jingcai")

# sha256[:16] anchors for the FROZEN batch-1 sources under test (supplied by team-lead).
SRC_HASHES = {
    os.path.join("ui", "live", "LiveScreen.kt"): "9242e16e3bc0db81",
    os.path.join("ui", "ViewModels.kt"): "879f4c1222065814",
    os.path.join("data", "network", "TitanLiveParser.kt"): "c53cdd72064045b9",
    os.path.join("data", "repository", "LiveScoreRepository.kt"): "1174f492082cb96f",
    os.path.join("data", "model", "LiveModels.kt"): "54ff1ee298751576",
}

_results = []          # (name, ok, detail)
_warns = []
_untested = []


def check(name, ok, detail=""):
    _results.append((name, bool(ok), detail))
    print("[{}] {}  |  {}".format("PASS" if ok else "FAIL", name, detail))
    return bool(ok)


def info(msg):
    print("       . " + msg)


def warn(name, detail):
    _warns.append((name, detail))
    print("[WARN] {}  |  {}".format(name, detail))


def untested(name, detail):
    _untested.append((name, detail))
    print("[UNTESTED] {}  |  {}".format(name, detail))


# ---------------------------------------------------------------------------
# GoalCn conversion table (titan007).  index = round(goal * 4)
# ---------------------------------------------------------------------------
GOAL_CN = [
    "0", "0/0.5", "0.5", "0.5/1", "1", "1/1.5", "1.5", "1.5/2", "2", "2/2.5", "2.5", "2.5/3",
    "3", "3/3.5", "3.5", "3.5/4", "4", "4/4.5", "4.5", "4.5/5", "5", "5/5.5", "5.5", "5.5/6",
    "6", "6/6.5", "6.5", "6.5/7", "7", "7/7.5", "7.5", "7.5/8", "8", "8/8.5", "8.5", "8.5/9",
    "9", "9/9.5", "9.5", "9.5/10", "10", "10/10.5", "10.5", "10.5/11", "11", "11/11.5", "11.5",
    "11.5/12", "12", "12/12.5", "12.5", "12.5/13", "13", "13/13.5", "13.5", "13.5/14", "14",
]


def goal_to_cn(goal):
    try:
        g = float(goal)
    except (TypeError, ValueError):
        return goal
    idx = int(round(g * 4.0))
    return GOAL_CN[idx] if 0 <= idx < len(GOAL_CN) else goal


def _clean(v):
    if v is None:
        return ""
    v = v.strip().strip('"').strip("'").strip()
    return "" if v == "undefined" else v


# ===========================================================================
# INDEPENDENT reference implementation (no App code is used)
# ===========================================================================
def _find_outer_tr_end(html, start):
    """Return index of the outer </tr> that closes the match row starting at `start`,
    skipping nested <table>..</table> (the inner spfItem table)."""
    depth = 0
    i = start
    while True:
        cands = []
        for tok, kind in (("<table", "t"), ("</table>", "c"), ("</tr>", "r")):
            p = html.find(tok, i)
            if p != -1:
                cands.append((p, kind))
        if not cands:
            return -1
        p, k = min(cands)
        if k == "t":
            depth += 1
            i = p + 6
        elif k == "c":
            depth -= 1
            i = p + 8
        else:
            if depth <= 0:
                return p
            i = p + 5


def ref_parse_sample(html_path, dx_path):
    html = open(html_path, encoding="utf-8", errors="replace").read()

    # --- inline script M[i][j] : j0=titanId j11=homeRank j12=awayRank j23=swap
    cells = {}
    for m in re.finditer(r"M\[(\d+)\]\[(\d+)\]\s*=\s*([^;]+);", html):
        cells.setdefault(int(m.group(1)), {})[int(m.group(2))] = m.group(3)
    rank_map = {}          # titanId -> (homeRank, awayRank, swap)
    for _, row in cells.items():
        tid = _clean(row.get(0))
        if tid:
            rank_map[tid] = (_clean(row.get(11)), _clean(row.get(12)),
                             _clean(row.get(23)).lower() in ("true", "1"))

    # --- match rows -------------------------------------------------------
    mid_pos = [(m.group(1), m.start()) for m in re.finditer(r'matchid="(\d+)"', html)]
    rows = {}              # matchid -> dict
    first_by_num = {}      # '001' -> matchid (first occurrence in document order)
    n_td_top = {}          # matchid -> top-level <td> count
    for mid, pos in mid_pos:
        if mid in rows:
            continue
        outer_start = html.rfind("<tr", 0, pos)
        end = _find_outer_tr_end(html, outer_start)
        row = html[outer_start:end + 5] if end >= 0 else html[outer_start:pos]

        # top-level <td> count: strip the inner spfItem table first
        no_inner = re.sub(r"<table[\s\S]*?</table>", "", row, flags=re.I)
        n_td_top[mid] = len(re.findall(r"<td\b", no_inner))

        tds_full = re.findall(r"<td\b[^>]*>.*?</td>", no_inner, flags=re.S)
        tds_txt = [re.sub(r"<[^>]+>", "", t) for t in tds_full]
        num_m = re.search(r"(\d{3})", tds_txt[0]) if tds_txt else None
        num = num_m.group(1) if num_m else ""
        if num and num not in first_by_num:
            first_by_num[num] = mid

        league_td = tds_full[1] if len(tds_full) > 1 else ""
        league = ""
        lm = re.search(r"<a\b[^>]*>([^<]+)</a>", league_td)
        if lm:
            league = lm.group(1).strip()
        colm = re.search(r"background-color:\s*(#[0-9A-Fa-f]{6})", league_td)
        color = colm.group(1).upper() if colm else ""

        tm = re.search(r'title="截止时间：\d{4}-\d{2}-\d{2}\s+(\d{2}:\d{2})"', row)
        time = tm.group(1) if tm else ""

        hm = re.search(r'id="HomeTeam_(\d+)"[^>]*>([^<]+)</a>', row)
        home = hm.group(2).strip() if hm else ""
        titan = hm.group(1) if hm else ""
        am = re.search(r'id="GuestTeam_(\d+)"[^>]*>([^<]+)</a>', row)
        away = am.group(2).strip() if am else ""

        sm = re.search(r'<span class="score[^"]*">([^<]*)</span>', row)
        score = (sm.group(1).strip() or "-") if sm else "-"

        rqm = re.search(r'id="rqTr_%s"><td[^>]*><b>(?:<font[^>]*>)?([-+]?\d+)' % mid, row)
        handicap = rqm.group(1) if rqm else ""

        def sp(sfx):
            mm = re.search(r'id="sp_%s_%s">([^<]*)</span>' % (mid, sfx), row)
            return mm.group(1).strip() if mm else ""

        hr, ar, sw = rank_map.get(titan, ("", "", False))
        rows[mid] = dict(
            matchid=mid, titan=titan, num=num, league=league, color=color, time=time,
            home=home, away=away, hr=hr, ar=ar, swap=sw, score=score, handicap=handicap,
            spf=(sp("52"), sp("53"), sp("54")), rqspf=(sp("1"), sp("2"), sp("3")),
        )
    return rows, rank_map, n_td_top, first_by_num


def ref_parse_ou(dx_path):
    xml = open(dx_path, encoding="utf-8", errors="replace").read().replace("\ufeff", "")
    out = {}
    for m in re.finditer(r"<i>([^<]+)</i>", xml):
        p = [x.strip() for x in m.group(1).split(",")]
        if len(p) < 6 or p[0] != "3":
            continue
        out[p[1]] = (p[3], goal_to_cn(p[4]), p[5])   # home water, GoalCn, away water
    return out


# ---------------------------------------------------------------------------
# ORACLE-1 : user's original requirement screenshot (~14:30 CST), transcribed
#            column values for 001..004.  These fields do NOT drift with time.
#            (Odds are transcribed too and happen to equal the 14:39 sample.)
# ---------------------------------------------------------------------------
ORACLE_USER = {
    "001": dict(league="澳超", color="#FF7000", time="17:00", home="悉尼FC", away="西悉尼",
                hr="5", ar="12", ou=("0.90", "2.5/3", "0.98"), rq="-1",
                spf=("2.03", "3.53", "2.83"), rqspf=("3.95", "3.92", "1.61")),
    "002": dict(league="德乙", color="#CA00CA", time="22:00", home="科特布斯", away="纽伦堡",
                hr="8", ar="2", ou=("1.03", "3.5/4", "0.85"), rq="+1",
                spf=("3.55", "4.35", "1.62"), rqspf=("1.97", "3.85", "2.77")),
    "003": dict(league="瑞典超", color="#004488", time="22:00", home="布鲁马波", away="佐加顿斯",
                hr="11", ar="2", ou=("0.83", "3", "1.04"), rq="+1",
                spf=("6.00", "4.75", "1.33"), rqspf=("2.72", "3.82", "2.00")),
    "004": dict(league="荷甲", color="#FF6699", time="22:00", home="海伦芬", away="SBV精英",
                hr="10", ar="9", ou=("0.99", "3.5", "0.89"), rq="-1",
                spf=("1.42", "4.50", "4.95"), rqspf=("2.34", "3.50", "2.40")),
}

# ---------------------------------------------------------------------------
# ORACLE-2 : engineer's device capture qa/live_tab_right.png (15:33 CST),
#            transcribed.  Odds here are ~54 min later -> may have drifted.
# ---------------------------------------------------------------------------
DEVICE_1533 = {
    "001": dict(ou=("1.00", "3", "0.88"), rq="-1",
                spf=("2.03", "3.53", "2.83"), rqspf=("3.95", "3.92", "1.61")),
    "002": dict(ou=("1.03", "3.5/4", "0.85"), rq="+1",
                spf=("3.73", "4.45", "1.57"), rqspf=("2.05", "3.75", "2.67")),
    "003": dict(ou=("0.83", "3", "1.04"), rq="+1",
                spf=("6.00", "4.75", "1.33"), rqspf=("2.72", "3.82", "2.00")),
    "004": dict(ou=("0.99", "3.5", "0.89"), rq="-1",
                spf=("1.39", "4.65", "5.15"), rqspf=("2.32", "3.40", "2.48")),
}


# ===========================================================================
# A. frozen sample reconciliation
# ===========================================================================
def part_a():
    print("\n===== A. frozen sample independent reconciliation =====")
    rows, rank_map, n_td, first_by_num = ref_parse_sample(
        os.path.join(QA, "_live_sample.html"),
        os.path.join(QA, "_live_sample_dx101.xml"))
    ou = ref_parse_ou(os.path.join(QA, "_live_sample_dx101.xml"))
    info("reference impl found {} match rows, {} rank entries, {} OU rows (company 3)"
         .format(len(rows), len(rank_map), len(ou)))

    # A0 structural: 13 top-level td in every sample row
    tdv = sorted(set(n_td.values()))
    check("A0 every match tr has 13 top-level <td>",
          tdv == [13], "observed distinct counts = {} (rows={})".format(tdv, len(n_td)))

    for num in ("001", "002", "003", "004"):
        mid = first_by_num.get(num)
        r = rows.get(mid)
        exp = ORACLE_USER[num]
        if r is None:
            check("A1 row {} present".format(num), False, "row not found in sample")
            continue
        info("row {} -> matchid {} ({})".format(num, mid, r["league"]))
        o = ou.get(r["matchid"], ("-", "-", "-"))
        got = dict(
            league=r["league"], color=r["color"], time=r["time"], home=r["home"],
            away=r["away"], hr=r["hr"], ar=r["ar"], num=r["num"], rq=r["handicap"],
            ou=o, spf=r["spf"], rqspf=r["rqspf"],
        )
        # structural (non-drifting) fields vs ORACLE-1
        struct_ok = all(got[k] == exp[k] for k in
                        ("league", "color", "time", "home", "away", "hr", "ar", "rq"))
        check("A1-{} structural fields == user oracle".format(num), struct_ok,
              "league={} color={} time={} {}{}-{}{} rq={}".format(
                  got["league"], got["color"], got["time"], got["hr"], got["home"],
                  got["away"], got["ar"], got["rq"]))
        odds_ok = (got["ou"] == exp["ou"] and got["spf"] == exp["spf"]
                   and got["rqspf"] == exp["rqspf"])
        check("A1-{} odds == user oracle (14:30) ".format(num), odds_ok,
              "ou={} spf={} rqspf={}".format(got["ou"], got["spf"], got["rqspf"]))

    # A2 reference vs device(15:33): structural must match, odds may drift
    print("   -- A2 reference(sample,14:39) vs device(15:33) --")
    drift_fields = []
    for num in ("001", "002", "003", "004"):
        r = rows[first_by_num[num]]
        dev = DEVICE_1533[num]
        cur_ou = ou.get(r["matchid"], ("-", "-", "-"))
        # structural first
        if r["handicap"] != dev["rq"]:
            check("A2-{} handicap sign stable".format(num), False,
                  "sample rq={} device rq={}".format(r["handicap"], dev["rq"]))
        else:
            check("A2-{} handicap sign stable".format(num), True,
                  "rq={} (both)".format(r["handicap"]))
        for label, cur, dv in (("ou", cur_ou, dev["ou"]),
                               ("spf", r["spf"], dev["spf"]),
                               ("rqspf", r["rqspf"], dev["rqspf"])):
            if cur != dv:
                drift_fields.append("{}.{} sample={} device={}".format(num, label, cur, dv))
    if drift_fields:
        warn("A2 odds drift between 14:39 sample and 15:33 device (expected, live market)",
             "; ".join(drift_fields))
    else:
        info("no odds difference between 14:39 sample and 15:33 device")

    # the GoalCn slash conversion is exercised on the sample itself
    check("A3 GoalCn slash-format conversion (2.75->2.5/3, 3.75->3.5/4)",
          goal_to_cn("2.75") == "2.5/3" and goal_to_cn("3.75") == "3.5/4"
          and goal_to_cn("3") == "3" and goal_to_cn("3.5") == "3.5",
          "2.75->{} 3.75->{} 3->{} 3.5->{}".format(
              goal_to_cn("2.75"), goal_to_cn("3.75"), goal_to_cn("3"), goal_to_cn("3.5")))

    # ---- E: reverse checks -------------------------------------------------
    print("   -- E reverse checks --")
    # recompute swap distribution
    sw = {}
    for tid, (hr, ar, s) in rank_map.items():
        sw[s] = sw.get(s, 0) + 1
    check("E2 swap(M[i][23]) distribution recorded", True,
          "distribution = {}".format(sw))
    if True not in sw:
        untested("E2 swap branch exercised on real data",
                 "sample has 0 rows with swap=true -> applyChanges swap semantics "
                 "cannot be validated against real data (code branch exists)")
    # change.xml titanId mapping
    chg_raw = open(os.path.join(QA, "_live_sample_change.xml"),
                   encoding="utf-8", errors="replace").read()
    cm = re.search(r"<!\[CDATA\[([\s\S]*?)\]\]>", chg_raw)
    parts = cm.group(1).split("^") if cm else []
    tid = parts[0].strip() if parts else ""
    present = tid in rank_map
    info("change.xml D0 titanId={} state={} home={} away={}".format(
        tid, parts[1] if len(parts) > 1 else "?", parts[2] if len(parts) > 2 else "?",
        parts[3] if len(parts) > 3 else "?"))
    if present:
        check("E1 change.xml titanId maps to a sample match", True, "titanId {} found".format(tid))
    else:
        untested("E1 change.xml titanId -> same-match mapping",
                 "titanId {} is NOT present in the 14:39 sample M array "
                 "(change.xml captured for a different match/time); parseChanges "
                 "field extraction itself is verified by shape, but the merge map "
                 "to a shown match cannot be confirmed offline".format(tid))


# ===========================================================================
# B. source contract assertions
# ===========================================================================
def _read(p):
    with open(p, encoding="utf-8", errors="replace") as f:
        return f.read()


def part_b():
    print("\n===== B. source contract assertions =====")
    ls_path = os.path.join(SRC, "ui", "live", "LiveScreen.kt")
    vm_path = os.path.join(SRC, "ui", "ViewModels.kt")
    ls = _read(ls_path)
    vm = _read(vm_path)

    def lineno(text, pat):
        for i, line in enumerate(text.splitlines(), 1):
            if re.search(pat, line):
                yield i

    # R1/R2 : forbidden tokens
    for tok in ("CheckBox", "COL_XJ", "XjCell"):
        hits = list(lineno(ls, r"\b" + tok))
        check("B1 LiveScreen.kt has no '{}'".format(tok), not hits, "hits={}".format(hits))
    sel_hits = [i for i in lineno(ls, r"\bselected\b")]
    check("B2 LiveScreen.kt has no 'selected'", not sel_hits, "hits={}".format(sel_hits))
    xj_hits = list(lineno(ls, "亚欧析荐"))
    non_comment = [i for i in xj_hits
                   if not ls.splitlines()[i - 1].lstrip().startswith(("//", "*", "/*"))]
    check("B2b '亚欧析荐' only in comments (no functional ref)", not non_comment,
          "all hits={} non-comment={}".format(xj_hits, non_comment))

    # header order
    order = [(ls[:m.start()].count("\n") + 1, m.group(1))
             for m in re.finditer(r'HeadCell\("([^"]+)"', ls)]
    names = [n for _, n in order]
    expect = ["联赛", "时间", "主队-比分-客队", "大小球", "让球"]
    check("B3 header column order (编号->联赛->时间->对阵->大小球->让球->胜平负/让球胜平负)",
          names == expect, "HeadCell order={} (编号 is the fixed col)".format(names))
    vm_tail = vm.find("胜平负")
    check("B3b header has 胜平负/让球胜平负 block",
          "胜平负" in ls and "让球胜平负" in ls, "both labels present")

    # tail formula
    tail_lines = [i for i in lineno(ls, r"COL_OU_START \+ viewportW - CONTENT_W")]
    check("B4 tail formula = (COL_OU_START + viewportW - CONTENT_W).coerceIn(0,TAIL_MAX)",
          bool(tail_lines) and "coerceIn(0.dp, TAIL_MAX)" in ls,
          "formula at line(s) {}".format(tail_lines))

    # same tail to header and rows
    hdr_tail = bool(re.search(r"TableHeader\(hScroll,\s*tail\)", ls))
    row_tail = bool(re.search(r"MatchRow\([^)]*tail\s*=\s*tail", ls))
    check("B5 header & data rows use the SAME tail value", hdr_tail and row_tail,
          "TableHeader(hScroll,tail)={} MatchRow(...,tail=tail)={}".format(hdr_tail, row_tail))

    # 5s tick
    dly = [i for i in lineno(ls, r"delay\(5_000\)")]
    check("B6 delay(5_000) in tick loop", bool(dly), "line(s) {}".format(dly))

    # full refresh only every 3rd tick
    mod3 = [i for i in lineno(vm, r"idx\s*%\s*3\s*==\s*0")]
    check("B7 full-page refresh only every 3rd tick (idx % 3 == 0)",
          bool(mod3), "ViewModels.kt line(s) {}".format(mod3))

    # log level
    logd = list(lineno(vm, r"Log\.d\("))
    logi = [i for i in lineno(vm, r'Log\.i\("LiveRefresh"')]
    check("B8 tick log uses Log.i (OriginOS drops Log.d)", bool(logi) and not logd,
          "Log.i at {} / Log.d at {}".format(logi, logd))

    # mutex
    check("B9 LiveViewModel.tick() guarded by Mutex",
          "Mutex()" in vm and "mutex.withLock" in vm,
          "has Mutex()={} / withLock={}".format("Mutex()" in vm, "mutex.withLock" in vm))

    return ls, vm


# ===========================================================================
# C. pixel-level verification (Pillow)
# ===========================================================================
def _load(name):
    return np.asarray(Image.open(os.path.join(QA, name)).convert("RGB")).astype(int)


def _ink_segments(mask_band, gap=10):
    col = mask_band.sum(axis=0)
    xs = [x for x in range(len(col)) if col[x] > 0]
    if not xs:
        return []
    out = []
    s = p = xs[0]
    for x in xs[1:]:
        if x - p > gap:
            out.append((s, p))
            s = x
        p = x
    out.append((s, p))
    return out


def _fixed_col_boundary(left, right):
    d = np.abs(left - right).sum(axis=2)[320:2500]
    frac = (d > 30).mean(axis=0)
    for x in range(200):
        if frac[x] > 0.5:
            return x
    return None


def _row1_band(img):
    # locate the "001" text group inside the fixed column x in [20,100]
    ink = (np.abs(img[:, 20:100] - np.array([255, 255, 255])).sum(axis=2) > 90)
    rowink = ink.sum(axis=1)
    groups = []
    ys = [y for y in range(400, 2600) if rowink[y] > 0]
    s = p = ys[0]
    for y in ys[1:]:
        if y - p > 12:
            groups.append((s, p))
            s = y
        p = y
    groups.append((s, p))
    groups = [g for g in groups if (g[1] - g[0]) >= 8]
    c = (groups[0][0] + groups[0][1]) // 2
    return c - 42, c + 42, groups[0]


def _check_right(img, label, left_img):
    print("   -- {} --".format(label))
    H, W, _ = img.shape
    exp_num_w = 119  # 34dp * 3.5px
    b = _fixed_col_boundary(left_img, img)
    check("C1[{}] fixed 编号 col right edge == 119px (34dp)".format(label),
          b is not None and abs(b - exp_num_w) <= 2, "measured={}px".format(b))

    # OU header ink
    hdr = img[172:242]
    hi = (np.abs(hdr - np.array([247, 248, 252])).sum(axis=2) > 90)
    hsegs = _ink_segments(hi)
    ou_hdr = [s for s in hsegs if 110 < s[0] < 520]
    check("C2[{}] 大小球 header ink exists and starts > 119px".format(label),
          bool(ou_hdr) and ou_hdr[0][0] > exp_num_w,
          "header segments={} first_OU={}".format(hsegs, ou_hdr[0] if ou_hdr else None))

    # first OU water value ink
    y0, y1, grp = _row1_band(img)
    band = img[y0:y1]
    ink = (np.abs(band - np.array([255, 255, 255])).sum(axis=2) > 90)
    segs = _ink_segments(ink)
    ou_vals = [s for s in segs if s[0] > exp_num_w and s[0] < 520]
    first = ou_vals[0] if ou_vals else None
    if first:
        width = first[1] - first[0] + 1
        check("C3[{}] first 大小球 value ink start > 119px (not clipped)".format(label),
              first[0] > exp_num_w,
              "start={} (row band y{})".format(first[0], (y0, y1)))
        check("C4[{}] first 大小球 value ink width ~ 4 chars".format(label),
              50 <= width <= 85, "width={}px (segment={})".format(width, first))
    else:
        check("C3[{}] first 大小球 value ink found".format(label), False, "none")

    # odds cells present, rightmost ink inside screen
    fo = (np.abs(img - np.array([255, 232, 213])).sum(axis=2) < 10).mean(axis=0)
    oxs = [x for x in range(W) if fo[x] > 0.3]
    check("C5[{}] odds block (#FFE8D5) present & fully on screen".format(label),
          bool(oxs) and 635 <= min(oxs) and max(oxs) <= 1185 and max(oxs) < W,
          "odds-bg x=[{},{}] (screen width {})".format(min(oxs), max(oxs), W) if oxs else "none")
    maxink = max(x for x in range(W) if ink[:, x].sum() > 0)
    check("C6[{}] rightmost ink < screen width (no right clipping)".format(label),
          maxink < W, "max_row1_ink={} screen={}".format(maxink, W))


def _check_left(img, label):
    print("   -- {} --".format(label))
    H, W, _ = img.shape
    # header segments on white/very-light header bg
    hdr = img[172:242]
    hi = (np.abs(hdr - np.array([247, 248, 252])).sum(axis=2) > 90)
    hsegs = _ink_segments(hi, gap=10)
    check("C7[{}] leftmost header ink starts at 编号 (~x23), no checkbox to its left".format(label),
          bool(hsegs) and 15 <= hsegs[0][0] <= 32, "header segments={}".format(hsegs))
    check("C8[{}] left header has 4 columns (编号/联赛/时间/主队-比分-客队)".format(label),
          len(hsegs) == 4, "count={} segs={}".format(len(hsegs), hsegs))
    # no checkbox glyph in the left strip: only thin (<=3px) full-width divider lines
    # may cross x[0,20]; a checkbox would be a tall (~40px) block
    strip = (np.abs(img[300:2400, 0:20] - np.array([255, 255, 255])).sum(axis=2) > 90)
    runs = []
    s = None
    for y in range(strip.shape[0]):
        if strip[y].sum() > 0:
            if s is None:
                s = y
        else:
            if s is not None:
                runs.append(y - s)
                s = None
    if s is not None:
        runs.append(strip.shape[0] - s)
    check("C9[{}] left strip x[0,20] has only thin divider lines (no checkbox glyph)".format(label),
          all(r <= 3 for r in runs),
          "ink row-run heights in strip = {} (checkbox would be a tall run)".format(runs))


def part_c():
    print("\n===== C. pixel-level verification =====")
    lr = _load("live_tab_right.png")
    ll = _load("live_tab.png")
    mr = _load("qa_r8_right.png")
    ml = _load("qa_r8_left.png")
    for img, name in ((lr, "live_tab_right.png"), (ll, "live_tab.png"),
                      (mr, "qa_r8_right.png"), (ml, "qa_r8_left.png")):
        print("   size {} = {}x{}".format(name, img.shape[1], img.shape[0]))
    _check_right(lr, "engineer live_tab_right.png", ll)
    _check_right(mr, "qa fresh qa_r8_right.png", ml)
    _check_left(ll, "engineer live_tab.png")
    _check_left(ml, "qa fresh qa_r8_left.png")


# ===========================================================================
# D. device evidence (captured live during this QA round)
# ===========================================================================
def part_d():
    print("\n===== D. device evidence =====")
    # D0: anchor that the FROZEN source under test is unchanged (sha256 first 16 hex).
    # The on-device APK cannot be pinned by lastUpdateTime (it is rebuilt repeatedly),
    # so we prove the CODE under test is unmodified + prove behaviour by live tests.
    import hashlib
    src_ok = True
    det = []
    for rel, exp in SRC_HASHES.items():
        p = os.path.join(SRC, rel)
        if not os.path.exists(p):
            det.append("{} MISSING".format(rel))
            src_ok = False
            continue
        h = hashlib.sha256(open(p, "rb").read()).hexdigest()[:16]
        det.append("{}={}{}".format(rel, h, "" if h == exp else " (EXPECTED {})".format(exp)))
        src_ok = src_ok and (h == exp)
    check("D0 5 frozen source files sha256[:16] match anchors (source under test unmodified)",
          src_ok, "; ".join(det))

    pkg_p = os.path.join(QA, "r8_device_package.txt")
    if not os.path.exists(pkg_p):
        untested("D1 device package info", "r8_device_package.txt missing")
    else:
        txt = _read(pkg_p)
        lu = re.search(r"lastUpdateTime=([\d\- :]+)", txt)
        luS = lu.group(1).strip() if lu else ""
        info("D1 recorded lastUpdateTime={} (informational; NOT used as identity proof)"
             .format(luS))
        warn("D1b APK identity",
             "task anchor 7bc8e29df994aa64 is stale: the APK is rebuilt during the round "
             "(installed base.apk == local app-debug.apk, byte-identical). Identity of the "
             "code under test is anchored by D0 source hashes + behaviour tests instead.")

    live_p = os.path.join(QA, "r8_ticks_live.txt")
    after_p = os.path.join(QA, "r8_ticks_after_switch.txt")
    tick_re = re.compile(r"(\d{2}:\d{2}:\d{2}\.\d{3}).*tick #(\d+) t=(\d+) dx=(\S+) chg=(\d+) full=(true|false)")

    def parse_ticks(path):
        out = []
        if os.path.exists(path):
            for line in _read(path).splitlines():
                m = tick_re.search(line)
                if m:
                    out.append(dict(clock=m.group(1), idx=int(m.group(2)),
                                    t=int(m.group(3)), dx=m.group(4),
                                    chg=int(m.group(5)), full=(m.group(6) == "true")))
        return out

    ticks = parse_ticks(live_p)
    if len(ticks) < 4:
        untested("D2/D3 live tick cadence", "not enough tick log lines ({} found)".format(len(ticks)))
    else:
        gaps = [(ticks[i + 1]["t"] - ticks[i]["t"]) / 1000.0 for i in range(len(ticks) - 1)]
        info("tick intervals(s) = {}".format([round(g, 3) for g in gaps]))
        check("D2 intervals ~= 5s (all within 4.5-5.8s)",
              all(4.5 <= g <= 5.8 for g in gaps),
              "min={:.3f} max={:.3f}".format(min(gaps), max(gaps)))
        full_ok = all(t["full"] == (t["idx"] % 3 == 0) for t in ticks)
        check("D2b full=true exactly every 3rd tick",
              full_ok,
              "ticks={}".format([(t["idx"], t["full"]) for t in ticks]))

    sw_p = os.path.join(QA, "r8_switch_meta.txt")
    if os.path.exists(sw_p) and os.path.exists(after_p):
        sww = re.search(r"switch_time=([\d:]+)", _read(sw_p))
        sw = sww.group(1) if sww else ""
        after = parse_ticks(after_p)
        after_clocks = [t["clock"][:8] for t in after]
        new = [c for c in after_clocks if sw and c > sw]
        check("D3 no tick after switching away from the Live tab",
              len(after) == len(ticks) and not new,
              "switch_time={} after_count={} (live_count={}) ticks_after_switch={}".format(
                  sw, len(after), len(ticks), new))
    else:
        untested("D3 tick stops on tab switch", "switch meta / after log missing")


# ===========================================================================
def main():
    print("ROUND-8 verification  |  project root = {}".format(ROOT))
    part_a()
    part_b()
    part_c()
    part_d()

    print("\n===== SUMMARY =====")
    n_pass = sum(1 for _, ok, _ in _results if ok)
    n_fail = len(_results) - n_pass
    print("checks: {} PASS / {} FAIL / {} WARN / {} UNTESTED".format(
        n_pass, n_fail, len(_warns), len(_untested)))
    if _warns:
        print("-- warnings --")
        for n, d in _warns:
            print("   WARN {}: {}".format(n, d))
    if _untested:
        print("-- untested --")
        for n, d in _untested:
            print("   UNTESTED {}: {}".format(n, d))
    if n_fail:
        print("-- failed --")
        for n, ok, d in _results:
            if not ok:
                print("   FAIL {}: {}".format(n, d))
    print("IS_PASS: {}".format("YES" if n_fail == 0 else "NO"))
    return 1 if n_fail else 0


if __name__ == "__main__":
    sys.exit(main())
