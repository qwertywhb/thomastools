# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open("qa/shots/b3_tab_02_card_sameodds.png").convert("RGB")
W,H = im.size; px = im.load()
def lum(c): return (c[0]*299+c[1]*587+c[2]*114)//1000
X0, TOT = 42, 1176
def col(a_dp, w_dp):
    a = X0 + int(a_dp/336*TOT); b = X0 + int((a_dp+w_dp)/336*TOT); return a,b
cols = [("主客队",0,.32),("日期",.32,.16),("胜",.48,.085),("平",.565,.085),("负",.65,.085),("赛果",.735,.09),("奖金",.825,.17)]
for name,adp,wd in cols:
    a,b = col(adp,wd)
    rows=[]
    for y in range(700, 1300):
        xs=[x for x in range(a,b) if lum(px[x,y])<175]
        if len(xs)>2: rows.append((y,min(xs),max(xs),len(xs)))
    # 合并成行带
    bands=[]; cur=None
    for r in rows:
        if cur is None: cur=[r[0],r[0]]
        elif r[0]-cur[1]<=3: cur[1]=r[0]
        else: bands.append(tuple(cur)); cur=[r[0],r[0]]
    if cur: bands.append(tuple(cur))
    print(f"== {name} 列 x[{a},{b}] w={b-a}px={(b-a)/3.5:.1f}dp  行带数={len(bands)}")
    for (y0,y1) in bands[:3]:
        xs=[x for y in range(y0,y1+1) for x in range(a,b) if lum(px[x,y])<175]
        i0,i1=min(xs),max(xs)
        print(f"   y[{y0},{y1}] 墨迹x[{i0},{i1}] w={i1-i0+1}px={(i1-i0+1)/3.5:.1f}dp  左侧余={i0-a}px 右侧余={b-1-i1}px")
