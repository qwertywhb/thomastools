# -*- coding: utf-8 -*-
"""用「页面底色满宽行」定位卡片间隙，独立算出卡顶/卡高/条数。"""
import numpy as np
from PIL import Image
a = np.asarray(Image.open("qa/shots/match_list_v4.png").convert("RGB")).astype(np.int16)
H,W = a.shape[:2]
PG = np.array([244,245,250])
wide = (np.abs(a[:,10:1250,:]-PG).max(axis=2) <= 3).sum(axis=1) >= int(1240*0.97)
# 卡间隙 = wide 连续段（且高度合理）
gaps=[]; y=0
while y<H:
    if wide[y]:
        s=y
        while y<H and wide[y]: y+=1
        if 4 <= y-s <= 80: gaps.append((s,y-1))
    y+=1
print(f"[A] 页面底色满宽段(候选间隙) = {gaps}")
mids=[(u+v)//2 for u,v in gaps]
print(f"[A] 间隙中点 = {mids}")
if len(mids)>=3:
    d=[mids[i+1]-mids[i] for i in range(len(mids)-1)]
    print(f"[A] 相邻间隙间距 px={d} dp={[round(v/3.5,1) for v in d]}")
# 卡顶 = 前一个间隙底 + 1
tops=[gaps[i][1]+1 for i in range(len(gaps))]
print(f"[A] 各卡顶 y = {tops}")
# 底栏：找底部一块大面积非卡片/非页面色的区域
print(f"[B] 末行颜色 y=2700..2790 @x=630: {[tuple(int(t) for t in a[y,630]) for y in range(2700,2791,15)]}")
print(f"[B] 卡片高度 = 相邻卡顶差 - 间隙高度")
