# 多布局看板引擎设计（H0）

> 任务：H0「调研 + 设计文档」。
> **本任务只改 `docs/`，不写 App 代码、不改源码、不提交 git、不 push。**
> 设计目标：在现有「单 Activity / 单标的 / 单图」之外，引入一套**可递归分割的布局树**，
> 每个叶子格（Slot）可以放任意行情模块，并提供 14 种现成布局与自定义分割。
>
> 代码基线：`main` @ `5aee8cf`（v1.2：加密币/美股搜索切换、31 个可开关技术指标、多窗格图表）
> 调研/核对日期：2026-11-18
> 文档状态：替换此前中断留下的半成品；已保留并复核其调研结论，修正了与当前代码不一致的部分。

---

## 需求对照

| 任务书要求 | 本文位置 |
|---|---|
| a. ≥15 个真实项目/产品样本 + 模式归纳 | §2（31 个样本，其中 13 个 GitHub 仓库已用 GitHub API 核实存在） |
| b. 各 layout 的分割树、各格模块、1920×1080 ASCII 线框图、720p/4K 缩放 | §3、§4 |
| c. 模块清单、数据来源/刷新频率、OKX 公共 REST/WS 订阅与限流预算 | §5 |
| d. LayoutNode(Split/Leaf)、Slot、LayoutSpec、JSON 持久化、设置交互 | §6 |
| e. 遥控器：D-pad 焦点、OK 全屏、MENU 换标的、BACK、◀▶ 切周期 | §7 |
| f. 性能与稳定性风险（多 Canvas、WS 帧率、内存、烧屏）与对策 | §8 |
| g. 实施拆分、共享文件冲突点与建议顺序 | §9、§10 |

---

## 0. 结论摘要

1. **布局用树表达，不写死行列。** 根是 `LayoutNode`：`Split{direction, weights, children}` 或
   `Leaf{Slot}`。运行时按权重和 gutter 递归算出每个叶子的像素矩形；预设和自定义布局共用同一套引擎。
2. **14 种预置布局**覆盖任务书 ①–⑭；默认仍是 **L01 经典单图**，老用户升级后界面/按键完全不变
   （`presetId="classic"` 直接走现有 `TvScreenInner`，不进入 Board 引擎）。
   另附 2 种可选扩展（三栏工作台、对比叠加），不占用 v1 范围。
3. **模块清单严格按任务书收敛为 7 类**：`MAIN_CHART`、`MINI_CHART`、`PRICE_CARD`、`STAT_TILE`、
   `WATCHLIST`、`MOVERS`、`TICKER_STRIP`。4 宫格 K 线不是新模块，而是 `MAIN_CHART` 的
   `detail=COMPACT` 级别；全屏时才升为 `FULL`。这样既满足「4 个标的 K 线」，又避免 4 份指标引擎。
4. **同屏多标的不开多条连接。** 新增 `QuoteHub`：一条 OKX 公共 WS 连接，一条 subscribe 消息里放
   N 个 `instId`（N≤12）；UI 侧按聚焦格 120ms、非聚焦格 250–500ms 合并推送。
   K 线走 REST，按 `(instId, tf)` 去重、并发上限 4、全局令牌桶；迷你图只拉 40–60 根。
5. **大盘数据已落地。** `data/overview/` 已有 `MarketOverview`、`OverviewRepository`
   （`overview: StateFlow`、`refresh(force)`、`overviewFlow(intervalMs)`），默认 TTL：
   全局 120s、稳定币 600s、历史 30min；带文件缓存、主备源和失败退避。本设计只消费它，不重复实现。
6. **性能红线**：整屏最多 1 个 `FULL` 图表（带指标/十字光标/完整坐标）；4 宫格用 4 个 `COMPACT`；
   6/9 宫格只画 `PRICE_CARD`/`MINI_CHART`，不画 K 线。目标是 1080p 下 30fps 以上 UI、
   数据 4–8fps；9 宫格不因 9 个 Canvas 掉到个位数帧率。
7. **遥控器必须显式建模。** 焦点是「叶子格 → 格内列表」两层状态机；焦点环由 Board 父层统一画；
   `OK` 全屏、`MENU` 换标的、`BACK` 逐级返回、`◀▶` 只作用于当前聚焦的图表格。
8. **最大风险不是数据量，而是整合面**：`Screen.kt`（1048 行）、`MarketViewModel.kt`（671 行）、
   `UiState`/`UiActions`/`Prefs` 都是单会话假设。实施拆分必须先冻结接口，再由**唯一整合 owner**
   改这些文件；其余任务只新增文件，避免并行改同一份大文件。

---

## 1. 现状盘点（代码事实）

### 1.1 当前界面结构

`ui/Screen.kt:88` 的 `TvScreenInner(st, act)` 把整屏写死为一条纵向流（`Screen.kt:141–154`）：

```
BoxWithConstraints
└── Column
    ├── Header        标的图标 + 名称/数据源 + 连接徽章 + 时钟
    ├── PriceBlock    大价格 + 涨跌 pill + 24H 高/低/量
    ├── ChartCard     PriceChart（weight(1f)，占满剩余高度）
    └── Footer        周期 chips + 缩放提示 + 按键提示
```

- 缩放系数：`s = (maxHeight.value / 540f).coerceIn(0.72f, 2.6f)`（`Screen.kt:125`）。
  `maxHeight` 是 Compose 的 **Dp**，不是物理像素；Android TV 常见 540dp 逻辑高度，此时 `s≈1.0`，
  物理 720p/1080p/4K 由 density 负责像素缩放。设计里不要假设「1080p 一定 s=2」。
- 设置与搜索是覆盖层：`SettingsOverlay`（`Screen.kt:513`，private）、`SearchOverlay`
  （`ui/SearchOverlay.kt:69`，public）。打开时根 `Box` 的 `focusProperties { canFocus = false }`
  （`Screen.kt:112`），按键全部交给面板。
- 防烧屏：`antiBurn` 打开时整棵内容树按 `sin(clock/...)` 做约 ±5px / ±3.5px 的慢漂移
  （`Screen.kt:126–127`）。

### 1.2 数据与状态

- `data/Model.kt:62` 的 `TF` **已经包含 `W1`**（OKX bar=`1Wutc`），所以「同币多周期 1m/1H/1D/1W」
  不需要新增枚举；`TF.step()` 可用于周期切换。
- `UiState`（`Model.kt` 末尾）只有一套行情字段：`instrument`、`tf`、`candles`、`price`、`quote`、
  `indicatorSettings`、`indicatorResults`、`searchOpen`、`favorites` 等。
- `MarketViewModel`（`MarketViewModel.kt:46`）一个会话只服务一个标的：
  - `startSession()` / `runSession()`（:105、:128）拉历史 K 线 + 首次报价；
  - 20s 重拉一次 K 线（:169）；
  - 单标的 WS（`client.liveStream(...)`，:187）；
  - `publishLoop()`（:208）把 WS 高频推送压到约 **120ms（8fps）**，并修补最后一根 K 线；
  - `setInstrument()`（:483）直接替换全局唯一标的并重开会话。
- 指标引擎按单标的单份结果计算：`IndicatorEngine.compute(settings, candles, revision)`；
  结果放进 `UiState.indicatorResults`，副图最多 3 个。
- `MarketProvider` 的 `liveStream(inst)` 是「一个连接一个标的」：OKX 的 `spec(inst)` 只拼一个
  `instId`（`data/MarketProviders.kt:88`、`data/OkxProvider.kt:67–72`）。多标的需要新增复用层，
  不能直接为 9 个格子开 9 条连接。
- 搜索选中后走 `act.selectInstrument(inst)`（`SearchOverlay.kt:413`），最终到
  `MarketViewModel.setInstrument()`；没有「目标槽位」概念。
- `Instrument` 的完整 JSON 序列化在 `data/InstrumentCatalog.kt:12`（`instrumentToJson`）和
  `:28`（`instrumentFromJson`）。布局里的固定标的必须存**完整 JSON**，不能只存 id
  （搜索结果可能不在内置目录，升级后只存 id 会丢）。

### 1.3 交互与设置

- `ui/KeyRouting.kt` 的 `routeKey()` 是唯一按键真源：
  - `◀▶` → `ChangeTf`；
  - `▲▼` → `Zoom`；
  - `MENU/SEARCH` → `OpenSearch`；
  - `OK/ENTER` → `OpenSettings`；
  - 设置/搜索打开时一律返回 `None`。
- `AppEntry.kt:14` 的 `TvScreen(vm)` 负责 `collectAsStateWithLifecycle` + `UiActions` 绑定；
  `BackHandler`（:20）处理搜索 → 展开指标 → 设置。
- `Prefs`（`Prefs.kt:18`）使用 `SharedPreferences("btc_tv")`：
  `instrument`、`tf`、`provider`、`style`、`flash`、`antburn`、`clock`、`volume`、`autostart`、
  `indicator_settings`、`recent_instruments`、`favorite_instruments`；**没有 layout 相关 key**。
- 设置面板是 `Screen.kt` 内的 private `SettingsOverlay`，垂直滚动；没有布局选择、没有槽位编辑。

### 1.4 大盘数据层（已存在，不重复实现）

`app/src/main/java/com/milolab/btctv/data/overview/` 已落地：

| 文件 | 作用 |
|---|---|
| `MarketOverview.kt` | `MarketOverview`（BTCD、ETH 占比、总市值、24h 变化、总成交量、稳定币市值、稳定币占比、源端更新时间、本地抓取时间、`stale`、三条历史序列）；`HistoryPoint(t, v)` |
| `OverviewRepository.kt` | `overview: StateFlow<MarketOverview?>`、`current()`、`cached()`、`refresh(force)`、`overviewFlow(intervalMs)`；内存 + 文件缓存、主备源、失败退避、本地采样 |
| `OverviewSources.kt` | CoinGecko / CoinPaprika / DefiLlama 解析与抓取；`SOURCE_*` 常量；`OverviewFetchException`（含 code/retryAfterMs） |
| `OverviewCache.kt` | 纯 Kotlin/JSON 落盘，`v=1`，含 backoff 与历史序列 |
| `OverviewAndroid.kt` | `createOverviewRepository(context)`，缓存文件 `filesDir/overview_cache.json` |
| 测试 | `preview/src/test/kotlin/OverviewParseTest.kt`、`OverviewRepositoryTest.kt` |

默认预算（构造参数）：全局 TTL **120s**、稳定币 TTL **600s**、历史 TTL **30min**、
本地采样间隔 **5min**、最多 500 个采样点、写盘节流 30s。
`MarketOverview.stale` 为 true 时 UI 必须保留旧值并显示「更新于 X 分钟前」，不能清空 tile。

### 1.5 对布局引擎的硬约束

| 约束 | 影响 |
|---|---|
| 一个 Activity + 一个根 `UiState` | 布局状态要么扩展 `UiState`，要么新建 `BoardState`；v1 建议扩展 `UiState`，但只让 BoardScreen 读 |
| 单标的 `liveStream` | 多图必须新增 `QuoteHub` 多路复用；不能 9 格 9 连接 |
| 指标只算一份 | 整屏最多 1 个 `FULL` 图表；其余 `COMPACT`/`MINI` |
| 搜索是全局替换 | 必须加「目标槽位」参数；经典布局继续走旧路径 |
| 按键全局拦截 | 必须引入 Board 模式机；否则会重演「遥控器控制下层」 |
| `TF` 已有 `W1` | 多周期布局可直接用；无需改 `Model.kt` |
| `SettingsOverlay` 是 private | 看板设置页应新建独立 overlay，避免和现有设置耦合 |
| `PriceChart` 已有完整绘制 | `FULL` 直接复用；`COMPACT`/`MINI` 新建组件，避免改 `Chart.kt` 引发冲突 |
| `overview` 已有缓存/退避 | StatTile 只读 `OverviewRepository`，不重复写网络逻辑 |

---

## 2. 样本调研

### 2.1 样本表（31 个真实项目/产品）

> 说明：链接均为公开入口；「一句话布局」只描述公开可见的布局特征。
> 其中 13 个 GitHub 仓库已于 2026-11-18 用 GitHub API 核实存在，并按其官方 description 修正了
> 此前半成品里夸大/不准确的描述（例如 OpenBook 是深度热力图，不是可停靠窗格）。
> 个别 Web 产品对无头访问返回 403/验证码，但入口本身是公开页面。

| # | 名称 | 链接 | 一句话布局描述 | 可借鉴点 |
|---:|---|---|---|---|
| 1 | TradingView | https://www.tradingview.com/features/ | 多图网格最多 16 图，常见 1/2/3/4/6/8/16 布局；符号/周期/画线可同步，右侧自选 | 多图网格 + 同步 + 自选 |
| 2 | Binance | https://www.binance.com/en/trade/BTC_USDT | 交易页四区：顶部交易对、左侧市场列表、中间主图、右侧订单簿/成交、底部下单/持仓 | 终端四区 + 主图聚焦 |
| 3 | OKX | https://www.okx.com/trade-spot/btc-usdt | 同构四区交易终端：主图居中，右侧订单簿/成交，底部下单/仓位 | 右侧信息栏宽度参考 |
| 4 | Bybit | https://www.bybit.com/trade/usdt/BTCUSDT | 同构四区：主图 + 订单簿/成交 + 仓位/下单 + 市场切换 | 主图与交易面板比例 |
| 5 | CoinMarketCap | https://coinmarketcap.com/ | 顶部全局指标条 + 热门/涨跌模块 + 宽幅币表（含 7 日迷你走势） | 指标条 + 宽表 + sparkline |
| 6 | CoinGecko | https://www.coingecko.com/ | KPI 双卡 + Trending / Top Gainers 横条 + 宽表 | KPI 带 + 横向资产条 |
| 7 | Coin360 | https://coin360.com/ | 按市值定大小的 treemap 热力图，涨绿跌红、颜色深浅表示幅度 | 热力图 / 市值权重 |
| 8 | Finviz Map | https://finviz.com/map.ashx | S&P 500 treemap，按板块/市值分块、按涨跌着色 | 板块分组热力图 |
| 9 | CoinGlass | https://www.coinglass.com/ | 清算热力图、资金费率、OI、多空比等多面板仪表盘 | 多面板大盘仪表盘 |
| 10 | Grafana | https://grafana.com/docs/grafana/latest/dashboards/use-dashboards/ | 24 列网格的自由面板布局；kiosk 模式隐藏导航，适合电视/大屏 | kiosk + 面板网格 + 自动刷新 |
| 11 | Home Assistant | https://www.home-assistant.io/dashboards/ | Masonry / Panel / Sections / Sidebar 四种视图 + 卡片网格 | 视图切换 + 卡片抽象 |
| 12 | MagicMirror² | https://magicmirror.builders/ | 屏幕按 top/middle/bottom × left/center/right 分区，模块挂到区域 | 区域化模块布局 |
| 13 | DAKboard | https://dakboard.com/site | 可拖拽、可缩放的内容块：日历/天气/照片/新闻/股票 | 自由块 + 股票块 |
| 14 | Bloomberg Terminal | https://www.bloomberg.com/professional/products/bloomberg-terminal/ | 多面板平铺工作区：报价 montage、图表、新闻、命令栏，支持多显示器 | 高密度多面板 |
| 15 | Apple Stocks | https://apps.apple.com/us/app/stocks/id1069512882 | 自选列表 + 图表 + 关键统计 + 新闻，iPhone/iPad/Mac 自适应 | 自选 + 主图 + 统计 |
| 16 | OpenTerminal | https://github.com/ErTasselli/OpenTerminal | 基于免费公共行情数据的终端式交易看板，多面板聚合报价/图表/新闻 | 终端式多面板 + 免费数据 |
| 17 | Neuberg | https://github.com/KoNananachan/Neuberg | Bloomberg 风格的实时交易新闻终端，多资产行情 + 预测市场 + AI 分析 | 新闻/行情混排 |
| 18 | OpenBook | https://github.com/DegenSugarBoo/OpenBook | Rust 实现的实时加密期货深度热力图，带订单流与成交 tape | 深度热力图 + 成交 tape |
| 19 | AmbientDeck | https://github.com/tuyenctbk/AmbientDeck | Android TV/平板/kiosk 的模块化氛围看板：天气、航班雷达、加密 ticker、4K 背景 | TV 模块网格 |
| 20 | Coins Cap Market | https://github.com/widle-ai/Coins-Cap-Market | 支持手机/平板/WearOS/Android TV 的币价应用：列表、图表、收藏、新闻、事件 | 跨端列表 + 图表 |
| 21 | mtv Home | https://github.com/Themaina/mtv-home | Android TV 个人仪表盘：行情、提醒、新闻、YouTube | TV 首页信息层级 |
| 22 | Departures | https://github.com/0xdravyn/departures | Android TV/Google TV 的 Vestaboard 风格翻牌板：日期、天气、日历、行情 | 大字价格榜 / 翻牌 |
| 23 | Free My TV | https://github.com/noraseth-swu/free-my-tv | Android TV launcher：Home Assistant、摄像头、market ticker | TV 抽屉式行情 |
| 24 | Stock Cast | https://github.com/dan-silver/stock-cast-android-client | 手机选股，TV 通过 Chromecast 显示股票图表 | 手机配置 + TV 展示 |
| 25 | StockTicker | https://github.com/premnirmal/StockTicker | 可调整大小的主屏 widget，展示自选组合的价格与涨跌 | 迷你卡片墙 |
| 26 | IDouble Crypto Dashboard | https://github.com/IDouble/Customizable-Crypto-Currency-Dashboard-with-Chart | 加密货币看板：价格走势图 + 可定制面板 | 单图 + 指标组合 |
| 27 | Quantum Terminal | https://github.com/RAYDENFLY/quantumterminal | Bloomberg 灵感的加密交易看板：市场概览、新闻、链上数据 | 市场概览 + 新闻 |
| 28 | TradingLite | https://www.tradinglite.com/ | 多图网格 + 订单流/热力图/成交量分布工具 | 多图 + 订单流 |
| 29 | Coinigy | https://www.coinigy.com/ | 可保存的多图工作区 + 订单簿/成交/仓位 | 工作区 + 多图 |
| 30 | DexScreener | https://dexscreener.com/ | K 线 + 交易表 + 代币信息/流动性侧栏 | 主图 + 数据表侧栏 |
| 31 | FreeKiosk | https://github.com/RushB-fr/freekiosk | Android kiosk / 数字标牌锁定应用，适合常亮大屏 | kiosk 锁定 + 常亮 |

补充说明：Grafana kiosk、DAKboard、MagicMirror²、FreeKiosk 共同说明「电视大屏」需要
隐藏系统 UI、固定刷新、抗烧屏；本项目的 `antiBurn` 已有基础，需要扩展到 Board。

### 2.2 模式归纳

每种模式给出：**适合场景 / 每格展示什么 / 遥控器操作难点**。

#### P1 单图聚焦（Single-chart hero）
- 适合：只想盯一个标的；默认首页；低端设备。
- 每格：大价格 + 涨跌 + 单张 K 线（可带成交量/指标）。
- 遥控器难点：几乎无；难点在「如何进入其它功能」而不破坏单图沉浸感。

#### P2 主图 + 侧边自选（Chart + watchlist）
- 适合：盯一个主标的、同时扫一篮子币。
- 每格：主图区 K 线；侧栏每行「符号 + 最新价 + 24h% + 迷你走势」。
- 遥控器难点：侧栏行数多，D-pad 要区分「格子间移动」与「列表内移动」；
  需要 OK 进入列表、BACK 退出，避免焦点在 10 行里迷路。

#### P3 主图 + 大盘指标条（Chart + KPI strip/sidebar）
- 适合：既看个币又看大盘（BTCD/TOTAL/稳定币）。
- 每格：主图；指标格只放「标签 + 大数字 + 变化 + 更新时间」。
- 遥控器难点：指标格通常不可交互；D-pad 经过时仍要有焦点环，OK 可展开大图/详情，
  避免「按了没反应」。

#### P4 2/3/4/6/9 分屏（Multi-chart grid）
- 适合：2–9 个标的同时看；4 宫格最平衡，9 宫格偏「行情墙」。
- 每格：K 线（2/4 宫格）或「价格 + 涨跌 + 迷你图」（6/9 宫格）。
- 遥控器难点：焦点环必须明显；格子越小越难读出「当前选中谁」；
  ◀▶ 是切「当前格周期」还是「全部格周期」需要明确规则；换标的要走搜索弹层。

#### P5 主图 + 同币多周期（Multi-timeframe）
- 适合：同一币的 1m/1H/1D/1W 联动分析。
- 每格：同一标的、不同周期的 K 线，共享顶部价格/涨跌。
- 遥控器难点：四个格子看起来一样，必须靠 TF 标签和焦点环区分；
  「换标的」应作用于全部四格，而不是某一格。

#### P6 迷你走势卡片墙（Sparkline card wall）
- 适合：快速扫 6–9 个标的的强弱；电视常驻。
- 每格：符号、最新价、24h%、40–60 点 sparkline；不画 K 线。
- 遥控器难点：格子多、文字小；OK 进全屏是主要出口；不要在每个卡里放可聚焦按钮。

#### P7 价格大字榜（Big-number board）
- 适合：远距离观看、数字 signage、机场翻牌风格。
- 每格：一行一个标的，超大价格/涨跌，弱化图表。
- 遥控器难点：几乎没有交互；需要防止「大字烧屏」和长时间静止。

#### P8 ticker 跑马灯（Ticker strip）
- 适合：主图之外的持续信息流；新闻/价格/大盘指标。
- 每格：一条横向滚动的「符号 价格 涨跌」或文本。
- 遥控器难点：滚动内容不可聚焦；整条 ticker 作为叶子可以获得焦点（OK 放大、MENU 设置），
  但内部条目不可聚焦；低端设备上持续动画会耗性能，需要降帧/暂停策略。

#### P9 热力图（Heatmap / treemap）
- 适合：全市场强弱、板块轮动；远距离。
- 每格：按市值定面积，颜色表示涨跌，格内符号/涨跌。
- 遥控器难点：格子数量多且大小不一，几何焦点导航容易跳；通常只做展示 + OK 进详情。
- 本项目 v1 不做热力图；列为后续模块（需要全市场市值/涨跌快照，OKX tickers 只能给出价格/成交额）。

#### P10 涨跌榜（Movers / gainers-losers）
- 适合：发现异动；与大盘指标搭配。
- 每格：榜单行「排名 + 符号 + 价格 + 24h% + 成交额」。
- 遥控器难点：列表滚动与翻页；OK 选中后是「换主图」还是「进详情」要统一。

#### P11 大盘总览仪表盘（Market overview）
- 适合：不盯单币，只看市场温度；开机默认/待机屏。
- 每格：4 个大盘指标 + 涨跌榜 + 自选 + ticker。
- 遥控器难点：模块多但没有主图；需要定义「OK 进入哪个模块」以及 BACK 的层级。

#### P12 对比叠加图（Normalized compare）
- 适合：BTC vs ETH vs SOL 的相对强弱。
- 每格：一张归一化百分比折线图（多标的），旁边价格卡。
- 遥控器难点：多序列的图例/开关；D-pad 要能切换窗口（24H/7D）和增删对比标的。
- 本项目列为可选扩展（L16），不进入 14 种必选。

#### P13 订单簿/成交（Order book / tape）
- 适合：短线/做市；专业终端。
- 每格：买卖档位、成交明细、深度图。
- 遥控器难点：信息密度极高，TV 远距离几乎不可读；本项目 v1 不做。
- 数据可行性：OKX 公共 WS 有 `books5`/`trades` 频道，未来可接。

#### P14 模块化区域 / 轮播（Modular regions / playlist）
- 适合：数字标牌、家庭大屏、长时间无人操作。
- 每格：任意模块；可整页轮播。
- 遥控器难点：轮播会打断用户操作；必须提供「操作时暂停轮播」。
- 本项目 v1 不做自动轮播；布局树已为后续「播放列表」留出扩展位。

### 2.3 调研结论（对本项目的 10 条指导）

1. **默认必须单图**：绝大多数行情产品首屏都是单图聚焦；多图是进阶需求，不能强迫老用户接受。
2. **4 宫格是甜点**：2×2 在 1080p 每格约 930×510，既能看 K 线又不至于太小；
   9 宫格只适合「价格 + sparkline」，不适合 K 线 + 指标。
3. **侧栏宽度 28%–33%**：TradingView/终端类产品的右侧信息栏大致在这个区间；
   再窄放不下「标签 + 数字」，再宽主图失去焦点。本设计 L02 用 33.3%，L03 用 27.1%。
4. **大盘指标要独立成 StatTile**：不要混进价格行；每个 tile 只表达一个数。
5. **迷你走势是高频刚需**：CMC/CoinGecko 的表格、Apple Stocks 的自选、StockTicker 的 widget
   都在用 sparkline；它比小 K 线便宜得多，也更适合远距离。
6. **ticker 内部条目不可聚焦**：整条 ticker 作为叶子可以被 D-pad 选中并 OK 放大，
   但滚动内容本身不参与焦点；否则遥控器体验会崩。
7. **电视端必须显式设计焦点**：Web 产品靠鼠标 hover，TV 只能靠焦点环 + 几何导航。
8. **kiosk/大屏要抗烧屏**：Grafana/DAKboard/MagicMirror/FreeKiosk 都强调长时间常亮；
   本项目已有 `antiBurn`，需要扩展到多布局。
9. **树比固定网格更合适**：「左一大右两小」「1:2/2:1」「自定义再分」都不是规则网格，
   用 Split/Leaf 树可以统一表达；矩形是运行时算出来的，不是手写死的。
10. **同屏只保留一个全功能图表**：TradingView 的多图是 Web/桌面 GPU 渲染；TV 盒子性能有限，
   4 个带指标的 Canvas 很容易掉帧。分级（FULL/COMPACT/MINI）是必须的，不是优化项。

---

## 3. 布局引擎与几何

### 3.1 设计原则

1. **树是唯一真源**：`LayoutNode.Split` 决定结构与比例，`Leaf` 决定模块与绑定；
   像素矩形（`Rect`）只是运行时派生结果，不持久化。
2. **归一化 + 设计单位**：预设以 1920×1080 为设计画布给出比例；运行时按实际 `maxWidth/maxHeight`
   换算，1080p/720p/4K 共用一套结构。
3. **安全区与 gutter 统一**：1080p 外边距 24px、格间距 12px；720p 按 2/3 缩放为 16px / 8px；
   4K 按 2× 缩放为 48px / 24px（UI 缩放上限见 §3.3）。
4. **一个布局最多 1 个 FULL 图表**：带指标、坐标、十字光标、价格标签。
   其余图表降级为 `COMPACT`（无指标/少标签/无十字光标）或 `MINI`（纯 sparkline）。
5. **每格都有标题行**：符号 + 周期 + 数据状态（实时/延迟/休市）。小格也要有，
   否则用户不知道自己在看什么；9 宫格用压缩标题（符号 + 涨跌）。
6. **焦点环由 Board 父层统一画**：模块内部不画焦点，避免每个模块各写一套。
7. **布局只决定「格子和模块」，不决定「数据」**：数据由 `Slot.binding` + `Slot.params` 决定。
8. **经典布局走旧代码路径**：`presetId="classic"` 时完全走 `TvScreenInner`，保证老用户零变化。
9. **电视优先**：1080p 设计下最小可读字号 14sp；720p 不低于 12sp；关键价格 24–32sp（9 宫格）
   或 64–96sp（单图/4 卡）。

### 3.2 画布、边距与 gutter

设计画布：**1920×1080**，坐标原点在左上角。

```
outer margin M = 24   (1080p)
gutter      G = 12   (1080p)
content box = (M, M, 1920-2M, 1080-2M) = (24, 24, 1872, 1032)
```

预设可以直接使用整个 content box（大多数布局），也可以先切出 chrome：

- **共享头部**（L10 多周期）：高 72px，位于 content box 顶部；下方 gap 12px 后是网格。
- **底部 ticker**（L13/L14）：高 48px，位于 content box 底部；上方 gap 12px。
- **顶部指标带**（L13）：高 160px；下方 gap 12px 后是 body；body 下方 gap 12px 后是 ticker。

L01 经典布局不遵守 Board 的 margin/gutter，它沿用现有 `Screen.kt` 的
`padding(horizontal=42*s, vertical=24*s)`，以保证老用户界面逐像素不变。

### 3.3 720p / 4K 如何缩放

**结论：按 Dp/归一化布局缩放，不按物理像素写死。**

Android TV 的 Compose `BoxWithConstraints.maxHeight` 返回 Dp。常见设备可能报告 540dp 逻辑高度
（1080p/720p 都常见），也可能报告 1080dp（部分盒子/显示器模式）。因此：

- 现有 `s = maxHeight/540`（clamp 0.72–2.6）继续用于经典布局和字号；
- Board 使用同一 `s` 作为**设计缩放因子**：`s = (maxHeight.value / 540f).coerceIn(0.72f, 2.6f)`；
  所有间距/字号都是 `designPx * s` 的 Dp/Sp，而不是物理 px；
- 几何计算始终用**实测尺寸**：`rectPx = nodeRectNormalized * (maxWidth, maxHeight)`，
  所以 1280×720、1920×1080、3840×2160 都得到同样的比例；
- 4K 时 density 通常更大，同样的 Dp 会渲染成更多物理像素；如果设备报告 1080dp 高度，
  `s=2.0`，UI 放大 2 倍。**不因为 4K 就多画 K 线**：MainChart 仍 120–500 根，
  其余仍 120/60，避免 GPU/内存翻倍。

> 下表的 px 是 **1920×1080 设计画布单位**，用于线框图/预览，不是物理像素承诺。
> Compose 里写 Dp/Sp，乘以 `s`，再由 density 转成物理像素。
> 若 TV 报告 540dp 逻辑高度（常见），`s=1`：1080p（density=2）下 24dp≈48 物理 px；
> 720p（density≈1.33）下 24dp≈32 物理 px；4K（density=4）下 24dp≈96 物理 px。
> 若设备报告 1080dp 高度，`s=2`，UI 再放大 2×。

| 设计画布等效 | 外边距（设计单位） | gutter | 最小字号 | 说明 |
|---|---:|---:|---:|---|
| 1280×720 | 16 | 8 | 12sp | 9 宫格价格 20–24sp；6 宫格 24–28sp |
| 1920×1080 | 24 | 12 | 14sp | 基准；9 宫格价格 24–28sp；4 宫格标题 40–44 设计单位 |
| 3840×2160 | 48 | 24 | 14sp（Dp） | UI 最多放大 2×；不增加数据量；低端 4K 盒子可渲染 1080p 让 TV 上采样 |
| 960×540（540dp 逻辑） | 24dp | 12dp | 14sp | 与 1080p 相同的 Dp 布局，物理像素由 density 决定 |

**720p 特别规则**：

- 9 宫格只保留「符号 + 价格 + 24h% + spark」，隐藏 24H 高/低/量；
- 6 宫格标题压缩到 32px；4 宫格标题压缩到 36–40px；
- 侧栏列表从 10 行降到 8 行；
- 如果 `maxHeight < 500dp`，Board 自动进入 `performanceMode=LOW`（见 §8.7）。

**4K 特别规则**：

- 不改变布局树和每个格子的数据量；只放大字号/线宽/焦点环；
- 线宽 `1.5 * s`，焦点环 `2 * s`，sparkline 线宽 `1.2 * s`；
- 若设备是低端 4K 盒子（RAM < 2GB 或检测到持续掉帧），UI 以 1080p 逻辑渲染，
  由 TV 负责上采样；不要在 4K 上开 9 个高分辨率 Canvas。

### 3.4 树的矩形推导

对每个 `Split`：

```
n = children.size
available = parentLength - (n - 1) * gutter
childLength_i = available * weights_i / sum(weights)
```

- `direction = H`：沿 x 轴切分，`y/h` 继承父矩形；每个 child 的 `x` 逐段累加 `childLength + gutter`。
- `direction = V`：沿 y 轴切分，`x/w` 继承父矩形；每个 child 的 `y` 逐段累加。
- `weights` 必须为有限正数；解析时归一化；和 ≤0 时退化为等分。
- 叶子得到最终矩形；Board 用该矩形测量/摆放 Slot，并画焦点环。
- 舍入：用 Float/Dp 计算，最后绘制时取整；不要对每个子矩形单独四舍五入后再累加，
  否则多级嵌套会出现 1–2px 缝。推荐「先算浮点边界，再对边界取整」。

示例（L02，1080p）：

```
content = (24, 24, 1872, 1032)
root H [2,1] -> available 1860 -> 1240 / 620
main = (24, 24, 1240, 1032)
sidebar = (1276, 24, 620, 1032)
sidebar V [26,59] -> available 1020 -> 312 / 708
tiles = (1276, 24, 620, 312)
movers = (1276, 348, 620, 708)
tiles V [1,1] -> available 300 -> 150 / 150
row1 = (1276, 24, 620, 150)
row2 = (1276, 186, 620, 150)
row H [1,1] -> available 608 -> 304 / 304
btcd = (1276, 24, 304, 150)
total = (1592, 24, 304, 150)
stable_mcap = (1276, 186, 304, 150)
stable_dom = (1592, 186, 304, 150)
```

> 注意：`available = 312 - 12 = 300`，所以两行各 150；`304 = (620-12)/2`。
> 这些数字与 §4 的槽位表一致。

### 3.5 布局 ID 与命名

| presetId | 名称 | 任务书编号 |
|---|---|---|
| `classic` | 经典单图（默认） | ① |
| `chart_macro` | 单图 + 右侧大盘栏 | ② |
| `chart_watchlist` | 单图 + 右侧自选 | ③ |
| `split_1_1` | 左右 1:1 双分屏 | ④ |
| `split_1_2` | 左 1 : 右 2 双分屏 | ⑤ |
| `split_2_1` | 左 2 : 右 1 双分屏 | ⑥ |
| `split_3` | 3 分屏（1 大 2 小） | ⑦ |
| `grid_4_charts` | 4 宫格 K 线 | ⑧ |
| `grid_4_cards` | 4 宫格迷你卡 | ⑨ |
| `multi_tf` | 同币多周期 | ⑩ |
| `grid_6` | 6 宫格 | ⑪ |
| `grid_9` | 9 宫格行情墙 | ⑫ |
| `overview` | 大盘总览 | ⑬ |
| `chart_ticker` | 跑马灯底栏 + 单图 | ⑭ |
| `pro_3col` | 三栏工作台（可选） | 扩展 |
| `compare` | 对比叠加（可选） | 扩展 |
| `custom` | 用户自定义 | — |

> 对 ⑤ 的理解：任务书写「左 1:2 右（1:2）」，本设计按 **左:右 = 1:2** 实现
> （左窄右宽）。如果原意是「左右各 1:2 的三分屏」，L07 的 1 大 2 小已经覆盖类似场景，
> 可在设置里用自定义分割实现。

---

## 4. 预置布局（14 种必选 + 2 种可选）

线框图图例：

```
[M] MAIN_CHART detail=FULL        [C] MAIN_CHART detail=COMPACT
[S] MINI_CHART / Sparkline        [$] PRICE_CARD（可内嵌 spark）
[T] STAT_TILE                     [W] WATCHLIST
[V] MOVERS                        [>] TICKER_STRIP
```

所有矩形均为 1920×1080 设计画布下的像素；实际渲染按 §3.3 缩放。

---

### L01 经典单图（Classic，默认）

- **定位**：现状，默认布局；老用户升级后界面与按键完全不变。
- **树**：`preset="classic"`，`legacy=true`，`Leaf(main)`；运行时**不进入 Board 引擎**，
  直接调用现有 `TvScreenInner`。树仅用于设置页预览和未来迁移。
- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `main` | `MAIN_CHART` | `FollowMain`；`tf=FollowMain`；`detail=FULL` | 现有布局自适应，不按 Board rect |

- **线框图**：

```
┌──────────────────────────────────────────────────────────────┐
│ Header  标的/数据源/连接/时钟                                 │
├──────────────────────────────────────────────────────────────┤
│ 大价格 + 涨跌 + 24H 高/低/量                                  │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│                    [M] MAIN_CHART（全功能）                  │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│ 周期 chips        缩放/按键提示                               │
└──────────────────────────────────────────────────────────────┘
```

- **遥控器**：无新增；`◀▶/▲▼/MENU/OK/BACK` 与现在逐键一致。
- **性能**：与 v1.2 完全相同；Board 引擎不加载。

---

### L02 单图 + 右侧大盘栏（Chart + Macro）

- **定位**：主图盯币，右侧同时看大盘温度 + 涨跌榜。
- **树**：

```
Split(H, [2, 1]) {
  Leaf(main, MAIN_CHART/FULL, FollowMain)
  Split(V, [26, 59]) {
    Split(V, [1, 1]) {
      Split(H, [1, 1]) { Leaf(btcd), Leaf(total) }
      Split(H, [1, 1]) { Leaf(stable_mcap), Leaf(stable_dom) }
    }
    Leaf(movers, MOVERS, SPOT_USDT/GAINERS/5)
  }
}
```

- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `main` | `MAIN_CHART` | `FollowMain`；`tf=FollowMain`；`detail=FULL` | (24, 24, 1240, 1032) |
| `btcd` | `STAT_TILE` | `Overview([BTCD])` | (1276, 24, 304, 150) |
| `total` | `STAT_TILE` | `Overview([TOTAL])` | (1592, 24, 304, 150) |
| `stable_mcap` | `STAT_TILE` | `Overview([STABLE_MCAP])` | (1276, 186, 304, 150) |
| `stable_dom` | `STAT_TILE` | `Overview([STABLE_DOM])` | (1592, 186, 304, 150) |
| `movers` | `MOVERS` | `SPOT_USDT / GAINERS / 5` | (1276, 348, 620, 708) |

- **线框图**（1920×1080 比例）：

```
┌──────────────────────────────────────────────┬────────────────────┐
│ BTC/USDT  $96,432  ▲2.34%                    │ [T] BTCD   56.5%   │
│                                              ├────────────────────┤
│                                              │ [T] TOTAL  $2.31T  │
│                                              ├────────────────────┤
│                 [M] MAIN_CHART               │ [T] STABLE $286B   │
│                                              ├────────────────────┤
│                                              │ [T] ST.DOM 12.4%   │
│                                              ├────────────────────┤
│                                              │ [V] 涨跌榜 [涨][跌] │
│                                              │ 1 SOL   +18.2%     │
│                                              │ 2 ...              │
└──────────────────────────────────────────────┴────────────────────┘
```

- **默认内容**：主图 `FollowMain`（全局 BTC）；tile 四项大盘；Movers = OKX SPOT USDT 涨幅 Top5。
- **遥控器难点**：
  - StatTile 不可交互，但仍有焦点环；D-pad 从主图向右落到 Movers 时要跨过 tile 区；
    几何导航按中心点方向评分，不按 DOM 顺序。
  - Movers 内部行数多：OK 进入列表（`SLOT_LIST`），▲▼ 滚动，BACK 回到侧栏整体焦点。
  - MENU 在主图上换主标的；在 Movers 上打开「现货/永续 + 涨/跌 + 条数」选项。
- **性能**：1 个 FULL + 4 个 StatTile + 1 个列表；数据成本低，适合常驻。

---

### L03 单图 + 右侧自选（Chart + Watchlist）

- **定位**：主图 + 10 行自选，最接近 Apple Stocks / TradingView 的经典组合。
- **树**：

```
Split(H, [113, 42]) {
  Leaf(main, MAIN_CHART/FULL, FollowMain)
  Leaf(watchlist, WATCHLIST, FAVORITES/10)
}
```

- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `main` | `MAIN_CHART` | `FollowMain`；`tf=FollowMain`；`detail=FULL` | (24, 24, 1356, 1032) |
| `watchlist` | `WATCHLIST` | `FAVORITES / 10`（空则 `HOT`） | (1392, 24, 504, 1032) |

- **线框图**：

```
┌──────────────────────────────────────────────┬────────────────────┐
│ BTC/USDT  $96,432  ▲2.34%                    │ [W] 自选            │
│                                              ├────────────────────┤
│                                              │ BTC  96,432 +2.3%  │
│                 [M] MAIN_CHART               │ ETH   3,120 +1.1%  │
│                                              │ SOL     198 +8.2%  │
│                                              │ ...                │
│                                              │                    │
│                                              │ + 管理自选         │
└──────────────────────────────────────────────┴────────────────────┘
```

- **默认内容**：自选 = `Prefs.favorites`，为空时用 `Instruments.HOT`；主图 FollowMain。
- **遥控器难点**：
  - 自选行是「列表内焦点」，与「格子间焦点」是两层；OK 进入 / BACK 退出。
  - 选中一行应「把主图换成该标的」（更新全局 `instrument`），而不是打开新页面；
    这是与 L08 四宫格「只换当前格」的关键区别。
  - 行内 spark 不可聚焦；焦点只画在整行上。
- **性能**：10 行报价 + 10 条 spark；WS 按预算决定是否逐标的订阅（见 §5.3）。

---

### L04 左右 1:1 双分屏（Split 1:1）

- **定位**：两个标的并排，BTC/ETH 或「币 vs 美股」。
- **树**：

```
Split(H, [1, 1]) {
  Leaf(left,  MAIN_CHART/FULL,    FollowMain)
  Leaf(right, MAIN_CHART/COMPACT, Fixed(ETH))
}
```

- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `left` | `MAIN_CHART` | `FollowMain`；`tf=FollowMain`；`detail=FULL` | (24, 24, 930, 1032) |
| `right` | `MAIN_CHART` | `Fixed(ETH)`；`tf=FollowMain`；`detail=COMPACT` | (966, 24, 930, 1032) |

- **线框图**：

```
┌──────────────────────────────┬──────────────────────────────┐
│ BTC/USDT  1m   $96,432       │ ETH/USDT  1m   $3,120        │
│                              │                              │
│        [M] MAIN_CHART        │      [C] COMPACT_CHART       │
│        （全功能）             │      （无指标/少标签）        │
│                              │                              │
│                              │                              │
└──────────────────────────────┴──────────────────────────────┘
```

- **默认内容**：左 FollowMain（BTC）；右 Fixed ETH。周期默认跟随全局。
- **遥控器难点**：
  - 两个大图容易混淆「当前焦点」，焦点环要够粗（2dp + 外发光）+ 标题高亮；
  - ◀▶ 只改「焦点格」周期；设置里可开 `syncTf` 让两格同步；
  - OK 全屏后，BACK 必须回到原来的格子，而不是回到左格。
- **性能**：1 FULL + 1 COMPACT；成本可控。

---

### L05 左 1 : 右 2 双分屏（Split 1:2）

- **定位**：左窄右宽；左侧做「卫星/对照」，右侧做大图。
- **树**：

```
Split(H, [1, 2]) {
  Leaf(left,  MAIN_CHART/COMPACT, Fixed(ETH))
  Leaf(right, MAIN_CHART/FULL,    FollowMain)
}
```

- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `left` | `MAIN_CHART` | `Fixed(ETH)`；`tf=FollowMain`；`detail=COMPACT` | (24, 24, 620, 1032) |
| `right` | `MAIN_CHART` | `FollowMain`；`tf=FollowMain`；`detail=FULL` | (656, 24, 1240, 1032) |

- **线框图**：

```
┌──────────────────────┬────────────────────────────────────────────┐
│ ETH/USDT  $3,120     │ BTC/USDT  $96,432  ▲2.34%                  │
│                      │                                            │
│  [C] COMPACT_CHART   │              [M] MAIN_CHART                │
│                      │              （全功能）                     │
│                      │                                            │
│                      │                                            │
└──────────────────────┴────────────────────────────────────────────┘
```

- **默认内容**：左 ETH、右 BTC；右侧 FULL。
- **遥控器难点**：左侧窄，标题行必须压缩；◀▶ 只改焦点格周期；OK 全屏左格时升为 FULL。
- **性能**：1 FULL + 1 COMPACT；与 L04 相同。

---

### L06 左 2 : 右 1 双分屏（Split 2:1）

- **定位**：左大右小；右侧做「对照/下一个候选」。
- **树**：

```
Split(H, [2, 1]) {
  Leaf(left,  MAIN_CHART/FULL,    FollowMain)
  Leaf(right, MAIN_CHART/COMPACT, Fixed(ETH))
}
```

- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `left` | `MAIN_CHART` | `FollowMain`；`tf=FollowMain`；`detail=FULL` | (24, 24, 1240, 1032) |
| `right` | `MAIN_CHART` | `Fixed(ETH)`；`tf=FollowMain`；`detail=COMPACT` | (1276, 24, 620, 1032) |

- **线框图**：

```
┌────────────────────────────────────────────┬──────────────────────┐
│ BTC/USDT  $96,432  ▲2.34%                  │ ETH/USDT  $3,120     │
│                                            │                      │
│              [M] MAIN_CHART                │  [C] COMPACT_CHART   │
│              （全功能）                     │                      │
│                                            │                      │
│                                            │                      │
└────────────────────────────────────────────┴──────────────────────┘
```

- **默认内容**：左 BTC、右 ETH；左侧 FULL。
- **遥控器**：与 L04/L05 相同规则；右侧窄格不显示指标。
- **性能**：1 FULL + 1 COMPACT。

---

### L07 3 分屏（1 大 2 小，Split 3）

- **定位**：一个主标的 + 两个卫星标的。
- **树**：

```
Split(H, [2, 1]) {
  Leaf(main, MAIN_CHART/FULL, FollowMain)
  Split(V, [1, 1]) {
    Leaf(top_right,    MAIN_CHART/COMPACT, Fixed(ETH))
    Leaf(bottom_right, MAIN_CHART/COMPACT, Fixed(SOL))
  }
}
```

- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `main` | `MAIN_CHART` | `FollowMain`；`tf=FollowMain`；`detail=FULL` | (24, 24, 1240, 1032) |
| `top_right` | `MAIN_CHART` | `Fixed(ETH)`；`tf=FollowMain`；`detail=COMPACT` | (1276, 24, 620, 510) |
| `bottom_right` | `MAIN_CHART` | `Fixed(SOL)`；`tf=FollowMain`；`detail=COMPACT` | (1276, 546, 620, 510) |

- **线框图**：

```
┌──────────────────────────────────────┬──────────────────────┐
│ BTC/USDT  $96,432                    │ ETH/USDT  $3,120     │
│                                      │ [C] COMPACT_CHART    │
│            [M] MAIN_CHART            ├──────────────────────┤
│            （全功能）                 │ SOL/USDT  $198       │
│                                      │ [C] COMPACT_CHART    │
└──────────────────────────────────────┴──────────────────────┘
```

- **默认内容**：BTC 主图、ETH/SOL 卫星。
- **遥控器难点**：
  - 右侧两格高度 510px，标题行压缩到 36–40px；
  - D-pad 从右上到右下要自然；几何导航按中心点方向评分；
  - 小格不显示副图指标，避免高度不够。
- **性能**：1 FULL + 2 COMPACT。

---

### L08 4 宫格 K 线（Quad Charts）

- **定位**：4 个标的的 K 线同屏，最平衡的多图布局。
- **树**：

```
Split(V, [1, 1]) {
  Split(H, [1, 1]) { Leaf(tl, COMPACT/BTC), Leaf(tr, COMPACT/ETH) }
  Split(H, [1, 1]) { Leaf(bl, COMPACT/SOL), Leaf(br, COMPACT/BNB) }
}
```

- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `tl` | `MAIN_CHART` | `Fixed(BTC)`；`tf=FollowMain`；`detail=COMPACT` | (24, 24, 930, 510) |
| `tr` | `MAIN_CHART` | `Fixed(ETH)`；`tf=FollowMain`；`detail=COMPACT` | (966, 24, 930, 510) |
| `bl` | `MAIN_CHART` | `Fixed(SOL)`；`tf=FollowMain`；`detail=COMPACT` | (24, 546, 930, 510) |
| `br` | `MAIN_CHART` | `Fixed(BNB)`；`tf=FollowMain`；`detail=COMPACT` | (966, 546, 930, 510) |

- **线框图**：

```
┌──────────────────────────────┬──────────────────────────────┐
│ BTC  1m                      │ ETH  1m                      │
│      [C] COMPACT_CHART       │      [C] COMPACT_CHART       │
│                              │                              │
├──────────────────────────────┼──────────────────────────────┤
│ SOL  1m                      │ BNB  1m                      │
│      [C] COMPACT_CHART       │      [C] COMPACT_CHART       │
│                              │                              │
└──────────────────────────────┴──────────────────────────────┘
```

- **默认内容**：BTC、ETH、SOL、BNB；周期跟随全局 `Prefs.tf`。
- **遥控器难点**：
  - 4 格尺寸相同，焦点环 + 标题高亮必须同时存在；
  - ◀▶ 改焦点格周期；长按 OK 可「把当前周期应用到全部格」（可选）；
  - MENU 换焦点格标的，搜索弹层标题要显示「正在修改：左上」；
  - OK 全屏后该格升为 `FULL`（带指标/十字光标），BACK 回到原格。
- **性能**：4 个 COMPACT，每格 60–120 根；不跑 4 份指标。全屏时才跑 1 份 FULL。

---

### L09 4 宫格迷你卡（Quad Price Cards）

- **定位**：价格优先，4 个标的的「大价格 + 涨跌 + 迷你走势」。
- **树**：与 L08 相同的 2×2；叶子模块换成 `PRICE_CARD`。
- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `tl` | `PRICE_CARD` | `Fixed(BTC)`；`sparkWindow=H24`；`showSpark=true` | (24, 24, 930, 510) |
| `tr` | `PRICE_CARD` | `Fixed(ETH)`；`sparkWindow=H24`；`showSpark=true` | (966, 24, 930, 510) |
| `bl` | `PRICE_CARD` | `Fixed(SOL)`；`sparkWindow=H24`；`showSpark=true` | (24, 546, 930, 510) |
| `br` | `PRICE_CARD` | `Fixed(BNB)`；`sparkWindow=H24`；`showSpark=true` | (966, 546, 930, 510) |

- **线框图**：

```
┌──────────────────────────────┬──────────────────────────────┐
│ BTC        $96,432           │ ETH        $3,120            │
│ ▲ +2.34%   H/L/V             │ ▲ +1.10%   H/L/V             │
│      ~~~~~~~~~~~~            │      ~~~~~~~~~~~~            │
├──────────────────────────────┼──────────────────────────────┤
│ SOL        $198.40           │ BNB        $612.30           │
│ ▲ +8.20%   H/L/V             │ ▼ -0.80%   H/L/V             │
│      ~~~~~~~~~~~~            │      ~~~~~~~~~~~~            │
└──────────────────────────────┴──────────────────────────────┘
```

- **默认内容**：BTC、ETH、SOL、BNB；spark 窗口 24H（可切 1H/7D）。
- **遥控器难点**：
  - 卡片本身没有内部可聚焦元素，OK 直接进全屏 K 线；
  - ◀▶ 改 spark 窗口（1H/24H/7D），不是周期；
  - 需要明确提示「OK 看 K 线」，否则用户不知道能进。
- **性能**：4 个 spark + 4 个报价；成本低。

---

### L10 同币多周期（Multi-Timeframe）

- **定位**：同一标的的 1m / 1H / 1D / 1W 四联屏。
- **树**：共享顶部 header（chrome，高 72px）+ 下方 2×2：

```
Split(V, [1, 1]) {
  Split(H, [1, 1]) { Leaf(m1, COMPACT, FollowMain/M1), Leaf(h1, COMPACT, FollowMain/H1) }
  Split(H, [1, 1]) { Leaf(d1, COMPACT, FollowMain/D1), Leaf(w1, COMPACT, FollowMain/W1) }
}
```

- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `m1` | `MAIN_CHART` | `FollowMain`；`tf=M1`；`detail=COMPACT` | (24, 108, 930, 468) |
| `h1` | `MAIN_CHART` | `FollowMain`；`tf=H1`；`detail=COMPACT` | (966, 108, 930, 468) |
| `d1` | `MAIN_CHART` | `FollowMain`；`tf=D1`；`detail=COMPACT` | (24, 588, 930, 468) |
| `w1` | `MAIN_CHART` | `FollowMain`；`tf=W1`；`detail=COMPACT` | (966, 588, 930, 468) |

- **线框图**：

```
┌──────────────────────────────────────────────────────────────┐
│ BTC/USDT  $96,432  ▲2.34%     多周期 1m / 1H / 1D / 1W       │ 72px header
├──────────────────────────────┬───────────────────────────────┤
│ 1m    [C] COMPACT_CHART      │ 1H    [C] COMPACT_CHART       │
│                              │                               │
├──────────────────────────────┼───────────────────────────────┤
│ 1D    [C] COMPACT_CHART      │ 1W    [C] COMPACT_CHART       │
│                              │                               │
└──────────────────────────────┴───────────────────────────────┘
```

- **默认内容**：`FollowMain` 标的；四格周期固定为 1m/1H/1D/1W。
- **遥控器难点**：
  - 四格标的相同，必须靠 TF 标签区分；标签固定在左上角，不能被图表盖住；
  - MENU 换标的应作用于**全部四格**（它们共享 FollowMain）；
  - ◀▶ 默认改「焦点格」周期；设置里提供「恢复 1m/1H/1D/1W」一键复位；
  - `TF.W1` 已存在，不需要新增枚举；OKX `1Wutc` 可直接取。
- **数据**：同一标的 4 条 K 线序列，共享 1 个 ticker 订阅；初始 4 次 candles 请求。
- **性能**：4 个 COMPACT；同一标的的 quote 只订阅一次。

---

### L11 6 宫格（Six Grid）

- **定位**：3×2，比 4 宫格多两个标的，仍保留迷你走势。
- **树**：

```
Split(V, [1, 1]) {
  Split(H, [1, 1, 1]) { Leaf(r1c1), Leaf(r1c2), Leaf(r1c3) }
  Split(H, [1, 1, 1]) { Leaf(r2c1), Leaf(r2c2), Leaf(r2c3) }
}
```

- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `r1c1` | `PRICE_CARD` | `Fixed(BTC)`；`sparkWindow=H24` | (24, 24, 616, 510) |
| `r1c2` | `PRICE_CARD` | `Fixed(ETH)`；`sparkWindow=H24` | (652, 24, 616, 510) |
| `r1c3` | `PRICE_CARD` | `Fixed(SOL)`；`sparkWindow=H24` | (1280, 24, 616, 510) |
| `r2c1` | `PRICE_CARD` | `Fixed(BNB)`；`sparkWindow=H24` | (24, 546, 616, 510) |
| `r2c2` | `PRICE_CARD` | `Fixed(XRP)`；`sparkWindow=H24` | (652, 546, 616, 510) |
| `r2c3` | `PRICE_CARD` | `Fixed(DOGE)`；`sparkWindow=H24` | (1280, 546, 616, 510) |

- **线框图**：

```
┌──────────────┬──────────────┬──────────────┐
│ BTC  +2.3%   │ ETH  +1.1%   │ SOL  +8.2%   │
│ 96,432 ~~~~  │ 3,120 ~~~~   │ 198.4 ~~~~   │
│              │              │              │
├──────────────┼──────────────┼──────────────┤
│ BNB  -0.8%   │ XRP  +3.4%   │ DOGE -1.2%   │
│ 612.3 ~~~~   │ 2.41 ~~~~    │ 0.38 ~~~~    │
│              │              │              │
└──────────────┴──────────────┴──────────────┘
```

- **默认内容**：BTC、ETH、SOL、BNB、XRP、DOGE；可切换为 6 个 COMPACT K 线（`detail=chart`）。
- **遥控器难点**：
  - 3 列时 D-pad 左右移动容易「跨两列」，几何导航要按列中心选最近；
  - 格子宽度 616px，价格字号 28–32sp，避免溢出；
  - OK 进全屏，MENU 换标的，BACK 返回。
- **性能**：6 spark + 6 报价；不画 K 线。

---

### L12 9 宫格行情墙（Market Wall）

- **定位**：3×3，纯「价格 + 涨跌 + 迷你走势」，电视常驻行情墙。
- **树**：3×3 嵌套 Split，每行权重 1:1:1。
- **槽位**（每格 616×336）：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `r1c1`…`r1c3` | `PRICE_CARD` | 前 3 个 HOT；`sparkWindow=H24` | y=24, h=336；x=24/652/1280 |
| `r2c1`…`r2c3` | `PRICE_CARD` | 第 4–6 个；`sparkWindow=H24` | y=372, h=336 |
| `r3c1`…`r3c3` | `PRICE_CARD` | 第 7–9 个；`sparkWindow=H24` | y=720, h=336 |

- **线框图**：

```
┌──────────────┬──────────────┬──────────────┐
│ BTC  +2.3%   │ ETH  +1.1%   │ SOL  +8.2%   │
│ 96,432 ~~~~  │ 3,120 ~~~~   │ 198.4 ~~~~   │
├──────────────┼──────────────┼──────────────┤
│ BNB  -0.8%   │ XRP  +3.4%   │ ADA  +0.4%   │
│ 612.3 ~~~~   │ 2.41 ~~~~    │ 0.62 ~~~~    │
├──────────────┼──────────────┼──────────────┤
│ DOGE -1.2%   │ AVAX +5.6%   │ LINK +2.0%   │
│ 0.38 ~~~~    │ 38.2 ~~~~    │ 22.4 ~~~~    │
└──────────────┴──────────────┴──────────────┘
```

- **默认内容**：按成交额排序的前 9 个 USDT 交易对，或 `HOT` 前 9。
- **遥控器难点**：
  - 9 格文字最小，必须保证 720p 可读（价格 20–24sp）；
  - 焦点环要明显，否则不知道 OK 会进哪一格；
  - MENU 换标的时搜索弹层标题要写明「第 X 格」；
  - 不建议在格内放任何按钮。
- **性能**：9 个 spark（40 点）+ 9 个报价；无 K 线、无指标。**这是最便宜的多标的布局**。

---

### L13 大盘总览（Market Overview）

- **定位**：没有主图，整屏都是市场温度。
- **树**：

```
Split(V, [10, 50, 3]) {
  Split(H, [1, 1, 1, 1]) { Leaf(btcd), Leaf(total), Leaf(stable_mcap), Leaf(stable_dom) }
  Split(H, [1, 1, 1]) { Leaf(gainers), Leaf(losers), Leaf(watchlist) }
  Leaf(ticker, TICKER_STRIP)
}
```

- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `btcd` | `STAT_TILE` | `Overview([BTCD])` | (24, 24, 459, 160) |
| `total` | `STAT_TILE` | `Overview([TOTAL])` | (495, 24, 459, 160) |
| `stable_mcap` | `STAT_TILE` | `Overview([STABLE_MCAP])` | (966, 24, 459, 160) |
| `stable_dom` | `STAT_TILE` | `Overview([STABLE_DOM])` | (1437, 24, 459, 160) |
| `gainers` | `MOVERS` | `SPOT_USDT / GAINERS / 5` | (24, 196, 616, 800) |
| `losers` | `MOVERS` | `SPOT_USDT / LOSERS / 5` | (652, 196, 616, 800) |
| `watchlist` | `WATCHLIST` | `FAVORITES / 10` | (1280, 196, 616, 800) |
| `ticker` | `TICKER_STRIP` | `FAVORITES + Overview` | (24, 1008, 1872, 48) |

- **线框图**：

```
┌──────────────┬──────────────┬──────────────┬──────────────┐
│ BTCD 56.5%   │ TOTAL $2.31T │ STABLE $286B │ ST.DOM 12.4% │
├──────────────┴──────────────┴──────────────┴──────────────┤
│ [V] 涨幅榜        │ [V] 跌幅榜        │ [W] 自选            │
│ 1 SOL +18.2%      │ 1 XXX -12.4%      │ BTC  96,432 +2.3%  │
│ 2 ...             │ 2 ...             │ ETH   3,120 +1.1%  │
│ ...               │ ...               │ ...                │
├────────────────────────────────────────────────────────────┤
│ [>] BTC 96,432 +2.3% · ETH 3,120 +1.1% · SOL 198 +8.2% →  │
└────────────────────────────────────────────────────────────┘
```

- **默认内容**：大盘四项 + OKX 现货 24h 涨跌榜 + `favorites`/`HOT`。
- **遥控器难点**：
  - 模块多，焦点顺序按「上→下、左→右」；几何导航应自然；
  - OK 在榜单/自选上进入全屏列表；在 StatTile 上展开单指标详情（源/更新时间）；
  - 没有主图，BACK 在根层回到系统/上一个页面，不能无处可去。
- **数据**：涨跌榜一次 `/market/tickers?instType=SPOT` 即可，不需要 N 个请求。
- **性能**：4 个 tile + 2 个榜单 + 1 个自选 + ticker；无 Canvas K 线。

---

### L14 跑马灯底栏 + 单图（Chart + Ticker）

- **定位**：保留经典单图的沉浸感，只在底部加一条展示型 ticker。
- **树**：

```
Split(V, [81, 4]) {
  Leaf(main, MAIN_CHART/FULL, FollowMain)
  Leaf(ticker, TICKER_STRIP, FAVORITES + Overview)
}
```

- **槽位**：

| id | module | binding / params | 1080p rect |
|---|---|---|---|
| `main` | `MAIN_CHART` | `FollowMain`；`tf=FollowMain`；`detail=FULL` | (24, 24, 1872, 972) |
| `ticker` | `TICKER_STRIP` | `FAVORITES + Overview`；`speed=normal` | (24, 1008, 1872, 48) |

- **线框图**：

```
┌──────────────────────────────────────────────────────────────┐
│ BTC/USDT  $96,432  ▲2.34%                                    │
│                                                              │
│                       [M] MAIN_CHART                         │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│ [>] BTC 96,432 +2.3% · ETH 3,120 +1.1% · SOL 198 +8.2% →    │
└──────────────────────────────────────────────────────────────┘
```

- **默认内容**：主图 FollowMain；ticker = `favorites`/`HOT` 前 12 + `BTCD 56.5%` 等文本项。
- **遥控器难点**：
  - ticker 内部滚动条目不可聚焦；整条 ticker 作为叶子可以获得焦点，
    OK 放大、MENU 打开内容来源/速度；它没有周期，◀▶ 只提示「该格没有周期」；
  - 滚动动画在低端设备上要降帧/暂停；
  - 与 L01 的按键保持一致（◀▶/▲▼/MENU/OK），但 ticker 格不响应周期切换。
- **性能**：1 个 FULL + 一条 ticker；ticker 用 15–20fps 步进，不用逐帧插值。

---

### L15 三栏工作台（Pro Dashboard，可选扩展）

- **定位**：Bloomberg-lite；一屏同时容纳自选、主图、大盘与涨跌榜。
- **树**：

```
Split(H, [30, 82, 42]) {
  Leaf(watchlist, WATCHLIST, FAVORITES/10)
  Leaf(main, MAIN_CHART/FULL, FollowMain)
  Split(V, [26, 59]) {
    Split(V, [1, 1]) {
      Split(H, [1, 1]) { Leaf(btcd), Leaf(total) }
      Split(H, [1, 1]) { Leaf(stable_mcap), Leaf(stable_dom) }
    }
    Leaf(movers, MOVERS, SPOT_USDT/GAINERS/5)
  }
}
```

- **矩形**：左 360px、中 984px、右 504px；右侧 tile 2×2（246×150）+ Movers（504×708）。
- **线框图**：

```
┌────────────┬──────────────────────────────┬──────────────┐
│ [W] 自选    │ BTC/USDT  $96,432 ▲2.34%     │ [T] BTCD     │
│ BTC +2.3%  │                              │ [T] TOTAL    │
│ ETH +1.1%  │                              ├──────────────┤
│ SOL +8.2%  │        [M] MAIN_CHART        │ [T] STABLE   │
│ ...        │                              │ [T] ST.DOM   │
│            │                              ├──────────────┤
│            │                              │ [V] 涨跌榜    │
└────────────┴──────────────────────────────┴──────────────┘
```

- **遥控器**：三栏 + 栏内列表 = 两层焦点，最容易迷路；v1 若做，建议左/右只读，
  焦点只在主图与列表整体之间移动；完整列表内导航放到 v2。
- **性能**：1 FULL + 10 行报价 + 大盘；成本可控。

---

### L16 对比叠加图（Compare Overlay，可选扩展）

- **定位**：2–4 个标的的归一化涨跌幅叠加，比较相对强弱。
- **树**：

```
Split(V, [66, 19]) {
  Split(H, [113, 42]) {
    Leaf(compare, COMPARE_CHART, FixedSet(BTC, ETH, SOL), window=24H)
    Split(V, [1, 1, 1]) { Leaf(card1), Leaf(card2), Leaf(card3) }
  }
  Leaf(table, COMPARE_TABLE, FixedSet(BTC, ETH, SOL))
}
```

- **矩形**：主图 1356×792；右侧 3 张 PriceCard 504×256；底部对比表 1872×228。
- **线框图**：

```
┌──────────────────────────────────────────┬───────────────┐
│ 对比 24H   BTC ● ETH ● SOL ●             │ [ $ ] BTC     │
│                                          ├───────────────┤
│         COMPARE_CHART                    │ [ $ ] ETH     │
│      （归一化 % 多条折线）                 ├───────────────┤
│                                          │ [ $ ] SOL     │
├──────────────────────────────────────────┴───────────────┤
│ 符号   最新价   24H    7D    成交额                        │
└──────────────────────────────────────────────────────────┘
```

- **遥控器**：◀▶ 改窗口（24H/7D）；MENU 增删对比标的；图例只做展示。
- **数据**：2–4 条收盘序列；归一化后只画折线，成本低。
- **范围**：v1 不做；如果要做，必须先完成 `COMPARE_CHART`/`COMPARE_TABLE` 两个新模块，
  不要在 L01–L14 的实现里预留半成品。

---

### 覆盖矩阵（任务书 ①–⑭）

| 任务书要求 | 布局 | 说明 |
|---|---|---|
| ① 经典单图（现状，默认） | L01 | 完全复用现有界面 |
| ② 单图 + 右侧大盘栏 | L02 | BTCD/总市值/稳定币市值/稳定币占比 + 涨跌榜 |
| ③ 单图 + 右侧自选 | L03 | 10 行自选 + spark |
| ④ 左右 1:1 两分屏 | L04 | 左右各一张 |
| ⑤ 左 1:2 右 | L05 | 左窄右宽；1 FULL + 1 COMPACT |
| ⑥ 左 2:1 | L06 | 左宽右窄；1 FULL + 1 COMPACT |
| ⑦ 3 分屏（1 大 2 小） | L07 | 左大右双小 |
| ⑧ 4 宫格（4 个标的 K 线） | L08 | 2×2 COMPACT |
| ⑨ 4 宫格迷你卡 | L09 | 2×2 PriceCard + spark |
| ⑩ 同币多周期 | L10 | 1m/1H/1D/1W |
| ⑪ 6 宫格 | L11 | 3×2 |
| ⑫ 9 宫格行情墙 | L12 | 3×3 仅价格/涨跌/spark |
| ⑬ 大盘总览 | L13 | 指标带 + 涨跌榜 + 自选 + ticker |
| ⑭ 跑马灯底栏 + 单图 | L14 | 单图 + ticker |
| 加分：Pro 三栏 | L15 | 可选，不进入 v1 必选 |
| 加分：对比叠加 | L16 | 可选，不进入 v1 必选 |

---

## 5. 模块清单与数据预算

### 5.1 ModuleType 总表

> 与任务书对齐：模块只有 7 类。`MAIN_CHART` 内部再分 `FULL/COMPACT` 两个细节级别；
> `PRICE_CARD` 可内嵌 spark，`MINI_CHART` 也可独立成格。

| ModuleType | 用途 | 渲染内容 | 数据来源 | 刷新 | 同屏上限 | 成本 |
|---|---|---|---|---|---|---|
| `MAIN_CHART` (`FULL`) | 全功能主图 | K 线/线/面积 + 成交量 + 指标 + 完整坐标 + 十字光标 + 最新价标签 | candles 120–500 + quote + indicatorResults | tick 8fps；REST 20s（日内）/60s（≥1H） | 1 | 高 |
| `MAIN_CHART` (`COMPACT`) | 小图 K 线 | K 线/线 + 2–3 条价格刻度 + 最新价线；无指标、无十字光标 | candles 60–120 + quote | tick 4fps；REST 60s | 4–6 | 中 |
| `MINI_CHART` | sparkline | 40–60 个收盘点的折线/面积；无文字、无坐标 | candles 收盘序列（60×1m 或 24×1H） | tick 4fps 只更新最后一点；REST 120s | 9 | 低 |
| `PRICE_CARD` | 价格卡 | 符号 + 大价格 + 24h% + 24H 高/低/量 + 可选 spark | quote + 可选 candles | tick 4fps；REST 60s（spark 120s） | 9 | 低 |
| `STAT_TILE` | 大盘指标格 | 标签 + 大数字 + 变化 + 更新时间/陈旧标记 | `OverviewRepository.overview` | 全局 120s；稳定币 600s | 4 | 低 |
| `WATCHLIST` | 自选列表 | 8–12 行：符号 + 价格 + 24h% + spark | `QuoteHub` + 每行缓存的收盘序列 | tick 4fps；spark REST 120s | 1（最多 2） | 中 |
| `MOVERS` | 涨跌榜 | 5–10 行：排名 + 符号 + 价格 + 24h% + 成交额 | OKX `/market/tickers?instType=SPOT`（可选 SWAP） | 15s | 2 | 低 |
| `TICKER_STRIP` | 跑马灯 | 横向滚动「符号 价格 涨跌」/ 大盘文本 | `QuoteHub` + `OverviewRepository` + Movers 快照 | tick 4fps；滚动 15–20fps | 1 | 低–中 |
| `COMPARE_CHART` (v2) | 对比叠加 | 2–4 条归一化 % 折线 + 图例 | 每个标的一条收盘序列 | tick 4fps；REST 60s | 1 | 中 |
| `COMPARE_TABLE` (v2) | 对比表 | 符号/最新价/24H/7D/成交额 | 同 Compare | 60s | 1 | 低 |

补充：

- `HEADER`、`FOOTER`、`CLOCK`、`CONN_BADGE` 属于 Board chrome，不是可绑定槽位；
  它们不参与 JSON 的 `root` 树，由布局选项控制显示。
- `ORDER_BOOK`、`TRADES_TAPE`、`HEATMAP` 是未来模块（OKX `books5`/`trades` 公共 WS 可支持），
  v1 不做；不要在 v1 的 ModuleType 枚举里放半成品。
- `MAIN_CHART` 的 `FULL` 必须整屏最多 1 个；解析时如果发现多个，只保留第一个，
  其余自动降级为 `COMPACT`（并在设置页提示）。

### 5.2 每个模块「格子里放什么」

| 模块 | 第一行 | 主体 | 底部/角标 |
|---|---|---|---|
| MainChart FULL | 符号 + TF + 最新价 + 24h% | K 线 + 成交量 + 指标 | 数据源/延迟/休市 |
| MainChart COMPACT | 符号 + TF | K 线 + 最新价线 | 24h%（可选） |
| MiniChart | —（由父模块提供标题） | sparkline | 窗口标签（可选） |
| PriceCard | 符号 + 市场类型（加密/美股） | 大价格 + 涨跌 pill | 24h 高/低/量 + spark |
| StatTile | 指标名（BTCD/TOTAL/…） | 大数字 | 24h 变化 + 更新时间 |
| Watchlist | 「自选」+ 管理入口 | N 行 | 最后更新时间 |
| Movers | 「涨幅/跌幅」Tab | N 行 | 统计范围（现货/含美股） |
| TickerStrip | — | 横向滚动条目 | — |
| CompareChart (v2) | 窗口 chips + 图例 | 多条归一化线 | 基准/更新时间 |

### 5.3 WebSocket：一条连接订阅 N 个标的

OKX 公共 WS：`wss://ws.okx.com:443/ws/v5/public`。一条连接、一条 `subscribe` 消息里可以放多个 arg：

```json
{"op":"subscribe","args":[
  {"channel":"tickers","instId":"BTC-USDT"},
  {"channel":"tickers","instId":"ETH-USDT"},
  {"channel":"tickers","instId":"SOL-USDT"}
]}
```

实现要点（数值以 OKX 官方文档为准，编码前复核）：

- 公共频道无需鉴权；一条 subscribe 消息总长度上限 64 KB；
- 每 IP 的连接请求约 3 次/秒；每连接 subscribe/unsubscribe/login 约 480 次/小时；
- `tickers` 频道最快约 100ms 推送一次，无事件不推；
- 9 个 `instId` 的 subscribe 消息约 500–700 字节，远低于上限。

**预算（9 宫格 / 4 宫格 / 双分屏共用）**：

| 项 | 数值 | 说明 |
|---|---|---|
| 唯一标的数 | ≤ 12（9 个可见 + 3 个缓冲） | 超过 12 的部分走 REST 快照 |
| WS 连接 | 1 条 | 绝不为每个格开连接 |
| subscribe 消息 | 布局切换时 1 条（含 N 个 arg） | 重连后再 1 条；远低于 480/h |
| 入站消息 | 最坏 12 × 10 msg/s = 120 msg/s | 解析放 IO 线程；按 instId conflate |
| UI 更新 | 聚焦 120ms；非聚焦 250–500ms | 合并后 4–8fps |
| 断线重连 | 1s→2s→4s→8s→30s，带抖动 | 重连期间 REST 兜底 |

**订阅优先级**（当布局里的唯一标的超过 12 个时）：

1. 主图/FollowMain 标的 + 所有 `MAIN_CHART` 叶子（最多 9 个）；
2. `TICKER_STRIP` 的前 3 个额外标的；
3. `WATCHLIST` 与剩余 ticker 项：不逐标的订阅，改用一次 `/market/tickers` 批量快照（15s）；
4. `MOVERS` 永远走批量快照，不逐标的订阅。

**去重规则**：

- 同一标的在多个格子复用同一个 `instId` 订阅（例如 L10 四格同币只订阅 1 次）；
- 同一标的在不同周期仍只订阅 1 次 tickers；K 线按 `(instId, tf)` 分开缓存；
- 布局切换时对 `desired` 集合做 diff：只 subscribe 新增、unsubscribe 移除；
  不要把整条连接断开重连；
- L13 的涨幅榜/跌幅榜共享**同一份** `/market/tickers` 快照，只是排序/取前 N 不同；
  Watchlist/Ticker 的 REST 溢出快照也复用这份 15s 快照，不要重复请求。

**非 OKX 标的**：

- 美股在 OKX 上有永续/代币化现货，使用 `Instrument.symbols["OKX"]`，可直接订阅；
- 若用户选了只有 Binance/Bybit 交易对的币：不放进 WS 预算，改用现有 REST
  `/ticker` 每 10s 轮询，角标显示「延迟」；不要为它单独开 Binance/Bybit WS（v1 范围外）。
- 任何请求都不带用户个人信息；User-Agent 用通用客户端名（现有代码是 `BtcTv/1.0`），
  不要加入邮箱/用户名。

### 5.4 REST：K 线与批量行情

OKX 公共 REST 限额（以官方文档为准）：

| 端点 | 限额 | 用途 |
|---|---|---|
| `GET /api/v5/market/tickers?instType=SPOT` | 20 次 / 2s（IP） | 全市场涨跌榜、ticker 兜底、watchlist 溢出快照 |
| `GET /api/v5/market/ticker?instId=...` | 20 次 / 2s（IP） | 单标的报价兜底 |
| `GET /api/v5/market/candles` | 40 次 / 2s（IP） | K 线（≤300 根/次；本项目 ≤500 分页） |
| `GET /api/v5/market/history-candles` | 20 次 / 2s（IP） | 更早历史（翻页） |

> **「批量 K 线」的准确含义**：OKX `/market/candles` 一次只接受一个 `instId`，没有真正的
> 多标的批量端点。这里的「批量」指客户端侧的**去重 + 并发上限 + 令牌桶 + 单飞（single-flight）**：
> 9 个 spark 需要 9 次请求，但同一 `(instId, tf)` 只发一次，且最多 4 个并发。
> 不要把它误解成「一个 HTTP 请求拿 9 个标的的 K 线」。

**9 宫格 / 4 宫格 / 多周期的实际请求量**：

| 场景 | 初始 | 周期刷新 | 折算 |
|---|---|---|---|
| 9 个 sparkline（60×1m/1H） | 9 次 candles | 每 120s 9 次 | 0.075 次/s |
| 4 个 COMPACT（120 根） | 4 次 candles | 每 60s 4 次 | 0.067 次/s |
| 1 个 FULL（120–500 根） | 1 次 candles（分页） | 每 20s 1 次（日内） | 0.05 次/s |
| 同币多周期（4 个 TF） | 4 次 candles | 每 60s 4 次 | 0.067 次/s |
| 涨跌榜 | 1 次 tickers（约 1242 条） | 每 15s 1 次 | 0.067 次/s |
| Ticker 兜底 | 1 次 tickers | 每 10s 1 次 | 0.1 次/s |
| 大盘全局 | 1 次 CoinGecko/CoinPaprika | 每 120s 1 次 | 0.008 次/s |
| 稳定币 | 1 次 DefiLlama | 每 600s 1 次 | 0.002 次/s |
| **合计（最坏稳态）** | ~15 次突发 | — | **~0.44 次/s** |

对照 candles 限额 20 次/s（40 次/2s），稳态占用约 2%；即使所有刷新撞在一起，
15 次突发也低于 20 次/2s 的限额。

**节流策略**：

1. **并发上限**：`Semaphore(4)`；初始加载 9 个标的时不要一次性 `async` 9 个，
   弱网下容易一起超时。
2. **令牌桶**：在 `MarketClient` 外层加全局 `RateLimiter`（建议 10 req/s、突发 20），
   把搜索目录、K 线、报价、涨跌榜都纳入，避免某条路径把 IP 额度打满。
3. **K 线长度按模块分级**：FULL 120–500；COMPACT 60–120；MINI/PRICE_CARD spark 40–60；
   不要给 sparkline 拉 500 根。
4. **单飞 + 缓存**：`CandleStore` 以 `(instId, tf, limit)` 为 key；同 key 并发请求合并；
   `fetchedAt` 未过期直接返回；布局切换不重复拉。
5. **本地 tick 修补**：WS 报价到达时用 `applyTick` 更新最后一根 K 线，
   不要每次 tick 都重拉 candles（现有 `MarketViewModel` 已用这个思路）。
6. **自适应刷新**：日内 20s；≥1H 60s；≥1D 180s；日线以上 300s；页面不可见时暂停。
7. **失败退避**：单标的失败只影响该格；2s→4s→8s→30s；连续 3 次后该格显示「数据延迟」，
   不阻塞其它格。

### 5.5 大盘数据映射（StatTile）

`MarketOverview` 已经包含所有需要的字段；`StatKind` 与字段映射：

| StatKind | 显示 | 字段 | 格式 | 刷新 |
|---|---|---|---|---|
| `BTCD` | BTC 市占率 | `btcDominance` | `56.5%` | 全局 TTL 120s |
| `TOTAL` | 加密总市值 | `totalMarketCapUsd` | `$2.31T` / `$986.4B` | 全局 TTL 120s |
| `STABLE_MCAP` | 稳定币市值 | `stablecoinMarketCapUsd` | `$286.8B` | 稳定币 TTL 600s |
| `STABLE_DOM` | 稳定币占比 | `stablecoinDominancePct`（= stable / total） | `12.4%` | 依赖两者，取较旧者的时间 |

显示规则：

- `stale=true`：保留旧值，右下角显示「更新于 X 分钟前」，不要清空；
- 字段为 `null`：显示 `—`，并显示「暂无数据」，不要显示 `0`；
- 源名：`source` 显示在 tile 底部小字（例如 `CoinGecko + DefiLlama`）；
- 单位：市值用 T/B 缩写，保留 2 位小数；占比保留 1 位小数；
- 颜色：24h 变化为正用上涨色，负用下跌色；`null` 用中性色。

### 5.6 失败与降级矩阵

| 故障 | 表现 | 降级 |
|---|---|---|
| OKX WS 断开 | 所有格价格停住 | 1s→30s 重连；期间 `/market/tickers` 每 10s 兜底；角标「延迟」 |
| 单个标的 WS 无推送 | 只有该格不动 | 该格单独 `/market/ticker` 每 10s；不拖累其它格 |
| candles 失败 | 该格图为空 | 保留旧 K 线 + 「数据延迟」；重试 3 次后显示占位 |
| 涨跌榜失败 | 榜单空 | 保留上次快照 + 时间戳 |
| 大盘接口 429 | tile 空 | 保留上次值 + 「更新于 X 分钟前」；`OverviewRepository` 退避 |
| 标的在 OKX 不可用 | 该格无法订阅 | 走 REST 10s 轮询并标「延迟」；搜索弹层标注「仅 REST」 |
| 美股休市 | 价格不动 | 显示「休市」而不是「离线」；保留最后价 |
| 低端设备掉帧 | 动画卡 | 进入 `performanceMode=LOW`：2fps、关闭 ticker、spark 静态化 |
| JSON 布局损坏 | 无法解析 | 回退 `classic`，保留原始字符串，下次成功保存时覆盖 |
| 槽位固定标的解析失败 | 该格无数据 | 回退 `FollowMain`，设置页提示 |

---

## 6. 数据模型与持久化

### 6.1 类型定义（Kotlin）

```kotlin
enum class ModuleType {
    MAIN_CHART, MINI_CHART, PRICE_CARD, STAT_TILE,
    WATCHLIST, MOVERS, TICKER_STRIP
    // v2: COMPARE_CHART, COMPARE_TABLE
}

enum class Direction { H, V }                 // H = 左右分，V = 上下分

sealed interface LayoutNode {
    data class Split(
        val direction: Direction,
        val weights: List<Float>,             // 长度 = children.size，2..4
        val children: List<LayoutNode>
    ) : LayoutNode

    data class Leaf(val slot: Slot) : LayoutNode
}

data class Slot(
    val id: String,                            // 稳定 id，如 "main" / "r1c1" / "slot_3"
    val module: ModuleType,
    val binding: SlotBinding,
    val params: SlotParams = SlotParams()
)

sealed interface SlotBinding {
    /** 图表/迷你图/价格卡：跟随全局主标的 */
    data object FollowMain : SlotBinding

    /** 图表/迷你图/价格卡：固定标的（存完整 Instrument） */
    data class Fixed(val instrument: Instrument) : SlotBinding

    /** StatTile：一个格子可以只显示一个 kind，也可以显示多个（默认一个） */
    data class Overview(val kinds: List<StatKind>) : SlotBinding

    /** 涨跌榜 */
    data class Movers(
        val scope: MoversScope = MoversScope.SPOT_USDT,
        val metric: MoversMetric = MoversMetric.GAINERS,
        val limit: Int = 5
    ) : SlotBinding

    /** 自选列表 */
    data class Watchlist(
        val source: WatchlistSource = WatchlistSource.FAVORITES,
        val limit: Int = 10,
        /** source=CUSTOM 时使用；最多 12 个 */
        val instruments: List<Instrument> = emptyList()
    ) : SlotBinding

    /** 跑马灯 */
    data class Ticker(
        val source: TickerSource = TickerSource.FAVORITES,
        val instruments: List<Instrument> = emptyList()
    ) : SlotBinding
}

enum class StatKind { BTCD, TOTAL, STABLE_MCAP, STABLE_DOM }
enum class MoversScope { SPOT_USDT, SWAP_USDT, ALL_OKX }
enum class MoversMetric { GAINERS, LOSERS }
enum class WatchlistSource { FAVORITES, HOT, CUSTOM }
enum class TickerSource { FAVORITES, HOT, CUSTOM }

sealed interface TfMode {
    data object FollowMain : TfMode
    data class Fixed(val tf: TF) : TfMode
}

enum class ChartDetail { AUTO, FULL, COMPACT }
enum class SparkWindow { H1, H24, D7 }
enum class TickerSpeed { SLOW, NORMAL, FAST }
enum class PerformanceMode { AUTO, HIGH, LOW }

data class SlotParams(
    val tf: TfMode = TfMode.FollowMain,
    val detail: ChartDetail = ChartDetail.AUTO,
    val sparkWindow: SparkWindow = SparkWindow.H24,
    val showSpark: Boolean = true,
    val visibleBars: Int? = null,              // null = 按 detail 默认
    val showAxes: Boolean = true,
    val speed: TickerSpeed = TickerSpeed.NORMAL
)

data class LayoutSpec(
    val version: Int = 1,
    val presetId: String,                      // "classic" / "chart_macro" / "custom" ...
    val name: String = "",                     // 自定义布局显示名
    val root: LayoutNode,
    val gutter: Float = 12f,                   // 1080p 设计单位；缩放见 §3.3
    val options: LayoutOptions = LayoutOptions()
) {
    val legacy: Boolean get() = presetId == "classic"

    companion object {
        fun classic(): LayoutSpec = LayoutSpec(
            presetId = "classic",
            root = LayoutNode.Leaf(
                Slot(
                    id = "main",
                    module = ModuleType.MAIN_CHART,
                    binding = SlotBinding.FollowMain,
                    params = SlotParams(detail = ChartDetail.FULL)
                )
            )
        )
    }
}

data class LayoutOptions(
    val syncTf: Boolean = false,               // 多图是否共享周期（默认否；L10 用锁定的 Fixed）
    val antiBurn: Boolean = true,
    val performanceMode: PerformanceMode = PerformanceMode.AUTO
)
```

说明：

- `Slot.binding` 只回答「数据是谁」，`Slot.params` 回答「怎么显示」。
  不要把 TF 塞进 `Fixed`，否则同一个标的的两个周期会变成两个 binding，难以复用。
- `MAIN_CHART` 的 `detail=AUTO` 由 Board 决定：布局里标记为主图/全屏的格子用 `FULL`，
  其余用 `COMPACT`；预设显式写 `FULL/COMPACT` 更可控。
- `Slot.id` 必须在整棵树内唯一；预设用语义 id（`main`、`btcd`、`movers`），
  自定义布局用 `slot_1`、`slot_2`，删除/再分时不要复用到旧 id。
- `LayoutSpec.legacy` 不持久化，由 `presetId=="classic"` 派生。

### 6.2 JSON 持久化格式

存储位置：`SharedPreferences("btc_tv")`，key = **`layout_spec_v1`**，value = 字符串。
不写新文件、不引新依赖，沿用现有 `org.json` 做法。

**原则**：

- 只存树、选项和完整 `Instrument` JSON；**不存运行时矩形**（矩形由树 + gutter 算出）；
- 省略默认值，减小体积；解析时缺省用默认值；
- `Instrument` 用 `instrumentToJson()`（`InstrumentCatalog.kt:12`）的完整输出，
  不要只存 `id`；
- 顶层 `v` 用于未来迁移；当前 `v=1`。

**示例：L02 单图 + 大盘栏**：

```json
{
  "v": 1,
  "preset": "chart_macro",
  "name": "",
  "gutter": 12,
  "root": {
    "type": "split",
    "dir": "H",
    "weights": [2, 1],
    "children": [
      {
        "type": "leaf",
        "slot": {
          "id": "main",
          "module": "MAIN_CHART",
          "binding": { "kind": "follow" },
          "params": { "tf": "follow", "detail": "full" }
        }
      },
      {
        "type": "split",
        "dir": "V",
        "weights": [26, 59],
        "children": [
          {
            "type": "split",
            "dir": "V",
            "weights": [1, 1],
            "children": [
              {
                "type": "split",
                "dir": "H",
                "weights": [1, 1],
                "children": [
                  {
                    "type": "leaf",
                    "slot": {
                      "id": "btcd",
                      "module": "STAT_TILE",
                      "binding": { "kind": "overview", "kinds": ["BTCD"] }
                    }
                  },
                  {
                    "type": "leaf",
                    "slot": {
                      "id": "total",
                      "module": "STAT_TILE",
                      "binding": { "kind": "overview", "kinds": ["TOTAL"] }
                    }
                  }
                ]
              },
              {
                "type": "split",
                "dir": "H",
                "weights": [1, 1],
                "children": [
                  {
                    "type": "leaf",
                    "slot": {
                      "id": "stable_mcap",
                      "module": "STAT_TILE",
                      "binding": { "kind": "overview", "kinds": ["STABLE_MCAP"] }
                    }
                  },
                  {
                    "type": "leaf",
                    "slot": {
                      "id": "stable_dom",
                      "module": "STAT_TILE",
                      "binding": { "kind": "overview", "kinds": ["STABLE_DOM"] }
                    }
                  }
                ]
              }
            ]
          },
          {
            "type": "leaf",
            "slot": {
              "id": "movers",
              "module": "MOVERS",
              "binding": { "kind": "movers", "scope": "SPOT_USDT", "metric": "GAINERS", "limit": 5 }
            }
          }
        ]
      }
    ]
  },
  "options": { "syncTf": false, "antiBurn": true, "performanceMode": "AUTO" }
}
```

**固定标的示例**（L08 左上）：

```json
{
  "type": "leaf",
  "slot": {
    "id": "tl",
    "module": "MAIN_CHART",
    "binding": {
      "kind": "fixed",
      "instrument": {
        "id": "BTCUSDT",
        "name": "BTC / USDT",
        "fullName": "",
        "type": "CRYPTO",
        "base": "BTC",
        "quote": "USDT",
        "decimals": 2,
        "prefix": "$",
        "accent": 4294415130,
        "glyph": "₿",
        "hot": true,
        "stockKind": "",
        "symbols": { "OKX": "BTC-USDT", "Binance": "BTCUSDT", "Bybit": "BTCUSDT" }
      }
    },
    "params": { "tf": "follow", "detail": "compact" }
  }
}
```

> `accent` 是现有 `Long` 颜色值；示例只展示格式，实际以 `instrumentToJson()` 输出为准。

**默认值**：

- 缺失 `params` → `SlotParams()`；
- 缺失 `weights` 或非法 → 等分；
- 缺失 `gutter` → 12；
- 缺失 `options` → 默认 `syncTf=false, antiBurn=true, performanceMode=AUTO`；
- 缺失 `name` → 空字符串。

### 6.3 默认值、迁移与校验

| 情况 | 行为 |
|---|---|
| key 不存在（老用户） | 返回 `LayoutSpec.classic()`，**不写回**；界面走 `TvScreenInner` |
| JSON 解析失败 | 返回 classic；保留原始字符串，下次成功保存时覆盖；不弹窗 |
| `v` 不认识 | 返回 classic；记录一次日志（不弹窗） |
| `preset` 不认识但 `root` 合法 | 按 `root` 渲染（宽容），设置页显示「未知预设」 |
| `root` 为空/非法 | 返回 classic |
| `weights` 长度与 children 不一致 | 丢弃该 Split，回退 classic |
| `weights` 有 NaN/∞/≤0 | clamp 到 0；全 0 时等分 |
| `rect`/比例越界 | 树结构天然不会越界；运行时把叶子矩形 clamp 到父矩形 |
| `MAIN_CHART` 多于 1 个 FULL | 保留第一个 FULL，其余降级 COMPACT |
| 固定标的解析失败 | 该槽位回退 `FollowMain` |
| `Slot.id` 重复 | 保留第一个，丢弃重复；日志记录 |
| 自定义布局/预设布局切换 | 不改变全局 `instrument`/`tf`；只有 FollowMain 槽位跟随 |
| 恢复默认 | 删除 `layout_spec_v1` 或写入 classic；回到 L01 |

**保存前校验规则**：

1. 叶子数 1..16；Split 子节点数 2..4；树深度 ≤6；
2. 每个 `weights[i]` 为有限正数；归一化后每个叶子在 1080p 下宽/高 ≥ 0.10（192×108）；
3. 整屏 `MAIN_CHART detail=FULL` 最多 1 个；
4. `STAT_TILE` 的 `kinds` 非空且去重，最多 4 个；
5. `MOVERS.limit` 在 3..10；`WATCHLIST.limit` 在 6..12；`Ticker` 标的数 1..20；
6. `COMPARE`（v2）标的数 2..4；
7. 固定标的必须至少有一个数据源 symbol；没有 OKX symbol 时允许保存，但 UI 标注「仅 REST」。

**迁移策略**：

- 当前只有 `v=1`；未来字段变更时在 `LayoutJson` 里按 `v` 分支，不修改旧 key；
- 新增 `ModuleType` 时，旧客户端遇到未知 module → 该叶子显示「不支持的模块」，
  不崩溃；或者整体回退 classic（推荐后者，避免半屏空白）；
- 旧 `Prefs` 的 `instrument`/`tf`/`favorites`/`indicator_settings` 完全不动。

### 6.4 设置里的交互

#### 6.4.1 入口与布局选择

设置面板新增「看板布局」区块（放在「图表样式」之前）：

- 当前布局显示为「经典单图（默认）」或所选布局名 + 是否自定义；
- 3 列网格（1080p），每张卡片 320×180；720p 用 2 列；
- 卡片内容：
  - Canvas 缩略图：按 `LayoutNode` 递归画色块；`MAIN_CHART`=蓝、`MINI_CHART`=绿、
    `PRICE_CARD`=青、`STAT_TILE`=琥珀、`WATCHLIST`=紫、`MOVERS`=橙、`TICKER`=灰；
  - 名称（中文）+ 一行描述 + 徽章（「默认」「需要大盘数据」「进阶」）；
  - 当前选中：accent 边框；焦点：2dp 暖色边框 + 1.04 缩放（沿用现有 FocusBorder 色）。
- 焦点移动时，右侧/下方显示 16:9 大预览 + 适用场景文字；
- `OK` 应用并持久化；关闭设置回到看板，顶部浮层提示 1.5s：
  「已切换到「单图 + 大盘栏」· MENU 换标的 · OK 全屏 · BACK 返回」；
- 「恢复默认布局」= 回到 L01，并删除/忽略 `layout_spec_v1`；
- 从经典切到 Board 时，不改变全局 `instrument`/`tf`；从 Board 切回经典时，
  FollowMain 槽位最后一次选的主标的/周期写回 `Prefs`（见 §7.6）。

#### 6.4.2 槽位设置（复用现有搜索弹层）

选中一个布局后，设置面板下方出现「格子设置」列表（按阅读顺序）：

```
┌ 槽位      模块        当前内容                 操作 ┐
│ 主图      MainChart   BTC/USDT · 跟随主标的     OK 换标的 │
│ 右上      StatTile     大盘 BTCD                OK 选项   │
│ 右下      Movers       24h 涨幅 Top5            OK 选项   │
└──────────────────────────────────────────────────────┘
```

- 在槽位行按 `OK`：
  - 图表/迷你图/价格卡 → 打开现有 `SearchOverlay`，增加 `targetSlotId`：
    - 标题：「为「右上格」选择标的」；
    - 顶部显示当前绑定；
    - 第一行固定是「跟随主标的」（仅 chart 类槽位）；
    - 选中后写回该槽位的 `Fixed` 绑定，**不改变全局 `instrument`**；
    - 选「跟随主标的」→ `FollowMain`。
  - `StatTile` → 打开「选择指标」小面板（四个 kind 的开关，最多 4 个）；
  - `Movers` → 打开「现货/永续/全部 + 涨幅/跌幅 + 条数（3/5/8/10）」；
  - `Watchlist` → 打开「收藏/热门/自定义 + 行数（6/8/10/12）」；
    自定义时再打开一次多选搜索，最多 12 个标的；
  - `Ticker` → 打开「收藏/热门/自定义 + 速度」。
- `◀▶` 在槽位行上：
  - chart 类：循环该槽位的 TF（含「跟随主标的」）；
  - Movers：切换 3/5/8/10 条；
  - Watchlist：切换 6/8/10/12 行。
- 长按 `OK`：恢复该槽位的预设默认绑定；
- 所有修改即时保存（debounce 300ms），不需要「保存」按钮。

#### 6.4.3 自定义分割（Custom Split）

「自定义」卡片进入向导，分两步：

**第 1 步：结构**

- 先选一个基础结构：
  - 单格、左右 1:1、左右 1:2、左右 2:1、上下 1:1、上下 1:2、上下 2:1、
    `1:1:1` 三列/三行、1 大 2 小、2×2、3×2、3×3；
- 实时预览（缩略图 + 16:9 大图）；
- `OK` 进入编辑；`BACK` 取消。

**第 2 步：编辑叶子**

- 焦点在叶子上；`MENU` 打开操作菜单：
  - 「水平/垂直再分」→ 选比例（1:1 / 1:2 / 2:1 / 1:1:1 / 自定义）；
  - 「删除此格」→ 与相邻兄弟合并；
  - 「换模块」→ 模块选择器；
  - 「换标的」→ 搜索弹层（chart 类）或模块选项；
- 比例自定义：`◀▶` 以 5% 步进调整权重，`OK` 确认，`BACK` 撤销上一步；
- 叶子数上限 16、深度上限 6、最小格 0.10×0.10；达到上限时禁用「再分」并提示；
- 编辑过程中不请求实时数据，预览用假数据/缓存的最后一帧；
- 「完成」→ 生成 `presetId="custom"` 的 `LayoutSpec`，保存并应用；
- v1 只保留 **1 个** 自定义布局（`custom`）；「另存为多个」放 v2，避免 Prefs 膨胀。

**模块选择器**：

- 列表：MainChart（全功能/紧凑）、MiniChart、PriceCard、StatTile、Watchlist、Movers、TickerStrip；
- 每个条目显示一句「需要什么数据 / 适合多大格子」；
- 不兼容的组合不隐藏，而是标注（例如「MainChart FULL 整屏最多 1 个」），
  保存时自动降级。

**绑定选择器**：

- chart 类：跟随主标的 / 选择标的（搜索弹层）；
- StatTile：四个 kind 复选；
- Movers：scope + metric + limit；
- Watchlist：source + limit；
- Ticker：source + 最多 20 个标的。

#### 6.4.4 设置页与运行时的接口

建议新增的 `UiActions`（只列接口，具体实现见 §9）：

```kotlin
data class BoardActions(
    val selectPreset: (String) -> Unit = {},
    val applyLayoutSpec: (LayoutSpec) -> Unit = {},
    val resetLayout: () -> Unit = {},
    val setFocusedSlot: (String) -> Unit = {},
    val toggleFullscreen: () -> Unit = {},
    val openSlotSearch: (String) -> Unit = {},
    val selectInstrumentForSlot: (String, Instrument) -> Unit = {},
    val setSlotModule: (String, ModuleType) -> Unit = {},
    val setSlotBinding: (String, SlotBinding) -> Unit = {},
    val setSlotParam: (String, String, String) -> Unit = {}
)
```

实现时可以合并进现有 `UiActions`，但**不要**把 Board 专用的十几个回调散落在各个 Composable 参数里；
用一个 `BoardActions` 聚合，减少 `Screen.kt` 的签名改动。

---

## 7. 遥控器交互

### 7.1 模式机

Board 只允许一个「活动层」，避免按键同时作用到下层：

```kotlin
enum class BoardMode {
    BOARD,        // 在格子间移动
    SLOT_LIST,    // 焦点进入某个列表模块内部
    FULLSCREEN,   // 某一格全屏
    SEARCH,       // 搜索弹层（全局或目标槽位）
    SETTINGS      // 布局/槽位设置
}

enum class FocusDirection { LEFT, RIGHT, UP, DOWN }  // 与 LayoutNode.Split 的 H/V 区分开

data class BoardState(
    val spec: LayoutSpec,
    val focusedSlotId: String?,       // BOARD/SLOT_LIST/FULLSCREEN 的当前格
    val fullscreenSlotId: String? = null,
    val listSlotId: String? = null,   // 正在内部导航的列表格
    val searchTargetSlotId: String? = null,
    val editing: Boolean = false      // 自定义分割编辑中
)

fun boardMode(settingsOpen: Boolean, searchOpen: Boolean, b: BoardState): BoardMode = when {
    // 覆盖层优先：设置/搜索打开时，Board 根节点不可聚焦
    settingsOpen || b.editing -> BoardMode.SETTINGS
    searchOpen || b.searchTargetSlotId != null -> BoardMode.SEARCH
    b.fullscreenSlotId != null -> BoardMode.FULLSCREEN
    b.listSlotId != null -> BoardMode.SLOT_LIST
    else -> BoardMode.BOARD
}
```

- 设置/搜索打开时，Board 根节点 `focusProperties { canFocus = false }`，按键交给面板；
  这与现有 `Screen.kt:112` 的做法一致。
- 经典布局（`presetId="classic"`）**完全不使用**这套模式机，继续走现有
  `routeKey()` + `BackHandler`，保证老用户按键逐键不变。
- 从 Board 切回经典时，清空 `fullscreenSlotId`/`listSlotId`/`focusedSlotId`；
  全局 `instrument`/`tf` 保留 FollowMain 槽位最后一次的值。

### 7.2 按键映射

| 按键 | BOARD | SLOT_LIST | FULLSCREEN | SEARCH | SETTINGS |
|---|---|---|---|---|---|
| `▲▼` | 在格子间上下移动焦点 | 列表行上下滚动 | 聚焦图：缩放；列表：滚动 | 结果列表移动 | 菜单/卡片移动 |
| `◀▶` | 左右移动焦点；无邻居时对聚焦图切 TF；长按强制切 TF | Movers 切涨/跌；Watchlist 无操作 | 聚焦图：切 TF | 无操作 | 调整比例/选项 |
| `OK/ENTER` | 聚焦格全屏 | 激活当前行 | 退出全屏 | 选中结果 | 应用/进入 |
| `MENU/SEARCH` | 聚焦图：换标的；非图表：模块选项 | 列表选项 | 换标的/模块选项 | 无操作 | 无操作 |
| `BACK` | 系统默认（退出）；可在设置改为打开设置 | 回到 BOARD | 回到 BOARD，恢复原焦点 | 取消搜索，回到原层 | 返回上一级/关闭 |

**焦点移动与切周期的冲突（重要）**：任务书同时要求 D-pad 移动焦点和 ◀▶ 切周期，
两者都占用左右键。Board 采用以下优先级，保证两件事都能做到：

1. **BOARD 短按 ◀▶**：优先移动到该方向最近的焦点邻居（几何算法见 §7.3）；
2. **该方向没有邻居时**：如果聚焦格是图表格，短按 ◀▶ 切 TF；非图表格给 0.8s 提示；
3. **BOARD 长按 ◀▶（≥400ms）**：强制切聚焦图的 TF，不移动焦点；
4. **FULLSCREEN 短按 ◀▶**：始终切 TF（全屏只有一格，没有焦点冲突）；
5. Footer/提示条显示：「◀▶ 移动 · 长按切周期 · OK 全屏」，让用户能发现长按。

> §4 各布局里写的「◀▶ 改焦点格周期」都指这套规则：全屏短按或 BOARD 长按。
> 如果产品上更希望「BOARD 短按 ◀▶ 始终切周期」，则需要把水平焦点移动改成
> 「长按方向键」或增加组合键；这会牺牲一部分 D-pad 直觉，列入 §10.2 待确认。

补充规则：

- **BOARD 的 ◀▶**：只有 `MAIN_CHART`/`MINI_CHART`/`PRICE_CARD` 这类有 TF 或窗口的格子响应；
  `STAT_TILE`/`MOVERS`/`WATCHLIST`/`TICKER_STRIP` 上按 ◀▶ 不改变布局，
  可显示 0.8s 的轻提示「该格没有周期」，避免用户以为按键坏了。
- **FULLSCREEN 的 ▲▼**：沿用现有 `zoom` 的档位（45/60/90/120/180/240 根）；
  非图表全屏时 ▲▼ 滚动内容。
- **SLOT_LIST 的 OK**：
  - Watchlist 行 → 若布局里有 `FollowMain` 主图，把全局主标的换成该标的；
    若没有主图（如 L13），打开该标的的全屏图；
  - Movers 行 → 同样处理（换主图或打开全屏图）；
  - 不要为新标的打开新 Activity，保持单 Activity 架构。
- **SLOT_LIST 的 BACK**：退出列表，焦点回到该列表格；不要直接退出 Board。
- **长按 OK**（可选，v1 可不做）：把聚焦格周期应用到同布局所有图表格（类似 TradingView 的同步）。
- **长按 BACK**（可选）：从任意层直接回到 BOARD 根。
- 所有动画/焦点移动必须是 `KeyDown` 单次触发，不能因长按重复触发切 TF 风暴；
  长按 ◀▶ 用 400ms 阈值 + 只触发一次。

### 7.3 几何焦点导航

不要用「按树的前序/后序」移动焦点；用户是按屏幕位置理解方向的。用叶子矩形的中心点做几何评分：

```kotlin
data class RectF(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
}

fun nextSlot(current: RectF, candidates: List<Pair<String, RectF>>, dir: FocusDirection): String? {
    val eps = 0.5f
    val pool = candidates.filter { (_, r) ->
        when (dir) {
            FocusDirection.RIGHT -> r.cx > current.cx + eps
            FocusDirection.LEFT  -> r.cx < current.cx - eps
            FocusDirection.DOWN  -> r.cy > current.cy + eps
            FocusDirection.UP    -> r.cy < current.cy - eps
        }
    }
    if (pool.isEmpty()) return null

    fun overlap(a0: Float, a1: Float, b0: Float, b1: Float): Float =
        max(0f, min(a1, b1) - max(a0, b0))

    return pool.minByOrNull { (_, r) ->
        val primary = when (dir) {
            FocusDirection.RIGHT -> r.x - (current.x + current.w)
            FocusDirection.LEFT  -> current.x - (r.x + r.w)
            FocusDirection.DOWN  -> r.y - (current.y + current.h)
            FocusDirection.UP    -> current.y - (r.y + r.h)
        }.coerceAtLeast(0f)
        val perpendicular = when (dir) {
            FocusDirection.RIGHT, FocusDirection.LEFT -> abs(r.cy - current.cy)
            FocusDirection.UP, FocusDirection.DOWN -> abs(r.cx - current.cx)
        }
        val crossOverlap = when (dir) {
            FocusDirection.RIGHT, FocusDirection.LEFT ->
                overlap(current.y, current.y + current.h, r.y, r.y + r.h)
            FocusDirection.UP, FocusDirection.DOWN ->
                overlap(current.x, current.x + current.w, r.x, r.x + r.w)
        }
        // 有纵向/横向重叠的候选优先；没有重叠时按中心距离
        val penalty = if (crossOverlap > 0f) 0f else 1_000f
        primary + perpendicular * 0.5f + penalty
    }?.first
}
```

- 这个评分能处理 L07「左大右两小」：从左上大格按右，会选与它纵向中心更近的右上格；
  从右上按下，会落到右下，而不是跳回左边。
- 9 宫格：从中间格按右，优先同行的右格（纵向重叠），不会跨到下一行。
- 没有候选时焦点不动；不要循环到对角线。
- 焦点顺序只在同层叶子之间；列表内部行导航由模块自己处理（`SLOT_LIST`）。
- 焦点状态只存 `focusedSlotId`，矩形每次从布局树重算，布局切换后自然失效。

### 7.4 焦点环

- 由 Board 父层统一绘制：在每个叶子的矩形外画 2dp accent 边框 + 8px 外发光；
  同时把该格标题行文字提亮 10%。
- 模块内部**不画**焦点边框；列表行焦点是另一种样式（行背景高亮 + 左侧 3dp 竖条），
  由列表模块自己画。
- 全屏时焦点环隐藏（只有一格），但标题行仍显示。
- 焦点环不参与布局测量（用 `drawBehind`/overlay），不能因为画边框把格子撑大。
- 颜色沿用现有 `FocusBorder`（暖色 `0xFFFFD79A`），与 Settings 的焦点视觉一致。

### 7.5 全屏（OK）

- `OK` 在 BOARD 上把 `fullscreenSlotId = focusedSlotId`；Board 只组合该叶子，
  其余叶子不组合（不是透明遮挡），节省绘制与订阅。
- 全屏矩形 = content box（或保留 12px 内边距）；全屏内容按模块类型决定：

| 聚焦模块 | 全屏内容 |
|---|---|
| `MAIN_CHART` | 原图升为 `FULL`（指标/坐标/十字光标） |
| `PRICE_CARD` / `MINI_CHART` | 用该槽位 binding 的标的打开一张 `FULL MAIN_CHART`；不修改槽位本身 |
| `STAT_TILE` | 大号指标 + 源名 + 更新时间 + stale 提示 |
| `WATCHLIST` / `MOVERS` | 全屏列表（行数增加，仍可滚动） |
| `TICKER_STRIP` | 大号 ticker（速度/内容不变） |

- 数据订阅不变：全屏不会新增第二条 WS；`QuoteHub` 只把该标的标为高优先级（120ms）。
- `BACK` 或再次 `OK` 退出全屏，恢复 `focusedSlotId` 与原布局；不要重建 Board 状态。
- 如果全屏期间布局被设置页改掉（例如槽位被删除）：退出全屏，焦点落到第一个叶子。
- 全屏时 `MENU` 仍可用：图表格换该格标的，非图表格打开模块选项；
  `◀▶` 图表格切 TF，非图表格提示无周期；`▲▼` 缩放图表。
- `PRICE_CARD`/`MINI_CHART` 的全屏 K 线使用该槽位的 TF；没有 TF 时用 FollowMain。
  全屏里按 `◀▶` 只影响本次全屏会话，不改槽位参数；需要持久化请在设置页改槽位 TF。

### 7.6 换标的（MENU）与搜索目标槽位

- BOARD 上 `MENU`：
  - 聚焦 chart 类槽位 → 打开 `SearchOverlay`，`searchTargetSlotId = focusedSlotId`；
  - 聚焦 `STAT_TILE`/`MOVERS`/`WATCHLIST`/`TICKER_STRIP` → 打开对应模块选项，
    不打开标的搜索（避免用户误以为能换标）。
- 搜索弹层变化：
  - 标题：「为「右上格」选择标的」；顶部显示当前绑定；
  - 第一行固定「跟随主标的」（仅 chart 类）；
  - 选中结果 → `selectInstrumentForSlot(slotId, inst)`，写该槽位的 `Fixed`，
    **不改变全局 `instrument`**；
  - 选「跟随主标的」→ 该槽位 `FollowMain`；
  - `BACK` 取消，不改任何槽位。
- **L10 同币多周期例外**：四格都是 `FollowMain`；MENU 改的是共享主标的，
  四格一起变。不要给四格分别设不同标的（否则就不是「同币多周期」了）。
- **L03 自选列表例外**：在列表内选一行，应把全局主图换成该标的（`setInstrument`），
  而不是改槽位绑定；因为用户的心智是「自选 → 主图」。
- 搜索结果如果只有 Binance/Bybit symbol：允许选，但该格显示「延迟」并走 REST；
  不要让整个布局因为一个标的不可 WS 订阅而失效。

### 7.7 BACK 层级

```
FULLSCREEN  --BACK-->  BOARD（恢复原焦点）
SLOT_LIST   --BACK-->  BOARD（焦点回到该列表格）
SEARCH      --BACK-->  打开搜索前的层（取消，不保存）
SETTINGS    --BACK-->  上一级；根级关闭设置 -> BOARD
BOARD       --BACK-->  系统默认（退出 App）
```

- 设置页内部还有「展开指标参数」等层级，沿用现有 `settingsBackAction()`；
  Board 设置页自己维护 `settingsPage`（布局列表 / 槽位编辑 / 自定义向导）。
- 经典布局的 BACK 行为不变：只有搜索/设置打开时才拦截，其余交给系统。
- 如果用户觉得 BOARD 根层 BACK 直接退出太突然，可在设置里提供
  「BACK 在根层打开布局设置」开关；默认关闭，保持平台习惯。

### 7.8 与现有按键路由的边界

- 现有 `routeKey(settingsOpen, searchOpen, key)` 继续服务经典布局；
- Board 使用新的 `routeBoardKey(mode, key): BoardIntent`；
- 在 `AppEntry`/`BoardScreen` 里只根据 `spec.legacy` 二选一，不要在一个函数里塞两套语义；
- 所有 Board 按键在 `KeyDown` 时消费并返回 `true`，避免系统同时处理；
- 搜索/设置打开时，Board 根节点不可聚焦（同现有 `focusProperties`），
  防止「遥控器控制下面那层」。

---

## 8. 性能与稳定性

### 8.1 多 Canvas 与 Compose 重组

**风险**：9 个 `Canvas` 本身不贵，贵的是每个格子都跑一套 `PriceChart`（坐标文字、
`TextMeasurer`、十字光标 `pointerInput`、指标 Path），以及每个 tick 都复制一份 `UiState`
导致整棵树重组。

**对策**：

1. **分级渲染**：
   - 整屏最多 1 个 `FULL`：复用现有 `PriceChart`，保留指标/坐标/十字光标；
   - `COMPACT`：新建轻量 `CompactChart`，只画 K 线/折线 + 2–3 条价格刻度 + 最新价线；
     **不注册 `pointerInput` 十字光标**，不做指标；
   - `MINI`/`PRICE_CARD` spark：新建 `Sparkline`，只画 40–60 点折线，不画坐标/文字。
2. **按格拆分状态**：
   - 不要把 `quotes: Map<String, Quote>` 塞进一个巨大的 `UiState` 然后每 120ms 复制；
   - 用 `StateFlow<Map<String, QuoteSnapshot>>` + 每个模块 `collectAsState`；
   - 或者用一个 `BoardSnapshot`，但用 `key(slot.id)` 和 `derivedStateOf` 限定重组范围；
   - 焦点/全屏/列表状态与行情状态分开，移动焦点不应触发行情格重组。
3. **绘制缓存**：
   - `Canvas` 用 `drawWithCache`，只在数据/尺寸变化时重建 `Path`；
   - 价格刻度文字按 `(tf, priceDecimals, step)` 缓存，不要每帧 `TextMeasurer.measure`；
   - sparkline 用 `FloatArray`/复用 `Path`，不要每帧创建大量 `Offset`/`List`；
   - 静态网格/边框用 `drawBehind`，不要每帧重画。
4. **背景只画一次**：现有整屏渐变/氛围光保留在 Board 根节点；每个格子只画半透明卡片底色，
   不要每格再画一层径向渐变，避免 overdraw。
5. **动画隔离**：
   - ticker 滚动用 15–20fps 的 `Animatable`/`withFrameNanos`，不要 60fps 逐帧；
   - 焦点环缩放/闪烁用 `graphicsLayer`，不要触发测量/布局；
   - 低端设备关闭非必要动画（见 §8.7）。

**目标**（参考设备：Android TV 盒子，1080p 逻辑分辨率）：

| 布局 | UI 帧目标 | 单帧 UI 线程目标 | 数据更新 |
|---|---|---|---|
| L01/L02/L03/L14 | 60fps | <8ms | 8fps |
| L04–L07 | 60fps | <12ms | 4–8fps |
| L08 | 30fps 最低，目标 60fps | <16ms | 4fps |
| L09/L11/L12 | 30fps 最低，目标 60fps | <10ms | 4fps |
| L13 | 30fps 最低 | <8ms | 4fps |

> 这些是验收目标，不是承诺；必须在真实 TV/模拟器上用 `Choreographer` 或 Macrobenchmark 测量。

### 8.2 WS 帧率与消息风暴

**风险**：OKX `tickers` 最快 100ms 推一次；9 个标的 = 90 msg/s，12 个 = 120 msg/s。
如果每条消息都 `_ui.update {}`，Compose 会持续重组，甚至主线程被 JSON 解析拖慢。

**对策**：

1. **解析在 IO 线程**：WS 回调只做 `JSONObject` 解析 + 写入 per-instId `AtomicReference`；
   不触碰 Compose 状态。
2. **按标的分桶 + conflate**：每个 `instId` 只保留最新 `QuoteSnapshot`；
   UI 侧用一个 120ms 的 ticker 批量取快照，合并成一次状态更新。
3. **聚焦优先**：全屏/焦点格 120ms；其它格 250–500ms；不可见格（全屏时的其它格）不更新。
4. **单连接 + diff 订阅**：绝不为每格开连接；布局切换只 subscribe/unsubscribe 差集；
   不要整条重连。
5. **心跳/重连**：沿用现有 `MarketProviders.kt` 的 staleTimeout 思路（30s 无消息关闭），
   Board `QuoteHub` 用 1s→30s 指数退避 + 抖动；重连期间 REST 兜底。
6. **背压**：如果 `trySend` 失败，丢弃旧快照而不是排队；价格只关心最新值。
7. **不要在 WS 回调里更新 `UiState`**：`publishLoop` 的 120ms 合并逻辑可以复用，
   但要改成「多标的一次 publish」，而不是每标的一条协程。

### 8.3 REST 并发与限流

- `Semaphore(4)` 限制初始 9 个 candles 的并发；
- 全局 `RateLimiter`（10 req/s，突发 20）覆盖所有 OKX REST；
- `CandleStore` 单飞 + 缓存，避免同一 `(instId, tf)` 被多个格重复请求；
- 失败只重试该 key，不阻塞其它格；
- 页面不可见（`onPause`）停止 candles 轮询和 ticker 动画；
- 大盘 `OverviewRepository` 已有 TTL/退避/缓存，不要在外面再包一层高频刷新；
- 不要在 UI 线程做 JSON 解析/文件读写。

### 8.4 内存

**实际数据量很小**：

- 9 个 spark × 60 点 × 2 个 float ≈ 4 KB；
- 4 个 COMPACT × 120 根 × 6 个 double ≈ 23 KB；
- 1 个 FULL × 500 根 ≈ 24 KB；
- 指标结果（只 1 份）通常是几十 KB 到几百 KB。

**真正的内存风险**：

1. 每个格都保留 500 根 K 线 + 指标结果 → 禁止；按 detail 分级；
2. 每个格每帧创建 `Path`/`Offset`/`TextLayoutResult` → 用缓存/复用；
3. `TextMeasurer` 缓存过大 → 每个模块一个小 cache（现有 `PriceChart` 用 192）；
4. 布局切换后旧 `CandleStore` 不释放 → LRU 上限（例如 32 个序列），
   `layout` 变更时淘汰不再引用的 `(instId, tf)`；
5. 4K 上给 9 个格开全分辨率 Canvas → 不按物理像素放大绘制，按 Dp 布局；
6. 位图/背景图 → v1 不用图片背景，避免 4K 位图常驻。

目标：Board 相对经典布局的常驻内存增量 ≤ 30 MB；9 宫格不超过 +15 MB。

### 8.5 烧屏（OLED/等离子/长时间常亮）

现有 `antiBurn` 已经对整棵内容树做 ±5px/±3.5px 的慢漂移（`Screen.kt:126–127`）。
Board 需要扩展，而不是每格各自漂移（否则会出现格子错位）：

1. **整板漂移**：只对 Board 根节点做漂移，所有格子一起移动；保留现有 `antiBurn` 开关。
2. **静态亮块**：
   - 大价格、StatTile 数字、焦点环是高风险静态亮块；
   - 降低纯白使用率：大数字用 `#F2F2F2` 而不是 `#FFFFFF`；
   - 9 宫格的卡片底色不要长时间保持同一亮度；可用极慢的 ±2% 亮度呼吸（低端设备可关）。
3. **ticker**：持续滚动比静止文字更不容易烧屏，但会耗性能；低端模式暂停滚动，
   改为每 30s 换一屏/轻微位移。
4. **焦点环**：不要长时间停在同一格不动；焦点环本身有轻微呼吸/位移；
   用户无操作 5 分钟后，焦点环可自动淡出（按任意键恢复）。
5. **全屏**：全屏大图同样应用整板漂移；不要让全屏标题/价格标签固定 8 小时。
6. **亮度**：不修改系统亮度（需要权限/体验问题）；只通过配色和漂移降低风险。
7. **经典布局**：保持现有 `antiBurn` 行为不变，不引入 Board 的额外动画。

### 8.6 失败隔离与会话竞态

**竞态场景**：用户在 Board 里快速 MENU 换标的，旧的 WS/candles 请求晚到，把新标的价格覆盖。

**对策**：

1. 每个槽位/每次会话一个 `sessionToken`（`Long`/`Job`）；旧 token 的响应直接丢弃；
2. `QuoteHub` 按 `instId` 存快照，切换标的后旧 `instId` 不再匹配；
3. `CandleStore` 请求带 `(instId, tf, requestId)`，回调校验当前 key；
4. 布局切换时 `cancel` 掉不再引用的 candles 请求；
5. 每个格子独立 `supervisorScope`，一个格失败不影响其它格；
6. 全屏/退出全屏不重建数据订阅，只改可见性/优先级；
7. WS 断线时保留最后价格并标 `stale`，不要显示 0；
8. JSON 解析失败/布局非法 → classic，不崩溃。

### 8.7 低端性能模式

`PerformanceMode`：

- `AUTO`：默认；根据设备 RAM、是否 4K、连续掉帧自动降级；
- `HIGH`：强制 8fps 数据、ticker 滚动、spark 60 点；
- `LOW`：2fps 数据、关闭 ticker 动画、spark 30 点、COMPACT 不画十字光标/坐标文字、
  9 宫格只保留符号+价格+涨跌。

触发条件建议：

- `ActivityManager.isLowRamDevice` 为 true；
- 连续 5 秒掉帧率 > 30%；
- 9 宫格在 720p 且设备 RAM < 1.5GB；
- 用户手动在设置里选「流畅优先」。

降级顺序：ticker 动画 → 非焦点格更新频率 → spark 点数 → COMPACT 细节 → 指标副图数量。
不要降级到「价格不更新」；价格是核心。

### 8.8 验证与验收

**自动化**：

- `LayoutGeometryTest`：14 个预设 + 自定义树，验证矩形不重叠、不越界、比例正确；
- `LayoutJsonTest`：encode/decode 往返；缺字段/坏字段/未知版本回退 classic；
- `BoardFocusTest`：给定矩形，验证四方向邻居（尤其 L07、L09、L12）；
- `QuoteHubTest`：9 个 instId 只发 1 条 subscribe；diff 订阅；重连退避；
- `CandleStoreTest`：同 key 单飞；LRU 淘汰；tick 修补最后一根；
- `Overview` 已有测试保持通过。

**预览/截图**：

- 用现有 `preview`（`ImageComposeScene`）为 14 个布局渲染 1920×1080 PNG；
- 再渲染 1280×720 与 3840×2160 各一组，检查字号/间距/不溢出；
- 9 宫格重点检查最小字号、价格不换行、spark 不压文字。

**人工/真机**：

- 遥控器按键矩阵逐项走一遍（§7.2）；
- 9 个标的 WS 连续 30 分钟，观察内存/帧率/重连；
- 断网/弱网/429/单标的无推送/美股休市；
- 从旧版本升级（无 layout key）→ 界面完全不变；
- 切换布局 50 次，检查订阅数不增长、内存不持续上涨；
- 8 小时常亮，检查烧屏与漂移。

**验收指标**：

| 项 | 目标 |
|---|---|
| 经典布局 | 与 v1.2 逐像素/逐键一致 |
| 9 宫格 | UI ≥30fps；数据 4fps；内存增量 ≤15MB |
| 4 宫格 | UI ≥30fps；1 个 FULL + 3 个 COMPACT |
| WS 连接 | 整屏 1 条；subscribe 消息 1 条含 N 个 instId |
| REST 稳态 | <0.5 req/s；初始突发 ≤15 次 |
| 布局切换 | 不重连 WS；不泄漏 CandleStore；焦点可恢复 |
| 异常 | JSON/网络/单标的失败均不崩溃、不清空旧值 |
| 升级 | 老用户默认经典，界面/按键不变 |

---

## 9. 实施拆分（后续工程，不在 H0 执行）

> H0 只写文档。下面是后续实现的任务拆分，目标是**互不抢文件**：
> 能新增文件的任务并行；必须改共享大文件的任务由一个 owner 串行完成。

### 9.1 文件所有权矩阵

| 文件 | 现状 | Owner | 说明 |
|---|---|---|---|
| `ui/Screen.kt` | 1048 行；`TvScreenInner` + private `SettingsOverlay` | **B3** | 只允许 B3 改；B4 用新 overlay，不碰这里 |
| `MarketViewModel.kt` | 671 行；单会话/WS/Prefs | **B3**，之后 B5 | B3 加 BoardState/QuoteHub；B5 串行加搜索目标 |
| `Prefs.kt` | 小文件；无 layout key | **B3** | 只加 `layoutSpec` get/set/reset |
| `data/Model.kt` | `UiState`、`TF` 等 | **B3**，之后 B5 | 优先把 Board 状态放独立 `BoardState`，减少 `UiState` 膨胀 |
| `ui/UiActions.kt` | 全局回调 | **B3**，之后 B5 | 加 `BoardActions` 聚合；B5 加 `selectInstrumentForSlot` |
| `AppEntry.kt` | ViewModel→UI 绑定、BackHandler | **B3** | 唯一整合入口 |
| `ui/KeyRouting.kt` | 经典按键路由 | **B3** | 新增 `routeBoardKey`；不改经典语义 |
| `ui/SearchOverlay.kt` | 全局搜索 | **B5** | 加 `targetSlotId`；B3 不改 |
| `ui/Chart.kt` | `PriceChart` | **只读** | 不改；COMPACT/MINI 新建组件 |
| `data/MarketClient.kt` | REST/WS 入口 | **B1** | 加批量 tickers/限流；B3 不碰 |
| `data/OkxProvider.kt` | OKX 单标的 | **B1** | 加多 instId subscribe 辅助；B3 不碰 |
| `data/MarketProvider.kt` | provider 接口 | **B1** | 如需扩展接口，B1 独占 |
| `data/overview/**` | 大盘数据层 | **外部任务** | 只读消费；B1/B3 不改 |
| `ui/board/Layout*.kt` | 新 | **B0** | 模型/几何/预设/JSON/焦点算法 |
| `data/board/**` | 新 | **B1** | QuoteHub/CandleStore/Movers/限流 |
| `ui/board/modules/**` | 新 | **B2** | 7 类模块的纯渲染组件 |
| `ui/board/Board*.kt` | 新 | **B3** | BoardScreen/Keys/Chrome |
| `ui/board/settings/**` | 新 | **B4** | 布局选择/槽位编辑/自定义向导 |
| `ui/board/BoardPerformance.kt` | 新 | **B6** | 性能模式/烧屏扩展 |
| `preview/src/main/kotlin/BoardPreview.kt` | 新 | **B7** | 14 布局 × 3 分辨率截图 |
| `preview/build.gradle.kts` | 当前工作区已有改动 | **B7 / 用户确认** | 不要覆盖现有未提交改动 |

> **当前工作区有未提交改动**（`MarketViewModel.kt`、`MarketClient.kt`、`Model.kt`、`Chart.kt`、
> `Screen.kt`、`preview/*` 等）。实现阶段开始前，先由用户决定 commit/stash；
> **不要**用 `git reset --hard`、`git checkout --` 或覆盖这些文件来「清场」。

### 9.2 任务表

| ID | 任务 | 新增文件 | 需要修改的共享文件 | 依赖 | 并行性 | 验收 |
|---|---|---|---|---|---|---|
| **B0** | Layout 引擎：模型、几何、预设、JSON、焦点算法 | `ui/board/LayoutModel.kt`、`LayoutGeometry.kt`、`LayoutPresets.kt`、`LayoutJson.kt`、`LayoutFocus.kt`；`preview/src/test/kotlin/board/*Test.kt` | 无 | 无 | 可立即开始；与 B1/B2 并行 | 14 必选 + L15 的矩形/JSON/焦点测试通过；L16 仅做几何/JSON 测试（`COMPARE_*` 是 v2 模块）；坏数据回退 classic |
| **B1** | Board 数据层：QuoteHub、CandleStore、Movers、限流 | `data/board/QuoteHub.kt`、`CandleStore.kt`、`MoversRepository.kt`、`BoardData.kt`、`RateLimiter.kt`、`QuoteSnapshot.kt` | `data/MarketClient.kt`、`data/OkxProvider.kt`、`data/MarketProvider.kt`（B1 独占） | B0 的 id/数据类型（可先冻结接口） | 与 B0/B2 并行；不要和 B3 同时改 data | 9 个 instId 只发 1 条 subscribe；diff 订阅；REST 兜底；movers 批量；缓存/单飞 |
| **B2** | Slot 模块：7 类模块的纯渲染 | `ui/board/modules/MainChartSlot.kt`、`CompactChart.kt`、`Sparkline.kt`、`PriceCardSlot.kt`、`StatTile.kt`、`WatchlistSlot.kt`、`MoversSlot.kt`、`TickerStripSlot.kt`、`SlotChrome.kt` | 无（`Chart.kt` 只读） | B0 + B1 的 `BoardData` 接口 | 可与 B1 并行（用 fake data） | 各模块在目标尺寸不溢出；不自己请求数据；不画焦点 |
| **B3** | Board 壳 + 整合 + 焦点/按键 + 全屏 | `ui/board/BoardScreen.kt`、`BoardSlotHost.kt`、`BoardChrome.kt`、`BoardKeys.kt`、`BoardState.kt` | `ui/Screen.kt`、`MarketViewModel.kt`、`Prefs.kt`、`data/Model.kt`、`ui/UiActions.kt`、`AppEntry.kt`、`ui/KeyRouting.kt`（B3 独占） | B0/B1/B2 的最小可用版本 | **串行瓶颈，不能并行** | classic 逐键/逐像素不变；L04 或 L12 可实时运行；焦点/OK/MENU/BACK/◀▶ 可用 |
| **B4** | 布局选择、槽位编辑、自定义分割 | `ui/board/settings/BoardSettingsOverlay.kt`、`LayoutPicker.kt`、`SlotEditor.kt`、`CustomSplitEditor.kt`、`LayoutPreview.kt` | 只依赖 B3 预留的 hook；理想情况下不碰 `Screen.kt` | B3 | 可与 B5 并行（若 hook 稳定） | 14 预设可切换；槽位绑定；自定义向导；持久化/恢复默认 |
| **B5** | 搜索目标槽位 | 无（改现有文件） | `ui/SearchOverlay.kt`、`ui/UiActions.kt`、`MarketViewModel.kt`、`data/Model.kt`（B5 独占，B3 之后） | B3 | 与 B4 串行（共享文件）；B4 只读 hook 时可并行 | MENU 打开「为某格选标的」；选完只改该槽位；经典搜索不变 |
| **B6** | 性能模式、烧屏扩展、低端降级 | `ui/board/BoardPerformance.kt`、`BoardBurnIn.kt` | 只改 B3 新建的 `BoardScreen.kt` | B3 | 可与 B4/B5 并行（新文件 + B3 文件） | AUTO/LOW 生效；9 宫格不持续掉帧；烧屏漂移整板一致 |
| **B7** | 预览矩阵、截图、验收脚本 | `preview/src/main/kotlin/BoardPreview.kt`、必要的测试/脚本 | `preview/build.gradle.kts`（先检查现有改动） | B0–B6 | 最后做；可与 B6 并行 | 14×3 分辨率截图；关键布局人工检查；性能/内存记录 |

### 9.3 建议顺序

```
阶段 0：B0 冻结接口（LayoutModel/LayoutSpec/BoardData/Rect）
         同时 B1 评估 OKX 多订阅与限流
阶段 1：B0 + B1 + B2 并行
         产出：可在 preview 里用假数据渲染 14 个布局
阶段 2：B3 整合（唯一 owner 改 Screen.kt/MarketViewModel.kt/Prefs/Model/UiActions/AppEntry）
         先做 L04 + L12 的垂直切片，确认经典布局无回归
阶段 3：B3 扩展全部 14 预设；B4 设置页；B5 搜索目标槽位
         B4/B5 串行，避免同时改共享文件
阶段 4：B6 性能/烧屏；B7 预览/验收
```

**关键原则**：

1. **先冻结接口，再并行**：B0 先给出 `LayoutNode`/`Slot`/`SlotBinding`/`SlotParams`/`Rect`
   和 `BoardData` 的最小接口；B1/B2 按接口写，不要边写边改签名。
2. **B3 是唯一整合 owner**：在 B3 完成前，B1/B2 只写新文件；不要让另一个任务顺手改
   `Screen.kt`/`MarketViewModel.kt`。
3. **B4 尽量只新增**：Board 设置页做成独立 overlay；B3 在 `Screen.kt` 里预留一个稳定 hook
   （例如 `if (st.board.settingsOpen) BoardSettingsOverlay(...)`），B4 只填新文件。
4. **B5 串行**：搜索目标槽位必然改 `SearchOverlay.kt` + `UiActions`/`Model`/`VM`；
   等 B3 合并后再做，避免冲突。
5. **经典布局是回归红线**：每个阶段都要用现有 `preview` 的 `01-main.png` 等截图对比；
   `presetId="classic"` 不得走 Board 代码。
6. **预置 layout 拆成两半**：**结构定义/默认绑定**属于 B0（纯数据，先完成）；
   **选择/编辑 UI** 属于 B4（依赖 B3）。这样「先引擎/抽象，再模块，再预置与设置页」
   的顺序不会被设置页阻塞。

### 9.4 共享文件冲突点（重点）

| 冲突点 | 为什么会冲突 | 规避 |
|---|---|---|
| `ui/Screen.kt` | 所有界面入口/设置/搜索都在这里；private `SettingsOverlay` | 只让 B3 改；Board 设置独立文件；B3 预留 hook |
| `MarketViewModel.kt` | 单标的会话、WS、Prefs、搜索都在这 | B3 只加 BoardState/QuoteHub 的装配；B5 串行加目标槽位；不要并行 |
| `Prefs.kt` | 所有持久化 key | B3 一次加完 `layoutSpec`/`resetLayout`；B4 不直接写 Prefs |
| `data/Model.kt` | `UiState` 是全局状态 | Board 状态放独立 `BoardState`；B3 只加最小字段；B5 串行 |
| `ui/UiActions.kt` | 所有回调 | B3 加 `BoardActions`；B5 串行加 `selectInstrumentForSlot` |
| `ui/KeyRouting.kt` | 经典按键真源 | B3 新增 `routeBoardKey`，不改 `routeKey`；B4/B6 不碰 |
| `ui/SearchOverlay.kt` | 全局选择器 | 只让 B5 改；B3 不改，避免双 owner |
| `data/MarketClient.kt` / `OkxProvider.kt` | 数据入口 | 只让 B1 改；B3 通过 `BoardData` 接口消费 |
| `ui/Chart.kt` | 共享绘制组件 | 不改；新建 Compact/Spark；否则 B2/B3 都会想改 |
| `data/overview/**` | 另一任务的成果 | 只读；不改；接口变化由对方负责 |
| `preview/build.gradle.kts` | 当前工作区已有改动 | B7 先读当前内容；不要覆盖未提交改动 |

### 9.5 里程碑与验收

| 里程碑 | 内容 | 验收 |
|---|---|---|
| **M0（本次 H0）** | 设计文档 | 本文件完成 a–g；不碰源码 |
| **M1** | B0 引擎 | 16 个预设矩形/JSON/焦点单测通过；preview 可画静态色块 |
| **M2** | B1 + B2 | 假数据下 14 布局可渲染；9 标的 1 条 WS 预算可验证 |
| **M3** | B3 垂直切片 | 经典布局零回归；L04 + L12 实时数据；遥控器可用 |
| **M4** | B3 全量 + B4 + B5 | 14 预设可选；槽位换标的；自定义分割；持久化 |
| **M5** | B6 + B7 | 性能/烧屏/低端模式；14×3 截图；验收指标达标 |

**M3 的硬门槛**：先证明「经典布局不走 Board」和「9 宫格 1 条 WS」两件事，
再扩张到 14 个预设。否则很容易在整合阶段同时背两个风险。

---

## 10. 风险登记与待确认

### 10.1 风险表

| # | 风险 | 概率 | 影响 | 对策 | 责任 |
|---|---|---|---|---|---|
| R1 | `MarketViewModel`/`Screen.kt` 单会话改造引发回归 | 高 | 高 | B3 唯一 owner；classic 走旧路径；黄金截图；每个里程碑跑现有 preview | B3 |
| R2 | 多标的数据扇出（9 格 9 连接/9 份指标） | 高 | 高 | `QuoteHub` 1 条 WS + diff 订阅；整屏 1 个 FULL；COMPACT/MINI 分级 | B1/B3 |
| R3 | 多 Canvas + 每 tick 全局重组导致掉帧 | 高 | 高 | 拆分行情/焦点状态；`drawWithCache`；低端模式；9 宫格不画 K 线 | B2/B3/B6 |
| R4 | 几何焦点在 1 大 2 小/9 宫格里跳错 | 中高 | 中 | 中心点方向评分 + 重叠优先；单测覆盖 L07/L09/L12 | B0/B3 |
| R5 | 设置/自定义分割在遥控器上难用 | 中 | 中 | 预设优先；自定义向导分两步；比例 5% 步进；v1 只保留 1 个自定义 | B4 |
| R6 | 大盘源 429/不稳定 | 中 | 中 | 复用 `OverviewRepository` 缓存/退避/stale；tile 不清空 | 外部/B2 |
| R7 | 布局 JSON 演进/损坏 | 中 | 中 | `v` 版本；宽容解析；非法回退 classic；单测 | B0 |
| R8 | 非 OKX 标的/美股休市 | 中 | 中 | REST 10s 兜底 + 「延迟」；休市显示「休市」；不阻塞其它格 | B1/B3 |
| R9 | 长时间常亮烧屏 | 中 | 中 | 整板 antiBurn 漂移；降低纯白；ticker/焦点环策略；8h 测试 | B6 |
| R10 | 范围蔓延（热力图/订单簿/对比图） | 高 | 中 | v1 锁定 14 必选 + 2 可选；L15/L16 不排期；新模块走 v2 | 全员 |
| R11 | 720p/4K 溢出/字号过小 | 中 | 中 | 归一化矩形；最小字号；14×3 分辨率截图；720p 隐藏次要信息 | B0/B7 |
| R12 | 实现阶段覆盖当前未提交改动 | 中 | 高 | 开始前由用户 commit/stash；禁止 reset/checkout；先读文件再改 | B3/全员 |
| R13 | 全屏/布局切换时旧请求覆盖新标的 | 中 | 中 | sessionToken/Job 取消；QuoteHub 按 instId；CandleStore key 校验 | B1/B3 |
| R14 | 低端 TV 上 9 宫格仍卡 | 中 | 中 | AUTO/LOW 降级；spark 30 点；ticker 暂停；数据 2fps | B6 |

### 10.2 待确认的假设

以下不影响 H0 文档成立，但实现前需要用户拍板：

1. **⑤ 的比例**：本设计按「左:右 = 1:2」实现；若原意是别的结构，用自定义分割或调整
   `split_1_2` 预设即可。
2. **自定义布局数量**：v1 只保留 1 个 `custom`；是否需要多个「我的布局」？
3. **BOARD 根层 BACK**：默认交给系统退出；是否改为「打开布局设置」？
4. **非 OKX 标的**：允许选择但走 REST（本设计默认），还是在搜索里直接置灰？
5. **6 宫格默认内容**：本设计默认 `PRICE_CARD + spark`（性能优先）；
   是否要默认 6 个 COMPACT K 线？
6. **L15/L16**：是否进入 v1？本设计列为可选扩展，不排期。
7. **4K 渲染策略**：是否对低端 4K 盒子强制 1080p 逻辑渲染 + TV 上采样？
8. **◀▶ 的优先级**：当前采用「BOARD 短按优先移动焦点，长按/全屏切周期」。
   如果希望 BOARD 内短按 ◀▶ 始终切周期，就必须改成长按移动焦点或增加组合键；
   这是遥控器手感的核心取舍，需用户拍板（见 §7.2）。

### 10.3 不在本设计范围

- 热力图 / treemap（需要全市场市值权重，OKX tickers 不足）；
- 订单簿 / 成交 tape / 深度图（OKX `books5`/`trades` 可做，但 v1 不做）；
- 自动轮播 / 播放列表；
- 多显示器 / 多窗口；
- 账户/持仓/下单（本项目是公共行情只读看板）；
- 新闻/RSS 模块；
- 用户账号/云同步（布局只存本机 Prefs）。

---

## 附录 A：与旧半成品文档的差异（供审阅）

旧文档有 30 个样本、14 个布局、较好的线框图和 OKX 预算，但存在以下需要修正/补齐的点；
本版已处理：

| 旧文档 | 本版 |
|---|---|
| 数据模型用扁平 `rect` 列表，不是树 | 改为 `LayoutNode.Split/Leaf` + `Slot`，矩形运行时派生 |
| 缺少 ⑤ 左 1:2 / ⑥ 左 2:1 两个分屏 | 新增 L05/L06，重排 14 个布局编号 |
| 写「需要新增 `TF.W1`」 | `TF.W1` 已存在（`Model.kt:62`），删除该建议 |
| 写「没有 `data/overview/`，由另一任务提供」 | 已落地；按真实 `MarketOverview`/`OverviewRepository` 接口和 TTL 写 |
| 写「1080p 时 s=2.0」 | 修正：`maxHeight` 是 Dp，不能按物理分辨率断言；给出 720p/4K 缩放规则 |
| 部分 GitHub 描述夸大（OpenBook 可停靠窗格等） | 按 GitHub API 官方 description 修正；13 个仓库已核实存在 |
| 缺少遥控器模式机/几何焦点/全屏/BACK 层级 | 新增 §7 |
| 缺少实施拆分/文件所有权/冲突点 | 新增 §9 |
| 缺少性能/内存/烧屏/低端模式的系统对策 | 新增 §8 |
| 提到 `docs/layouts-wireframes.md` 但文件不存在 | 线框图全部并入本文 §4，不再依赖外部文件 |

## 附录 B：参考链接汇总

产品/项目样本见 §2.1。代码事实参考：

- `ui/Screen.kt:88` `TvScreenInner`；`:125` 缩放；`:141–154` 纵向布局；`:513` `SettingsOverlay`
- `MarketViewModel.kt:46` ViewModel；`:105/:128` 会话；`:169` 20s 刷新；`:187` WS；`:208` 120ms publish
- `data/Model.kt:62` `TF`（含 `W1`）；`UiState` 在文件末尾
- `data/MarketProviders.kt:88` 单标的 WS spec；`data/OkxProvider.kt:67–72` 单 instId subscribe
- `ui/KeyRouting.kt` `routeKey`；`AppEntry.kt:14` `TvScreen`；`:20` `BackHandler`
- `data/InstrumentCatalog.kt:12/:28` Instrument JSON
- `data/overview/MarketOverview.kt`、`OverviewRepository.kt`、`OverviewSources.kt`、`OverviewCache.kt`

---

*文档结束。H0 只产出本文件；源码、构建脚本、git 均未改动。*
