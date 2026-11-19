# -*- coding: utf-8 -*-
"""量卡头三元素的纵向对齐：各元素 ink 上下边界与中心。"""
import numpy as np
from PIL import Image
a = np.asarray(Image.open("qa/shots/match_list_v4.png").convert("RGB")).astype(np.int16)
CARD = np.array([238,240,245])
def bbox(x0,x1,y0,y1):
    sub = a[y0:y1, x0:x1, :]
    # ink = 与卡底色差 > 25（含彩色胶囊底与文字）
    m = np.abs(sub - CARD).max(axis=2) > 25
    ys,xs = np.where(m)
    if not len(ys): return None
    return (x0+xs.min(), x0+xs.max(), y0+ys.min(), y0+ys.max())
# 卡1 顶 = 721（工程师）+ 我方间隙法一致；卡内 padding 10dp=35px
TOP = 721; PAD = 35
for ci, top in enumerate([721, 1133, 1545]):
    y0, y1 = top+PAD-14, top+PAD+70
    print(f"--- 卡{ci+1} top={top}  扫描 y={y0}..{y1} ---")
    for name, x0, x1 in [("联赛胶囊", 30, 132), ("编号文本", 136, 240), ("单标签", 242, 310)]:
        b = bbox(x0, x1, y0, y1)
        if b is None: print(f"   {name}: 无 ink"); continue
        bx0,bx1,by0,by1 = b
        cy = (by0+by1)/2
        print(f"   {name}: x[{bx0},{bx1}] y[{by0},{by1}] h={by1-by0+1}px={((by1-by0+1)/3.5):.1f}dp  中心y={cy:.1f} 相对卡顶={(cy-top)/3.5:.1f}dp")
    # 整行共同中心
    b = bbox(30, 310, y0, y1)
    if b: print(f"   [三元素整体] y[{b[2]},{b[3]}] 中心={(b[2]+b[3])/2:.1f}")
