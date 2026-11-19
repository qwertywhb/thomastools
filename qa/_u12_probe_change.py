#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Probe: is change.xml a GLOBAL live feed or 竞彩-scoped?

Fetch page once (all titanIds), then poll change.xml ~10 times and see overlap.
"""
import os
import re
import io
import time
import urllib.request

QA = os.path.dirname(os.path.abspath(__file__))
PAGE_URL = "https://cp.titan007.com/buy/JingCai.aspx"
CHANGE_URL = "https://cp.titan007.com/info/match/data/change.xml"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": PAGE_URL,
                                               "Accept-Encoding": "identity"})
    with OPENER.open(req, timeout=30) as r:
        return r.read()


def clean(v):
    if v is None:
        return ""
    v = v.strip().strip('"').strip("'").strip()
    return "" if v == "undefined" else v


html = io.open(os.path.join(QA, "_live_pair2_page.html"), encoding="utf-8", errors="replace").read()
cells = {}
for m in re.finditer(r"M\[(\d+)\]\[(\d+)\]\s*=\s*([^;]+);", html):
    cells.setdefault(int(m.group(1)), {})[int(m.group(2))] = m.group(3)
page_tids = set()
for _, row in cells.items():
    tid = clean(row.get(0))
    if tid:
        page_tids.add(tid)
print("page titanIds:", len(page_tids))
print("sample page titanIds:", sorted(page_tids)[:10])
print("min/max:", min(int(t) for t in page_tids), max(int(t) for t in page_tids))

all_change = set()
for i in range(10):
    raw = get(CHANGE_URL + "?%d" % int(time.time() * 1000))
    txt = raw.decode("gb2312", errors="replace")
    ids = []
    for m in re.finditer(r"<!\[CDATA\[([\s\S]*?)\]\]>", txt):
        p = m.group(1).split("^")
        ids.append(p[0].strip())
    all_change.update(ids)
    print("  poll %d: %d entries, ids=%s" % (i, len(ids), ids[:12]))
    time.sleep(3)

print("union of change titanIds:", len(all_change))
overlap = page_tids & all_change
print("overlap with page M:", len(overlap), sorted(overlap)[:20])
print("sample change ids:", sorted(all_change)[:15])
print("change id range:", (min(int(t) for t in all_change if t.isdigit()),
                           max(int(t) for t in all_change if t.isdigit())) if all_change else None)
