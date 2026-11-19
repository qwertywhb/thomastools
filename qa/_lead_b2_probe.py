# -*- coding: utf-8 -*-
"""Lead 独立探针：定位列表边界 + 量化赔率两行 4 列是否真对齐。"""
import numpy as np
from PIL import Image

a = np.asarray(Image.open("qa/shots/match_list_after.png").convert("RGB")).astype(np.int16)
H, W = a.shape[:2]
print(f"[INFO] {W}x{H}  1dp=3.5px")

print("\n[边界探针] y : 左槽x=5 / 卡内x=630 / 右槽x=1250")
prev = None
for y in list(range(600, 700, 10)) + list(range(2380, 2470, 5)) + [2500, 2600, 2700, 2790]:
    l, c, r = a[y, 5], a[y, 630], a[y, 1250]
    print(f"  y={y:5d}  左{tuple(l)}  中{tuple(c)}  右{tuple(r)}")

# 列表区上沿：x=630 处第一行出现非顶栏色
print("\n[上沿定位] x=630 逐行 y=640..700")
for y in range(640, 700, 2):
    print(f"  y={y} {tuple(a[y,630])}", end="   ")
print()

def ink_segments(band, thr=60, gap=12):
    """band: (y0,y1) 返回 [(x0,x1,cx)] 墨水段"""
    sub = a[band[0]:band[1]+1, :, :]
    diff = np.abs(sub - np.array([255,255,255])).max(axis=2)
    col = (diff > thr).sum(axis=0)
    xs = np.where(col > 0)[0]
    if len(xs) == 0: return []
    segs, s = [], xs[0]
    for i in range(1, len(xs)):
        if xs[i] - xs[i-1] > gap:
            segs.append((s, xs[i-1])); s = xs[i]
    segs.append((s, xs[-1]))
    return [(x0, x1, (x0+x1)//2) for x0, x1 in segs]

# 卡1: top=679, 卡外高 331px
top = 679
# 结构: pad4dp=14 / 头13dp=45 / gap2dp=7 / 对阵15dp=53 / gap7 / 赔率1 13dp=45 / gap7 / 赔率2 46 / gap7 / 操作24dp=84 / pad14
y = top + 14
rows = {}
for name, h in [("头部",45),("对阵",53),("赔率1",46),("赔率2",46),("操作",84)]:
    rows[name] = (y, y+h-1); y += h + 7
for k,(y0,y1) in rows.items():
    print(f"  {k}: y={y0}..{y1}")

print("\n[赔率对齐] 卡1 赔率两行 ink 段(x0,x1,center)")
for name in ["赔率1","赔率2"]:
    segs = ink_segments(rows[name])
    print(f"  {name}: {segs}")
    print(f"     centers = {[s[2] for s in segs]}")

# 逐卡验证：用同一相对结构在每张卡上取赔率两行
print("\n[多卡交叉验证] 卡顶=679+n*352，赔率1/2 相对偏移 +118..163 / +171..216")
for n in range(6):
    t = 679 + n*352
    b1 = (t+118, t+163); b2 = (t+171, t+216)
    if b2[1] >= H: print(f"  卡{n+1}: 越界跳过"); continue
    s1 = ink_segments(b1); s2 = ink_segments(b2)
    c1 = [s[2] for s in s1]; c2 = [s[2] for s in s2]
    # 只比较「数值列」中心（去掉最左标签列）
    v1 = c1[1:] if len(c1) > 3 else c1
    v2 = c2[1:] if len(c2) > 3 else c2
    d = [abs(x-yv) for x, yv in zip(v1, v2)] if len(v1) == len(v2) and v1 else None
    print(f"  卡{n+1} y={t}: 行1 centers={c1}")
    print(f"          行2 centers={c2}   各列中心差={d}")

# 底部导航上沿
print("\n[底栏上沿] x=630 逐行 2420..2470 找突变")
for y in range(2420, 2472, 2):
    print(f"  y={y} {tuple(a[y,630])}")
