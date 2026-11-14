package com.milolab.btctv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.Candle
import com.milolab.btctv.data.Coin
import com.milolab.btctv.data.Conn
import com.milolab.btctv.data.MarketClient
import com.milolab.btctv.data.PROVIDER_OKX
import com.milolab.btctv.data.Quote
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.UiState
import com.milolab.btctv.data.applyTick
import com.milolab.btctv.data.mergeWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MarketViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val client = MarketClient()

    private val _ui = MutableStateFlow(
        UiState(
            coin = prefs.coin,
            tf = prefs.tf,
            provider = prefs.provider,
            style = prefs.style,
            flash = prefs.flash,
            antiBurn = prefs.antiBurn,
            showClock = prefs.showClock,
            showVolume = prefs.showVolume,
            autoStart = prefs.autoStart
        )
    )
    val ui = _ui.asStateFlow()

    private val live = AtomicReference<Quote?>(null)
    private val liveSeq = AtomicLong(0L)
    private val lastLiveAt = AtomicLong(0L)
    private var session: Job? = null

    init {
        startSession()
    }

    // ------------------------------------------------------------ 会话控制

    private fun startSession() {
        session?.cancel()
        live.set(null)
        _ui.update { it.copy(candles = emptyList(), conn = Conn.CONNECTING, firstLoad = true) }
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
                client.bootstrap(start.provider, start.coin, start.tf)
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
                    firstLoad = false
                )
            }

            try {
                coroutineScope {
                    val refresh = launch {
                        while (isActive) {
                            delay(20_000)
                            runCatching {
                                val cs = client.fetchCandles(snap.provider, start.coin, start.tf)
                                if (cs.isNotEmpty()) _ui.update { st -> st.copy(candles = cs) }
                            }
                        }
                    }
                    try {
                        client.liveStream(snap.provider, start.coin).collect { q ->
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
            _ui.update { st ->
                val merged = q.mergeWith(st.quote ?: st.candles.lastOrNull()?.let { Quote(it.c, it.o, it.h, it.l, it.v) })
                st.copy(
                    price = merged.price,
                    prevPrice = if (changed) st.price else st.prevPrice,
                    quote = merged,
                    candles = if (st.candles.isEmpty()) st.candles
                    else st.candles.applyTick(merged.price, st.tf.seconds, now),
                    conn = if (fromWs) Conn.LIVE else st.conn,
                    tickCount = if (changed) st.tickCount + 1 else st.tickCount
                )
            }
            if (now - savedAt > 20_000) {
                savedAt = now
                prefs.provider = _ui.value.provider
            }
        }
    }

    /** 看门狗：WS 久了没消息就降级成 REST 轮询，保证画面一直在动 */
    private suspend fun watchdogLoop() {
        while (currentCoroutineContext().isActive) {
            delay(3000)
            val st = _ui.value
            val age = System.currentTimeMillis() - lastLiveAt.get()
            if (age < 15_000) continue
            if (st.conn == Conn.LIVE) _ui.update { it.copy(conn = Conn.POLLING) }
            try {
                val q = client.fetchQuote(st.provider ?: PROVIDER_OKX, st.coin)
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

    fun openSettings() = _ui.update { it.copy(settingsOpen = true) }

    fun closeSettings() = _ui.update { it.copy(settingsOpen = false) }

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
}
