package com.milolab.btctv.ui.board

import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.Quote
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.hub.MoversSnapshot
import com.milolab.btctv.data.overview.HistoryPoint
import com.milolab.btctv.data.overview.MarketOverview
import com.milolab.btctv.ui.board.layout.LayoutSpec
import com.milolab.btctv.ui.board.layout.ModuleType
import com.milolab.btctv.ui.board.layout.Slot
import com.milolab.btctv.ui.board.layout.SlotBinding
import com.milolab.btctv.ui.board.layout.SlotParamKeys
import com.milolab.btctv.ui.board.layout.leafSlotIds
import com.milolab.btctv.ui.board.layout.StatKind as LayoutStatKind
import com.milolab.btctv.ui.board.modules.MainChartData
import com.milolab.btctv.ui.board.modules.MiniChartData
import com.milolab.btctv.ui.board.modules.ModulePhase
import com.milolab.btctv.ui.board.modules.MoverRow
import com.milolab.btctv.ui.board.modules.MoversData
import com.milolab.btctv.ui.board.modules.MoversLayout
import com.milolab.btctv.ui.board.modules.MoversMetric
import com.milolab.btctv.ui.board.modules.PriceCardData
import com.milolab.btctv.ui.board.modules.StatTileData
import com.milolab.btctv.ui.board.modules.TickerItem
import com.milolab.btctv.ui.board.modules.TickerSpeed
import com.milolab.btctv.ui.board.modules.TickerStripData
import com.milolab.btctv.ui.board.modules.WatchRow
import com.milolab.btctv.ui.board.modules.WatchlistData
import com.milolab.btctv.ui.board.modules.fmtUsdCompact
import com.milolab.btctv.ui.board.modules.StatKind as ModuleStatKind

// ---------------------------------------------------------------- 标识适配

/**
 * B0 的 StatKind 与 B2 的同名枚举属于不同包，这里做唯一转换点。
 * 只在这里映射，两边的公开名字都保持不动（大量测试/模块依赖它们）。
 */
fun statKindToModule(kind: LayoutStatKind): ModuleStatKind = when (kind) {
    LayoutStatKind.BTCD -> ModuleStatKind.BTCD
    LayoutStatKind.TOTAL -> ModuleStatKind.TOTAL
    LayoutStatKind.STABLE_CAP -> ModuleStatKind.STABLE_MCAP
    LayoutStatKind.STABLE_DOM -> ModuleStatKind.STABLE_DOM
}

fun staleMinutes(nowMs: Long, thenMs: Long?): Long? {
    if (thenMs == null || thenMs <= 0L) return null
    return ((nowMs - thenMs) / 60_000L).coerceAtLeast(0L)
}

fun isChartModule(module: ModuleType): Boolean = when (module) {
    ModuleType.MainChart, ModuleType.MiniChart, ModuleType.PriceCard -> true
    else -> false
}

/** 布局里承担“完整主图”的槽位：第一个 FollowMain、无固定周期的 MainChart。 */
fun primaryMainSlotId(spec: LayoutSpec): String? = spec.root.leafSlotIds().firstOrNull { id ->
    val slot = spec.slots[id] ?: return@firstOrNull false
    slot.module is ModuleType.MainChart &&
        slot.binding is SlotBinding.FollowMain &&
        slot.timeframe == null
}

/** 当前全局主图（含指标/缩放）的完整数据，供 Board 主图槽与全屏使用。 */
fun mainChartDataFromUi(st: com.milolab.btctv.data.UiState): MainChartData = MainChartData(
    instrument = st.instrument,
    tf = st.tf,
    candles = st.candles,
    price = st.price,
    changePct = st.quote?.changePct,
    style = st.style,
    showVolume = st.showVolume,
    priceDecimals = st.instrument.priceDecimals,
    indicatorSettings = st.indicatorSettings,
    indicatorResults = st.indicatorResults,
    visibleBars = st.zoom,
    phase = if (st.candles.size >= 2) ModulePhase.READY else ModulePhase.LOADING,
    source = st.provider ?: "OKX"
)

// ---------------------------------------------------------------- 槽位解析

/** 一个槽位解析后的数据来源，供订阅集合与 K 线加载共用。 */
data class SlotPlan(
    val slot: Slot,
    val instrument: Instrument,
    val tf: TF?,
    val needsQuote: Boolean,
    val needsCandles: Boolean
)

/**
 * 把 Fixed(instrumentId) 解析成 Instrument。查找范围由调用方提供
 * （内置标的、目录、自选、最近使用），找不到时回退当前主标的，避免空格。
 */
fun resolveSlotInstrument(
    binding: SlotBinding,
    main: Instrument,
    lookup: (String) -> Instrument?
): Instrument = when (binding) {
    is SlotBinding.FollowMain -> main
    is SlotBinding.Fixed -> lookup(binding.instrumentId) ?: main
}

fun slotTimeframe(slot: Slot, mainTf: TF): TF? = when (slot.module) {
    ModuleType.MainChart -> slot.timeframe ?: mainTf
    ModuleType.MiniChart -> slot.timeframe ?: TF.H1
    ModuleType.PriceCard -> slot.timeframe ?: sparkTimeframe(slot)
    else -> null
}

fun sparkTimeframe(slot: Slot): TF = when (slot.params[SlotParamKeys.SPARK_WINDOW]) {
    SlotParamKeys.WINDOW_H1 -> TF.H1
    SlotParamKeys.WINDOW_D7 -> TF.D1
    else -> TF.H4
}

fun watchlistPool(
    slot: Slot,
    favorites: List<Instrument>,
    hot: List<Instrument>
): List<Instrument> {
    val limit = slot.params[SlotParamKeys.LIMIT]?.toIntOrNull()?.coerceIn(1, 30) ?: 10
    val source = when (slot.params[SlotParamKeys.SOURCE]) {
        SlotParamKeys.SOURCE_HOT -> hot
        SlotParamKeys.SOURCE_CUSTOM -> favorites
        else -> favorites.ifEmpty { hot }
    }
    return source.distinctBy { it.id }.take(limit)
}

fun planSlots(
    spec: LayoutSpec,
    main: Instrument,
    mainTf: TF,
    lookup: (String) -> Instrument?,
    favorites: List<Instrument>,
    hot: List<Instrument>
): Map<String, SlotPlan> {
    val out = LinkedHashMap<String, SlotPlan>(spec.slots.size)
    for ((id, slot) in spec.slots) {
        val inst = resolveSlotInstrument(slot.binding, main, lookup)
        out[id] = SlotPlan(
            slot = slot,
            instrument = inst,
            tf = slotTimeframe(slot, mainTf),
            needsQuote = slot.module !is ModuleType.StatTile,
            needsCandles = isChartModule(slot.module)
        )
    }
    return out
}

/**
 * 订阅集合：主标的永远第一，然后图表格，再列表/跑马灯，最多 12 个
 * （QuoteHub 单条 WS 的上限）。这个函数是纯的，便于单测。
 */
fun subscriptionInstruments(
    spec: LayoutSpec,
    plans: Map<String, SlotPlan>,
    main: Instrument,
    favorites: List<Instrument>,
    hot: List<Instrument>,
    max: Int = 12
): List<Instrument> {
    val ordered = ArrayList<Instrument>(max)
    ordered += main

    val leaves = spec.slots.keys
    for (id in leaves) {
        val plan = plans[id] ?: continue
        if (plan.needsQuote) ordered += plan.instrument
    }
    for (id in leaves) {
        val slot = spec.slots[id] ?: continue
        if (slot.module is ModuleType.Watchlist || slot.module is ModuleType.TickerStrip) {
            ordered += watchlistPool(slot, favorites, hot)
        }
    }
    if (leaves.any { spec.slots[it]?.module is ModuleType.TickerStrip }) {
        ordered += hot.take(6)
    }
    return ordered.distinctBy { it.id }.take(max.coerceAtLeast(1))
}

// ---------------------------------------------------------------- B1 -> B2

fun statTileData(
    kind: LayoutStatKind,
    overview: MarketOverview?,
    nowMs: Long = System.currentTimeMillis()
): StatTileData {
    val moduleKind = statKindToModule(kind)
    if (overview == null) {
        return StatTileData(kind = moduleKind, phase = ModulePhase.LOADING)
    }
    val value = when (kind) {
        LayoutStatKind.BTCD -> overview.btcDominance
        LayoutStatKind.TOTAL -> overview.totalMarketCapUsd
        LayoutStatKind.STABLE_CAP -> overview.stablecoinMarketCapUsd
        LayoutStatKind.STABLE_DOM -> overview.stablecoinDominancePct
    }
    val change = when (kind) {
        LayoutStatKind.TOTAL -> overview.totalChange24hPct
        else -> null
    }
    val spark = when (kind) {
        LayoutStatKind.BTCD -> series(overview.btcDominanceHistory)
        LayoutStatKind.TOTAL -> series(overview.totalMarketCapHistory)
        LayoutStatKind.STABLE_CAP -> series(overview.stablecoinMarketCapHistory)
        LayoutStatKind.STABLE_DOM -> emptyList()
    }
    val hasValue = value != null || overview.stale
    return StatTileData(
        kind = moduleKind,
        value = value,
        changePct = change,
        spark = spark,
        source = overview.source.substringBefore(" + ").ifBlank { "大盘" },
        staleAgoMinutes = staleMinutes(nowMs, overview.fetchedAtMs),
        phase = if (hasValue) ModulePhase.READY else ModulePhase.EMPTY,
        stale = overview.stale
    )
}

private fun series(points: List<HistoryPoint>): List<Double> =
    if (points.size < 2) emptyList() else points.takeLast(30).map { it.v }

fun mainChartData(
    instrument: Instrument,
    tf: TF,
    candles: List<Candle>,
    quote: Quote?,
    style: ChartStyle,
    showVolume: Boolean,
    visibleBars: Int?,
    stale: Boolean,
    staleAgoMinutes: Long?,
    source: String = ""
): MainChartData {
    val price = quote?.price ?: candles.lastOrNull()?.c
    return MainChartData(
        instrument = instrument,
        tf = tf,
        candles = candles,
        price = price,
        changePct = quote?.changePct,
        style = style,
        showVolume = showVolume,
        priceDecimals = instrument.priceDecimals,
        visibleBars = visibleBars,
        phase = when {
            candles.isNotEmpty() -> ModulePhase.READY
            stale -> ModulePhase.EMPTY
            else -> ModulePhase.LOADING
        },
        stale = stale,
        staleAgoMinutes = staleAgoMinutes,
        source = source
    )
}

fun miniChartData(
    instrument: Instrument,
    tf: TF,
    candles: List<Candle>,
    quote: Quote?,
    style: ChartStyle = ChartStyle.LINE,
    stale: Boolean = false,
    staleAgoMinutes: Long? = null
): MiniChartData = MiniChartData(
    instrument = instrument,
    tf = tf,
    candles = candles,
    price = quote?.price ?: candles.lastOrNull()?.c,
    changePct = quote?.changePct,
    style = style,
    priceDecimals = instrument.priceDecimals,
    phase = when {
        candles.size >= 2 -> ModulePhase.READY
        stale -> ModulePhase.EMPTY
        else -> ModulePhase.LOADING
    },
    stale = stale,
    staleAgoMinutes = staleAgoMinutes
)

fun priceCardData(
    instrument: Instrument,
    quote: Quote?,
    sparkCandles: List<Candle>,
    stale: Boolean = false,
    staleAgoMinutes: Long? = null
): PriceCardData = PriceCardData(
    instrument = instrument,
    price = quote?.price ?: sparkCandles.lastOrNull()?.c,
    changePct = quote?.changePct,
    high24h = quote?.high24h,
    low24h = quote?.low24h,
    volume24h = quote?.vol24h,
    spark = sparkCandles.takeLast(48).map { it.c },
    priceDecimals = instrument.priceDecimals,
    phase = when {
        quote != null || sparkCandles.isNotEmpty() -> ModulePhase.READY
        stale -> ModulePhase.EMPTY
        else -> ModulePhase.LOADING
    },
    stale = stale,
    staleAgoMinutes = staleAgoMinutes
)

fun watchlistData(
    slot: Slot,
    instruments: List<Instrument>,
    quotes: Map<String, Quote>,
    sparks: (Instrument) -> List<Double>,
    selectedInstrumentId: String?,
    nowMs: Long = System.currentTimeMillis()
): WatchlistData {
    val rows = instruments.map { inst ->
        val q = quotes[inst.id]
        WatchRow(
            instrument = inst,
            price = q?.price,
            changePct = q?.changePct,
            spark = sparks(inst),
            priceDecimals = inst.priceDecimals,
            stale = q == null
        )
    }
    val selected = instruments.indexOfFirst { it.id == selectedInstrumentId }.takeIf { it >= 0 }
    return WatchlistData(
        rows = rows,
        selectedIndex = selected,
        phase = when {
            rows.isNotEmpty() -> ModulePhase.READY
            else -> ModulePhase.LOADING
        },
        stale = rows.isNotEmpty() && rows.all { it.stale }
    )
}

fun moversData(
    snapshot: MoversSnapshot?,
    slot: Slot,
    nowMs: Long = System.currentTimeMillis()
): MoversData {
    if (snapshot == null) return MoversData(phase = ModulePhase.LOADING)
    val limit = slot.params[SlotParamKeys.LIMIT]?.toIntOrNull()?.coerceIn(1, 12) ?: 5
    val metric = when (slot.params[SlotParamKeys.METRIC]) {
        SlotParamKeys.METRIC_LOSERS -> MoversMetric.LOSERS
        else -> MoversMetric.GAINERS
    }
    val gainers = snapshot.gainers(limit).mapIndexed { i, m -> m.toRow(i + 1) }
    val losers = snapshot.losers(limit).mapIndexed { i, m -> m.toRow(i + 1) }
    val singleMetric = slot.params.containsKey(SlotParamKeys.METRIC)
    return MoversData(
        gainers = gainers,
        losers = losers,
        layout = if (singleMetric) MoversLayout.TABS else MoversLayout.SPLIT,
        activeTab = metric,
        phase = when {
            gainers.isEmpty() && losers.isEmpty() -> ModulePhase.EMPTY
            else -> ModulePhase.READY
        },
        stale = snapshot.stale,
        staleAgoMinutes = staleMinutes(nowMs, snapshot.updatedAtMs)
    )
}

private fun com.milolab.btctv.data.hub.Mover.toRow(rank: Int): MoverRow = MoverRow(
    rank = rank,
    instrument = instrument,
    price = price,
    changePct = changePct,
    volumeLabel = fmtUsdCompact(quoteVolume24h),
    priceDecimals = instrument.priceDecimals
)

fun tickerData(
    slot: Slot,
    instruments: List<Instrument>,
    quotes: Map<String, Quote>,
    overview: MarketOverview?,
    nowMs: Long = System.currentTimeMillis()
): TickerStripData {
    val speed = when (slot.params[SlotParamKeys.SPEED]) {
        SlotParamKeys.SPEED_SLOW -> TickerSpeed.SLOW
        SlotParamKeys.SPEED_FAST -> TickerSpeed.FAST
        else -> TickerSpeed.NORMAL
    }
    val items = ArrayList<TickerItem>(instruments.size + 3)
    for (inst in instruments) {
        val q = quotes[inst.id]
        items += TickerItem(
            name = inst.baseCurrency.ifBlank { inst.displayName },
            price = q?.price,
            priceDecimals = inst.priceDecimals,
            changePct = q?.changePct
        )
    }
    if (slot.params[SlotParamKeys.INCLUDE_OVERVIEW] == "true" && overview != null) {
        overview.btcDominance?.let {
            items += TickerItem("BTCD", valueText = String.format(java.util.Locale.US, "%.2f%%", it))
        }
        overview.totalMarketCapUsd?.let {
            items += TickerItem(
                "TOTAL",
                valueText = fmtUsdCompact(it),
                changePct = overview.totalChange24hPct
            )
        }
        overview.stablecoinMarketCapUsd?.let {
            items += TickerItem("STABLE", valueText = fmtUsdCompact(it))
        }
    }
    return TickerStripData(
        items = items,
        speed = speed,
        phase = if (items.isEmpty()) ModulePhase.LOADING else ModulePhase.READY,
        stale = overview?.stale == true,
        staleAgoMinutes = overview?.let { staleMinutes(nowMs, it.fetchedAtMs) }
    )
}

/** 内置标的 + 目录 + 自选 + 最近，按顺序解析 Fixed 绑定。 */
fun defaultInstrumentLookup(
    catalog: List<Instrument>,
    favorites: List<Instrument>,
    recent: List<Instrument>
): (String) -> Instrument? {
    val map = LinkedHashMap<String, Instrument>()
    for (list in listOf(Instruments.HOT, favorites, recent, catalog)) {
        for (inst in list) map.putIfAbsent(inst.id, inst)
    }
    return { id -> map[id] ?: Instruments.byId(id) }
}
