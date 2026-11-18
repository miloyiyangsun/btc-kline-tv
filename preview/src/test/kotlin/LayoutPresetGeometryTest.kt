import com.milolab.btctv.data.TF
import com.milolab.btctv.ui.board.layout.LayoutGeometry
import com.milolab.btctv.ui.board.layout.LayoutGeometryConfig
import com.milolab.btctv.ui.board.layout.LayoutNode
import com.milolab.btctv.ui.board.layout.LayoutPresets
import com.milolab.btctv.ui.board.layout.LayoutRect
import com.milolab.btctv.ui.board.layout.ModuleType
import com.milolab.btctv.ui.board.layout.Slot
import com.milolab.btctv.ui.board.layout.SlotBinding
import com.milolab.btctv.ui.board.layout.SlotParamKeys
import com.milolab.btctv.ui.board.layout.SplitDirection
import com.milolab.btctv.ui.board.layout.StatKind
import com.milolab.btctv.ui.board.layout.depth
import com.milolab.btctv.ui.board.layout.leaves
import com.milolab.btctv.ui.board.layout.leafSlotIds
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LayoutPresetGeometryTest {

    private val eps = 0.01f

    private val expectedLeaves = mapOf(
        "classic" to 1,
        "chart_macro" to 6,
        "chart_watchlist" to 2,
        "split_1_1" to 2,
        "split_1_2" to 2,
        "split_2_1" to 2,
        "split_3" to 3,
        "grid_4_charts" to 4,
        "grid_4_cards" to 4,
        "multi_tf" to 4,
        "grid_6" to 6,
        "grid_9" to 9,
        "overview" to 8,
        "chart_ticker" to 2,
        "pro_3col" to 7
    )

    @Test
    fun allPresetsAreRegisteredAndClassicIsFirst() {
        assertEquals(expectedLeaves.keys.toList(), LayoutPresets.all.map { it.id })
        assertEquals("classic", LayoutPresets.all.first().id)
        for ((id, count) in expectedLeaves) {
            val spec = LayoutPresets.byId(id)
            assertTrue(spec != null, "missing preset $id")
            assertEquals(spec.id, id)
            assertEquals(count, spec.root.leaves().size, "$id leaf count")
            assertEquals(count, spec.slots.size, "$id slot count")
            assertEquals(count, spec.root.leafSlotIds().distinct().size, "$id duplicate slot ids")
            assertTrue(spec.slots.keys.containsAll(spec.root.leafSlotIds()), "$id missing slot")
        }
        assertTrue(LayoutPresets.byId("nope") == null)
    }

    @Test
    fun rootWeightsMatchDesign() {
        val expected = mapOf(
            "chart_macro" to listOf(2f, 1f),
            "chart_watchlist" to listOf(113f, 42f),
            "split_1_1" to listOf(1f, 1f),
            "split_1_2" to listOf(1f, 2f),
            "split_2_1" to listOf(2f, 1f),
            "split_3" to listOf(2f, 1f),
            "grid_4_charts" to listOf(1f, 1f),
            "grid_4_cards" to listOf(1f, 1f),
            "multi_tf" to listOf(1f, 1f),
            "grid_6" to listOf(1f, 1f),
            "grid_9" to listOf(1f, 1f, 1f),
            "overview" to listOf(10f, 50f, 3f),
            "chart_ticker" to listOf(81f, 4f),
            "pro_3col" to listOf(30f, 82f, 42f)
        )
        assertIs<LayoutNode.Leaf>(LayoutPresets.classic().root)
        for ((id, weights) in expected) {
            val root = LayoutPresets.byId(id)!!.root
            assertIs<LayoutNode.Split>(root, id)
            assertEquals(weights, root.weights, "$id root weights")
            assertEquals(root.children.size, root.weights.size, "$id weight count")
            assertTrue(root.weights.all { it.isFinite() && it > 0f }, "$id bad weight")
        }
    }

    @Test
    fun allPresetTreesAreStructurallyValid() {
        for (spec in LayoutPresets.all) {
            assertAtMost(spec.root.depth(), 9, "${spec.id} depth")
            forEachSplit(spec.root) { split, path ->
                assertTrue(split.children.size in 2..4, "${spec.id} $path children")
                assertEquals(split.children.size, split.weights.size, "${spec.id} $path weights")
                assertTrue(split.weights.all { it.isFinite() && it > 0f }, "${spec.id} $path weight value")
            }
        }
    }

    @Test
    fun presetDefaultsMatchDesign() {
        val macro = LayoutPresets.byId("chart_macro")!!
        assertEquals(ModuleType.MainChart, macro.slot("main")!!.module)
        assertEquals(SlotBinding.FollowMain, macro.slot("main")!!.binding)
        assertEquals("FULL", macro.slot("main")!!.params[SlotParamKeys.DETAIL])
        val stats = listOf("btcd", "total", "stable_mcap", "stable_dom")
        assertEquals(
            listOf(StatKind.BTCD, StatKind.TOTAL, StatKind.STABLE_CAP, StatKind.STABLE_DOM),
            stats.map { (macro.slot(it)!!.module as ModuleType.StatTile).stat }
        )
        assertEquals("GAINERS", macro.slot("movers")!!.params[SlotParamKeys.METRIC])
        assertEquals("SPOT_USDT", macro.slot("movers")!!.params[SlotParamKeys.SCOPE])
        assertEquals("5", macro.slot("movers")!!.params[SlotParamKeys.LIMIT])

        assertEquals(
            SlotBinding.Fixed("ETHUSDT"),
            LayoutPresets.byId("split_1_2")!!.slot("left")!!.binding
        )
        assertEquals(
            "COMPACT",
            LayoutPresets.byId("split_1_2")!!.slot("left")!!.params[SlotParamKeys.DETAIL]
        )
        assertEquals(
            "FULL",
            LayoutPresets.byId("split_1_2")!!.slot("right")!!.params[SlotParamKeys.DETAIL]
        )

        val multi = LayoutPresets.byId("multi_tf")!!
        assertEquals(72f, multi.topChrome)
        assertEquals(listOf(TF.M1, TF.H1, TF.D1, TF.W1), listOf("m1", "h1", "d1", "w1").map { multi.slot(it)!!.timeframe })
        assertTrue(listOf("m1", "h1", "d1", "w1").all { multi.slot(it)!!.binding == SlotBinding.FollowMain })

        val wall = LayoutPresets.byId("grid_9")!!
        assertTrue(wall.slots.values.all { it.module == ModuleType.PriceCard })
        assertTrue(wall.slots.values.all { it.binding is SlotBinding.Fixed })

        val overview = LayoutPresets.byId("overview")!!
        assertEquals("GAINERS", overview.slot("gainers")!!.params[SlotParamKeys.METRIC])
        assertEquals("LOSERS", overview.slot("losers")!!.params[SlotParamKeys.METRIC])
        assertEquals(ModuleType.TickerStrip, overview.slot("ticker")!!.module)

        val pro = LayoutPresets.byId("pro_3col")!!
        assertEquals(ModuleType.Watchlist, pro.slot("watchlist")!!.module)
        assertEquals(ModuleType.MainChart, pro.slot("main")!!.module)
        assertTrue(pro.slot("movers") != null)
    }

    @Test
    fun geometryAt1080pFillsContentWithoutOverlap() {
        for (spec in LayoutPresets.all) {
            val rects = LayoutGeometry.solve(spec, 1920f, 1080f)
            assertEquals(spec.slots.keys, rects.keys, "${spec.id} slots")
            assertNoOverlap(spec.id, rects)
            assertInsideContent(spec, rects)

            val left = rects.values.minOf { it.x }
            val top = rects.values.minOf { it.y }
            val right = rects.values.maxOf { it.right }
            val bottom = rects.values.maxOf { it.bottom }
            assertEquals(24f, left, eps, "${spec.id} left edge")
            assertEquals(24f + spec.topChrome + if (spec.topChrome > 0f) 12f else 0f, top, eps, "${spec.id} top edge")
            assertEquals(1896f, right, eps, "${spec.id} right edge")
            assertEquals(1056f, bottom, eps, "${spec.id} bottom edge")
        }
    }

    @Test
    fun trickyPresetsMatchDocumentedRects() {
        assertRects(
            "chart_macro",
            mapOf(
                "main" to rect(24f, 24f, 1240f, 1032f),
                "btcd" to rect(1276f, 24f, 304f, 150f),
                "total" to rect(1592f, 24f, 304f, 150f),
                "stable_mcap" to rect(1276f, 186f, 304f, 150f),
                "stable_dom" to rect(1592f, 186f, 304f, 150f),
                "movers" to rect(1276f, 348f, 620f, 708f)
            )
        )
        assertRects(
            "split_3",
            mapOf(
                "main" to rect(24f, 24f, 1240f, 1032f),
                "top_right" to rect(1276f, 24f, 620f, 510f),
                "bottom_right" to rect(1276f, 546f, 620f, 510f)
            )
        )
        assertRects(
            "multi_tf",
            mapOf(
                "m1" to rect(24f, 108f, 930f, 468f),
                "h1" to rect(966f, 108f, 930f, 468f),
                "d1" to rect(24f, 588f, 930f, 468f),
                "w1" to rect(966f, 588f, 930f, 468f)
            )
        )
        assertRects(
            "grid_9",
            mapOf(
                "r1c1" to rect(24f, 24f, 616f, 336f),
                "r1c2" to rect(652f, 24f, 616f, 336f),
                "r1c3" to rect(1280f, 24f, 616f, 336f),
                "r2c1" to rect(24f, 372f, 616f, 336f),
                "r2c2" to rect(652f, 372f, 616f, 336f),
                "r2c3" to rect(1280f, 372f, 616f, 336f),
                "r3c1" to rect(24f, 720f, 616f, 336f),
                "r3c2" to rect(652f, 720f, 616f, 336f),
                "r3c3" to rect(1280f, 720f, 616f, 336f)
            )
        )
        assertRects(
            "overview",
            mapOf(
                "btcd" to rect(24f, 24f, 459f, 160f),
                "total" to rect(495f, 24f, 459f, 160f),
                "stable_mcap" to rect(966f, 24f, 459f, 160f),
                "stable_dom" to rect(1437f, 24f, 459f, 160f),
                "gainers" to rect(24f, 196f, 616f, 800f),
                "losers" to rect(652f, 196f, 616f, 800f),
                "watchlist" to rect(1280f, 196f, 616f, 800f),
                "ticker" to rect(24f, 1008f, 1872f, 48f)
            )
        )
        assertRects(
            "chart_ticker",
            mapOf(
                "main" to rect(24f, 24f, 1872f, 972f),
                "ticker" to rect(24f, 1008f, 1872f, 48f)
            )
        )
        assertRects(
            "pro_3col",
            mapOf(
                "watchlist" to rect(24f, 24f, 360f, 1032f),
                "main" to rect(396f, 24f, 984f, 1032f),
                "btcd" to rect(1392f, 24f, 246f, 150f),
                "total" to rect(1650f, 24f, 246f, 150f),
                "stable_mcap" to rect(1392f, 186f, 246f, 150f),
                "stable_dom" to rect(1650f, 186f, 246f, 150f),
                "movers" to rect(1392f, 348f, 504f, 708f)
            )
        )
    }

    @Test
    fun seventyTwentyAnd4kAreProportional() {
        val scale720 = 2f / 3f
        for (spec in LayoutPresets.all) {
            val base = LayoutGeometry.solve(spec, 1920f, 1080f)
            val hd = LayoutGeometry.solve(spec, 1280f, 720f)
            val uhd = LayoutGeometry.solve(spec, 3840f, 2160f)
            for ((id, r) in base) {
                assertRect(scale(r, scale720), hd.getValue(id), "${spec.id}/$id 720p")
                assertRect(scale(r, 2f), uhd.getValue(id), "${spec.id}/$id 4k")
            }
        }
    }

    @Test
    fun minimumCellSizeIsProtected() {
        val spec = com.milolab.btctv.ui.board.layout.LayoutSpec(
            id = "custom",
            name = "",
            root = LayoutNode.Split(
                SplitDirection.Horizontal,
                listOf(1f, 100f),
                listOf(LayoutNode.Leaf("a"), LayoutNode.Leaf("b"))
            ),
            slots = mapOf(
                "a" to Slot("a", ModuleType.PriceCard, SlotBinding.Fixed("BTCUSDT")),
                "b" to Slot("b", ModuleType.PriceCard, SlotBinding.Fixed("ETHUSDT"))
            )
        )
        val rects = LayoutGeometry.solve(spec, 1920f, 1080f)
        assertTrue(rects.getValue("a").width >= 48f - eps, "a=${rects.getValue("a")}")
        assertTrue(rects.getValue("b").width >= 48f - eps, "b=${rects.getValue("b")}")
        assertEquals(rects.getValue("a").right + 12f, rects.getValue("b").x, eps)
        assertEquals(1896f, rects.getValue("b").right, eps)
    }

    @Test
    fun degenerateBoundsDoNotProduceNegativeRects() {
        for (spec in LayoutPresets.all) {
            val rects = LayoutGeometry.solve(spec, 40f, 20f)
            for ((id, r) in rects) {
                assertTrue(r.width >= 0f && r.height >= 0f, "${spec.id}/$id negative size")
                assertTrue(r.x.isFinite() && r.y.isFinite(), "${spec.id}/$id non-finite")
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun rect(x: Float, y: Float, w: Float, h: Float) = LayoutRect(x, y, w, h)

    private fun scale(r: LayoutRect, factor: Float) =
        LayoutRect(r.x * factor, r.y * factor, r.width * factor, r.height * factor)

    private fun assertRects(id: String, expected: Map<String, LayoutRect>) {
        val actual = LayoutGeometry.solve(LayoutPresets.byId(id)!!, 1920f, 1080f)
        assertEquals(expected.keys, actual.keys, "$id slot ids")
        for ((slot, want) in expected) assertRect(want, actual.getValue(slot), "$id/$slot")
    }

    private fun assertRect(want: LayoutRect, got: LayoutRect, label: String) {
        assertEquals(want.x, got.x, eps, "$label x")
        assertEquals(want.y, got.y, eps, "$label y")
        assertEquals(want.width, got.width, eps, "$label w")
        assertEquals(want.height, got.height, eps, "$label h")
    }

    private fun assertNoOverlap(id: String, rects: Map<String, LayoutRect>) {
        val items = rects.entries.toList()
        for (i in items.indices) {
            for (j in i + 1 until items.size) {
                assertTrue(
                    !items[i].value.overlaps(items[j].value),
                    "$id overlap ${items[i].key} ${items[i].value} / ${items[j].key} ${items[j].value}"
                )
            }
        }
    }

    private fun assertInsideContent(spec: com.milolab.btctv.ui.board.layout.LayoutSpec, rects: Map<String, LayoutRect>) {
        val top = 24f + spec.topChrome + if (spec.topChrome > 0f) 12f else 0f
        for ((id, r) in rects) {
            assertTrue(r.x >= 24f - eps, "${spec.id}/$id left ${r.x}")
            assertTrue(r.y >= top - eps, "${spec.id}/$id top ${r.y}")
            assertTrue(r.right <= 1896f + eps, "${spec.id}/$id right ${r.right}")
            assertTrue(r.bottom <= 1056f + eps, "${spec.id}/$id bottom ${r.bottom}")
        }
    }

    private fun assertAtMost(value: Int, max: Int, label: String) =
        assertTrue(value <= max, "$label: $value > $max")

    private fun forEachSplit(node: LayoutNode, path: String = "root", action: (LayoutNode.Split, String) -> Unit) {
        if (node is LayoutNode.Split) {
            action(node, path)
            node.children.forEachIndexed { i, child -> forEachSplit(child, "$path.$i", action) }
        }
    }
}
