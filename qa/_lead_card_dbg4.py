# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open("qa/shots/b3_tab_01_coldstart.png").convert("RGB")
W,H = im.size; px = im.load()
print("size", W, H)
prev=None
for y in range(150, 900):
    c = px[80, y]
    if prev is None or (abs(c[0]-prev[0])+abs(c[1]-prev[1])+abs(c[2]-prev[2]))>18:
        print(f"  y={y} x=80 -> {c}")
        prev=c
