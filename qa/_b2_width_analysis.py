#!/usr/bin/env python3
"""Batch2 布局 B 的离线「最坏文本宽度」分析（不需要真机）。

用真机同源样本 qa/_zq_list_sample.json 枚举最长队名 / 最长赔率串，
按源码 dp 预算 + Compose 默认字体度量（Roboto Latin / 系统 CJK 全角）算出可用宽度与余量。

字体度量（em advance，1em = fontSize）:
  CJK 全角 1.00 | 数字 0.556 | 拉丁字母 0.552 | '.' 0.291 | ':' 0.291 | '-' 0.360 | '+' 0.546 | '[' ']' 0.291 | 空格 0.248
"""
import json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAMPLE = os.path.join(ROOT, "qa", "_zq_list_sample.json")

EM = {"cjk": 1.00, "digit": 0.556, "latin": 0.552, "dot": 0.291,
      "colon": 0.291, "dash": 0.360, "plus": 0.546, "brk": 0.291, "space": 0.248}

def adv(ch):
    o = ord(ch)
    if 0x4E00 <= o <= 0x9FFF or ch in "，。：" or o >= 0x3000 and o <= 0x303F or o >= 0xFF00:
        return EM["cjk"]
    if ch.isdigit():
        return EM["digit"]
    if ch.isalpha():
        return EM["latin"]
    if ch == '.':
        return EM["dot"]
    if ch == ':':
        return EM["colon"]
    if ch == '-':
        return EM["dash"]
    if ch == '+':
        return EM["plus"]
    if ch in "[]":
        return EM["brk"]
    if ch == ' ':
        return EM["space"]
    return 0.55

def w_dp(s, sp):
    return sum(adv(c) for c in s) * sp

# ---- dp 预算（布局 B，真机 360dp 宽）----
SCREEN = 360.0
LZ_PAD = 10.0        # LazyColumn contentPadding
CARD_PAD_H = 10.0    # 卡内水平 padding
ACTION_COL = 64.0    # 右操作列宽
SPACER = 8.0         # 左内容列与操作列间距
LEFT = SCREEN - 2*LZ_PAD - 2*CARD_PAD_H - ACTION_COL - SPACER
ODDS_LABEL = 52.0
ODDS_COL = (LEFT - ODDS_LABEL) / 3.0
VS_SP = 9.0
VS_PAD = 4.0
CENTER = w_dp("VS", VS_SP) + 2*VS_PAD
SIDE = (LEFT - CENTER) / 2.0

# ---- 源码字号 ----
SP_NAME, SP_RANK, SP_ODDS, SP_LABEL = 11.0, 7.0, 11.0, 9.0

def main():
    d = json.load(open(SAMPLE, encoding="utf-8"))
    subs = []
    for g in d["value"]["matchInfoList"]:
        subs += g.get("subMatchList", [])
    names, leagues, odds = [], [], []
    for m in subs:
        names += [m.get("homeTeamAllName", ""), m.get("awayTeamAllName", "")]
        leagues.append(m.get("leagueAbbName", ""))
        for o in m.get("oddsList", []):
            if o.get("poolCode") in ("HAD", "HHAD"):
                odds += [o.get("h", ""), o.get("d", ""), o.get("a", "")]
    names = sorted(set(n for n in names if n), key=lambda s: -w_dp(s, SP_NAME))
    leagues = sorted(set(leagues), key=lambda s: -len(s))
    odds = sorted(set(o for o in odds if o), key=lambda s: -w_dp(s, SP_ODDS))

    print("== 布局 B dp 预算 ==")
    print(f"  屏幕 {SCREEN}dp − LazyCol {2*LZ_PAD} − 卡内 {2*CARD_PAD_H} − 操作列 {ACTION_COL} − 间距 {SPACER}")
    print(f"  左内容列 = {LEFT:.2f}dp | 赔率数值列 = {ODDS_COL:.2f}dp | 对阵单侧 = {SIDE:.2f}dp (center VS={CENTER:.2f}dp)")

    print("\n== 最长队名（按 11sp 渲染）==")
    for n in names[:5]:
        print(f"  {n} ({len(n)}字) = {w_dp(n, SP_NAME):.1f}dp")

    print("\n== 最长联赛简称 ==")
    print("  " + ", ".join(leagues[:5]))

    print("\n== 最长赔率串（按 11sp）==")
    for o in odds[:5]:
        print(f"  {o} = {w_dp(o, SP_ODDS):.1f}dp  (列宽 {ODDS_COL:.1f}dp, 余量 {ODDS_COL - w_dp(o, SP_ODDS):+.1f})")
    w_ws = w_dp("未开售", SP_ODDS)
    print(f"  未开售 = {w_ws:.1f}dp  (列宽 {ODDS_COL:.1f}dp, 余量 {ODDS_COL - w_ws:+.1f})")

    print("\n== 对阵单侧最坏（排名+间距+队名）==")
    for lg_cjk in (2, 3):
        rank = "[" + "甲"*lg_cjk + "18]"
        for nm in ("纽卡斯尔喷气机", "西悉尼漫步者"):
            tot = w_dp(rank, SP_RANK) + 2.0 + w_dp(nm, SP_NAME)
            print(f"  排名'{rank}'(#cjk={lg_cjk}) {w_dp(rank,SP_RANK):.1f}dp + 2 + 队名'{nm}' {w_dp(nm,SP_NAME):.1f}dp = {tot:.1f}dp  "
                  f"vs 单侧 {SIDE:.1f}dp ⇒ 余量 {SIDE - tot:+.1f}dp {'OK' if tot <= SIDE else 'OVERFLOW'}")

    print("\n== 赔率行标签 ==")
    for lab in ("胜平负", "让球-1", "让球-2"):
        print(f"  '{lab}' @9sp = {w_dp(lab, SP_LABEL):.1f}dp vs 标签列 {ODDS_LABEL}dp ⇒ 余量 {ODDS_LABEL - w_dp(lab,SP_LABEL):+.1f}")

if __name__ == "__main__":
    main()
