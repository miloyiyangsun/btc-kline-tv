import com.milolab.btctv.ui.board.layout.FocusDirection
import com.milolab.btctv.ui.board.layout.LayoutFocus
import com.milolab.btctv.ui.board.layout.LayoutGeometry
import com.milolab.btctv.ui.board.layout.LayoutPresets
import com.milolab.btctv.ui.board.layout.LayoutRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutFocusTest {

    private data class N(
        val up: String? = null,
        val down: String? = null,
        val left: String? = null,
        val right: String? = null
    )

    @Test
    fun classicSingleSlotHasNoNeighbours() {
        check("classic", "main" to N())
    }

    @Test
    fun chartMacroNeighbours() {
        check(
            "chart_macro",
            "main" to N(up = "btcd", down = "movers", right = "movers"),
            "btcd" to N(down = "stable_mcap", left = "main", right = "total"),
            "total" to N(down = "stable_dom", left = "btcd"),
            "stable_mcap" to N(up = "btcd", down = "movers", left = "main", right = "stable_dom"),
            "stable_dom" to N(up = "total", down = "movers", left = "stable_mcap"),
            "movers" to N(up = "stable_mcap", left = "main", right = "stable_dom")
        )
    }

    @Test
    fun twoColumnNeighbours() {
        check(
            "chart_watchlist",
            "main" to N(right = "watchlist"),
            "watchlist" to N(left = "main")
        )
        for (id in listOf("split_1_1", "split_1_2", "split_2_1")) {
            check(id, "left" to N(right = "right"), "right" to N(left = "left"))
        }
        check(
            "chart_ticker",
            "main" to N(down = "ticker"),
            "ticker" to N(up = "main")
        )
    }

    @Test
    fun splitThreeNeighbours() {
        check(
            "split_3",
            "main" to N(up = "top_right", down = "bottom_right", right = "top_right"),
            "top_right" to N(down = "bottom_right", left = "main"),
            "bottom_right" to N(up = "top_right", left = "main")
        )
    }

    @Test
    fun quadGridsAlwaysStayInTheirRowOrColumn() {
        for (id in listOf("grid_4_charts", "grid_4_cards")) {
            check(
                id,
                "tl" to N(down = "bl", right = "tr"),
                "tr" to N(down = "br", left = "tl"),
                "bl" to N(up = "tl", right = "br"),
                "br" to N(up = "tr", left = "bl")
            )
        }
        check(
            "multi_tf",
            "m1" to N(down = "d1", right = "h1"),
            "h1" to N(down = "w1", left = "m1"),
            "d1" to N(up = "m1", right = "w1"),
            "w1" to N(up = "h1", left = "d1")
        )
    }

    @Test
    fun sixGridNeighbours() {
        check(
            "grid_6",
            "r1c1" to N(down = "r2c1", right = "r1c2"),
            "r1c2" to N(down = "r2c2", left = "r1c1", right = "r1c3"),
            "r1c3" to N(down = "r2c3", left = "r1c2"),
            "r2c1" to N(up = "r1c1", right = "r2c2"),
            "r2c2" to N(up = "r1c2", left = "r2c1", right = "r2c3"),
            "r2c3" to N(up = "r1c3", left = "r2c2")
        )
    }

    @Test
    fun nineGridNeighbours() {
        check(
            "grid_9",
            "r1c1" to N(down = "r2c1", right = "r1c2"),
            "r1c2" to N(down = "r2c2", left = "r1c1", right = "r1c3"),
            "r1c3" to N(down = "r2c3", left = "r1c2"),
            "r2c1" to N(up = "r1c1", down = "r3c1", right = "r2c2"),
            "r2c2" to N(up = "r1c2", down = "r3c2", left = "r2c1", right = "r2c3"),
            "r2c3" to N(up = "r1c3", down = "r3c3", left = "r2c2"),
            "r3c1" to N(up = "r2c1", right = "r3c2"),
            "r3c2" to N(up = "r2c2", left = "r3c1", right = "r3c3"),
            "r3c3" to N(up = "r2c3", left = "r3c2")
        )
    }

    @Test
    fun overviewNeighbours() {
        check(
            "overview",
            "btcd" to N(down = "gainers", right = "total"),
            "total" to N(down = "losers", left = "btcd", right = "stable_mcap"),
            "stable_mcap" to N(down = "losers", left = "total", right = "stable_dom"),
            "stable_dom" to N(down = "watchlist", left = "stable_mcap"),
            "gainers" to N(up = "btcd", down = "ticker", left = "btcd", right = "losers"),
            "losers" to N(up = "total", down = "ticker", left = "gainers", right = "watchlist"),
            "watchlist" to N(up = "stable_dom", down = "ticker", left = "losers", right = "stable_dom"),
            "ticker" to N(up = "losers", left = "gainers", right = "watchlist")
        )
    }

    @Test
    fun proThreeColumnNeighbours() {
        check(
            "pro_3col",
            "watchlist" to N(up = "btcd", down = "movers", right = "main"),
            "main" to N(up = "btcd", down = "movers", left = "watchlist", right = "movers"),
            "btcd" to N(down = "stable_mcap", left = "main", right = "total"),
            "total" to N(down = "stable_dom", left = "btcd"),
            "stable_mcap" to N(up = "btcd", down = "movers", left = "main", right = "stable_dom"),
            "stable_dom" to N(up = "total", down = "movers", left = "stable_mcap"),
            "movers" to N(up = "stable_mcap", left = "main", right = "stable_dom")
        )
    }

    @Test
    fun unknownCurrentAndEdgeCasesReturnNull() {
        val rects = LayoutGeometry.solve(LayoutPresets.byId("grid_9")!!, 1920f, 1080f)
        assertNull(LayoutFocus.next("nope", rects, FocusDirection.Left))
        val single = mapOf("only" to LayoutRect(0f, 0f, 100f, 100f))
        for (dir in FocusDirection.entries) assertNull(LayoutFocus.next("only", single, dir))
    }

    @Test
    fun clampedTinyRectsStillResolveSpatially() {
        val current = LayoutRect(0f, 0f, 10f, 10f)
        val candidates = listOf(
            "far" to LayoutRect(100f, 0f, 10f, 10f),
            "near" to LayoutRect(20f, 2f, 10f, 10f),
            "below" to LayoutRect(0f, 20f, 10f, 10f)
        )
        assertEquals("near", LayoutFocus.next(current, candidates, FocusDirection.Right))
        assertEquals("below", LayoutFocus.next(current, candidates, FocusDirection.Down))
        assertNull(LayoutFocus.next(current, candidates, FocusDirection.Left))
        assertNull(LayoutFocus.next(current, candidates, FocusDirection.Up))
    }

    private fun check(id: String, vararg expected: Pair<String, N>) {
        val rects = LayoutGeometry.solve(LayoutPresets.byId(id)!!, 1920f, 1080f)
        val table = expected.toMap()
        assertEquals(rects.keys, table.keys, "$id table covers every slot")
        val diffs = buildList {
            for ((slot, n) in table) {
                for ((dir, want) in listOf(
                    FocusDirection.Up to n.up,
                    FocusDirection.Down to n.down,
                    FocusDirection.Left to n.left,
                    FocusDirection.Right to n.right
                )) {
                    val got = LayoutFocus.next(slot, rects, dir)
                    if (got != want) add("$id/$slot ${dir.name.lowercase()}: expected=$want actual=$got")
                }
            }
        }
        assertTrue(diffs.isEmpty(), diffs.joinToString("\n"))
    }
}
