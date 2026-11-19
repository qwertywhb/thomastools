"""探索性测量：卡片白底带 / 隔行带 / 导航胶囊位置。仅打印，不做断言。"""
import io
import os

from PIL import Image, ImageCms

SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "verify_shots")


def load_srgb(p):
    im = Image.open(p)
    icc = im.info.get("icc_profile")
    rgb = im.convert("RGB")
    if icc:
        src = ImageCms.ImageCmsProfile(io.BytesIO(icc))
        dst = ImageCms.createProfile("sRGB")
        rgb = ImageCms.profileToProfile(rgb, src, dst, outputMode="RGB")
    return rgb


def bands(im, x0, x1, pred, min_len=4):
    out = []
    start = None
    for y in range(im.height):
        ok = pred(im, y, x0, x1)
        if ok and start is None:
            start = y
        elif not ok and start is not None:
            if y - start >= min_len:
                out.append((start, y - 1, y - start))
            start = None
    if start is not None:
        out.append((start, im.height - 1, im.height - start))
    return out


def count_white(im, y, x0, x1):
    px = im.load()
    n = 0
    for x in range(x0, x1, 4):
        r, g, b = px[x, y]
        if r >= 250 and g >= 250 and b >= 250:
            n += 1
    return n


for name in ["ui_unify_02b_sameodds.png", "ui2_01_sameodds.png"]:
    im = load_srgb(os.path.join(SHOTS, name))
    print("==", name, im.size)
    white = bands(im, 60, 1200, lambda im, y, a, b: count_white(im, y, a, b) > 200)
    for s, e, h in white:
        print("   white band y=%d..%d h=%d" % (s, e, h))
    print("   bg@20,1400 =", im.getpixel((20, 1400)))
