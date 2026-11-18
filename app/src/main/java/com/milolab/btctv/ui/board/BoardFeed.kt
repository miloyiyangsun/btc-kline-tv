package com.milolab.btctv.ui.board

import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.applyTick
import com.milolab.btctv.data.hub.CandleStore
import com.milolab.btctv.data.hub.MoversRepository
import com.milolab.btctv.data.hub.QuoteHub
import com.milolab.btctv.data.hub.candleTtlMs
import com.milolab.btctv.data.overview.OverviewRepository
import com.milolab.btctv.ui.board.layout.LayoutSpec
import com.milolab.btctv.ui.board.layout.ModuleType
import com.milolab.btctv.ui.board.layout.SlotBinding
import com.milolab.btctv.ui.board.layout.leafSlotIds
import com.milolab.btctv.ui.board.modules.ChartDensity
import com.milolab.btctv.ui.board.modules.MainChartData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 非经典布局的数据装配器：
 * - 依据布局解析每个槽位的标的/周期，向 QuoteHub 重设订阅集合（最多 12 个）；
 * - 图表槽位共用 CandleStore（TTL + 单飞），同一 (标的, 周期) 只发一次请求；
 * - 大盘经 OverviewRepository，榜单经 MoversRepository（30s）；
 * - 主图（FollowMain 且无固定周期的第一个主图槽）直接使用 ViewModel 的完整 K 线/指标数据。
 *
 * 全部代码不依赖 Android，可在 preview 模块里离屏渲染或单测。
 */
class BoardFeed(
    parentScope: CoroutineScope,
    private val hub: QuoteHub,
    private val candles: CandleStore,
    private val movers: MoversRepository,
    private val overview: OverviewRepository,
    private val instrumentLookup: () -> (String) -> Instrument?,
    private val favorites: () -> List<Instrument>,
    private val hot: () -> List<Instrument>,
    private val mainChart: () -> MainChartData
) {
    private data class CandleEntry(
        val candles: List<Candle>,
        val loadedAtMs: Long,
        val failed: Boolean,
        val failedAtMs: Long
    )

    private val job = Job()
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private val _contents = MutableStateFlow<Map<String, SlotContent>>(emptyMap())
    val contents: StateFlow<Map<String, SlotContent>> = _contents.asStateFlow()

    private var spec: LayoutSpec? = null
    private var mainInstrument: Instrument? = null
    private var mainTf: TF = TF.M1
    private var plans: Map<String, SlotPlan> = emptyMap()
    private var primaryMainSlotId: String? = null

    private val candleEntries = HashMap<String, CandleEntry>()
    private val loadingKeys = HashSet<String>()

    init {
        scope.launch {
            hub.quotes.collect { rebuild() }
        }
        scope.launch {
            movers.snapshot.collect { rebuild() }
        }
        scope.launch {
            overview.overview.collect { rebuild() }
        }
        scope.launch { refreshLoop() }
        scope.launch { moversLoop() }
        scope.launch { overviewLoop() }
    }

    fun update(spec: LayoutSpec, main: Instrument, tf: TF) {
        this.spec = spec
        this.mainInstrument = main
        this.mainTf = tf
        val byId = instrumentLookup()
        val favs = favorites()
        val hotList = hot()
        val nextPlans = planSlots(spec, main, tf, byId, favs, hotList)
        plans = nextPlans
        primaryMainSlotId = spec.root.leafSlotIds().firstOrNull { id ->
            val slot = spec.slots[id] ?: return@firstOrNull false
            slot.module is ModuleType.MainChart &&
                slot.binding is SlotBinding.FollowMain &&
                slot.timeframe == null
        }
        hub.setInstruments(
            subscriptionInstruments(spec, nextPlans, main, favs, hotList)
        )
        requestCandles()
        requestAuxiliary(nextPlans)
        rebuild()
    }

    /** 进入或切到含榜单/大盘模块的布局时立刻取一次，避免等 30/60s 循环。 */
    private fun requestAuxiliary(nextPlans: Map<String, SlotPlan>) {
        if (nextPlans.values.any { it.slot.module is ModuleType.Movers }) {
            scope.launch { runCatching { movers.refresh(force = false) } }
        }
        if (nextPlans.values.any {
                it.slot.module is ModuleType.StatTile || it.slot.module is ModuleType.TickerStrip
            }
        ) {
            scope.launch { runCatching { overview.refresh(force = false) } }
        }
    }

    /** 离开看板时调用；QuoteHub 本身由 ViewModel 负责 close。 */
    fun stop() {
        job.cancel()
    }

    // ------------------------------------------------------------ 数据装配

    private fun rebuild() {
        val sp = spec ?: return
        val main = mainInstrument ?: return
        val quotes = hub.quotes.value
        val ov = overview.overview.value
        val snap = movers.snapshot.value
        val now = System.currentTimeMillis()

        val favs = favorites()
        val hotList = hot()

        val out = LinkedHashMap<String, SlotContent>(sp.slots.size)
        for ((id, plan) in plans) {
            val slot = plan.slot
            val inst = plan.instrument
            val quote = quotes[inst.id]
            out[id] = when (val module = slot.module) {
                ModuleType.MainChart -> {
                    if (id == primaryMainSlotId) {
                        SlotContent.Main(mainChart(), ChartDensity.FULL)
                    } else {
                        val tf = plan.tf ?: mainTf
                        val state = candleView(inst, tf, quote, now)
                        SlotContent.Main(
                            mainChartData(
                                instrument = inst,
                                tf = tf,
                                candles = state.candles,
                                quote = quote,
                                style = ChartStyle.CANDLE,
                                showVolume = false,
                                visibleBars = null,
                                stale = state.stale,
                                staleAgoMinutes = state.staleAgoMinutes
                            ),
                            ChartDensity.COMPACT
                        )
                    }
                }

                ModuleType.MiniChart -> {
                    val tf = plan.tf ?: TF.H1
                    val state = candleView(inst, tf, quote, now)
                    SlotContent.Mini(
                        miniChartData(
                            instrument = inst,
                            tf = tf,
                            candles = state.candles,
                            quote = quote,
                            stale = state.stale,
                            staleAgoMinutes = state.staleAgoMinutes
                        )
                    )
                }

                ModuleType.PriceCard -> {
                    val tf = plan.tf ?: sparkTimeframe(slot)
                    val state = candleView(inst, tf, quote, now)
                    SlotContent.Card(
                        priceCardData(
                            instrument = inst,
                            quote = quote,
                            sparkCandles = state.candles,
                            stale = state.stale,
                            staleAgoMinutes = state.staleAgoMinutes
                        )
                    )
                }

                is ModuleType.StatTile -> SlotContent.Stat(statTileData(module.stat, ov, now))

                ModuleType.Watchlist -> {
                    val pool = watchlistPool(slot, favs, hotList)
                    val selected = if (main.id in pool.map { it.id }) main.id else null
                    SlotContent.Watch(
                        watchlistData(
                            slot = slot,
                            instruments = pool,
                            quotes = quotes,
                            sparks = { inst -> sparkOf(inst) },
                            selectedInstrumentId = selected,
                            nowMs = now
                        )
                    )
                }

                ModuleType.Movers -> SlotContent.Movers(moversData(snap, slot, now))

                ModuleType.TickerStrip -> {
                    val pool = watchlistPool(slot, favs, hotList)
                    SlotContent.Ticker(tickerData(slot, pool, quotes, ov, now))
                }
            }
        }
        _contents.value = out
    }

    private data class CandleView(
        val candles: List<Candle>,
        val stale: Boolean,
        val staleAgoMinutes: Long?
    )

    private fun candleView(inst: Instrument, tf: TF, quote: com.milolab.btctv.data.Quote?, now: Long): CandleView {
        val entry = synchronized(candleEntries) { candleEntries[keyOf(inst, tf)] }
        val raw = entry?.candles ?: emptyList()
        val patched = if (quote != null && raw.isNotEmpty()) {
            raw.applyTick(quote.price, tf, now)
        } else {
            raw
        }
        val stale = entry != null &&
            (entry.failed || now - entry.loadedAtMs > (candleTtlMs(tf) * 3).coerceAtLeast(120_000L))
        val ago = when {
            entry == null -> null
            entry.failed -> staleMinutes(now, entry.failedAtMs)
            stale -> staleMinutes(now, entry.loadedAtMs)
            else -> null
        }
        return CandleView(patched, stale, ago)
    }

    private fun sparkOf(inst: Instrument): List<Double> {
        val cached = candles.peek(inst, TF.H1) ?: return emptyList()
        return cached.takeLast(24).map { it.c }
    }

    // ------------------------------------------------------------ 拉取循环

    private fun keyOf(inst: Instrument, tf: TF): String = inst.id + "|" + tf.name

    /** 布局/绑定变化时立刻加载；之后 refreshLoop 每 20s 走一次缓存检查。 */
    private fun requestCandles() {
        val now = System.currentTimeMillis()
        val wanted = HashSet<String>()
        for (plan in plans.values) {
            if (!plan.needsCandles) continue
            val tf = plan.tf ?: continue
            val key = keyOf(plan.instrument, tf)
            wanted += key
            val entry = synchronized(candleEntries) { candleEntries[key] }
            val fresh = entry != null && !entry.failed && now - entry.loadedAtMs < 20_000L
            if (!fresh) load(plan.instrument, tf, key)
        }
        synchronized(candleEntries) {
            val removed = candleEntries.keys.filterNot { it in wanted }
            removed.forEach { candleEntries.remove(it) }
        }
    }

    private fun load(inst: Instrument, tf: TF, key: String) {
        synchronized(loadingKeys) {
            if (!loadingKeys.add(key)) return
        }
        scope.launch {
            try {
                val data = candles.candles(inst, tf, 120)
                synchronized(candleEntries) {
                    candleEntries[key] = CandleEntry(
                        candles = data,
                        loadedAtMs = System.currentTimeMillis(),
                        failed = false,
                        failedAtMs = 0L
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val now = System.currentTimeMillis()
                synchronized(candleEntries) {
                    val old = candleEntries[key]
                    candleEntries[key] = CandleEntry(
                        candles = old?.candles ?: emptyList(),
                        loadedAtMs = old?.loadedAtMs ?: now,
                        failed = true,
                        failedAtMs = now
                    )
                }
            } finally {
                synchronized(loadingKeys) { loadingKeys.remove(key) }
                rebuild()
            }
        }
    }

    private suspend fun refreshLoop() {
        while (scope.isActive) {
            delay(20_000)
            requestCandles()
        }
    }

    private suspend fun moversLoop() {
        while (scope.isActive) {
            if (plans.values.any { it.slot.module is ModuleType.Movers }) {
                runCatching { movers.refresh(force = false) }
            }
            delay(30_000)
        }
    }

    private suspend fun overviewLoop() {
        while (scope.isActive) {
            if (plans.values.any { it.slot.module is ModuleType.StatTile } ||
                plans.values.any { it.slot.module is ModuleType.TickerStrip }
            ) {
                runCatching { overview.refresh(force = false) }
            }
            delay(60_000)
        }
    }
}
