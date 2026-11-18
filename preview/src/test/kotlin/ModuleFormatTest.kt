import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.StockKind
import com.milolab.btctv.ui.board.modules.PriceCardTier
import com.milolab.btctv.ui.board.modules.StatKind
import com.milolab.btctv.ui.board.modules.StatTileTier
import com.milolab.btctv.ui.board.modules.TickerSpeed
import com.milolab.btctv.ui.board.modules.antiBurnShiftDp
import com.milolab.btctv.ui.board.modules.chartHeaderScale
import com.milolab.btctv.ui.board.modules.fmtPctSigned
import com.milolab.btctv.ui.board.modules.fmtStatValue
import com.milolab.btctv.ui.board.modules.fmtUsdCompact
import com.milolab.btctv.ui.board.modules.listRowFontSp
import com.milolab.btctv.ui.board.modules.marketBadge
import com.milolab.btctv.ui.board.modules.priceCardTier
import com.milolab.btctv.ui.board.modules.statTileTier
import com.milolab.btctv.ui.board.modules.staleLabel
import com.milolab.btctv.ui.board.modules.tickerFontSp
import com.milolab.btctv.ui.board.modules.tickerOffsetPx
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleFormatTest {

    @Test
    fun usdCompact_usesMarketCapUnits() {
        assertEquals("—", fmtUsdCompact(null))
        assertEquals("—", fmtUsdCompact(Double.NaN))
        assertEquals("$2.23T", fmtUsdCompact(2.2345e12))
        assertEquals("$292.3B", fmtUsdCompact(292.3e9))
        assertEquals("$986.4B", fmtUsdCompact(986.4e9))
        assertEquals("$12.30M", fmtUsdCompact(12.3e6))
        assertEquals("$1.25K", fmtUsdCompact(1_250.0))
        assertEquals("$999", fmtUsdCompact(999.0))
        assertEquals("$99.50", fmtUsdCompact(99.5))
        assertEquals("$0.00", fmtUsdCompact(0.0))
    }

    @Test
    fun statValue_formatsByKind() {
        assertEquals("58.44%", fmtStatValue(StatKind.BTCD, 58.441989))
        assertEquals("7.85%", fmtStatValue(StatKind.STABLE_DOM, 7.8535))
        assertEquals("$2.23T", fmtStatValue(StatKind.TOTAL, 2.2345e12))
        assertEquals("$292.3B", fmtStatValue(StatKind.STABLE_MCAP, 292.3e9))
        assertEquals("—", fmtStatValue(StatKind.BTCD, null))
    }

    @Test
    fun signedPercent_hasExplicitSign() {
        assertEquals("+2.34%", fmtPctSigned(2.341))
        assertEquals("-0.80%", fmtPctSigned(-0.8))
        assertEquals("+0.00%", fmtPctSigned(0.0))
        assertEquals("—", fmtPctSigned(null))
        assertEquals("+2.3%", fmtPctSigned(2.34, decimals = 1))
    }

    @Test
    fun staleLabel_switchesUnits() {
        assertEquals("已过期", staleLabel(null))
        assertEquals("已过期", staleLabel(-1L))
        assertEquals("刚刚更新", staleLabel(0L))
        assertEquals("更新于 3 分钟前", staleLabel(3L))
        assertEquals("更新于 59 分钟前", staleLabel(59L))
        assertEquals("更新于 2 小时前", staleLabel(125L))
        assertEquals("更新于 2 天前", staleLabel(49L * 60L))
    }

    @Test
    fun marketBadge_coversAllKinds() {
        assertEquals("加密", marketBadge(Instruments.BTC))
        val stock = Instruments.stock("AAPL", "Apple Inc.", 2, kind = StockKind.PERP)
        assertEquals("永续", marketBadge(stock))
        assertEquals("代币现货", marketBadge(stock.copy(stockKind = StockKind.TOKENIZED)))
        assertEquals("美股", marketBadge(stock.copy(stockKind = null)))
    }

    @Test
    fun priceCardTier_boundaries() {
        assertEquals(PriceCardTier.LARGE, priceCardTier(420f, 300f))
        assertEquals(PriceCardTier.MEDIUM, priceCardTier(419f, 299f))
        assertEquals(PriceCardTier.MEDIUM, priceCardTier(240f, 190f))
        assertEquals(PriceCardTier.SMALL, priceCardTier(239f, 189f))
        assertEquals(PriceCardTier.MEDIUM, priceCardTier(600f, 299f))
    }

    @Test
    fun statTileTier_boundaries() {
        assertEquals(StatTileTier.LARGE, statTileTier(220f, 130f))
        assertEquals(StatTileTier.MEDIUM, statTileTier(219f, 130f))
        assertEquals(StatTileTier.MEDIUM, statTileTier(120f, 68f))
        assertEquals(StatTileTier.SMALL, statTileTier(119f, 67f))
    }

    @Test
    fun headerAndFontScales_areBounded() {
        assertEquals(1f, chartHeaderScale(640f, 300f), 1e-4f)
        assertEquals(0.68f, chartHeaderScale(100f, 100f), 1e-4f)
        assertEquals(1.55f, chartHeaderScale(4_000f, 2_000f), 1e-4f)

        assertEquals(10f, listRowFontSp(20f), 1e-4f)
        assertEquals(16f, listRowFontSp(40f), 1e-4f)
        assertEquals(17f, listRowFontSp(100f), 1e-4f)

        assertEquals(9f, tickerFontSp(20f), 1e-4f)
        assertEquals(13.2f, tickerFontSp(30f), 1e-4f)
        assertEquals(16f, tickerFontSp(100f), 1e-4f)
    }

    @Test
    fun tickerOffset_isUniformAndWraps() {
        assertEquals(0f, tickerOffsetPx(0L, TickerSpeed.NORMAL, 2f, 10_000f), 1e-4f)
        assertEquals(92f, tickerOffsetPx(1_000L, TickerSpeed.NORMAL, 2f, 10_000f), 1e-4f)
        assertEquals(480f, tickerOffsetPx(20_000L, TickerSpeed.FAST, 1f, 1_000f), 1e-4f)
        assertEquals(0f, tickerOffsetPx(1_000L, TickerSpeed.NORMAL, 2f, 0f), 1e-4f)
        assertEquals(0f, tickerOffsetPx(-5_000L, TickerSpeed.NORMAL, 2f, 10_000f), 1e-4f)
    }

    @Test
    fun antiBurnShift_staysInsideOnePointTwoDp() {
        assertEquals(0f, antiBurnShiftDp(0f, 1_000f), 1e-4f)
        assertEquals(1.2f, antiBurnShiftDp(250f, 1_000f), 1e-4f)
        assertEquals(0f, antiBurnShiftDp(500f, 1_000f), 1e-4f)
        assertEquals(0f, antiBurnShiftDp(250f, 0f), 1e-4f)
    }
}
