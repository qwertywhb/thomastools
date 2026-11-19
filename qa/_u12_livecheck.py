#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Check: do any 竞彩 page rows have a live score? cross-check with change.xml ids."""
import os
import re
import io
import time
import urllib.request

QA = os.path.dirname(os.path.abspath(__file__))
CHANGE_URL = "https://cp.titan007.com/info/match/data/change.xml"
PAGE_URL = "https://cp.titan007.com/buy/JingCai.aspx"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": PAGE_URL,
                                               "Accept-Encoding": "identity"})
    with OPENER.open(req, timeout=30) as r:
        return r.read()


html = io.open(os.path.join(QA, "_live_pair2_page.html"), encoding="utf-8", errors="replace").read()

# rows with a score span that is not "-"
live = []
for m in re.finditer(r'matchid="(\d+)"', html):
    mid = m.group(1)
    start = html.rfind("<tr", 0, m.start())
    seg = html[start:start + 4000]
    sm = re.search(r'<span class="score[^"]*">([^<]*)</span>', seg)
    sc = (sm.group(1).strip() or "-") if sm else "-"
    hm = re.search(r'id="HomeTeam_(\d+)"[^>]*>([^<]+)</a>', seg)
    if sm and sc not in ("", "-"):
        live.append((mid, hm.group(1) if hm else "?", sc))
print("page rows with a non-empty score:", len(live))
for x in live[:20]:
    print("   mid=%s titan=%s score=%s" % x)

# fetch change now
raw = get(CHANGE_URL + "?%d" % int(time.time() * 1000))
txt = raw.decode("gb2312", errors="replace")
ids = [p.split("^")[0].strip() for p in re.findall(r"<!\[CDATA\[([\s\S]*?)\]\]>", txt)]
print("change ids now:", ids)
