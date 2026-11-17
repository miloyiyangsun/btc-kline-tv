import com.milolab.btctv.ui.layoutLabelPositions
import com.milolab.btctv.ui.selectLabelIndices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartLayoutTest {

    @Test
    fun crowdedLabelsArePushedApartWithoutReordering() {
        val desired = listOf(10f, 10f, 10f, 10f, 10f)
        val out = layoutLabelPositions(desired, top = 0f, bottom = 100f, minGap = 10f)
        assertEquals(5, out.size)
        for (i in 1 until out.size) {
            assertTrue(out[i] - out[i - 1] >= 10f - 1e-4f, "标签重叠: $out")
        }
        assertTrue(out.all { it in 0f..100f }, "标签越界: $out")
    }

    @Test
    fun labelsNearBottomArePulledBackInside() {
        val out = layoutLabelPositions(listOf(95f, 96f), top = 0f, bottom = 100f, minGap = 10f)
        assertEquals(2, out.size)
        assertTrue(out[1] <= 100f)
        assertTrue(out[0] <= out[1] - 10f + 1e-4f)
        assertTrue(out.all { it >= 0f })
    }

    @Test
    fun alreadySeparatedLabelsStayPut() {
        val desired = listOf(10f, 40f, 70f)
        val out = layoutLabelPositions(desired, top = 0f, bottom = 100f, minGap = 8f)
        assertEquals(desired, out)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertEquals(emptyList(), layoutLabelPositions(emptyList(), 0f, 100f, 8f))
    }

    /** 9 条价位挤在 100px 里：抽稀后剩下的标签仍然互不重叠。 */
    @Test
    fun crowdedLevelLabelsAreThinnedInsteadOfOverlapping() {
        val desired = listOf(10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f)
        val chosen = selectLabelIndices(desired, top = 0f, bottom = 100f, minGap = 20f)
        assertTrue(chosen.size <= 5, "应抽稀到 5 个以内: $chosen")
        assertTrue(chosen.isNotEmpty())

        val positions = layoutLabelPositions(chosen.map { desired[it] }, 0f, 100f, 20f)
        for (i in 1 until positions.size) {
            assertTrue(
                positions[i] - positions[i - 1] >= 20f - 1e-4f,
                "抽稀后仍重叠: $positions"
            )
        }
    }

    @Test
    fun labelSelectionKeepsEverythingWhenSpaceAllows() {
        val desired = listOf(10f, 40f, 70f)
        assertEquals(listOf(0, 1, 2), selectLabelIndices(desired, 0f, 100f, 8f))
    }

    @Test
    fun labelSelectionFallsBackToMiddleWhenOnlyOneFits() {
        val desired = listOf(10f, 20f, 30f)
        assertEquals(listOf(1), selectLabelIndices(desired, 0f, 10f, 20f))
    }
}
