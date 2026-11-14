package com.milolab.btctv

import android.content.Context
import android.content.SharedPreferences
import com.milolab.btctv.data.ChartStyle
import com.milolab.btctv.data.Coin
import com.milolab.btctv.data.TF

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("btc_tv", Context.MODE_PRIVATE)

    var coin: Coin
        get() = runCatching { Coin.valueOf(sp.getString("coin", Coin.BTC.name)!!) }.getOrDefault(Coin.BTC)
        set(v) = sp.edit().putString("coin", v.name).apply()

    var tf: TF
        get() = runCatching { TF.valueOf(sp.getString("tf", TF.M1.name)!!) }.getOrDefault(TF.M1)
        set(v) = sp.edit().putString("tf", v.name).apply()

    var provider: String?
        get() = sp.getString("provider", null)
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
}
