package com.milolab.btctv.data

import com.milolab.btctv.data.indicators.IndicatorResult
import java.util.Calendar
import java.util.TimeZone

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

/** 时间轴标签的日期粒度 */
enum class TfScale { INTRADAY, DAY, MONTH, YEAR }

private const val DAY_MS = 86_400_000L
private const val WEEK_MS = 7 * DAY_MS
private val UTC_TZ: TimeZone = TimeZone.getTimeZone("UTC")

private fun utcCalendar(ms: Long): Calendar =
    Calendar.getInstance(UTC_TZ).apply { timeInMillis = ms }

/**
 * K线周期。三家交易所各自保存 interval 字符串和名义秒数。
 * 日及以上的 OKX bar 统一用 utc 变体，切分点与 Binance/Bybit 一致（00:00 UTC）。
 * 年线三家都没有原生周期，[aggregateFrom] 指向月线，由 MarketClient 聚合。
 */
enum class TF(
    val label: String,
    val binance: String,
    val okx: String,
    val bybit: String,
    val seconds: Long,
    val scale: TfScale = TfScale.INTRADAY,
    val aggregateFrom: TF? = null
) {
    M1("1m", "1m", "1m", "1", 60),
    M5("5m", "5m", "5m", "5", 300),
    M15("15m", "15m", "15m", "15", 900),
    M30("30m", "30m", "30m", "30", 1800),
    H1("1H", "1h", "1H", "60", 3600),
    H4("4H", "4h", "4H", "240", 14400),
    H12("12H", "12h", "12Hutc", "720", 43_200),
    D1("1D", "1d", "1Dutc", "D", 86_400, TfScale.DAY),
    W1("1W", "1w", "1Wutc", "W", 604_800, TfScale.MONTH),
    MO1("1M", "1M", "1Mutc", "M", 2_592_000, TfScale.MONTH),
    Y1("1Y", "", "", "", 31_536_000, TfScale.YEAR, aggregateFrom = MO1);

    /** 有没有原生周期；年线是月线聚合出来的 */
    val aggregated: Boolean get() = aggregateFrom != null

    fun step(delta: Int): TF = entries[Math.floorMod(ordinal + delta, entries.size)]

    /** 时间轴刻度：日以上显示日期，周/月显示年月，年显示年份 */
    val axisPattern: String
        get() = when (scale) {
            TfScale.INTRADAY -> "HH:mm"
            TfScale.DAY -> "MM-dd"
            TfScale.MONTH -> "yyyy-MM"
            TfScale.YEAR -> "yyyy"
        }

    /** 十字光标浮层的时间格式，规则与时间轴一致 */
    val tooltipPattern: String
        get() = if (scale == TfScale.INTRADAY) "MM-dd HH:mm" else axisPattern

    /**
     * 一根K线所属桶的起点（UTC）。周从周一开始，月/年按日历边界切；
     * 这些周期长度不固定，不能用 seconds 取整。
     */
    fun bucketStart(ms: Long): Long = when (this) {
        W1 -> {
            val day = Math.floorDiv(ms, DAY_MS)
            // 1970-01-01 是周四，以周一为 0 的星期序号 = (epochDay + 3) mod 7
            (day - Math.floorMod(day + 3, 7)) * DAY_MS
        }

        D1 -> ms - Math.floorMod(ms, DAY_MS)

        MO1, Y1 -> utcCalendar(ms).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            if (this@TF == Y1) set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        else -> ms - Math.floorMod(ms, seconds * 1000L)
    }

    /** [bucketStart] 所在桶的下一个桶起点（UTC） */
    fun nextBucketStart(ms: Long): Long {
        val start = bucketStart(ms)
        return when (this) {
            W1 -> start + WEEK_MS
            D1 -> start + DAY_MS
            MO1 -> utcCalendar(start).apply { add(Calendar.MONTH, 1) }.timeInMillis
            Y1 -> utcCalendar(start).apply { add(Calendar.YEAR, 1) }.timeInMillis
            else -> start + seconds * 1000L
        }
    }
}

enum class MarketType { CRYPTO, STOCK }

/** 同一只股票在 OKX 上的两种形态，各自是独立条目 */
enum class StockKind(val label: String) {
    PERP("永续"),
    TOKENIZED("代币现货")
}

/** 与加密币重名的正股（如 SOL）使用的前缀；展示仍用正股代码 */
const val STOCK_ID_PREFIX = "stock:"

/** 搜索弹层的市场过滤 */
enum class MarketFilter(val label: String) {
    ALL("全部"),
    CRYPTO("加密"),
    STOCK("美股")
}

/**
 * 一个可交易标的。id 在 App 内唯一（加密现货当前用 BASEQUOTE，如 BTCUSDT），
 * symbols 保存每个数据源自己的交易对写法，任何地方都不再从 base 拼接交易对。
 */
data class Instrument(
    val id: String,
    val displayName: String,
    val marketType: MarketType,
    val baseCurrency: String,
    val quoteCurrency: String,
    /** 价格显示小数位，由交易所 tickSize 推导 */
    val priceDecimals: Int,
    val symbols: Map<String, String>,
    /** 公司全名（美股），加密标的为空 */
    val name: String = "",
    val pricePrefix: String = "$",
    val accent: Long = 0xFF7FA8FF,
    val glyph: String = baseCurrency.take(1),
    val hot: Boolean = false,
    /** 股票形态：永续 / 代币现货；加密标的为 null */
    val stockKind: StockKind? = null
) {
    fun symbolFor(provider: String): String? = symbols[provider]
}

/**
 * 内置常用标的与标的工具。已知币种在三个交易所的符号都是字面量映射，
 * 搜索得到的标的则携带交易所返回的符号。
 */
object Instruments {

    val BTC = known("BTC", 2, 0xFFF7931A, "₿", "BTCUSDT", "BTC-USDT", "BTCUSDT")
    val ETH = known("ETH", 2, 0xFF8C9EFF, "Ξ", "ETHUSDT", "ETH-USDT", "ETHUSDT")
    val SOL = known("SOL", 2, 0xFF9D7BFF, "S", "SOLUSDT", "SOL-USDT", "SOLUSDT")
    val BNB = known("BNB", 2, 0xFFF3BA2F, "B", "BNBUSDT", "BNB-USDT", "BNBUSDT")
    val DOGE = known("DOGE", 5, 0xFFC2A633, "D", "DOGEUSDT", "DOGE-USDT", "DOGEUSDT")
    val XRP = known("XRP", 4, 0xFF6FA8FF, "X", "XRPUSDT", "XRP-USDT", "XRPUSDT")
    val ADA = known("ADA", 4, 0xFF3F8CFF, "A", "ADAUSDT", "ADA-USDT", "ADAUSDT")
    val AVAX = known("AVAX", 2, 0xFFE84142, "A", "AVAXUSDT", "AVAX-USDT", "AVAXUSDT")
    val LINK = known("LINK", 3, 0xFF4A90E2, "L", "LINKUSDT", "LINK-USDT", "LINKUSDT")
    val TON = known("TON", 3, 0xFF0098EA, "T", "TONUSDT", "TON-USDT", "TONUSDT")

    /** 热门列表：不联网也能直接切 */
    val HOT: List<Instrument> = listOf(BTC, ETH, SOL, BNB, DOGE, XRP, ADA, AVAX, LINK, TON)

    private val KNOWN: List<Instrument> = HOT

    fun byId(id: String?): Instrument? = KNOWN.firstOrNull { it.id == id }

    fun byBase(base: String?): Instrument? {
        val b = base?.trim()?.uppercase() ?: return null
        return KNOWN.firstOrNull { it.baseCurrency == b }
    }

    /** 旧版本只存了 Coin 枚举名（BTC/ETH/...），读不到就回退 BTC */
    fun fromLegacyName(name: String?): Instrument = byBase(name) ?: BTC

    fun isHot(base: String): Boolean = KNOWN.any { it.baseCurrency.equals(base, ignoreCase = true) }

    /** 搜索/目录里的标的：quote 默认 USDT，符号按交易所各自返回 */
    fun crypto(
        base: String,
        priceDecimals: Int,
        symbols: Map<String, String>,
        quote: String = "USDT"
    ): Instrument {
        val b = base.trim().uppercase()
        val q = quote.trim().uppercase()
        return Instrument(
            id = b + q,
            displayName = "$b / $q",
            marketType = MarketType.CRYPTO,
            baseCurrency = b,
            quoteCurrency = q,
            priceDecimals = priceDecimals.coerceIn(0, 8),
            symbols = symbols,
            accent = accentFor(b),
            glyph = glyphFor(b),
            hot = isHot(b)
        )
    }

    /**
     * 正股/ETF。id 为代码（与加密币冲突时加 stock: 前缀），
     * 代币化现货再加 .x 后缀，永续与现货因此是两个独立条目。
     */
    fun stock(
        ticker: String,
        company: String = "",
        priceDecimals: Int = 2,
        symbols: Map<String, String> = emptyMap(),
        kind: StockKind = StockKind.PERP,
        cryptoBases: Set<String> = emptySet()
    ): Instrument {
        val t = ticker.trim().uppercase()
        val c = company.trim().ifBlank { StockUniverse.companyName(t).orEmpty() }
        val id = stockId(t, cryptoBases) + if (kind == StockKind.TOKENIZED) ".x" else ""
        return Instrument(
            id = id,
            displayName = c.ifBlank { t },
            marketType = MarketType.STOCK,
            baseCurrency = t,
            quoteCurrency = "USD",
            priceDecimals = priceDecimals.coerceIn(0, 8),
            symbols = symbols,
            name = c,
            pricePrefix = "$",
            accent = 0xFF5B9DFF,
            glyph = t.take(1),
            hot = StockUniverse.isHot(t) && kind == StockKind.PERP,
            stockKind = kind
        )
    }

    /** 内置加密币代码和正股重名时（SOL 等）预先加前缀；交易所目录里的冲突由调用方传入 */
    fun stockId(ticker: String, cryptoBases: Set<String> = emptySet()): String {
        val t = ticker.trim().uppercase()
        val clash = KNOWN.any { it.baseCurrency.equals(t, ignoreCase = true) } ||
            cryptoBases.any { it.equals(t, ignoreCase = true) }
        return if (clash) STOCK_ID_PREFIX + t else t
    }

    /** 同一标的在多个交易所的搜索结果合并成一个，符号取并集，公司名取第一个非空 */
    fun merge(items: List<Instrument>): List<Instrument> {
        if (items.isEmpty()) return emptyList()
        val cryptoBases = LinkedHashSet<String>()
        val cryptoIds = LinkedHashSet<String>()
        for (inst in items) {
            if (inst.marketType == MarketType.CRYPTO) {
                cryptoBases.add(inst.baseCurrency.uppercase())
                cryptoIds.add(inst.id)
            }
        }
        val normalized = items.map { inst ->
            if (inst.marketType != MarketType.STOCK || inst.id.startsWith(STOCK_ID_PREFIX)) {
                inst
            } else {
                val suffix = if (inst.stockKind == StockKind.TOKENIZED) ".x" else ""
                val bare = inst.id.removeSuffix(suffix)
                if (bare.uppercase() in cryptoBases || inst.id in cryptoIds) {
                    inst.copy(id = STOCK_ID_PREFIX + inst.id)
                } else {
                    inst
                }
            }
        }
        return normalized.groupBy { it.id }.values.map { group ->
            val symbols = LinkedHashMap<String, String>()
            for (inst in group) symbols.putAll(inst.symbols)
            val first = group.first()
            val company = group.firstNotNullOfOrNull { it.name.takeIf(String::isNotBlank) }.orEmpty()
            val display = if (first.marketType == MarketType.STOCK && company.isNotBlank()) {
                company
            } else {
                first.displayName.ifBlank { first.baseCurrency }
            }
            first.copy(
                displayName = display,
                name = company,
                symbols = symbols,
                priceDecimals = group.maxOf { it.priceDecimals },
                hot = group.any { it.hot } ||
                    (first.stockKind != StockKind.TOKENIZED && isHot(first.baseCurrency))
            )
        }.sortedWith(
            compareBy<Instrument> { !it.hot }
                .thenBy { it.baseCurrency }
                .thenBy { if (it.stockKind == StockKind.TOKENIZED) 1 else 0 }
                .thenBy { it.id }
        )
    }

    private fun known(
        base: String,
        decimals: Int,
        accent: Long,
        glyph: String,
        binance: String,
        okx: String,
        bybit: String
    ): Instrument = Instrument(
        id = base + "USDT",
        displayName = "$base / USDT",
        marketType = MarketType.CRYPTO,
        baseCurrency = base,
        quoteCurrency = "USDT",
        priceDecimals = decimals,
        symbols = mapOf(
            PROVIDER_BINANCE to binance,
            PROVIDER_OKX to okx,
            PROVIDER_BYBIT to bybit
        ),
        accent = accent,
        glyph = glyph,
        hot = true
    )

    private fun accentFor(base: String): Long = when (base) {
        "BTC" -> 0xFFF7931A
        "ETH" -> 0xFF8C9EFF
        "SOL" -> 0xFF9D7BFF
        "BNB" -> 0xFFF3BA2F
        "DOGE" -> 0xFFC2A633
        "XRP" -> 0xFF6FA8FF
        "ADA" -> 0xFF3F8CFF
        "AVAX" -> 0xFFE84142
        "LINK" -> 0xFF4A90E2
        "TON" -> 0xFF0098EA
        else -> 0xFF7FA8FF
    }

    private fun glyphFor(base: String): String = when (base) {
        "BTC" -> "₿"
        "ETH" -> "Ξ"
        else -> base.take(1)
    }
}

enum class Conn { CONNECTING, LIVE, POLLING, OFFLINE }

enum class ChartStyle(val label: String) {
    CANDLE("蜡烛图"),
    LINE("折线"),
    AREA("面积")
}

/** 界面唯一数据源；不含任何 Android 依赖，方便离屏渲染验收 */
data class UiState(
    val instrument: Instrument = Instruments.BTC,
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
    val tickCount: Long = 0,
    // ---- 指标 ----
    /** 全部关闭 = 与旧版一致的干净画面 */
    val indicatorSettings: IndicatorSettings = IndicatorSettings.DEFAULT,
    /** ViewModel 预先算好的结果，key 为 IndicatorSpec.id；Compose 只读 */
    val indicatorResults: Map<String, IndicatorResult> = emptyMap(),
    /** 设置页里展开了参数的指标 id；BACK 先收它 */
    val expandedIndicator: String? = null,
    /** 设置页里折叠起来的分组（IndicatorGroup.name） */
    val collapsedIndicatorGroups: Set<String> = emptySet(),
    /** 例如副图已满 3 个时的提示 */
    val indicatorNotice: String? = null,
    /** candles 的修订号；只有内容真的变了才 +1，指标缓存按它命中 */
    val dataRevision: Long = 0,
    // ---- 标的搜索 ----
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Instrument> = emptyList(),
    val searchFilter: MarketFilter = MarketFilter.ALL,
    val searching: Boolean = false,
    val recent: List<Instrument> = emptyList(),
    val favorites: List<Instrument> = emptyList(),
    val hot: List<Instrument> = Instruments.HOT,
    val catalogLoading: Boolean = false,
    val catalogReady: Boolean = false
)
