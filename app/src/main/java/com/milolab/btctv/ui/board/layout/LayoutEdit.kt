package com.milolab.btctv.ui.board.layout

import com.milolab.btctv.data.TF

data class LayoutValidationResult(val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/** 保存/应用前的结构校验；JSON 解析和编辑操作共用同一套规则。 */
object LayoutValidator {
    const val MAX_DEPTH = 9
    const val MAX_LEAVES = 9
    const val MIN_CHILDREN = 2
    const val MAX_CHILDREN = 4

    fun validate(spec: LayoutSpec): LayoutValidationResult {
        val errors = ArrayList<String>()

        if (spec.id.isBlank()) errors += "layout id is blank"
        if (!spec.topChrome.isFinite() || spec.topChrome < 0f) errors += "topChrome invalid"

        for ((key, slot) in spec.slots) {
            if (slot.id.isBlank()) errors += "slot id is blank"
            if (key != slot.id) errors += "slot key $key != ${slot.id}"
            if (slot.binding is SlotBinding.Fixed && slot.binding.instrumentId.isBlank()) {
                errors += "slot ${slot.id} fixed instrument is blank"
            }
        }

        val leaves = spec.root.leaves()
        if (leaves.isEmpty()) errors += "root has no leaves"
        if (leaves.size > MAX_LEAVES) errors += "too many leaves: ${leaves.size} > $MAX_LEAVES"
        if (spec.root.depth() > MAX_DEPTH) errors += "tree too deep: ${spec.root.depth()} > $MAX_DEPTH"

        val seen = HashSet<String>()
        for (leaf in leaves) {
            if (leaf.slotId.isBlank()) {
                errors += "leaf slotId is blank"
                continue
            }
            if (!seen.add(leaf.slotId)) errors += "duplicate leaf slotId ${leaf.slotId}"
            if (spec.slots[leaf.slotId] == null) errors += "missing slot ${leaf.slotId}"
        }
        val orphaned = spec.slots.keys - seen
        if (orphaned.isNotEmpty()) errors += "unused slots: ${orphaned.joinToString(",")}"

        validateNode(spec.root, "root", errors)
        return LayoutValidationResult(errors)
    }

    fun isValid(spec: LayoutSpec): Boolean = validate(spec).ok

    private fun validateNode(node: LayoutNode, path: String, errors: MutableList<String>) {
        when (node) {
            is LayoutNode.Leaf -> Unit
            is LayoutNode.Split -> {
                val n = node.children.size
                if (n < MIN_CHILDREN || n > MAX_CHILDREN) {
                    errors += "$path has $n children (allowed $MIN_CHILDREN..$MAX_CHILDREN)"
                }
                if (node.weights.size != n) {
                    errors += "$path weights size ${node.weights.size} != children $n"
                } else {
                    for ((i, w) in node.weights.withIndex()) {
                        if (!w.isFinite() || w <= 0f) errors += "$path weight[$i] invalid: $w"
                    }
                }
                node.children.forEachIndexed { i, child ->
                    validateNode(child, "$path.$i", errors)
                }
            }
        }
    }
}

/** 自定义分割可选的权重比例。 */
enum class SplitRatio(val weights: List<Float>) {
    OneToOne(listOf(1f, 1f)),
    OneToTwo(listOf(1f, 2f)),
    TwoToOne(listOf(2f, 1f)),
    OneToOneOne(listOf(1f, 1f, 1f))
}

/**
 * 纯函数编辑 API：全部返回新的不可变 LayoutSpec；操作非法时返回 null，
 * 调用方无需捕获异常。结构编辑（split/merge/remove）不修改 spec.id，由设置页决定是否另存 custom。
 */
object LayoutEdit {

    fun splitLeaf(
        spec: LayoutSpec,
        slotId: String,
        direction: SplitDirection,
        ratio: SplitRatio
    ): LayoutSpec? {
        val source = spec.slots[slotId] ?: return null
        if (spec.root.leafSlotIds().none { it == slotId }) return null
        if (spec.slots.size + ratio.weights.size - 1 > LayoutValidator.MAX_LEAVES) return null

        var nextId = nextSlotNumber(spec)
        val newSlots = LinkedHashMap(spec.slots)
        val newChildren = ArrayList<LayoutNode>(ratio.weights.size)
        newChildren += LayoutNode.Leaf(slotId)
        for (i in 1 until ratio.weights.size) {
            val id = "slot_${nextId++}"
            newSlots[id] = source.copy(id = id)
            newChildren += LayoutNode.Leaf(id)
        }

        val root = replaceLeaf(spec.root, slotId) {
            LayoutNode.Split(direction, ratio.weights, newChildren)
        }
        val updated = spec.copy(root = root, slots = newSlots)
        return if (LayoutValidator.isValid(updated)) updated else null
    }

    /** 仅当目标叶子与兄弟构成 2 子 Split 时，删除该叶并让兄弟占据父节点位置。 */
    fun mergeSiblings(spec: LayoutSpec, slotId: String): LayoutSpec? {
        val path = parentPathOf(spec.root, slotId) ?: return null
        val parent = nodeAt(spec.root, path) as? LayoutNode.Split ?: return null
        if (parent.children.size != 2) return null

        var sibling: LayoutNode? = null
        for (child in parent.children) {
            if (child is LayoutNode.Leaf && child.slotId == slotId) continue
            sibling = child
            break
        }
        val keep = sibling ?: return null

        val root = replaceAt(spec.root, path, keep)
        val updated = spec.copy(root = root, slots = spec.slots - slotId)
        return if (LayoutValidator.isValid(updated)) updated else null
    }

    /** 删除任意叶子；剩余兄弟保持原权重，父 Split 只剩 1 个子节点时自动折叠。 */
    fun removeLeaf(spec: LayoutSpec, slotId: String): LayoutSpec? {
        if (spec.root.leafSlotIds().none { it == slotId }) return null
        if (spec.root.leaves().size <= 1) return null

        fun walk(node: LayoutNode): LayoutNode? = when (node) {
            is LayoutNode.Leaf -> if (node.slotId == slotId) null else node
            is LayoutNode.Split -> {
                val kept = ArrayList<LayoutNode>(node.children.size)
                val keptWeights = ArrayList<Float>(node.children.size)
                node.children.forEachIndexed { i, child ->
                    val next = walk(child)
                    if (next != null) {
                        kept += next
                        keptWeights += node.weights.getOrElse(i) { 1f }
                    }
                }
                when (kept.size) {
                    0 -> null
                    1 -> kept[0]
                    else -> LayoutNode.Split(node.direction, keptWeights, kept)
                }
            }
        }

        val root = walk(spec.root) ?: return null
        val updated = spec.copy(root = root, slots = spec.slots - slotId)
        return if (LayoutValidator.isValid(updated)) updated else null
    }

    fun setModule(spec: LayoutSpec, slotId: String, module: ModuleType): LayoutSpec? {
        val slot = spec.slots[slotId] ?: return null
        val updated = spec.copy(slots = spec.slots + (slotId to slot.copy(module = module)))
        return if (LayoutValidator.isValid(updated)) updated else null
    }

    fun setBinding(spec: LayoutSpec, slotId: String, binding: SlotBinding): LayoutSpec? {
        val slot = spec.slots[slotId] ?: return null
        val updated = spec.copy(slots = spec.slots + (slotId to slot.copy(binding = binding)))
        return if (LayoutValidator.isValid(updated)) updated else null
    }

    fun setParams(spec: LayoutSpec, slotId: String, params: Map<String, String>): LayoutSpec? {
        val slot = spec.slots[slotId] ?: return null
        val updated = spec.copy(slots = spec.slots + (slotId to slot.copy(params = params)))
        return if (LayoutValidator.isValid(updated)) updated else null
    }

    fun setTimeframe(spec: LayoutSpec, slotId: String, timeframe: TF?): LayoutSpec? {
        val slot = spec.slots[slotId] ?: return null
        val updated = spec.copy(slots = spec.slots + (slotId to slot.copy(timeframe = timeframe)))
        return if (LayoutValidator.isValid(updated)) updated else null
    }

    private fun nextSlotNumber(spec: LayoutSpec): Int {
        var max = 0
        for (id in spec.slots.keys) {
            if (!id.startsWith("slot_")) continue
            val n = id.substring("slot_".length).toIntOrNull() ?: continue
            if (n > max) max = n
        }
        return max + 1
    }

    private fun parentPathOf(node: LayoutNode, slotId: String, path: MutableList<Int> = ArrayList()): List<Int>? {
        if (node !is LayoutNode.Split) return null
        if (node.children.any { it is LayoutNode.Leaf && it.slotId == slotId }) return path.toList()
        node.children.forEachIndexed { index, child ->
            path += index
            val found = parentPathOf(child, slotId, path)
            path.removeAt(path.lastIndex)
            if (found != null) return found
        }
        return null
    }

    private fun nodeAt(root: LayoutNode, path: List<Int>): LayoutNode {
        var node = root
        for (index in path) node = (node as LayoutNode.Split).children[index]
        return node
    }

    private fun replaceAt(root: LayoutNode, path: List<Int>, replacement: LayoutNode): LayoutNode {
        if (path.isEmpty()) return replacement
        val split = root as LayoutNode.Split
        val index = path.first()
        val children = split.children.toMutableList()
        children[index] = replaceAt(children[index], path.drop(1), replacement)
        return split.copy(children = children)
    }

    private fun replaceLeaf(
        node: LayoutNode,
        slotId: String,
        replacement: () -> LayoutNode
    ): LayoutNode = when (node) {
        is LayoutNode.Leaf -> if (node.slotId == slotId) replacement() else node
        is LayoutNode.Split -> node.copy(
            children = node.children.map { replaceLeaf(it, slotId, replacement) }
        )
    }
}
