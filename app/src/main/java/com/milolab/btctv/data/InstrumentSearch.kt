package com.milolab.btctv.data

/**
 * 标的目录的纯本地搜索评分：精确/前缀/包含/子序列，另加中文别名。
 * 同一只股票的永续与代币现货得分相同时，永续排前面。
 */
object InstrumentSearch {

    fun search(query: String, source: List<Instrument>, limit: Int = 80): List<Instrument> {
        val q = query.trim().uppercase()
        if (q.isEmpty()) return emptyList()
        val aliases = StockUniverse.aliasTickers(query)
        return source.asSequence()
            .mapNotNull { inst -> score(inst, q, aliases)?.let { inst to it } }
            .sortedWith(
                compareByDescending<Pair<Instrument, Int>> { it.second }
                    .thenBy { kindRank(it.first) }
                    .thenBy { it.first.baseCurrency }
                    .thenBy { it.first.id }
            )
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun kindRank(inst: Instrument): Int = when (inst.stockKind) {
        StockKind.TOKENIZED -> 1
        else -> 0
    }

    private fun score(inst: Instrument, q: String, aliases: Set<String>): Int? {
        val base = inst.baseCurrency.uppercase()
        val id = inst.id.uppercase()
        val name = inst.displayName.uppercase()
        val company = inst.name.uppercase()
        val aliasHit = inst.marketType == MarketType.STOCK && base in aliases
        return when {
            base == q || id == q -> 100
            aliasHit -> 96
            base.startsWith(q) -> 90
            id.startsWith(q) -> 80
            name.startsWith(q) -> 75
            company.startsWith(q) -> 72
            base.contains(q) -> 60
            id.contains(q) -> 50
            name.contains(q) -> 45
            company.contains(q) -> 42
            isSubsequence(q, base) -> 30
            isSubsequence(q, id) -> 20
            else -> null
        }
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        var i = 0
        for (c in haystack) {
            if (i < needle.length && c == needle[i]) i++
            if (i == needle.length) return true
        }
        return i == needle.length
    }
}
