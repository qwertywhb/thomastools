# -*- coding: utf-8 -*-
"""卡头垂直对齐量化检查（团队负责人自测脚本）。

判据（用户诉求「文字要在框中垂直对齐」）：
  1. 联赛胶囊 / 「单」标签的**框高**应一致（同为 16dp = 56px）；
  2. 框内白色文字的墨迹应在框内**上下留白大致相等**（|上留白-下留白| <= 4px ≈ 1.1dp）；
  3. 无底色的编号文字（如「周五001」）墨迹中线应与三个框的**框中线**对齐（|Δ| <= 4px）。

用法: python _lead_card_check.py <screenshot.png> [label]
"""
import sys
from PIL import Image

DP = 3.5  # 本机 1260x2800 / density 560


def is_capsule(c):
    """胶囊/标签的「有色」像素。
    注意：本机截图里胶囊实际渲染成 (238,126,101)、标签 (236,92,87) —— R 通道被压到接近
    背景值（疑与 HDR 截图/PIL 转换有关），不能按 #FF7B5B=(255,123,91) 写死阈值，
    否则整张图一个都匹配不到（第一版脚本就踩了这个坑，返回 0 个框）。
    """
    r, g, b = c
    return r >= 225 and 60 <= g <= 210 and 55 <= b <= 190


def is_white(c):
    return c[0] > 232 and c[1] > 232 and c[2] > 232


def is_sub(c):
    r, g, b = c
    return abs(r - 138) < 34 and abs(g - 143) < 34 and abs(b - 168) < 34


def blobs(px, W, H, pred, min_w=40, min_h=28):
    """非常朴素的连通块提取（4 邻域，栈式），只用于找胶囊/标签这类实心块。"""
    seen = [[False] * W for _ in range(H)]
    out = []
    for y in range(H):
        for x in range(W):
            if seen[y][x] or not pred(px[x, y]):
                continue
            stack = [(x, y)]
            seen[y][x] = True
            xs = []
            ys = []
            while stack:
                cx, cy = stack.pop()
                xs.append(cx)
                ys.append(cy)
                for nx, ny in ((cx + 1, cy), (cx - 1, cy), (cx, cy + 1), (cx, cy - 1)):
                    if 0 <= nx < W and 0 <= ny < H and not seen[ny][nx] and pred(px[nx, ny]):
                        seen[ny][nx] = True
                        stack.append((nx, ny))
            x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
            if (x1 - x0) >= min_w and (y1 - y0) >= min_h:
                out.append((x0, x1, y0, y1, len(xs)))
    return out


def main():
    path = sys.argv[1]
    label = sys.argv[2] if len(sys.argv) > 2 else path
    im = Image.open(path).convert("RGB")
    W, H = im.size
    px = im.load()
    print(f"=== {label}  {W}x{H} ===")

    caps = blobs(px, W, H, is_capsule)
    # 只保留「宽 60~200px 且高 40~70px」的块：胶囊(≈98x56) / 标签(≈60x56)
    boxes = [b for b in caps if 55 <= (b[1] - b[0]) <= 210 and 38 <= (b[3] - b[2]) <= 72]
    boxes.sort(key=lambda b: (b[2], b[0]))
    print(f"  找到 {len(boxes)} 个框（胶囊/标签）")
    for i, (x0, x1, y0, y1, n) in enumerate(boxes[:6]):
        # 框内白字墨迹（严格限制在框内缩进 2px，避免把框外白底算进来）
        iy = []
        for y in range(y0 + 2, y1 - 1):
            for x in range(x0 + 2, x1 - 1):
                if is_white(px[x, y]):
                    iy.append(y)
        hh = y1 - y0 + 1
        cy = (y0 + y1) / 2
        ww = x1 - x0 + 1
        line = f"  框#{i} x[{x0},{x1}] w={ww}px={ww/DP:.1f}dp | y[{y0},{y1}] h={hh}px={hh/DP:.1f}dp 中心={cy:.1f}"
        if iy:
            i0, i1 = min(iy), max(iy)
            top, bot = i0 - y0, y1 - i1
            line += (f"\n        墨迹 y[{i0},{i1}] h={i1-i0+1}px={(i1-i0+1)/DP:.1f}dp"
                     f" | 上留白={top}px={top/DP:.1f}dp 下留白={bot}px={bot/DP:.1f}dp"
                     f" 差={abs(top-bot)}px | 墨迹中心={(i0+i1)/2:.1f} 相对框心={((i0+i1)/2-cy):+.1f}px")
        else:
            line += "  ⚠ 框内未找到白色墨迹"
        print(line)

    # 灰色编号/时间文字：在卡头同一 y 带内，取所有 TextSub 像素
    if boxes:
        y0 = min(b[2] for b in boxes)
        y1 = max(b[3] for b in boxes)
        gy = []
        for y in range(max(0, y0 - 8), min(H, y1 + 9)):
            for x in range(0, W):
                if is_sub(px[x, y]):
                    gy.append(y)
        if gy:
            print(f"  灰色编号/时间墨迹 y[{min(gy)},{max(gy)}] 中心={(min(gy)+max(gy))/2:.1f}"
                  f"  → 相对框中线 {((min(gy)+max(gy))/2 - (y0+y1)/2):+.1f}px")
        else:
            print("  （未匹配到灰色文字，可能该卡无编号）")
    print()


if __name__ == "__main__":
    main()
