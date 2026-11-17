package com.milolab.btctv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.Conn
import com.milolab.btctv.data.IndicatorGroup
import com.milolab.btctv.data.IndicatorSettings
import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.InstrumentCatalog
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.MarketClient
import com.milolab.btctv.data.MarketFilter
import com.milolab.btctv.data.MarketType
import com.milolab.btctv.data.PROVIDER_OKX
import com.milolab.btctv.data.Quote
import com.milolab.btctv.data.StockKind
import com.milolab.btctv.data.StockUniverse
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.UiState
import com.milolab.btctv.data.applyTick
import com.milolab.btctv.data.indicators.IndicatorEngine
import com.milolab.btctv.data.indicators.IndicatorRegistry
import com.milolab.btctv.data.indicators.IndicatorResult
import com.milolab.btctv.data.mergeWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class MarketViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val client = MarketClient()
    private val catalog = InstrumentCatalog(app, client)

    /** 搜索目录：内置热门 + 交易所缓存/拉取结果 */
    private var catalogItems: List<Instrument> = Instruments.HOT
    private var catalogJob: Job? = null

    /** 本地搜索的轻防抖，避免每个按键都重排整份目录 */
    private var searchJob: Job? = null
    private var searchQuerySeq = 0L

    private val _ui = MutableStateFlow(
        UiState(
            instrument = prefs.instrument,
            tf = prefs.tf,
            provider = prefs.provider,
            style = prefs.style,
            flash = prefs.flash,
            antiBurn = prefs.antiBurn,
            showClock = prefs.showClock,
            showVolume = prefs.showVolume,
            autoStart = prefs.autoStart,
            recent = prefs.recent,
            favorites = prefs.favorites,
            indicatorSettings = prefs.indicatorSettings
        )
    )
    val ui = _ui.asStateFlow()

    private val live = AtomicReference<Quote?>(null)
    private val liveSeq = AtomicLong(0L)
    private val lastLiveAt = AtomicLong(0L)
    private var session: Job? = null

    // ---- 指标 ----
    private val indicators = IndicatorEngine()
    private val indicatorResults = AtomicReference<Map<String, IndicatorResult>>(emptyMap())
    /** 合并 120ms 的实时刷新请求：计算不过来时只保留“还需要再算一次”。 */
    private val indicatorRequests = Channel<Unit>(Channel.CONFLATED)
    private val indicatorFullPending = AtomicBoolean(false)
    @Volatile
    private var indicatorSettingsUsed: IndicatorSettings? = null
    private var noticeJob: Job? = null
    /**
     * 当前会话要拉的K线根数。开启长预热指标时自动抬高（上限 500），
     * 关闭指标不会立刻缩回去，避免来回重拉历史。
     */
    private var candleLimit = IndicatorSettings.requiredBars(_ui.value.indicatorSettings)

    init {
        startIndicatorWorker()
        startSession()
    }

    // ------------------------------------------------------------ 会话控制

    private fun startSession() {
        session?.cancel()
        live.set(null)
        indicatorResults.set(emptyMap())
        indicatorSettingsUsed = null
        indicatorFullPending.set(false)
        candleLimit = IndicatorSettings.requiredBars(_ui.value.indicatorSettings)
        _ui.update {
            it.copy(
                candles = emptyList(),
                indicatorResults = emptyMap(),
                dataRevision = it.dataRevision + 1,
                conn = Conn.CONNECTING,
                firstLoad = true
            )
        }
        session = viewModelScope.launch {
            launch { publishLoop() }
            launch { watchdogLoop() }
            runSession()
        }
    }

    private suspend fun runSession() {
        var backoff = 1500L
        while (currentCoroutineContext().isActive) {
            val start = _ui.value
            val snap = try {
                client.bootstrap(start.provider, start.instrument, start.tf, candleLimit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            if (snap == null) {
                _ui.update { it.copy(conn = if (it.candles.isEmpty()) Conn.OFFLINE else Conn.POLLING) }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000)
                continue
            }

            backoff = 1500L
            prefs.provider = snap.provider
            live.set(snap.quote)
            lastLiveAt.set(System.currentTimeMillis())
            _ui.update {
                it.copy(
                    provider = snap.provider,
                    candles = snap.candles,
                    price = snap.quote.price,
                    quote = snap.quote,
                    conn = Conn.POLLING,
                    firstLoad = false,
                    dataRevision = it.dataRevision + 1
                )
            }
            refreshIndicators(full = true)
            ensureHistory(IndicatorSettings.requiredBars(_ui.value.indicatorSettings))

            try {
                coroutineScope {
                    val refresh = launch {
                        while (isActive) {
                            delay(20_000)
                            runCatching {
                                val cs = client.fetchCandles(
                                    snap.provider,
                                    start.instrument,
                                    start.tf,
                                    candleLimit
                                )
                                if (cs.isNotEmpty()) {
                                    _ui.update { st ->
                                        st.copy(candles = cs, dataRevision = st.dataRevision + 1)
                                    }
                                    refreshIndicators(full = true)
                                }
                            }
                        }
                    }
                    try {
                        client.liveStream(snap.provider, start.instrument).collect { q ->
                            live.set(q)
                            liveSeq.incrementAndGet()
                            lastLiveAt.set(System.currentTimeMillis())
                        }
                    } finally {
                        refresh.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // WS 断线，回到循环重连
            }

            _ui.update { if (it.conn == Conn.LIVE) it.copy(conn = Conn.POLLING) else it }
            delay(1200)
        }
    }

    /** 把 WS 的高频推送平滑成 ~8fps 的界面刷新，同时实时修补最后一根K线 */
    private suspend fun publishLoop() {
        var savedAt = 0L
        var seenSeq = -1L
        while (currentCoroutineContext().isActive) {
            delay(120)
            val q = live.get() ?: continue
            val now = System.currentTimeMillis()
            val seq = liveSeq.get()
            val fromWs = seq != seenSeq
            seenSeq = seq
            val changed = q.price != _ui.value.price
            var candlesChanged = false
            var appended = false
            _ui.update { st ->
                val merged = q.mergeWith(st.quote ?: st.candles.lastOrNull()?.let { Quote(it.c, it.o, it.h, it.l, it.v) })
                if (st.candles.isEmpty()) {
                    return@update st.copy(
                        price = merged.price,
                        prevPrice = if (changed) st.price else st.prevPrice,
                        quote = merged,
                        conn = if (fromWs) Conn.LIVE else st.conn,
                        tickCount = if (changed) st.tickCount + 1 else st.tickCount
                    )
                }
                val nextCandles = st.candles.applyTick(merged.price, st.tf.seconds, now)
                val lastChanged = nextCandles.size != st.candles.size ||
                    nextCandles.last() != st.candles.last()
                candlesChanged = lastChanged
                appended = nextCandles.size != st.candles.size
                st.copy(
                    price = merged.price,
                    prevPrice = if (changed) st.price else st.prevPrice,
                    quote = merged,
                    candles = if (lastChanged) nextCandles else st.candles,
                    dataRevision = if (lastChanged) st.dataRevision + 1 else st.dataRevision,
                    conn = if (fromWs) Conn.LIVE else st.conn,
                    tickCount = if (changed) st.tickCount + 1 else st.tickCount
                )
            }
            if (candlesChanged) refreshIndicators(full = appended)
            if (now - savedAt > 20_000) {
                savedAt = now
                prefs.provider = _ui.value.provider
            }
        }
    }

    /** 看门狗：WS/轮询久了没消息就补一次 REST 报价，保证画面一直在动 */
    private suspend fun watchdogLoop() {
        while (currentCoroutineContext().isActive) {
            delay(3000)
            val st = _ui.value
            val provider = st.provider ?: PROVIDER_OKX
            val age = System.currentTimeMillis() - lastLiveAt.get()
            if (age < 15_000) continue
            if (st.conn == Conn.LIVE) _ui.update { it.copy(conn = Conn.POLLING) }
            try {
                val q = client.fetchQuote(provider, st.instrument)
                live.set(q)
                lastLiveAt.set(System.currentTimeMillis() - 9_000)
                _ui.update { if (it.conn == Conn.OFFLINE) it.copy(conn = Conn.POLLING) else it }
            } catch (e: Exception) {
                if (st.candles.isEmpty()) _ui.update { it.copy(conn = Conn.OFFLINE) }
            }
        }
    }

    // ---------------------------------------------------------------- 交互

    fun cycleTf(delta: Int) {
        val next = _ui.value.tf.step(delta)
        prefs.tf = next
        _ui.update { it.copy(tf = next) }
        startSession()
    }

    fun setTf(tf: TF) {
        if (tf == _ui.value.tf) return
        prefs.tf = tf
        _ui.update { it.copy(tf = tf) }
        startSession()
    }

    /** 遥控器 ▲▼：缩放图表显示的K线根数 */
    fun zoom(delta: Int) {
        val steps = listOf(45, 60, 90, 120, 180, 240)
        val cur = _ui.value.zoom
        val idx = steps.indexOfFirst { it >= cur }.coerceAtLeast(0)
        val next = steps[(idx + delta).coerceIn(0, steps.size - 1)]
        if (next != cur) _ui.update { it.copy(zoom = next) }
    }

    fun openSettings() = _ui.update {
        it.copy(settingsOpen = true, searchOpen = false, searchQuery = "", searchResults = emptyList())
    }

    fun closeSettings() = _ui.update {
        it.copy(settingsOpen = false, expandedIndicator = null, indicatorNotice = null)
    }

    // ------------------------------------------------------------ 指标

    fun toggleIndicator(id: String) {
        val cur = _ui.value.indicatorSettings
        val spec = IndicatorRegistry.byId(id) ?: return
        val next = cur.withToggled(id)
        if (next == cur) {
            if (!cur.isEnabled(id) && spec.type == com.milolab.btctv.data.indicators.IndicatorType.OSCILLATOR) {
                showIndicatorNotice("最多同时显示 ${IndicatorSettings.MAX_OSCILLATORS} 个副图")
            }
            return
        }
        applyIndicatorSettings(next)
    }

    fun stepIndicatorParam(id: String, key: String, delta: Int) {
        val cur = _ui.value.indicatorSettings
        val next = cur.withParamStep(id, key, delta)
        if (next != cur) applyIndicatorSettings(next)
    }

    fun expandIndicator(id: String?) = _ui.update {
        it.copy(expandedIndicator = if (it.expandedIndicator == id) null else id)
    }

    fun toggleIndicatorGroup(group: IndicatorGroup) = _ui.update {
        val key = group.name
        it.copy(
            collapsedIndicatorGroups = if (key in it.collapsedIndicatorGroups) {
                it.collapsedIndicatorGroups - key
            } else {
                it.collapsedIndicatorGroups + key
            }
        )
    }

    fun allIndicatorsOff() {
        val next = _ui.value.indicatorSettings.allOff()
        if (next != _ui.value.indicatorSettings) applyIndicatorSettings(next)
    }

    fun resetIndicators() = applyIndicatorSettings(_ui.value.indicatorSettings.restoreDefaults())

    private fun showIndicatorNotice(text: String) {
        noticeJob?.cancel()
        _ui.update { it.copy(indicatorNotice = text) }
        noticeJob = viewModelScope.launch {
            delay(2600)
            _ui.update { if (it.indicatorNotice == text) it.copy(indicatorNotice = null) else it }
        }
    }

    private fun applyIndicatorSettings(next: IndicatorSettings) {
        if (next == _ui.value.indicatorSettings) return
        prefs.indicatorSettings = next
        val need = IndicatorSettings.requiredBars(next)
        candleLimit = max(candleLimit, need)
        noticeJob?.cancel()
        indicatorResults.set(emptyMap())
        indicatorSettingsUsed = null
        _ui.update {
            it.copy(
                indicatorSettings = next,
                indicatorResults = emptyMap(),
                dataRevision = it.dataRevision + 1,
                expandedIndicator = it.expandedIndicator?.takeIf { id -> next.isEnabled(id) },
                indicatorNotice = null
            )
        }
        refreshIndicators(full = true)
        ensureHistory(need)
    }

    /**
     * 单线程指标工人：请求用 CONFLATED Channel 合并，计算期间到来的新 tick 不会取消
     * 正在跑的计算，也不会堆出一串并发任务；一组结果算完后只处理最新的一份快照。
     */
    private fun startIndicatorWorker() {
        viewModelScope.launch(Dispatchers.Default) {
            for (ignored in indicatorRequests) {
                val snapshot = _ui.value
                val settings = snapshot.indicatorSettings
                if (settings.enabled.isEmpty() || snapshot.candles.isEmpty()) {
                    indicatorFullPending.set(false)
                    indicatorSettingsUsed = null
                    withContext(Dispatchers.Main.immediate) {
                        _ui.update {
                            if (it.indicatorResults.isEmpty()) it
                            else it.copy(indicatorResults = emptyMap())
                        }
                    }
                    continue
                }
                val revision = snapshot.dataRevision
                val previous = indicatorResults.get()
                val full = indicatorFullPending.getAndSet(false) ||
                    indicatorSettingsUsed != settings ||
                    previous.isEmpty()
                val results = try {
                    if (full) {
                        indicators.compute(settings, snapshot.candles, revision)
                    } else {
                        indicators.refreshLast(settings, snapshot.candles, revision, previous)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                withContext(Dispatchers.Main.immediate) {
                    _ui.update { cur ->
                        if (cur.dataRevision != revision || cur.indicatorSettings != settings) {
                            cur
                        } else {
                            indicatorResults.set(results)
                            indicatorSettingsUsed = settings
                            cur.copy(indicatorResults = results)
                        }
                    }
                }
            }
        }
    }

    /**
     * 全量计算或只贴最后一根。请求本身是合并的：慢计算不会被每个 120ms tick 取消，
     * 结果发布前校验数据修订号和参数，避免旧快照覆盖新数据。
     */
    private fun refreshIndicators(full: Boolean) {
        val snapshot = _ui.value
        if (snapshot.indicatorSettings.enabled.isEmpty()) {
            indicatorFullPending.set(false)
            indicatorSettingsUsed = null
            indicatorResults.set(emptyMap())
            if (snapshot.indicatorResults.isNotEmpty()) {
                _ui.update { it.copy(indicatorResults = emptyMap()) }
            }
            return
        }
        if (snapshot.candles.isEmpty()) return
        if (full) indicatorFullPending.set(true)
        indicatorRequests.trySend(Unit)
    }

    /** 开启长预热指标后，后台补拉历史；不打断实时 WS。 */
    private fun ensureHistory(need: Int) {
        val st = _ui.value
        if (st.firstLoad || st.candles.isEmpty() || need <= st.candles.size) return
        val requestInstrument = st.instrument
        val requestTf = st.tf
        val provider = st.provider ?: PROVIDER_OKX
        val limit = max(need, candleLimit)
        viewModelScope.launch {
            try {
                val cs = client.fetchCandles(provider, requestInstrument, requestTf, limit)
                if (cs.isEmpty()) return@launch
                _ui.update { cur ->
                    if (cur.instrument.id != requestInstrument.id || cur.tf != requestTf) {
                        cur
                    } else {
                        cur.copy(candles = cs, dataRevision = cur.dataRevision + 1)
                    }
                }
                refreshIndicators(full = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 补历史失败就先用现有K线，指标预热期显示为空
            }
        }
    }

    // ------------------------------------------------------------ 标的切换

    /** 选中标的：持久化 → 记录最近使用 → 重连会话 */
    fun setInstrument(inst: Instrument) {
        closeSearch()
        val cur = _ui.value.instrument
        if (inst.id == cur.id) {
            if (inst != cur) {
                prefs.instrument = inst
                _ui.update { it.copy(instrument = inst) }
            }
            return
        }
        prefs.instrument = inst
        pushRecent(inst)
        _ui.update { it.copy(instrument = inst) }
        startSession()
    }

    private fun pushRecent(inst: Instrument) {
        val next = (listOf(inst) + _ui.value.recent.filterNot { it.id == inst.id }).take(RECENT_MAX)
        prefs.recent = next
        _ui.update { it.copy(recent = next) }
    }

    fun openSearch() {
        searchJob?.cancel()
        _ui.update {
            it.copy(
                settingsOpen = false,
                searchOpen = true,
                searchQuery = "",
                searchResults = emptyList(),
                searching = false
            )
        }
        loadCatalog()
    }

    fun closeSearch() {
        searchJob?.cancel()
        _ui.update {
            it.copy(
                searchOpen = false,
                searchQuery = "",
                searchResults = emptyList(),
                searching = false
            )
        }
    }

    fun setFilter(filter: MarketFilter) = _ui.update { it.copy(searchFilter = filter) }

    /** 输入后稍等片刻再本地评分，目录在内存里，搜索不联网 */
    fun setQuery(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            _ui.update { it.copy(searchQuery = query, searchResults = emptyList(), searching = false) }
            return
        }
        _ui.update { it.copy(searchQuery = query, searching = true) }
        val seq = ++searchQuerySeq
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            if (seq != searchQuerySeq) return@launch
            val results = InstrumentCatalog.search(q, catalogItems)
            _ui.update { st ->
                if (st.searchQuery.trim() != q) {
                    st
                } else {
                    st.copy(searchResults = results, searching = false)
                }
            }
        }
    }

    fun toggleFavorite(inst: Instrument) {
        val current = _ui.value.favorites
        val next = if (current.any { it.id == inst.id }) {
            current.filterNot { it.id == inst.id }
        } else {
            (listOf(inst) + current).take(FAVORITE_MAX)
        }
        prefs.favorites = next
        _ui.update { it.copy(favorites = next) }
    }

    // ------------------------------------------------------------ 搜索目录

    private fun loadCatalog() {
        if (catalogJob?.isActive == true) return
        _ui.update { it.copy(catalogLoading = true) }

        catalogJob = viewModelScope.launch {
            val cached = catalog.cached()
            if (cached.isNotEmpty()) applyCatalog(cached)

            val fresh = try {
                catalog.refreshIfStale()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (fresh.isNotEmpty()) applyCatalog(fresh)

            val loaded = catalogItems.size > Instruments.HOT.size
            _ui.update { it.copy(catalogLoading = false, catalogReady = loaded) }
        }
    }

    private fun applyCatalog(items: List<Instrument>) {
        catalogItems = Instruments.merge(Instruments.HOT + items)
        // 热门美股只展示交易所目录里真的有永续 symbol 的代码
        val hotStocks = StockUniverse.HOT_SYMBOLS.mapNotNull { symbol ->
            catalogItems.firstOrNull {
                it.marketType == MarketType.STOCK &&
                    it.stockKind == StockKind.PERP &&
                    it.baseCurrency == symbol
            }
        }
        val hotAll = Instruments.HOT + hotStocks
        _ui.update { st ->
            val known = catalogItems + hotAll + st.recent + st.favorites
            fun resolve(list: List<Instrument>) =
                list.map { old -> known.firstOrNull { it.id == old.id } ?: old }
            st.copy(
                hot = resolve(hotAll),
                recent = resolve(st.recent),
                favorites = resolve(st.favorites),
                searchResults = if (st.searchQuery.isBlank()) emptyList()
                else InstrumentCatalog.search(st.searchQuery, catalogItems)
            )
        }
    }

    // ------------------------------------------------------------ 设置

    fun setStyle(style: ChartStyle) {
        prefs.style = style
        _ui.update { it.copy(style = style) }
    }

    fun toggleFlash() {
        val v = !_ui.value.flash
        prefs.flash = v
        _ui.update { it.copy(flash = v) }
    }

    fun toggleAntiBurn() {
        val v = !_ui.value.antiBurn
        prefs.antiBurn = v
        _ui.update { it.copy(antiBurn = v) }
    }

    fun toggleClock() {
        val v = !_ui.value.showClock
        prefs.showClock = v
        _ui.update { it.copy(showClock = v) }
    }

    fun toggleVolume() {
        val v = !_ui.value.showVolume
        prefs.showVolume = v
        _ui.update { it.copy(showVolume = v) }
    }

    fun toggleAutoStart() {
        val v = !_ui.value.autoStart
        prefs.autoStart = v
        _ui.update { it.copy(autoStart = v) }
    }

    /** 从后台回到前台：离线状态就重连一次 */
    fun onAppResume() {
        if (_ui.value.conn == Conn.OFFLINE || _ui.value.conn == Conn.CONNECTING) {
            reconnect()
        }
    }

    fun reconnect() {
        _ui.update { it.copy(conn = Conn.CONNECTING) }
        startSession()
    }

    private companion object {
        const val RECENT_MAX = 12
        const val FAVORITE_MAX = 30
        const val SEARCH_DEBOUNCE_MS = 120L
    }
}
