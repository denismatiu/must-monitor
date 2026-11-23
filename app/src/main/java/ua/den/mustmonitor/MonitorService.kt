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
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface

class MonitorService : Service() {

    companion object {
        const val HTTP_PORT = 8080
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        private const val POLL_MS = 5_000L
        private const val HISTORY_MS = 30_000L
        private const val SERVER_RETRY_MS = 30_000L
        private const val BACKUP_MS = 3_600_000L
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var server: WebServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var store: HistoryStore? = null
    private var grid: GridWatcher? = null
    private lateinit var settings: Settings
    private lateinit var device: DeviceBattery
    private lateinit var link: LinkWatcher

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Monitor", NotificationManager.IMPORTANCE_LOW))
        val n = notification("Starting…")
        guard("foreground") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }
        guard("locks") {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mustmonitor:poll").apply { acquire() }
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mustmonitor:wifi").apply { acquire() }
        }

        // Each subsystem is independent: one failing must not stop polling.
        settings = Settings(applicationContext)
        L10n.messages = settings.tgLang
        device = DeviceBattery(settings)
        link = LinkWatcher({ settings.inverterOfflineAlert }, Telegram::enqueue)
        guard("clock") { Clock.init(applicationContext) }
        guard("telegram") { Telegram.start(settings, filesDir) }
        guard("events") { EventLog.init(filesDir) }
        guard("history") {
            store = HistoryStore(filesDir).also { State.loadHistory(it.load()); Backup.store = it }
        }
        guard("grid") { grid = GridWatcher.create(applicationContext) }

        running = true
        thread = Thread(::loop, "poller").also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        thread?.let {
            it.interrupt()
            try { it.join(2_000) } catch (_: InterruptedException) {}
        }
        thread = null
        server?.stop()
        server = null
        synchronized(Inverter.lock) { Inverter.closePort() }
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    /** First auto backup ~2 minutes after start (so it contains data), then hourly. */
    private fun up0() = SystemClock.elapsedRealtime() - BACKUP_MS + 120_000

    private fun guard(name: String, block: () -> Unit) {
        try {
            block()
            State.startupErrors.remove(name)
        } catch (e: Throwable) {
            State.startupErrors[name] = e.message ?: e.javaClass.simpleName
        }
    }

    private fun loop() {
        var errors = 0
        var lastHistory = SystemClock.elapsedRealtime()
        var lastClockSave = 0L
        var lastServerTry = -SERVER_RETRY_MS
        var lastBackup = up0()
        while (running) {
            val up = SystemClock.elapsedRealtime()
            try {
                if (server == null && up - lastServerTry >= SERVER_RETRY_MS) {
                    lastServerTry = up
                    guard("web server") { server = WebServer(applicationContext, settings, HTTP_PORT).also { it.start() } }
                }

                try {
                    val raw = Inverter.poll(applicationContext)
                    if (raw != null) {
                        State.batteryWh = settings.batteryWh.toDouble()
                        val v = Derive.apply(raw, settings, up, Clock.now(), State.gridOn)
                        State.values = v
                        State.lastOk = Clock.now()
                        State.lastError = null
                        State.sample(v)
                        grid?.update(v)
                    }
                    errors = 0
                } catch (e: Exception) {
                    State.lastError = e.message ?: e.javaClass.simpleName
                    if (++errors >= 3) {
                        synchronized(Inverter.lock) { Inverter.closePort() }
                        errors = 0
                    }
                }

                device.update(applicationContext)
                link.update(State.lastOk)

                if (up - lastHistory >= HISTORY_MS) {
                    State.takeRow()?.let {
                        State.addHistory(it)
                        store?.append(it)
                    }
                    lastHistory = up
                }
                // Auto backup to Download/MustMonitor: hourly, and shortly after settings change.
                if (up - lastBackup >= BACKUP_MS || (Backup.dirty && up - lastBackup >= 60_000)) {
                    Backup.dirty = false
                    Backup.writeAuto(applicationContext, settings)
                    lastBackup = up
                }
                if (up - lastClockSave >= 60_000) {
                    Clock.save()
                    lastClockSave = up
                }
                updateNotification()
            } catch (e: Throwable) {
                // Never let one bad iteration (OOM, unexpected bug) kill the poller silently.
                State.lastError = "poller: " + (e.message ?: e.javaClass.simpleName)
            }
            if (!running) break
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
            L10n.pick(L10n.device, "~%.0f%% · %.1f V · навантаження %.0f W · мережа %.0f V", "~%.0f%% · %.1f V · load %.0f W · grid %.0f V").format(
                State.soc(v) ?: 0.0,
                v["must_battery_voltage_volts"] ?: 0.0,
                v["must_load_power_watts"]?.takeUnless { it.isNaN() } ?: 0.0,
                v["must_grid_voltage_volts"] ?: 0.0,
            )
        } else {
            L10n.pick(L10n.device, "Немає даних: ", "No data: ") + (State.lastError ?: L10n.pick(L10n.device, "очікування", "waiting"))
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
