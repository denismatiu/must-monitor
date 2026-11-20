package ua.den.mustmonitor

import kotlin.math.abs

/**
 * The sign of the battery power/current registers is not documented for MUST,
 * so we learn it: with no grid and no PV, the battery can only be discharging.
 * Adds must_battery_charge_power_watts / must_battery_charge_current_amps
 * (positive = charging, negative = discharging) once the sign is known.
 */
object Battery {
    fun apply(settings: Settings, v: Map<String, Double>): Map<String, Double> {
        val p = v["must_battery_power_watts"] ?: return v
        if (settings.batterySignManual == 0) learn(settings, v, p)
        val sign = settings.batteryChargeSign
        if (sign == 0) return v
        val out = LinkedHashMap(v)
        out["must_battery_charge_power_watts"] = p * sign
        v["must_battery_current_amps"]?.let { out["must_battery_charge_current_amps"] = it * sign }
        return out
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
