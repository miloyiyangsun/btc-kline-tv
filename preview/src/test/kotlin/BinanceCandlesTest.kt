import com.milolab.btctv.data.TF
import com.milolab.btctv.data.toCandles
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinanceCandlesTest {

    @Test
    fun numericAndStringElementsAreBothAccepted() {
        // Binance 的 K 线：时间戳是数字，OHLCV 是字符串；这里故意混着放
        val raw = JSONArray(
            """
            [
              [1700000000000, "100.5", "110.0", "90.0", "105.0", "12.5"],
              [1700000060000, 101, 111, 91, 106, 20],
              ["1700000120000", "106", "112", "100", "108", "7.25"]
            ]
            """.trimIndent()
        )
        val candles = raw.toCandles()
        assertEquals(3, candles.size)
        assertEquals(1_700_000_000_000L, candles[0].t)
        assertEquals(100.5, candles[0].o)
        assertEquals(110.0, candles[0].h)
        assertEquals(90.0, candles[0].l)
        assertEquals(105.0, candles[0].c)
        assertEquals(12.5, candles[0].v)
        assertEquals(1_700_000_060_000L, candles[1].t)
        assertEquals(106.0, candles[1].c)
        assertEquals(1_700_000_120_000L, candles[2].t)
        assertEquals(7.25, candles[2].v)
    }

    @Test
    fun allStringRowsParseWithoutDroppingCandles() {
        val raw = JSONArray(
            """
            [
              ["1700000000000", "1.1", "2.2", "0.9", "2.0", "10"],
              ["1700000060000", "2.0", "3.0", "1.5", "2.5", "20"]
            ]
            """.trimIndent()
        )
        val candles = raw.toCandles()
        assertEquals(2, candles.size)
        assertEquals(2.0, candles.last().o)
        assertEquals(3.0, candles.last().h)
        assertEquals(1.5, candles.last().l)
        assertEquals(2.5, candles.last().c)
        assertEquals(20.0, candles.last().v)
    }

    @Test
    fun malformedRowsAreSkippedInsteadOfKillingTheWholePage() {
        val raw = JSONArray(
            """
            [
              [1700000000000, "1", "2", "0.5", "1.5", "10"],
              null,
              [],
              [1700000060000, "bad", "2", "0.5", "1.5", "10"],
              [1700000120000, "1", "2", "0.5", "1.5"],
              [1700000180000, "1", "2", "0.5", "1.5", "11"]
            ]
            """.trimIndent()
        )
        val candles = raw.toCandles()
        assertEquals(2, candles.size)
        assertEquals(1_700_000_000_000L, candles[0].t)
        assertEquals(1_700_000_180_000L, candles[1].t)
    }

    @Test
    fun emptyArrayProducesNoCandles() {
        assertTrue(JSONArray("[]").toCandles().isEmpty())
    }

    @Test
    fun binanceTimeframeMappingCoversRequestedPeriods() {
        assertEquals("30m", TF.M30.binance)
        assertEquals("12h", TF.H12.binance)
        assertEquals("1w", TF.W1.binance)
        assertEquals("1M", TF.MO1.binance)
        // 年线没有原生周期，走月线聚合
        assertEquals(TF.MO1, TF.Y1.aggregateFrom)
        assertTrue(TF.Y1.aggregated)
    }

    @Test
    fun okxAndBybitMappingsStayDistinctFromBinance() {
        assertEquals("12Hutc", TF.H12.okx)
        assertEquals("720", TF.H12.bybit)
        assertEquals("D", TF.D1.bybit)
        assertEquals("W", TF.W1.bybit)
        assertEquals("M", TF.MO1.bybit)
    }
}
