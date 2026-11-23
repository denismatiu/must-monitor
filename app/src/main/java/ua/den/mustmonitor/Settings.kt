package ua.den.mustmonitor

import android.content.Context

class Settings(ctx: Context) : BatteryPrefs {
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
    override var batterySignManual: Int
        get() = prefs.getInt("battery_sign_manual", 0)
        set(v) = prefs.edit().putInt("battery_sign_manual", v).apply()

    override var batterySignLearned: Int
        get() = prefs.getInt("battery_sign_learned", 0)
        set(v) = prefs.edit().putInt("battery_sign_learned", v).apply()

    override var batteryWh: Int
        get() = prefs.getInt("battery_wh", DEFAULT_BATTERY_WH)
        set(v) = prefs.edit().putInt("battery_wh", v).apply()

    /** Battery voltage at which a battery that no longer takes current counts as 100%. */
    override var batteryFullVolts: Double
        get() = prefs.getFloat("battery_full_volts", DEFAULT_FULL_VOLTS.toFloat()).toDouble()
        set(v) = prefs.edit().putFloat("battery_full_volts", v.toFloat()).apply()

    override val socEnergy: Double
        get() = prefs.getFloat("soc_energy", Float.NaN).toDouble()
    override val socEnergyTs: Long
        get() = prefs.getLong("soc_energy_ts", 0)

    override fun saveSocEnergy(soc: Double, ts: Long) =
        prefs.edit().putFloat("soc_energy", soc.toFloat()).putLong("soc_energy_ts", ts).apply()

    /** Telegram warning when the phone running the app is low and not charging. */
    var phoneLowAlert: Boolean
        get() = prefs.getBoolean("phone_low_alert", false)
        set(v) = prefs.edit().putBoolean("phone_low_alert", v).apply()

    var phoneLowPercent: Int
        get() = prefs.getInt("phone_low_percent", 10)
        set(v) = prefs.edit().putInt("phone_low_percent", v).apply()

    /** Telegram warning when the inverter stops answering for a few minutes. */
    var inverterOfflineAlert: Boolean
        get() = prefs.getBoolean("inverter_offline_alert", true)
        set(v) = prefs.edit().putBoolean("inverter_offline_alert", v).apply()

    /** Language of Telegram messages: "uk" or "en". */
    var tgLang: String
        get() = prefs.getString("tg_lang", "uk") ?: "uk"
        set(v) = prefs.edit().putString("tg_lang", v).apply()

    val telegramReady: Boolean
        get() = tgEnabled && tgToken.isNotBlank() && tgChatId.isNotBlank()

    companion object {
        const val DEFAULT_BATTERY_WH = 3000
        const val DEFAULT_FULL_VOLTS = 27.0
    }
}
