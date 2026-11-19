#!/usr/bin/env python3
"""在「比赛」列表里滚动查找目标文本（11.00 / 未开售），命中即截图取证。"""
import subprocess, time, re, os
from xml.etree import ElementTree as ET
ADB = "D:/SDK/platform-tools/adb.exe"
PKG = "com.example.jingcai"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "qa", "shots")

def sh(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True, errors="replace")

def dump_texts():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    p = subprocess.run([ADB, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True, errors="replace")
    out = []
    try:
        for n in ET.fromstring(p.stdout).iter("node"):
            t = (n.get("text", "") or "").strip()
            b = n.get("bounds", "")
            m = re.findall(r"\[(\d+),(\d+)\]", b)
            if t and len(m) == 2:
                x1, y1 = int(m[0][0]), int(m[0][1]); x2, y2 = int(m[1][0]), int(m[1][1])
                out.append((t, x1, y1, x2, y2))
    except Exception as e:
        print("parse err", e)
    return out

def cap(name):
    with open(os.path.join(SHOTS, name), "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f)

def swipe():
    sh("shell", "input", "swipe", "630", "2200", "630", "900", "300")
    time.sleep(1.2)

targets = ["11.00", "未开售"]
found = {}
sh("shell", "am", "force-stop", PKG); time.sleep(1)
sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(4)
for step in range(16):
    nodes = dump_texts()
    for t, x1, y1, x2, y2 in nodes:
        for tg in targets:
            if t == tg and tg not in found:
                found[tg] = (step, (x1, y1, x2, y2), x2 - x1)
                print(f"命中 {tg!r} @ 第{step}屏 bounds=({x1},{y1},{x2},{y2}) w={x2-x1}px={ (x2-x1)/3.5:.1f}dp")
                cap(f"b2_6_{'odds1100' if tg=='11.00' else 'wukaishou'}.png")
    if len(found) == len(targets):
        break
    swipe()
print("FOUND:", found)
print("MISSING:", [t for t in targets if t not in found])
