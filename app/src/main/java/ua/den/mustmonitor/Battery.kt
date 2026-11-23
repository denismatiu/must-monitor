package ua.den.mustmonitor

import kotlin.math.abs

/** Battery settings and persisted counter state; implemented by [Settings], faked in tests. */
interface BatteryPrefs {
    val batterySignManual: Int
    var batterySignLearned: Int
    val batteryWh: Int
    val batteryFullVolts: Double
    val socEnergy: Double
    val socEnergyTs: Long
    fun saveSocEnergy(soc: Double, ts: Long)

    /** Effective sign; until learned, negative = charging (observed on HBP18). */
    val batteryChargeSign: Int
        get() = batterySignManual.takeIf { it != 0 } ?: batterySignLearned.takeIf { it != 0 } ?: -1
}

/**
 * Battery-derived values:
 *  - must_battery_charge_power_watts / _current_amps: positive = charging.
 *    The register sign is learned (no grid + no PV => discharging); on HBP18 negative = charging.
 *  - must_battery_soc_energy_percent: state of charge by counting energy in/out of the battery,
 *    recalibrated to 100% when the battery sits full.
 *  - must_battery_soc_percent: best available SoC (energy if known, else voltage).
 *  - must_battery_runtime_seconds / _time_to_full_seconds: see [estimates]. Runtime uses the recent average
 *    battery discharge power, which is ~15% above the load because the inverter consumes power itself.
 */
object Battery {
    const val CHARGE_EFFICIENCY = 0.95
    const val RESERVE_PERCENT = 10.0
    private const val MAX_STEP_MS = 60_000L
    private const val FULL_HOLD_MS = 5 * 60_000L
    private const val RESTORE_MAX_AGE_MS = 30 * 60_000L
    private const val LOAD_WINDOW_MS = 10 * 60_000L
    private const val LEARN_CONFIRM = 3

    private var soc = Double.NaN
    private var lastUptime = -1L
    private var lastSave = -1L
    private var fullSince = -1L
    private var learnVotes = 0
    private var learnCandidate = 0
    /** Recent battery discharge power (W, >= 0): includes the inverter's own consumption, unlike load. */
    private val drainWindow = ArrayDeque<Pair<Long, Double>>()

    /**
     * @param uptime monotonic ms (SystemClock.elapsedRealtime), used for integration
     * @param wall wall-clock ms, used only to decide whether a saved counter is fresh
     * @param gridOn debounced grid state, null if unknown
     */
    @Synchronized
    fun apply(prefs: BatteryPrefs, v: Map<String, Double>, uptime: Long, wall: Long, gridOn: Boolean?): Map<String, Double> {
        val out = LinkedHashMap(v)
        val voltSoc = v["must_battery_soc_estimate_percent"]
        val p = v["must_battery_power_watts"]?.takeUnless { it.isNaN() }
        var charge: Double? = null
        if (p != null) {
            if (prefs.batterySignManual == 0) learn(prefs, v, p, gridOn)
            val sign = prefs.batteryChargeSign
            charge = p * sign
            out["must_battery_charge_power_watts"] = charge
            v["must_battery_current_amps"]?.takeUnless { it.isNaN() }?.let { out["must_battery_charge_current_amps"] = it * sign }
            if (voltSoc != null && !voltSoc.isNaN()) updateSoc(prefs, voltSoc, charge, v["must_battery_voltage_volts"], uptime, wall)
        }
        if (!soc.isNaN()) out["must_battery_soc_energy_percent"] = soc
        val best = if (!soc.isNaN()) soc else voltSoc?.takeUnless { it.isNaN() }
        if (best != null) out["must_battery_soc_percent"] = best
        return out
    }

    /** Adds runtime / time-to-full; call after the load value is final (Bypass estimate applied). */
    @Synchronized
    fun estimates(prefs: BatteryPrefs, out: MutableMap<String, Double>, uptime: Long) {
        val cap = prefs.batteryWh.toDouble()
        val best = out["must_battery_soc_percent"]
        val charge = out["must_battery_charge_power_watts"]?.takeUnless { it.isNaN() }
        if (charge != null) {
            drainWindow.addLast(uptime to (-charge).coerceAtLeast(0.0))
            while (drainWindow.isNotEmpty() && uptime - drainWindow.first().first > LOAD_WINDOW_MS) drainWindow.removeFirst()
        }
        if (best != null && charge != null) {
            val avgDrain = if (drainWindow.isEmpty()) 0.0 else drainWindow.sumOf { it.second } / drainWindow.size
            if (charge < -10 && avgDrain > 10) {
                val usableWh = (best - RESERVE_PERCENT).coerceAtLeast(0.0) / 100 * cap
                out["must_battery_runtime_seconds"] = usableWh / avgDrain * 3600
            }
            if (charge > 20 && best < 99.5) {
                out["must_battery_time_to_full_seconds"] = (100 - best) / 100 * cap / (charge * CHARGE_EFFICIENCY) * 3600
            }
        }
    }

    private fun updateSoc(prefs: BatteryPrefs, voltSoc: Double, charge: Double, volts: Double?, uptime: Long, wall: Long) {
        val cap = prefs.batteryWh.toDouble()
        if (soc.isNaN()) {
            // Voltage is only meaningful near rest: under charge it reads high, under load low.
            val saved = prefs.socEnergy
            val atRest = abs(charge) < cap * 0.02
            val fresh = !saved.isNaN() && wall - prefs.socEnergyTs in 0 until RESTORE_MAX_AGE_MS
            soc = when {
                fresh -> saved
                atRest -> voltSoc
                !saved.isNaN() -> saved
                // Nothing saved (fresh install) and the battery is charging or under load: voltage would
                // give a wrong start (e.g. 100% while charging). Wait for rest or for the "full" condition.
                else -> return
            }
        } else {
            val dt = uptime - lastUptime
            if (dt in 0..MAX_STEP_MS) {
                val wh = (if (charge > 0) charge * CHARGE_EFFICIENCY else charge) * dt / 3_600_000.0
                soc += wh / cap * 100
            }
        }
        lastUptime = uptime

        // Full: voltage at the configured "full" level and the charger has stopped pushing current.
        if (volts != null && volts >= prefs.batteryFullVolts && abs(charge) < cap * 0.02) {
            if (fullSince < 0) fullSince = uptime
            if (uptime - fullSince >= FULL_HOLD_MS) soc = 100.0
        } else {
            fullSince = -1
        }
        soc = soc.coerceIn(0.0, 100.0)

        if (lastSave < 0 || uptime - lastSave >= 60_000) {
            prefs.saveSocEnergy(soc, wall)
            lastSave = uptime
        }
    }

    private fun learn(prefs: BatteryPrefs, v: Map<String, Double>, p: Double, gridOn: Boolean?) {
        // Only when we are sure the battery is discharging: no grid, PV readable and idle, real load.
        val pv = v["must_pv_power_watts"]
        val load = v["must_load_power_watts"]
        if (gridOn != false || pv == null || pv.isNaN() || pv > 20 || load == null || load.isNaN() || load < 50 || abs(p) < 30) {
            learnVotes = 0
            return
        }
        val candidate = if (p > 0) -1 else 1
        if (candidate != learnCandidate) {
            learnCandidate = candidate
            learnVotes = 0
        }
        if (++learnVotes >= LEARN_CONFIRM && prefs.batterySignLearned != candidate) prefs.batterySignLearned = candidate
    }

    @Synchronized
    fun reset(prefs: BatteryPrefs) {
        soc = Double.NaN
        lastUptime = -1
        fullSince = -1
        prefs.saveSocEnergy(Double.NaN, 0)
    }

    /** For tests: forget all in-memory state. */
    @Synchronized
    fun resetState() {
        soc = Double.NaN
        lastUptime = -1
        lastSave = -1
        fullSince = -1
        learnVotes = 0
        learnCandidate = 0
        drainWindow.clear()
    }
}
