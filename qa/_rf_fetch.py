# -*- coding: utf-8 -*-
"""独立拉取 okooo history 页（移动 UA，GBK），不依赖实现者样本。"""
import urllib.request

URL = "https://www.okooo.cn/soccer/match/1341208/history/"
REF = "https://www.okooo.cn/soccer/match/1341208/"
UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

req = urllib.request.Request(URL, headers={
    "User-Agent": UA,
    "Referer": REF,
    "Accept": "text/html,application/xhtml+xml",
    "Accept-Language": "zh-CN,zh;q=0.9",
})
with urllib.request.urlopen(req, timeout=30) as r:
    print("HTTP", r.status)
    raw = r.read()
print("BYTES", len(raw))
html = raw.decode("gbk", errors="replace")
out = r"D:/WorkBuddyPro/project/sporttery/NativeAndroid/qa/_rf_fetch.html"
with open(out, "w", encoding="utf-8") as f:
    f.write(html)
print("SAVED", out, "CHARS", len(html))
