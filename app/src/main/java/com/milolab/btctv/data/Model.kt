package com.milolab.btctv.data

/** 一根K线，t = 开盘时间(毫秒) */
data class Candle(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double
)

data class Quote(
    val price: Double,
    val open24h: Double? = null,
    val high24h: Double? = null,
    val low24h: Double? = null,
    val vol24h: Double? = null,
    val quoteVol24h: Double? = null
) {
    /** 24小时涨跌幅（百分比） */
    val changePct: Double?
        get() = open24h?.takeIf { it > 0.0 }?.let { (price - it) / it * 100.0 }

    /** 24小时涨跌额 */
    val changeAbs: Double?
        get() = open24h?.let { price - it }
}

/** 合并行情：只用非空字段覆盖旧值，避免 WS 推送缺字段时把 24h 数据清空 */
fun Quote.mergeWith(old: Quote?): Quote {
    if (old == null) return this
    return Quote(
        price = price,
        open24h = open24h ?: old.open24h,
        high24h = high24h ?: old.high24h,
        low24h = low24h ?: old.low24h,
        vol24h = vol24h ?: old.vol24h,
        quoteVol24h = quoteVol24h ?: old.quoteVol24h
    )
}

enum class TF(val label: String, val binance: String, val okx: String, val bybit: String, val seconds: Long) {
    M1("1m", "1m", "1m", "1", 60),
    M5("5m", "5m", "5m", "5", 300),
    M15("15m", "15m", "15m", "15", 900),
    H1("1H", "1h", "1H", "60", 3600),
    H4("4H", "4h", "4H", "240", 14400),
    D1("1D", "1d", "1D", "D", 86400);

    fun step(delta: Int): TF = entries[Math.floorMod(ordinal + delta, entries.size)]
}

enum class Coin(val base: String, val accent: Long, val glyph: String) {
    BTC("BTC", 0xFFF7931A, "\u20BF"),
    ETH("ETH", 0xFF8C9EFF, "\u039E"),
    SOL("SOL", 0xFF9D7BFF, "S"),
    BNB("BNB", 0xFFF3BA2F, "B"),
    DOGE("DOGE", 0xFFC2A633, "D");

    val pair: String get() = base + "USDT"
    val okxPair: String get() = base + "-USDT"

    fun step(delta: Int): Coin = entries[Math.floorMod(ordinal + delta, entries.size)]
}

enum class Conn { CONNECTING, LIVE, POLLING, OFFLINE }

enum class ChartStyle(val label: String) {
    CANDLE("蜡烛图"),
    LINE("折线"),
    AREA("面积")
}

/** 界面唯一数据源；不含任何 Android 依赖，方便离屏渲染验收 */
data class UiState(
    val coin: Coin = Coin.BTC,
    val tf: TF = TF.M1,
    val candles: List<Candle> = emptyList(),
    val price: Double = 0.0,
    val prevPrice: Double = 0.0,
    val quote: Quote? = null,
    val provider: String? = null,
    val conn: Conn = Conn.CONNECTING,
    val settingsOpen: Boolean = false,
    val style: ChartStyle = ChartStyle.CANDLE,
    val flash: Boolean = true,
    val antiBurn: Boolean = true,
    val showClock: Boolean = true,
    val showVolume: Boolean = true,
    val autoStart: Boolean = true,
    /** 图表显示最近多少根K线（遥控器 ▲▼ 缩放） */
    val zoom: Int = 120,
    val firstLoad: Boolean = true,
    val tickCount: Long = 0
)
