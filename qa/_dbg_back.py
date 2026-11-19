import subprocess, time
from xml.etree import ElementTree as ET
ADB = "D:/SDK/platform-tools/adb.exe"
PKG = "com.example.jingcai"

def sh(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True, errors="replace")

def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    p = subprocess.run([ADB, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True, errors="replace")
    ts = []
    try:
        root = ET.fromstring(p.stdout)
        for n in root.iter("node"):
            t = n.get("text", "")
            if t:
                ts.append(t)
    except Exception as e:
        print("  parse err", e, "len", len(p.stdout))
    return ts

sh("shell", "am", "force-stop", PKG); time.sleep(1)
sh("shell", "am", "start", "-n", PKG + "/.MainActivity"); time.sleep(6)
print("after launch:", dump())
sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(2)
print("after BACK#1:", dump())
sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(2)
print("after BACK#2:", dump())
