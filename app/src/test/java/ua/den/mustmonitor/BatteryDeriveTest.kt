package ua.den.mustmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakePrefs(
    override var batterySignManual: Int = 0,
    override var batterySignLearned: Int = 0,
    override val batteryWh: Int = 3000,
    override val batteryFullVolts: Double = 27.0,
) : BatteryPrefs {
    override var socEnergy = Double.NaN
    override var socEnergyTs = 0L
    override fun saveSocEnergy(soc: Double, ts: Long) {
        socEnergy = soc; socEnergyTs = ts
    }
}

class BatteryDeriveTest {
    private lateinit var prefs: FakePrefs
    private val wall = 1_800_000_000_000L

    @Before fun setUp() {
        Battery.resetState()
        prefs = FakePrefs()
    }

    private fun raw(
        batteryW: Double = 0.0, volts: Double = 26.0, load: Double = 300.0, pv: Double? = 0.0,
        gridW: Double = 0.0, workState: Double = 2.0,
    ) = buildMap {
        put("must_battery_power_watts", batteryW)
        put("must_battery_current_amps", batteryW / volts)
        put("must_battery_voltage_volts", volts)
        put("must_load_power_watts", load)
        put("must_grid_power_watts", gridW)
        put("must_work_state", workState)
        if (pv != null) put("must_pv_power_watts", pv)
    }

    @Test fun defaultSignNegativeMeansCharging() {
        val out = Derive.apply(raw(batteryW = -800.0), prefs, 0, wall, true)
        assertEquals(800.0, out["must_battery_charge_power_watts"]!!, 1e-9)
    }

    @Test fun gridImportIsNegatedRegister() {
        val out = Derive.apply(raw(gridW = -858.0), prefs, 0, wall, true)
        assertEquals(858.0, out["must_grid_import_watts"]!!, 1e-9)
    }

    @Test fun learnsSignOnlyAfterConfirmedDischargeWithoutGridAndPv() {
        // positive register while off-grid, no PV, real load => positive = discharge => charge sign -1
        prefs.batterySignLearned = 1
        repeat(2) { Derive.apply(raw(batteryW = 441.0), prefs, it * 5000L, wall, false) }
        assertEquals(1, prefs.batterySignLearned) // not yet: needs 3 votes
        Derive.apply(raw(batteryW = 441.0), prefs, 10_000, wall, false)
        assertEquals(-1, prefs.batterySignLearned)
    }

    @Test fun doesNotLearnWhenPvUnreadableOrGridOn() {
        repeat(5) { Derive.apply(raw(batteryW = 441.0, pv = null), prefs, it * 5000L, wall, false) }
        repeat(5) { Derive.apply(raw(batteryW = 441.0), prefs, it * 5000L, wall, true) }
        assertEquals(0, prefs.batterySignLearned)
    }

    @Test fun manualSignWins() {
        prefs.batterySignManual = 1
        val out = Derive.apply(raw(batteryW = -800.0), prefs, 0, wall, true)
        assertEquals(-800.0, out["must_battery_charge_power_watts"]!!, 1e-9)
    }

    @Test fun energySocIntegratesDischarge() {
        // start from a saved counter, then discharge 600 W for 30 min in 5 s steps => 300 Wh = 10% of 3 kWh
        prefs.saveSocEnergy(80.0, wall)
        val first = Derive.apply(raw(batteryW = 600.0, volts = 26.0), prefs, 0, wall, false)
        val start = first["must_battery_soc_energy_percent"]!!
        var out = first
        for (i in 1..360) out = Derive.apply(raw(batteryW = 600.0, volts = 26.0), prefs, i * 5000L, wall, false)
        assertEquals(start - 10.0, out["must_battery_soc_energy_percent"]!!, 0.01)
    }

    @Test fun chargingCountsEfficiency() {
        prefs.saveSocEnergy(10.0, wall)
        var out: Map<String, Double> = emptyMap()
        for (i in 0..720) out = Derive.apply(raw(batteryW = -1000.0, volts = 25.0), prefs, i * 5000L, wall, true)
        // 1 h at 1000 W * 0.95 = 950 Wh = 31.67% of 3 kWh
        assertEquals(10.0 + 31.667, out["must_battery_soc_energy_percent"]!!, 0.05)
    }

    @Test fun gapsLongerThanAMinuteAreNotIntegrated() {
        prefs.saveSocEnergy(80.0, wall)
        val a = Derive.apply(raw(batteryW = 600.0), prefs, 0, wall, false)["must_battery_soc_energy_percent"]!!
        val b = Derive.apply(raw(batteryW = 600.0), prefs, 10 * 60_000L, wall, false)["must_battery_soc_energy_percent"]!!
        assertEquals(a, b, 1e-9)
    }

    @Test fun recalibratesToFullAfterHoldingAtFullVoltage() {
        prefs.saveSocEnergy(60.0, wall)
        var out: Map<String, Double> = emptyMap()
        for (i in 0..8) out = Derive.apply(raw(batteryW = -20.0, volts = 27.2), prefs, i * 30_000L, wall, true)
        assertTrue(out["must_battery_soc_energy_percent"]!! < 70) // 4 min: not yet
        for (i in 9..12) out = Derive.apply(raw(batteryW = -20.0, volts = 27.2), prefs, i * 30_000L, wall, true)
        assertEquals(100.0, out["must_battery_soc_energy_percent"]!!, 1e-9)
    }

    @Test fun restoresFreshSavedCounterButNotStale() {
        prefs.saveSocEnergy(42.0, wall - 60_000)
        assertEquals(42.0, Derive.apply(raw(), prefs, 0, wall, false)["must_battery_soc_energy_percent"]!!, 0.01)
        Battery.resetState()
        prefs.saveSocEnergy(42.0, wall - 3 * 3600_000L)
        val out = Derive.apply(raw(volts = 26.0), prefs, 0, wall, false)
        assertEquals(out["must_battery_soc_estimate_percent"]!!, out["must_battery_soc_energy_percent"]!!, 0.01)
    }

    @Test fun runtimeUsesReserveAndAverageLoad() {
        prefs.saveSocEnergy(60.0, wall)
        var out: Map<String, Double> = emptyMap()
        // alternating 200 / 400 W => avg 300 W; usable = (60-10)% of 3000 = 1500 Wh => ~5 h
        for (i in 0..19) out = Derive.apply(raw(batteryW = 300.0, load = if (i % 2 == 0) 200.0 else 400.0), prefs, i * 5000L, wall, false)
        assertEquals(5 * 3600.0, out["must_battery_runtime_seconds"]!!, 0.05 * 3600)
        assertNull(out["must_battery_time_to_full_seconds"])
    }

    @Test fun timeToFullWhenCharging() {
        prefs.saveSocEnergy(50.0, wall)
        val out = Derive.apply(raw(batteryW = -950.0, volts = 26.5), prefs, 0, wall, true)
        // 50% of 3000 = 1500 Wh at 950*0.95 W => ~1.66 h
        assertEquals(1500 / (950 * 0.95) * 3600, out["must_battery_time_to_full_seconds"]!!, 60.0)
        assertNull(out["must_battery_runtime_seconds"])
    }

    @Test fun glitchesAreFiltered() {
        val v = mutableMapOf(
            "must_load_power_watts" to 99999.0,
            "must_inverter_power_watts" to -120.0,
            "must_battery_power_watts" to 32000.0,
            "must_load_percent" to 65000.0,
        )
        Derive.sanitize(v)
        assertTrue(v["must_load_power_watts"]!!.isNaN())
        assertEquals(-120.0, v["must_inverter_power_watts"]!!, 0.0)
        assertTrue(v["must_battery_power_watts"]!!.isNaN())
        assertTrue(v["must_load_percent"]!!.isNaN())
    }

    @Test fun nanBatteryPowerDoesNotBreakSoc() {
        val out = Derive.apply(raw(batteryW = 99999.0), prefs, 0, wall, false)
        assertNull(out["must_battery_charge_power_watts"])
        assertFalse(out.containsKey("must_battery_soc_energy_percent"))
        assertEquals(out["must_battery_soc_estimate_percent"]!!, out["must_battery_soc_percent"]!!, 0.0)
    }

    private fun bypassRaw() = mapOf(
        // real HBP18 registers in Bypass while charging from the grid
        "must_work_state" to 4.0, "must_load_power_watts" to -526.0, "must_load_current_amps" to 2.3,
        "must_grid_power_watts" to -541.0, "must_inverter_power_watts" to -1066.0,
        "must_battery_power_watts" to -988.0, "must_battery_current_amps" to -36.0, "must_battery_voltage_volts" to 27.4,
        "must_grid_voltage_volts" to 229.0, "must_pv_power_watts" to 0.0,
    )

    @Test fun bypassLoadIsTheMagnitudeOfTheNegativeRegister() {
        val out = Derive.apply(bypassRaw(), prefs, 0, wall, true)
        assertEquals(526.0, out["must_load_power_watts"]!!, 0.0)
        assertEquals(0.0, out["must_load_power_unmeasured"]!!, 0.0)
        assertEquals(988.0, out["must_battery_charge_power_watts"]!!, 0.0)
    }

    @Test fun gridImportIsChargerPlusLoad() {
        val out = Derive.apply(bypassRaw(), prefs, 0, wall, true)
        assertEquals(1066.0 + 526.0, out["must_grid_import_watts"]!!, 0.0)
        // second real reading: no load, charger only (the grid register reads -1118 here)
        val out2 = Derive.apply(bypassRaw() + mapOf("must_load_power_watts" to 0.0, "must_load_current_amps" to 0.0,
            "must_inverter_power_watts" to -1100.0, "must_grid_power_watts" to -1118.0), prefs, 0, wall, true)
        assertEquals(1100.0, out2["must_grid_import_watts"]!!, 0.0)
    }

    @Test fun freshInstallWhileChargingWaitsInsteadOfTrustingVoltage() {
        // nothing saved, charging at 1 kW, voltage curve says 100%
        val out = Derive.apply(bypassRaw(), prefs, 0, wall, true)
        assertFalse(out.containsKey("must_battery_soc_energy_percent"))
        // once at rest it starts from voltage
        val rest = Derive.apply(raw(batteryW = -10.0, volts = 26.6), prefs, 5000, wall, true)
        assertTrue(rest.containsKey("must_battery_soc_energy_percent"))
    }

    @Test fun bypassZeroWithCurrentIsUnmeasured() {
        val out = Derive.apply(bypassRaw() + mapOf("must_load_power_watts" to 0.0), prefs, 0, wall, true)
        assertTrue(out["must_load_power_watts"]!!.isNaN())
        assertEquals(1.0, out["must_load_power_unmeasured"]!!, 0.0)
    }

    @Test fun offGridSwitchoverNegativeIsClampedToZero() {
        val out = Derive.apply(raw(load = -51.0, workState = 2.0), prefs, 0, wall, false)
        assertEquals(0.0, out["must_load_power_watts"]!!, 0.0)
    }

    @Test fun gridImportNeverNegative() {
        val out = Derive.apply(raw(gridW = 22.0), prefs, 0, wall, true)
        assertEquals(0.0, out["must_grid_import_watts"]!!, 0.0)
    }

    @Test fun runtimeUsesBatteryDrainNotLoad() {
        // live data: load 100 W, battery gives 143 W (inverter self-consumption)
        prefs.saveSocEnergy(60.0, wall)
        val out = Derive.apply(raw(batteryW = 143.0, load = 100.0), prefs, 0, wall, false)
        assertEquals(0.5 * 3000 / 143.0 * 3600, out["must_battery_runtime_seconds"]!!, 60.0)
    }

    @Test fun staleCounterKeptWhileChargingInsteadOfInflatedVoltage() {
        prefs.saveSocEnergy(74.5, wall - 15 * 3600_000L)
        val out = Derive.apply(raw(batteryW = -900.0, volts = 28.3), prefs, 0, wall, true)
        assertEquals(74.5, out["must_battery_soc_energy_percent"]!!, 0.01)
    }

    @Test fun noBypassEstimateOffGrid() {
        val out = Derive.apply(raw(batteryW = 441.0, load = 401.0, workState = 2.0), prefs, 0, wall, false)
        assertEquals(401.0, out["must_load_power_watts"]!!, 0.0)
        assertEquals(0.0, out["must_load_power_unmeasured"]!!, 0.0)
    }

    @Test fun socEstimateNaN() = assertTrue(Registers.socEstimate(Double.NaN).isNaN())
}
