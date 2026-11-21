package ua.den.mustmonitor

import kotlin.math.abs

/**
 * Derived battery values:
 *  - must_battery_charge_power_watts / _current_amps: positive = charging.
 *    The register sign is learned (no grid + no PV => discharging); on HBP18 negative = charging.
 *  - must_battery_soc_energy_percent: state of charge by counting energy in/out of the battery
 *    (capacity from settings), recalibrated to 100% when the battery sits full.
 *  - must_grid_import_watts: power taken from the grid (the register is negative on import).
 */
object Battery {
    private const val CHARGE_EFFICIENCY = 0.95
    private const val MAX_STEP_S = 60.0
    private const val FULL_HOLD_MS = 5 * 60_000L
    private const val RESTORE_MAX_AGE_MS = 30 * 60_000L

    private var soc = Double.NaN
    private var lastTs = 0L
    private var lastSave = 0L
    private var fullSince = 0L

    @Synchronized
    fun apply(settings: Settings, v: Map<String, Double>): Map<String, Double> {
        val out = LinkedHashMap(v)
        v["must_grid_power_watts"]?.let { out["must_grid_import_watts"] = -it }
        val p = v["must_battery_power_watts"] ?: return out
        if (settings.batterySignManual == 0) learn(settings, v, p)
        val sign = settings.batteryChargeSign
        val charge = p * sign
        out["must_battery_charge_power_watts"] = charge
        v["must_battery_current_amps"]?.let { out["must_battery_charge_current_amps"] = it * sign }

        updateSoc(settings, v, charge)
        if (!soc.isNaN()) out["must_battery_soc_energy_percent"] = soc
        return out
    }

    private fun updateSoc(settings: Settings, v: Map<String, Double>, charge: Double) {
        val now = System.currentTimeMillis()
        val cap = settings.batteryWh.toDouble()
        val voltSoc = v["must_battery_soc_estimate_percent"] ?: return

        if (soc.isNaN()) {
            val saved = settings.socEnergy
            soc = if (!saved.isNaN() && now - settings.socEnergyTs < RESTORE_MAX_AGE_MS) saved else voltSoc
        } else {
            val dt = (now - lastTs) / 1000.0
            if (dt in 0.0..MAX_STEP_S) {
                val wh = (if (charge > 0) charge * CHARGE_EFFICIENCY else charge) * dt / 3600
                soc += wh / cap * 100
            }
        }
        lastTs = now

        // Full: voltage at the top and the charger has stopped pushing current.
        if (voltSoc >= 99.0 && abs(charge) < cap * 0.02) {
            if (fullSince == 0L) fullSince = now
            if (now - fullSince >= FULL_HOLD_MS) soc = 100.0
        } else {
            fullSince = 0L
        }
        soc = soc.coerceIn(0.0, 100.0)

        if (now - lastSave >= 60_000) {
            settings.saveSocEnergy(soc, now)
            lastSave = now
        }
    }

    @Synchronized
    fun reset(settings: Settings) {
        soc = Double.NaN
        settings.saveSocEnergy(Double.NaN, 0)
    }

    private fun learn(settings: Settings, v: Map<String, Double>, p: Double) {
        if (State.gridOn != false) return
        val pv = v["must_pv_power_watts"] ?: 0.0
        val load = v["must_load_power_watts"] ?: 0.0
        if (pv > 20 || load < 50 || abs(p) < 30) return
        // discharging right now, so the opposite sign means charging
        val learned = if (p > 0) -1 else 1
        if (settings.batterySignLearned != learned) settings.batterySignLearned = learned
    }
}
