#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
临时离线自检脚本（Engineer 自测用，可留可删）。

用**与 Kotlin `TitanLiveParser` 完全相同的规则**解析冻结样本：
  - qa/_live_sample.html          -> 比分直播比赛表格 + 内联脚本 M 数组排名
  - qa/_live_sample_dx101.xml     -> 大小球（公司 3）

打印 001~004 行的 20 个字段，供 QA 用独立脚本复核。
不发任何网络请求。
"""
import os
import re
from html.parser import HTMLParser

HERE = os.path.dirname(os.path.abspath(__file__))
HTML_PATH = os.path.join(HERE, "_live_sample.html")
XML_PATH = os.path.join(HERE, "_live_sample_dx101.xml")

OU_COMPANY_ID = "3"

# titan007 GoalCn 盘口换算表（逐字照抄），索引 = round(goal*4)
GOAL_CN = [
    "0", "0/0.5", "0.5", "0.5/1", "1", "1/1.5", "1.5", "1.5/2", "2", "2/2.5", "2.5", "2.5/3",
    "3", "3/3.5", "3.5", "3.5/4", "4", "4/4.5", "4.5", "4.5/5", "5", "5/5.5", "5.5", "5.5/6",
    "6", "6/6.5", "6.5", "6.5/7", "7", "7/7.5", "7.5", "7.5/8", "8", "8/8.5", "8.5", "8.5/9",
    "9", "9/9.5", "9.5", "9.5/10", "10", "10/10.5", "10.5", "10.5/11", "11", "11/11.5", "11.5",
    "11.5/12", "12", "12/12.5", "12.5", "12.5/13", "13", "13/13.5", "13.5", "13.5/14", "14",
]


def goal_to_cn(goal):
    try:
        g = float(goal)
    except ValueError:
        return goal
    idx = int(round(g * 4))
    return GOAL_CN[idx] if 0 <= idx < len(GOAL_CN) else goal


# ---------------------------------------------------------------------------
# 源 2：dx101.xml
# ---------------------------------------------------------------------------
def parse_over_under(xml_text):
    text = xml_text.replace("\uFEFF", "")
    out = {}
    for m in re.finditer(r"<i>([^<]+)</i>", text):
        parts = m.group(1).split(",")
        if len(parts) < 6:
            continue
        if parts[0].strip() != OU_COMPANY_ID:
            continue
        matchnum = parts[1].strip()
        if not matchnum:
            continue
        home = parts[3].strip()
        goal = parts[4].strip()
        away = parts[5].strip()
        out[matchnum] = (home, goal_to_cn(goal), away)
    return out


# ---------------------------------------------------------------------------
# 源 1：比赛页（迷你 DOM，只取 tr 的直接 td 子节点 —— 等价 Jsoup children()）
# ---------------------------------------------------------------------------
class RowParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tr_stack = []
        self.rows = []  # 每个 tr 一帧：{attrs, tds:[{attrs,text,anchors}]}

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "tr":
            fr = {"attrs": a, "tds": [], "open_td": None}
            self.tr_stack.append(fr)
            self.rows.append(fr)
        elif tag == "td" and self.tr_stack:
            fr = self.tr_stack[-1]
            td = {"attrs": a, "text": [], "anchors": [], "cur_anchor": None}
            fr["tds"].append(td)
            fr["open_td"] = td
        elif tag == "a" and self.tr_stack and self.tr_stack[-1]["open_td"] is not None:
            td = self.tr_stack[-1]["open_td"]
            anc = {"attrs": a, "text": []}
            td["anchors"].append(anc)
            td["cur_anchor"] = anc

    def handle_startendtag(self, tag, attrs):
        # 自闭合标签（<img/> 之类）不影响 td 结构
        pass

    def handle_endtag(self, tag):
        if tag == "tr":
            if self.tr_stack:
                self.tr_stack.pop()
        elif tag == "td":
            if self.tr_stack and self.tr_stack[-1]["open_td"] is not None:
                self.tr_stack[-1]["open_td"] = None
        elif tag == "a":
            if self.tr_stack and self.tr_stack[-1]["open_td"] is not None:
                self.tr_stack[-1]["open_td"]["cur_anchor"] = None

    def handle_data(self, data):
        if self.tr_stack and self.tr_stack[-1]["open_td"] is not None:
            td = self.tr_stack[-1]["open_td"]
            td["text"].append(data)
            if td["cur_anchor"] is not None:
                td["cur_anchor"]["text"].append(data)


def td_text(td):
    return "".join(td["text"]).replace("\u00a0", " ").strip()


def clean_value(raw):
    if raw is None:
        return ""
    v = raw.strip().strip("\"'").strip()
    return "" if v == "undefined" else v


def clean_rank(raw):
    v = clean_value(raw)
    return "" if not v else v.replace("\u00a0", "").strip()


def parse_ranks(html_text):
    """M[i][0]=titanId, M[i][11]=主队排名, M[i][12]=客队排名, M[i][23]=主客是否调转。"""
    cells = {}
    for m in re.finditer(r"M\[(\d+)\]\[(\d+)\]\s*=\s*([^;]+);", html_text):
        i, j, v = int(m.group(1)), int(m.group(2)), m.group(3)
        cells.setdefault(i, {})[j] = v
    out = {}
    for row in cells.values():
        tid = clean_value(row.get(0))
        if not tid:
            continue
        swap = clean_value(row.get(23)).lower() in ("true", "1")
        out[tid] = (clean_rank(row.get(11)), clean_rank(row.get(12)), swap)
    return out


def parse_page(html_text):
    rank_map = parse_ranks(html_text)

    # 内层 spfItem 里的赔率（span id + rqTr 让球显示文本）
    spans = dict(re.findall(r'<span id="(sp_[^"]+)">([^<]*)</span>', html_text))
    rq_b = dict(re.findall(r'<tr id="rqTr_(\d+)"><td[^>]*>(?:<b>)?(.*?)</td>', html_text, re.S))

    p = RowParser()
    p.feed(html_text)

    groups = []
    started = False
    cur_weekday, cur_date, cur_list = "", "", []

    def flush():
        if started:
            groups.append((cur_date, cur_weekday, list(cur_list)))

    for fr in p.rows:
        cls = fr["attrs"].get("class", "")
        if "niDate" in cls:
            flush()
            cur_list.clear()
            tds = fr["tds"]
            label = td_text(tds[0]) if tds else ""
            label = label.replace("隐藏", "").replace("显示", "").strip()
            dm = re.search(r"(\d{4})年(\d{1,2})月(\d{1,2})日\s*星期([一二三四五六日])", label)
            if dm:
                cur_date = "%04d-%02d-%02d" % (int(dm.group(1)), int(dm.group(2)), int(dm.group(3)))
                cur_weekday = "星期" + dm.group(4)
            else:
                cur_date, cur_weekday = "", ""
            started = True
            continue

        if "matchid" not in fr["attrs"]:
            continue

        tds = fr["tds"]
        matchid = fr["attrs"].get("matchid", "").strip()
        titan_id = ""
        num_raw = td_text(tds[0]) if len(tds) > 0 else ""
        num_m = re.search(r"\d{3}", num_raw)
        num = num_m.group(0) if num_m else num_raw

        league = ""
        color = ""
        if len(tds) > 1:
            ltd = tds[1]
            if ltd["anchors"]:
                league = "".join(ltd["anchors"][0]["text"]).strip()
            cm = re.search(r"background-color:\s*(#[0-9A-Fa-f]{6})", ltd["attrs"].get("style", ""))
            color = cm.group(1) if cm else ""

        time = td_text(tds[3]) if len(tds) > 3 else ""
        home = away = ""
        if len(tds) > 4:
            for anc in tds[4]["anchors"]:
                if anc["attrs"].get("id", "").startswith("HomeTeam_"):
                    home = "".join(anc["text"]).strip()
                    titan_id = anc["attrs"]["id"][len("HomeTeam_"):]
                    break
        score = "-"
        if len(tds) > 6:
            s = td_text(tds[6]).strip()
            score = s if s else "-"
        if len(tds) > 7:
            for anc in tds[7]["anchors"]:
                if anc["attrs"].get("id", "").startswith("GuestTeam_"):
                    away = "".join(anc["text"]).strip()
                    break

        # 让球数：优先 rqTr <b> 文本，回退 polygoal（正数补 +）
        shown = rq_b.get(matchid, "")
        shown = re.sub(r"<[^>]+>", "", shown).strip()
        if shown:
            handicap = shown
        else:
            pg = fr["attrs"].get("polygoal", "").strip()
            handicap = pg if (not pg or pg[0] in "-+" or pg == "0") else "+" + pg

        rh, ra, swap = rank_map.get(titan_id, ("", "", False))
        cur_list.append({
            "matchId": matchid,
            "titanId": titan_id,
            "num": num,
            "league": league,
            "color": color,
            "time": time,
            "home": home,
            "homeRank": rh,
            "score": score,
            "away": away,
            "awayRank": ra,
            "spfH": spans.get("sp_%s_52" % matchid, "").strip(),
            "spfD": spans.get("sp_%s_53" % matchid, "").strip(),
            "spfA": spans.get("sp_%s_54" % matchid, "").strip(),
            "handicap": handicap,
            "rqH": spans.get("sp_%s_1" % matchid, "").strip(),
            "rqD": spans.get("sp_%s_2" % matchid, "").strip(),
            "rqA": spans.get("sp_%s_3" % matchid, "").strip(),
            "striped": "ni2" in cls,
            "swap": swap,
        })
    flush()
    return groups


FIELDS = ["num", "league", "color", "time", "home", "homeRank", "score", "away", "awayRank",
          "ouHome", "ouGoal", "ouAway", "spfH", "spfD", "spfA", "handicap", "rqH", "rqD", "rqA",
          "matchId", "swap"]


def parse_changes(xml_text):
    """change.xml: CDATA 里 D=text.split('^'); D[0]=titanId,D[1]=state,D[2]=h,D[3]=a。"""
    text = xml_text.replace("\uFEFF", "")
    out = []
    for m in re.finditer(r"<!\[CDATA\[([\s\S]*?)\]\]>", text):
        parts = m.group(1).split("^")
        if len(parts) < 4:
            continue
        tid = parts[0].strip()
        if not tid:
            continue
        try:
            state = int(parts[1].strip())
            h = int(parts[2].strip() or "0")
            a = int(parts[3].strip() or "0")
        except ValueError:
            continue
        out.append((tid, state, h, a))
    return out


def main():
    html_text = open(HTML_PATH, "rb").read().decode("utf-8", errors="replace")
    xml_text = open(XML_PATH, "rb").read().decode("utf-8", errors="replace")

    groups = parse_page(html_text)
    ou = parse_over_under(xml_text)

    total = 0
    for date, wd, matches in groups:
        print("== %s %s : %d 场 ==" % (wd, date, len(matches)))
        total += len(matches)
    print("total matches:", total)

    print("\n---- 第 1 组（星期五 2026-10-16）001~004 的 20 字段 ----")
    shown = 0
    if groups:
        date, wd, matches = groups[0]
        for m in matches:
            if m["num"] in ("001", "002", "003", "004"):
                o = ou.get(m["matchId"])
                m["ouHome"], m["ouGoal"], m["ouAway"] = o if o else ("-", "-", "-")
                print("[%s] %s" % (m["num"], ", ".join("%s=%s" % (k, m[k]) for k in FIELDS)))
                shown += 1
    print("shown:", shown)

    # change.xml 增量解析自检（若冻结样本存在）
    change_path = os.path.join(HERE, "_live_sample_change.xml")
    if os.path.exists(change_path):
        cx = open(change_path, "rb").read().decode("utf-8", errors="replace")
        chs = parse_changes(cx)
        print("\n---- change.xml 增量条目 (%d) ----" % len(chs))
        for tid, state, h, a in chs:
            print("titanId=%s state=%d home=%d away=%d -> score=%s" % (
                tid, state, h, a, "-" if state == 0 else "%d-%d" % (h, a)))


if __name__ == "__main__":
    main()
