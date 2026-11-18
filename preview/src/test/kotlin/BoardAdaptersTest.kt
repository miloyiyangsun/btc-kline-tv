import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.TF
import com.milolab.btctv.ui.board.defaultInstrumentLookup
import com.milolab.btctv.ui.board.layout.LayoutPresets
import com.milolab.btctv.ui.board.layout.ModuleType
import com.milolab.btctv.ui.board.layout.SlotBinding
import com.milolab.btctv.ui.board.layout.StatKind
import com.milolab.btctv.ui.board.layout.leafSlotIds
import com.milolab.btctv.ui.board.planSlots
import com.milolab.btctv.ui.board.primaryMainSlotId
import com.milolab.btctv.ui.board.resolveSlotInstrument
import com.milolab.btctv.ui.board.statKindToModule
import com.milolab.btctv.ui.board.subscriptionInstruments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardAdaptersTest {

    private val lookup = defaultInstrumentLookup(emptyList(), emptyList(), emptyList())

    @Test
    fun statKindsMapAcrossTheTwoEnums() {
        assertEquals(
            com.milolab.btctv.ui.board.modules.StatKind.BTCD,
            statKindToModule(StatKind.BTCD)
        )
        assertEquals(
            com.milolab.btctv.ui.board.modules.StatKind.TOTAL,
            statKindToModule(StatKind.TOTAL)
        )
        assertEquals(
            com.milolab.btctv.ui.board.modules.StatKind.STABLE_MCAP,
            statKindToModule(StatKind.STABLE_CAP)
        )
        assertEquals(
            com.milolab.btctv.ui.board.modules.StatKind.STABLE_DOM,
            statKindToModule(StatKind.STABLE_DOM)
        )
        assertNotEquals(
            com.milolab.btctv.ui.board.modules.StatKind.STABLE_DOM.name,
            statKindToModule(StatKind.STABLE_CAP).name
        )
    }

    @Test
    fun primaryMainSlotOnlyMatchesFollowMainFullChart() {
        assertEquals("main", primaryMainSlotId(LayoutPresets.classic()))
        assertEquals("main", primaryMainSlotId(LayoutPresets.byId("chart_macro")!!))
        assertEquals("main", primaryMainSlotId(LayoutPresets.byId("chart_watchlist")!!))
        assertEquals("left", primaryMainSlotId(LayoutPresets.byId("split_1_1")!!))
        assertEquals("main", primaryMainSlotId(LayoutPresets.byId("pro_3col")!!))
        assertNull(primaryMainSlotId(LayoutPresets.byId("grid_4_charts")!!))
        assertNull(primaryMainSlotId(LayoutPresets.byId("multi_tf")!!))

        for (spec in LayoutPresets.all) {
            val id = primaryMainSlotId(spec) ?: continue
            val slot = spec.slots.getValue(id)
            assertEquals(ModuleType.MainChart, slot.module, "${spec.id}/$id")
            assertEquals(SlotBinding.FollowMain, slot.binding, "${spec.id}/$id")
            assertNull(slot.timeframe, "${spec.id}/$id")
        }
    }

    @Test
    fun fixedBindingFallsBackToMainWhenUnknown() {
        assertEquals(
            Instruments.BTC,
            resolveSlotInstrument(SlotBinding.FollowMain, Instruments.BTC, lookup)
        )
        assertEquals(
            Instruments.ETH,
            resolveSlotInstrument(SlotBinding.Fixed(Instruments.ETH.id), Instruments.BTC, lookup)
        )
        assertEquals(
            Instruments.BTC,
            resolveSlotInstrument(SlotBinding.Fixed("NOPE"), Instruments.BTC, lookup)
        )
    }

    @Test
    fun plansCarryTimeframesAndNeeds() {
        val spec = LayoutPresets.byId("multi_tf")!!
        val plans = planSlots(spec, Instruments.BTC, TF.M15, lookup, emptyList(), Instruments.HOT)
        assertEquals(TF.M1, plans.getValue("m1").tf)
        assertEquals(TF.H1, plans.getValue("h1").tf)
        assertEquals(TF.D1, plans.getValue("d1").tf)
        assertEquals(TF.W1, plans.getValue("w1").tf)
        assertTrue(plans.values.all { it.needsCandles && it.needsQuote })

        val macro = planSlots(
            LayoutPresets.byId("chart_macro")!!,
            Instruments.BTC,
            TF.M15,
            lookup,
            emptyList(),
            Instruments.HOT
        )
        assertEquals(TF.M15, macro.getValue("main").tf)
        assertTrue(macro.getValue("main").needsCandles)
        assertTrue(!macro.getValue("btcd").needsQuote)
        assertTrue(!macro.getValue("btcd").needsCandles)
    }

    @Test
    fun subscriptionsStartWithMainAndIncludeFixedSlots() {
        val spec = LayoutPresets.byId("grid_4_charts")!!
        val plans = planSlots(spec, Instruments.BTC, TF.M15, lookup, emptyList(), Instruments.HOT)
        val subs = subscriptionInstruments(spec, plans, Instruments.BTC, emptyList(), Instruments.HOT)
        assertEquals(Instruments.BTC, subs.first())
        assertEquals(4, subs.size)
        assertTrue(subs.containsAll(listOf(Instruments.ETH, Instruments.SOL, Instruments.BNB)))
    }

    @Test
    fun watchlistAndTickerAddTheirPoolsButStayBounded() {
        val spec = LayoutPresets.byId("overview")!!
        val plans = planSlots(spec, Instruments.BTC, TF.M15, lookup, emptyList(), Instruments.HOT)
        val subs = subscriptionInstruments(spec, plans, Instruments.BTC, emptyList(), Instruments.HOT)
        assertEquals(Instruments.BTC, subs.first())
        assertTrue(subs.size <= 12)
        assertTrue(subs.contains(Instruments.DOGE))

        val favorites = listOf(Instruments.TON)
        val withFav = subscriptionInstruments(spec, plans, Instruments.BTC, favorites, Instruments.HOT)
        assertTrue(withFav.any { it.id == Instruments.TON.id })
        assertTrue(subs.map { it.id }.toSet().size == subs.size)
    }

    @Test
    fun classicSubscriptionIsJustTheMainInstrument() {
        val spec = LayoutPresets.classic()
        val plans = planSlots(spec, Instruments.ETH, TF.H1, lookup, emptyList(), Instruments.HOT)
        val subs = subscriptionInstruments(spec, plans, Instruments.ETH, emptyList(), Instruments.HOT)
        assertEquals(listOf(Instruments.ETH), subs)
        assertEquals(spec.root.leafSlotIds(), listOf("main"))
    }
}
