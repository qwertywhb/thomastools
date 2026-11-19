#!/usr/bin/env python3
"""Gate6（加严）：从真实 okooo 数据里枚举「比赛」tab 实际会渲染的队名，找出真正的最长队名。

数据源 = qa/_okooo_20261016.html（真机 App 的「比赛」tab 走 OkoooParser，GBK）。
本脚本重放 OkoooParser.parse 的取名字段：
  home = wz(0) 的 zhum.title  若空则 data-hname
  away = wz(2) 的 zhum.title  若空则 data-aname
再按源码 11sp 字体度量换算宽度，与布局 B 的「对阵单侧可用宽」比较余量。
"""
import re, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HTML = os.path.join(ROOT, "qa", "_okooo_20261016.html")

EM = {"cjk": 1.00, "digit": 0.556, "latin": 0.552, "dot": 0.291,
      "colon": 0.291, "dash": 0.360, "plus": 0.546, "brk": 0.291, "space": 0.248}

def adv(ch):
    o = ord(ch)
    if 0x4E00 <= o <= 0x9FFF or ch in "，。：" or (0x3000 <= o <= 0x303F) or o >= 0xFF00:
        return EM["cjk"]
    if ch.isdigit(): return EM["digit"]
    if ch.isalpha(): return EM["latin"]
    if ch == '.': return EM["dot"]
    if ch == ':': return EM["colon"]
    if ch == '-': return EM["dash"]
    if ch == '+': return EM["plus"]
    if ch in "[]": return EM["brk"]
    if ch == ' ': return EM["space"]
    return 0.55

def w_dp(s, sp):
    return sum(adv(c) for c in s) * sp

def cjk_count(s):
    return sum(1 for c in s if 0x4E00 <= ord(c) <= 0x9FFF)

# ---- 布局 B 预算（与源码一致）----
SCREEN, LZ_PAD, CARD_PAD_H, ACTION_COL, SPACER = 360.0, 10.0, 10.0, 64.0, 8.0
LEFT = SCREEN - 2*LZ_PAD - 2*CARD_PAD_H - ACTION_COL - SPACER
VS_SP, VS_PAD = 9.0, 4.0
CENTER = w_dp("VS", VS_SP) + 2*VS_PAD
SIDE = (LEFT - CENTER) / 2.0
SP_NAME, SP_RANK = 11.0, 7.0

def parse(html):
    blocks = re.split(r'<div class="touzhu_1"', html)[1:]
    out = []
    for b in blocks:
        def g(p, d=""):
            m = re.search(p, b)
            return m.group(1) if m else d
        order = g(r'data-ordercn="([^"]*)"')
        mid = g(r'data-mid="([^"]*)"')
        hname = g(r'data-hname="([^"]*)"')
        aname = g(r'data-aname="([^"]*)"')
        single = 'data-dg="1"' in b
        league = g(r'class="saiming[^"]*"[^>]*title="([^"]*)"')
        # 切出各 wz 段；每个块含 shenpf + rangqiuspf 两套，Kotlin 只看 shenpf，
        # 故同一 wz 只取「第一次出现」（= shenpf 段）。
        marks = [(m.start(), m.group(1)) for m in re.finditer(r'data-wz="(\d)"', b)]
        seg = {}
        for i, (pos, wz) in enumerate(marks):
            if wz in seg:
                continue
            end = marks[i+1][0] if i+1 < len(marks) else len(b)
            seg[wz] = b[pos:end]
        def nm(wz, fallback):
            s = seg.get(wz, "")
            # Kotlin: z.text().trim() —— 用 zhum 的「内层文本」，非 title 属性
            m = re.search(r'class="zhum[^"]*"[^>]*>([^<]*)<', s)
            v = (m.group(1) if m else "").strip()
            if not v:
                m2 = re.search(r'class="zhum[^"]*"[^>]*title="([^"]*)"', s)
                v = (m2.group(1) if m2 else "").strip()
            return v if v else fallback
        def rank(wz):
            s = seg.get(wz, "")
            p1 = re.search(r'class="p1"[^>]*>([^<]*)<', s)
            p2 = re.search(r'class="p2"[^>]*>\[?([^\]<]*)\]?<', s)
            lg = (p1.group(1) if p1 else "").strip()
            r = (p2.group(1) if p2 else "").strip()
            return lg + r if r and r != "-" else ""
        out.append(dict(order=order, mid=mid, league=league, single=single,
                        home=nm("0", hname), away=nm("2", aname),
                        homeRank=rank("0"), awayRank=rank("2")))
    return out

def main():
    html = open(HTML, "rb").read().decode("gbk")
    ms = parse(html)
    print(f"== 真实 okooo 解析 ==  比赛数={len(ms)}  样本={os.path.basename(HTML)}")
    print(f"布局B 对阵单侧可用宽 SIDE = {SIDE:.2f}dp (center VS={CENTER:.2f}dp)")
    names = []
    for m in ms:
        names.append(m["home"]); names.append(m["away"])
    uniq = sorted(set(n for n in names if n), key=lambda s: (-w_dp(s, SP_NAME), s))
    print(f"\n== 全量唯一队名 {len(uniq)} 个（按渲染宽降序，前 20）==")
    for n in uniq[:20]:
        print(f"  {n:12s} 字={len(n)} 全角={cjk_count(n)} 宽={w_dp(n,SP_NAME):.1f}dp "
              f"单侧余量={SIDE - w_dp(n,SP_NAME):+.2f}dp")
    L = uniq[0]
    print(f"\n== 真正的队名最长者 ==  『{L}』  字符数={len(L)}  全角字={cjk_count(L)}  "
          f"渲染宽={w_dp(L,SP_NAME):.1f}dp  单侧余量={SIDE - w_dp(L,SP_NAME):+.2f}dp")
    # 对阵两侧组合最坏：rank + 2dp + name，两侧取大
    worst = None
    for m in ms:
        for side, nm2, rk2 in (("home", m["home"], m["homeRank"]), ("away", m["away"], m["awayRank"])):
            tot = w_dp(rk2, SP_RANK) + (2.0 if rk2 else 0.0) + w_dp(nm2, SP_NAME)
            if worst is None or tot > worst[0]:
                worst = (tot, m["order"], side, rk2, nm2)
    print(f"== 单侧最坏组合（rank+间距+队名）== {worst[3]}'{worst[4]}'"
          f" 总宽={worst[0]:.1f}dp 余量={SIDE - worst[0]:+.2f}dp  @{worst[1]} {worst[2]}")

    print("\n== 指向性核对（team-lead 点名）==")
    for q in ("布鲁马波卡纳", "SBV精英", "西悉尼漫步者"):
        hit = [m["order"] for m in ms if q in (m["home"], m["away"])]
        print(f"  『{q}』 在真实列表出现 {len(hit)} 次 {hit[:5]}  宽={w_dp(q,SP_NAME):.2f}dp "
              f"余量={SIDE - w_dp(q,SP_NAME):+.2f}dp")

    print("\n== 对阵侧最坏行样例（前 6 场）==")
    for m in ms[:6]:
        for side, nm2, rk2 in (("主", m["home"], m["homeRank"]), ("客", m["away"], m["awayRank"])):
            tot = w_dp(rk2, SP_RANK) + (2.0 if rk2 else 0.0) + w_dp(nm2, SP_NAME)
            print(f"  {m['order']} {side} rank={rk2!r:12s} name={nm2!r:14s} "
                  f"总={tot:.1f}dp 余量={SIDE - tot:+.2f}dp")

if __name__ == "__main__":
    main()
