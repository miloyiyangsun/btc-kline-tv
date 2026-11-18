package com.milolab.btctv.ui.board.modules

import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.IndicatorSettings
import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.indicators.IndicatorResult

/**
 * B2 模块的纯数据入参：不引用 B0 的布局树、不引用 B1 的数据层，
 * 由 Board 集成者把 QuoteHub / CandleStore / OverviewRepository 的快照适配成这些类型。
 *
 * 三态约定：
 * - [ModulePhase.LOADING] 且没有任何内容 -> 显示静态骨架（无动画，避免闪烁）；
 * - [ModulePhase.EMPTY] -> 显示「暂无数据」；
 * - [ModulePhase.READY] -> 显示内容；
 * - [stale] 是正交标记：不清空旧值，只淡化内容并显示「更新于 X 分钟前」。
 */
enum class ModulePhase { LOADING, READY, EMPTY }

/** 主图密度：FULL 复用 ui/Chart.kt 的 PriceChart（坐标/指标/十字光标）；COMPACT 用轻量自绘。 */
enum class ChartDensity { FULL, COMPACT }

data class MainChartData(
    val instrument: Instrument,
    val tf: TF = TF.M1,
    val candles: List<Candle> = emptyList(),
    val price: Double? = null,
    val changePct: Double? = null,
    val style: ChartStyle = ChartStyle.CANDLE,
    val showVolume: Boolean = true,
    val priceDecimals: Int = instrument.priceDecimals,
    val indicatorSettings: IndicatorSettings = IndicatorSettings.DEFAULT,
    val indicatorResults: Map<String, IndicatorResult> = emptyMap(),
    /** 只显示最后 N 根；null = FULL 用全部、COMPACT 用 90 根 */
    val visibleBars: Int? = null,
    val phase: ModulePhase = ModulePhase.READY,
    val stale: Boolean = false,
    val staleAgoMinutes: Long? = null,
    val source: String = ""
)

data class MiniChartData(
    val instrument: Instrument,
    val tf: TF = TF.H1,
    val candles: List<Candle> = emptyList(),
    val price: Double? = null,
    val changePct: Double? = null,
    val style: ChartStyle = ChartStyle.LINE,
    val priceDecimals: Int = instrument.priceDecimals,
    val phase: ModulePhase = ModulePhase.READY,
    val stale: Boolean = false,
    val staleAgoMinutes: Long? = null
)

data class PriceCardData(
    val instrument: Instrument,
    val price: Double? = null,
    val changePct: Double? = null,
    val high24h: Double? = null,
    val low24h: Double? = null,
    val volume24h: Double? = null,
    val spark: List<Double> = emptyList(),
    /** null 时按 changePct 的正负着色 */
    val sparkUp: Boolean? = null,
    val priceDecimals: Int = instrument.priceDecimals,
    val phase: ModulePhase = ModulePhase.READY,
    val stale: Boolean = false,
    val staleAgoMinutes: Long? = null
)

enum class StatKind(val title: String, val shortTitle: String) {
    BTCD("BTC 市占率", "BTCD"),
    TOTAL("加密总市值", "TOTAL"),
    STABLE_MCAP("稳定币市值", "STABLE"),
    STABLE_DOM("稳定币占比", "ST.DOM")
}

data class StatTileData(
    val kind: StatKind,
    val value: Double? = null,
    val changePct: Double? = null,
    val spark: List<Double> = emptyList(),
    val source: String = "",
    /** stale 时展示「更新于 X 分钟前」；null 显示「已过期」 */
    val staleAgoMinutes: Long? = null,
    val phase: ModulePhase = ModulePhase.READY,
    val stale: Boolean = false
)

data class WatchRow(
    val instrument: Instrument,
    val price: Double? = null,
    val changePct: Double? = null,
    val spark: List<Double> = emptyList(),
    val priceDecimals: Int = instrument.priceDecimals,
    val stale: Boolean = false
)

data class WatchlistData(
    val rows: List<WatchRow> = emptyList(),
    val title: String = "自选",
    /** 焦点行（D-pad 选中项）；null 表示没有选中行 */
    val selectedIndex: Int? = null,
    val phase: ModulePhase = ModulePhase.READY,
    val stale: Boolean = false,
    val staleAgoMinutes: Long? = null
)

enum class MoversMetric(val label: String) {
    GAINERS("涨幅榜"),
    LOSERS("跌幅榜")
}

enum class MoversLayout { SPLIT, TABS }

data class MoverRow(
    val rank: Int,
    val instrument: Instrument,
    val price: Double? = null,
    val changePct: Double? = null,
    /** 成交额等附加列，已格式化的短文本（如 "$18.2M"） */
    val volumeLabel: String? = null,
    val priceDecimals: Int = instrument.priceDecimals
)

data class MoversData(
    val gainers: List<MoverRow> = emptyList(),
    val losers: List<MoverRow> = emptyList(),
    val layout: MoversLayout = MoversLayout.SPLIT,
    val activeTab: MoversMetric = MoversMetric.GAINERS,
    val phase: ModulePhase = ModulePhase.READY,
    val stale: Boolean = false,
    val staleAgoMinutes: Long? = null
)

enum class TickerSpeed(val dpPerSecond: Float) {
    SLOW(26f),
    NORMAL(46f),
    FAST(74f)
}

data class TickerItem(
    val name: String,
    val price: Double? = null,
    val priceDecimals: Int = 2,
    val changePct: Double? = null,
    /** 非价格项（如 "BTCD 58.45%"）优先展示这段文本 */
    val valueText: String? = null
)

data class TickerStripData(
    val items: List<TickerItem> = emptyList(),
    val speed: TickerSpeed = TickerSpeed.NORMAL,
    val phase: ModulePhase = ModulePhase.READY,
    val stale: Boolean = false,
    val staleAgoMinutes: Long? = null
)

// ---------------------------------------------------------------------------
// 自适应尺寸档位（纯函数，便于单测；输入是格子实测 Dp 尺寸）
// ---------------------------------------------------------------------------

enum class PriceCardTier { LARGE, MEDIUM, SMALL }

fun priceCardTier(widthDp: Float, heightDp: Float): PriceCardTier = when {
    heightDp >= 300f && widthDp >= 420f -> PriceCardTier.LARGE
    heightDp >= 190f && widthDp >= 240f -> PriceCardTier.MEDIUM
    else -> PriceCardTier.SMALL
}

enum class StatTileTier { LARGE, MEDIUM, SMALL }

fun statTileTier(widthDp: Float, heightDp: Float): StatTileTier = when {
    heightDp >= 130f && widthDp >= 220f -> StatTileTier.LARGE
    heightDp >= 68f && widthDp >= 120f -> StatTileTier.MEDIUM
    else -> StatTileTier.SMALL
}

/** 主图头部字号相对 1x 设计稿的缩放；跟随格子高度，宽格不无限放大。 */
fun chartHeaderScale(widthDp: Float, heightDp: Float): Float =
    minOf(heightDp / 300f, widthDp / 640f).coerceIn(0.68f, 1.55f)

/** 列表行主字号（sp）：随行高适度变化，限制在可读区间。 */
fun listRowFontSp(rowHeightDp: Float): Float =
    (rowHeightDp * 0.40f).coerceIn(10f, 17f)

/** 跑马灯字号（sp）：随条高变化，保证 720p 上仍可读。 */
fun tickerFontSp(heightDp: Float): Float =
    (heightDp * 0.44f).coerceIn(9f, 16f)
