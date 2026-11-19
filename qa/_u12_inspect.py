#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""U1/U2 verify: parse the back-to-back pair and check change.xml <-> page M array."""
import os
import re
import io

QA = os.path.dirname(os.path.abspath(__file__))


def clean(v):
    if v is None:
        return ""
    v = v.strip().strip('"').strip("'").strip()
    return "" if v == "undefined" else v


def parse_m(html):
    cells = {}
    for m in re.finditer(r"M\[(\d+)\]\[(\d+)\]\s*=\s*([^;]+);", html):
        cells.setdefault(int(m.group(1)), {})[int(m.group(2))] = m.group(3)
    rank = {}   # titanId -> (homeRank, awayRank, swap)
    for _, row in cells.items():
        tid = clean(row.get(0))
        if tid:
            rank[tid] = (clean(row.get(11)), clean(row.get(12)),
                         clean(row.get(23)).lower() in ("true", "1"))
    return rank, cells


def parse_rows(html):
    # titanId -> (matchid, score, spf)
    out = {}
    for m in re.finditer(r'matchid="(\d+)"', html):
        pass
    # simpler: find HomeTeam_titan blocks and the enclosing row's matchid
    for m in re.finditer(r'id="HomeTeam_(\d+)"', html):
        titan = m.group(1)
        start = html.rfind('matchid="', 0, m.start())
        # find the matchid value
        mm = re.search(r'matchid="(\d+)"', html[start:start + 60])
        if not mm:
            continue
        mid = mm.group(1)
        out[titan] = mid
    return out


def parse_change(raw):
    txt = raw.decode("gb2312", errors="replace")
    res = []
    for m in re.finditer(r"<!\[CDATA\[([\s\S]*?)\]\]>", txt):
        p = m.group(1).split("^")
        res.append(p)
    return res


def main():
    html = io.open(os.path.join(QA, "_live_pair2_page.html"), encoding="utf-8", errors="replace").read()
    raw = open(os.path.join(QA, "_live_pair2_change.xml"), "rb").read()

    rank, cells = parse_m(html)
    titan2mid = parse_rows(html)
    changes = parse_change(raw)

    print("M-array entries:", len(cells), " rank_map:", len(rank))
    print("page rows (titan->matchid):", len(titan2mid))

    sw_dist = {}
    for tid, (hr, ar, s) in rank.items():
        sw_dist[s] = sw_dist.get(s, 0) + 1
    print("swap distribution:", sw_dist)
    # titanIds with swap=true
    sw_true = [t for t, (hr, ar, s) in rank.items() if s]
    print("swap=true titanIds (up to 10):", sw_true[:10], "... total", len(sw_true))

    print("change entries:", len(changes))
    for p in changes:
        tid = p[0].strip() if p else ""
        print("  change:", p[:6], "titanId", tid,
              "in_M=", tid in rank, "in_rows=", tid in titan2mid)

    # cross-check
    for p in changes:
        tid = p[0].strip()
        if tid in rank:
            hr, ar, s = rank[tid]
            print("  MATCH titanId", tid, "mid=", titan2mid.get(tid),
                  "swap=", s, "rank=(", hr, ar, ")",
                  "state=", p[1] if len(p) > 1 else "?", 
                  "home=", p[2] if len(p) > 2 else "?",
                  "away=", p[3] if len(p) > 3 else "?")


if __name__ == "__main__":
    main()
