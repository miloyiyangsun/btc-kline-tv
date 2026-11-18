package com.milolab.btctv.ui.board.layout

import com.milolab.btctv.data.TF

/**
 * 内置布局预设。尺寸语义见 LayoutGeometry（1920×1080 设计画布）。
 * 每个槽位的 detail/params 直接沿用设计文档 §4 的默认值。
 */
object LayoutPresets {

    const val CLASSIC = "classic"

    private const val BTC = "BTCUSDT"
    private const val ETH = "ETHUSDT"
    private const val SOL = "SOLUSDT"
    private const val BNB = "BNBUSDT"
    private const val XRP = "XRPUSDT"
    private const val DOGE = "DOGEUSDT"
    private const val ADA = "ADAUSDT"
    private const val AVAX = "AVAXUSDT"
    private const val LINK = "LINKUSDT"

    val all: List<LayoutSpec> by lazy {
        listOf(
            classic(),
            chartMacro(),
            chartWatchlist(),
            split11(),
            split12(),
            split21(),
            split3(),
            grid4Charts(),
            grid4Cards(),
            multiTf(),
            grid6(),
            grid9(),
            overview(),
            chartTicker(),
            pro3col()
        )
    }

    private val byId: Map<String, LayoutSpec> by lazy { all.associateBy { it.id } }

    fun byId(id: String?): LayoutSpec? = byId[id]

    fun classic(): LayoutSpec = LayoutSpec(
        id = CLASSIC,
        name = "经典单图",
        root = LayoutNode.Leaf("main"),
        slots = slotsOf(
            chart("main", SlotBinding.FollowMain, SlotParamKeys.DETAIL_FULL)
        )
    )

    /** L02 单图 + 右侧大盘栏 */
    private fun chartMacro(): LayoutSpec {
        val tiles = LayoutNode.Split(
            SplitDirection.Vertical,
            listOf(1f, 1f),
            listOf(
                row("btcd", "total"),
                row("stable_mcap", "stable_dom")
            )
        )
        val root = LayoutNode.Split(
            SplitDirection.Horizontal,
            listOf(2f, 1f),
            listOf(
                LayoutNode.Leaf("main"),
                LayoutNode.Split(
                    SplitDirection.Vertical,
                    listOf(26f, 59f),
                    listOf(tiles, LayoutNode.Leaf("movers"))
                )
            )
        )
        return LayoutSpec(
            id = "chart_macro",
            name = "单图 + 大盘栏",
            root = root,
            slots = slotsOf(
                chart("main", SlotBinding.FollowMain, SlotParamKeys.DETAIL_FULL),
                stat("btcd", StatKind.BTCD),
                stat("total", StatKind.TOTAL),
                stat("stable_mcap", StatKind.STABLE_CAP),
                stat("stable_dom", StatKind.STABLE_DOM),
                movers("movers", SlotParamKeys.METRIC_GAINERS)
            )
        )
    }

    /** L03 单图 + 右侧自选 */
    private fun chartWatchlist(): LayoutSpec = LayoutSpec(
        id = "chart_watchlist",
        name = "单图 + 自选",
        root = LayoutNode.Split(
            SplitDirection.Horizontal,
            listOf(113f, 42f),
            listOf(LayoutNode.Leaf("main"), LayoutNode.Leaf("watchlist"))
        ),
        slots = slotsOf(
            chart("main", SlotBinding.FollowMain, SlotParamKeys.DETAIL_FULL),
            watchlist("watchlist")
        )
    )

    /** L04 左右 1:1 */
    private fun split11(): LayoutSpec = LayoutSpec(
        id = "split_1_1",
        name = "左右 1:1",
        root = LayoutNode.Split(
            SplitDirection.Horizontal,
            listOf(1f, 1f),
            listOf(LayoutNode.Leaf("left"), LayoutNode.Leaf("right"))
        ),
        slots = slotsOf(
            chart("left", SlotBinding.FollowMain, SlotParamKeys.DETAIL_FULL),
            chart("right", SlotBinding.Fixed(ETH), SlotParamKeys.DETAIL_COMPACT)
        )
    )

    /** L05 左 1 : 右 2 */
    private fun split12(): LayoutSpec = LayoutSpec(
        id = "split_1_2",
        name = "左 1 : 右 2",
        root = LayoutNode.Split(
            SplitDirection.Horizontal,
            listOf(1f, 2f),
            listOf(LayoutNode.Leaf("left"), LayoutNode.Leaf("right"))
        ),
        slots = slotsOf(
            chart("left", SlotBinding.Fixed(ETH), SlotParamKeys.DETAIL_COMPACT),
            chart("right", SlotBinding.FollowMain, SlotParamKeys.DETAIL_FULL)
        )
    )

    /** L06 左 2 : 右 1 */
    private fun split21(): LayoutSpec = LayoutSpec(
        id = "split_2_1",
        name = "左 2 : 右 1",
        root = LayoutNode.Split(
            SplitDirection.Horizontal,
            listOf(2f, 1f),
            listOf(LayoutNode.Leaf("left"), LayoutNode.Leaf("right"))
        ),
        slots = slotsOf(
            chart("left", SlotBinding.FollowMain, SlotParamKeys.DETAIL_FULL),
            chart("right", SlotBinding.Fixed(ETH), SlotParamKeys.DETAIL_COMPACT)
        )
    )

    /** L07 三分屏：左大、右双小 */
    private fun split3(): LayoutSpec = LayoutSpec(
        id = "split_3",
        name = "三分屏（1 大 2 小）",
        root = LayoutNode.Split(
            SplitDirection.Horizontal,
            listOf(2f, 1f),
            listOf(
                LayoutNode.Leaf("main"),
                LayoutNode.Split(
                    SplitDirection.Vertical,
                    listOf(1f, 1f),
                    listOf(LayoutNode.Leaf("top_right"), LayoutNode.Leaf("bottom_right"))
                )
            )
        ),
        slots = slotsOf(
            chart("main", SlotBinding.FollowMain, SlotParamKeys.DETAIL_FULL),
            chart("top_right", SlotBinding.Fixed(ETH), SlotParamKeys.DETAIL_COMPACT),
            chart("bottom_right", SlotBinding.Fixed(SOL), SlotParamKeys.DETAIL_COMPACT)
        )
    )

    /** L08 四宫格 K 线 */
    private fun grid4Charts(): LayoutSpec = LayoutSpec(
        id = "grid_4_charts",
        name = "四宫格 K 线",
        root = grid(
            2,
            2,
            listOf("tl", "tr", "bl", "br")
        ),
        slots = slotsOf(
            chart("tl", SlotBinding.Fixed(BTC), SlotParamKeys.DETAIL_COMPACT),
            chart("tr", SlotBinding.Fixed(ETH), SlotParamKeys.DETAIL_COMPACT),
            chart("bl", SlotBinding.Fixed(SOL), SlotParamKeys.DETAIL_COMPACT),
            chart("br", SlotBinding.Fixed(BNB), SlotParamKeys.DETAIL_COMPACT)
        )
    )

    /** L09 四宫格迷你卡 */
    private fun grid4Cards(): LayoutSpec = LayoutSpec(
        id = "grid_4_cards",
        name = "四宫格迷你卡",
        root = grid(2, 2, listOf("tl", "tr", "bl", "br")),
        slots = slotsOf(
            priceCard("tl", BTC),
            priceCard("tr", ETH),
            priceCard("bl", SOL),
            priceCard("br", BNB)
        )
    )

    /** L10 同币多周期：顶部 72 设计单位共享头部，下面 2×2。 */
    private fun multiTf(): LayoutSpec = LayoutSpec(
        id = "multi_tf",
        name = "同币多周期",
        root = grid(2, 2, listOf("m1", "h1", "d1", "w1")),
        slots = slotsOf(
            chart("m1", SlotBinding.FollowMain, SlotParamKeys.DETAIL_COMPACT, TF.M1),
            chart("h1", SlotBinding.FollowMain, SlotParamKeys.DETAIL_COMPACT, TF.H1),
            chart("d1", SlotBinding.FollowMain, SlotParamKeys.DETAIL_COMPACT, TF.D1),
            chart("w1", SlotBinding.FollowMain, SlotParamKeys.DETAIL_COMPACT, TF.W1)
        ),
        topChrome = 72f
    )

    /** L11 六宫格 */
    private fun grid6(): LayoutSpec = LayoutSpec(
        id = "grid_6",
        name = "六宫格",
        root = grid(2, 3, listOf("r1c1", "r1c2", "r1c3", "r2c1", "r2c2", "r2c3")),
        slots = slotsOf(
            priceCard("r1c1", BTC),
            priceCard("r1c2", ETH),
            priceCard("r1c3", SOL),
            priceCard("r2c1", BNB),
            priceCard("r2c2", XRP),
            priceCard("r2c3", DOGE)
        )
    )

    /** L12 九宫格行情墙 */
    private fun grid9(): LayoutSpec = LayoutSpec(
        id = "grid_9",
        name = "九宫格行情墙",
        root = grid(
            3,
            3,
            listOf(
                "r1c1", "r1c2", "r1c3",
                "r2c1", "r2c2", "r2c3",
                "r3c1", "r3c2", "r3c3"
            )
        ),
        slots = slotsOf(
            priceCard("r1c1", BTC),
            priceCard("r1c2", ETH),
            priceCard("r1c3", SOL),
            priceCard("r2c1", BNB),
            priceCard("r2c2", XRP),
            priceCard("r2c3", ADA),
            priceCard("r3c1", DOGE),
            priceCard("r3c2", AVAX),
            priceCard("r3c3", LINK)
        )
    )

    /** L13 大盘总览 */
    private fun overview(): LayoutSpec = LayoutSpec(
        id = "overview",
        name = "大盘总览",
        root = LayoutNode.Split(
            SplitDirection.Vertical,
            listOf(10f, 50f, 3f),
            listOf(
                LayoutNode.Split(
                    SplitDirection.Horizontal,
                    listOf(1f, 1f, 1f, 1f),
                    listOf(
                        LayoutNode.Leaf("btcd"),
                        LayoutNode.Leaf("total"),
                        LayoutNode.Leaf("stable_mcap"),
                        LayoutNode.Leaf("stable_dom")
                    )
                ),
                LayoutNode.Split(
                    SplitDirection.Horizontal,
                    listOf(1f, 1f, 1f),
                    listOf(
                        LayoutNode.Leaf("gainers"),
                        LayoutNode.Leaf("losers"),
                        LayoutNode.Leaf("watchlist")
                    )
                ),
                LayoutNode.Leaf("ticker")
            )
        ),
        slots = slotsOf(
            stat("btcd", StatKind.BTCD),
            stat("total", StatKind.TOTAL),
            stat("stable_mcap", StatKind.STABLE_CAP),
            stat("stable_dom", StatKind.STABLE_DOM),
            movers("gainers", SlotParamKeys.METRIC_GAINERS),
            movers("losers", SlotParamKeys.METRIC_LOSERS),
            watchlist("watchlist"),
            ticker("ticker")
        )
    )

    /** L14 单图 + 跑马灯底栏 */
    private fun chartTicker(): LayoutSpec = LayoutSpec(
        id = "chart_ticker",
        name = "单图 + 跑马灯",
        root = LayoutNode.Split(
            SplitDirection.Vertical,
            listOf(81f, 4f),
            listOf(LayoutNode.Leaf("main"), LayoutNode.Leaf("ticker"))
        ),
        slots = slotsOf(
            chart("main", SlotBinding.FollowMain, SlotParamKeys.DETAIL_FULL),
            ticker("ticker")
        )
    )

    /** L15 三栏工作台：自选 / 主图 / 大盘栏。 */
    private fun pro3col(): LayoutSpec {
        val tiles = LayoutNode.Split(
            SplitDirection.Vertical,
            listOf(1f, 1f),
            listOf(
                row("btcd", "total"),
                row("stable_mcap", "stable_dom")
            )
        )
        val root = LayoutNode.Split(
            SplitDirection.Horizontal,
            listOf(30f, 82f, 42f),
            listOf(
                LayoutNode.Leaf("watchlist"),
                LayoutNode.Leaf("main"),
                LayoutNode.Split(
                    SplitDirection.Vertical,
                    listOf(26f, 59f),
                    listOf(tiles, LayoutNode.Leaf("movers"))
                )
            )
        )
        return LayoutSpec(
            id = "pro_3col",
            name = "三栏工作台",
            root = root,
            slots = slotsOf(
                watchlist("watchlist"),
                chart("main", SlotBinding.FollowMain, SlotParamKeys.DETAIL_FULL),
                stat("btcd", StatKind.BTCD),
                stat("total", StatKind.TOTAL),
                stat("stable_mcap", StatKind.STABLE_CAP),
                stat("stable_dom", StatKind.STABLE_DOM),
                movers("movers", SlotParamKeys.METRIC_GAINERS)
            )
        )
    }

    // ------------------------------------------------------------ helpers

    private fun row(left: String, right: String): LayoutNode = LayoutNode.Split(
        SplitDirection.Horizontal,
        listOf(1f, 1f),
        listOf(LayoutNode.Leaf(left), LayoutNode.Leaf(right))
    )

    /** rows × cols 等权网格，slotIds 按阅读顺序。 */
    private fun grid(rows: Int, cols: Int, slotIds: List<String>): LayoutNode {
        require(rows > 0 && cols > 0 && slotIds.size == rows * cols)
        val rowNodes = (0 until rows).map { r ->
            LayoutNode.Split(
                SplitDirection.Horizontal,
                List(cols) { 1f },
                (0 until cols).map { c -> LayoutNode.Leaf(slotIds[r * cols + c]) }
            )
        }
        return LayoutNode.Split(SplitDirection.Vertical, List(rows) { 1f }, rowNodes)
    }

    private fun slotsOf(vararg slots: Slot): Map<String, Slot> {
        val map = LinkedHashMap<String, Slot>(slots.size)
        for (slot in slots) map[slot.id] = slot
        return map
    }

    private fun chart(
        id: String,
        binding: SlotBinding,
        detail: String,
        tf: TF? = null
    ): Slot = Slot(
        id = id,
        module = ModuleType.MainChart,
        binding = binding,
        params = mapOf(SlotParamKeys.DETAIL to detail),
        timeframe = tf
    )

    private fun priceCard(id: String, instrumentId: String): Slot = Slot(
        id = id,
        module = ModuleType.PriceCard,
        binding = SlotBinding.Fixed(instrumentId),
        params = mapOf(
            SlotParamKeys.SPARK_WINDOW to SlotParamKeys.WINDOW_H24,
            SlotParamKeys.SHOW_SPARK to "true"
        )
    )

    private fun stat(id: String, kind: StatKind): Slot = Slot(
        id = id,
        module = ModuleType.StatTile(kind)
    )

    private fun movers(id: String, metric: String): Slot = Slot(
        id = id,
        module = ModuleType.Movers,
        params = mapOf(
            SlotParamKeys.SCOPE to SlotParamKeys.SCOPE_SPOT_USDT,
            SlotParamKeys.METRIC to metric,
            SlotParamKeys.LIMIT to "5"
        )
    )

    private fun watchlist(id: String): Slot = Slot(
        id = id,
        module = ModuleType.Watchlist,
        params = mapOf(
            SlotParamKeys.SOURCE to SlotParamKeys.SOURCE_FAVORITES,
            SlotParamKeys.LIMIT to "10"
        )
    )

    private fun ticker(id: String): Slot = Slot(
        id = id,
        module = ModuleType.TickerStrip,
        params = mapOf(
            SlotParamKeys.SOURCE to SlotParamKeys.SOURCE_FAVORITES,
            SlotParamKeys.INCLUDE_OVERVIEW to "true",
            SlotParamKeys.SPEED to SlotParamKeys.SPEED_NORMAL
        )
    )
}
