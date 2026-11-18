import com.milolab.btctv.data.hub.RateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RateLimiterTest {

    private class Clock(var now: Long = 0L)

    @Test
    fun burstIsSpendableImmediately() {
        val clock = Clock()
        val limiter = RateLimiter(ratePerSecond = 10.0, burst = 5, nowMs = { clock.now })
        repeat(5) { assertEquals(0L, limiter.tryAcquire()) }
        assertTrue(limiter.available() < 1e-6)
    }

    @Test
    fun emptyBucketReportsWaitTimeWithoutConsuming() {
        val clock = Clock()
        val limiter = RateLimiter(ratePerSecond = 10.0, burst = 5, nowMs = { clock.now })
        repeat(5) { limiter.tryAcquire() }
        // 缺 1 个令牌，10 个/s ⇒ 100ms
        assertEquals(100L, limiter.tryAcquire())
        assertEquals(100L, limiter.tryAcquire())
        clock.now += 99L
        assertEquals(1L, limiter.tryAcquire())
        clock.now += 1L
        assertEquals(0L, limiter.tryAcquire())
    }

    @Test
    fun refillIsCappedAtBurst() {
        val clock = Clock()
        val limiter = RateLimiter(ratePerSecond = 10.0, burst = 5, nowMs = { clock.now })
        repeat(5) { limiter.tryAcquire() }
        clock.now += 60_000L
        assertEquals(5.0, limiter.available(), 1e-9)
        repeat(5) { assertEquals(0L, limiter.tryAcquire()) }
        assertEquals(100L, limiter.tryAcquire())
    }

    @Test
    fun partialTokensProduceRoundedUpWait() {
        val clock = Clock()
        val limiter = RateLimiter(ratePerSecond = 10.0, burst = 5, nowMs = { clock.now })
        repeat(5) { limiter.tryAcquire() }
        clock.now += 50L // +0.5 token
        assertEquals(0.5, limiter.available(), 1e-9)
        // 要 2 个，缺 1.5 ⇒ 150ms
        assertEquals(150L, limiter.tryAcquire(2))
        clock.now += 150L
        assertEquals(0L, limiter.tryAcquire(2))
    }

    @Test
    fun multiTokenRequestLargerThanBurstIsRejected() {
        val limiter = RateLimiter(ratePerSecond = 10.0, burst = 3)
        assertFailsWith<IllegalArgumentException> { limiter.tryAcquire(4) }
    }

    @Test
    fun invalidConfigurationIsRejected() {
        assertFailsWith<IllegalArgumentException> { RateLimiter(ratePerSecond = 0.0, burst = 5) }
        assertFailsWith<IllegalArgumentException> { RateLimiter(ratePerSecond = 10.0, burst = 0) }
    }

    @Test
    fun timeGoingBackwardsDoesNotAddTokens() {
        val clock = Clock(1_000L)
        val limiter = RateLimiter(ratePerSecond = 10.0, burst = 2, nowMs = { clock.now })
        repeat(2) { limiter.tryAcquire() }
        clock.now = 500L
        assertEquals(100L, limiter.tryAcquire())
    }
}
