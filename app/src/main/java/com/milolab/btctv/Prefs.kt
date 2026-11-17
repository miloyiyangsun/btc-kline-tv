package com.milolab.btctv

import android.content.Context
import android.content.SharedPreferences
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.IndicatorSettings
import com.milolab.btctv.data.Instrument
import com.milolab.btctv.data.Instruments
import com.milolab.btctv.data.MarketType
import com.milolab.btctv.data.PROVIDER_OKX
import com.milolab.btctv.data.PROVIDERS
import com.milolab.btctv.data.TF
import com.milolab.btctv.data.instrumentFromJson
import com.milolab.btctv.data.instrumentListFromJson
import com.milolab.btctv.data.instrumentListToJson
import com.milolab.btctv.data.instrumentToJson

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("btc_tv", Context.MODE_PRIVATE)

    /**
     * 当前标的。优先读新版完整 JSON；老版本只存了 coin 枚举名，
     * 解析失败或不存在时回退到 BTC，保证升级后不白屏。
     * 老版本还可能存了已下线数据源的美股标的，没有 OKX 交易对时同样回退。
     */
    var instrument: Instrument
        get() {
            val saved = instrumentFromJson(sp.getString(KEY_INSTRUMENT, null))
            if (saved != null &&
                (saved.marketType != MarketType.STOCK || saved.symbols.containsKey(PROVIDER_OKX))
            ) {
                return saved
            }
            return Instruments.fromLegacyName(sp.getString(KEY_LEGACY_COIN, null))
        }
        set(v) = sp.edit().putString(KEY_INSTRUMENT, instrumentToJson(v).toString()).apply()

    /** 最近使用（最新的在前） */
    var recent: List<Instrument>
        get() = instrumentListFromJson(sp.getString(KEY_RECENT, null)).take(RECENT_MAX)
        set(v) = sp.edit()
            .putString(KEY_RECENT, instrumentListToJson(v.distinctBy { it.id }.take(RECENT_MAX)))
            .apply()

    var favorites: List<Instrument>
        get() = instrumentListFromJson(sp.getString(KEY_FAVORITES, null)).take(FAVORITE_MAX)
        set(v) = sp.edit()
            .putString(KEY_FAVORITES, instrumentListToJson(v.distinctBy { it.id }.take(FAVORITE_MAX)))
            .apply()

    var tf: TF
        get() = runCatching { TF.valueOf(sp.getString("tf", TF.M1.name)!!) }.getOrDefault(TF.M1)
        set(v) = sp.edit().putString("tf", v.name).apply()

    /** 已下线的旧数据源读出来当 null，交给 bootstrap 重新选源 */
    var provider: String?
        get() = sp.getString("provider", null)?.takeIf { it in PROVIDERS }
        set(v) = sp.edit().putString("provider", v).apply()

    var style: ChartStyle
        get() = runCatching { ChartStyle.valueOf(sp.getString("style", ChartStyle.CANDLE.name)!!) }
            .getOrDefault(ChartStyle.CANDLE)
        set(v) = sp.edit().putString("style", v.name).apply()

    var flash: Boolean
        get() = sp.getBoolean("flash", true)
        set(v) = sp.edit().putBoolean("flash", v).apply()

    var antiBurn: Boolean
        get() = sp.getBoolean("antburn", true)
        set(v) = sp.edit().putBoolean("antburn", v).apply()

    var showClock: Boolean
        get() = sp.getBoolean("clock", true)
        set(v) = sp.edit().putBoolean("clock", v).apply()

    var showVolume: Boolean
        get() = sp.getBoolean("volume", true)
        set(v) = sp.edit().putBoolean("volume", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autostart", true)
        set(v) = sp.edit().putBoolean("autostart", v).apply()

    /**
     * 指标开关与参数。老版本没有这个 key，读出来就是“全部关闭”，
     * 与升级前干净的主界面一致。
     */
    var indicatorSettings: IndicatorSettings
        get() = IndicatorSettings.decode(sp.getString(KEY_INDICATORS, null))
        set(v) = sp.edit().putString(KEY_INDICATORS, v.encode()).apply()

    private companion object {
        const val KEY_INSTRUMENT = "instrument"
        const val KEY_LEGACY_COIN = "coin"
        const val KEY_RECENT = "recent_instruments"
        const val KEY_FAVORITES = "favorite_instruments"
        const val KEY_INDICATORS = "indicator_settings"
        const val RECENT_MAX = 12
        const val FAVORITE_MAX = 30
    }
}
