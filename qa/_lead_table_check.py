# -*- coding: utf-8 -*-
"""同奖查询结果表：逐列量「墨迹宽 vs 列宽」，判定裁字与列间空隙。

列权重视觉换算（卡片可视宽 = 1260 - 2*12dp*3.5 = 1176px = 336dp）：
  主客队 .32 / 日期 .16 / 胜 .085 / 平 .085 / 负 .085 / 赛果 .09 / 奖金类型 .17
"""
from PIL import Image
im = Image.open("qa/shots/b3_tab_02_card_sameodds.png").convert("RGB")
W, H = im.size
px = im.load()
print("size", W, H)

def lum(c): return (c[0]*299+c[1]*587+c[2]*114)//1000

# 1) 定位表头底色带 #F0F2FB (240,242,251)
hdr_rows = [y for y in range(H) if sum(1 for x in range(0, W, 7) if abs(px[x,y][0]-240)<6 and abs(px[x,y][1]-242)<6 and abs(px[x,y][2]-251)<6) > 120]
if not hdr_rows:
    print("未找到表头底色带"); raise SystemExit
hy0, hy1 = min(hdr_rows), max(hdr_rows)
print(f"表头底色带 y[{hy0},{hy1}]")

# 2) 第一条数据行：表头下方第一条「有墨迹」的行带
rows_with_ink = []
for y in range(hy1+1, min(H, hy1+120)):
    n = sum(1 for x in range(42, 1218) if lum(px[x,y]) < 170)
    if n > 25: rows_with_ink.append(y)
if not rows_with_ink:
    print("未找到数据行"); raise SystemExit
dy0, dy1 = min(rows_with_ink), max(rows_with_ink)
print(f"第一条数据行 y[{dy0},{dy1}]")

# 3) 列边界（px）
X0, TOT = 42, 1176
cols = [("主队VS客队", .32, 11), ("日期", .16, 10), ("胜", .085, 11), ("平", .085, 11),
        ("负", .085, 11), ("赛果", .09, 12), ("奖金类型", .17, 11)]
acc = 0.0
print(f"{'列':<10}{'列宽px':>7}{'列宽dp':>8}{'墨迹px':>7}{'墨迹dp':>8}{'余量px':>7}{'右侧余px':>8}")
for name, w, sp in cols:
    a = X0 + int(acc*TOT); b = X0 + int((acc+w)*TOT); acc += w
    ix = [x for x in range(a, b) if any(lum(px[x,y]) < 175 for y in range(dy0, dy1+1))]
    if not ix:
        print(f"{name:<10}{b-a:>7}{(b-a)/3.5:>8.1f}{'无墨迹':>7}")
        continue
    i0, i1 = min(ix), max(ix)
    print(f"{name:<10}{b-a:>7}{(b-a)/3.5:>8.1f}{i1-i0+1:>7}{(i1-i0+1)/3.5:>8.1f}{(b-a)-(i1-i0+1):>7}{b-1-i1:>8}")
