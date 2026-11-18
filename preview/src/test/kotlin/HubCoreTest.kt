import com.milolab.btctv.data.Quote
import com.milolab.btctv.data.hub.LinkHealth
import com.milolab.btctv.data.hub.QuoteDebouncer
import com.milolab.btctv.data.hub.ReconnectBackoff
import com.milolab.btctv.data.hub.priceDecimalsOf
import com.milolab.btctv.data.hub.subscriptionDelta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HubCoreTest {

    // --------------------------------------------------------- 订阅集差量

    @Test
    fun deltaSubscribesOnlyNewInstruments() {
        val delta = subscriptionDelta(setOf("BTC-USDT", "ETH-USDT"), setOf("BTC-USDT", "ETH-USDT", "SOL-USDT"))
        assertEquals(listOf("SOL-USDT"), delta.subscribe)
        assertTrue(delta.unsubscribe.isEmpty())
        assertFalse(delta.isEmpty)
    }

    @Test
    fun deltaUnsubscribesOnlyRemovedInstruments() {
        val delta = subscriptionDelta(setOf("BTC-USDT", "ETH-USDT"), setOf("BTC-USDT"))
        assertTrue(delta.subscribe.isEmpty())
        assertEquals(listOf("ETH-USDT"), delta.unsubscribe)
    }

    @Test
    fun deltaHandlesSwapOfWholeSet() {
        val delta = subscriptionDelta(setOf("A", "B"), setOf("C", "D"))
        assertEquals(listOf("C", "D"), delta.subscribe)
        assertEquals(listOf("A", "B"), delta.unsubscribe)
    }

    @Test
    fun deltaIsEmptyWhenSetIsUnchanged() {
        val delta = subscriptionDelta(setOf("A", "B"), setOf("B", "A"))
        assertTrue(delta.isEmpty)
        assertTrue(delta.subscribe.isEmpty())
        assertTrue(delta.unsubscribe.isEmpty())
    }

    // ------------------------------------------------------------- 去抖

    @Test
    fun debouncerKeepsOnlyLatestAndSuppressesSamePrice() {
        val d = QuoteDebouncer()
        d.submit("BTC", Quote(100.0))
        d.submit("BTC", Quote(101.0))
        assertEquals(101.0, d.drain()["BTC"]?.price)
        // 价格没变就不发射
        d.submit("BTC", Quote(101.0))
        assertTrue(d.drain().isEmpty())
        // 变了才发射
        d.submit("BTC", Quote(102.0))
        assertEquals(102.0, d.drain()["BTC"]?.price)
    }

    @Test
    fun debouncerTracksInstrumentsIndependently() {
        val d = QuoteDebouncer()
        d.submit("BTC", Quote(100.0))
        d.submit("ETH", Quote(200.0))
        val first = d.drain()
        assertEquals(setOf("BTC", "ETH"), first.keys)
        d.submit("BTC", Quote(100.0))
        d.submit("ETH", Quote(201.0))
        val second = d.drain()
        assertEquals(setOf("ETH"), second.keys)
    }

    @Test
    fun debouncerResetAllowsRepublish() {
        val d = QuoteDebouncer()
        d.submit("BTC", Quote(100.0))
        assertEquals(1, d.drain().size)
        d.reset()
        d.submit("BTC", Quote(100.0))
        assertEquals(1, d.drain().size)
    }

    @Test
    fun debouncerForgetAllowsRepublishAfterReAdd() {
        val d = QuoteDebouncer()
        d.submit("BTC", Quote(100.0))
        assertEquals(1, d.drain().size)
        d.forget("BTC")
        d.submit("BTC", Quote(100.0))
        assertEquals(1, d.drain().size)
        // 其它标的的抑制状态不受影响
        d.submit("ETH", Quote(200.0))
        d.drain()
        d.forget("BTC")
        d.submit("ETH", Quote(200.0))
        assertTrue(d.drain().isEmpty())
    }

    // ------------------------------------------------------------- 链路健康

    @Test
    fun healthyLinkNeverPollsOrReconnects() {
        val link = LinkHealth(staleMs = 15_000L, reconnectMs = 30_000L)
        assertFalse(link.isFresh(0L))
        link.onOpen(1_000L)
        assertTrue(link.isFresh(1_000L))
        assertTrue(link.isFresh(15_999L))
        assertFalse(link.isFresh(16_000L))
        assertFalse(link.shouldPollRest(15_999L))
        assertFalse(link.shouldReconnect(15_999L))
    }

    @Test
    fun fifteenSecondsWithoutMessageStartsRestFallback() {
        val link = LinkHealth(staleMs = 15_000L, reconnectMs = 30_000L)
        link.onOpen(1_000L)
        link.onMessage(2_000L)
        assertTrue(link.isFresh(16_999L))
        assertFalse(link.isFresh(17_000L))
        assertTrue(link.shouldPollRest(17_000L))
        // 还没到 30s，不重连
        assertFalse(link.shouldReconnect(31_999L))
        assertTrue(link.shouldReconnect(32_000L))
    }

    @Test
    fun failureIsImmediatelyStaleAndReconnectable() {
        val link = LinkHealth()
        link.onOpen(1_000L)
        assertTrue(link.isFresh(1_100L))
        link.onFailure()
        assertFalse(link.open)
        assertFalse(link.isFresh(1_100L))
        assertTrue(link.shouldPollRest(1_100L))
        assertTrue(link.shouldReconnect(1_100L))
    }

    @Test
    fun anyMessageRefreshesTheLink() {
        val link = LinkHealth(staleMs = 15_000L)
        link.onOpen(0L)
        link.onMessage(14_000L)
        assertTrue(link.isFresh(28_000L))
    }

    // ------------------------------------------------------------- 重连退避

    @Test
    fun backoffDoublesWithoutJitter() {
        val backoff = ReconnectBackoff(baseMs = 1_000L, maxMs = 30_000L, random = { 0.0 })
        val delays = List(8) { backoff.nextDelayMs() }
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            delays
        )
    }

    @Test
    fun backoffJitterNeverExceedsCap() {
        val backoff = ReconnectBackoff(baseMs = 1_000L, maxMs = 30_000L, random = { 1.0 })
        val delays = List(8) { backoff.nextDelayMs() }
        assertEquals(listOf(1_250L, 2_500L, 5_000L, 10_000L, 20_000L, 30_000L, 30_000L, 30_000L), delays)
        assertTrue(delays.all { it <= 30_000L })
    }

    @Test
    fun backoffResetStartsFromBaseAgain() {
        val backoff = ReconnectBackoff(baseMs = 1_000L, random = { 0.0 })
        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    // ------------------------------------------------------------- 价格小数位

    @Test
    fun decimalsFollowRawPriceString() {
        assertEquals(2, priceDecimalsOf("65000.50"))
        assertEquals(1, priceDecimalsOf("0.1"))
        assertEquals(6, priceDecimalsOf("0.000123"))
        assertEquals(0, priceDecimalsOf("12345"))
        assertEquals(0, priceDecimalsOf("123."))
        assertEquals(2, priceDecimalsOf(null))
        assertEquals(2, priceDecimalsOf("  "))
    }

    // ------------------------------------------------------- 全局桶是单例

    @Test
    fun globalOkxLimiterIsShared() {
        assertSame(
            com.milolab.btctv.data.hub.RateLimiter.okxGlobal,
            com.milolab.btctv.data.hub.RateLimiter.okxGlobal
        )
    }
}
