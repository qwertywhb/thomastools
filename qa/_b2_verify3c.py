#!/usr/bin/env python3
"""Batch2 3c 取证工具（离线；装机后运行）。

子命令:
  cards <png> [xcol]                 -> 卡片步距/单卡高/卡距（px→dp），并数出完全可见整卡数
  profile <png> <y0> <y1> [x0 x1]    -> 逐 y 深色墨迹行剖面（用于定位赔率两行）
  cols <png> <y0> <y1> [x0 x1]       -> 给定 y 带内 x 方向墨迹簇（数值列中心/列分隔线）
  border <png> <cx0> <cx1> <cy0> <cy1> [edge_px]
                                     -> 卡片四边环带内红色像素（单关竖边厚度 / 非单关无红）
"""
import sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
exec(open(os.path.join(ROOT, "qa", "_b2_measure.py")).read().split("def main")[0])

DENS = 3.5  # 560dpi / 160

def load(p):
    return read_png(p)

def at(px, w, ch, x, y):
    i = (y * w + x) * ch
    return (px[i], px[i + 1], px[i + 2])

def near(c, t, tol=3):
    return all(abs(c[i] - t[i]) <= tol for i in range(3))

CARD = (238, 240, 245)
GAP = (244, 245, 250)
NAV = (241, 243, 254)

def is_red(p):
    return p[0] > 190 and p[1] < 130 and p[2] < 130

def cmd_cards(png, xcol=630):
    w, h, ch, px = load(png)
    def cls(y):
        c = at(px, w, ch, xcol, y)
        if near(c, GAP): return 'GAP'
        if near(c, NAV): return 'NAV'
        return 'CARD'
    runs = []
    y = 500
    while y < 2450:
        if cls(y) == 'CARD':
            s = y
            while y < 2450 and cls(y) == 'CARD':
                y += 1
            runs.append((s, y - 1))
        else:
            y += 1
    cards = [(a, b) for a, b in runs if (b - a) >= 250]
    print(f"[cards] 检测到 {len(cards)} 个卡身区间 (x={xcol})")
    prev = None
    for i, (a, b) in enumerate(cards, 1):
        line = f"  #{i} y[{a}..{b}] 高{b-a+1}px={ (b-a+1)/DENS :.1f}dp"
        if prev is not None:
            gap = a - prev - 1
            pitch = b - prev
            line += f" | 卡距{gap}px={gap/DENS:.1f}dp 步距{pitch}px={pitch/DENS:.1f}dp"
        print(line)
        prev = b
    return cards

def cmd_profile(png, y0, y1, x0, x1):
    w, h, ch, px = load(png)
    for y in range(y0, y1):
        n = 0
        for x in range(x0, x1):
            r, g, b = at(px, w, ch, x, y)
            if r < 150 and g < 150 and b < 150:
                n += 1
        if n:
            print(f"  y={y} dark={n}")

def cmd_cols(png, y0, y1, x0, x1):
    w, h, ch, px = load(png)
    cols = []
    for x in range(x0, x1):
        ink = 0
        for y in range(y0, y1):
            r, g, b = at(px, w, ch, x, y)
            if r < 150 and g < 150 and b < 150:
                ink += 1
        cols.append(ink > 0)
    out = []
    x = 0
    while x < len(cols):
        if cols[x]:
            s = x
            while x < len(cols) and cols[x]:
                x += 1
            out.append([x0 + s, x0 + x - 1])
        else:
            x += 1
    merged = []
    for c in out:
        if merged and c[0] - merged[-1][1] <= 14:
            merged[-1][1] = c[1]
        else:
            merged.append(c)
    merged = [m for m in merged if m[1] - m[0] >= 5]
    print(f"[cols] y[{y0}..{y1}] 簇数={len(merged)}")
    for i, (a, b) in enumerate(merged):
        print(f"   col{i}: x[{a}..{b}] 宽{b-a+1} 中心={(a+b)/2:.1f}")
    if len(merged) == 4:
        bounds = [(merged[i][1] + merged[i+1][0]) / 2 for i in range(3)]
        print(f"   列分隔线 x ≈ {[round(v,1) for v in bounds]}")

def cmd_border(png, cx0, cx1, cy0, cy1, edge=10):
    w, h, ch, px = load(png)
    left = [x for x in range(cx0, cx0 + edge) for y in range(cy0 + 20, cy1 - 20)
            if is_red(at(px, w, ch, x, y))]
    right = [x for x in range(cx1 - edge, cx1 + 1) for y in range(cy0 + 20, cy1 - 20)
             if is_red(at(px, w, ch, x, y))]
    top = [y for y in range(cy0, cy0 + edge) for x in range(cx0 + 20, cx1 - 20)
           if is_red(at(px, w, ch, x, y))]
    bot = [y for y in range(cy1 - edge, cy1 + 1) for x in range(cx0 + 20, cx1 - 20)
           if is_red(at(px, w, ch, x, y))]
    def rng(v):
        return (f"{min(v)}..{max(v)} n={len(set(v))}" if v else "无")
    print(f"[border] 左竖边红 x: {rng(left)}")
    print(f"[border] 右竖边红 x: {rng(right)}")
    print(f"[border] 上边红 y: {rng(top)}")
    print(f"[border] 下边红 y: {rng(bot)}")

def main():
    sub = sys.argv[1]
    png = sys.argv[2]
    args = [int(a) for a in sys.argv[3:]]
    if sub == "cards":
        cmd_cards(png, *args)
    elif sub == "profile":
        cmd_profile(png, *args)
    elif sub == "cols":
        cmd_cols(png, *args)
    elif sub == "border":
        cmd_border(png, *args)
    else:
        print("unknown subcommand")

if __name__ == "__main__":
    main()
