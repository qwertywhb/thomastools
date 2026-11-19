# -*- coding: utf-8 -*-
from PIL import Image
import sys
im = Image.open("qa/shots/b3_tab_01_coldstart.png").convert("RGB")
px = im.load()
def lum(c): return (c[0]*299+c[1]*587+c[2]*114)//1000
# 第一张卡：胶囊 x[70,167] y[637,692]；其右侧是编号/时间文字
print("=== 胶囊 (x=68..170) 亮度热图 y=630..700：' '=胶囊底色 '#'=白字 ===")
for y in range(630, 701):
    line = "".join("#" if lum(px[x,y])>250 else ("+" if lum(px[x,y])>200 else ("." if lum(px[x,y])>150 else " ")) for x in range(68,171))
    print(f"{y:4d} |{line}|")
print()
print("=== 胶囊右侧文字 (x=170..420) y=630..700：'#'=文字墨迹 ===")
for y in range(630, 701):
    line = "".join("#" if lum(px[x,y])<200 else "." for x in range(170,421))
    print(f"{y:4d} |{line}|")
