# -*- coding: utf-8 -*-
from PIL import Image
p = r"C:/Users/wanghongbo/.workbuddy/clipboard-images/clipboard-2026-10-16T08-44-22-816Z-e929aa40.png"
im = Image.open(p).convert("RGB")
px = im.load()
W,H = im.size
def lum(c): return (c[0]*299+c[1]*587+c[2]*114)//1000
# 胶囊区域：亮度热图，' '=暗(橙红底) '·'=中 '#'=亮(白字)
print("=== 胶囊 (x=16..76) 亮度热图, y=10..45 ===")
for y in range(10,46):
    line=""
    for x in range(16,77):
        L=lum(px[x,y])
        line += "#" if L>200 else ("+" if L>150 else ("." if L>110 else " "))
    print(f"{y:3d} |{line}|")
print()
print("=== 单 tag (x=140..185) 亮度热图 ===")
for y in range(10,46):
    line=""
    for x in range(140,186):
        L=lum(px[x,y])
        line += "#" if L>200 else ("+" if L>150 else ("." if L>110 else " "))
    print(f"{y:3d} |{line}|")
print()
print("=== 周五001 (x=76..142) 亮度热图（灰蓝字=中暗） ===")
for y in range(10,46):
    line=""
    for x in range(76,143):
        L=lum(px[x,y])
        line += "#" if L>210 else ("+" if L>170 else ("." if L>130 else " "))
    print(f"{y:3d} |{line}|")
