package ua.den.mustmonitor

object State {
    @Volatile var values: Map<String, Double> = emptyMap()
    @Volatile var lastOk: Long = 0
    @Volatile var lastError: String? = null
    @Volatile var chargerError: String? = null
    @Volatile var device: String? = null

    /** null until the first reading ever. */
    @Volatile var gridOn: Boolean? = null
    @Volatile var gridSince: Long = 0

    const val HISTORY_RETENTION_MS = 24 * 3600_000L
    const val HISTORY_MAX = 3200

    private enum class Agg { AVG, MAX }
    private class Col(val name: String, val src: String, val agg: Agg)

    /** Each history row aggregates all polls since the previous row. */
    private val cols = listOf(
        Col("must_load_power_watts", "must_load_power_watts", Agg.AVG),
        Col("must_load_power_max_watts", "must_load_power_watts", Agg.MAX),
        Col("must_pv_power_watts", "must_pv_power_watts", Agg.AVG),
        Col("must_battery_power_watts", "must_battery_power_watts", Agg.AVG),
        Col("must_battery_charge_power_watts", "must_battery_charge_power_watts", Agg.AVG),
        Col("must_battery_charge_current_amps", "must_battery_charge_current_amps", Agg.AVG),
        Col("must_battery_voltage_volts", "must_battery_voltage_volts", Agg.AVG),
        Col("must_grid_voltage_volts", "must_grid_voltage_volts", Agg.AVG),
        Col("must_battery_soc_estimate_percent", "must_battery_soc_estimate_percent", Agg.AVG),
    )
    val historyKeys = cols.map { it.name }

    private val acc = DoubleArray(cols.size)
    private val cnt = IntArray(cols.size)
    private val history = ArrayDeque<DoubleArray>()

    fun online(): Boolean = System.currentTimeMillis() - lastOk < 30_000

    fun sample(v: Map<String, Double>) = synchronized(acc) {
        cols.forEachIndexed { i, c ->
            val x = v[c.src] ?: return@forEachIndexed
            if (x.isNaN()) return@forEachIndexed
            acc[i] = when {
                cnt[i] == 0 -> x
                c.agg == Agg.MAX -> maxOf(acc[i], x)
                else -> acc[i] + x
            }
            cnt[i]++
        }
    }

    /** Builds a row from the samples collected so far and resets them; null if nothing was sampled. */
    fun takeRow(): DoubleArray? = synchronized(acc) {
        if (cnt.all { it == 0 }) return null
        val row = DoubleArray(cols.size + 1)
        row[0] = (System.currentTimeMillis() / 1000).toDouble()
        cols.forEachIndexed { i, c ->
            row[i + 1] = when {
                cnt[i] == 0 -> Double.NaN
                c.agg == Agg.AVG -> acc[i] / cnt[i]
                else -> acc[i]
            }
            acc[i] = 0.0
            cnt[i] = 0
        }
        row
    }

    fun addHistory(row: DoubleArray) = synchronized(history) {
        history.addLast(row)
        trim()
    }

    fun loadHistory(rows: List<DoubleArray>) = synchronized(history) {
        history.clear()
        history.addAll(rows.sortedBy { it[0] })
        trim()
    }

    private fun trim() {
        val cutoff = (System.currentTimeMillis() - HISTORY_RETENTION_MS) / 1000.0
        while (history.isNotEmpty() && (history.first()[0] < cutoff || history.size > HISTORY_MAX)) {
            history.removeFirst()
        }
    }

    fun historySnapshot(): List<DoubleArray> = synchronized(history) { history.toList() }
}
