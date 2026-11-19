# -*- coding: utf-8 -*-
from PIL import Image
p = r"C:/Users/wanghongbo/.workbuddy/clipboard-images/clipboard-2026-10-16T08-44-22-816Z-e929aa40.png"
im = Image.open(p).convert("RGB")
W,H = im.size
print("size", W, H)
px = im.load()
# 采样：找所有行，统计非白像素
def isw(c): return c[0]>245 and c[1]>245 and c[2]>245
for y in range(H):
    row = [px[x,y] for x in range(W)]
    nw = sum(1 for c in row if not isw(c))
    if nw:
        # 找出该行的颜色种类概览：橙 vs 红 vs 深色文字
        orange=sum(1 for c in row if c[0]>230 and 90<c[1]<190 and c[2]<170)
        red=sum(1 for c in row if c[0]>220 and c[1]<100 and c[2]<110)
        dark=sum(1 for c in row if max(c)<200 and not (c[0]>230 and 90<c[1]<190 and c[2]<170) and not(c[0]>220 and c[1]<100 and c[2]<110))
        print(f"y={y:3d} nonwhite={nw:3d} orange={orange:3d} red={red:3d} dark={dark:3d}")
