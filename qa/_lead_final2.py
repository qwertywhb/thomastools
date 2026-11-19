# -*- coding: utf-8 -*-
"""只量真实卡片带（避开顶栏误判），给出赔率两行列中心。"""
import numpy as np
from PIL import Image
a = np.asarray(Image.open("qa/shots/match_list_v3.png").convert("RGB")).astype(np.int16)
X0, X1 = 40, 880          # 排除右侧操作列
CARDS = [(681,1008),(1017,1345),(1352,1683),(1688,2020),(2023,2456),(2516,2593)]
print(f"卡带: {CARDS}")
print(f"步距 px={[CARDS[i+1][0]-CARDS[i][0] for i in range(4)]}  dp={[round((CARDS[i+1][0]-CARDS[i][0])/3.5,1) for i in range(4)]}")
print(f"卡高 px={[e-s+1 for s,e in CARDS[:5]]}  dp={[round((e-s+1)/3.5,1) for s,e in CARDS[:5]]}")
print(f"第6条可见高度 = {2593-2516+1}px = {(2593-2516+1)/3.5:.1f}dp")

def segs(y):
    sub = a[y:y+1, X0:X1, :]
    col = (np.abs(sub-255).max(axis=2) > 60).sum(axis=0)
    xs = np.where(col > 0)[0]
    if not len(xs): return []
    out, s = [], xs[0]
    for i in range(1, len(xs)):
        if xs[i]-xs[i-1] > 20: out.append((s, xs[i-1])); s = xs[i]
    out.append((s, xs[-1]))
    return [(u+X0, v+X0) for u, v in out]

def centers(segs_, n):
    return [ (u+v)//2 for u,v in segs_ ] if len(segs_)==n else None

for ci in range(4):
    y0, y1 = CARDS[ci]
    rows = []
    for y in range(y0, y1+1):
        c = centers(segs(y), 3)
        if c: rows.append((y, c))
    print(f"\n--- 卡{ci+1} (y={y0}..{y1}) 三数值列的行 ---")
    for y, c in rows:
        print(f"   y={y} centers={c}")
    # 取相邻两组（两行赔率）比较
    if len(rows) >= 4:
        grp = []
        cur = [rows[0]]
        for r in rows[1:]:
            if r[0]-cur[-1][0] <= 2: cur.append(r)
            else: grp.append(cur); cur=[r]
        grp.append(cur)
        print(f"   共 {len(grp)} 组；各组平均中心：")
        for g in grp:
            arr = np.array([c for _, c in g], dtype=float)
            print(f"     y≈{g[0][0]}..{g[-1][0]}  平均中心={np.round(arr.mean(axis=0),2).tolist()}")
