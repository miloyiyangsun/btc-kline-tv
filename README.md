# BTC 行情 TV（安卓电视/投影仪用）

一个打开就看的比特币实时行情大屏：大字号价格 + K 线，遥控器操作，专为投影仪/电视设计。

- 包名：`com.milolab.btctv`
- 版本：1.2（versionCode 3）
- 安装包：`dist/BTC-Kline-TV-v1.2.apk`（1.26 MB，已签名，sha256 `4efadad77fe884ff6031ab186ca959ebafd9ae3516d466088d3c9efc6d7a48e2`）
- 下载：https://github.com/miloyiyangsun/btc-kline-tv/raw/main/dist/BTC-Kline-TV-v1.2.apk
- 支持：Android 6.0+（minSdk 23），arm64 / armv7 / x86 / x86_64 全支持
- 适配：720p / 1080p / 4K 自动缩放；手机竖屏机器也能装（会横屏显示）

## v1.2 新增

- **搜索切换标的**：遥控器按搜索键，可搜索并切换到任意加密币，或 OKX 美股（股票永续 / 代币化现货，如 NVDA、AAPL）。
- **31 个技术指标**：均线、布林带、Ichimoku、SuperTrend、RSI、MACD、KDJ、ATR、OBV、CCI、威廉指标、随机 RSI、VPVR、枢轴点、斐波那契等；设置里可逐个开关和调参，**默认全部关闭**。
- 多窗格图表：副图指标独立窗格显示，主图叠加指标可自由组合。

## 一、安装

三种方式任选：

1. **U 盘**：把 apk 拷到 U 盘根目录 → 插到投影仪/电视 → 用自带的「文件管理 / 媒体中心」打开 → 允许「安装未知来源应用」→ 安装。
2. **电视助手类 App**（当贝/沙发管家等）：开启「远程推送」，浏览器打开推送地址把 apk 传上去。
3. **adb**（同一局域网）：
   ```
   adb connect <投影仪IP>:5555
   adb install -r BTC-Kline-TV-v1.2.apk
   ```

装完在桌面/应用列表里叫 **「BTC 行情」**，TV 桌面会显示横幅图标。

## 二、遥控器操作

| 按键 | 作用 |
|---|---|
| ◀ / ▶ | 切换周期：1m → 5m → 15m → 1H → 4H → 1D |
| ▲ / ▼ | 缩放 K 线（45 / 60 / 90 / 120 / 180 / 240 根，底部显示当前根数） |
| ☰ 菜单键 / 🔍 搜索键 | 打开搜索面板：输入币种或美股代码/中文名（如 SOL、NVDA、英伟达）切换标的 |
| OK / 确认键 | 打开设置面板 |
| BACK | 面板打开时关闭面板；主界面按则退出 |
| ⚙ 设置键 | 部分机型被系统占用会直接跳到系统设置，此时用 ☰ 或 OK |

**设置面板里**：方向键移动焦点（当前项有橙色描边 + 轻微放大），OK 确认/切换，返回键关闭。
底部还有「立即重连」「关闭」两个按钮，网络抽风时不用退出重进。

**搜索面板里**：可直接调出系统输入法输入，也可用 A–Z 快捷筛选；方向键选中后 OK 切换。
加密币与美股用橙色/蓝色标签区分，股票条目会标注「美股 · 永续」或「美股 · 代币现货」，还可按市场过滤、收藏常用标的。

也支持鼠标和触摸：点底部周期按钮切换，设置里可以直接点。

设置面板里可调：**图表样式**（蜡烛/折线/面积）、**价格跳动闪烁**、**防烧屏微位移**、**显示时钟**、**显示成交量**、**开机自动启动**，以及 **31 个技术指标**的开关与参数（默认全关，主图叠加/水平线/副图分开管理）。

## 三、数据说明

- 价格来源：加密币为 **OKX / Binance / Bybit** 三家热备，启动时自动挑最快的一家，界面左上角会显示当前源（如「实时行情 · OKX」）。
- 美股行情走 **OKX**：支持股票永续与代币化现货，标的目录启动后从 OKX 拉取；搜索面板里可过滤只看加密或只看美股。
- 实时性：WebSocket 逐笔成交推送，价格每秒多次跳动；断线 15 秒自动降级为 REST 轮询，恢复后自动回 WebSocket。
- 右上角状态点：**实时**=WS 正常，**轮询**=WS 断了用兜底，**离线**=全部线路不通（会自动重试）。
- 24H 最高/最低/成交量来自交易所 24 小时统计，不是本地计算。

## 四、常见问题

**Q：打开后一直显示「正在拉取K线…」？**
投影仪没联网，或所在网络屏蔽了交易所域名。先确认能上网；如果公司/酒店网络拦截，换个热点试试。

**Q：画面数字不动了？**
看右上角是不是「轮询」或「离线」。按 **▲** 手动重连；仍然不行就是网络问题。

**Q：开机自启没生效？**
Android 10 以后系统限制后台启动 Activity，部分品牌盒子/投影会拦。这个开关是"尽力而为"，不保证所有设备都生效；不生效就在桌面点图标打开。

**Q：会不会烧屏？**
设置里「防烧屏微位移」默认开启，整个画面会以几十秒为周期做 ±5px 的极慢漂移，肉眼几乎看不出，但能显著降低静态元素烙印风险。另外 App 全程常亮不息屏。

**Q：v1.0 里设置面板按键没反应/还在改下面的周期？**
v1.1 已修：按键处理从 Activity 搬进了 Compose 焦点体系，设置打开后方向键只走面板，不再动到下面的周期和时间轴。装 v1.1 覆盖即可。

**Q：怎么更新？**
用同一签名的新 apk 直接覆盖安装即可（`adb install -r` 或 U 盘重装），设置和数据会保留。

## 五、目录结构（源码）

```
D:/dev/btc-kline-tv/
├─ app/src/main/java/com/milolab/btctv/
│  ├─ MainActivity.kt          全屏 + 遥控器按键
│  ├─ AppEntry.kt              ViewModel → UI 的接线
│  ├─ MarketViewModel.kt       会话状态机（重连/看门狗/限流）
│  ├─ Prefs.kt                 设置持久化
│  ├─ BootReceiver.kt          开机自启
│  └─ data/
│     ├─ Model.kt              行情模型 + UiState
│     ├─ MarketClient.kt       交易所 REST + WebSocket 接入
│     ├─ MarketProvider.kt     OKX / Binance / Bybit 统一接口
│     ├─ InstrumentCatalog.kt  加密现货 + OKX 美股标的目录
│     ├─ InstrumentSearch.kt   标的本体搜索 / 中文别名
│     ├─ Stocks.kt             美股热门清单与永续/代币现货规则
│     └─ indicators/           31 个技术指标（注册表 / 计算引擎 / 缓存）
├─ app/src/main/java/com/milolab/btctv/ui/
│  ├─ Screen.kt                主界面 + 设置面板
│  ├─ Chart.kt                 K 线画布（自绘，无第三方图表库）
│  ├─ SearchOverlay.kt         标的搜索切换面板
│  ├─ Theme.kt / Format.kt     配色 / 数字格式
│  └─ UiActions.kt             界面回调
├─ preview/                    离屏渲染工具（无模拟器时验收 UI）
└─ dist/                       成品 apk
```

签名文件 `keys/release.jks` 和口令只保存在本地（`local.properties`，已被 `.gitignore` 排除），仓库里不含任何密钥。

## 六、重新打包

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.21.9-hotspot"
export ANDROID_HOME="C:/dev/android-sdk"
cd /d/dev/btc-kline-tv
/c/dev/gradle-8.9/bin/gradle.bat assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

要在本地打出签名版，把下面四项写进 `local.properties`（不入库）：

```properties
RELEASE_STORE_FILE=keys/release.jks
RELEASE_STORE_PASSWORD=<你的口令>
RELEASE_KEY_ALIAS=<你的别名>
RELEASE_KEY_PASSWORD=<你的口令>
```

没有这几项时 release 也能编译，只是不会签名（产物装不上而已）。

改完 UI 想先看效果（不开模拟器）：

```bash
cd preview
/c/dev/gradle-8.9/bin/gradle.bat run --args="shots"     # 生成 1080p/720p/4K 截图到 preview/shots
/c/dev/gradle-8.9/bin/gradle.bat run -PmainClass=LiveProbeKt   # 真连交易所跑一遍数据链路
```
