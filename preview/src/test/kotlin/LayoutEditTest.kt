import com.milolab.btctv.data.TF
import com.milolab.btctv.ui.board.layout.LayoutEdit
import com.milolab.btctv.ui.board.layout.LayoutNode
import com.milolab.btctv.ui.board.layout.LayoutPresets
import com.milolab.btctv.ui.board.layout.LayoutValidator
import com.milolab.btctv.ui.board.layout.ModuleType
import com.milolab.btctv.ui.board.layout.SlotBinding
import com.milolab.btctv.ui.board.layout.SplitDirection
import com.milolab.btctv.ui.board.layout.SplitRatio
import com.milolab.btctv.ui.board.layout.depth
import com.milolab.btctv.ui.board.layout.leaves
import com.milolab.btctv.ui.board.layout.leafSlotIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LayoutEditTest {

    @Test
    fun splitLeafKeepsOriginalSlotAndAddsUniqueSlots() {
        val original = LayoutPresets.classic()
        for (ratio in SplitRatio.entries) {
            val edited = assertNotNull(
                LayoutEdit.splitLeaf(original, "main", SplitDirection.Horizontal, ratio),
                "split $ratio"
            )
            assertTrue(LayoutValidator.isValid(edited), "invalid after $ratio")
            assertEquals(ratio.weights.size, edited.root.leaves().size)
            assertEquals(ratio.weights.size, edited.slots.size)
            val split = edited.root as LayoutNode.Split
            assertEquals(SplitDirection.Horizontal, split.direction)
            assertEquals(ratio.weights, split.weights)
            assertEquals("main", (split.children.first() as LayoutNode.Leaf).slotId)
            assertEquals("main", edited.slot("main")!!.id)
            assertEquals(ModuleType.MainChart, edited.slot("main")!!.module)
            assertTrue(edited.slots.keys.containsAll(split.children.map { (it as LayoutNode.Leaf).slotId }))
            // original spec untouched
            assertTrue(original.root is LayoutNode.Leaf)
            assertEquals(1, original.slots.size)
        }
    }

    @Test
    fun splitCopiesModuleBindingParamsAndTimeframe() {
        val source = LayoutPresets.byId("multi_tf")!!
        val edited = assertNotNull(
            LayoutEdit.splitLeaf(source, "m1", SplitDirection.Vertical, SplitRatio.OneToOne)
        )
        val newId = edited.root.leafSlotIds().first { it != "m1" }
        val sourceSlot = source.slot("m1")!!
        val newSlot = edited.slot(newId)!!
        assertEquals(sourceSlot.module, newSlot.module)
        assertEquals(sourceSlot.binding, newSlot.binding)
        assertEquals(sourceSlot.params, newSlot.params)
        assertEquals(sourceSlot.timeframe, newSlot.timeframe)
    }

    @Test
    fun splitRejectsUnknownSlotAndFullNineLeafLayout() {
        val classic = LayoutPresets.classic()
        assertNull(LayoutEdit.splitLeaf(classic, "nope", SplitDirection.Horizontal, SplitRatio.OneToOne))
        val wall = LayoutPresets.byId("grid_9")!!
        assertNull(LayoutEdit.splitLeaf(wall, "r1c1", SplitDirection.Horizontal, SplitRatio.OneToOne))
    }

    @Test
    fun repeatedSplitsStopAtNineLeavesAndDepthLimit() {
        var spec = LayoutPresets.classic()
        var target = "main"
        repeat(8) {
            val next = assertNotNull(
                LayoutEdit.splitLeaf(spec, target, SplitDirection.Horizontal, SplitRatio.OneToOne),
                "split #${it + 1}"
            )
            spec = next
            target = spec.root.leafSlotIds().last()
        }
        assertEquals(9, spec.root.leaves().size)
        assertTrue(LayoutValidator.isValid(spec), LayoutValidator.validate(spec).errors.joinToString())
        assertTrue(spec.root.depth() <= 9, "depth ${spec.root.depth()}")
        assertNull(LayoutEdit.splitLeaf(spec, target, SplitDirection.Vertical, SplitRatio.OneToOne))
        assertEquals(9, spec.slots.size)
    }

    @Test
    fun removeLeafCollapsesSingleChildSplits() {
        val split = LayoutPresets.byId("split_1_1")!!
        val onlyLeft = assertNotNull(LayoutEdit.removeLeaf(split, "right"))
        assertEquals(listOf("left"), onlyLeft.root.leafSlotIds())
        assertTrue(onlyLeft.root is LayoutNode.Leaf)
        assertEquals(setOf("left"), onlyLeft.slots.keys)

        val three = LayoutPresets.byId("split_3")!!
        val two = assertNotNull(LayoutEdit.removeLeaf(three, "main"))
        assertEquals(listOf("top_right", "bottom_right"), two.root.leafSlotIds())
        assertEquals(setOf("top_right", "bottom_right"), two.slots.keys)

        assertNull(LayoutEdit.removeLeaf(LayoutPresets.classic(), "main"))
        assertNull(LayoutEdit.removeLeaf(three, "nope"))
    }

    @Test
    fun removeLeafKeepsRemainingWeightsAndDropsOne() {
        val wall = LayoutPresets.byId("grid_9")!!
        val eight = assertNotNull(LayoutEdit.removeLeaf(wall, "r1c2"))
        assertEquals(8, eight.root.leaves().size)
        val root = eight.root as LayoutNode.Split
        assertEquals(listOf(1f, 1f, 1f), root.weights)
        val firstRow = root.children.first() as LayoutNode.Split
        assertEquals(listOf("r1c1", "r1c3"), firstRow.children.map { (it as LayoutNode.Leaf).slotId })
        assertEquals(listOf(1f, 1f), firstRow.weights)
    }

    @Test
    fun mergeSiblingsOnlyWorksForTwoChildParents() {
        val pair = LayoutPresets.byId("split_1_1")!!
        val merged = assertNotNull(LayoutEdit.mergeSiblings(pair, "right"))
        assertEquals(listOf("left"), merged.root.leafSlotIds())
        assertEquals(setOf("left"), merged.slots.keys)

        val triple = LayoutPresets.byId("split_3")!!
        val mergedTriple = assertNotNull(LayoutEdit.mergeSiblings(triple, "main"))
        assertEquals(listOf("top_right", "bottom_right"), mergedTriple.root.leafSlotIds())
        assertTrue(mergedTriple.root is LayoutNode.Split)

        val wall = LayoutPresets.byId("grid_9")!!
        assertNull(LayoutEdit.mergeSiblings(wall, "r1c1"))
        assertNull(LayoutEdit.mergeSiblings(LayoutPresets.classic(), "main"))
    }

    @Test
    fun settersReturnNewSpecsAndKeepOriginalUntouched() {
        val original = LayoutPresets.byId("grid_9")!!

        val moduleEdited = assertNotNull(
            LayoutEdit.setModule(original, "r1c1", ModuleType.MiniChart)
        )
        assertEquals(ModuleType.MiniChart, moduleEdited.slot("r1c1")!!.module)
        assertEquals(ModuleType.PriceCard, original.slot("r1c1")!!.module)

        val bindingEdited = assertNotNull(
            LayoutEdit.setBinding(original, "r1c1", SlotBinding.FollowMain)
        )
        assertEquals(SlotBinding.FollowMain, bindingEdited.slot("r1c1")!!.binding)
        assertTrue(original.slot("r1c1")!!.binding is SlotBinding.Fixed)

        val paramsEdited = assertNotNull(
            LayoutEdit.setParams(original, "r1c1", mapOf("sparkWindow" to "D7"))
        )
        assertEquals("D7", paramsEdited.slot("r1c1")!!.params["sparkWindow"])

        val tfEdited = assertNotNull(LayoutEdit.setTimeframe(original, "r1c1", TF.H4))
        assertEquals(TF.H4, tfEdited.slot("r1c1")!!.timeframe)
        assertNull(original.slot("r1c1")!!.timeframe)

        assertNull(LayoutEdit.setModule(original, "nope", ModuleType.MiniChart))
        assertNull(LayoutEdit.setBinding(original, "nope", SlotBinding.FollowMain))
        assertNull(LayoutEdit.setParams(original, "nope", emptyMap()))
        assertNull(LayoutEdit.setTimeframe(original, "nope", null))
        assertNull(LayoutEdit.setBinding(original, "r1c1", SlotBinding.Fixed("")))
    }

    @Test
    fun editedSpecsKeepIdsAndRemainValid() {
        var spec = LayoutPresets.byId("chart_macro")!!
        for (slot in spec.slots.keys.toList()) {
            spec = LayoutEdit.setModule(spec, slot, spec.slot(slot)!!.module) ?: error("setModule $slot")
        }
        assertEquals("chart_macro", spec.id)
        assertTrue(LayoutValidator.isValid(spec))
        assertFalse(spec.legacy)
    }
}
