package ua.den.mustmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.net.Inet4Address
import java.net.NetworkInterface

class MonitorService : Service() {

    companion object {
        const val HTTP_PORT = 8080
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        private const val POLL_MS = 5_000L
        private const val HISTORY_MS = 30_000L
    }

    @Volatile private var running = false
    private var server: WebServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var store: HistoryStore
    private lateinit var grid: GridWatcher
    private lateinit var settings: Settings

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Monitor", NotificationManager.IMPORTANCE_LOW))
        val n = notification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mustmonitor:poll").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mustmonitor:wifi").apply { acquire() }

        settings = Settings(applicationContext)
        Telegram.start(settings)
        EventLog.init(filesDir)
        store = HistoryStore(filesDir)
        State.loadHistory(store.load())
        grid = GridWatcher(applicationContext)

        server = WebServer(applicationContext, settings, HTTP_PORT).also { it.start() }

        running = true
        Thread(::loop, "poller").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        server?.stop()
        synchronized(Inverter.lock) { Inverter.closePort() }
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    private fun loop() {
        var errors = 0
        var lastHistory = 0L
        while (running) {
            try {
                val before = State.lastOk
                Inverter.poll(applicationContext)
                if (State.lastOk != before) {
                    State.batteryWh = settings.batteryWh.toDouble()
                    State.values = Battery.apply(settings, State.values)
                    State.sample(State.values)
                    grid.update(State.values)
                }
                errors = 0
            } catch (e: Exception) {
                State.lastError = e.message ?: e.javaClass.simpleName
                if (++errors >= 3) {
                    synchronized(Inverter.lock) { Inverter.closePort() }
                    errors = 0
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastHistory >= HISTORY_MS) {
                State.takeRow()?.let {
                    State.addHistory(it)
                    store.append(it)
                }
                lastHistory = now
            }
            updateNotification()
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun updateNotification() {
        val v = State.values
        val text = if (State.online()) {
            "~%.0f%% · %.1f V · навантаження %.0f W · мережа %.0f V".format(
                State.soc(v) ?: 0.0,
                v["must_battery_voltage_volts"] ?: 0.0,
                v["must_load_power_watts"] ?: 0.0,
                v["must_grid_voltage_volts"] ?: 0.0,
            )
        } else {
            "Немає даних: ${State.lastError ?: "очікування"}"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Must Monitor · ${ipAddress() ?: "no wifi"}:$HTTP_PORT")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun ipAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
