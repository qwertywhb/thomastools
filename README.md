# 数据 App（原生 Android · Kotlin + Jetpack Compose）

个人用的比分工具：足球与篮球比分直播、模拟投注与账单、赔率变化、同奖查询、赛事资料。

> 本仓库**同时承载源码与自动更新**：App 检查更新走本仓库的 Releases
> （仓库名取自 `data/update/UpdateConfig.kt` 的 `OWNER` / `REPO`），
> 发版由 [scripts/release.py](scripts/release.py) 一条命令完成
> 「自增版本 → 构建 → 上传 APK + update.json」。
> 代码和安装包永远在同一个项目下，不需要另开仓库或服务器。

## 功能

| 模块 | 说明 |
|---|---|
| 比分直播 | 足球（bfdata，全量 + 增量补丁）／篮球（球米宝），支持关注、进球提醒、桌面小组件 |
| 模拟投注 | 竞彩足球与篮球玩法，投注单、过关方式、倍数、开奖结算；账单长按可复制投注内容 |
| 赔率变化 | 体彩固定奖金变化曲线 |
| 同奖查询 | 按赔率组合反查历史比赛 |
| 赛事资料 | 联赛列表、比赛结果、积分榜 |
| 近期状况 | okooo 历史战绩（含赢盘） |
| 自动更新 | GitHub Releases 检查 + 应用内下载安装 |

## 技术栈

- **UI**：Jetpack Compose + Material 3，自绘图表，涨红跌绿
- **架构**：MVVM（ViewModel + StateFlow）
- **网络**：OkHttp（统一 UA / Referer，okooo 自动 GBK 解码）
- **解析**：Jsoup（HTML）+ Gson（JSON、增量补丁）
- minSdk 24 / targetSdk 34 / compileSdk 34，Kotlin 1.9.22 / Compose BOM 2024.02

## 目录结构

```
app/src/main/java/com/example/jingcai/
├── MainActivity.kt              # 入口 + Navigation Compose
├── data/
│   ├── model/                   # 数据模型（比分、赔率、投注、结算）
│   ├── network/                 # 各数据源解析器（titan / 球米宝 / 体彩 / okooo）
│   ├── repository/              # 数据入口、注单存储、快照
│   ├── settle/                  # 玩法判定与结算（让分、大小分、过关）
│   └── update/                  # GitHub Releases 自动更新
├── ui/
│   ├── live/                    # 比分直播（足球 / 篮球）
│   ├── bet/                     # 模拟投注、账单、分享文案
│   ├── livedetail/              # 比赛详情
│   ├── match/ sameodds/ oddschange/ league/ schedule/
│   └── analysis/ recentform/ update/
├── widget/                      # 桌面小组件
└── scripts/release.py           # 一键发版
```

## 数据源

| 功能 | 接口 |
|---|---|
| 足球比分 | `livestatic.titan007.com/vbsxml/bfdata_ut.js`（全量）+ `change.xml`（增量） |
| 篮球比分 | `matchs.qiumibao.com/live/all.htm` |
| 竞彩赔率/赛果 | `webapi.sporttery.cn/gateway/jc/...`（需 `Referer: https://www.sporttery.cn/`） |
| 近期状况 | `okooo.cn/soccer/match/{mid}/history/`（GBK，需移动端 UA） |

## 构建

1. 安装 Android Studio（含 SDK 34）
2. 打开本目录，首次会自动下载 Gradle 8.2 与依赖
3. 本项目没有提交 `gradlew`，命令行构建请用本机 gradle：
   ```
   gradle assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
   gradle assembleRelease    # app/build/outputs/apk/release/app-release.apk
   ```
   或在 Android Studio 里 `Build → Build Bundle(s) / APK(s) → Build APK(s)`

`local.properties`（SDK 路径）、`build/`、`.gradle/`、签名文件都不入库。

## 发版（自动更新的唯一正确姿势）

```bash
# 1) 自增 versionCode、构建、创建 Release 并上传 APK + update.json
python scripts/release.py --changelog "1. 修复篮球主客反了 2. 账单长按复制"

# 只改了排版、不想动版本号（客户端靠 sha256 识别「同版本新包」）
python scripts/release.py --changelog "微调间距" --no-bump

# 只本地验证，不上传
python scripts/release.py --changelog "预览" --dry-run
```

`GITHUB_TOKEN` 未设置时脚本会复用本机 `git credential` 里保存的 GitHub 凭据，不会把 token 写入任何文件。

### 三条硬约定（少一条就会出现「传了新包却不提示」）

1. **Tag 必须是 `v` + `versionCode`**（如 `v23`）。客户端拿整数比版本；`versionName` 是字符串，`1.10` 会被判成比 `1.9` 旧。
2. **`update.json` 必须和 APK 一起挂**，且 `versionCode` 与 APK 一致。历史上出过「v9 的 Release 挂了 v8 的包和 v8 的 json」，客户端始终认为自己是最新。
3. **同版本重打包时，资产内容必须变**。客户端会比对 APK 的 SHA-256，同 `versionCode` 但哈希不同会提示「发现新的安装包」。

脚本已把这三条写死在流程里：`update.json` 与 APK 由同一次运行生成，SHA-256 现算，产物若早于本次构建开始时间直接中止。

