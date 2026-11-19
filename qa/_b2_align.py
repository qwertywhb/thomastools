#!/usr/bin/env python3
"""Batch2 任务2 验证：赔率区 4 列对齐。

在给定 y 带内，把「深色墨迹」按 x 聚成簇；第一簇=行标签，其后 3 簇=三个赔率数值列。
对「第1行(胜平负)」「第2行(让球)」各跑一次，比较三列数值簇的质心 x，纵向误差应 ≤1px。

用法:
  python _b2_align.py <png> row1 <y0> <y1> [x0 x1]
  python _b2_align.py <png> row2 <y0> <y1> [x0 x1]
"""
import sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
exec(open(os.path.join(ROOT, "qa", "_b2_measure.py")).read().split("def main")[0])

def clusters(w, h, ch, px, y0, y1, x0, x1, dark=140, min_gap=14, min_w=6):
    cols = []
    for x in range(x0, x1):
        ink = 0
        for y in range(y0, y1):
            i = (y * w + x) * ch
            if px[i] < dark and px[i+1] < dark and px[i+2] < dark:
                ink += 1
        cols.append(ink > 0)
    out = []
    x = 0
    while x < len(cols):
        if cols[x]:
            s = x
            while x < len(cols) and cols[x]:
                x += 1
            e = x - 1
            out.append((x0 + s, x0 + e))
        else:
            x += 1
    # 合并间隔 < min_gap 的相邻簇（同一数值被字符间空隙切开）
    merged = []
    for c in out:
        if merged and c[0] - merged[-1][1] <= min_gap:
            merged[-1] = (merged[-1][0], c[1])
        else:
            merged.append(list(c))
    merged = [tuple(m) for m in merged if (m[1] - m[0]) >= min_w]
    return merged

def report(name, clus):
    centers = [ (a + b) / 2 for a, b in clus ]
    print(f"[{name}] 簇数={len(clus)}")
    for i, (a, b) in enumerate(clus):
        print(f"   col{i}: x[{a}..{b}] 宽{b-a+1} 质心={ (a+b)/2:.1f}")

def main():
    p = sys.argv[1]
    which = sys.argv[2]
    y0, y1 = int(sys.argv[3]), int(sys.argv[4])
    x0 = int(sys.argv[5]) if len(sys.argv) > 5 else 40
    x1 = int(sys.argv[6]) if len(sys.argv) > 6 else 900
    w, h, ch, px = read_png(p)
    clus = clusters(w, h, ch, px, y0, y1, x0, x1)
    report(which, clus)
    # 若恰好 4 簇：打印相邻列边界（数值列的列分隔线 x 估计）
    if len(clus) == 4:
        bounds = [ (clus[i][1] + clus[i+1][0]) / 2 for i in range(3) ]
        print(f"  列分隔线 x ≈ {[round(b,1) for b in bounds]}")

if __name__ == "__main__":
    main()
