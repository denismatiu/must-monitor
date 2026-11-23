package ua.den.mustmonitor

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persisted debounced grid state. */
interface GridStore {
    var on: Boolean?
    var since: Long
    /** Last time the grid state was actually observed (persisted roughly once a minute). */
    var lastSeen: Long
}

class PrefsGridStore(ctx: Context) : GridStore {
    private val prefs = ctx.getSharedPreferences("grid", Context.MODE_PRIVATE)
    override var on: Boolean?
        get() = if (prefs.contains("on")) prefs.getBoolean("on", false) else null
        set(v) {
            if (v == null) prefs.edit().remove("on").apply() else prefs.edit().putBoolean("on", v).apply()
        }
    override var since: Long
        get() = prefs.getLong("since", 0)
        set(v) = prefs.edit().putLong("since", v).apply()
    override var lastSeen: Long
        get() = prefs.getLong("last_seen", 0)
        set(v) = prefs.edit().putLong("last_seen", v).apply()
}

/**
 * Tracks whether the grid is present. Uses hysteresis and requires several consecutive polls
 * to confirm a change, so voltage dips don't produce spam.
 */
class GridWatcher(
    private val store: GridStore,
    /** [unseenSince] > 0 if the grid was not observed between that time and the change (app/inverter offline). */
    private val onChange: (on: Boolean, at: Long, prevSince: Long, unseenSince: Long, values: Map<String, Double>) -> Unit,
    private val clock: () -> Long = Clock::now,
) {
    companion object {
        const val ON_VOLTS = 170.0
        const val OFF_VOLTS = 150.0
        const val CONFIRM_POLLS = 3
        /** A pause in observations longer than this means we don't know when a change really happened. */
        const val GAP_MS = 2 * 60_000L

        /** Production wiring: persist in prefs, log the event, send Telegram. */
        fun create(ctx: Context) = GridWatcher(PrefsGridStore(ctx), { on, at, prev, unseen, v ->
            EventLog.add(at, on)
            Telegram.enqueue(GridMessages.text(on, at, prev, v, Clock.approximate, unseen))
        })
    }

    private var pending = 0
    private var pendingSince = 0L
    private var lastSeen = store.lastSeen
    private var lastSeenSaved = lastSeen
    private var gapFrom = 0L

    init {
        State.gridOn = store.on
        State.gridSince = store.since
    }

    /** Call after each successful poll. */
    fun update(v: Map<String, Double>) {
        val volts = v["must_grid_voltage_volts"] ?: return
        if (volts.isNaN()) return
        val now = clock()
        if (lastSeen > 0 && now - lastSeen > GAP_MS) gapFrom = lastSeen
        lastSeen = now
        if (now - lastSeenSaved >= 60_000) {
            store.lastSeen = now
            lastSeenSaved = now
        }
        val cur = State.gridOn
        if (cur == null) {
            commit(volts > (ON_VOLTS + OFF_VOLTS) / 2, now)
            return
        }
        val seen = if (cur) volts >= OFF_VOLTS else volts > ON_VOLTS
        if (seen == cur) {
            pending = 0
            gapFrom = 0
            return
        }
        if (pending++ == 0) pendingSince = now
        if (pending < CONFIRM_POLLS) return
        pending = 0

        val prevSince = State.gridSince
        val at = pendingSince
        val unseen = gapFrom
        gapFrom = 0
        commit(seen, at)
        onChange(seen, at, prevSince, unseen, v)
    }

    private fun commit(on: Boolean, since: Long) {
        State.gridOn = on
        State.gridSince = since
        store.on = on
        store.since = since
    }
}

object GridMessages {
    fun text(
        on: Boolean, at: Long, prevSince: Long, v: Map<String, Double>,
        approxTime: Boolean = false, unseenSince: Long = 0,
    ): String {
        val fmt = SimpleDateFormat("HH:mm, dd.MM", Locale.ROOT)
        val time = (if (approxTime) "≈" else "") + fmt.format(Date(at))
        // After a monitoring gap the change happened somewhere inside it: no exact time or duration.
        val dur = if (unseenSince == 0L && prevSince in 1 until at) duration(at - prevSince) else null
        val gapNote = if (unseenSince > 0) "\n⚠️ " + L10n.msg(
            "Моніторинг не бачив інвертор з ${fmt.format(Date(unseenSince))} — коли саме це сталося, невідомо",
            "Monitoring could not see the inverter since ${fmt.format(Date(unseenSince))} — the exact time is unknown",
        ) else ""
        val soc = v.num("must_battery_soc_percent")
        val bv = v.num("must_battery_voltage_volts")
        val battery = listOfNotNull(
            soc?.let { "~%.0f%%".format(Locale.ROOT, it) },
            bv?.let { "%.1f V".format(Locale.ROOT, it) },
        ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { "🔋 $it" }
        return buildString {
            if (on) {
                append(if (unseenSince > 0) L10n.msg("🟢 Світло є (помічено о ", "🟢 Grid is on (noticed at ") else L10n.msg("🟢 Світло з'явилось о ", "🟢 Grid is back at ")).append(time)
                if (unseenSince > 0) append(")")
                append(gapNote)
                if (dur != null) append(L10n.msg("\nНе було: ", "\nWas out for: ")).append(dur)
                if (battery != null) append('\n').append(battery)
            } else {
                append(if (unseenSince > 0) L10n.msg("🔴 Світла немає (помічено о ", "🔴 Grid is off (noticed at ") else L10n.msg("🔴 Світло зникло о ", "🔴 Grid lost at ")).append(time)
                if (unseenSince > 0) append(")")
                append(gapNote)
                if (dur != null) append(L10n.msg("\nБуло: ", "\nWas on for: ")).append(dur)
                if (battery != null) append('\n').append(battery)
                v.num("must_load_power_watts")?.let { append(L10n.msg("\n⚡ Навантаження %.0f W", "\n⚡ Load %.0f W").format(Locale.ROOT, it)) }
                val drain = v.num("must_battery_charge_power_watts")?.let { -it }
                val load = listOfNotNull(v.num("must_load_power_watts"), drain).maxOrNull()
                if (soc != null && load != null && load > 10) {
                    val usableWh = (soc - Battery.RESERVE_PERCENT).coerceAtLeast(0.0) / 100 * State.batteryWh
                    append(L10n.msg("\n⏳ Вистачить приблизно на ", "\n⏳ Estimated runtime: ")).append(duration((usableWh / load * 3_600_000).toLong()))
                }
            }
        }
    }

    fun duration(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        val d = m / 1440
        val h = (m % 1440) / 60
        val mm = m % 60
        return when {
            d > 0 -> L10n.msg("$d д $h год $mm хв", "$d d $h h $mm min")
            h > 0 -> L10n.msg("$h год $mm хв", "$h h $mm min")
            else -> L10n.msg("$mm хв", "$mm min")
        }
    }

    private fun Map<String, Double>.num(k: String): Double? = this[k]?.takeUnless { it.isNaN() }
}

/**
 * Warns when the inverter stops answering for a while (switched off, USB unplugged, cable fault):
 * while it is silent, grid notifications cannot work either.
 */
class LinkWatcher(private val enabled: () -> Boolean, private val notify: (String) -> Unit, private val clock: () -> Long = Clock::now) {
    companion object {
        const val OFFLINE_ALERT_MS = 3 * 60_000L
    }

    private var alertedSince = 0L

    /** Call every poll iteration with the time of the last successful read (0 = never). */
    fun update(lastOk: Long) {
        if (lastOk == 0L) return
        val now = clock()
        val fmt = SimpleDateFormat("HH:mm, dd.MM", Locale.ROOT)
        if (alertedSince == 0L && now - lastOk >= OFFLINE_ALERT_MS) {
            alertedSince = lastOk
            if (enabled()) {
                notify(L10n.msg(
                    "⚠️ Інвертор не відповідає з ${fmt.format(Date(lastOk))}\nВимкнений або від'єднано USB. Поки зв'язку немає, сповіщень про світло не буде.",
                    "⚠️ Inverter not responding since ${fmt.format(Date(lastOk))}\nSwitched off or USB unplugged. No grid notifications until it is back.",
                ))
            }
        } else if (alertedSince != 0L && now - lastOk < OFFLINE_ALERT_MS) {
            if (enabled()) notify(L10n.msg("✅ Зв'язок з інвертором відновлено (не було ", "✅ Inverter is responding again (silent for ") + GridMessages.duration(lastOk - alertedSince) + ")")
            alertedSince = 0L
        }
    }
}
