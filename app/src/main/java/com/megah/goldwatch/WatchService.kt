package com.megah.goldwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The notification engine. A foreground service polls the gold
 * bot's public /api/state every 30 seconds and turns changes into
 * phone notifications:
 *
 *   - a new signal (last_signal.ts changes) -> "signals" channel,
 *     with direction, entry, SL and TP
 *   - the tracked signal closing at TP or SL -> "outcomes" channel,
 *     with the R result
 *
 * /api/state carries no outcome field and no stable signal id, so
 * the signal is identified by its ts (epoch seconds) and its
 * outcome is judged from the live price in the same response:
 * a BUY closes at TP when mid >= take_profit and at SL when
 * mid <= stop_loss (reversed for SELL). The R result is computed
 * from the signal's own levels: TP pays |TP-entry|/|entry-SL| R,
 * SL costs -1.0R. The price must be fresh (age_s <= 120) or the
 * outcome check is skipped, so a stale quote can never fake a
 * close. /api/state only ever exposes the latest signal, so the
 * app watches that one signal at a time; older signals' outcomes
 * stay visible in the dashboard's history.
 *
 * The endpoint is public — plain GET, no key, no auth headers.
 * The first successful poll only records the current signal as the
 * baseline — history never spams. Polling also keeps the free
 * Render service awake, so it doubles as the keep-alive pinger.
 *
 * The service is always on: MainActivity starts it on every launch
 * and BootReceiver restarts it on boot, and there is no stop control
 * in the UI. Only force-stop or uninstall ends it.
 */
class WatchService : Service() {

    private var exec: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
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
        val url = URL(STATE_URL)
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
        val firstRun = !Prefs.baselineDone(this)
        var newCount = 0

        val sig = root.optJSONObject("last_signal")
        if (sig != null) {
            val ts = sig.optLong("ts", 0L)
            val seenTs = Prefs.lastSignalTs(this)
            if (ts > 0 && ts != seenTs) {
                val direction =
                    sig.optString("direction", "").uppercase()
                val entry = sig.optDouble("entry", 0.0)
                val sl = sig.optDouble("stop_loss", 0.0)
                val tp = sig.optDouble("take_profit", 0.0)
                Prefs.setLastSignalTs(this, ts)
                Prefs.setSignalOutcome(this, "open")
                Prefs.setSignalLevels(this, direction, entry, sl, tp)
                if (!firstRun) {
                    notifySignal(direction, entry, sl, tp, ts)
                    newCount++
                }
            }
        }

        // Outcome check: judge the tracked (still-open) signal
        // against the live price in this same response.
        if (Prefs.signalOutcome(this) == "open" &&
            Prefs.lastSignalTs(this) > 0L) {
            checkOutcome(root)
        }

        if (firstRun) Prefs.setBaselineDone(this, true)
        Prefs.setLastPoll(this, true,
            if (newCount > 0) "· $newCount new" else "")
    }

    /** Returns true when the tracked signal just closed. */
    private fun checkOutcome(root: JSONObject): Boolean {
        val price = root.optJSONObject("price") ?: return false
        if (!price.has("mid")) return false
        // Never judge a close off a stale quote.
        val age = price.optDouble("age_s", -1.0)
        if (age < 0 || age > MAX_PRICE_AGE_S) return false
        val mid = price.optDouble("mid", 0.0)
        if (mid <= 0.0) return false

        val direction = Prefs.signalDirection(this)
        val entry = Prefs.signalEntry(this)
        val sl = Prefs.signalSl(this)
        val tp = Prefs.signalTp(this)
        if (direction !in listOf("BUY", "SELL")) return false
        if (entry <= 0.0 || sl <= 0.0 || tp <= 0.0) return false

        val hitTp: Boolean
        val hitSl: Boolean
        if (direction == "BUY") {
            hitTp = mid >= tp
            hitSl = mid <= sl
        } else {
            hitTp = mid <= tp
            hitSl = mid >= sl
        }
        if (!hitTp && !hitSl) return false
        // Both touched in one poll (wide move): the level nearer the
        // entry is taken as hit first.
        val outcome = if (hitTp && hitSl) {
            if (kotlin.math.abs(mid - tp) <= kotlin.math.abs(mid - sl))
                "TP" else "SL"
        } else if (hitTp) "TP" else "SL"

        val risk = kotlin.math.abs(entry - sl)
        val r = if (outcome == "TP" && risk > 0.0)
            kotlin.math.abs(tp - entry) / risk else -1.0
        Prefs.setSignalOutcome(this, outcome)
        notifyOutcome(direction, entry, outcome, r)
        return true
    }

    // -- notifications --------------------------------------------------

    private fun notifySignal(direction: String, entry: Double,
                             sl: Double, tp: Double, ts: Long) {
        val levels = "Entry %.2f · SL %.2f · TP %.2f".format(entry, sl, tp)
        post(CH_SIGNALS, (ts % Int.MAX_VALUE).toInt(),
            "Gold $direction signal — XAUUSD",
            "$levels — new signal from the gold bot.")
    }

    private fun notifyOutcome(direction: String, entry: Double,
                              outcome: String, r: Double) {
        val title = if (outcome == "TP")
            "TP HIT %+.2fR".format(r)
        else
            "SL HIT %+.2fR".format(r)
        val id = (Prefs.lastSignalTs(this) % Int.MAX_VALUE).toInt() +
            OUTCOME_ID_OFFSET
        post(CH_OUTCOMES, id, title,
            "$direction @ %.2f closed at $outcome.".format(entry))
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
            .setContentText("Watching XAUUSD — tap to open the dashboard")
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CH_WATCH, "Watcher status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(
            CH_SIGNALS, "Gold signals", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(
            CH_OUTCOMES, "Signal outcomes",
            NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        private const val STATE_URL =
            "https://gold-signals-bot-pjyn.onrender.com/api/state"
        private const val POLL_SEC = 30L
        private const val FG_ID = 1001
        private const val OUTCOME_ID_OFFSET = 500_000
        private const val MAX_PRICE_AGE_S = 120.0
        const val CH_WATCH = "watch_status"
        const val CH_SIGNALS = "signals"
        const val CH_OUTCOMES = "outcomes"

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
    }
}
