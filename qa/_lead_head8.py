# -*- coding: utf-8 -*-
from PIL import Image
p = r"C:/Users/wanghongbo/.workbuddy/clipboard-images/clipboard-2026-10-16T08-36-03-926Z-7d93cf04.png"
im = Image.open(p).convert("RGB")
W,H = im.size
px = im.load()
def lum(c): return (c[0]*299+c[1]*587+c[2]*114)//1000
def cls(c):
    r,g,b=c
    if lum(c)>238 and abs(r-g)<14 and abs(g-b)<16: return 'W'
    if r>240 and 100<g<200 and b<200: return 'O'
    if r>235 and g<100 and b<110: return 'R'
    if b>r and 110<lum(c)<200: return 'g'
    if lum(c)<100: return '#'
    return '.'
print("size",W,H)
for y in range(0,H,2):
    print(f"{y:3d} " + "".join(cls(px[x,y]) for x in range(0,W,4)))
