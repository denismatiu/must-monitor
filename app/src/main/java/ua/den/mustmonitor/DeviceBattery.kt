package ua.den.mustmonitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Battery of the phone / tablet running the app (on TV boxes there is usually none).
 * Optionally warns in Telegram when it is low and not charging — e.g. the phone is on the
 * inverter output and the inverter shut down, or the charger came loose.
 */
class DeviceBattery(private val settings: Settings) {
    private var alerted = false

    /** Reads the sticky ACTION_BATTERY_CHANGED broadcast (no permission needed). */
    fun update(ctx: Context) {
        val i: Intent = try {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        } ?: return
        if (!i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
            State.deviceBatteryPercent = null
            return
        }
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val percent = level * 100.0 / scale
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        State.deviceBatteryPercent = percent
        State.deviceCharging = plugged
        check(percent, plugged)
    }

    fun check(percent: Double, plugged: Boolean) {
        val threshold = settings.phoneLowPercent
        if (!alerted && !plugged && percent <= threshold) {
            alerted = true
            if (settings.phoneLowAlert) {
                Telegram.enqueue(
                    L10n.msg(
                        "🪫 Батарея пристрою з Must Monitor: %.0f%%, не заряджається.\nСкоро вимкнеться — моніторинг і сповіщення зупиняться.",
                        "🪫 Must Monitor device battery: %.0f%%, not charging.\nIt will shut down soon — monitoring and notifications will stop.",
                    ).format(java.util.Locale.ROOT, percent)
                )
            }
        } else if (alerted && (plugged || percent >= threshold + 5)) {
            alerted = false
            if (settings.phoneLowAlert && plugged) Telegram.enqueue(
                L10n.msg("🔌 Пристрій з Must Monitor знову заряджається (%.0f%%)", "🔌 Must Monitor device is charging again (%.0f%%)")
                    .format(java.util.Locale.ROOT, percent)
            )
        }
    }
}
