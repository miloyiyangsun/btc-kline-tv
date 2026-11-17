package com.milolab.btctv.data

import java.util.Locale

/**
 * 交易所股票永续/代币化股票的共享规则：热门代码、中文别名、pre-IPO/合成标的过滤。
 * 纯 JVM 代码，Android 与 preview 工程共用。
 */
object StockUniverse {

    /** 热门美股：目录里真的有 symbol 才会显示 */
    val HOT_SYMBOLS = listOf(
        "NVDA", "TSLA", "AAPL", "MSFT", "GOOGL",
        "AMZN", "META", "COIN", "MSTR", "PLTR"
    )

    /** 未上市 / pre-IPO / 一级市场标的，交易所会以永续形式上线，但不属于已上市正股 */
    private val PRE_IPO = setOf(
        "OPENAI", "ANTHROPIC", "MINIMAX", "MOONSHOT", "UNITREE",
        "OURA", "SPCX", "SPACEX", "SHEIN", "ZHIPU",
        "PREDICT", "ANT", "STRIPE", "DATABRICKS"
    )

    fun isHot(ticker: String): Boolean =
        ticker.trim().uppercase(Locale.US) in HOT_SYMBOLS

    /** 只保留已上市正股/ETF：过滤指数、杠杆合成品和 pre-IPO */
    fun isEligible(ticker: String): Boolean {
        val t = ticker.trim().uppercase(Locale.US)
        if (t.isEmpty() || !t.all { it in 'A'..'Z' }) return false
        // JP225 / US500 / KR200 / CSOPSAMSUNG2L 这类指数与杠杆合成品都带数字
        if (t.any { it.isDigit() }) return false
        if (t.startsWith("CSOP")) return false
        return t !in PRE_IPO
    }

    /** OKX 不返回公司名，常用标的在本地补一份（有就用） */
    private val COMPANY_NAMES: Map<String, String> = mapOf(
        "NVDA" to "英伟达 NVIDIA",
        "TSLA" to "特斯拉 Tesla",
        "AAPL" to "苹果 Apple",
        "MSFT" to "微软 Microsoft",
        "GOOGL" to "谷歌 Alphabet",
        "AMZN" to "亚马逊 Amazon",
        "META" to "Meta",
        "COIN" to "Coinbase",
        "MSTR" to "微策略 Strategy",
        "PLTR" to "Palantir",
        "AVGO" to "博通 Broadcom",
        "TSM" to "台积电 TSMC",
        "NFLX" to "奈飞 Netflix",
        "INTC" to "英特尔 Intel",
        "AMD" to "AMD",
        "QCOM" to "高通 Qualcomm",
        "MU" to "美光 Micron",
        "ORCL" to "甲骨文 Oracle",
        "BABA" to "阿里巴巴 Alibaba",
        "JD" to "京东 JD",
        "PDD" to "拼多多 PDD",
        "BIDU" to "百度 Baidu",
        "NTES" to "网易 NetEase",
        "NIO" to "蔚来 NIO",
        "XPEV" to "小鹏 XPeng",
        "LI" to "理想 Li Auto",
        "BILI" to "哔哩哔哩 Bilibili",
        "WMT" to "沃尔玛 Walmart",
        "DIS" to "迪士尼 Disney",
        "KO" to "可口可乐 Coca-Cola",
        "SBUX" to "星巴克 Starbucks",
        "JPM" to "摩根大通 JPMorgan",
        "BRKB" to "伯克希尔 Berkshire Hathaway",
        "BA" to "波音 Boeing",
        "PFE" to "辉瑞 Pfizer",
        "JNJ" to "强生 J&J",
        "MA" to "万事达 Mastercard",
        "V" to "维萨 Visa",
        "BAC" to "美国银行 Bank of America",
        "XOM" to "埃克森美孚 ExxonMobil",
        "CVX" to "雪佛龙 Chevron",
        "SMCI" to "超微电脑 Supermicro",
        "CSCO" to "思科 Cisco",
        "DELL" to "戴尔 Dell",
        "CRCL" to "Circle",
        "HOOD" to "Robinhood",
        "MELI" to "MercadoLibre",
        "SONY" to "索尼 Sony",
        "ARM" to "Arm",
        "ASML" to "阿斯麦 ASML",
        "COST" to "好市多 Costco",
        "MCD" to "麦当劳 McDonald's",
        "NKE" to "耐克 Nike",
        "PEP" to "百事 PepsiCo",
        "LLY" to "礼来 Eli Lilly",
        "UNH" to "联合健康 UnitedHealth"
    )

    fun companyName(ticker: String): String? = COMPANY_NAMES[ticker.trim().uppercase(Locale.US)]

    /** 中文/英文别名 → 正股代码，用于本地搜索评分 */
    val ALIASES: List<Pair<String, String>> = listOf(
        "英伟达" to "NVDA",
        "苹果" to "AAPL",
        "特斯拉" to "TSLA",
        "微软" to "MSFT",
        "谷歌" to "GOOGL",
        "亚马逊" to "AMZN",
        "台积电" to "TSM",
        "奈飞" to "NFLX",
        "网飞" to "NFLX",
        "英特尔" to "INTC",
        "超威" to "AMD",
        "超微" to "AMD",
        "AMD" to "AMD",
        "Meta" to "META",
        "脸书" to "META",
        "coinbase" to "COIN",
        "Coinbase" to "COIN",
        "palantir" to "PLTR",
        "Palantir" to "PLTR",
        "微策略" to "MSTR",
        "strategy" to "MSTR",
        "博通" to "AVGO",
        "高通" to "QCOM",
        "美光" to "MU",
        "甲骨文" to "ORCL",
        "阿里巴巴" to "BABA",
        "阿里" to "BABA",
        "京东" to "JD",
        "拼多多" to "PDD",
        "百度" to "BIDU",
        "网易" to "NTES",
        "蔚来" to "NIO",
        "小鹏" to "XPEV",
        "理想" to "LI",
        "哔哩哔哩" to "BILI",
        "B站" to "BILI",
        "沃尔玛" to "WMT",
        "迪士尼" to "DIS",
        "可口可乐" to "KO",
        "星巴克" to "SBUX",
        "摩根大通" to "JPM",
        "伯克希尔" to "BRKB",
        "波音" to "BA",
        "辉瑞" to "PFE",
        "强生" to "JNJ",
        "万事达" to "MA",
        "维萨" to "V",
        "美国银行" to "BAC",
        "埃克森美孚" to "XOM",
        "雪佛龙" to "CVX",
        "超微电脑" to "SMCI",
        "思科" to "CSCO",
        "戴尔" to "DELL",
        "Circle" to "CRCL",
        "罗宾汉" to "HOOD",
        "索尼" to "SONY",
        "阿斯麦" to "ASML",
        "好市多" to "COST",
        "麦当劳" to "MCD",
        "耐克" to "NKE",
        "百事" to "PEP",
        "礼来" to "LLY",
        "联合健康" to "UNH"
    )

    /** 查询里出现的中/英文别名对应的正股代码（最多 3 个） */
    fun aliasTickers(query: String): Set<String> {
        val q = query.trim().lowercase(Locale.US)
        if (q.length < 2) return emptySet()
        val out = LinkedHashSet<String>()
        for ((alias, ticker) in ALIASES) {
            val a = alias.lowercase(Locale.US)
            if (q == a || (a.length >= 2 && q.contains(a)) || (a.length >= 2 && a.contains(q))) {
                out.add(ticker)
                if (out.size >= 3) break
            }
        }
        return out
    }
}
