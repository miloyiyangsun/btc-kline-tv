package com.milolab.btctv.data.hub

import com.milolab.btctv.data.Conn
import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.MarketClient
import com.milolab.btctv.data.OkxTickerRow
import com.milolab.btctv.data.PROVIDER_OKX
import com.milolab.btctv.data.Quote
import com.milolab.btctv.data.mergeWith
import com.milolab.btctv.data.OkxFeed
import com.milolab.btctv.data.parseOkxTickerWsMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong

/**
 * 同屏多标的报价中枢：
 * - 一条 OKX 公共 WS（tickers 频道）订阅最多 [maxSubscriptions] 个 instId，集合变化只发增/减量；
 * - 超过上限的 OKX 标的和所有非 OKX 标的走 REST 批量/单标的轮询；
 * - WS 15s 无消息自动切 REST 批量兜底，30s 无消息强制重连，退避 1s→30s；
 * - 对外按 instrument.id 暴露最新报价，250ms 合批且价格没变不重复发射。
 *
 * 调用方只需 [setInstruments]，UI 组合变化（布局切换）时重设一次集合即可，不会重建连接。
 */
class QuoteHub(
    private val client: MarketClient,
    private val scope: CoroutineScope,
    private val maxSubscriptions: Int = 12,
    private val debounceMs: Long = 250L,
    private val wsStaleMs: Long = 15_000L,
    private val wsReconnectMs: Long = 30_000L,
    private val restPollMs: Long = 10_000L,
    private val pingMs: Long = 10_000L,
    private val now: () -> Long = System::currentTimeMillis,
    private val api: OkxPublicApi = OkxPublicApi(client.http)
) {

    private val _quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())
    val quotes: StateFlow<Map<String, Quote>> = _quotes.asStateFlow()

    private val _conn = MutableStateFlow(Conn.CONNECTING)
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    private val lock = Any()
    private val desired = LinkedHashMap<String, Instrument>()
    private val okxToApp = HashMap<String, String>()
    private val debouncer = QuoteDebouncer()
    private val link = LinkHealth(wsStaleMs, wsReconnectMs)
    private val backoff = ReconnectBackoff()

    private var socket: WebSocket? = null
    private var socketGeneration = 0
    private var connecting = false
    private var subscribed: Set<String> = emptySet()
    private var lastPingMs = 0L
    private var lastRestPollMs = 0L
    private var restFailures = 0
    private var reconnectAtMs = 0L
    private var pollBusy = false
    private var closed = false
    private val wsTicks = AtomicLong()

    private val supervisor: Job = scope.launch { supervise() }

    /** 布局变化时整体重设；内部做增/减量订阅，不重连 */
    fun setInstruments(items: Collection<Instrument>) {
        val next = LinkedHashMap<String, Instrument>(items.size)
        for (inst in items) next[inst.id] = inst

        val removed: Set<String>
        synchronized(lock) {
            if (closed) return
            removed = desired.keys - next.keys
            if (desired.keys == next.keys && desired.values.map { it.symbols } == next.values.map { it.symbols }) {
                desired.clear()
                desired.putAll(next)
                rebuildOkxIndexLocked()
                return
            }
            desired.clear()
            desired.putAll(next)
            rebuildOkxIndexLocked()
            if (removed.isNotEmpty()) {
                for (id in removed) debouncer.forget(id)
                val filtered = _quotes.value.filterKeys { it !in removed }
                if (filtered.size != _quotes.value.size) _quotes.value = filtered
            }
        }
        syncSubscriptions()
        if (synchronized(lock) { wsTargetsLocked().isEmpty() }) dropSocket("idle")
    }

    fun addInstrument(inst: Instrument) {
        setInstruments(synchronized(lock) { desired.values.toList() } + inst)
    }

    fun removeInstrument(instrumentId: String) {
        setInstruments(synchronized(lock) { desired.values.filterNot { it.id == instrumentId } })
    }

    fun instruments(): List<Instrument> = synchronized(lock) { desired.values.toList() }

    fun quote(instrumentId: String): Quote? = _quotes.value[instrumentId]

    /** 已解析的 WS tickers 推送条数（诊断用，不参与业务逻辑） */
    internal fun wsTickCount(): Long = wsTicks.get()

    fun close() {
        val ws: WebSocket?
        synchronized(lock) {
            if (closed) return
            closed = true
            socketGeneration++
            ws = socket
            socket = null
            connecting = false
            subscribed = emptySet()
            desired.clear()
            okxToApp.clear()
            debouncer.reset()
        }
        supervisor.cancel()
        runCatching { ws?.close(1000, "client close") }
        _conn.value = Conn.OFFLINE
    }

    // ------------------------------------------------------------ 主管道

    private suspend fun supervise() {
        while (true) {
            delay(debounceMs)
            if (synchronized(lock) { closed }) return
            flushQuotes()
            try {
                maintainSocket(now())
                pollRest(now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 监督循环不能死；下一轮继续
            }
            updateConn()
        }
    }

    private fun flushQuotes() {
        val batch = debouncer.drain()
        if (batch.isEmpty()) return
        val accepted = synchronized(lock) {
            if (desired.keys.containsAll(batch.keys)) batch else batch.filterKeys { it in desired }
        }
        if (accepted.isNotEmpty()) _quotes.update { it + accepted }
    }

    private fun updateConn() {
        val nowMs = now()
        val hasQuotes = _quotes.value.isNotEmpty()
        val next = synchronized(lock) {
            when {
                link.isFresh(nowMs) -> Conn.LIVE
                restFailures >= 3 && !hasQuotes -> Conn.OFFLINE
                hasQuotes -> Conn.POLLING
                else -> Conn.CONNECTING
            }
        }
        if (_conn.value != next) _conn.value = next
    }

    // ------------------------------------------------------------- WS

    private fun maintainSocket(nowMs: Long) {
        val targets = synchronized(lock) { wsTargetsLocked() }
        if (targets.isEmpty()) {
            if (synchronized(lock) { socket != null || connecting }) dropSocket("idle")
            return
        }

        val stale = synchronized(lock) {
            socket != null && link.open && link.shouldReconnect(nowMs)
        }
        if (stale) dropSocket("stale")

        val shouldOpen = synchronized(lock) {
            !closed && socket == null && !connecting && nowMs >= reconnectAtMs
        }
        if (shouldOpen) openSocket()
        syncSubscriptions()

        val ws = synchronized(lock) { socket }
        if (ws != null && synchronized(lock) { link.open && nowMs - lastPingMs >= pingMs }) {
            lastPingMs = nowMs
            runCatching { ws.send("ping") }
        }
    }

    private fun openSocket() {
        val gen: Int
        synchronized(lock) {
            if (closed || connecting || socket != null) return
            connecting = true
            socketGeneration++
            gen = socketGeneration
        }
        val ws = client.wsHttp.newWebSocket(
            Request.Builder().url(OkxFeed.WS_URL).build(),
            Listener(gen)
        )
        synchronized(lock) {
            if (closed || gen != socketGeneration) {
                runCatching { ws.close(1000, "stale") }
                return
            }
            socket = ws
        }
    }

    private fun syncSubscriptions() {
        val ws = synchronized(lock) { socket } ?: return
        val delta = synchronized(lock) {
            if (!link.open) return
            val target = wsTargetsLocked().map { it.second }.toSet()
            val d = subscriptionDelta(subscribed, target)
            if (!d.isEmpty) subscribed = target
            d
        }
        if (delta.isEmpty) return
        if (delta.unsubscribe.isNotEmpty()) runCatching { ws.send(OkxFeed.unsubscribe(delta.unsubscribe)) }
        if (delta.subscribe.isNotEmpty()) runCatching { ws.send(OkxFeed.subscribe(delta.subscribe)) }
    }

    private fun dropSocket(reason: String) {
        val ws = synchronized(lock) {
            socketGeneration++
            val old = socket
            socket = null
            connecting = false
            subscribed = emptySet()
            link.onFailure()
            reconnectAtMs = 0L
            old
        }
        runCatching { ws?.close(1000, reason) }
    }

    private fun onSocketDown(gen: Int) {
        synchronized(lock) {
            if (closed || gen != socketGeneration) return
            socket = null
            connecting = false
            subscribed = emptySet()
            link.onFailure()
            reconnectAtMs = now() + backoff.nextDelayMs()
        }
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val nowMs = now()
            synchronized(lock) {
                if (closed || gen != socketGeneration) {
                    runCatching { webSocket.close(1000, "stale") }
                    return
                }
                socket = webSocket
                connecting = false
                link.onOpen(nowMs)
                backoff.reset()
                restFailures = 0
                subscribed = emptySet()
            }
            syncSubscriptions()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val nowMs = now()
            synchronized(lock) {
                if (closed || gen != socketGeneration) return
                link.onMessage(nowMs)
            }
            if (text == "ping") {
                runCatching { webSocket.send("pong") }
                return
            }
            if (text == "pong" || !text.startsWith("{")) return
            val row = try {
                parseOkxTickerWsMessage(text)
            } catch (e: Exception) {
                null
            } ?: return
            wsTicks.incrementAndGet()
            deliverOkx(listOf(row))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(1000, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onSocketDown(gen)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onSocketDown(gen)
        }
    }

    // ----------------------------------------------------------- REST 兜底

    private fun pollRest(nowMs: Long) {
        var batchTypes: List<String> = emptyList()
        var singles: List<Instrument> = emptyList()
        synchronized(lock) {
            if (closed || pollBusy || nowMs - lastRestPollMs < restPollMs) return
            val targets = okxTargetsLocked()
            val fresh = link.isFresh(nowMs)
            val overflow = if (targets.size > maxSubscriptions) {
                targets.drop(maxSubscriptions)
            } else {
                emptyList()
            }
            if (targets.isNotEmpty() && (!fresh || overflow.isNotEmpty())) {
                val relevant = if (fresh) overflow else targets
                batchTypes = relevant.map { instTypeOf(it.second) }.distinct()
            }
            singles = desired.values.filter {
                it.symbolFor(PROVIDER_OKX) == null && preferredProvider(it) != null
            }
            if (batchTypes.isEmpty() && singles.isEmpty()) return
            lastRestPollMs = nowMs
            pollBusy = true
        }
        scope.launch {
            try {
                if (batchTypes.isNotEmpty()) fetchBatch(batchTypes)
                for (inst in singles) fetchSingle(inst)
            } finally {
                synchronized(lock) { pollBusy = false }
            }
        }
    }

    private suspend fun fetchBatch(types: List<String>) {
        val rows: Map<String, OkxTickerRow> = try {
            api.tickerMap(types)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) { restFailures++ }
            return
        }
        synchronized(lock) { restFailures = 0 }
        deliverOkx(rows.values)
    }

    private suspend fun fetchSingle(inst: Instrument) {
        val provider = preferredProvider(inst) ?: return
        val quote = try {
            client.fetchQuote(provider, inst)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val merged = quote.mergeWith(_quotes.value[inst.id])
        debouncer.submit(inst.id, merged)
    }

    private fun deliverOkx(rows: Collection<OkxTickerRow>) {
        for (row in rows) {
            val appId = synchronized(lock) { okxToApp[row.instId] } ?: continue
            val merged = row.toQuote().mergeWith(_quotes.value[appId])
            debouncer.submit(appId, merged)
        }
    }

    // ------------------------------------------------------------- 工具

    private fun rebuildOkxIndexLocked() {
        okxToApp.clear()
        for (inst in desired.values) {
            inst.symbolFor(PROVIDER_OKX)?.let { okxToApp[it] = inst.id }
        }
    }

    /** (instrument, okxInstId) 按插入顺序；前 [maxSubscriptions] 个走 WS */
    private fun okxTargetsLocked(): List<Pair<Instrument, String>> =
        desired.values.mapNotNull { inst -> inst.symbolFor(PROVIDER_OKX)?.let { inst to it } }

    private fun wsTargetsLocked(): List<Pair<Instrument, String>> =
        okxTargetsLocked().take(maxSubscriptions)

    private companion object {
        fun instTypeOf(okxInstId: String): String =
            if (okxInstId.endsWith("-SWAP")) "SWAP" else "SPOT"
    }
}
