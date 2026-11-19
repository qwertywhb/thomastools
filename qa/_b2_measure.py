#!/usr/bin/env python3
"""Batch2 单卡步距量化：纯 Python 解 PNG，沿竖直扫描线识别「白卡 / 灰缝」run。

用法: python _b2_measure.py <png> [x_col]
输出: 每个白卡的 [y_top, y_bot] 像素区间、卡高、卡间距、步距；并按密度换算为 dp。
"""
import sys, zlib, struct

def read_png(path):
    with open(path, 'rb') as f:
        data = f.read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', "not a png"
    pos = 8
    idat = b''
    w = h = bitd = colort = None
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos+4])[0]
        typ = data[pos+4:pos+8]
        chunk = data[pos+8:pos+8+ln]
        if typ == b'IHDR':
            w, h, bitd, colort = struct.unpack('>IIBB', chunk[:10])
        elif typ == b'IDAT':
            idat += chunk
        elif typ == b'IEND':
            break
        pos += 12 + ln
    assert bitd == 8, f"only 8-bit supported, got {bitd}"
    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colort]
    stride = w * ch
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if ft == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i-ch]) & 0xFF
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ft == 3:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ft == 4:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                b = prev[i]
                c = prev[i-ch] if i >= ch else 0
                pa = abs(b - c); pb = abs(a - c); pc = abs(a + b - 2*c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y*stride:(y+1)*stride] = line
        prev = line
    return w, h, ch, out

def main():
    path = sys.argv[1]
    xcol = int(sys.argv[2]) if len(sys.argv) > 2 else None
    w, h, ch, px = read_png(path)
    if xcol is None:
        xcol = w - 70  # 卡右侧内部（避开文字）
    print(f"image {w}x{h} ch={ch} scan x={xcol}")

    def is_card(y):
        i = (y * w + xcol) * ch
        r, g, b = px[i], px[i+1], px[i+2]
        return r >= 251 and g >= 251 and b >= 251

    # 找白卡竖条 run
    runs = []
    y = 0
    while y < h:
        if is_card(y):
            y0 = y
            while y < h and is_card(y):
                y += 1
            runs.append((y0, y-1))
        else:
            y += 1
    # 过滤：太短（<150px ≈ 43dp）的当作噪声/文字行内白
    cards = [(a, b) for (a, b) in runs if (b - a) >= 150]
    dens = 3.5  # 560dpi / 160
    print(f"检测到 {len(cards)} 个白卡区间:")
    prev_bot = None
    for idx, (a, b) in enumerate(cards, 1):
        card_h = b - a + 1
        line = f"  #{idx} y[{a}..{b}] 高{card_h}px = {card_h/dens:.1f}dp"
        if prev_bot is not None:
            gap = a - prev_bot - 1
            pitch = b - prev_bot
            line += f" | 卡间距{gap}px={gap/dens:.1f}dp 步距{pitch}px={pitch/dens:.1f}dp"
        print(line)
        prev_bot = b

if __name__ == '__main__':
    main()
