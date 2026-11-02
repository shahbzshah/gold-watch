package com.megah.goldwatch

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * All app state lives here: the tracked signal (the latest one seen
 * in /api/state) and the notification bookkeeping (which signal has
 * been seen, and whether its outcome has already been announced).
 * There is deliberately no watcher on/off flag — the watcher is
 * always on (see MainActivity and BootReceiver), so no stale "off"
 * state can survive.
 *
 * No key of any kind is stored: the gold /api/state endpoint is
 * public.
 */
object Prefs {
    private const val NAME = "goldwatch"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun baselineDone(ctx: Context): Boolean =
        prefs(ctx).getBoolean("baseline_done", false)

    fun setBaselineDone(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("baseline_done", v).apply()

    /** Epoch seconds of the latest signal seen, 0 = none yet. */
    fun lastSignalTs(ctx: Context): Long =
        prefs(ctx).getLong("last_signal_ts", 0L)

    fun setLastSignalTs(ctx: Context, ts: Long) =
        prefs(ctx).edit().putLong("last_signal_ts", ts).apply()

    /** "open", "TP" or "SL" for the tracked signal. */
    fun signalOutcome(ctx: Context): String =
        prefs(ctx).getString("signal_outcome", "open") ?: "open"

    fun setSignalOutcome(ctx: Context, v: String) =
        prefs(ctx).edit().putString("signal_outcome", v).apply()

    /** Tracked signal levels, saved so the outcome can still be
     *  judged from the live price even after /api/state stops
     *  reporting the signal (e.g. a new day starts). */
    fun setSignalLevels(ctx: Context, direction: String,
                        entry: Double, sl: Double, tp: Double) =
        prefs(ctx).edit()
            .putString("signal_direction", direction)
            .putString("signal_entry", entry.toString())
            .putString("signal_sl", sl.toString())
            .putString("signal_tp", tp.toString())
            .apply()

    fun signalDirection(ctx: Context): String =
        prefs(ctx).getString("signal_direction", "") ?: ""

    fun signalEntry(ctx: Context): Double =
        prefs(ctx).getString("signal_entry", "")?.toDoubleOrNull() ?: 0.0

    fun signalSl(ctx: Context): Double =
        prefs(ctx).getString("signal_sl", "")?.toDoubleOrNull() ?: 0.0

    fun signalTp(ctx: Context): Double =
        prefs(ctx).getString("signal_tp", "")?.toDoubleOrNull() ?: 0.0

    fun setLastPoll(ctx: Context, ok: Boolean, note: String) =
        prefs(ctx).edit()
            .putLong("last_poll_ms", System.currentTimeMillis())
            .putBoolean("last_poll_ok", ok)
            .putString("last_poll_note", note)
            .apply()

    fun lastPollText(ctx: Context): String {
        val p = prefs(ctx)
        val ms = p.getLong("last_poll_ms", 0)
        if (ms == 0L) return "no checks yet"
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
        val ok = p.getBoolean("last_poll_ok", false)
        val note = p.getString("last_poll_note", "") ?: ""
        return "last check $t — " + (if (ok) "OK $note" else "failed ($note)")
    }
}
