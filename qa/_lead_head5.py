# -*- coding: utf-8 -*-
from PIL import Image
p = r"C:/Users/wanghongbo/.workbuddy/clipboard-images/clipboard-2026-10-16T08-44-22-816Z-e929aa40.png"
im = Image.open(p).convert("RGB")
W,H = im.size
px = im.load()
def cls(c):
    r,g,b = c
    if r>240 and g>240 and b>240: return 'W'          # 纯白（文字/白底）
    if r>235 and g>110 and b<120: return 'O'          # 橙红（边框/渐变）
    if r>225 and g<95 and b<105: return 'R'           # 纯红 #FF4D4F
    if abs(r-238)<12 and abs(g-240)<12 and abs(b-245)<14: return '.'  # 卡底 #EEF0F5
    if 100<r<210 and 100<g<215 and 120<b<235: return 'g'  # 灰蓝文字 #8A8FA8
    if max(c)<90: return '#'
    return '?'
print("     " + "".join(str((x//10)%10) for x in range(0,W,2)))
for y in range(H):
    print(f"{y:3d}  " + "".join(cls(px[x,y]) for x in range(0,W,2)))
