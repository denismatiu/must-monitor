package ua.den.mustmonitor

import android.content.Context

class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var tgToken: String
        get() = prefs.getString("tg_token", "") ?: ""
        set(v) = prefs.edit().putString("tg_token", v.trim()).apply()

    var tgChatId: String
        get() = prefs.getString("tg_chat", "") ?: ""
        set(v) = prefs.edit().putString("tg_chat", v.trim()).apply()

    var tgEnabled: Boolean
        get() = prefs.getBoolean("tg_enabled", true)
        set(v) = prefs.edit().putBoolean("tg_enabled", v).apply()

    /** 1 = positive register value means charging, -1 = negative means charging, 0 = auto. */
    var batterySignManual: Int
        get() = prefs.getInt("battery_sign_manual", 0)
        set(v) = prefs.edit().putInt("battery_sign_manual", v).apply()

    var batterySignLearned: Int
        get() = prefs.getInt("battery_sign_learned", 0)
        set(v) = prefs.edit().putInt("battery_sign_learned", v).apply()

    /** Effective sign; until learned, negative = charging (observed on HBP18). */
    val batteryChargeSign: Int
        get() = batterySignManual.takeIf { it != 0 } ?: batterySignLearned.takeIf { it != 0 } ?: -1

    var batteryWh: Int
        get() = prefs.getInt("battery_wh", 3000)
        set(v) = prefs.edit().putInt("battery_wh", v).apply()

    val socEnergy: Double
        get() = prefs.getFloat("soc_energy", Float.NaN).toDouble()
    val socEnergyTs: Long
        get() = prefs.getLong("soc_energy_ts", 0)

    fun saveSocEnergy(soc: Double, ts: Long) =
        prefs.edit().putFloat("soc_energy", soc.toFloat()).putLong("soc_energy_ts", ts).apply()

    val telegramReady: Boolean
        get() = tgEnabled && tgToken.isNotBlank() && tgChatId.isNotBlank()
}
