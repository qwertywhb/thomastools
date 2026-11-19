#!/usr/bin/env python3
"""Batch2 3a/3b 一键取证脚本（需先 adb install -r）。

3a 按钮跳转：点「同奖查询」/「赔率变化」→ 判据(页面文案) → 返回列表
3b BackHandler：①取消不退出 ②弹框时连按返回只关框 ③退出真 finish ④4 个非首页 tab 返回回列表

判据来源：`uiautomator dump` 的 Compose 语义文本（稳健），坐标 tap 作为兜底。
每条 adb 命令的原文与返回码都会打印。带重试（tap/BACK 偶发丢事件）。
"""
import subprocess, time, re, os, sys
from xml.etree import ElementTree as ET

ADB = "D:/SDK/platform-tools/adb.exe"
PKG = "com.example.jingcai"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "qa", "shots")

exec(open(os.path.join(ROOT, "qa", "_b2_measure.py")).read().split("def main")[0])

def sh(*a):
    p = subprocess.run([ADB, *a], capture_output=True, text=True, errors="replace")
    print(f"  $ adb {' '.join(a)}  -> rc={p.returncode}"
          + (f" | {p.stdout.strip()[:200]}" if p.stdout.strip() else ""))
    return p

def cap(name):
    p = os.path.join(SHOTS, name)
    with open(p, "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f)
    return p

def dump_nodes(tag=""):
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    p = subprocess.run([ADB, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True, errors="replace")
    xml = p.stdout
    nodes = []
    try:
        root = ET.fromstring(xml)
        for n in root.iter("node"):
            t = n.get("text", "") or ""
            b = n.get("bounds", "")
            m = re.findall(r"\[(\d+),(\d+)\]", b)
            if len(m) == 2 and t:
                x1, y1 = int(m[0][0]), int(m[0][1])
                x2, y2 = int(m[1][0]), int(m[1][1])
                nodes.append((t, (x1 + x2) // 2, (y1 + y2) // 2, (x1, y1, x2, y2)))
    except Exception as e:
        print(f"  ! uia dump parse fail ({tag}): {e}; len={len(xml)}")
    return nodes

def texts(nodes):
    return [t for t, *_ in nodes]

def find(nodes, sub, exact=False):
    """exact=True 时用全等匹配（避免『退出』误配标题『退出应用』）。"""
    for t, cx, cy, b in nodes:
        if (t == sub) if exact else (sub in t):
            return (cx, cy)
    return None

def tap_text(sub, tries=3, exact=False):
    for i in range(tries):
        nodes = dump_nodes(f"tap_text:{sub}")
        pos = find(nodes, sub, exact=exact)
        if pos:
            print(f"  找到『{sub}』(exact={exact}) @ {pos}")
            sh("shell", "input", "tap", str(pos[0]), str(pos[1]))
            return True
        print(f"  (第{i+1}次) 未找到『{sub}』(exact={exact})，重试")
        time.sleep(1)
    # 如实说明：把 dump 里真实出现的文本全打出来，绝不静默回退到硬编码坐标
    print(f"  !! 找不到『{sub}』(exact={exact})，dump 实际文本={texts(nodes)}")
    return False

def tap_xy(x, y):
    sh("shell", "input", "tap", str(x), str(y))

def back():
    sh("shell", "input", "keyevent", "KEYCODE_BACK")

def foreground():
    p = subprocess.run([ADB, "shell", "dumpsys", "activity", "activities"], capture_output=True, text=True, errors="replace")
    for line in p.stdout.splitlines():
        if "topResumedActivity" in line:
            return PKG in line, line.strip()
    return False, "(no topResumedActivity line)"

def launch():
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(4)

def has(nodes, *subs):
    ts = texts(nodes)
    return any(any(s in t for t in ts) for s in subs)

def main():
    print("======== 3a 按钮跳转 ========")
    launch()
    if not tap_text("同奖查询"):
        print("3a:A UNTESTED (找不到同奖查询按钮)")
    else:
        time.sleep(3)
        nodes = dump_nodes("sameodds")
        ok_same = has(nodes, "奖金类型", "查 询", "搜索赔率")
        cap("b2_3a_sameodds.png")
        print(f"3a:A 同奖页判据(奖金类型/查 询/搜索赔率) = {ok_same} -> {'PASS' if ok_same else 'FAIL'}")
        back(); time.sleep(2)
        nodes = dump_nodes("back_to_list1")
        back_ok = has(nodes, "显示已截止", "今天")
        print(f"3a:A 返回列表判据(显示已截止/今天) = {back_ok} -> {'PASS' if back_ok else 'FAIL'}")

    if not tap_text("赔率变化"):
        print("3a:B UNTESTED (找不到赔率变化按钮)")
    else:
        time.sleep(3)
        nodes = dump_nodes("oddschange")
        ok_oc = has(nodes, "固定奖金", "发布时间", "让球")
        cap("b2_3a_oddschange.png")
        print(f"3a:B 赔率变化页判据(固定奖金/发布时间) = {ok_oc} -> {'PASS' if ok_oc else 'FAIL'}")
        back(); time.sleep(2)
        nodes = dump_nodes("back_to_list2")
        back_ok = has(nodes, "显示已截止", "今天")
        print(f"3a:B 返回列表判据 = {back_ok} -> {'PASS' if back_ok else 'FAIL'}")

    print("\n======== 3b BackHandler ========")
    # 复位到首页
    sh("shell", "am", "force-stop", PKG); time.sleep(1)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(4)

    # ① 首页返回 -> 弹框；点取消 -> 框关且 App 仍在
    back(); time.sleep(2)
    nodes = dump_nodes("d1")
    dlg = has(nodes, "退出应用", "确定要退出竞彩")
    cap("b2_3b1_dialog.png")
    print(f"3b:① 首页返回弹框 = {dlg} -> {'PASS' if dlg else 'FAIL'}")
    if tap_text("取消", exact=True):
        time.sleep(2)
        nodes = dump_nodes("d1b")
        gone = not has(nodes, "退出应用")
        fg, line = foreground()
        print(f"3b:① 取消后 框消失={gone} App仍在={fg}  [{line[:80]}] -> {'PASS' if gone and fg else 'FAIL'}")

    # ② 弹框显示时再按返回 -> 只关框、不退出
    back(); time.sleep(2)
    nodes = dump_nodes("d2")
    print(f"3b:② 再次弹框 = {has(nodes,'退出应用')}")
    back(); time.sleep(2)
    nodes = dump_nodes("d2b")
    fg, line = foreground()
    gone = not has(nodes, "退出应用")
    print(f"3b:② 弹框态按返回 -> 框消失={gone} App仍在={fg} -> {'PASS' if gone and fg else 'FAIL'}")

    # ③ 弹框点退出 -> 真 finish
    back(); time.sleep(2)
    nodes = dump_nodes("d3")
    print(f"3b:③ 弹框文本 = {[t for t in texts(nodes) if '退出' in t or '取消' in t]}")
    if tap_text("退出", exact=True):
        time.sleep(3)
        fg, line = foreground()
        print(f"3b:③ 点退出后 App前台={fg} [{line[:90]}] -> {'PASS' if not fg else 'FAIL'}")

    # ④ 4 个非首页 tab 各按一次返回 -> 应回「比赛」列表，不弹框
    for tab in ("竞彩赛程", "比分直播", "同奖查询", "资料"):
        launch()
        if not tap_text(tab):
            print(f"3b:④ {tab} 导航失败 UNTESTED"); continue
        time.sleep(3)
        back(); time.sleep(2)
        nodes = dump_nodes(f"tab:{tab}")
        on_match = has(nodes, "显示已截止")
        dlg = has(nodes, "退出应用")
        cap(f"b2_3b4_{tab}.png")
        print(f"3b:④ {tab} 返回 -> 回到比赛列表={on_match} 弹框={dlg} "
              f"-> {'PASS' if on_match and not dlg else 'FAIL'}")

if __name__ == "__main__":
    main()
