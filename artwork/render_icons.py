#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Single-source icon renderer for the "宏" (Hong) competition app icon.

The whole glyph is defined as ONE Python data structure: a list of ``Stroke``
objects, where every stroke is a list of vertices plus a uniform width, a
``cap`` and a ``join``.  That *same* structure drives BOTH outputs:

    (a) the SVG text  (``to_svg``), and
    (b) the Pillow PNG render (``render_png``, 4x supersampled + LANCZOS).

Because ``cap``/``join`` live in the data, the SVG ``stroke-linecap/linejoin``
and the PNG end-cap drawing rules are guaranteed to agree -- there is only one
copy of the truth.

Geometry follows the *measured* simhei skeleton supplied by the design lead
(see the module constants below); it is NOT hand-waved.  Correct proportions:
  一  is the WIDEST stroke of the whole glyph (ink x[227,824], 597px);
  宀  roof is NARROWER than 一 (ink x[255,801], 546px) and has two downward
      竖脚 whose ink bottom (y~445) nearly touches the top of 一 (y~469);
  丿  is a 3-point bow whose tail sweeps PAST 一 to the far bottom-left
      (ink left ~217, i.e. left of 一's ink left ~227);
  厶  sits just under the right part of 一 (left leg top ink y~545 vs 一
      bottom ink y~535).

No cairosvg / cairocffi dependency - Pillow only.

Usage
-----
    python render_icons.py                       # A/B/C SVG + 1024 PNG + D + sheet
    python render_icons.py --style A --size 512  # any single style at any size
    python render_icons.py --android <res_root>  # also export Android res assets
"""

from __future__ import annotations

import argparse
import math
import os
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# --------------------------------------------------------------------------- #
# Constants
# --------------------------------------------------------------------------- #
CANVAS = 1024                       # master design canvas is 1024 x 1024
BG = (0, 166, 81)                   # #00A651 - pure flat background
FG = (255, 255, 255)                # #FFFFFF - glyph colour

# Adaptive-icon (API 26+) geometry.
VD_VIEWPORT = 108.0                 # VectorDrawable viewport is 108 x 108
VD_SAFE_ZONE = 66.0                 # glyph must fit the centre 66 x 66 box
VD_GLYPH_SCALE = 0.61               # requested ~61% relative scale

Point = Tuple[float, float]

# --------------------------------------------------------------------------- #
# Measured skeleton (centre-line coordinates, 1024 x 1024 canvas)
# --------------------------------------------------------------------------- #
# Dot (丶): centre per the lead's FINAL skeleton; diameter is fixed at 66 for
# ALL versions (only coordinates zoom, never the width).
DOT: Point = (516.0, 200.0)
DOT_DIAMETER = 66

HENG: Tuple[Point, Point] = ((260, 502), (791, 502))       # 一 : WIDEST stroke
PIE: Sequence[Point] = ((486, 356), (462, 505), (232, 802))  # 丿 : 3-point bow
SI_LEFT: Tuple[Point, Point] = ((534, 578), (452, 762))    # 厶 左腿
SI_RIGHT: Tuple[Point, Point] = ((695, 652), (748, 762))   # 厶 右腿
SI_BASE: Tuple[Point, Point] = ((405, 800), (760, 800))    # 厶 底

# 宀 roofs.  Each is one polyline that INCLUDES the two downward 竖脚
# (down to y=412).  Roof horizontal span is x[288,768] -> NARROWER than 一.
ROOF_ARCH: Sequence[Point] = ((288, 412), (288, 327), (512, 272),
                              (768, 327), (768, 412))       # 微拱 (arch + feet)
ROOF_FLAT: Sequence[Point] = ((288, 412), (288, 327),
                              (768, 327), (768, 412))       # 平直 (flat + feet)
ROOF_FLAT_NOFEET: Sequence[Point] = ((288, 327), (768, 327))  # 平直, 无竖脚 (C)

STROKE_WIDTH_A = 66                 # 主推·象形
STROKE_WIDTH_B = 62                 # 篆书·方刻
STROKE_WIDTH_C = 78                 # 现代·极简

# Master zoom: the glyph is scaled 1.15x about the canvas centre so it sits
# fuller in the frame.  ONLY coordinates scale - stroke widths do NOT.
CENTER: Point = (512.0, 512.0)
MASTER_SCALE = 1.15


# --------------------------------------------------------------------------- #
# Geometry primitives
# --------------------------------------------------------------------------- #
class Stroke:
    """A single uniform-width poly-line (the atom of the glyph)."""

    __slots__ = ("points", "width", "cap", "join")

    def __init__(
        self,
        points: Sequence[Point],
        width: float,
        cap: str = "round",
        join: str = "round",
    ) -> None:
        self.points: List[Point] = [(float(x), float(y)) for x, y in points]
        self.width: float = float(width)
        self.cap: str = cap                 # "round" | "square" | "butt"
        self.join: str = join               # "round" | "miter" | "bevel"

    @property
    def is_dot(self) -> bool:
        return len(self.points) == 1


class Style:
    """A named glyph: a palette + a list of strokes."""

    def __init__(self, name: str, strokes: Sequence[Stroke],
                 bg: Tuple[int, int, int] = BG,
                 fg: Tuple[int, int, int] = FG) -> None:
        self.name = name
        self.strokes: List[Stroke] = list(strokes)
        self.bg = bg
        self.fg = fg


def _scale(pts: Sequence[Point], s: float) -> List[Point]:
    """Scale a point list about the canvas centre (s == 1.0 -> unchanged)."""
    if s == 1.0:
        return [(float(x), float(y)) for x, y in pts]
    cx, cy = CENTER
    return [(cx + (x - cx) * s, cy + (y - cy) * s) for x, y in pts]


def _skeleton(roof: Sequence[Point], width: float, cap: str, join: str,
              scale: float = 1.0) -> List[Stroke]:
    """Build the shared 宏 skeleton (optionally zoomed about the centre).

    The 丶 dot is ALWAYS a round dot of DOT_DIAMETER (66), independent of the
    version stroke width, exactly as the lead specified.
    """
    return [
        Stroke(_scale([DOT], scale), DOT_DIAMETER, "round", "round"),  # 丶
        Stroke(_scale([HENG[0], HENG[1]], scale), width, cap, join),   # 一
        Stroke(_scale(PIE, scale), width, cap, join),                  # 丿
        Stroke(_scale([SI_LEFT[0], SI_LEFT[1]], scale), width, cap, join),
        Stroke(_scale([SI_RIGHT[0], SI_RIGHT[1]], scale), width, cap, join),
        Stroke(_scale([SI_BASE[0], SI_BASE[1]], scale), width, cap, join),
        Stroke(_scale(roof, scale), width, cap, join),                 # 宀 屋顶
    ]


# --------------------------------------------------------------------------- #
# Style definitions  (four genuinely different treatments)
# --------------------------------------------------------------------------- #
# A = 主推·象形  : 微拱屋顶 + 竖脚, round caps, width 66  -> the shipped design
# B = 篆书·方刻  : 平直屋顶 + 竖脚, square/miter, 62
# C = 现代·极简  : 平直屋顶 无竖脚, round caps,   78
STYLE_A = Style("A", _skeleton(ROOF_ARCH, STROKE_WIDTH_A, "round", "round", MASTER_SCALE))
STYLE_B = Style("B", _skeleton(ROOF_FLAT, STROKE_WIDTH_B, "square", "miter", MASTER_SCALE))
STYLE_C = Style("C", _skeleton(ROOF_FLAT_NOFEET, STROKE_WIDTH_C, "round", "round", MASTER_SCALE))

STYLES: Dict[str, Style] = {"A": STYLE_A, "B": STYLE_B, "C": STYLE_C}

# Adaptive-icon foreground is DECOUPLED from the 1.15 master zoom: it uses the
# raw (un-zoomed) skeleton with the independent 108-viewport safe-zone mapping.
STYLE_A_FG = Style("A_fg", _skeleton(ROOF_ARCH, STROKE_WIDTH_A, "round", "round", 1.0))


# --------------------------------------------------------------------------- #
# SVG emission
# --------------------------------------------------------------------------- #
def _hexc(rgb: Tuple[int, int, int]) -> str:
    return "#%02X%02X%02X" % (int(rgb[0]), int(rgb[1]), int(rgb[2]))


def to_svg(style: Style, canvas: int = CANVAS, precision: int = 2) -> str:
    """Serialise a :class:`Style` to a standalone SVG string."""
    out: List[str] = []
    out.append(
        '<svg xmlns="http://www.w3.org/2000/svg" '
        f'width="{canvas}" height="{canvas}" '
        f'viewBox="0 0 {canvas} {canvas}">'
    )
    out.append(f'  <rect width="{canvas}" height="{canvas}" '
               f'fill="{_hexc(style.bg)}"/>')
    for st in style.strokes:
        if st.is_dot:
            x, y = st.points[0]
            out.append(f'  <circle cx="{x:.{precision}f}" cy="{y:.{precision}f}" '
                       f'r="{st.width / 2:.{precision}f}" fill="{_hexc(style.fg)}"/>')
            continue
        pts = " ".join(
            f"{x:.{precision}f},{y:.{precision}f}" for x, y in st.points
        )
        out.append(
            f'  <polyline points="{pts}" fill="none" '
            f'stroke="{_hexc(style.fg)}" stroke-width="{st.width:.{precision}f}" '
            f'stroke-linecap="{st.cap}" stroke-linejoin="{st.join}"/>'
        )
    out.append("</svg>")
    return "\n".join(out) + "\n"


# --------------------------------------------------------------------------- #
# PNG rendering (4x supersample -> LANCZOS downscale)
# --------------------------------------------------------------------------- #
def _extend_square(pts: List[Point], w: float) -> List[Point]:
    """Extend the end points of a poly-line so a square cap is produced."""
    if len(pts) < 2:
        return pts
    half = w / 2.0
    x0, y0 = pts[0]
    x1, y1 = pts[1]
    d = math.hypot(x1 - x0, y1 - y0) or 1.0
    first = (x0 + (x0 - x1) / d * half, y0 + (y0 - y1) / d * half)
    xn, yn = pts[-1]
    xp, yp = pts[-2]
    d2 = math.hypot(xn - xp, yn - yp) or 1.0
    last = (xn + (xn - xp) / d2 * half, yn + (yn - yp) / d2 * half)
    return [first] + pts[1:-1] + [last]


def _fill_dot(draw: ImageDraw.ImageDraw, p: Point, w: float,
              color: Tuple[int, int, int], square: bool = False) -> None:
    r = w / 2.0
    if square:
        draw.rectangle([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=color)
    else:
        draw.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=color)


def _draw_stroke(draw: ImageDraw.ImageDraw, pts: List[Point], w: float,
                 color: Tuple[int, int, int], cap: str, join: str) -> None:
    """Draw one poly-line honouring ``cap`` / ``join`` exactly like SVG does."""
    iw = max(1, int(round(w)))

    if len(pts) == 1:
        _fill_dot(draw, pts[0], w, color, square=(cap == "square"))
        return

    if cap == "round" or join == "round":
        # Round end-caps and/or round joins.
        line_pts = _extend_square(pts, w) if cap == "square" else pts
        draw.line(line_pts, fill=color, width=iw, joint="curve")
        dots = line_pts if join == "round" else [line_pts[0], line_pts[-1]]
        for p in dots:
            _fill_dot(draw, p, w, color)
    else:
        # Square cap + miter join: draw each segment as a square-ended bar;
        # the overlaps form square (miter-like) corners at interior vertices.
        for a, b in zip(pts, pts[1:]):
            seg = _extend_square([a, b], w)
            draw.line(seg, fill=color, width=iw, joint="curve")


def render_png(style: Style, size: int, supersample: int = 4) -> Image.Image:
    """Render a :class:`Style` to an RGB :class:`PIL.Image.Image`.

    The glyph is first drawn on a ``size * supersample`` canvas (4096 for a
    1024 target) and then downscaled with :data:`PIL.Image.LANCZOS`, which
    yields clean anti-aliased edges while keeping the background plane a
    single pure ``#00A651`` colour value.
    """
    hi = size * supersample
    scale = hi / float(CANVAS)
    img = Image.new("RGB", (hi, hi), style.bg)
    draw = ImageDraw.Draw(img)

    for st in style.strokes:
        pts = [(x * scale, y * scale) for x, y in st.points]
        _draw_stroke(draw, pts, st.width * scale, style.fg, st.cap, st.join)

    return img.resize((size, size), Image.LANCZOS)


def to_round_rgba(img: Image.Image) -> Image.Image:
    """Return an RGBA copy that is fully transparent OUTSIDE the inscribed circle.

    Used for legacy (API 24/25) ``ic_launcher_round``: a clean circular icon
    with the corners knocked out, instead of an opaque square.
    """
    size = img.width
    yy, xx = np.mgrid[0:size, 0:size]
    c = (size - 1) / 2.0
    r = size / 2.0
    inside = ((xx - c) ** 2 + (yy - c) ** 2) <= r * r
    rgba = np.dstack([np.array(img.convert("RGB")),
                      np.where(inside, 255, 0).astype(np.uint8)])
    return Image.fromarray(rgba, "RGBA")


# --------------------------------------------------------------------------- #
# Reference "true character" image (simhei)
# --------------------------------------------------------------------------- #
_FONT_CANDIDATES = (
    "C:/Windows/Fonts/simhei.ttf",
    "C:/Windows/Fonts/simhei.ttc",
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simsun.ttc",
    "C:/Windows/Fonts/simkai.ttf",
)


def find_cjk_font() -> str:
    for cand in _FONT_CANDIDATES:
        if os.path.exists(cand):
            return cand
    raise FileNotFoundError("no CJK font found in " + ", ".join(_FONT_CANDIDATES))


# The measured simhei frame for 宏 (used to place the reference identically).
REF_FRAME = (118.0, 100.0, 872.0, 920.0)    # x0, y0, x1, y1


def render_reference_png(text: str = "宏", size: int = CANVAS,
                         font_path: Optional[str] = None,
                         frame: Tuple[float, float, float, float] = REF_FRAME
                         ) -> Image.Image:
    """Render the true character with simhei, white on green, fitted to frame."""
    font_path = font_path or find_cjk_font()
    img = Image.new("RGB", (size, size), BG)

    # Draw large + tight-crop so the glyph fills the requested frame.
    big = 2048
    font = ImageFont.truetype(font_path, int(big * 0.82))
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).text((big // 2, big // 2), text, fill=255,
                              font=font, anchor="mm")
    box = mask.getbbox()
    glyph = mask.crop(box)

    x0, y0, x1, y1 = frame
    fw, fh = x1 - x0, y1 - y0
    gw, gh = glyph.size
    s = min(fw / gw, fh / gh)
    glyph = glyph.resize((max(1, int(round(gw * s))), max(1, int(round(gh * s)))),
                         Image.LANCZOS)

    white = Image.new("RGB", glyph.size, FG)
    img.paste(white, (int(round((x0 + x1) / 2 - glyph.width / 2)),
                      int(round((y0 + y1) / 2 - glyph.height / 2))), glyph)
    return img


# --------------------------------------------------------------------------- #
# Contact sheet (A/B/C/D side by side, 1024 + 48x8 zoom)
# --------------------------------------------------------------------------- #
def build_sheet(items: Sequence[Tuple[str, Image.Image]],
                out_path: str) -> str:
    """items: list of (label, 1024-image).  Writes a 4-column comparison sheet."""
    pad, gap, disp = 24, 16, 384
    n = len(items)
    name_h, lab_h = 46, 28
    width = pad * 2 + n * disp + (n - 1) * gap
    height = pad * 2 + name_h + 2 * (lab_h + disp)

    sheet = Image.new("RGB", (width, height), (245, 245, 245))
    d = ImageDraw.Draw(sheet)

    def font(sz: int) -> ImageFont.FreeTypeFont:
        try:
            return ImageFont.truetype(find_cjk_font(), sz)
        except Exception:                       # pragma: no cover - fallback
            return ImageFont.load_default()

    f_name, f_lab = font(28), font(20)

    for i, (label, big_img) in enumerate(items):
        x = pad + i * (disp + gap)
        d.text((x + disp / 2, pad + name_h / 2), label,
               fill=(20, 20, 20), font=f_name, anchor="mm")

        y1 = pad + name_h
        d.text((x + disp / 2, y1 + lab_h / 2), "1024 (缩略)",
               fill=(90, 90, 90), font=f_lab, anchor="mm")
        sheet.paste(big_img.resize((disp, disp), Image.LANCZOS), (x, y1 + lab_h))

        y2 = y1 + lab_h + disp
        d.text((x + disp / 2, y2 + lab_h / 2), "48 x 8 (判可辨识度)",
               fill=(90, 90, 90), font=f_lab, anchor="mm")
        small = big_img.resize((48, 48), Image.LANCZOS).resize(
            (disp, disp), Image.NEAREST)
        sheet.paste(small, (x, y2 + lab_h))

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    sheet.save(out_path)
    return out_path


# --------------------------------------------------------------------------- #
# Android asset export
# --------------------------------------------------------------------------- #
def _glyph_bbox(strokes: Sequence[Stroke]) -> Tuple[float, float, float, float]:
    """Painted bounding box (centre-line bbox expanded by half the width)."""
    minx = miny = math.inf
    maxx = maxy = -math.inf
    for st in strokes:
        h = st.width / 2.0
        for x, y in st.points:
            minx, miny = min(minx, x - h), min(miny, y - h)
            maxx, maxy = max(maxx, x + h), max(maxy, y + h)
    return minx, miny, maxx, maxy


# The adaptive-icon safe zone is a CIRCLE of diameter 66 (radius 33) centred at
# (54,54).  We size the glyph so the circle circumscribing its ink bbox has
# radius VD_TARGET_CIRC_R (<= 33 hard, target 30 -> ~3 units of margin).
VD_SAFE_DIAMETER = 66.0
VD_TARGET_CIRC_R = 30.0


def glyph_circumradius(strokes: Sequence[Stroke]) -> float:
    """Radius of the circle circumscribing the glyph's ink bbox (1024 space)."""
    minx, miny, maxx, maxy = _glyph_bbox(strokes)
    return math.hypot((maxx - minx) / 2.0, (maxy - miny) / 2.0)


def _vd_transform(strokes: Sequence[Stroke]) -> Tuple[float, float, float, float]:
    """Return (scale, offset_x, offset_y, stroke_width) mapping 1024 -> 108.

    The glyph is sized so the circle circumscribing its ink bbox has radius
    VD_TARGET_CIRC_R (30) in the viewport, then centred at (54, 54) - i.e. the
    whole ink (corners included) sits INSIDE the diameter-66 safe circle.
    """
    minx, miny, maxx, maxy = _glyph_bbox(strokes)
    scale = VD_TARGET_CIRC_R / glyph_circumradius(strokes)
    cx = (minx + maxx) / 2.0
    cy = (miny + maxy) / 2.0
    off = VD_VIEWPORT / 2.0
    ox = off - cx * scale
    oy = off - cy * scale
    stroke_width = strokes[0].width * scale   # 1024-space width -> VD units
    return scale, ox, oy, stroke_width


def foreground_bbox_in_vd(style: Style) -> Tuple[float, float, float, float]:
    """Verify helper: glyph bbox expressed in the 108 x 108 VD coordinate system."""
    scale, ox, oy, _ = _vd_transform(style.strokes)
    minx, miny, maxx, maxy = _glyph_bbox(style.strokes)
    return (minx * scale + ox, miny * scale + oy,
            maxx * scale + ox, maxy * scale + oy)


def foreground_vector_xml(style: Style) -> str:
    """Build the adaptive-icon foreground VectorDrawable (viewport 108x108)."""
    scale, ox, oy, sw = _vd_transform(style.strokes)
    subpaths: List[str] = []
    for st in style.strokes:
        pts = [((x * scale + ox), (y * scale + oy)) for x, y in st.points]
        if st.is_dot:
            # Represent the dot as a tiny round-capped segment -> a circle.
            pts = [pts[0], (pts[0][0] + 0.001, pts[0][1])]
        seg = "M%.3f,%.3f" % pts[0]
        for x, y in pts[1:]:
            seg += " L%.3f,%.3f" % (x, y)
        subpaths.append(seg)
    path_data = " ".join(subpaths)
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        '    <path\n'
        f'        android:pathData="{path_data}"\n'
        '        android:strokeColor="#FFFFFF"\n'
        f'        android:strokeWidth="{sw:.4f}"\n'
        '        android:strokeLineCap="round"\n'
        '        android:strokeLineJoin="round"\n'
        '        android:fillColor="#00000000" />\n'
        '</vector>\n'
    )


_BACKGROUND_XML = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<resources>\n'
    '    <color name="ic_launcher_background">#00A651</color>\n'
    '</resources>\n'
)

_ADAPTIVE_XML = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/ic_launcher_background" />\n'
    '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
    '</adaptive-icon>\n'
)

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def _write(path: str, text: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def export_android(res_root: str, style: Style = STYLE_A) -> List[str]:
    """Write every launcher asset into an Android ``res/`` tree.

    Returns the list of files written.  Only ``style`` (the 象形 A master) is
    wired into the launcher, exactly as specified.
    """
    written: List[str] = []

    # 1/2 - legacy mipmaps: square (opaque) + round (RGBA, corners transparent).
    for dens, px in DENSITIES.items():
        square = render_png(style, px)
        roundd = to_round_rgba(square)
        for fname, im in (("ic_launcher.png", square),
                          ("ic_launcher_round.png", roundd)):
            path = os.path.join(res_root, f"mipmap-{dens}", fname)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            im.save(path)
            written.append(path)

    # 3 - adaptive icon resources.
    path = os.path.join(res_root, "values", "ic_launcher_background.xml")
    _write(path, _BACKGROUND_XML)
    written.append(path)

    path = os.path.join(res_root, "drawable", "ic_launcher_foreground.xml")
    _write(path, foreground_vector_xml(STYLE_A_FG))
    written.append(path)

    for fname in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = os.path.join(res_root, "mipmap-anydpi-v26", fname)
        _write(path, _ADAPTIVE_XML)
        written.append(path)

    return written


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="宏 app icon single-source renderer")
    parser.add_argument("--style", choices=sorted(STYLES), default=None,
                        help="render only this style to --out")
    parser.add_argument("--size", type=int, default=None,
                        help="pixel size for --style output")
    parser.add_argument("--out", default=None, help="output PNG path for --style")
    parser.add_argument("--android", default=None, metavar="RES_ROOT",
                        help="also export Android res assets into this res/ root")
    args = parser.parse_args(argv)

    here = os.path.dirname(os.path.abspath(__file__))
    out_dir = os.path.join(here, "out")
    os.makedirs(out_dir, exist_ok=True)

    # (Re)generate the three vector masters, the 1024 PNGs and the reference D.
    for key, style in STYLES.items():
        svg_path = os.path.join(here, f"app_icon_{key}.svg")
        _write(svg_path, to_svg(style))
        print(f"wrote {svg_path}")

        png_path = os.path.join(out_dir, f"app_icon_{key}_1024.png")
        render_png(style, CANVAS).save(png_path)
        print(f"wrote {png_path}")

    d_path = os.path.join(out_dir, "app_icon_D_1024.png")
    render_reference_png("宏", CANVAS).save(d_path)
    print(f"wrote {d_path}")

    # Contact sheet: A/B/C/D, each 1024 + 48x8.
    sheet_items = [(f"{k}  ({desc})", render_png(STYLES[k], CANVAS))
                   for k, desc in (("A", "象形·主推"), ("B", "篆书·方刻"),
                                   ("C", "现代·极简"))]
    sheet_items.append(("D  (真字参照)", render_reference_png("宏", CANVAS)))
    sheet_path = build_sheet(sheet_items, os.path.join(out_dir, "_sheet.png"))
    print(f"wrote {sheet_path}")

    # Optional single-style arbitrary-size output.
    if args.style and args.size:
        style = STYLES[args.style]
        dst = args.out or os.path.join(out_dir, f"app_icon_{args.style}_{args.size}.png")
        render_png(style, args.size).save(dst)
        print(f"wrote {dst}")

    # Optional Android export.
    if args.android:
        files = export_android(args.android, STYLE_A)
        for f in files:
            print(f"android -> {f}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
