# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open("qa/shots/b3_tab_02_card_sameodds.png").convert("RGB")
W,H = im.size; px = im.load()
def lum(c): return (c[0]*299+c[1]*587+c[2]*114)//1000
prof=[]
for y in range(500, 1300):
    n = sum(1 for x in range(42,1218) if lum(px[x,y]) < 175)
    prof.append((y,n))
# 打印有墨迹的行带
bands=[]; cur=None
for y,n in prof:
    if n>20:
        if cur is None: cur=[y,y]
        else: cur[1]=y
    else:
        if cur: bands.append(tuple(cur)); cur=None
if cur: bands.append(tuple(cur))
print("墨迹行带:", bands[:12])
