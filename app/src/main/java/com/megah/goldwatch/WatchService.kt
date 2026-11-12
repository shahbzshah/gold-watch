package com.megah.goldwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The shift bell. A foreground service polls the gold bot's public
 * /api/shift every 30 seconds and turns new market shifts into
 * phone notifications:
 *
 *   - a shift event with a new epoch -> "shift" channel
 *     (HIGH importance, WITH SOUND): "SHIFT SELL — XAUUSD" /
 *     "SHIFT BUY — XAUUSD", with price and time.
 *
 * A shift is identified by its firing-bar epoch. The endpoint is
 * public — plain GET, no key, no auth headers. The first successful
 * poll only records the current shifts as the baseline — history
 * never spams. Polling also keeps the free Render service awake, so
 * it doubles as the keep-alive pinger.
 *
 * The service is always on: MainActivity starts it on every launch
 * and BootReceiver restarts it on boot, and there is no stop control
 * in the UI. Only force-stop or uninstall ends it.
 *
 * v1.1.0: this replaces the v1.0 Phil+RUDY signal + outcome
 * watcher. No signal, TP or SL is watched any more — the app is
 * only the bell.
 */
class WatchService : Service() {

    private var exec: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fg = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FG_ID, fg,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FG_ID, fg)
        }
        if (exec == null) {
            val e = Executors.newSingleThreadScheduledExecutor()
            exec = e
            e.scheduleWithFixedDelay({ pollSafe() }, 3, POLL_SEC,
                TimeUnit.SECONDS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        exec?.shutdownNow()
        exec = null
        super.onDestroy()
    }

    // -- polling ------------------------------------------------------

    private fun pollSafe() {
        try {
            poll()
        } catch (e: Exception) {
            Prefs.setLastPoll(this, false,
                e.javaClass.simpleName)
        }
    }

    private fun poll() {
        val url = URL(SHIFT_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        // Public endpoint: no auth header, no key of any kind.
        val code = conn.responseCode
        if (code != 200) {
            Prefs.setLastPoll(this, false, "HTTP $code")
            return
        }
        val root = JSONObject(conn.inputStream.bufferedReader().readText())
        val feedOk = root.optBoolean("feed_ok", false)
        val firstRun = !Prefs.baselineDone(this)
        var newCount = 0

        val shifts = root.optJSONArray("shifts")
        val newest = if (shifts != null && shifts.length() > 0)
            shifts.optJSONObject(0) else null
        if (newest != null) {
            val epoch = newest.optLong("epoch", 0L)
            if (epoch > 0) {
                val seen = Prefs.lastShiftEpoch(this)
                if (epoch > seen) {
                    Prefs.setLastShiftEpoch(this, epoch)
                    if (!firstRun) {
                        notifyShift(newest)
                        newCount++
                    }
                }
            }
            Prefs.setRecentShifts(this, shiftsToLines(shifts))
        }

        // Live bell state for the UI screen.
        val price = root.optDouble("price", -1.0)
        val rsi = root.optDouble("rsi", -1.0)
        val armed = root.optString("armed", "none")
        Prefs.setShiftStatus(
            this, statusText(feedOk, armed, newest),
            if (rsi >= 0.0) "%.1f".format(rsi) else "",
            if (price > 0.0) "%.2f".format(price) else "")

        if (firstRun) Prefs.setBaselineDone(this, true)
        Prefs.setLastPoll(this, feedOk,
            if (!feedOk) "feed down"
            else if (newCount > 0) "· $newCount new shift" else "")
    }

    /** Bell status line for the UI: armed state first, else the
     * latest shift with its time, else waiting. */
    private fun statusText(feedOk: Boolean, armed: String,
                           newest: JSONObject?): String {
        if (!feedOk) return "FEED DOWN"
        if (armed == "sell") return "ARMED SELL — overbought"
        if (armed == "buy") return "ARMED BUY — oversold"
        if (newest != null) {
            val dir = newest.optString("direction", "")
            val epoch = newest.optLong("epoch", 0L)
            if (dir.isNotEmpty() && epoch > 0) {
                return "SHIFT $dir ${fmtTime(epoch)}"
            }
        }
        return "WAITING"
    }

    private fun shiftsToLines(shifts: JSONArray): String {
        val lines = ArrayList<String>()
        val n = minOf(shifts.length(), MAX_RECENT)
        for (i in 0 until n) {
            val s = shifts.optJSONObject(i) ?: continue
            val dir = s.optString("direction", "")
            val price = s.optDouble("price", 0.0)
            val epoch = s.optLong("epoch", 0L)
            if (dir.isEmpty() || epoch <= 0L) continue
            lines.add("$dir  %.2f  ${fmtTime(epoch)}".format(price))
        }
        return lines.joinToString("\n")
    }

    // -- notifications --------------------------------------------------

    private fun notifyShift(shift: JSONObject) {
        val dir = shift.optString("direction", "")
        val price = shift.optDouble("price", 0.0)
        val epoch = shift.optLong("epoch", 0L)
        val title = if (dir == "SELL") "SHIFT SELL — XAUUSD"
            else "SHIFT BUY — XAUUSD"
        val side = if (dir == "SELL") "overbought turn" else "oversold turn"
        val text = "XAUUSD %.2f at %s — 5m %s. Confirm on 1m."
            .format(price, fmtTime(epoch), side)
        post(CH_SHIFT, (epoch % Int.MAX_VALUE).toInt(), title, text)
    }

    private fun post(channel: String, id: Int, title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setDefaults(Notification.DEFAULT_SOUND)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    private fun buildForegroundNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle("Gold Watch is on")
            .setContentText("Shift bell watching XAUUSD 5m — tap to open")
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val SHIFT_URL =
            "https://gold-signals-bot-pjyn.onrender.com/api/shift"
        private const val POLL_SEC = 30L
        private const val FG_ID = 1001
        private const val MAX_RECENT = 10
        const val CH_WATCH = "watch_status"
        const val CH_SHIFT = "shift"

        fun start(ctx: Context) {
            val i = Intent(ctx, WatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WatchService::class.java))
        }

        /** Test button: a real notification through the shift
         * channel, so the user hears the bell before a real shift. */
        fun testBell(ctx: Context) {
            createChannels(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val tap = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CH_SHIFT)
                .setSmallIcon(R.drawable.ic_stat_pulse)
                .setContentTitle("TEST — SHIFT BUY — XAUUSD")
                .setContentText("Test bell: this is how a real shift sounds.")
                .setContentIntent(tap)
                .setDefaults(Notification.DEFAULT_SOUND)
                .setAutoCancel(true)
                .build()
            nm.notify(999_999, n)
        }

        private fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                CH_WATCH, "Watcher status",
                NotificationManager.IMPORTANCE_LOW))
            // The bell: HIGH importance WITH SOUND — a shift must be
            // heard, not just seen. Sound is the user's default
            // notification sound.
            val bell = NotificationChannel(
                CH_SHIFT, "Shift bell", NotificationManager.IMPORTANCE_HIGH)
            bell.setSound(
                RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build())
            bell.enableVibration(true)
            nm.createNotificationChannel(bell)
        }

        fun fmtTime(epochSec: Long): String =
            SimpleDateFormat("HH:mm", Locale.US)
                .format(Date(epochSec * 1000))
    }
}
