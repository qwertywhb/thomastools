# -*- coding: utf-8 -*-
from PIL import Image
from collections import Counter
im = Image.open("qa/shots/b3_tab_01_coldstart.png").convert("RGB")
W,H = im.size; px = im.load()
# 第一张卡的头部区域：从图上看约 y 240..330, x 20..500
cnt = Counter()
for y in range(230, 340):
    for x in range(15, 520):
        cnt[px[x,y]] += 1
print("头部区域最常见颜色 Top15:")
for c,n in cnt.most_common(15):
    print("  ", c, n)
