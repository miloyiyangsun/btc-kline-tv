import com.milolab.btctv.data.TF
import com.milolab.btctv.ui.board.layout.LayoutJson
import com.milolab.btctv.ui.board.layout.LayoutPresets
import com.milolab.btctv.ui.board.layout.ModuleType
import com.milolab.btctv.ui.board.layout.SlotBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutJsonTest {

    @Test
    fun allPresetsRoundTrip() {
        for (spec in LayoutPresets.all) {
            val encoded = LayoutJson.encode(spec)
            assertTrue(encoded.contains("\"v\":1"), "${spec.id} missing version")
            val decoded = LayoutJson.decode(encoded)
            assertEquals(spec, decoded, "${spec.id} round trip")
        }
    }

    @Test
    fun editedSpecsRoundTrip() {
        val edited = assertNotNull(
            com.milolab.btctv.ui.board.layout.LayoutEdit.splitLeaf(
                LayoutPresets.byId("grid_4_charts")!!,
                "tl",
                com.milolab.btctv.ui.board.layout.SplitDirection.Vertical,
                com.milolab.btctv.ui.board.layout.SplitRatio.OneToOneOne
            )
        )
        assertEquals(edited, LayoutJson.decode(LayoutJson.encode(edited)))
    }

    @Test
    fun corruptOrUnknownInputFallsBackToClassic() {
        val classic = LayoutPresets.classic()
        val corrupt = listOf(
            null,
            "",
            "   ",
            "not json",
            "{}",
            "[]",
            """{"v":2,"id":"x","root":{"type":"leaf","slotId":"a"},"slots":[]}""",
            """{"v":1,"id":"x","root":{"type":"leaf","slotId":"a"},"slots":[{"id":"a","module":"WAT"}]}""",
            """{"v":1,"id":"x","root":{"type":"leaf","slotId":"b"},"slots":[{"id":"a","module":"MAIN_CHART"}]}""",
            """{"v":1,"id":"x","root":{"type":"split","dir":"H","weights":[1],"children":[{"type":"leaf","slotId":"a"},{"type":"leaf","slotId":"b"}]},"slots":[{"id":"a","module":"MAIN_CHART"},{"id":"b","module":"MAIN_CHART"}]}""",
            """{"v":1,"id":"x","root":{"type":"split","dir":"Z","weights":[1,1],"children":[{"type":"leaf","slotId":"a"},{"type":"leaf","slotId":"b"}]},"slots":[{"id":"a","module":"MAIN_CHART"},{"id":"b","module":"MAIN_CHART"}]}""",
            """{"v":1,"id":"x","root":{"type":"wat","slotId":"a"},"slots":[{"id":"a","module":"MAIN_CHART"}]}"""
        )
        for (raw in corrupt) {
            assertEquals(classic, LayoutJson.decode(raw), "fallback for: $raw")
            assertNull(LayoutJson.decodeOrNull(raw), "decodeOrNull for: $raw")
        }
    }

    @Test
    fun missingDefaultsAreTolerated() {
        val raw = """
            {
              "v": 1,
              "root": {"type":"leaf","slotId":"a"},
              "slots": [{"id":"a","module":"MINI_CHART"}]
            }
        """.trimIndent()
        val spec = LayoutJson.decode(raw)
        assertEquals("custom", spec.id)
        assertEquals("", spec.name)
        assertEquals(0f, spec.topChrome)
        assertEquals(SlotBinding.FollowMain, spec.slot("a")!!.binding)
        assertNull(spec.slot("a")!!.timeframe)
        assertEquals(emptyMap(), spec.slot("a")!!.params)
    }

    @Test
    fun missingWeightsBecomeEqualAndInvalidWeightsBecomeOne() {
        val raw = """
            {
              "v": 1,
              "id": "x",
              "root": {"type":"split","dir":"V","children":[
                {"type":"leaf","slotId":"a"},
                {"type":"leaf","slotId":"b"}
              ]},
              "slots": [{"id":"a","module":"MAIN_CHART"},{"id":"b","module":"MAIN_CHART"}]
            }
        """.trimIndent()
        val spec = LayoutJson.decode(raw)
        val root = spec.root as com.milolab.btctv.ui.board.layout.LayoutNode.Split
        assertEquals(listOf(1f, 1f), root.weights)

        val invalid = raw.replace("\"dir\":\"V\"", "\"dir\":\"V\",\"weights\":[0, -3]")
        val fixed = LayoutJson.decode(invalid)
        val fixedRoot = fixed.root as com.milolab.btctv.ui.board.layout.LayoutNode.Split
        assertEquals(listOf(1f, 1f), fixedRoot.weights)
    }

    @Test
    fun unknownTimeframeFallsBackToFollowAndBlankFixedFallsBackToFollow() {
        val raw = """
            {
              "v": 1,
              "id": "x",
              "root": {"type":"leaf","slotId":"a"},
              "slots": [{
                "id":"a","module":"MAIN_CHART",
                "binding":{"kind":"fixed","instrumentId":"  "},
                "tf":"NOPE"
              }]
            }
        """.trimIndent()
        val spec = LayoutJson.decode(raw)
        assertTrue(spec.id == "x")
        assertNull(spec.slot("a")!!.timeframe)
        assertEquals(SlotBinding.FollowMain, spec.slot("a")!!.binding)
    }

    @Test
    fun statTileStatAndParamsSurviveJson() {
        val spec = LayoutPresets.byId("chart_macro")!!
        val decoded = LayoutJson.decode(LayoutJson.encode(spec))
        assertEquals(
            ModuleType.StatTile(com.milolab.btctv.ui.board.layout.StatKind.STABLE_CAP),
            decoded.slot("stable_mcap")!!.module
        )
        assertEquals("SPOT_USDT", decoded.slot("movers")!!.params["scope"])
        assertEquals("5", decoded.slot("movers")!!.params["limit"])
        assertEquals(TF.M1, LayoutPresets.byId("multi_tf")!!.slot("m1")!!.timeframe)
        assertEquals(TF.W1, LayoutJson.decode(LayoutJson.encode(LayoutPresets.byId("multi_tf")!!)).slot("w1")!!.timeframe)
    }

    @Test
    fun duplicateSlotIdsKeepFirst() {
        val raw = """
            {
              "v": 1,
              "id": "x",
              "root": {"type":"leaf","slotId":"a"},
              "slots": [
                {"id":"a","module":"MAIN_CHART"},
                {"id":"a","module":"MINI_CHART"}
              ]
            }
        """.trimIndent()
        val spec = LayoutJson.decode(raw)
        assertEquals(1, spec.slots.size)
        assertEquals(ModuleType.MainChart, spec.slot("a")!!.module)
    }
}
