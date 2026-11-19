"""ui-engineer2 本轮改造（同奖查询紧凑化 / 近期状态列与着色）的 adb 操作小工具。

用法：
  python _ui2_dev.py dump                    打印全部可点/带文字节点
  python _ui2_dev.py tap "近期状态"           按文本点击（子串匹配，取最小 bounds 的节点）
  python _ui2_dev.py tapxy 630 2000           直接点坐标
  python _ui2_dev.py swipe up|down [px]       整页上下滑
  python _ui2_dev.py shot ui2_01_sameodds     截图到 verify_shots/<name>.png
"""
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ADB = r"D:\SDK\platform-tools\adb.exe"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "verify_shots")
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*args, **kw):
    return subprocess.run([ADB, *args], capture_output=True, text=True, **kw)


def dump():
    # 🔴 必须先删旧文件：uiautomator dump 在界面未 idle（动画/首帧）时会失败，
    #    但 adb pull 仍能拉到上一次的 ui.xml ⇒ 读到过期树（实测：明明在「比赛」列表，
    #    dump 却报同奖查询的 23 行结果）。删掉后失败就报错，不会静默错。
    adb("shell", "rm", "-f", "/sdcard/ui.xml")
    out = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    if "dumped to" not in (out.stdout or ""):
        raise RuntimeError("uiautomator dump failed: %s %s" % (out.stdout, out.stderr))
    fd, tmp = tempfile.mkstemp(suffix=".xml")
    os.close(fd)
    subprocess.run([ADB, "pull", "/sdcard/ui.xml", tmp], capture_output=True, text=True)
    tree = ET.parse(tmp)
    os.unlink(tmp)
    nodes = []
    for n in tree.iter("node"):
        b = n.get("bounds") or ""
        m = BOUNDS.match(b)
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        nodes.append({
            "text": (n.get("text") or "").strip(),
            "desc": (n.get("content-desc") or "").strip(),
            "cls": n.get("class"),
            "bounds": (x1, y1, x2, y2),
        })
    return nodes


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == "dump":
        for n in dump():
            if n["text"] or n["desc"]:
                print(n)
    elif cmd == "tap":
        want = sys.argv[2]
        cands = [n for n in dump() if want in n["text"] or want in n["desc"]]
        if not cands:
            print("NOT FOUND:", want)
            sys.exit(2)
        cands.sort(key=lambda n: (n["bounds"][2] - n["bounds"][0]) * (n["bounds"][3] - n["bounds"][1]))
        n = cands[0]
        x1, y1, x2, y2 = n["bounds"]
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        print("TAP", repr(n["text"] or n["desc"]), n["bounds"], "->", cx, cy)
        adb("shell", "input", "tap", str(cx), str(cy))
    elif cmd == "tapxy":
        adb("shell", "input", "tap", sys.argv[2], sys.argv[3])
        print("TAP", sys.argv[2], sys.argv[3])
    elif cmd == "swipe":
        d = sys.argv[2]
        px = sys.argv[3] if len(sys.argv) > 3 else "1800"
        if d == "up":
            adb("shell", "input", "swipe", "630", "2200", "630", str(2200 - int(px)), "300")
        else:
            adb("shell", "input", "swipe", "630", "800", "630", str(800 + int(px)), "300")
        print("SWIPE", d, px)
    elif cmd == "shot":
        name = sys.argv[2]
        path = os.path.join(SHOTS, name if name.endswith(".png") else name + ".png")
        raw = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout
        with open(path, "wb") as f:
            f.write(raw)
        print("SHOT", path, len(raw))
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
