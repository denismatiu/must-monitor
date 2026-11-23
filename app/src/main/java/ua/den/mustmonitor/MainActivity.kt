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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
        } else if (Build.VERSION.SDK_INT < 29) {
            // for the auto backup into Download/MustMonitor (survives reinstalling the app)
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
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
                text = L10n.pick(
                    L10n.device,
                    "Must Monitor працює.\n\nWebView на цьому пристрої недоступний — відкрийте дашборд " +
                        "з телефону чи комп'ютера: http://<IP цього пристрою>:${MonitorService.HTTP_PORT}/\n" +
                        "(IP видно в сповіщенні або в налаштуваннях мережі)",
                    "Must Monitor is running.\n\nWebView is not available on this device — open the dashboard " +
                        "from a phone or computer: http://<this device's IP>:${MonitorService.HTTP_PORT}/\n" +
                        "(the IP is shown in the notification or in the network settings)",
                )
            })
            return
        }
        web = w
        web.settings.javaScriptEnabled = true
        web.webViewClient = object : WebViewClient() {
            // The service may not be listening yet right after start: retry instead of showing an error page.
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) view.postDelayed({ view.reload() }, 2_000)
            }
        }
        setContentView(web)
        web.postDelayed({ web.loadUrl("http://127.0.0.1:${MonitorService.HTTP_PORT}/") }, 1000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startForegroundService(Intent(this, MonitorService::class.java))
    }
}
