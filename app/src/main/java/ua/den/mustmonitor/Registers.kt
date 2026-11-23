package ua.den.mustmonitor

data class Reg(
    val name: String,
    val help: String,
    val addr: Int,
    val scale: Double = 1.0,
    val signed: Boolean = false,
) {
    fun value(raw: Int): Double {
        val v = if (signed && raw >= 0x8000) raw - 0x10000 else raw
        return v * scale
    }
}

data class EnergyReg(val name: String, val help: String, val mwhAddr: Int)

object Registers {
    const val SLAVE = 4
    const val BAUD = 19200
    const val BATTERY_CELLS = 8

    const val INV_START = 25201
    const val INV_COUNT = 75
    const val CHG_START = 15201
    const val CHG_COUNT = 21

    val inverter = listOf(
        Reg("must_work_state", "Inverter work state code", 25201),
        Reg("must_battery_voltage_volts", "Battery voltage", 25205, 0.1),
        Reg("must_inverter_voltage_volts", "Inverter output voltage", 25206, 0.1),
        Reg("must_grid_voltage_volts", "Grid voltage", 25207, 0.1),
        Reg("must_bus_voltage_volts", "DC bus voltage", 25208, 0.1),
        Reg("must_inverter_current_amps", "Inverter current", 25210, 0.1),
        Reg("must_grid_current_amps", "Grid current", 25211, 0.1),
        Reg("must_load_current_amps", "Load current", 25212, 0.1),
        Reg("must_inverter_power_watts", "Inverter power", 25213, signed = true),
        Reg("must_grid_power_watts", "Grid power", 25214, signed = true),
        Reg("must_load_power_watts", "Load power", 25215, signed = true),
        Reg("must_load_percent", "Load percent of rated power", 25216, signed = true),
        Reg("must_inverter_frequency_hertz", "Inverter frequency", 25225, 0.01),
        Reg("must_grid_frequency_hertz", "Grid frequency", 25226, 0.01),
        Reg("must_ac_radiator_temp_celsius", "AC radiator temperature", 25233),
        Reg("must_transformer_temp_celsius", "Transformer temperature", 25234),
        Reg("must_dc_radiator_temp_celsius", "DC radiator temperature", 25235),
        Reg("must_battery_power_watts", "Battery power", 25273, signed = true),
        Reg("must_battery_current_amps", "Battery current", 25274, signed = true),
    )

    val inverterEnergy = listOf(
        EnergyReg("must_battery_discharge_energy_kwh_total", "Accumulated battery discharge energy", 25247),
        EnergyReg("must_grid_buy_energy_kwh_total", "Accumulated energy taken from grid", 25249),
        EnergyReg("must_load_energy_kwh_total", "Accumulated load energy", 25253),
    )

    val charger = listOf(
        Reg("must_pv_charger_state", "PV charger work state code", 15201),
        Reg("must_pv_voltage_volts", "PV input voltage", 15205, 0.1),
        Reg("must_pv_charger_current_amps", "PV charger current", 15207, 0.1),
        Reg("must_pv_power_watts", "PV charger power", 15208),
        Reg("must_pv_charger_temp_celsius", "PV charger radiator temperature", 15209),
    )

    val chargerEnergy = listOf(
        EnergyReg("must_pv_energy_kwh_total", "Accumulated PV energy", 15217),
    )

    val workStates = mapOf(
        0 to "PowerOn",
        1 to "SelfTest",
        2 to "OffGrid",
        3 to "GridTie",
        4 to "Bypass",
        5 to "Stop",
        6 to "GridCharging",
    )

    private val socCurve = listOf(
        2.50 to 0.0, 3.00 to 10.0, 3.20 to 20.0, 3.22 to 30.0, 3.25 to 40.0,
        3.26 to 50.0, 3.27 to 60.0, 3.30 to 70.0, 3.32 to 80.0, 3.35 to 90.0, 3.40 to 100.0,
    )

    fun socEstimate(batteryVolts: Double): Double {
        if (batteryVolts.isNaN()) return Double.NaN
        val cell = batteryVolts / BATTERY_CELLS
        if (cell <= socCurve.first().first) return 0.0
        if (cell >= socCurve.last().first) return 100.0
        for (i in 1 until socCurve.size) {
            val (v1, s1) = socCurve[i]
            val (v0, s0) = socCurve[i - 1]
            if (cell <= v1) return s0 + (cell - v0) / (v1 - v0) * (s1 - s0)
        }
        return 100.0
    }

    fun read(regs: List<Reg>, energy: List<EnergyReg>, start: Int, data: IntArray, out: MutableMap<String, Double>) {
        for (r in regs) out[r.name] = r.value(data[r.addr - start])
        for (e in energy) {
            val i = e.mwhAddr - start
            out[e.name] = data[i] * 1000.0 + data[i + 1] * 0.1
        }
    }
}
