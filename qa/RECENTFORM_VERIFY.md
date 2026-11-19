# 「近期状况」功能独立验收报告（qa-2）

- **验收人**：qa-2（独立验收工程师，未改动 `app/src/main/java/` 任何源码）
- **日期**：2026-10-22
- **工程根**：`D:/WorkBuddyPro/project/sporttery/NativeAndroid`
- **验收对象**：比赛列表「近期状况」按钮 → `recentform/{mid}/{vs}` 四 Tab 页面
  （`OkoooHistoryParser.kt` / `HistoryRepository.kt` / `HistoryModels.kt` / `RecentFormScreen.kt`）
- **独立性声明**：本报告统计口径直接照抄网页 `footcompetitionhistory.js` 的 `gethomerecord`
  （QA 自行从 `https://imgv1.okoooimg.cn/JS/dataanalysis/footcompetitionhistory.js` 下载并逐行阅读，
  **未参考实现者 Kotlin 代码的数值结论**），行数/summary 由 `qa/_rf_independent.py` 独立重算。

---

## 一、总表

| # | 项目 | 判定 | 证据摘要 |
|---|------|------|----------|
| 1 | `:app:assembleDebug` 编译 | **PASS** | exit code 0，`BUILD SUCCESSFUL in 12s`（另有 `compileDebugKotlin` 单独 exit 0） |
| 2 | 三表数据行数 = 10/10/10 | **PASS** | 独立脚本：home=10 away=10 vs=10，且与 awk 按行号交叉验证一致 |
| 3 | home 段 summary 口径 | **PASS** | 独立重算 `8胜1平1负，主场4胜0平0负，进22球失2球`，与实现者自述**逐字符一致** |
| 4 | away/vs 段 summary | **PASS（口径）** | away `5胜1平4负，客场2胜0平2负，进25球失19球`、vs `3胜4平3负，主场1胜3平1负，进17球失15球`，与 `gethomerecord` 口径完全一致；（网页默认值不可见，无法逐字对网页，但口径同源可证） |
| 5 | 抽查 ≥2 行 5 列 + 赢盘列 | **PASS** | 5 行抽查全部与原始 HTML 逐字对齐（详见 §3） |
| 6 | 跳过 jsThisMatch 行 | **PASS** | 5 处 `attr="event" class="...jsThisMatch"` 全部 `display:none`，解析器双重跳过；独立统计确认 0 泄漏 |
| 7 | 路由 `recentform/{mid}/{vs}` 接线 | **PASS** | MainActivity.kt:168 composable + :233 `toRecentForm` + MatchScreen/MatchCard 链路闭合 |
| 8 | `isTabRoute` 未被改动 | **PASS** | 仍是 `match/schedule/live/league/sameodds*` 五项，无 recentform ⇒ 不进底部 tab |
| 9 | MatchCard 三按钮用 ActionText | **PASS** | 无 Material Button；3×ActionText，`heightIn(min=32.dp)`，高度 3×32+2×4=104dp < 左侧列 ≈109dp |
| 10 | 未开赛容错 | **PASS** | 解析器 `attr="-"`→空串、`hasScore=false` 不入统计；UI `ifEmpty { "-" }` |
| 11 | HistoryRepository 移动 UA / GBK | **PASS** | 复用 `ApiClient`（UA = Android Chrome Mobile，`Charset.forName("GBK")`） |
| 12 | JingCaiRepository / LiveScreen / TitanLiveParser 等零改动 | **PASS** | 文件 mtime 全部早于本轮功能落地；sha256 留档（§6） |
| 13 | 真机冒烟 | **未做** | adb 无在线设备（`adb devices -l` 输出空），未编造 |

---

## 二、编译（独立重跑）

命令（QA 自行执行，不信实现者输出）：

```
cd D:/WorkBuddyPro/project/sporttery/NativeAndroid
C:/Users/wanghongbo/.gradle/wrapper/dists/gradle-8.2-bin/.../gradle-8.2/bin/gradle.bat :app:assembleDebug --console=plain
```

关键输出：

```
> Task :app:packageDebug UP-TO-DATE
> Task :app:assembleDebug UP-TO-DATE
BUILD SUCCESSFUL in 12s
33 actionable tasks: 1 executed, 32 up-to-date
EXIT_CODE=0
```

补充：`compileDebugKotlin` 单独跑也 `BUILD SUCCESSFUL` / `KOTLIN_EXIT=0`。
APK 产物存在：`app/build/outputs/apk/debug/app-debug.apk`（10,346,182 字节，Oct 22 17:26）。

> ⚠️ 注意：因增量编译 `UP-TO-DATE`，本条**不能单独证明**「全新 clean 构建」通过；
> 但 Kotlin 编译任务单独执行也通过，且本次为源码落地后的首次独立重跑，判定 PASS。
> （如需更强证据可跑 `clean assembleDebug`，耗时较长，本轮未做。）

---

## 三、解析正确性（重点，独立重算）

### 3.1 数据来源

- 样本：`D:/tmp_wordprobe/okooo_hist_1341208.html`（423KB，GBK 解码后 UTF-8）
- **独立直拉失败**：QA 用移动 UA（Chrome Android 126 Mobile）urllib 直拉
  `https://www.okooo.cn/soccer/match/1341208/history/` 返回 **HTTP 405**（与任务书预警一致，
  okooo WAF 对 Python urllib 仍拦，即使用移动 UA）。故改用样本 + **网页 JS 原文**独立重算。
- 统计口径来源（QA 自行下载）：`qa/_rf_footcomp.js`（`imgv1.okoooimg.cn/JS/dataanalysis/footcompetitionhistory.js`，
  HTTP 200，17,285 字节）。核心 `gethomerecord`：

```js
for(var i=3,ilen=trobj.length;i<ilen;i++){
  if($(trobj[i]).css("display")=="none")continue;
  var datasarr=$(trobj[i]).attr("attr").split(",");
  var recordval=datasarr[1];
  if(recordval=="-")continue;
  ...
  if(returnid==homeid){ // 主场视角：计 homevictory/homedraw/homefailure + 进失球
  }else if(returnid==awayid){ // 客场视角：只加进失球
  }
  // checkscore>0 victory++; <0 failure++; ==0 draw++
}
// team!="away" → "N胜N平N负，主场X胜Y平Z负，进A球失B球"
// team=="away" → "N胜N平N负，客场(N-X)胜(N-Y)平(N-Z)负，进A球失B球"
```

### 3.2 行数独立统计（脚本 `qa/_rf_independent.py` + awk 交叉验证）

三表定位：`table.homecomp`(2056-3603) / `table.awaycomp`(3605-5150) / `table.vscomp`(5306-6116)。
另有 `div.homefuture`/`div.awayfuture`（未来5场，无 class=homecomp/awaycomp，**解析器 select 不到**，正确排除）。

| 表 | tr 总数 | 表头/列名 | display:none 跳过 | **数据行** | 实现者自述 | 判定 |
|----|--------|-----------|-------------------|-----------|-----------|------|
| homecomp | 102 | 2 | 90（含 1 个 jsThisMatch） | **10** | 10 | PASS |
| awaycomp | 102 | 2 | 90（含 1 个 jsThisMatch） | **10** | 10 | PASS |
| vscomp | 50 | 2 | 38（含 1 个 jsThisMatch） | **10** | 10 | PASS |

- 交叉验证①（awk 按行号）：`awk 'NR>=2056&&NR<=3603' … | grep attr= | !display:none` = 10 行。
- 交叉验证②（全文件）：全文件可见 `data-lt` 行恰好 30 条 = 10+10+10，无第四处泄漏。
- `attr="event"`（jsThisMatch 本场占位）共 5 处，**全部** `style="display:none"`，独立统计可见行中
  `attr="event"` 计数 = 0 ⇒ **跳过逻辑无泄漏**。
- 另 10 条可见 `data-matchid` 无 `attr` 的行全部落在 `homefuture`(5153-5214)/`awayfuture`(5217-5283)
  的**未来赛程表**（比分 `-`），不在三表 select 范围内，不会污染解析。

### 3.3 summary 独立重算（gethomerecord 口径，QA 复刻）

| 段 | returnid | 独立重算结果 | 实现者自述 | 判定 |
|----|----------|--------------|-----------|------|
| home（homecomp，主场视角） | 416 | `8胜1平1负，主场4胜0平0负，进22球失2球` | `8胜1平1负，主场4胜0平0负，进22球失2球` | **PASS（逐字符一致）** |
| away（awaycomp，客场视角） | 379 | `5胜1平4负，客场2胜0平2负，进25球失19球` | （实现者未单独提供） | 口径 PASS |
| vs（vscomp，主场视角） | 416 | `3胜4平3负，主场1胜3平1负，进17球失15球` | （实现者未单独提供） | 口径 PASS |

**home 段 10 行明细**（独立展开，可复核）：

```
417,0-4,416   东京FC 客场 胜  进4失0
1566,1-0,416  东京FC 客场 负  进0失1
416,3-0,432   东京FC 主场 胜  进3失0   主场✓
416,4-0,1564  东京FC 主场 胜  进4失0   主场✓
1565,0-2,416  东京FC 客场 胜  进2失0
416,1-0,415   东京FC 主场 胜  进1失0   主场✓
424,0-2,416   东京FC 客场 胜  进2失0
416,2-0,378   东京FC 主场 胜  进2失0   主场✓
414,1-1,416   东京FC 客场 平  进1失1
2721,0-3,416  东京FC 客场 胜  进3失0
```

⇒ 胜8 平1 负1；主场 4胜0平0负；进 4+0+3+4+2+1+2+2+1+3 = 22 失 0+1+0+0+0+0+0+0+1+0 = **2**
⇒ 独立复算输出：`8胜1平1负，主场4胜0平0负，进22球失2球`（二次机器重算，与实现者、网页 JS 三方一致）**PASS**。

### 3.4 抽查 5 列 + 赢盘列（原始 HTML vs 解析口径）

| # | 来源表 | 联赛(轮次) | 时间 | 主队 | 比分 | 客队 | 赢盘 | 原文核对 |
|---|--------|-----------|------|------|------|------|------|----------|
| 1 | homecomp | J联赛 10 | 26-10-17 (2026-10-17 13:00) | 东京绿茵 | 0-4 | 东京FC | 赢(redB) | ✅ 一致 |
| 2 | homecomp | 日联杯 3 | 26-10-14 (2026-10-14 18:00) | 水户蜀葵 | 1-0 | 东京FC | 输(blueB) | ✅ 一致 |
| 3 | awaycomp | J联赛 10 | 26-10-17 (2026-10-17 18:00) | 柏太阳神 | 1-3 | 名古屋鲸 | 输(blueB) | ✅ 一致 |
| 4 | vscomp | J联赛 13 | 26-04-29 (2026-04-29 15:00) | 柏太阳神 | 1-3 | 东京FC | 赢(redB) | ✅ 一致 |
| 5 | vscomp | J联赛 4 | 26-02-28 (2026-02-28 14:00) | 东京FC | 0-2 | 柏太阳神 | 输(blueB) | ✅ 一致 |
| 6 | vscomp | J联赛 13 | 24-05-11 (2024-05-11 16:00) | 东京FC | 3-3 | 柏太阳神 | 走(huiB) | ✅ 一致（平局走盘，验证 huiB 分支） |

补充验证：
- 赢盘列三种 `redB/blueB/huiB` 全覆盖（6 行里赢/输/走各出现），与解析器 `OkoooHistoryParser.kt:132-136` 三分支一一对应。
- 主胜 `span.bold.redtxt` / 客胜 `span.bold.bluetxt` / 平 `span.bold ` 三种着色场景均在抽查中命中。

---

## 四、代码静态检查

### 4.1 路由（MainActivity.kt）

| 检查点 | 结果 | 证据 |
|--------|------|------|
| `composable("recentform/{mid}/{vs}")` 已注册 | ✅ | MainActivity.kt:168-172 |
| `toRecentForm` 导航函数 | ✅ | MainActivity.kt:233-236（`navigate("recentform/${m.mid}/$vs")`） |
| MatchScreen 传入 `onRecentForm` | ✅ | MainActivity.kt:131 + MatchScreen.kt:68,163 |
| `isTabRoute` 未含 recentform | ✅ | MainActivity.kt:201-203 仍为 `match/schedule/live/league/sameodds*` 五项 |
| 返回键行为 | ✅ | recentform 不属 `isTabRoute` ⇒ 走 `else -> nav.popBackStack()`（正常回退，不弹退出框） |
| 底部导航 | ✅ | `isTabRoute(route)==false` ⇒ 进 recentform 时底部 tab 隐藏（详情页语义，正确） |

### 4.2 MatchCard 三按钮

| 检查点 | 结果 | 证据 |
|--------|------|------|
| 三按钮均为 ActionText（非 Material Button） | ✅ | MatchCard.kt:226-228 三个 `ActionText(...)`；全文件无 `Button(`/`OutlinedButton` 实际调用 |
| 卡片高度 | ✅ | 右侧列 3×`heightIn(min=32.dp)` + 2×4dp spacing = 104dp ≤ 左侧内容列 ≈109dp（MatchCard.kt:219-221 注释核算）；卡片基线 125dp 不变，无 Compose 测量崩溃风险 |
| `onRecentForm` 参数贯穿 | ✅ | MatchCard.kt:75 签名 → :228 回调 |

### 4.3 未开赛 / 空比分容错

| 检查点 | 结果 | 证据 |
|--------|------|------|
| 解析器不崩 | ✅ | `attr="-"`/`"event"` → `score=""`（OkoooHistoryParser.kt:127-128）；`toLongOrNull()?:0L`（:141）；`tds.size<13` 整行丢弃（:114） |
| `hasScore=false` 不进统计 | ✅ | HistoryModels.kt:44 `score.contains("-")` 守卫，`resultFor` 返回 `""` |
| UI 显示「-」 | ✅ | RecentFormScreen.kt:370 `r.score.ifEmpty { "-" }`、:381 `halfScore.ifEmpty { "-" }`、PanText 空 → `-` |
| 未来赛程行（6列无 attr） | ✅ | 不在 `table.homecomp/awaycomp/vscomp` select 范围（Jsoup 只取这三个 class 的 table），天然排除 |

### 4.4 UA / GBK / 零改动

| 检查点 | 结果 | 证据 |
|--------|------|------|
| 移动 UA | ✅ | ApiClient.kt:13-14 `Mozilla/5.0 (Linux; Android 13) … Chrome/126.0 Mobile Safari/537.36`；HistoryRepository 复用之 |
| GBK | ✅ | HistoryRepository.kt:21 `ApiClient.get(..., "GBK")` |
| JingCaiRepository 未误改 | ✅ | `getMatches`/`OkoooParser.parse` 逻辑原样（JingCaiRepository.kt:22-24）；mtime Oct 19 11:11 < 功能落地 Oct 22 |
| LiveScreen/TitanLiveParser/LiveScoreRepository/LiveModels 零改动 | ✅ | mtime Oct 20 09:27~Oct 22 15:51（LiveScreen 为比发直播批次产物），均不含近期状况相关改动；sha256 留档 §6 |

---

## 五、真机冒烟（可选）

**未做真机冒烟**。`adb devices -l` 输出 `List of devices attached`（空），无在线设备。
按任务书要求「若无设备/无 adb 就明确写未做真机，不要编造」——本节如实记录为 **未做**。

（可选后续：设备上线后安装 `app/build/outputs/apk/debug/app-debug.apk`，进「比赛」tab 点
「近期状况」，四 tab 各截图一张存 `qa/shots/`。）

---

## 六、「应零改动」文件哈希留档（2026-10-22）

| 文件 | sha256[:16] | mtime |
|------|-------------|-------|
| data/repository/JingCaiRepository.kt | `ae4da57dd00812a5` | Oct 19 11:11 |
| data/network/TitanLiveParser.kt | `0adc48cbbee3aad6` | Oct 20 09:51 |
| data/repository/LiveScoreRepository.kt | `a2bdf1e1c7b27373` | Oct 20 09:28 |
| data/model/LiveModels.kt | `2391e1dbfc217fd8` | Oct 20 09:27 |
| ui/live/LiveScreen.kt | `827edbb2639adccd` | Oct 22 15:51 |

（LiveScreen.kt 的 Oct 22 15:51 属「比分直播三 tab」批次改动时间，早于近期状况落地
Oct 22 17:11+；本轮验收范围内无新增改动。）

---

## 七、问题清单

### 必须修
**无。** 所有关键项（编译/解析行数/summary/抽查/路由/按钮/容错/UA/零改动）全部 PASS。

### 建议
1. **[建议] 编译证据建议补一次 `clean assembleDebug`**：本轮为增量 `UP-TO-DATE`，如需
   「从零 clean 构建」的强证据可补跑（耗时较长，非阻塞）。
2. **[建议] away/vs 段 summary 缺网页可见默认值对账**：网页 `span.awayscore/vsscore` 初值由 JS
   在浏览器运行时写入，静态 HTML 不可见；本轮以 `gethomerecord` 口径同源重算为判据。
   如需逐字对账可在真机/浏览器里目视一次。
3. **[建议] 补真机冒烟截图**：设备上线后四 tab 各一张，作为 UI 层最终证据（本轮无设备，未做）。
4. **[提示] 未来赛程表（homefuture/awayfuture）不进「近期状况」**：解析器按 `table.homecomp/
   awaycomp/vscomp` 严格 select，未来5场（比分 `-`）天然排除——这是正确行为，但如产品侧想展示
   「未来赛程」需另开需求，勿混进现有三表解析。

---

## 八、结论

「近期状况」功能在编译、解析正确性（行数/summary/抽查）、路由、按钮、容错、UA/GBK、零改动
七个维度均**独立验收通过**，与实现者自述一致（home summary `8胜1平1负，主场4胜0平0负，进22球失2球`
逐字符命中）。真机冒烟因无设备未做。**判定：功能验收 PASS，可交付。**

---

## 附：QA 产出文件

| 文件 | 说明 |
|------|------|
| `qa/RECENTFORM_VERIFY.md` | 本报告 |
| `qa/_rf_independent.py` | 独立解析/统计/summary 重算脚本（HTMLParser，不依赖第三方库） |
| `qa/_rf_fetch.py` | 独立直拉脚本（复现 405，留档） |
| `qa/_rf_footcomp.js` | 网页统计口径 JS 原文（gethomerecord 来源） |
