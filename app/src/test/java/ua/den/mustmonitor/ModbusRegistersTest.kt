package ua.den.mustmonitor

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ModbusRegistersTest {
    private fun bytes(vararg i: Int) = ByteArray(i.size) { i[it].toByte() }

    @Test fun crcKnownVector() {
        // 01 03 00 00 00 0A -> C5 CD
        val d = bytes(1, 3, 0, 0, 0, 10)
        assertEquals(0xCDC5, Modbus.crc16(d, 6))
    }

    @Test fun crcEmptyIsInit() = assertEquals(0xFFFF, Modbus.crc16(ByteArray(0), 0))

    @Test fun regSignedUnsigned() {
        assertEquals(65485.0, Reg("a", "", 1).value(65485), 0.0)
        assertEquals(-51.0, Reg("a", "", 1, signed = true).value(65485), 0.0)
        assertEquals(32767.0, Reg("a", "", 1, signed = true).value(32767), 0.0)
        assertEquals(-32768.0, Reg("a", "", 1, signed = true).value(0x8000), 0.0)
        assertEquals(24.5, Reg("a", "", 1, 0.1).value(245), 1e-9)
        assertEquals(-2.5, Reg("a", "", 1, 0.1, true).value(0xFFE7), 1e-9)
    }

    @Test fun registersReadEnergy() {
        val data = IntArray(Registers.INV_COUNT)
        data[25247 - 25201] = 3; data[25247 - 25201 + 1] = 25
        data[25205 - 25201] = 512
        data[25214 - 25201] = 0xFF00 // -256 grid
        val out = HashMap<String, Double>()
        Registers.read(Registers.inverter, Registers.inverterEnergy, Registers.INV_START, data, out)
        assertEquals(3002.5, out["must_battery_discharge_energy_kwh_total"]!!, 1e-9)
        assertEquals(51.2, out["must_battery_voltage_volts"]!!, 1e-9)
        assertEquals(-256.0, out["must_grid_power_watts"]!!, 0.0)
    }

    @Test fun registerAddressesFitBlocks() {
        for (r in Registers.inverter) assertTrue(r.name, r.addr - Registers.INV_START in 0 until Registers.INV_COUNT)
        for (e in Registers.inverterEnergy) assertTrue(e.mwhAddr + 1 - Registers.INV_START < Registers.INV_COUNT)
        for (r in Registers.charger) assertTrue(r.name, r.addr - Registers.CHG_START in 0 until Registers.CHG_COUNT)
        for (e in Registers.chargerEnergy) assertTrue(e.mwhAddr + 1 - Registers.CHG_START < Registers.CHG_COUNT)
        val names = (Registers.inverter.map { it.name } + Registers.charger.map { it.name })
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun socEstimate() {
        assertEquals(0.0, Registers.socEstimate(0.0), 0.0)
        assertEquals(0.0, Registers.socEstimate(20.0), 0.0)
        assertEquals(100.0, Registers.socEstimate(27.2), 0.0)
        assertEquals(100.0, Registers.socEstimate(30.0), 0.0)
        assertEquals(10.0, Registers.socEstimate(24.0), 1e-9)
        assertEquals(5.0, Registers.socEstimate(8 * 2.75), 1e-9)
        assertEquals(50.0, Registers.socEstimate(8 * 3.26), 1e-9)
        assertTrue(Registers.socEstimate(Double.NaN).let { it.isNaN() || it in 0.0..100.0 })
        var prev = -1.0
        var v = 18.0
        while (v < 30) { val s = Registers.socEstimate(v); assertTrue(s >= prev); prev = s; v += 0.05 }
    }

    // --- Modbus.readHolding with a fake port -------------------------------------------------
    private fun fakePort(response: () -> List<ByteArray>, written: MutableList<ByteArray> = ArrayList()): UsbSerialPort {
        val queue = ArrayDeque<ByteArray>()
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(UsbSerialPort::class.java)) { _, m, a ->
            when (m.name) {
                "write" -> { written.add((a!![0] as ByteArray).copyOf()); queue.addAll(response()); null }
                "read" -> {
                    val buf = a!![0] as ByteArray
                    val c = queue.removeFirstOrNull()
                    if (c == null) 0 else { System.arraycopy(c, 0, buf, 0, c.size); c.size }
                }
                else -> null
            }
        } as UsbSerialPort
    }

    private fun frame(slave: Int, vararg words: Int): ByteArray {
        val b = ArrayList<Int>(); b += slave; b += 3; b += words.size * 2
        words.forEach { b += it shr 8; b += it and 0xFF }
        val arr = bytes(*b.toIntArray()).copyOf(b.size + 2)
        val c = Modbus.crc16(arr, b.size)
        arr[b.size] = c.toByte(); arr[b.size + 1] = (c shr 8).toByte()
        return arr
    }

    @Test fun readHoldingOk() {
        val w = ArrayList<ByteArray>()
        val port = fakePort({ listOf(frame(4, 0x1234, 0xFFFF)) }, w)
        assertArrayEquals(intArrayOf(0x1234, 0xFFFF), Modbus.readHolding(port, 4, 25201, 2))
        val req = w[0]
        assertEquals(8, req.size)
        assertEquals(0x62, req[2].toInt() and 0xFF); assertEquals(0x71, req[3].toInt() and 0xFF)
        assertEquals(Modbus.crc16(req, 6), (req[6].toInt() and 0xFF) or ((req[7].toInt() and 0xFF) shl 8))
    }

    @Test fun readHoldingSplitChunks() {
        val f = frame(4, 1, 2, 3)
        val port = fakePort({ listOf(f.copyOfRange(0, 3), f.copyOfRange(3, 7), f.copyOfRange(7, f.size)) })
        assertArrayEquals(intArrayOf(1, 2, 3), Modbus.readHolding(port, 4, 0, 3))
    }

    @Test fun readHoldingBadCrc() {
        val f = frame(4, 1); f[f.size - 1] = (f[f.size - 1] + 1).toByte()
        val port = fakePort({ listOf(f) })
        try { Modbus.readHolding(port, 4, 0, 1); fail() } catch (e: ModbusException) { assertTrue(e.message!!.contains("crc")) }
    }

    @Test fun readHoldingWrongSlave() {
        val port = fakePort({ listOf(frame(5, 1)) })
        try { Modbus.readHolding(port, 4, 0, 1); fail() } catch (e: ModbusException) { assertTrue(e.message!!.contains("header")) }
    }

    @Test fun readHoldingException() {
        val ex = bytes(4, 0x83, 2, 0, 0)
        val port = fakePort({ listOf(ex) })
        val t = System.currentTimeMillis()
        try { Modbus.readHolding(port, 4, 0, 5); fail() } catch (e: ModbusException) { assertTrue(e.message!!, e.message!!.contains("exception")) }
        assertTrue("exception should return fast", System.currentTimeMillis() - t < 1000)
    }
}
