# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open("qa/shots/b3_tab_01_coldstart.png").convert("RGB")
W,H = im.size; px = im.load()
def isO(c):
    r,g,b = c
    return r>=245 and 60<=g<=195 and 55<=b<=175
# 找出所有「整行橙色像素连续段」宽度在 40..130 的，报告行号
best=[]
for y in range(H):
    x=0
    while x<W:
        if isO(px[x,y]):
            x0=x
            while x<W and isO(px[x,y]): x+=1
            if 40 <= (x-x0) <= 140:
                best.append((y,x0,x-1,x-x0))
        else: x+=1
print("橙色连续段(宽40..140)数量:", len(best))
if best:
    ys=sorted(set(b[0] for b in best))
    print("行范围:", ys[0], "..", ys[-1], " 行数:", len(ys))
    for b in best[:6]: print("  y=%d x[%d,%d] w=%d" % b)
    for b in best[-6:]: print("  y=%d x[%d,%d] w=%d" % b)
