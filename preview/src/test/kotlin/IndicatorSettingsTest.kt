import com.milolab.btctv.data.IndicatorGroup
import com.milolab.btctv.data.IndicatorSettings
import com.milolab.btctv.data.indicators.IndicatorRegistry
import com.milolab.btctv.data.indicators.IndicatorType
import com.milolab.btctv.data.paramValueLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndicatorSettingsTest {

    private val rsi = IndicatorRegistry.requireById("rsi")
    private val macd = IndicatorRegistry.requireById("macd")
    private val kdj = IndicatorRegistry.requireById("kdj")
    private val atr = IndicatorRegistry.requireById("atr")
    private val boll = IndicatorRegistry.requireById("boll")
    private val vwap = IndicatorRegistry.requireById("vwap")

    /* ------------------------------------------------------------ 默认值 / 旧版升级 */

    @Test
    fun defaultIsAllOff() {
        val s = IndicatorSettings.DEFAULT
        assertTrue(s.enabled.isEmpty())
        assertEquals(0, s.enabledCount)
        assertEquals(0, s.oscillatorCount())
        assertTrue(s.enabledSpecs().isEmpty())
        assertEquals(IndicatorSettings.BASE_BARS, IndicatorSettings.requiredBars(s))
    }

    /** 旧版本根本没有这个 key，Prefs 读出来是 null；必须等价于全新安装。 */
    @Test
    fun oldVersionUpgradeStaysAllOff() {
        assertEquals(IndicatorSettings.DEFAULT, IndicatorSettings.decode(null))
        assertEquals(IndicatorSettings.DEFAULT, IndicatorSettings.decode(""))
        assertEquals(IndicatorSettings.DEFAULT, IndicatorSettings.decode("   "))
        assertEquals(IndicatorSettings.DEFAULT, IndicatorSettings.decode("{}"))
        assertEquals(IndicatorSettings.DEFAULT, IndicatorSettings.decode("""{"v":1}"""))
    }

    @Test
    fun corruptPayloadFallsBackToAllOff() {
        assertEquals(IndicatorSettings.DEFAULT, IndicatorSettings.decode("not json"))
        assertEquals(IndicatorSettings.DEFAULT, IndicatorSettings.decode("""{"enabled":42}"""))
    }

    @Test
    fun unknownAndDuplicateIdsAreDropped() {
        val decoded = IndicatorSettings.decode(
            """{"v":1,"enabled":["ema","nope","ema","rsi"],"params":{}}"""
        )
        assertEquals(listOf("ema", "rsi"), decoded.enabled)
    }

    /* ------------------------------------------------------------ 开关 */

    @Test
    fun toggleAddsAndRemoves() {
        val on = IndicatorSettings.DEFAULT.withToggled("ema")
        assertTrue(on.isEnabled("ema"))
        assertEquals(1, on.enabledCount)

        val off = on.withToggled("ema")
        assertFalse(off.isEnabled("ema"))
        assertEquals(0, off.enabledCount)
    }

    @Test
    fun unknownIdCannotBeEnabled() {
        val s = IndicatorSettings.DEFAULT.withToggled("does_not_exist")
        assertEquals(IndicatorSettings.DEFAULT, s)
    }

    @Test
    fun oscillatorCapIsThree() {
        var s = IndicatorSettings.DEFAULT
        for (spec in listOf(rsi, macd, kdj)) {
            assertTrue(s.canEnable(spec.id))
            s = s.withToggled(spec.id)
        }
        assertEquals(3, s.oscillatorCount())
        assertFalse(s.canEnable(atr.id))

        val refused = s.withToggled(atr.id)
        assertFalse(refused.isEnabled(atr.id))
        assertEquals(s, refused)

        // 关掉一个之后才能换成别的副图
        val swapped = s.withToggled(kdj.id).withToggled(atr.id)
        assertEquals(3, swapped.oscillatorCount())
        assertTrue(swapped.isEnabled(atr.id))
        assertFalse(swapped.isEnabled(kdj.id))
    }

    @Test
    fun decodeAlsoCapsOscillatorsAtThree() {
        val decoded = IndicatorSettings.decode(
            """{"v":1,"enabled":["rsi","macd","kdj","atr","ema"]}"""
        )
        assertEquals(3, decoded.oscillatorCount())
        assertTrue(decoded.isEnabled("ema"))
        assertFalse(decoded.isEnabled("atr"))
    }

    @Test
    fun enabledSpecsFollowRegistryOrderNotTapOrder() {
        val s = IndicatorSettings.DEFAULT
            .withToggled("rsi")
            .withToggled("ema")
            .withToggled("ma")
        assertEquals(listOf("ma", "ema", "rsi"), s.enabledSpecs().map { it.id })
        assertEquals(listOf("rsi"), s.enabledSpecs(IndicatorType.OSCILLATOR).map { it.id })
    }

    @Test
    fun groupsSplitRegressionFromOtherOverlays() {
        val s = IndicatorSettings.DEFAULT
            .withToggled("ema")
            .withToggled("linreg")
            .withToggled("pivot")
            .withToggled("rsi")
        assertEquals(1, s.enabledIn(IndicatorGroup.OVERLAY))
        assertEquals(1, s.enabledIn(IndicatorGroup.REGRESSION))
        assertEquals(1, s.enabledIn(IndicatorGroup.LEVELS))
        assertEquals(1, s.enabledIn(IndicatorGroup.OSCILLATOR))
    }

    /* ------------------------------------------------------------ 参数 */

    @Test
    fun paramOverridesAreClampedAndDefaultsFilled() {
        val s = IndicatorSettings.DEFAULT.withParam("boll", "period", 9_999.0)
        val p = s.paramsFor(boll)
        assertEquals(500.0, p.d("period"))
        assertEquals(2.0, p.d("mult"))
    }

    @Test
    fun numberStepUsesSpecStepAndClamps() {
        // boll.mult: min 0.1, max 10, step 0.1
        val up = IndicatorSettings.DEFAULT.withParamStep("boll", "mult", 1)
        assertEquals(2.1, up.paramsFor(boll).d("mult"), 1e-9)

        val down = up.withParamStep("boll", "mult", -1)
        assertEquals(2.0, down.paramsFor(boll).d("mult"), 1e-9)

        // 从默认值往下很多步也不会越过下限
        val floor = IndicatorSettings.DEFAULT
            .withParamStep("boll", "mult", -1_000)
        assertEquals(0.1, floor.paramsFor(boll).d("mult"), 1e-9)
    }

    @Test
    fun choiceStepCycles() {
        // vwap.anchor: 自动(0) / 全部K线(1) / UTC 日(2)
        val a = IndicatorSettings.DEFAULT.withParamStep("vwap", "anchor", 1)
        assertEquals(1.0, a.paramsFor(vwap).d("anchor"))
        val b = a.withParamStep("vwap", "anchor", 1)
        assertEquals(2.0, b.paramsFor(vwap).d("anchor"))
        val c = b.withParamStep("vwap", "anchor", 1)
        assertEquals(0.0, c.paramsFor(vwap).d("anchor"))
        val back = c.withParamStep("vwap", "anchor", -1)
        assertEquals(2.0, back.paramsFor(vwap).d("anchor"))
    }

    @Test
    fun boolStepToggles() {
        val fib = IndicatorRegistry.requireById("fib")
        val off = IndicatorSettings.DEFAULT.withParam("fib", "extensions", 0.0)
        assertEquals(0.0, off.paramsFor(fib).d("extensions"))
        val on = off.withParamStep("fib", "extensions", 1)
        assertEquals(1.0, on.paramsFor(fib).d("extensions"))
    }

    @Test
    fun unknownParamIsIgnored() {
        val s = IndicatorSettings.DEFAULT.withParam("ema", "nope", 3.0)
        assertTrue(s.params.isEmpty())
    }

    @Test
    fun summaryShowsCurrentValues() {
        val s = IndicatorSettings.DEFAULT.withParam("boll", "period", 30.0)
        val text = s.summaryFor(boll)
        assertTrue(text.contains("周期 30"), text)
        assertTrue(text.contains("标准差倍数 2"), text)
        assertTrue(IndicatorSettings.DEFAULT.summaryFor(IndicatorRegistry.requireById("obv")) == "默认")
    }

    @Test
    fun paramLabelsCoverUnitsChoicesAndBooleans() {
        val period = boll.params.first { it.key == "period" }
        val anchor = vwap.params.first { it.key == "anchor" }
        val extensions = IndicatorRegistry.requireById("fib").params.first { it.key == "extensions" }
        assertEquals("30", paramValueLabel(period, 30.0))
        assertEquals("UTC 日", paramValueLabel(anchor, 2.0))
        assertEquals("开", paramValueLabel(extensions, 1.0))
        assertEquals("关", paramValueLabel(extensions, 0.0))
    }

    /* ------------------------------------------------------------ 全部关闭 / 恢复默认 / 序列化 */

    @Test
    fun allOffKeepsParamsButResetClearsEverything() {
        val s = IndicatorSettings.DEFAULT
            .withToggled("ema")
            .withToggled("rsi")
            .withParam("ema", "period", 55.0)

        val off = s.allOff()
        assertTrue(off.enabled.isEmpty())
        assertEquals(55.0, off.paramsFor("ema")!!.d("period"))

        assertEquals(IndicatorSettings.DEFAULT, s.restoreDefaults())
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val s = IndicatorSettings.DEFAULT
            .withToggled("ema")
            .withToggled("vwap")
            .withToggled("rsi")
            .withParam("ema", "period", 55.0)
            .withParam("vwap", "anchor", 2.0)
            .withParam("rsi", "period", 7.0)

        val back = IndicatorSettings.decode(s.encode())
        assertEquals(s.enabled, back.enabled)
        assertEquals(55.0, back.paramsFor("ema")!!.d("period"))
        assertEquals(2.0, back.paramsFor("vwap")!!.d("anchor"))
        assertEquals(7.0, back.paramsFor("rsi")!!.d("period"))
    }

    @Test
    fun decodeNormalizesOutOfRangeStoredParams() {
        val back = IndicatorSettings.decode(
            """{"v":1,"enabled":["boll"],"params":{"boll":{"period":9999,"mult":-5}}}"""
        )
        val p = back.paramsFor(boll)
        assertEquals(500.0, p.d("period"))
        assertEquals(0.1, p.d("mult"))
    }

    @Test
    fun encodeSkipsUnknownIdsAndNonFiniteValues() {
        val s = IndicatorSettings(
            enabled = listOf("ema", "ghost"),
            params = mapOf(
                "ema" to mapOf("period" to Double.NaN),
                "ghost" to mapOf("x" to 1.0)
            )
        )
        val back = IndicatorSettings.decode(s.encode())
        assertEquals(listOf("ema"), back.enabled)
        assertEquals(20.0, back.paramsFor("ema")!!.d("period"))
    }

    /* ------------------------------------------------------------ 预热根数 */

    @Test
    fun requiredBarsGrowsWithWarmupAndCapsAtFiveHundred() {
        val ma20 = IndicatorSettings.DEFAULT.withToggled("ma")
        assertEquals(
            (20 - 1 + IndicatorSettings.MAX_DISPLAY_BARS).coerceIn(120, 500),
            IndicatorSettings.requiredBars(ma20)
        )

        val ma500 = IndicatorSettings.DEFAULT.withParam("ma", "period", 500.0).withToggled("ma")
        assertEquals(IndicatorSettings.MAX_BARS, IndicatorSettings.requiredBars(ma500))
    }
}
