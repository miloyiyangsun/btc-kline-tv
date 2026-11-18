import com.milolab.btctv.data.overview.CoinGeckoStablecoinSource
import com.milolab.btctv.data.overview.CoinPaprikaGlobalSource
import com.milolab.btctv.data.overview.MarketOverview
import com.milolab.btctv.data.overview.OverviewRepository
import com.milolab.btctv.data.overview.defaultOverviewHttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 只读探针：用真实网络跑一遍 OverviewRepository，打印 BTCD / TOTAL / 稳定币市值与占比。
 * 不写 app 目录，缓存落在系统临时目录。
 */
fun main() = runBlocking {
    val cacheDir = File(System.getProperty("java.io.tmpdir"), "btctv-overview-probe")
    val cacheFile = File(cacheDir, "overview_cache.json")
    val repo = OverviewRepository.withDefaults(cacheFile)

    println("=== 1) 首次 refresh ===")
    val t0 = System.currentTimeMillis()
    val first = repo.refresh()
    println("耗时 ${System.currentTimeMillis() - t0} ms")
    printOverview(first)

    println()
    println("=== 2) 立刻再 refresh（TTL 120s 内应命中缓存） ===")
    val t1 = System.currentTimeMillis()
    val second = repo.refresh()
    println("耗时 ${System.currentTimeMillis() - t1} ms")
    printOverview(second)

    println()
    println("=== 3) 缓存文件 ===")
    println("path = ${cacheFile.absolutePath}")
    println("exists = ${cacheFile.exists()}  size = ${if (cacheFile.exists()) cacheFile.length() else 0} bytes")

    println()
    println("=== 4) 历史序列 ===")
    if (second != null) {
        println("稳定币总市值历史(DefiLlama 30d) = ${second.stablecoinMarketCapHistory.size} 点")
        second.stablecoinMarketCapHistory.takeLast(3).forEach { p ->
            println("  ${fmtTime(p.t)}  ${usd(p.v)}")
        }
        println("本地 BTCD 采样 = ${second.btcDominanceHistory.size} 点  ${second.btcDominanceHistory.takeLast(2)}")
        println("本地 TOTAL 采样 = ${second.totalMarketCapHistory.size} 点  ${second.totalMarketCapHistory.takeLast(2)}")
    }
    println()
    println("=== 5) 备源直连对比 ===")
    val http = defaultOverviewHttpClient()
    val nowMs = System.currentTimeMillis()
    val paprika = runCatching { CoinPaprikaGlobalSource(http).fetch(nowMs) }
    println("CoinPaprika global => " + paprika.fold(
        onSuccess = {
            "TOTAL ${usd(it.totalMarketCapUsd)}  BTCD ${pct(it.btcDominance)}  " +
                "24h ${pct(it.change24hPct)}  vol ${usd(it.totalVolume24hUsd)}"
        },
        onFailure = { "FAIL ${it.message}" }
    ))
    val cgCat = runCatching { CoinGeckoStablecoinSource(http).fetch(nowMs) }
    println("CoinGecko categories => " + cgCat.fold(
        onSuccess = { "stablecoin mcap ${usd(it.marketCapUsd)}" },
        onFailure = { "FAIL ${it.message}" }
    ))

    println()
    println("PROBE DONE")
}

private fun printOverview(o: MarketOverview?) {
    if (o == null) {
        println("overview = null")
        return
    }
    println("source            = ${o.source}")
    println("stale             = ${o.stale}")
    println("BTCD              = ${pct(o.btcDominance)}")
    println("ETH dominance     = ${pct(o.ethDominance)}")
    println("TOTAL mcap        = ${usd(o.totalMarketCapUsd)}")
    println("TOTAL 24h change  = ${pct(o.totalChange24hPct)}")
    println("TOTAL 24h volume  = ${usd(o.totalVolume24hUsd)}")
    println("stablecoin mcap   = ${usd(o.stablecoinMarketCapUsd)}")
    println("stablecoin dom.   = ${pct(o.stablecoinDominancePct)}")
    println("global updatedAt  = ${fmtMs(o.globalUpdatedAtMs)}")
    println("stable updatedAt  = ${fmtMs(o.stablecoinUpdatedAtMs)}")
    println("fetchedAt         = ${fmtMs(o.fetchedAtMs)}")
}

private fun pct(v: Double?): String = if (v == null || v.isNaN()) "n/a" else String.format(Locale.US, "%.2f%%", v)

private fun usd(v: Double?): String = when {
    v == null || v.isNaN() -> "n/a"
    v >= 1e12 -> String.format(Locale.US, "$%.3f T", v / 1e12)
    v >= 1e9 -> String.format(Locale.US, "$%.2f B", v / 1e9)
    v >= 1e6 -> String.format(Locale.US, "$%.2f M", v / 1e6)
    else -> String.format(Locale.US, "$%.0f", v)
}

private fun fmtTime(sec: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(sec * 1000L))

private fun fmtMs(ms: Long?): String =
    if (ms == null || ms <= 0) "n/a" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
