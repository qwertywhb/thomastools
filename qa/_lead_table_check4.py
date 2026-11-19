# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open("qa/shots/b3_tab_02_card_sameodds.png").convert("RGB")
W,H = im.size; px = im.load()
def lum(c): return (c[0]*299+c[1]*587+c[2]*114)//1000
X0, CARD = 42, 336.0          # 卡片左边距 px、卡片可视宽 dp
W_ = [("主客队",.32),("日期",.16),("胜",.085),("平",.085),("负",.085),("赛果",.09),("奖金类型",.17)]
acc=0.0; bounds=[]
for n,w in W_:
    a = X0 + int(acc*CARD*3.5); b = X0 + int((acc+w)*CARD*3.5); acc+=w
    bounds.append((n,a,b))
for name,a,b in bounds:
    rows=[]
    for y in range(1100, 2500):
        xs=[x for x in range(a,b) if lum(px[x,y])<175]
        if len(xs)>2: rows.append(y)
    bands=[]; cur=None
    for y in rows:
        if cur is None: cur=[y,y]
        elif y-cur[1]<=6: cur[1]=y
        else: bands.append(tuple(cur)); cur=[y,y]
    if cur: bands.append(tuple(cur))
    print(f"== {name} 列宽={b-a}px={(b-a)/3.5:.1f}dp  行带={len(bands)}")
    for (y0,y1) in bands[:3]:
        xs=[x for y in range(y0,y1+1) for x in range(a,b) if lum(px[x,y])<175]
        i0,i1=min(xs),max(xs)
        print(f"   y[{y0},{y1}] 墨迹w={i1-i0+1}px={(i1-i0+1)/3.5:.1f}dp 左余={i0-a}px 右余={b-1-i1}px"
              + ("   ⚠右余=0 疑似裁字" if b-1-i1<=1 else ""))
