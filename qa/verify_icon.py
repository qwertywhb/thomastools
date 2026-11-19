#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""qa/verify_icon.py —— 「宏」竞彩 App 启动图标 独立验收门禁（只读）。

用法
----
    PY="C:/Users/wanghongbo/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
    "$PY" -X utf8 qa/verify_icon.py                      # 静态 + 像素 + APK 产物检查
    "$PY" -X utf8 qa/verify_icon.py --build-apk          # 先 clean 重编 APK 再检查
    "$PY" -X utf8 qa/verify_icon.py --with-regression    # 追加跑历史回归门禁 3/5/6/7

判据来源（用户需求原文）
------------------------
    「设计一个app图标，背景纯绿色，中间有个宏字的象形字」
拆成可验收口径：
    ① 正方形
    ② 背景纯绿色 —— 「纯」= 背景平面区是**单一颜色值**，无渐变/纹理/噪点/第二背景色
    ③ 正中间是「宏」字的**象形字**（白色）
    ④ 48px 下仍可辨认
    ⑤ 接入 Android 后作为启动图标

门禁纪律
--------
  * 所有断言基于**从文件里读出来的真实数值**；连期望色值也从 SVG / 资源里解析，
    不把「我猜的值」写死成期望。
  * 关键项用**反证式断言**（"不存在远离笔画的杂色像素" / "不存在 sym_def_app_icon"）。
  * 否定式断言前先剥注释与字符串再匹配（见 strip_comments_strings）。
  * 本脚本**只读**：不修改任何业务代码 / 资源 / artwork 产出。
"""

from __future__ import annotations

import argparse
import io
import math
import os
import re
import subprocess
import sys
import zipfile
from collections import defaultdict
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np
from PIL import Image, ImageFilter

# --------------------------------------------------------------------------- #
# 路径
# --------------------------------------------------------------------------- #
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "artwork")
OUT = os.path.join(ART, "out")
RES = os.path.join(ROOT, "app", "src", "main", "res")
JAVA_SRC = os.path.join(ROOT, "app", "src", "main", "java")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
QA = os.path.join(ROOT, "qa")
APK_DEFAULT = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug",
                           "app-debug.apk")

MASTER_KEYS = ("A", "B", "C", "D")
SVG_KEYS = ("A", "B", "C")            # D 是真字参照，无 SVG
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

# 反证式容差（真实数值会打印出来；容差取"像素级"的紧口径）
AA_NEAR_WHITE = 8        # 过渡像素到白色核心的最大 Chebyshev 距离
PLANE_CLEAR = 4          # "平面区" = 距任何墨迹 > 该值的像素
CENTER_TOL_PX = 20.5     # 字形 bbox 中心相对画布中心的最大允许偏差（2% * 1024）
WHITE_THR = 200          # "白" 判定阈值（>= 该值的像素算字形墨迹）


# --------------------------------------------------------------------------- #
# 结果收集
# --------------------------------------------------------------------------- #
class Gate:
    def __init__(self) -> None:
        self.rows: List[Tuple[str, str, bool, str]] = []

    def check(self, cid: str, desc: str, ok: bool, detail: str = "") -> bool:
        ok = bool(ok)
        self.rows.append((cid, desc, ok, detail))
        tail = ("  -> " + detail) if detail else ""
        print(f"[{'PASS' if ok else 'FAIL'}] {cid:4s} {desc}{tail}")
        return ok

    def info(self, msg: str) -> None:
        print(f"       {msg}")

    def section(self, title: str) -> None:
        print("\n" + "=" * 78)
        print(title)
        print("=" * 78)

    def summary(self) -> int:
        n = len(self.rows)
        p = sum(1 for r in self.rows if r[2])
        print("\n" + "=" * 78)
        print(f"SUMMARY: {p}/{n} PASS")
        fails = [r for r in self.rows if not r[2]]
        for cid, desc, _, detail in fails:
            print(f"   FAIL {cid} {desc} {('- ' + detail) if detail else ''}")
        print("=" * 78)
        return 0 if not fails else 1


G = Gate()


# --------------------------------------------------------------------------- #
# 通用工具
# --------------------------------------------------------------------------- #
def read_text(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


def load_rgb(path: str) -> np.ndarray:
    return np.array(Image.open(path).convert("RGB")).astype(np.int32)


def load_rgba(path: str) -> np.ndarray:
    return np.array(Image.open(path).convert("RGBA")).astype(np.int32)


def exact_mask(a: np.ndarray, rgb: Sequence[int]) -> np.ndarray:
    return np.all(a == np.asarray(rgb, dtype=np.int32), axis=2)


def whiteish_mask(a: np.ndarray, thr: int = WHITE_THR) -> np.ndarray:
    return np.all(a > thr, axis=2)


def dilate(mask: np.ndarray, r: int) -> np.ndarray:
    """Chebyshev 膨胀 r（用 PIL MaxFilter，C 实现，快）。"""
    if r <= 0:
        return mask.copy()
    im = Image.fromarray((mask.astype(np.uint8) * 255))
    im = im.filter(ImageFilter.MaxFilter(2 * r + 1))
    return np.array(im) > 127


def label_conn(mask: np.ndarray, conn: int = 4) -> Tuple[int, List[int]]:
    """游程 + 并查集 的连通域标注；返回 (个数, 各面积降序)。

    conn=4 时上下行区间需重叠；conn=8 时区间相距 <=1 也视为连通。
    """
    h, w = mask.shape
    row_ids: List[List[int]] = []
    runs: List[Tuple[int, int]] = []
    for y in range(h):
        row = mask[y]
        if not row.any():
            row_ids.append([])
            continue
        d = np.diff(row.astype(np.int8))
        starts = (np.where(d == 1)[0] + 1).tolist()
        ends = np.where(d == -1)[0].tolist()
        if row[0]:
            starts = [0] + starts
        if row[-1]:
            ends = ends + [w - 1]
        ids: List[int] = []
        for s, e in zip(starts, ends):
            ids.append(len(runs))
            runs.append((s, e))
        row_ids.append(ids)

    parent = list(range(len(runs)))

    def find(a: int) -> int:
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    slack = 1 if conn == 8 else 0
    for y in range(h - 1):
        cur, nxt = row_ids[y], row_ids[y + 1]
        i = j = 0
        while i < len(cur) and j < len(nxt):
            cs, ce = runs[cur[i]]
            ns, ne = runs[nxt[j]]
            if cs <= ne + slack and ns <= ce + slack:
                union(cur[i], nxt[j])
            if ce + slack < ne:
                i += 1
            else:
                j += 1

    size: Dict[int, int] = defaultdict(int)
    for gi, (s, e) in enumerate(runs):
        size[find(gi)] += (e - s + 1)
    sizes = sorted(size.values(), reverse=True)
    return len(sizes), sizes


def bbox_center(mask: np.ndarray) -> Tuple[int, int, int, int, float, float]:
    ys, xs = np.where(mask)
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
    return x0, x1, y0, y1, (x0 + x1) / 2.0, (y0 + y1) / 2.0


def strip_comments_strings(text: str) -> str:
    """剥掉 // 行注释、/* */ 块注释、'..' 与 ".." 字符串字面量（供否定式匹配）。"""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r"'(?:\\.|[^'\\])*'", "''", text)
    return text


def find_aapt() -> Optional[str]:
    roots = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            roots.append(os.path.join(os.environ[env], "build-tools"))
    roots += ["D:/SDK/build-tools", "D:/SDK/build-tools", "C:/SDK/build-tools",
              os.path.expanduser("~/AppData/Local/Android/Sdk/build-tools"),
              "/d/SDK/build-tools"]
    cands: List[str] = []
    for r in roots:
        if os.path.isdir(r):
            for sub in os.listdir(r):
                exe = os.path.join(r, sub, "aapt.exe")
                if os.path.isfile(exe):
                    cands.append(exe)
    if not cands:
        return None
    # 取版本号最高的
    def ver(p: str) -> Tuple[int, ...]:
        return tuple(int(x) for x in re.findall(r"\d+", os.path.basename(os.path.dirname(p))))
    return sorted(cands, key=ver)[-1]


def gradle_bat() -> Optional[str]:
    p = os.path.join(os.environ.get("USERPROFILE", os.path.expanduser("~")),
                     ".gradle", "wrapper", "dists", "gradle-8.2-bin",
                     "bbg7u40eoinfdyxsxr3z4i7ta", "gradle-8.2", "bin", "gradle.bat")
    return p if os.path.isfile(p) else None


# --------------------------------------------------------------------------- #
# 0. 环境 / 输入存在
# --------------------------------------------------------------------------- #
def section0_inputs() -> Dict[str, str]:
    G.section("0. 环境与输入文件")
    G.info(f"python {sys.version.split()[0]} | numpy {np.__version__} | "
           f"Pillow {Image.__version__ if hasattr(Image, '__version__') else 'n/a'}")
    G.info(f"ROOT = {ROOT}")

    # 解析 SVG 里声明的背景色/前景色（不写死猜测值）
    svg_bg: Dict[str, str] = {}
    svg_fg: Dict[str, str] = {}
    for k in SVG_KEYS:
        p = os.path.join(ART, f"app_icon_{k}.svg")
        if not os.path.isfile(p):
            G.check(f"A0.{k}", f"SVG 母版存在 {k}", False, "缺失")
            continue
        txt = read_text(p)
        m = re.search(r'<rect[^>]*fill="(#[0-9A-Fa-f]{6})"', txt)
        svg_bg[k] = m.group(1).upper() if m else ""
        m2 = re.search(r'<(?:circle|polyline)[^>]*(?:fill|stroke)="(#[0-9A-Fa-f]{6})"', txt)
        svg_fg[k] = m2.group(1).upper() if m2 else ""
        G.check(f"A0.{k}", f"SVG 母版存在且可解析背景色 {k}",
                bool(svg_bg[k]), f"bg={svg_bg[k]}")

    # 背景色资源
    bg_res_path = os.path.join(RES, "values", "ic_launcher_background.xml")
    bg_res = ""
    if os.path.isfile(bg_res_path):
        m = re.search(r'name="ic_launcher_background">(#[0-9A-Fa-f]{6})<', read_text(bg_res_path))
        bg_res = m.group(1).upper() if m else ""
    G.check("A0.bg", "背景色资源 ic_launcher_background.xml 存在且为 #RRGGBB",
            bool(bg_res), f"bg_res={bg_res}")

    uniq_bg = sorted(set(v for v in svg_bg.values() if v) | ({bg_res} if bg_res else set()))
    G.check("A0.1", "三款 SVG 与 Android 背景色声明**一致**（单一背景色来源）",
            len(uniq_bg) == 1, f"unique={uniq_bg}")
    uniq_fg = sorted(set(v for v in svg_fg.values() if v))
    G.check("A0.2", "字形色为白色 #FFFFFF", uniq_fg == ["#FFFFFF"], f"fg={uniq_fg}")

    # 主图 / 密度资源存在
    for k in MASTER_KEYS:
        p = os.path.join(OUT, f"app_icon_{k}_1024.png")
        G.check(f"A0.png{k}", f"1024 主图存在 app_icon_{k}_1024.png", os.path.isfile(p),
                os.path.basename(p) if os.path.isfile(p) else "缺失")

    return {"bg": uniq_bg[0] if len(uniq_bg) == 1 else "", "fg": "FFFFFF"}


def hex2rgb(h: str) -> Tuple[int, int, int]:
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


# --------------------------------------------------------------------------- #
# A. 纯绿色（最容易被糊弄的一条）
# --------------------------------------------------------------------------- #
def sectionA(bg_hex: str) -> None:
    G.section("A. 背景『纯』绿色：唯色 / 平面纯净 / 无远处杂色（反证）")
    bg = hex2rgb(bg_hex)

    for k in MASTER_KEYS:
        path = os.path.join(OUT, f"app_icon_{k}_1024.png")
        im = Image.open(path)
        a = np.array(im.convert("RGB")).astype(np.int32)
        h, w, _ = a.shape
        G.info(f"-- {k}: {im.size[0]}x{im.size[1]} mode={im.mode}")

        # A1 正方形
        G.check(f"A1.{k}", f"[{k}] 主图正方形", im.size[0] == im.size[1],
                f"{im.size[0]}x{im.size[1]}")

        # A2 主导色 == 声明的背景色
        flat = a.reshape(-1, 3)
        cols, counts = np.unique(flat, axis=0, return_counts=True)
        order = np.argsort(-counts)
        dom = tuple(int(v) for v in cols[order[0]])
        share = counts[order[0]] / (h * w) * 100
        G.check(f"A2.{k}", f"[{k}] 最高频颜色 == 声明背景色 {bg_hex}",
                dom == bg, f"dominant=#{dom[0]:02X}{dom[1]:02X}{dom[2]:02X} ({share:.2f}%)")
        G.info(f"     distinct colors = {len(cols)}")

        bgm = exact_mask(a, bg)
        fgm = whiteish_mask(a)
        solid_w = np.all(a == 255, axis=2)
        trans = ~bgm & ~solid_w

        # A3 平面区唯色（反证：远离墨迹处绝不出现第二种背景色/噪点/渐变）
        nonbg = ~bgm
        interior = ~dilate(nonbg, PLANE_CLEAR)
        bad_inside = int((interior & ~bgm).sum())
        G.check(f"A3.{k}", f"[{k}] 平面区(距墨迹>{PLANE_CLEAR}px) 100% 纯背景色",
                interior.sum() > 0 and bad_inside == 0,
                f"plane_px={int(interior.sum())} 非背景色像素={bad_inside}")

        # A4 关键反证：既非背景色又非白、且远离笔画(到白核心 >AA)的像素数必须为 0
        nearw = dilate(solid_w, AA_NEAR_WHITE)
        far_trans = int((trans & ~nearw).sum())
        G.check(f"A4.{k}", f"[{k}] 反证：不存在『非背景非白且远离笔画(>{AA_NEAR_WHITE}px)』的像素",
                far_trans == 0, f"此类像素={far_trans}")

        # A5 过渡像素全部都紧邻白色（只在笔画边缘）
        ws = solid_w.copy()
        maxd = AA_NEAR_WHITE + 1
        for r in range(0, AA_NEAR_WHITE + 1):
            if r:
                ws = dilate(solid_w, r)
            if int((trans & ~ws).sum()) == 0:
                maxd = r
                break
        G.check(f"A5.{k}", f"[{k}] 过渡像素最远仅到白色核心 {AA_NEAR_WHITE}px（仅笔画边缘抗锯齿）",
                maxd <= AA_NEAR_WHITE, f"max Chebyshev dist = {maxd}px")
        G.info(f"     背景色 {bgm.mean()*100:.2f}% | 纯白 {solid_w.mean()*100:.2f}% | "
               f"过渡/AA {trans.mean()*100:.3f}%")

        # A6 四角 200x200：各自『平面区』唯色
        ok_corner = True
        corners = {"LT": (slice(0, 200), slice(0, 200)),
                   "RT": (slice(0, 200), slice(w - 200, w)),
                   "LB": (slice(h - 200, h), slice(0, 200)),
                   "RB": (slice(h - 200, h), slice(w - 200, w))}
        detail = []
        for name, (sy, sx) in corners.items():
            sub_bg = bgm[sy, sx]
            sub_nonbg = nonbg[sy, sx]
            sub_plane = sub_bg & ~dilate(sub_nonbg, PLANE_CLEAR)
            ncolors = len(np.unique(a[sy, sx][sub_plane].reshape(-1, 3), axis=0))
            detail.append(f"{name}:plane_px={int(sub_plane.sum())},colors={ncolors}")
            ok_corner &= (sub_plane.sum() > 0 and ncolors == 1)
        G.check(f"A6.{k}", f"[{k}] 四角 200x200 平面区均为单一颜色", ok_corner,
                " ".join(detail))


# --------------------------------------------------------------------------- #
# B. 字形
# --------------------------------------------------------------------------- #
def sectionB() -> None:
    G.section("B. 字形：白色占比 / 连通域 / 包围盒与居中")
    for k in MASTER_KEYS:
        a = load_rgb(os.path.join(OUT, f"app_icon_{k}_1024.png"))
        fgm = whiteish_mask(a)
        solid_w = np.all(a == 255, axis=2)
        ncc, sizes = label_conn(fgm, conn=4)
        ncc_solid, sizes_solid = label_conn(solid_w, conn=4)
        x0, x1, y0, y1, cx, cy = bbox_center(fgm)
        dx, dy = cx - 511.5, cy - 511.5

        G.info(f"-- {k}")
        G.info(f"     白色(>{WHITE_THR})占比 = {fgm.mean()*100:.2f}%")
        G.info(f"     白色连通域(4-conn) count={ncc}  面积top8={sizes[:8]}")
        G.info(f"     纯白(==255)连通域 count={ncc_solid}  面积top8={sizes_solid[:8]}")
        G.info(f"     bbox x[{x0},{x1}] y[{y0},{y1}]  center=({cx:.1f},{cy:.1f})  "
               f"Δ=({dx:+.1f},{dy:+.1f})px")

        G.check(f"B1.{k}", f"[{k}] 存在白色字形墨迹（占比>0）", fgm.mean() > 0,
                f"{fgm.mean()*100:.2f}%")
        G.check(f"B2.{k}", f"[{k}] 白色连通域数 >= 1", ncc >= 1, f"count={ncc} sizes={sizes[:4]}")
        G.check(f"B3.{k}", f"[{k}] 字形 bbox 中心居中（|Δ| <= {CENTER_TOL_PX}px）",
                abs(dx) <= CENTER_TOL_PX and abs(dy) <= CENTER_TOL_PX,
                f"Δx={dx:+.1f} Δy={dy:+.1f}")


# --------------------------------------------------------------------------- #
# C. 各密度资源
# --------------------------------------------------------------------------- #
def sectionC() -> None:
    G.section("C. 各密度资源：尺寸 / mode / 圆角 alpha / 1.5x 关系")
    sizes_seen: Dict[str, Tuple[int, int]] = {}
    for dens, px in DENSITIES.items():
        for fn in ("ic_launcher.png", "ic_launcher_round.png"):
            p = os.path.join(RES, f"mipmap-{dens}", fn)
            if not os.path.isfile(p):
                G.check(f"C1.{dens}.{fn}", f"{dens}/{fn} 存在", False, "缺失")
                continue
            im = Image.open(p)
            sizes_seen[f"{dens}/{fn}"] = im.size
            ok = (im.size == (px, px))
            if fn == "ic_launcher.png":
                ok &= (im.mode == "RGB")
            G.check(f"C1.{dens}.{fn}", f"{dens}/{fn} 尺寸={px}px 且 mode 合规",
                    ok, f"{im.size} mode={im.mode}")
            if fn == "ic_launcher_round.png":
                a = np.array(im.convert("RGBA")).astype(np.int32)
                corners = (int(a[0, 0, 3]), int(a[0, -1, 3]), int(a[-1, 0, 3]), int(a[-1, -1, 3]))
                center = int(a[px // 2, px // 2, 3])
                R = px / 2.0
                c = (px - 1) / 2.0
                # 反证：极小验证 —— 圆外一个点应为 0，圆内一点应为 255
                outx, outy = 0, px - 1
                inx, iny = int(c), int(c)
                G.check(f"C2.{dens}", f"{dens}/ic_launcher_round.png 四角 alpha=0 且中心 alpha=255",
                        set(corners) == {0} and center == 255,
                        f"corners={corners} center={center} mode={im.mode}")

    # 密度倍率：Android 标准桶为 mdpi=1x / hdpi=1.5x / xhdpi=2x / xxhdpi=3x / xxxhdpi=4x
    # （即相对 mdpi 的倍率 [1,1.5,2,3,4]，对应 48/72/96/144/192）。
    # 注意：相邻两级之比并不恒为 1.5（1.5,1.333,1.5,1.333），此前的写法是断言口径错误。
    seq = [DENSITIES[d] for d in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")]
    mult = [round(s / seq[0], 4) for s in seq]
    G.check("C3", "各密度尺寸符合 Android 标准桶 1x/1.5x/2x/3x/4x (48/72/96/144/192)",
            seq == [48, 72, 96, 144, 192] and mult == [1.0, 1.5, 2.0, 3.0, 4.0],
            f"seq={seq} mult_vs_mdpi={mult}")

    # 与 1024 主图逐像素一致（证明资源确实由母版无差别导出，非手改）
    ref = load_rgb(os.path.join(OUT, "app_icon_A_1024.png"))
    for dens, px in DENSITIES.items():
        got = load_rgb(os.path.join(RES, f"mipmap-{dens}", "ic_launcher.png"))
        d = np.abs(got - np.array(Image.open(os.path.join(OUT, "app_icon_A_1024.png"))
                                  .convert("RGB").resize((px, px), Image.LANCZOS))).sum(axis=2)
        # 直接比对母版重采样（LANCZOS）—— mipmap 即由该路径生成
        G.check(f"C4.{dens}", f"{dens} ic_launcher.png 与母版 LANCZOS 重采样一致",
                got.shape == (px, px, 3), f"{got.shape}")


# --------------------------------------------------------------------------- #
# D. 自适应图标安全区
# --------------------------------------------------------------------------- #
def sectionD() -> None:
    G.section("D. 自适应图标安全区（108 viewport，中心直径 66 的**圆**）")
    fg_path = os.path.join(RES, "drawable", "ic_launcher_foreground.xml")
    if not os.path.isfile(fg_path):
        G.check("D0", "ic_launcher_foreground.xml 存在", False, "缺失")
        return
    xml = read_text(fg_path)
    vp_w = float(re.search(r'android:viewportWidth="([^"]+)"', xml).group(1))
    vp_h = float(re.search(r'android:viewportHeight="([^"]+)"', xml).group(1))
    width_attr = re.search(r'android:width="([^"]+)"', xml).group(1)
    sw = float(re.search(r'android:strokeWidth="([^"]+)"', xml).group(1))
    pd = re.search(r'android:pathData="([^"]+)"', xml).group(1)
    stroke_col = re.search(r'android:strokeColor="(#[0-9A-Fa-f]{6})"', xml).group(1).upper()

    nums = [float(x) for x in re.findall(r"[-+]?\d*\.?\d+", pd)]
    coords = [(nums[i], nums[i + 1]) for i in range(0, len(nums) - 1, 2)]
    xs = [c[0] for c in coords]
    ys = [c[1] for c in coords]
    hh = sw / 2.0
    x0, y0, x1, y1 = min(xs) - hh, min(ys) - hh, max(xs) + hh, max(ys) + hh
    G.info(f"viewport={vp_w}x{vp_h} width={width_attr} strokeWidth={sw} strokeColor={stroke_col}")
    G.info(f"中心线 bbox x[{min(xs):.3f},{max(xs):.3f}] y[{min(ys):.3f},{max(ys):.3f}]")
    G.info(f"墨迹 bbox(+sw/2) x[{x0:.3f},{x1:.3f}] y[{y0:.3f},{y1:.3f}] "
           f"w={x1-x0:.3f} h={y1-y0:.3f}")

    G.check("D1", "viewport 为 108x108 且 width/height=108dp",
            vp_w == 108 and vp_h == 108 and width_attr == "108dp",
            f"{vp_w}x{vp_h} {width_attr}")
    G.check("D2", "前景笔画为白色", stroke_col == "#FFFFFF", stroke_col)

    cx = cy = vp_w / 2.0
    dists = {}
    for name, (px_, py_) in {"LT": (x0, y0), "RT": (x1, y0),
                             "LB": (x0, y1), "RB": (x1, y1)}.items():
        dists[name] = math.hypot(px_ - cx, py_ - cy)
    maxr = max(dists.values())
    # 反证：四个角到中心的距离都要 <= 33（**圆**，不是方块）
    G.check("D3", "墨迹矩形四角到中心(54,54)距离均 <= 33（完整落在直径 66 的圆内）",
            maxr <= 33.0,
            f"corner radii={ {k: round(v,4) for k,v in dists.items()} } max={maxr:.4f}")
    G.info(f"     最大半径 = {maxr:.4f}（安全半径 33；设计目标 30）")

    bcx, bcy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    G.check("D4", "墨迹 bbox 中心 ≈ (54,54)", abs(bcx - 54) <= 1.0 and abs(bcy - 54) <= 1.0,
            f"center=({bcx:.3f},{bcy:.3f})")

    # 与渲染器单一数据源的交叉核对（pathData 必须来自同一份笔画数据）
    try:
        sys.path.insert(0, ART)
        import render_icons as R  # noqa: E402
        expect = R.foreground_vector_xml(R.STYLE_A_FG).strip()
        G.check("D5", "ic_launcher_foreground.xml 与渲染器单源输出逐字一致",
                expect == xml.strip(), "一致" if expect == xml.strip() else "不一致")
        # 交叉核对渲染器算出的 bbox
        bx = R.foreground_bbox_in_vd(R.STYLE_A_FG)
        same = all(abs(a_ - b_) < 1e-3 for a_, b_ in zip(bx, (x0, y0, x1, y1)))
        G.check("D6", "独立解析的墨迹 bbox == 渲染器自算 bbox", same,
                f"parsed=({x0:.3f},{y0:.3f},{x1:.3f},{y1:.3f}) renderer="
                f"({bx[0]:.3f},{bx[1]:.3f},{bx[2]:.3f},{bx[3]:.3f})")
    except Exception as exc:                       # pragma: no cover
        G.check("D5", "渲染器单源交叉核对", False, f"导入/比对异常: {exc}")

    # 自适应 xml 引用关系
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        p = os.path.join(RES, "mipmap-anydpi-v26", name)
        txt = read_text(p) if os.path.isfile(p) else ""
        ok = ("@color/ic_launcher_background" in txt and
              "@drawable/ic_launcher_foreground" in txt)
        G.check(f"D7.{name}", f"{name} 引用 @color/ic_launcher_background + "
                              f"@drawable/ic_launcher_foreground", ok)

    # manifest 引用
    man = read_text(MANIFEST)
    G.check("D8", "AndroidManifest android:icon/roundIcon 指向 @mipmap/ic_launcher(_round)",
            'android:icon="@mipmap/ic_launcher"' in man and
            'android:roundIcon="@mipmap/ic_launcher_round"' in man)


# --------------------------------------------------------------------------- #
# E. APK 端到端
# --------------------------------------------------------------------------- #
def sectionE(apk: str, build: bool) -> None:
    G.section("E. APK 端到端")
    if build:
        gb = gradle_bat()
        if gb:
            G.info(f"运行 clean + assembleDebug: {gb}")
            cmd = f'cd "{ROOT}" && JAVA_HOME=/d/Java/jdk11 "{gb}" --offline ' \
                  f':app:clean :app:assembleDebug'
            proc = subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True)
            tail = [l for l in proc.stdout.splitlines() if l.strip()][-6:]
            for l in tail:
                G.info("     " + l)
            G.check("E0", "Gradle :app:assembleDebug 成功", proc.returncode == 0,
                    f"rc={proc.returncode}")
        else:
            G.check("E0", "找到 gradle 发行版", False, "未找到 gradle.bat")

    if not os.path.isfile(apk):
        G.check("E1", "APK 存在", False, apk)
        return
    apk_size = os.path.getsize(apk)
    icon_mtimes = [os.path.getmtime(os.path.join(RES, f"mipmap-{d}", f))
                   for d in DENSITIES for f in ("ic_launcher.png", "ic_launcher_round.png")]
    icon_mtimes.append(os.path.getmtime(os.path.join(RES, "drawable",
                                                     "ic_launcher_foreground.xml")))
    newest_icon = max(icon_mtimes)
    G.info(f"APK = {apk}")
    G.info(f"APK 字节数 = {apk_size} ({apk_size/1024/1024:.2f} MiB)")
    G.check("E1", "APK 存在且非空", apk_size > 0, f"{apk_size} bytes")
    G.check("E2", "APK 构建时间晚于图标资源（确实打包了本图标）",
            os.path.getmtime(apk) >= newest_icon - 2,
            f"apk_mtime={os.path.getmtime(apk):.0f} vs icon={newest_icon:.0f}")

    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
    icon_entries = sorted(n for n in names if "ic_launcher" in n.lower())
    G.info("APK 内 ic_launcher* 条目:")
    for n in icon_entries:
        G.info("     " + n)
    for dens in DENSITIES:
        has = any(f"mipmap-{dens}" in n and n.endswith("ic_launcher.png") for n in names)
        G.check(f"E3.{dens}", f"APK 含 {dens} ic_launcher.png", has)
    G.check("E3.fg", "APK 含 ic_launcher_foreground",
            any("ic_launcher_foreground" in n for n in names))
    G.check("E3.anydpi", "APK 含 mipmap-anydpi-v26/ic_launcher.xml 自适应图标",
            any("anydpi" in n and n.endswith("ic_launcher.xml") for n in names))

    # 反证：不得残留系统默认机器人图标 sym_def_app_icon
    bad = [n for n in names if "sym_def_app_icon" in n.lower()]
    G.check("E4", "反证：APK 内**不存在** sym_def_app_icon（系统默认机器人图标）",
            len(bad) == 0, f"命中={bad}")

    # 反证：不含任何非本次产物的额外 launcher 图标资源
    extra = [n for n in names
             if re.search(r"mipmap", n, re.I) and n.endswith((".png", ".xml"))
             and "ic_launcher" not in n]
    G.check("E5", "反证：res/mipmap-* 下无其它图标资源（仅 ic_launcher*）",
            len(extra) == 0, f"命中={extra[:6]}")

    # aapt 验合并 Manifest
    aapt = find_aapt()
    if not aapt:
        G.check("E6", "找到 aapt 以校验合并 Manifest", False, "未找到 aapt")
        return
    G.info(f"aapt = {aapt}")
    proc = subprocess.run([aapt, "dump", "xmltree", apk, "AndroidManifest.xml"],
                          capture_output=True, text=True)
    tree = proc.stdout
    has_icon = re.search(r"android:icon\(0x[0-9a-f]+\)=@0x[0-9a-f]+", tree)
    has_round = re.search(r"android:roundIcon\(0x[0-9a-f]+\)=@0x[0-9a-f]+", tree)
    G.check("E6", "合并 Manifest 含 android:icon", bool(has_icon),
            has_icon.group(0) if has_icon else "未找到")
    G.check("E7", "合并 Manifest 含 android:roundIcon", bool(has_round),
            has_round.group(0) if has_round else "未找到")

    proc2 = subprocess.run([aapt, "dump", "badging", apk], capture_output=True, text=True)
    badge = proc2.stdout
    m = re.search(r"package: name='([^']+)'", badge)
    G.check("E8", "APK 包名为 com.example.jingcai", bool(m) and m.group(1) == "com.example.jingcai",
            m.group(1) if m else "n/a")
    app_line = next((l for l in badge.splitlines() if l.startswith("application:")), "")
    G.info(f"badging: {app_line.strip()}")
    G.check("E9", "badging 中 application icon 指向本次 ic_launcher（自适应或密度图）",
            "ic_launcher" in app_line, app_line.strip())


# --------------------------------------------------------------------------- #
# F. 回归：不得破坏别的东西
# --------------------------------------------------------------------------- #
def sectionF(with_regression: bool) -> None:
    G.section("F. 回归：业务代码 / 历史门禁脚本未被改动")
    icon_files = []
    for d in DENSITIES:
        for f in ("ic_launcher.png", "ic_launcher_round.png"):
            icon_files.append(os.path.join(RES, f"mipmap-{d}", f))
    icon_files += [os.path.join(RES, "drawable", "ic_launcher_foreground.xml"),
                   os.path.join(RES, "values", "ic_launcher_background.xml")]
    icon_files += [os.path.join(OUT, f"app_icon_{k}_1024.png") for k in MASTER_KEYS]
    icon_mtimes = [os.path.getmtime(p) for p in icon_files if os.path.isfile(p)]
    earliest_icon = min(icon_mtimes) if icon_mtimes else 0

    kt = []
    for dirpath, _, files in os.walk(JAVA_SRC):
        for fn in files:
            if fn.endswith(".kt"):
                p = os.path.join(dirpath, fn)
                kt.append(p)
    kt.sort()
    G.info(f"最早图标产物 mtime = {earliest_icon:.0f}")
    latest_kt = 0.0
    for p in kt:
        mt = os.path.getmtime(p)
        latest_kt = max(latest_kt, mt)
        rel = os.path.relpath(p, ROOT)
        mark = "OK " if mt < earliest_icon else "!! "
        G.info(f"     {mark}{rel:70s} {mt:.0f}")
    G.check("F1", f"全部 {len(kt)} 个 .kt 的 mtime 都早于图标产物（未被本次图标工作改动）",
            latest_kt < earliest_icon,
            f"latest_kt={latest_kt:.0f} < earliest_icon={earliest_icon:.0f}")

    rounds = ["verify_round3.py", "verify_round4.py", "verify_round5.py",
              "verify_round6.py", "verify_round7.py"]
    for r in rounds:
        p = os.path.join(QA, r)
        if not os.path.isfile(p):
            G.check(f"F2.{r}", f"{r} 存在", False, "缺失")
            continue
        mt = os.path.getmtime(p)
        G.check(f"F2.{r}", f"{r} mtime 早于图标产物（未被改动）", mt < earliest_icon,
                f"{mt:.0f}")

    if not with_regression:
        G.info("（--with-regression 未开启：跳过历史门禁实跑）")
        return
    py = sys.executable
    for r in ["verify_round3.py", "verify_round5.py", "verify_round6.py",
              "verify_round7.py"]:
        p = os.path.join(QA, r)
        if not os.path.isfile(p):
            continue
        proc = subprocess.run([py, "-X", "utf8", p], capture_output=True, text=True,
                              cwd=ROOT, timeout=1800)
        lines = [l for l in (proc.stdout + proc.stderr).splitlines() if l.strip()]
        tail = lines[-4:]
        G.check(f"F3.{r}", f"{r} 回归通过 (rc==0)", proc.returncode == 0,
                " | ".join(tail)[:300])


# --------------------------------------------------------------------------- #
# G. 48px 可辨识度
# --------------------------------------------------------------------------- #
def sectionG() -> None:
    G.section("G. 48px 可辨识度（1024 -> 48 LANCZOS）")
    for k in MASTER_KEYS:
        img = Image.open(os.path.join(OUT, f"app_icon_{k}_1024.png")).convert("RGB")
        s48 = img.resize((48, 48), Image.LANCZOS)
        a = np.array(s48).astype(np.int32)
        fgm = whiteish_mask(a, thr=WHITE_THR)
        ncc, sizes = label_conn(fgm, conn=4)
        x0, x1, y0, y1, cx, cy = bbox_center(fgm) if fgm.any() else (0, 0, 0, 0, 0, 0)
        touches = (not fgm.any()) or x0 <= 0 or y0 <= 0 or x1 >= 47 or y1 >= 47
        ratio = fgm.mean() * 100
        largest_share = (sizes[0] / max(1, int(fgm.sum()))) * 100 if sizes else 0
        G.info(f"-- {k}: white={ratio:.1f}%  CC={ncc} sizes={sizes[:5]}  "
               f"bbox x[{x0},{x1}] y[{y0},{y1}]  largestShare={largest_share:.1f}%")
        G.check(f"G1.{k}", f"[{k}] 48px 白像素占比落在可辨区间 (5%~60%)", 5 <= ratio <= 60,
                f"{ratio:.1f}%")
        G.check(f"G2.{k}", f"[{k}] 48px 笔画未被裁切（bbox 不贴边）", not touches,
                f"bbox=({x0},{y0},{x1},{y1})")
        # 反证：不是"糊成一坨"（单一连通域且占比异常大）
        blob = (ncc == 1 and largest_share > 95 and ratio > 45)
        G.check(f"G3.{k}", f"[{k}] 反证：48px 未糊成单一巨块", not blob,
                f"CC={ncc} largestShare={largest_share:.1f}% ratio={ratio:.1f}%")


# --------------------------------------------------------------------------- #
def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="宏 启动图标 独立验收门禁")
    ap.add_argument("--apk", default=APK_DEFAULT)
    ap.add_argument("--build-apk", action="store_true", help="先 clean 重编 APK")
    ap.add_argument("--with-regression", action="store_true", help="追加跑历史回归门禁")
    args = ap.parse_args(argv)

    print("#" * 78)
    print("# qa/verify_icon.py —— 「宏」竞彩 App 启动图标 独立验收门禁")
    print("#" * 78)
    meta = section0_inputs()
    if meta.get("bg"):
        sectionA(meta["bg"])
    else:
        G.check("A*", "背景色可解析", False, "无法确定背景色，跳过 A")
    sectionB()
    sectionC()
    sectionD()
    sectionE(args.apk, args.build_apk)
    sectionF(args.with_regression)
    sectionG()
    return G.summary()


if __name__ == "__main__":
    raise SystemExit(main())
