package ua.den.mustmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled", "BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(Intent(this, MonitorService::class.java))

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            // TV boxes often have no screen for this request
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
            }
        }

        val w = try {
            WebView(this)
        } catch (_: Exception) {
            null // no WebView provider (common on cheap AOSP boxes)
        }
        if (w == null) {
            setContentView(TextView(this).apply {
                textSize = 20f
                setPadding(48, 48, 48, 48)
                text = "Must Monitor працює.\n\nWebView на цьому пристрої недоступний — відкрийте дашборд " +
                    "з телефону чи комп'ютера: http://<IP цього пристрою>:${MonitorService.HTTP_PORT}/\n" +
                    "(IP видно в сповіщенні або в налаштуваннях мережі)"
            })
            return
        }
        web = w
        web.settings.javaScriptEnabled = true
        web.webViewClient = WebViewClient()
        setContentView(web)
        web.postDelayed({ web.loadUrl("http://127.0.0.1:${MonitorService.HTTP_PORT}/") }, 1000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startForegroundService(Intent(this, MonitorService::class.java))
    }
}
