package ua.den.mustmonitor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

object Inverter {
    private const val ACTION_USB_PERMISSION = "ua.den.mustmonitor.USB_PERMISSION"

    val lock = Any()
    private var port: UsbSerialPort? = null
    private var lastPermissionRequest = 0L

    fun ensurePort(ctx: Context): UsbSerialPort? {
        port?.let { return it }
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(um).firstOrNull()
        if (driver == null) {
            State.lastError = "USB serial device not found"
            return null
        }
        if (!um.hasPermission(driver.device)) {
            State.lastError = "no USB permission"
            val now = System.currentTimeMillis()
            if (now - lastPermissionRequest > 60_000) {
                lastPermissionRequest = now
                val pi = PendingIntent.getBroadcast(
                    ctx, 0,
                    Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
                    PendingIntent.FLAG_MUTABLE
                )
                um.requestPermission(driver.device, pi)
            }
            return null
        }
        val conn = um.openDevice(driver.device) ?: run {
            State.lastError = "cannot open USB device"
            return null
        }
        val p = driver.ports[0]
        p.open(conn)
        p.setParameters(Registers.BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        State.device = "%s %04X:%04X".format(
            driver.javaClass.simpleName, driver.device.vendorId, driver.device.productId
        )
        port = p
        return p
    }

    fun closePort() {
        try {
            port?.close()
        } catch (_: Exception) {
        }
        port = null
    }

    fun poll(ctx: Context) {
        synchronized(lock) {
            val p = ensurePort(ctx) ?: return
            val out = LinkedHashMap<String, Double>()

            val inv = Modbus.readHolding(p, Registers.SLAVE, Registers.INV_START, Registers.INV_COUNT)
            Registers.read(Registers.inverter, Registers.inverterEnergy, Registers.INV_START, inv, out)

            try {
                val chg = Modbus.readHolding(p, Registers.SLAVE, Registers.CHG_START, Registers.CHG_COUNT)
                Registers.read(Registers.charger, Registers.chargerEnergy, Registers.CHG_START, chg, out)
                State.chargerError = null
            } catch (e: ModbusException) {
                State.chargerError = e.message
            }

            out["must_battery_soc_estimate_percent"] =
                Registers.socEstimate(out["must_battery_voltage_volts"] ?: 0.0)

            State.values = out
            State.lastOk = System.currentTimeMillis()
            State.lastError = null
        }
    }

    fun raw(addr: Int, count: Int): IntArray {
        synchronized(lock) {
            val p = port ?: throw ModbusException("port not open")
            return Modbus.readHolding(p, Registers.SLAVE, addr, count)
        }
    }
}
