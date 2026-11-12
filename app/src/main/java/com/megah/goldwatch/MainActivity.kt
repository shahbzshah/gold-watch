package com.megah.goldwatch

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView

/**
 * Gold Watch v1.1.0 — the bell screen. Native Views only, no
 * WebView: the Phil+RUDY dashboard is gone, the app is only the
 * 5m market-shift bell.
 *
 *   - big status line: WAITING / ARMED SELL / ARMED BUY / latest
 *     SHIFT + time (with RSI and price when known)
 *   - last shift (direction, price, time)
 *   - recent shifts list
 *   - last-checked time
 *   - "Test bell" posts a test notification through the shift
 *     channel, so the sound can be checked before a real shift.
 *
 * The screen reads what WatchService writes into Prefs on every
 * poll and refreshes every 5 seconds while visible.
 *
 * The notification watcher is ALWAYS ON and has no stop control in
 * this UI — that is deliberate (an accidental tap switched the
 * Crash Watch watcher off once). It auto-starts on every launch
 * once the notification permission is dealt with, and restarts on
 * boot. The only ways to stop it afterwards are force-stopping the
 * app in Android Settings or uninstalling.
 *
 * The gold bot's /api/shift endpoint is public — plain GET, no
 * key — so this app ships no key at all.
 */
class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvLastShift: TextView
    private lateinit var tvRecent: TextView
    private lateinit var tvChecked: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvMeta = findViewById(R.id.tvMeta)
        tvLastShift = findViewById(R.id.tvLastShift)
        tvRecent = findViewById(R.id.tvRecent)
        tvChecked = findViewById(R.id.tvChecked)

        findViewById<Button>(R.id.btnTestBell).setOnClickListener {
            WatchService.testBell(this)
        }
        ensureWatcher()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.postDelayed(refresher, 5000)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        val status = Prefs.shiftStatus(this)
        tvStatus.text = status
        tvStatus.setTextColor(when {
            status.startsWith("SHIFT SELL") -> Color.parseColor("#ff6b5e")
            status.startsWith("ARMED SELL") -> Color.parseColor("#ff9a8a")
            status.startsWith("SHIFT BUY") -> Color.parseColor("#58d68d")
            status.startsWith("ARMED BUY") -> Color.parseColor("#7ee2a8")
            status == "FEED DOWN" -> Color.parseColor("#f5c542")
            else -> Color.WHITE
        })

        val rsi = Prefs.shiftRsi(this)
        val price = Prefs.shiftPrice(this)
        val meta = StringBuilder()
        if (price.isNotEmpty()) meta.append("XAUUSD $price")
        if (rsi.isNotEmpty()) {
            if (meta.isNotEmpty()) meta.append("  ·  ")
            meta.append("RSI $rsi")
        }
        tvMeta.text = if (meta.isEmpty()) "5m shift bell" else meta.toString()

        val recent = Prefs.recentShifts(this)
        if (recent.isEmpty()) {
            tvLastShift.text = "No shifts yet — waiting for the next turn."
            tvRecent.text = ""
        } else {
            val lines = recent.split("\n")
            tvLastShift.text = "Last shift: " + lines[0]
            tvRecent.text = if (lines.size > 1)
                lines.drop(1).joinToString("\n") else ""
        }
        tvChecked.text = Prefs.lastPollText(this)
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
}
