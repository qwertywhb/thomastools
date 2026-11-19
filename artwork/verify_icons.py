#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Evidence collector for the 宏 launcher-icon build.

Checks:
  1. 1024 masters - 5-point RGB sampling + background purity.
  2. 1024 masters - ink bbox + row ink-segments (proof the coords landed).
  3. 48x48 legibility - white ratio, white connected-component count, glyph bbox.
  4. density PNG sizes + round-version corner alpha (must be 0).
  5. adaptive foreground bbox vs the centre 66x66 safe zone (un-zoomed skeleton).
  6. glyph proportion checks on the RAW (un-zoomed) skeleton.
"""

from __future__ import annotations

import os
import sys
from collections import deque
from typing import List, Sequence, Tuple

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render_icons as R  # noqa: E402

BG = (0, 166, 81)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
RES = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   "..", "app", "src", "main", "res"))
NAMES = {"A": "象形·主推", "B": "篆书·方刻", "C": "现代·极简", "D": "真字参照"}
INK_SAMPLE_ROWS = (200, 327, 376, 400, 460, 502, 540, 700, 800)


def white_mask(img: Image.Image) -> np.ndarray:
    a = np.array(img.convert("RGB")).astype(int)
    return (a[:, :, 0] > 200) & (a[:, :, 1] > 200) & (a[:, :, 2] > 200)


def row_segments(row: np.ndarray) -> List[Tuple[int, int]]:
    out: List[Tuple[int, int]] = []
    start = None
    for x, v in enumerate(row):
        if v and start is None:
            start = x
        elif not v and start is not None:
            out.append((start, x - 1))
            start = None
    if start is not None:
        out.append((start, len(row) - 1))
    return out


def connected_components(mask: np.ndarray) -> Tuple[int, List[int]]:
    h, w = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    sizes: List[int] = []
    for sy in range(h):
        for sx in range(w):
            if not mask[sy, sx] or seen[sy, sx]:
                continue
            q = deque([(sy, sx)])
            seen[sy, sx] = True
            n = 0
            while q:
                cy, cx = q.popleft()
                n += 1
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        q.append((ny, nx))
            sizes.append(n)
    sizes.sort(reverse=True)
    return len(sizes), sizes


def sample_points(img: Image.Image) -> None:
    a = np.array(img.convert("RGB"))
    pts = {"LT": (20, 20), "RT": (1003, 20), "LB": (20, 1003),
           "RB": (1003, 1003), "C": (512, 512)}
    bg = (a[:, :, 0] == 0) & (a[:, :, 1] == 166) & (a[:, :, 2] == 81)
    fg = (a[:, :, 0] == 255) & (a[:, :, 1] == 255) & (a[:, :, 2] == 255)
    other = ~(bg | fg)
    tot = a.shape[0] * a.shape[1]
    for name, (x, y) in pts.items():
        print(f"      {name:2s} ({x:4d},{y:4d}) -> RGB{a[y, x].tolist()}")
    print(f"      bg {bg.sum()/tot*100:.2f}% | white {fg.sum()/tot*100:.2f}% | "
          f"AA {other.sum()/tot*100:.3f}%")


def measure_master(img: Image.Image) -> None:
    m = white_mask(img)
    ys, xs = np.where(m)
    print(f"      ink bbox x[{xs.min()},{xs.max()}] W={xs.max()-xs.min()+1}  "
          f"y[{ys.min()},{ys.max()}] H={ys.max()-ys.min()+1}")
    for y in INK_SAMPLE_ROWS:
        ss = row_segments(m[y])
        cs = [(s0 + s1) // 2 for s0, s1 in ss]
        print(f"      y={y:3d}: {ss}  centers={cs}")


def legibility_48(img: Image.Image) -> None:
    small = img.resize((48, 48), Image.LANCZOS)
    m = white_mask(small)
    ys, xs = np.where(m)
    bbox = (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())) if m.any() else None
    ncc, sizes = connected_components(m)
    print(f"      white {m.sum()/m.size*100:.1f}%  bbox={bbox}  "
          f"components={ncc} sizes={sizes[:8]}")


def _ink(points: Sequence[Tuple[float, float]], w: float) -> Tuple[float, float, float, float]:
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    h = w / 2.0
    return (min(xs) - h, min(ys) - h, max(xs) + h, max(ys) + h)


def geom_checks() -> None:
    print("\n" + "=" * 78)
    print("6. glyph proportion checks on the RAW (un-zoomed) skeleton")
    print("=" * 78)
    versions = [
        ("A", R.STROKE_WIDTH_A, R.ROOF_ARCH, True),
        ("B", R.STROKE_WIDTH_B, R.ROOF_FLAT, True),
        ("C", R.STROKE_WIDTH_C, R.ROOF_FLAT_NOFEET, False),
    ]
    for name, w, roof, has_feet in versions:
        hx, rx, px = _ink(R.HENG, w), _ink(roof, w), _ink(R.PIE, w)
        sx, dx = _ink(R.SI_LEFT, w), _ink([R.DOT], R.DOT_DIAMETER)
        hw, rw = hx[2] - hx[0], rx[2] - rx[0]
        print(f"\n  [{name}] W={w}  roof={'有竖脚' if has_feet else '无竖脚'}")
        print(f"     一 ink x[{hx[0]:.0f},{hx[2]:.0f}] w={hw:.0f} > 宀 ink x[{rx[0]:.0f},{rx[2]:.0f}] "
              f"w={rw:.0f}  -> {hw > rw}")
        print(f"     丿 ink left={px[0]:.0f} < 一 ink left={hx[0]:.0f}  -> {px[0] < hx[0]}")
        if has_feet:
            print(f"     宀竖脚底 ink y={rx[3]:.0f}  vs 一上沿 ink y={hx[1]:.0f}  "
                  f"-> 空隙 {hx[1]-rx[3]:.0f}px")
        else:
            print("     宀 无竖脚")
        print(f"     厶左腿顶 ink y={sx[1]:.0f}  vs 一下沿 ink y={hx[3]:.0f}  -> 空隙 {sx[1]-hx[3]:.0f}px")
        print(f"     圆点底 ink y={dx[3]:.0f}  vs 屋顶顶 ink y={rx[1]:.0f}  -> 空隙 {rx[1]-dx[3]:.0f}px")


def main() -> None:
    print("=" * 78)
    print("current stroke data (MASTER = 1.15x zoomed; *_FG = raw)")
    print("=" * 78)
    for k in ("A", "B", "C"):
        for i, st in enumerate(R.STYLES[k].strokes):
            pts = " ".join(f"({x:.1f},{y:.1f})" for x, y in st.points)
            print(f"   {k}[{i}] w={st.width:.0f} {st.cap}/{st.join}: {pts}")
    print(f"   A_FG[6] (foreground, raw): "
          + " ".join(f"({x:.0f},{y:.0f})" for x, y in R.STYLE_A_FG.strokes[6].points))

    print("\n" + "=" * 78)
    print("1/2. 1024 masters - 5-point sampling + ink bbox + row segments")
    print("=" * 78)
    for k in ("A", "B", "C", "D"):
        img = Image.open(os.path.join(OUT, f"app_icon_{k}_1024.png"))
        print(f"\n-- {k} ({NAMES[k]})")
        sample_points(img)
        measure_master(img.convert("RGB"))
        print("   48px legibility:")
        legibility_48(img.convert("RGB"))

    print("\n" + "=" * 78)
    print("4. density PNG sizes + round corner alpha (expect 0)")
    print("=" * 78)
    for dens in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        for fn in ("ic_launcher.png", "ic_launcher_round.png"):
            im = Image.open(os.path.join(RES, f"mipmap-{dens}", fn))
            extra = ""
            if fn.endswith("round.png"):
                a = np.array(im.convert("RGBA"))[:, :, 3]
                extra = (f"  corner-alpha={int(a[0,0]),int(a[0,-1]),int(a[-1,0]),int(a[-1,-1])}")
            print(f"   mipmap-{dens:8s}/{fn:20s} {im.size} {im.mode}{extra}")

    print("\n" + "=" * 78)
    print("5. adaptive foreground bbox + circumscribed radius (raw skeleton)")
    print("=" * 78)
    x0, y0, x1, y1 = R.foreground_bbox_in_vd(R.STYLE_A_FG)
    lo, hi = (108 - 66) / 2, (108 + 66) / 2
    circ = ((x1 - x0) / 2, (y1 - y0) / 2)
    r = (circ[0] ** 2 + circ[1] ** 2) ** 0.5
    print(f"   glyph bbox in 108 coords: x[{x0:.2f},{x1:.2f}] y[{y0:.2f},{y1:.2f}] "
          f"(w={x1-x0:.2f} h={y1-y0:.2f})")
    print(f"   circumscribed radius = {r:.2f}  (hard limit 33 = 66/2, target 30) "
          f"-> <=32: {r <= 32}  <=33: {r <= 33}")
    print(f"   safe circle centre 54, box 66x66=[{lo:.1f},{hi:.1f}] -> inside box: "
          f"{x0>=lo and x1<=hi and y0>=lo and y1<=hi}")

    geom_checks()

    print("\n" + "=" * 78)
    print("7. A master y=700 leftmost ink (long 丿 tail check)")
    print("=" * 78)
    a = np.array(Image.open(os.path.join(OUT, "app_icon_A_1024.png")).convert("RGB")).astype(int)
    m = (a[:, :, 0] > 200) & (a[:, :, 1] > 200) & (a[:, :, 2] > 200)
    xs = np.where(m[700])[0]
    ss = row_segments(m[700])
    print(f"   y=700: segments={ss}  leftmost ink x={xs.min() if xs.size else None}")


if __name__ == "__main__":
    main()
