# -*- coding: utf-8 -*-
"""同奖查询结果表（新版：左块两行 + 右数值列）逐列量化。

判据（用户诉求「显示太紧、很多被挡住」→ 改两行结构）：
  1. 左块第一行主客队、第二行日期都**不裁字**（右余 > 3px，且日期为 10 字符）；
  2. 右侧胜/平/负/赛果/奖金类型各自右余 > 3px；
  3. 行高 ≈ 两行文字 + 内边距（不再出现单行被挤压成一坨）。

列边界按权重换算（卡片可视宽 336dp = 1176px，左边界 x=42px）：
  左块 .506 / 胜 .085 / 平 .085 / 负 .085 / 赛果 .089 / 奖金类型 .149
"""
import sys
from PIL import Image

X0, CARD = 42, 336.0
COLS = [("左块(主客队/日期)", .506), ("胜", .085), ("平", .085), ("负", .085),
        ("赛果", .089), ("奖金类型", .149)]


def lum(c):
    return (c[0] * 299 + c[1] * 587 + c[2] * 114) // 1000


def main():
    path = sys.argv[1]
    label = sys.argv[2] if len(sys.argv) > 2 else path
    im = Image.open(path).convert("RGB")
    W, H = im.size
    px = im.load()
    print(f"=== {label}  {W}x{H} ===")

    # 1) 找表头底色带 #F0F2FB：要求整行连续匹配（页面底 (244,245,250) 只差 4，必须逐点连查）
    def is_hdr_bg(c):
        return abs(c[0] - 240) < 3 and abs(c[1] - 242) < 3 and abs(c[2] - 251) < 3

    hdr = None
    for y in range(H):
        run = 0
        for x in range(X0, X0 + 1176):
            run = run + 1 if is_hdr_bg(px[x, y]) else 0
            if run > 900:
                hdr = y
                break
        if hdr is not None:
            break
    if hdr is None:
        print("未找到表头底色带（可能不在同奖查询结果页）")
        return
    # 表头带高度：向下连续同为底色的行
    hy1 = hdr
    while hy1 + 1 < H and is_hdr_bg(px[X0 + 600, hy1 + 1]):
        hy1 += 1
    print(f"表头底色带 y[{hdr},{hy1}] h={hy1-hdr+1}px={(hy1-hdr+1)/3.5:.1f}dp")

    # 2) 第一条数据行：表头下方第一段有墨迹的行带
    rows = []
    for y in range(hy1 + 1, min(H, hy1 + 200)):
        n = sum(1 for x in range(X0, X0 + 1176) if lum(px[x, y]) < 175)
        if n > 20:
            rows.append(y)
    if not rows:
        print("未找到数据行")
        return
    dy0, dy1 = min(rows), max(rows)
    print(f"第一条数据行 y[{dy0},{dy1}] h={dy1-dy0+1}px={(dy1-dy0+1)/3.5:.1f}dp")

    # 3) 左块内部：第一行（主客队）/ 第二行（日期）分别量墨迹
    a, b = X0, X0 + int(.506 * CARD * 3.5)
    band = []
    for y in range(dy0, dy1 + 1):
        n = sum(1 for x in range(a, b) if lum(px[x, y]) < 175)
        band.append((y, n))
    # 按「无墨迹行」切成两行
    lines, cur = [], None
    for y, n in band:
        if n > 1:
            cur = [y, y] if cur is None else [cur[0], y]
        else:
            if cur:
                lines.append(tuple(cur))
                cur = None
    if cur:
        lines.append(tuple(cur))
    print(f"左块内部行数: {len(lines)}（期望 2：主客队 / 日期）")
    for i, (y0, y1) in enumerate(lines[:3]):
        xs = [x for y in range(y0, y1 + 1) for x in range(a, b) if lum(px[x, y]) < 175]
        print(f"   第{i+1}行 y[{y0},{y1}] h={y1-y0+1}px 墨迹宽={max(xs)-min(xs)+1}px"
              f"={(max(xs)-min(xs)+1)/3.5:.1f}dp 右余={b-1-max(xs)}px")

    # 4) 右侧各列
    acc = .506
    print(f"{'列':<10}{'列宽dp':>8}{'墨迹dp':>8}{'右余px':>7}")
    for name, w in COLS[1:]:
        ca = X0 + int(acc * CARD * 3.5)
        cb = X0 + int((acc + w) * CARD * 3.5)
        acc += w
        xs = [x for y in range(dy0, dy1 + 1) for x in range(ca, cb) if lum(px[x, y]) < 175]
        if not xs:
            print(f"{name:<10}{(cb-ca)/3.5:>8.1f}{'无':>8}")
            continue
        iw = max(xs) - min(xs) + 1
        print(f"{name:<10}{(cb-ca)/3.5:>8.1f}{iw/3.5:>8.1f}{cb-1-max(xs):>7}"
              + ("   ⚠ 裁字" if cb - 1 - max(xs) <= 1 else ""))


if __name__ == "__main__":
    main()
