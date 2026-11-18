package com.milolab.btctv.ui.board.layout

import com.milolab.btctv.data.TF
import org.json.JSONArray
import org.json.JSONObject

/**
 * 布局持久化格式（v1）。encode 省略默认值；decode 对缺字段宽容，
 * 但版本未知、模块未知、结构损坏时整体回退 classic，永不抛异常。
 */
object LayoutJson {

    const val VERSION = 1

    fun encode(spec: LayoutSpec): String = specToJson(spec).toString()

    fun decode(raw: String?): LayoutSpec =
        decodeOrNull(raw) ?: LayoutPresets.classic()

    fun decodeOrNull(raw: String?): LayoutSpec? = runCatching {
        if (raw.isNullOrBlank()) return null
        decodeOrThrow(raw)
    }.getOrNull()

    // ------------------------------------------------------------ encode

    private fun specToJson(spec: LayoutSpec): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("id", spec.id)
        if (spec.name.isNotEmpty()) put("name", spec.name)
        if (spec.topChrome > 0f) put("topChrome", spec.topChrome.toDouble())
        put("root", nodeToJson(spec.root))
        val arr = JSONArray()
        for (slot in spec.slots.values) arr.put(slotToJson(slot))
        put("slots", arr)
    }

    private fun nodeToJson(node: LayoutNode): JSONObject = when (node) {
        is LayoutNode.Leaf -> JSONObject().apply {
            put("type", "leaf")
            put("slotId", node.slotId)
        }

        is LayoutNode.Split -> JSONObject().apply {
            put("type", "split")
            put("dir", if (node.direction == SplitDirection.Horizontal) "H" else "V")
            put("weights", JSONArray(node.weights.map { it.toDouble() }))
            val children = JSONArray()
            for (child in node.children) children.put(nodeToJson(child))
            put("children", children)
        }
    }

    private fun slotToJson(slot: Slot): JSONObject = JSONObject().apply {
        put("id", slot.id)
        put("module", moduleName(slot.module))
        if (slot.module is ModuleType.StatTile) put("stat", slot.module.stat.name)
        put("binding", bindingToJson(slot.binding))
        if (slot.params.isNotEmpty()) {
            val params = JSONObject()
            for ((key, value) in slot.params) params.put(key, value)
            put("params", params)
        }
        slot.timeframe?.let { put("tf", it.name) }
    }

    private fun bindingToJson(binding: SlotBinding): JSONObject = when (binding) {
        is SlotBinding.FollowMain -> JSONObject().put("kind", "follow")
        is SlotBinding.Fixed -> JSONObject()
            .put("kind", "fixed")
            .put("instrumentId", binding.instrumentId)
    }

    private fun moduleName(module: ModuleType): String = when (module) {
        is ModuleType.MainChart -> "MAIN_CHART"
        is ModuleType.MiniChart -> "MINI_CHART"
        is ModuleType.PriceCard -> "PRICE_CARD"
        is ModuleType.StatTile -> "STAT_TILE"
        is ModuleType.Watchlist -> "WATCHLIST"
        is ModuleType.Movers -> "MOVERS"
        is ModuleType.TickerStrip -> "TICKER_STRIP"
    }

    // ------------------------------------------------------------ decode

    private fun decodeOrThrow(raw: String): LayoutSpec {
        val obj = JSONObject(raw)
        val version = obj.optInt("v", VERSION)
        if (version != VERSION) error("unsupported layout version $version")

        val id = sequenceOf("id", "preset", "presetId")
            .map { obj.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: "custom"
        val name = obj.optString("name")
        val topChrome = obj.optDouble("topChrome", 0.0)
            .let { if (it.isFinite() && it >= 0.0) it.toFloat() else 0f }

        val slots = readSlots(obj.opt("slots"))
        if (slots.isEmpty()) error("layout has no slots")
        val root = readNode(obj.optJSONObject("root") ?: error("missing root"))

        val spec = LayoutSpec(id = id, name = name, root = root, slots = slots, topChrome = topChrome)
        if (!LayoutValidator.isValid(spec)) error("invalid layout spec")
        return spec
    }

    private fun readSlots(raw: Any?): Map<String, Slot> {
        val out = LinkedHashMap<String, Slot>()
        when (raw) {
            is JSONArray -> {
                for (i in 0 until raw.length()) {
                    val item = raw.optJSONObject(i) ?: continue
                    val slot = readSlot(item, null) ?: continue
                    if (!out.containsKey(slot.id)) out[slot.id] = slot
                }
            }

            is JSONObject -> {
                for (key in raw.keys()) {
                    val item = raw.optJSONObject(key) ?: continue
                    val slot = readSlot(item, key) ?: continue
                    if (!out.containsKey(slot.id)) out[slot.id] = slot
                }
            }
        }
        return out
    }

    private fun readSlot(obj: JSONObject, fallbackId: String?): Slot? {
        val id = obj.optString("id").trim().ifEmpty { fallbackId.orEmpty() }
        if (id.isEmpty()) return null

        val module = readModule(obj) ?: return null
        val binding = readBinding(obj.optJSONObject("binding"))
        val params = readParams(obj.optJSONObject("params"))
        val tf = obj.optString("tf").trim().takeIf { it.isNotEmpty() }
            ?.let { name -> TF.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        return Slot(id = id, module = module, binding = binding, params = params, timeframe = tf)
    }

    private fun readModule(obj: JSONObject): ModuleType? {
        return when (obj.optString("module").trim().uppercase()) {
            "MAIN_CHART" -> ModuleType.MainChart
            "MINI_CHART" -> ModuleType.MiniChart
            "PRICE_CARD" -> ModuleType.PriceCard
            "STAT_TILE" -> {
                val kind = obj.optString("stat").trim().ifEmpty { StatKind.BTCD.name }
                StatKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }
                    ?.let { ModuleType.StatTile(it) }
            }

            "WATCHLIST" -> ModuleType.Watchlist
            "MOVERS" -> ModuleType.Movers
            "TICKER_STRIP" -> ModuleType.TickerStrip
            else -> null
        }
    }

    private fun readBinding(obj: JSONObject?): SlotBinding {
        if (obj == null) return SlotBinding.FollowMain
        return when (obj.optString("kind").trim().lowercase()) {
            "fixed" -> obj.optString("instrumentId").trim()
                .takeIf { it.isNotEmpty() }
                ?.let { SlotBinding.Fixed(it) }
                ?: SlotBinding.FollowMain

            else -> SlotBinding.FollowMain
        }
    }

    private fun readParams(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in obj.keys()) {
            when (val value = obj.opt(key)) {
                is String -> out[key] = value
                is Number, is Boolean -> out[key] = value.toString()
            }
        }
        return out
    }

    private fun readNode(obj: JSONObject): LayoutNode {
        return when (obj.optString("type").trim().lowercase()) {
            "leaf" -> {
                val slotId = obj.optString("slotId").trim()
                    .ifEmpty { obj.optString("slot").trim() }
                if (slotId.isEmpty()) error("leaf without slotId")
                LayoutNode.Leaf(slotId)
            }

            "split" -> {
                val direction = when (obj.optString("dir").trim().uppercase()) {
                    "H" -> SplitDirection.Horizontal
                    "V" -> SplitDirection.Vertical
                    else -> error("split without direction")
                }
                val childrenRaw = obj.optJSONArray("children") ?: error("split without children")
                if (childrenRaw.length() == 0) error("split with no children")
                val children = ArrayList<LayoutNode>(childrenRaw.length())
                for (i in 0 until childrenRaw.length()) {
                    children += readNode(childrenRaw.optJSONObject(i) ?: error("bad child"))
                }
                val weights = readWeights(obj.optJSONArray("weights"), children.size)
                LayoutNode.Split(direction, weights, children)
            }

            else -> error("unknown node type")
        }
    }

    private fun readWeights(arr: JSONArray?, n: Int): List<Float> {
        if (arr == null) return List(n) { 1f }
        if (arr.length() != n) error("weights size ${arr.length()} != children $n")
        return List(n) { i ->
            val value = (arr.opt(i) as? Number)?.toDouble() ?: 1.0
            if (value.isFinite() && value > 0.0) value.toFloat() else 1f
        }
    }
}
