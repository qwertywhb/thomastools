# -*- coding: utf-8 -*-
"""独立解析 okooo history 样本：行数 / summary / 抽查行，与实现者口径比对。

统计口径直接照抄网页 footcompetitionhistory.js 的 gethomerecord（QA 自行阅读 JS 复刻，不看实现者 Kotlin）：
  - 取 table 内全部 tr，跳过前 2 行（tr.tableh 表头 + tr.titlebg 列名）
  - display:none 的行（含 tr.jsThisMatch）跳过
  - 比分 attr == "-" 跳过；tr.attr("attr") 形如 "主id,比分,客id"
  - returnid==homeid → 主场视角计 homevictory/homedraw/homefailure + 进失球
    returnid==awayid → 客场视角只加进失球
  - 总胜平负按 checkscore（returnid 侧视角）全局计
  - team=="away" 时输出「客场」= 总数 - 主场数
"""
import re
import sys
from html.parser import HTMLParser

SAMPLE = r"D:/tmp_wordprobe/okooo_hist_1341208.html"


class TableParser(HTMLParser):
    """粗粒度解析三个表的 tr/td 结构（按 class 定位表，不依赖第三方库）。"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tables = {}          # class -> rows
        self.cur_table = None
        self.cur_tr = None
        self.cur_td = None
        self.depth_table = 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "table":
            cls = a.get("class", "")
            for c in ("homecomp", "awaycomp", "vscomp"):
                if c in cls.split():
                    self.cur_table = c
                    self.tables.setdefault(c, [])
                    self.depth_table += 1
                    return
        if self.cur_table is None:
            return
        if tag == "tr":
            self.cur_tr = {
                "class": a.get("class", ""),
                "attr": a.get("attr", ""),
                "style": a.get("style", ""),
                "data-lt": a.get("data-lt", ""),
                "data-matchid": a.get("data-matchid", ""),
                "tds": [],
            }
        elif tag == "td" and self.cur_tr is not None:
            self.cur_td = {"attr": a.get("attr", ""), "text": "", "html": ""}
        elif tag in ("script",):
            pass

    def handle_endtag(self, tag):
        if tag == "table" and self.cur_table is not None:
            self.depth_table -= 1
            if self.depth_table <= 0:
                self.cur_table = None
                self.depth_table = 0
        elif tag == "tr" and self.cur_tr is not None:
            self.tables[self.cur_table].append(self.cur_tr)
            self.cur_tr = None
            self.cur_td = None
        elif tag == "td" and self.cur_td is not None:
            self.cur_tr["tds"].append(self.cur_td)
            self.cur_td = None

    def handle_data(self, data):
        if self.cur_td is not None:
            self.cur_td["text"] += data


def load_rows():
    with open(SAMPLE, "r", encoding="utf-8", errors="replace") as f:
        html = f.read()
    p = TableParser()
    p.feed(html)
    return p.tables, html


def classify_rows(rows):
    """分出 表头/列名/隐藏本场/数据行。"""
    header, titlebg, hidden, data = [], [], [], []
    for tr in rows:
        cls = tr["class"]
        style = tr["style"].replace(" ", "").lower()
        if "tableh" in cls or "lstitle" in cls:
            header.append(tr)
        elif "titlebg" in cls:
            titlebg.append(tr)
        elif "jsThisMatch" in cls or "display:none" in style:
            hidden.append(tr)
        elif tr["data-matchid"] or tr["attr"]:
            data.append(tr)
        else:
            header.append(tr)  # 兜底算表头
    return header, titlebg, hidden, data


def tr_attr_tuple(tr):
    """tr.attr="主id,比分,客id" → (homeid, score, awayid)"""
    parts = tr["attr"].split(",")
    if len(parts) == 3:
        return parts[0].strip(), parts[1].strip(), parts[2].strip()
    tds = tr["tds"]
    if len(tds) >= 5:
        return tds[2]["attr"].strip(), tds[3]["attr"].strip(), tds[4]["attr"].strip()
    return "", "", ""


def gethomerecord(rows, returnid, away_view):
    """照抄网页 gethomerecord（跳过 display:none 与比分为'-'的行）。"""
    victory = failure = draw = 0
    homevictory = homefailure = homedraw = 0
    totalHome = totalAway = 0
    detail = []
    for tr in rows:
        style = tr["style"].replace(" ", "").lower()
        if "display:none" in style:
            continue
        attr = tr["attr"]
        parts = attr.split(",")
        if len(parts) == 3:
            recordval = parts[1]
        else:
            recordval = tr["tds"][3]["attr"] if len(tr["tds"]) > 3 else "-"
        if recordval == "-":
            continue
        if "-" not in recordval:
            continue
        hs, as_ = recordval.split("-")[0], recordval.split("-")[-1]
        try:
            homescore, awayscore = int(hs), int(as_)
        except ValueError:
            continue
        tds = tr["tds"]
        homeid = tds[2]["attr"].strip() if len(tds) > 2 else ""
        awayid = tds[4]["attr"].strip() if len(tds) > 4 else ""
        checkscore = None
        if returnid == homeid:
            checkscore = homescore - awayscore
            if checkscore > 0:
                homevictory += 1
            elif checkscore < 0:
                homefailure += 1
            else:
                homedraw += 1
            totalHome += homescore
            totalAway += awayscore
        elif returnid == awayid:
            checkscore = awayscore - homescore
            totalHome += awayscore
            totalAway += homescore
        if checkscore is None:
            continue
        if checkscore > 0:
            victory += 1
        elif checkscore < 0:
            failure += 1
        else:
            draw += 1
        detail.append((recordval, homeid, awayid, "胜" if checkscore > 0 else ("负" if checkscore < 0 else "平")))
    if not away_view:
        html_s = f"{victory}胜{draw}平{failure}负，主场{homevictory}胜{homedraw}平{homefailure}负，进{totalHome}球失{totalAway}球"
    else:
        html_s = (f"{victory}胜{draw}平{failure}负，"
                  f"客场{victory - homevictory}胜{draw - homedraw}平{failure - homefailure}负，"
                  f"进{totalHome}球失{totalAway}球")
    return html_s, detail


def main():
    tables, html = load_rows()
    m = re.search(r"host_team_id\s*=\s*'([^']+)'", html)
    home_id = m.group(1) if m else ""
    m = re.search(r"guest_team_id\s*=\s*'([^']+)'", html)
    away_id = m.group(1) if m else ""
    print(f"host_team_id={home_id} guest_team_id={away_id}")

    results = {}
    for key, tid, away_view in (
        ("homecomp", home_id, False),
        ("awaycomp", away_id, True),
        ("vscomp", home_id, False),
    ):
        rows = tables.get(key, [])
        header, titlebg, hidden, data = classify_rows(rows)
        print(f"\n=== {key} ===")
        print(f"tr总数={len(rows)} 表头行={len(header)} 列名行={len(titlebg)} "
              f"隐藏本场行={len(hidden)} 数据行={len(data)}")
        for tr in hidden:
            print(f"  [跳过] class={tr['class']!r} matchid={tr['data-matchid']} attr={tr['attr']!r}")
        summary, detail = gethomerecord(rows, tid, away_view)
        print(f"summary({key}) = {summary}")
        results[key] = (data, summary, detail)
        # 抽查前2行5列
        for tr in data[:2]:
            tds = tr["tds"]
            league = re.sub(r"\s+", "", tds[0]["text"])
            date = re.sub(r"\s+", "", tds[1]["text"])
            home = re.sub(r"\s+", "", tds[2]["text"])
            score = tds[3]["attr"].strip()
            away = re.sub(r"\s+", "", tds[4]["text"])
            pan = re.sub(r"\s+", "", tds[12]["text"]) if len(tds) > 12 else "?"
            print(f"  抽查: 联赛={league} | 时间={date} | 主队={home} | 比分={score} | 客队={away} | 赢盘={pan}")

    # 期望值（实现者自述）
    exp = {
        "homecomp": (10, "8胜1平1负，主场4胜0平0负，进22球失2球"),
        "awaycomp": (10, None),
        "vscomp": (10, None),
    }
    print("\n=== 期望核对 ===")
    ok = True
    for key in ("homecomp", "awaycomp", "vscomp"):
        data, summary, _ = results[key]
        exp_n, exp_s = exp[key]
        n_ok = len(data) == exp_n
        s_ok = exp_s is None or summary == exp_s
        print(f"{key}: 行数 {len(data)} (期望{exp_n}) {'PASS' if n_ok else 'FAIL'}; "
              f"summary {'与期望一致 PASS' if s_ok else ('FAIL 期望:' + str(exp_s))}")
        ok = ok and n_ok and s_ok
    print("OVERALL:", "PASS" if ok else "FAIL")


if __name__ == "__main__":
    main()
