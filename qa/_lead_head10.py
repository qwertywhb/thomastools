# -*- coding: utf-8 -*-
from PIL import Image
def lum(c): return (c[0]*299+c[1]*587+c[2]*114)//1000
im = Image.open("qa/shots/match_card_v4.png").convert("RGB"); W,H=im.size; px=im.load()
# 找某一行（头部行）里的元素分区
print("=== 我方真机 单卡v4：头部行热图 y=45..115, x=40..250 ===")
for y in range(45,116):
    line=""
    for x in range(40,251):
        L=lum(px[x,y])
        line += "#" if L>225 else ("+" if L>175 else ("." if L>120 else " "))
    print(f"{y:3d} |{line}|")
