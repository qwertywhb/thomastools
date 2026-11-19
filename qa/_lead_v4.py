# -*- coding: utf-8 -*-
import numpy as np
from PIL import Image
a = np.asarray(Image.open("qa/shots/match_list_v4.png").convert("RGB")).astype(np.int16)
H, W = a.shape[:2]
print(f"[INFO] {W}x{H} 1dp=3.5px")

CARD = np.array([238,240,245]); NAV=np.array([241,243,254])
def wide(color, tol=3, min_ratio=0.90, x0=40, x1=1150):
    hit = (np.abs(a[:, x0:x1, :]-color).max(axis=2) <= tol).sum(axis=1)
    return hit >= int((x1-x0)*min_ratio)
card_row = wide(CARD); nav_row = wide(NAV)
nav_top=None
for y in range(H-1,0,-1):
    if nav_row[y] and not nav_row[y-1]: nav_top=y; break
print(f"[A] 底栏上沿 y={nav_top} ({(nav_top or 0)/3.5:.1f}dp)")

# 卡片顶端 = card_row 由 False 变 True 的第一个长段
tops=[]
y=0
while y < (nav_top or H):
    if card_row[y]:
        s=y
        while y<(nav_top or H) and card_row[y]: y+=1
        if y-s >= 60: tops.append((s, y-1))
    y+=1
print(f"[A] 卡片带(卡底色连续段) = {tops}")
if len(tops)>=2:
    st=[tops[i+1][0]-tops[i][0] for i in range(len(tops)-1)]
    print(f"[A] 步距 px={st} dp={[round(v/3.5,1) for v in st]}")
print(f"[A] 底边未触底栏的完整卡 = {len([t for t in tops if t[1] <= (nav_top or H)])} 条  (末段={tops[-1] if tops else None}, nav_top={nav_top})")

pts = {"卡片底":(600,1060),"赔率变化底":(1078,812),"同奖查询底":(1078,945)}
base = a[1060,600]
print("\n[B] 底色采样")
for k,(x,y) in pts.items():
    v=a[y,x]; print(f"   {k:12s} ({x},{y}) RGB={tuple(int(t) for t in v)}  vs卡底 maxdiff={int(np.abs(v-base).max())}")
# 扫描按钮区域实际取色（避免坐标偏）
sub=a[790:840,1000:1160]; 
from collections import Counter
c=Counter(map(tuple, sub.reshape(-1,3).tolist()))
print(f"   [赔率变化区 790-840x1000-1160] 最高频3色: {[(k,n) for k,n in c.most_common(3)]}")
sub2=a[920:975,1000:1160]
c2=Counter(map(tuple, sub2.reshape(-1,3).tolist()))
print(f"   [同奖查询区 920-975x1000-1160] 最高频3色: {[(k,n) for k,n in c2.most_common(3)]}")
