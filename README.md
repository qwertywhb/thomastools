# 竞彩数据 App（原生 Android · Kotlin + Jetpack Compose + MVVM）

原生 Android 实现，包含：比赛列表、同奖查询、赔率变化、足球赛事资料 四大功能。

## 技术栈
- **UI**：Jetpack Compose + Material 3（底部导航 + 卡片 + 蓝紫渐变主题，涨红跌绿）
- **架构**：MVVM（ViewModel + StateFlow + SavedStateHandle）
- **网络**：OkHttp（统一 UA + Referer，okooo 自动 GBK 解码）
- **解析**：Jsoup（HTML）+ org.json（JSON）

## 目录结构
```
app/src/main/java/com/example/jingcai/
├── MainActivity.kt                 # 入口 + Navigation Compose 导航 + 底部 Tab
├── data/
│   ├── model/Models.kt             # 数据模型
│   ├── network/ApiClient.kt        # OkHttp 封装（UA/Referer/GBK）
│   ├── network/OkoooParser.kt      # okooo 页面解析（Jsoup）
│   └── repository/JingCaiRepository.kt  # 所有接口统一入口
└── ui/
    ├── theme/Theme.kt              # Material3 主题配色
    ├── Common.kt                   # 加载/错误/格式化组件
    ├── ViewModels.kt               # 4 个 ViewModel（MVVM）
    ├── match/MatchScreen.kt        # 比赛列表
    ├── sameodds/SameOddsScreen.kt  # 同奖查询
    ├── oddschange/OddsChangeScreen.kt # 赔率变化
    └── league/LeagueScreen.kt      # 赛事资料
```

## 数据源（全部网络请求已实现）
| 功能 | 接口 |
|---|---|
| 比赛列表 | `https://www.okooo.cn/jingcai/{date}/`（GBK） |
| 同奖查询 | `webapi.sporttery.cn/.../searchOddsV1.qry` |
| 赔率变化 | `webapi.sporttery.cn/.../getFixedBonusV1.qry`（先经 `getMatchCalculatorV1` 映射编号→matchId） |
| 赛事资料 | `.../league/getLeagueListV1` + `getMatchResultV1` + `getTablesV2` |

## 如何构建（Android Studio）
1. 安装 [Android Studio](https://developer.android.com/studio)（含 SDK 34）
2. **File → Open** 打开本目录 `NativeAndroid/`
3. 首次打开自动下载 Gradle 8.2 + 依赖（需联网，几分钟）。若提示 wrapper 缺失，选「Use default Gradle wrapper」或直接 Sync
4. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. 产物：`app/build/outputs/apk/debug/app-debug.apk`，拷到手机安装

> 或连手机（开 USB 调试）点绿色 ▶ 直接运行。

## 说明
- minSdk 24 / targetSdk 34 / compileSdk 34
- 体彩接口需 `Referer: https://www.sporttery.cn/`（已在 ApiClient 统一处理）
- okooo 页面是 GBK 编码（ApiClient 对 okooo 域名自动 GBK 解码）
- 涨跌标志 hf/df/af、status 等字段接口返回类型不稳定（字符串/数字），已用 `optString().toIntOrNull()` 容错
