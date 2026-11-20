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

    /** Effective sign, 0 if still unknown. */
    val batteryChargeSign: Int
        get() = batterySignManual.takeIf { it != 0 } ?: batterySignLearned

    val telegramReady: Boolean
        get() = tgEnabled && tgToken.isNotBlank() && tgChatId.isNotBlank()
}
