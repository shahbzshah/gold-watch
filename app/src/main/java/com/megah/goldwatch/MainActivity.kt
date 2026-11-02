package com.megah.goldwatch

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button

/**
 * Gold Watch shell: the live gold-signals dashboard in a WebView,
 * plus a reload button. That is the whole UI — no settings, no key
 * field, nothing else.
 *
 * The notification watcher is ALWAYS ON and has no stop control in
 * this UI — that is deliberate (an accidental tap switched the
 * Crash Watch watcher off once). It auto-starts on every launch
 * once the notification permission is dealt with, and restarts on
 * boot. The only ways to stop it afterwards are force-stopping the
 * app in Android Settings or uninstalling.
 *
 * The gold bot's /api/state endpoint is public — the web dashboard
 * already polls it with no key — so this app ships no key at all.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.wvDash)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.loadUrl(DASH_URL)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            web.reload()
        }
        ensureWatcher()
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions,
            grantResults)
        // The service runs regardless: without the permission the
        // system simply drops its notifications, and they start
        // flowing the moment the permission is granted in Settings.
        if (requestCode == 7) ensureWatcherStarted()
    }

    private fun ensureWatcher() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        ensureWatcherStarted()
    }

    private fun ensureWatcherStarted() {
        WatchService.start(this)
    }

    companion object {
        const val DASH_URL = "https://gold-signals-bot-pjyn.onrender.com"
    }
}
