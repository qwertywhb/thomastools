# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open("qa/shots/b3_tab_01_coldstart.png").convert("RGB")
W,H = im.size; px = im.load()
def isG(c):
    r,g,b=c
    return r>=250 and 80<=g<=190 and 85<=b<=165
rows=[]
for y in range(H):
    n=sum(1 for x in range(W) if isG(px[x,y]))
    if n>30: rows.append((y,n))
print("匹配行数:", len(rows))
if rows:
    print("y 范围:", rows[0][0], "..", rows[-1][0])
    # 按连续分组
    groups=[]; s=rows[0][0]; prev=rows[0][0]
    for y,n in rows[1:]:
        if y-prev>2:
            groups.append((s,prev)); s=y
        prev=y
    groups.append((s,prev))
    print("分组:", groups)
    for (a,b) in groups[:4]:
        n=[c for y,c in rows if a<=y<=b]
        print(f"  y[{a},{b}] h={b-a+1}px={(b-a+1)/3.5:.1f}dp 行像素数 min={min(n)} max={max(n)}")
