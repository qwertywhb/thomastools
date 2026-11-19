# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open("qa/shots/b3_tab_02_card_sameodds.png").convert("RGB")
px = im.load()
def lum(c): return (c[0]*299+c[1]*587+c[2]*114)//1000
for y in range(700, 900):
    n = sum(1 for x in range(42,1218) if lum(px[x,y])<175)
    if n: print(f"y={y} dark={n}")
