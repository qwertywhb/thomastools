#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
临时像素自检（Engineer 自测，可留可删）：纯 Python PNG 解码 + ink 跨段测量。

口径与 team-lead 一致：对某一行 y 带、逐列 x 判断是否有「ink」像素
（任一通道与白底差 > 90，即 min(r,g,b) < 165），再把间隔 ≤10px 的列合并成跨段。
用来证明「横向滚到最右时，大小球三值各自完整、起点 > 固定列右沿(161px)」。
"""
import struct
import sys
import zlib

PNG_SIG = b"\x89PNG\r\n\x1a\n"


def decode_png(path):
    data = open(path, "rb").read()
    assert data[:8] == PNG_SIG, "not a png"
    pos = 8
    width = height = bitdepth = colortype = None
    idat = bytearray()
    while pos < len(data):
        (ln,) = struct.unpack(">I", data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        cdata = data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if ctype == b"IHDR":
            width, height, bitdepth, colortype, comp, flt, interlace = struct.unpack(">IIBBBBB", cdata)
            assert bitdepth == 8, "only 8-bit supported"
            assert interlace == 0, "interlace unsupported"
        elif ctype == b"IDAT":
            idat += cdata
        elif ctype == b"IEND":
            break
    raw = zlib.decompress(bytes(idat))
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colortype]
    bpp = channels
    stride = width * bpp
    out = bytearray(height * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(height):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:  # Sub
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif f == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:  # Average
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:  # Paeth
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                pa = abs(b - c)
                pb = abs(a - c)
                pc = abs(a + b - 2 * c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return width, height, channels, out


def col_has_ink(width, channels, buf, x, y0, y1, thr=165):
    stride = width * channels
    for y in range(y0, y1):
        base = y * stride + x * channels
        r = buf[base]
        g = buf[base + 1]
        b = buf[base + 2]
        if min(r, g, b) < thr:
            return True
    return False


def segments(width, channels, buf, y0, y1, gap=10, thr=165):
    ink = [col_has_ink(width, channels, buf, x, y0, y1, thr) for x in range(width)]
    segs = []
    start = None
    last = None
    for x, has in enumerate(ink):
        if has:
            if start is None:
                start = x
            last = x
        else:
            if start is not None and x - last > gap:
                segs.append((start, last))
                start = None
    if start is not None:
        segs.append((start, last))
    return segs


def main():
    path = sys.argv[1]
    y0 = int(sys.argv[2]) if len(sys.argv) > 2 else 440
    y1 = int(sys.argv[3]) if len(sys.argv) > 3 else 595
    w, h, ch, buf = decode_png(path)
    print("png %dx%d channels=%d" % (w, h, ch))
    segs = segments(w, ch, buf, y0, y1)
    print("y[%d,%d) segments (start,end,width):" % (y0, y1))
    for s, e in segs:
        print("   (%d, %d)  w=%d" % (s, e, e - s + 1))


if __name__ == "__main__":
    main()
