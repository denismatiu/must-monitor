package ua.den.mustmonitor

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks whether the grid is present and fires Telegram messages on changes.
 * Uses hysteresis and requires several consecutive polls to confirm a change,
 * so voltage dips don't produce spam.
 */
class GridWatcher(ctx: Context) {
    companion object {
        const val ON_VOLTS = 170.0
        const val OFF_VOLTS = 150.0
        const val CONFIRM_POLLS = 3
    }

    private val prefs = ctx.getSharedPreferences("grid", Context.MODE_PRIVATE)
    private var pending = 0
    private var pendingSince = 0L

    init {
        if (prefs.contains("on")) {
            State.gridOn = prefs.getBoolean("on", false)
            State.gridSince = prefs.getLong("since", 0)
        }
    }

    /** Call after each successful poll. */
    fun update(v: Map<String, Double>) {
        val volts = v["must_grid_voltage_volts"] ?: return
        if (volts.isNaN()) return
        val now = System.currentTimeMillis()
        val cur = State.gridOn
        if (cur == null) {
            commit(volts > (ON_VOLTS + OFF_VOLTS) / 2, now)
            return
        }
        val seen = if (cur) volts >= OFF_VOLTS else volts > ON_VOLTS
        if (seen == cur) {
            pending = 0
            return
        }
        if (pending++ == 0) pendingSince = now
        if (pending < CONFIRM_POLLS) return
        pending = 0

        val prevSince = State.gridSince
        val at = pendingSince
        commit(seen, at)
        EventLog.add(at, seen)
        Telegram.enqueue(message(seen, at, prevSince, v))
    }

    private fun commit(on: Boolean, since: Long) {
        State.gridOn = on
        State.gridSince = since
        prefs.edit().putBoolean("on", on).putLong("since", since).apply()
    }

    private fun message(on: Boolean, at: Long, prevSince: Long, v: Map<String, Double>): String {
        val time = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(at))
        val dur = if (prevSince > 0) duration(at - prevSince) else null
        val soc = v["must_battery_soc_estimate_percent"] ?: 0.0
        val bv = v["must_battery_voltage_volts"] ?: 0.0
        val load = v["must_load_power_watts"] ?: 0.0
        val battery = "🔋 ~%.0f%% · %.1f V".format(Locale.ROOT, soc, bv)
        return if (on) {
            buildString {
                append("🟢 Світло з'явилось о ").append(time)
                if (dur != null) append("\nНе було: ").append(dur)
                append('\n').append(battery)
            }
        } else {
            buildString {
                append("🔴 Світло зникло о ").append(time)
                if (dur != null) append("\nБуло: ").append(dur)
                append('\n').append(battery)
                append("\n⚡ Навантаження %.0f W".format(Locale.ROOT, load))
                if (load > 10) {
                    val hours = soc / 100 * Registers.BATTERY_WH / load
                    append("\n⏳ Вистачить приблизно на ").append(duration((hours * 3600_000).toLong()))
                }
            }
        }
    }

    private fun duration(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        val d = m / 1440
        val h = (m % 1440) / 60
        val mm = m % 60
        return when {
            d > 0 -> "$d д $h год $mm хв"
            h > 0 -> "$h год $mm хв"
            else -> "$mm хв"
        }
    }
}
