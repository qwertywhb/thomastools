# -*- coding: utf-8 -*-
"""按颜色隔离卡头三元素，量其纵向中心（避开卡片边缘 AA）。"""
import numpy as np
from PIL import Image
a = np.asarray(Image.open("qa/shots/match_list_v4.png").convert("RGB")).astype(np.int16)
R,G,B = a[:,:,0], a[:,:,1], a[:,:,2]
orange = (R>230)&(G>80)&(G<180)&(B<140)&(R-B>110)      # 联赛胶囊渐变底
redbox = (R>225)&(G<115)&(B<115)                        # 「单」标签底
graynum= (np.abs(R-138)<28)&(np.abs(G-143)<28)&(np.abs(B-168)<28)  # TextSub 编号/时间文字
for ci, top in enumerate([721,1133,1545,1957]):
    y0,y1 = top+30, top+110
    print(f"--- 卡{ci+1} top={top} 扫描 y={y0}..{y1} ---")
    for name, m in [("联赛胶囊(橙)", orange), ("单标签(红)", redbox), ("编号/时间(灰蓝字)", graynum)]:
        sub = m[y0:y1, 40:1260]
        ys,xs = np.where(sub)
        if not len(ys):
            print(f"   {name}: 无"); continue
        yy0,yy1 = y0+ys.min(), y0+ys.max()
        print(f"   {name}: y[{yy0},{yy1}] h={yy1-yy0+1}px={((yy1-yy0+1)/3.5):.2f}dp 中心={((yy0+yy1)/2):.1f} 相对卡顶={(((yy0+yy1)/2)-top)/3.5:.2f}dp  x[{40+xs.min()},{40+xs.max()}]")
