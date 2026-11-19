#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
U1/U2 背靠背联网采样：同刻抓取 JingCai.aspx 与 change.xml（相隔 ~1s）。

本机有代理，urllib 必须绕过（build_opener(ProxyHandler({}))），Referer 设为比赛页。
（本环境无 requests 模块，故用标准库 urllib。）

产物：
    qa/_live_pair2_page.html    比赛整页（UTF-8）
    qa/_live_pair2_change.xml   比分增量（按原始字节保存，含 CDATA）
    qa/_live_pair2_meta.txt     抓取时刻 / 字节数 / 状态码 / 间隔
"""
import os
import time
import io
import urllib.request

QA = os.path.dirname(os.path.abspath(__file__))
PAGE_URL = "https://cp.titan007.com/buy/JingCai.aspx"
CHANGE_URL = "https://cp.titan007.com/info/match/data/change.xml"
REFERER = PAGE_URL
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")

# 显式空代理，绕过系统代理
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(url):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Referer": REFERER,
        "Accept-Encoding": "identity",  # 不压缩，拿明文
    })
    with OPENER.open(req, timeout=30) as resp:
        return resp.status, resp.headers.get("Content-Type", ""), resp.read()


def main():
    t0 = time.time()
    st_p, ct_p, body_p = get(PAGE_URL + "?%d" % int(t0 * 1000))
    t1 = time.time()
    st_c, ct_c, body_c = get(CHANGE_URL + "?%d" % int(t1 * 1000))
    t2 = time.time()

    with open(os.path.join(QA, "_live_pair2_page.html"), "wb") as f:
        f.write(body_p)
    with open(os.path.join(QA, "_live_pair2_change.xml"), "wb") as f:
        f.write(body_c)

    meta = (
        "page_url=%s\npage_status=%d\npage_bytes=%d\npage_time=%.3f\n"
        "change_url=%s\nchange_status=%d\nchange_bytes=%d\nchange_time=%.3f\n"
        "gap_s=%.3f\ncontent_type_page=%s\ncontent_type_change=%s\n"
    ) % (
        PAGE_URL, st_p, len(body_p), t1,
        CHANGE_URL, st_c, len(body_c), t2,
        t2 - t1, ct_p, ct_c,
    )
    with io.open(os.path.join(QA, "_live_pair2_meta.txt"), "w", encoding="utf-8") as f:
        f.write(meta)
    print(meta)
    print("change head:", repr(body_c[:300]))


if __name__ == "__main__":
    main()
