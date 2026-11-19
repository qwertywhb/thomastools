"""按页签截图（近期状态 4 视图），带「截图前先 dump 校验当前页」的防错。

⚠️ 真机 screencap 有 1 帧左右滞后（切页瞬间截图会拿到上一页），所以每张都
   先 tap → 等 2.5s → dump 校验 → shot → 等 1.5s → shot 覆盖 → dump 再校验。

用法：python _ui2_tabs.py
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _ui2_dev as D  # noqa: E402

PY = sys.executable

CASES = [
    ("主队近期", "布里斯班近况", "ui2_03_home"),
    ("客队近期", "珀斯光荣近况", "ui2_04_away"),
    ("未来5场", "未来5场", "ui2_05_future"),
    ("历史交锋", "两队交锋", "ui2_06_vs"),
]


def tap_text(text):
    nodes = [n for n in D.dump() if n["text"] == text]
    if not nodes:
        raise SystemExit("tab not found: " + text)
    n = nodes[0]
    x1, y1, x2, y2 = n["bounds"]
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    D.adb("shell", "input", "tap", str(cx), str(cy))
    return cx, cy


def has_text(sub):
    return any(sub in n["text"] for n in D.dump())


def shot(name):
    path = os.path.join(D.SHOTS, name + ".png")
    raw = subprocess.run([D.ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout
    open(path, "wb").write(raw)
    return path


for tab, expect, name in CASES:
    cx, cy = tap_text(tab)
    time.sleep(2.5)
    ok1 = has_text(expect)
    shot(name)
    time.sleep(1.5)
    p = shot(name)
    ok2 = has_text(expect)
    print(f"{tab} @({cx},{cy}) expect={expect!r} dump_before={ok1} dump_after={ok2} -> {p}")
