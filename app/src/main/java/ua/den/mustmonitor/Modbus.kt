package ua.den.mustmonitor

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

class ModbusException(msg: String) : IOException(msg)

object Modbus {
    fun crc16(data: ByteArray, len: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc
    }

    fun readHolding(port: UsbSerialPort, slave: Int, addr: Int, count: Int): IntArray {
        val req = byteArrayOf(
            slave.toByte(), 3,
            (addr shr 8).toByte(), addr.toByte(),
            (count shr 8).toByte(), count.toByte(),
            0, 0
        )
        val crc = crc16(req, 6)
        req[6] = (crc and 0xFF).toByte()
        req[7] = (crc shr 8).toByte()

        val tmp = ByteArray(1024)
        while (port.read(tmp, 30) > 0) { }
        port.write(req, 1000)

        val expected = 5 + count * 2
        val resp = ByteArray(expected)
        var n = 0
        val deadline = System.currentTimeMillis() + 1500
        while (n < expected && System.currentTimeMillis() < deadline) {
            val r = port.read(tmp, 200)
            if (r > 0) {
                val c = minOf(r, expected - n)
                System.arraycopy(tmp, 0, resp, n, c)
                n += c
            }
            if (n >= 5 && (resp[1].toInt() and 0x80) != 0) {
                throw ModbusException("modbus exception code ${resp[2].toInt() and 0xFF}")
            }
        }
        if (n < expected) throw ModbusException("timeout: got $n of $expected bytes")
        if ((resp[0].toInt() and 0xFF) != slave || resp[1].toInt() != 3 || (resp[2].toInt() and 0xFF) != count * 2) {
            throw ModbusException("bad response header")
        }
        val c = crc16(resp, expected - 2)
        if ((resp[expected - 2].toInt() and 0xFF) != (c and 0xFF) || (resp[expected - 1].toInt() and 0xFF) != (c shr 8)) {
            throw ModbusException("bad crc")
        }
        return IntArray(count) { i ->
            ((resp[3 + 2 * i].toInt() and 0xFF) shl 8) or (resp[4 + 2 * i].toInt() and 0xFF)
        }
    }
}
