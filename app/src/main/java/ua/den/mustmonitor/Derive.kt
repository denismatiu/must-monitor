package ua.den.mustmonitor

import kotlin.math.abs

/**
 * Turns a raw register map into what the app publishes: glitch filtering, grid import,
 * battery values (see [Battery]) and the Bypass load estimate. Pure apart from [Battery]'s counters.
 */
object Derive {
    /** Power readings beyond this are glitches (e.g. unsigned wrap-around during grid switchover). */
    const val MAX_POWER_W = 15_000.0
    private const val MAX_CURRENT_A = 500.0
    private const val MAX_LOAD_PERCENT = 300.0
    private const val WORK_STATE_BYPASS = 4.0

    private const val WORK_STATE_GRID_CHARGING = 6.0

    fun apply(raw: Map<String, Double>, prefs: BatteryPrefs, uptime: Long, wall: Long, gridOn: Boolean?): Map<String, Double> {
        val v = LinkedHashMap(raw)
        sanitize(v)
        v["must_battery_voltage_volts"]?.let { v["must_battery_soc_estimate_percent"] = Registers.socEstimate(it) }
        gridImport(v)
        val out = LinkedHashMap(Battery.apply(prefs, v, uptime, wall, gridOn))
        loadSign(out)
        Battery.estimates(prefs, out, uptime)
        return out
    }

    fun sanitize(v: MutableMap<String, Double>) {
        fun limit(k: String, max: Double, clampNegative: Boolean) {
            val x = v[k] ?: return
            v[k] = when {
                x.isNaN() || abs(x) > max -> Double.NaN
                clampNegative && x < 0 -> 0.0
                else -> x
            }
        }
        limit("must_load_power_watts", MAX_POWER_W, clampNegative = false) // sign handled in loadSign()
        limit("must_load_percent", MAX_LOAD_PERCENT, clampNegative = true)
        limit("must_inverter_power_watts", MAX_POWER_W, clampNegative = false)
        limit("must_grid_power_watts", MAX_POWER_W, clampNegative = false)
        limit("must_battery_power_watts", MAX_POWER_W, clampNegative = false)
        limit("must_battery_current_amps", MAX_CURRENT_A, clampNegative = false)
        limit("must_pv_power_watts", MAX_POWER_W, clampNegative = true)
    }

    /**
     * Power taken from the grid = charger + load passed through. Measured on an HBP18 in Bypass:
     *   load -526, inverter -1066, grid -541   and   load 0, inverter -1100, grid -1118.
     * The inverter bridge runs backwards as the charger (negative inverter power); the grid register is
     * the inverter's net figure (charger minus load), not the total, so it is not used for this.
     * Off-grid nothing is taken from the grid.
     */
    fun gridImport(v: MutableMap<String, Double>) {
        val state = v["must_work_state"]
        val passThrough = state == WORK_STATE_BYPASS || state == WORK_STATE_GRID_CHARGING
        if (!passThrough) {
            v["must_grid_power_watts"]?.takeUnless { it.isNaN() }?.let { v["must_grid_import_watts"] = (-it).coerceAtLeast(0.0) }
            return
        }
        val inv = v["must_inverter_power_watts"]?.takeUnless { it.isNaN() } ?: 0.0
        val load = v["must_load_power_watts"]?.takeUnless { it.isNaN() }?.let { abs(it) } ?: 0.0
        v["must_grid_import_watts"] = (-inv).coerceAtLeast(0.0) + load
    }

    /**
     * The load register is signed by direction: positive when the inverter feeds the load from the battery,
     * negative when grid power passes through to it (Bypass / grid charging). Off-grid, small negatives
     * only appear for a moment around switchover and are clamped to 0.
     */
    fun loadSign(v: MutableMap<String, Double>) {
        val load = v["must_load_power_watts"]
        val state = v["must_work_state"]
        val passThrough = state == WORK_STATE_BYPASS || state == WORK_STATE_GRID_CHARGING
        v["must_load_power_unmeasured"] = 0.0
        if (load == null || load.isNaN()) return
        if (passThrough) {
            v["must_load_power_watts"] = abs(load)
            // A zero reading while current clearly flows means the unit doesn't report it: unknown, not 0 W.
            if (load == 0.0 && (v["must_load_current_amps"] ?: 0.0) > 0.3) {
                v["must_load_power_raw_watts"] = load
                v["must_load_power_watts"] = Double.NaN
                v["must_load_power_unmeasured"] = 1.0
            }
        } else if (load < 0) {
            v["must_load_power_watts"] = 0.0
        }
    }
}
