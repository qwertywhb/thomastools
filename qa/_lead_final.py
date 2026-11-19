# -*- coding: utf-8 -*-
"""Lead 收口量化（一次跑完，不依赖任何 teammate）。"""
import numpy as np
from PIL import Image

a = np.asarray(Image.open("qa/shots/match_list_v3.png").convert("RGB")).astype(np.int16)
H, W = a.shape[:2]
print(f"[INFO] {W}x{H}  1dp=3.5px")

BG  = np.array([244,245,250])   # 页面底色
NAV = np.array([241,243,254])   # 底栏底色

def fullwidth_rows(color, tol=3, x0=10, x1=1250, min_ratio=0.97):
    strip = a[:, x0:x1, :]
    hit = (np.abs(strip - color).max(axis=2) <= tol).sum(axis=1)
    return hit >= int((x1-x0)*min_ratio)

gap = fullwidth_rows(BG)
nav = fullwidth_rows(NAV)
nav_top = None
for y in range(H-1, 0, -1):
    if nav[y] and not nav[y-1]:
        nav_top = y; break
print(f"[A] 底栏上沿 y = {nav_top}  ({(nav_top or 0)/3.5:.1f}dp)")

# 卡片带 = 两个 gap 行之间的连续段
bands, s = [], None
for y in range(H):
    in_card = (not gap[y]) and (not nav[y]) and y < (nav_top or H)
    if in_card and s is None: s = y
    elif not in_card and s is not None:
        if y-s > 40: bands.append((s, y-1))
        s = None
if s is not None: bands.append((s, nav_top-1))
print(f"[A] 卡片带 = {bands}")
if len(bands) >= 2:
    steps = [bands[i+1][0]-bands[i][0] for i in range(len(bands)-1)]
    print(f"[A] 步距 px={steps} dp={[round(v/3.5,1) for v in steps]}")
    print(f"[A] 卡高 px={[e-s+1 for s,e in bands]} dp={[round((e-s+1)/3.5,1) for s,e in bands]}")
    print(f"[A] 卡间距 px={[bands[i+1][0]-bands[i][1]-1 for i in range(len(bands)-1)]}")
# 完全可见 = 底边 <= nav_top
full = [b for b in bands if b[1] <= (nav_top or H)]
print(f"[A] 一屏完全可见整卡 = {len(full)} 条")

def segs(band, thr=60, gapx=12, x0=0, x1=W):
    sub = a[band[0]:band[1]+1, x0:x1, :]
    col = (np.abs(sub - 255).max(axis=2) > thr).sum(axis=0)
    xs = np.where(col > 0)[0]
    if not len(xs): return []
    out, s = [], xs[0]
    for i in range(1, len(xs)):
        if xs[i]-xs[i-1] > gapx: out.append((s, xs[i-1])); s = xs[i]
    out.append((s, xs[-1]))
    return [(u+x0, v+x0, (u+v)//2+x0) for u, v in out]

# 红边框
red = (a[:,:,0] > 200) & (a[:,:,1] < 110) & (a[:,:,2] < 110)
ys, xs = np.where(red)
print(f"[B] 红像素 y∈[{ys.min()},{ys.max()}] x∈[{xs.min()},{xs.max()}] 总数={int(red.sum())}")
if len(bands):
    b0 = bands[0]
    ymid = (b0[0]+b0[1])//2
    xr = np.where(red[ymid])[0]
    g, s = [], (xr[0] if len(xr) else None)
    if len(xr):
        for i in range(1, len(xr)):
            if xr[i]-xr[i-1] > 2: g.append((s, xr[i-1])); s = xr[i]
        g.append((s, xr[-1]))
    print(f"[B] 卡1 中线 y={ymid} 竖边 = {g} 厚度px={[v-u+1 for u,v in g]}")

# 卡内逐行 ink 段：找赔率两行（3 个数值列）
print("[C] 卡1 内逐行 ink 段（仅列出 3 段及以上的行，用于赔率两行对齐）")
for y in range(bands[0][0], bands[0][1]+1):
    sgs = segs((y,y), x0=0, x1=W)
    if len(sgs) >= 3:
        print(f"   y={y:5d} n={len(sgs)} centers={[s[2] for s in sgs]}")
