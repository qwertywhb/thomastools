#!/usr/bin/env python3
"""Batch2 任务3 真机验证：详情页返回应 popBackStack（不弹退出框）；tab 返回应弹退出框。

设备被人工/系统干扰时 tap 会丢失，故对「打开详情」做重试，并在每步用像素判据确认。
"""
import subprocess, sys, os, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = "D:/SDK/platform-tools/adb.exe"
SHOTS = os.path.join(ROOT, "qa", "shots")

exec(open(os.path.join(ROOT, "qa", "_b2_measure.py")).read().split("def main")[0])

def sh(*args):
    return subprocess.run([ADB, *args], capture_output=True)

def cap(name):
    p = os.path.join(SHOTS, name)
    with open(p, "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f)
    return p

def near(c, t, tol=6):
    return all(abs(c[i] - t[i]) <= tol for i in range(3))

def load(name):
    w, h, ch, px = read_png(os.path.join(SHOTS, name))
    return w, h, ch, px

def at(px, w, ch, x, y):
    i = (y * w + x) * ch
    return (px[i], px[i+1], px[i+2])

def is_detail(px, w, h, ch):
    # 详情页顶部 TopAppBar 为 Primary 蓝 (94,107,246)，在 y 200..270 一带成片出现
    hits = 0
    for y in range(190, 275, 6):
        for x in range(150, 1150, 50):
            if near(at(px, w, ch, x, y), (94, 107, 246)):
                hits += 1
    return hits >= 20, hits

def has_bottom_nav(px, w, h, ch):
    # 底部导航条配色 (241,243,254) 在 y>2450 x=20
    return near(at(px, w, ch, 20, 2560), (241, 243, 254), 8)

def dialog_ratio(px, w, h, ch):
    """M3 AlertDialog 卡面实测 (238,239,254)（冷色、b-r≈16）；列表无框，此处为卡色 (238,240,245)。"""
    hits = tot = 0
    for y in range(1150, 1750, 12):
        for x in range(280, 980, 12):
            tot += 1
            r, g, b = at(px, w, ch, x, y)
            if r >= 230 and b >= 248 and (b - r) >= 10:
                hits += 1
    return hits / max(tot, 1)

def main():
    sh("shell", "am", "force-stop", "com.example.jingcai")
    time.sleep(1)
    sh("shell", "am", "start", "-n", "com.example.jingcai/.MainActivity")
    time.sleep(5)

    detail = False
    for attempt in range(1, 6):
        sh("shell", "input", "tap", "727", "975")
        time.sleep(2.5)
        cap("_e_try.png")
        w, h, ch, px = load("_e_try.png")
        ok, hits = is_detail(px, w, h, ch)
        print(f"attempt {attempt}: detail_hits={hits} -> {'DETAIL' if ok else 'not detail'}")
        if ok:
            detail = True
            break

    if not detail:
        print("FAILED: 无法稳定打开详情页（设备干扰）")
        return

    # 详情页按 BACK → 期望：回到比赛列表，且不出现退出框
    sh("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(3)
    cap("_e_afterback.png")
    w, h, ch, px = load("_e_afterback.png")
    nav = has_bottom_nav(px, w, h, ch)
    dlg = dialog_ratio(px, w, h, ch)
    print(f"BACK on detail -> bottomNav={nav}  dialogWhiteRatio={dlg:.2f}")
    print("RESULT:", "PASS: 详情返回 pop 到列表且无退出框" if (nav and dlg < 0.05) else "CHECK")

    # 再按 BACK（此时在 tab 上）→ 期望弹出退出框（设备有丢事件可能，重试至多 3 次）
    dlg2 = 0.0
    for t in range(1, 4):
        sh("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(2)
        cap("_e_afterback2.png")
        w, h, ch, px = load("_e_afterback2.png")
        dlg2 = dialog_ratio(px, w, h, ch)
        print(f"tab-BACK try {t}: dialogRatio={dlg2:.2f}")
        if dlg2 > 0.15:
            break
    print("RESULT2:", "PASS: tab 返回弹退出框" if dlg2 > 0.15 else "CHECK")

if __name__ == "__main__":
    main()
