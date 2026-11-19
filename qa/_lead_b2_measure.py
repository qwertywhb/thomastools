# -*- coding: utf-8 -*-
"""Lead 独立量化：不复用工程师脚本，独立解 PNG。"""
import sys
from PIL import Image
import numpy as np

def load(p):
    im = Image.open(p).convert("RGB")
    a = np.asarray(im).astype(np.int16)
    return im, a

def card_bands(a, x0=70, x1=1190, white_thr=250, min_white=700):
    """按行统计「近白像素数」，连续成段 => 卡片体；返回 [(y_start,y_end)]"""
    strip = a[:, x0:x1, :]
    near_white = (strip > white_thr).all(axis=2).sum(axis=1)
    is_card = near_white >= min_white
    bands, s = [], None
    for y, v in enumerate(is_card):
        if v and s is None:
            s = y
        elif not v and s is not None:
            if y - s > 20:
                bands.append((s, y - 1))
            s = None
    if s is not None and len(is_card) - s > 20:
        bands.append((s, len(is_card) - 1))
    return bands, near_white

def red_pixels(a):
    m = (a[:, :, 0] > 200) & (a[:, :, 1] < 110) & (a[:, :, 2] < 110)
    return m

def main():
    im, a = load("qa/shots/match_list_after.png")
    H, W = a.shape[:2]
    print(f"[INFO] {im.size} H={H} W={W}  (1dp = 3.5px)")
    bands, prof = card_bands(a)
    print(f"[A] 检出卡片体段数 = {len(bands)}")
    for i, (s, e) in enumerate(bands):
        print(f"    卡{i+1}: y={s}..{e}  h={e-s+1}px = {(e-s+1)/3.5:.1f}dp")
    if len(bands) >= 2:
        steps = [bands[i+1][0] - bands[i][0] for i in range(len(bands)-1)]
        print(f"[A] 步距(卡顶间隔) px = {steps} => dp = {[round(s/3.5,1) for s in steps]}")
    if len(bands) >= 3:
        inner = [bands[i+1][0] - bands[i][1] - 1 for i in range(len(bands)-1)]
        print(f"[A] 卡间距 px = {inner} => dp = {[round(v/3.5,1) for v in inner]}")

    # 完全可见卡片：底边必须 < H - 底部导航高度(未知)，先用「底边 < H-40 且顶边 > 0」粗判
    last_bottom = bands[-1][1] if bands else 0
    truncated = last_bottom >= H - 40
    full = len(bands) - (1 if truncated else 0)
    print(f"[A] 完全可见卡数(粗判) = {full}  末段是否触底截断={truncated}")

    # 红框
    r = red_pixels(a)
    print(f"[B] match_list_after 红色像素总数 = {int(r.sum())}")
    ys, xs = np.where(r)
    if len(ys):
        print(f"    红像素 y∈[{ys.min()},{ys.max()}] x∈[{xs.min()},{xs.max()}]")
        uniq = sorted(set(ys.tolist()))
        segs, s = [], uniq[0]
        for i in range(1, len(uniq)):
            if uniq[i] - uniq[i-1] > 5:
                segs.append((s, uniq[i-1])); s = uniq[i]
        segs.append((s, uniq[-1]))
        print(f"    红像素纵向成段 = {segs}")

    im2, a2 = load("qa/shots/match_single_border.png")
    r2 = red_pixels(a2)
    print(f"[C] match_single_border {im2.size} 红色像素 = {int(r2.sum())}")
    ys2, xs2 = np.where(r2)
    if len(ys2):
        print(f"    红 y∈[{ys2.min()},{ys2.max()}] x∈[{xs2.min()},{xs2.max()}]")
        # 左边缘厚度
        ymid = (ys2.min() + ys2.max()) // 2
        row = r2[ymid]
        xr = np.where(row)[0]
        print(f"    y={ymid} 红像素 x = {xr.tolist()[:12]} ... 共{len(xr)}")
        # 左竖边厚度
        segs2, s2 = [], xr[0]
        for i in range(1, len(xr)):
            if xr[i] - xr[i-1] > 2:
                segs2.append((s2, xr[i-1])); s2 = xr[i]
        segs2.append((s2, xr[-1]))
        print(f"    y={ymid} 红色水平成段 = {segs2}  => 厚度px={[b-a+1 for a,b in segs2]}")

if __name__ == "__main__":
    main()
