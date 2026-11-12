package com.megah.goldwatch

import android.content.Context
import android.content.SharedPreferences

/**
 * All app state lives here: the shift bell's latest state (written
 * by WatchService on every poll, read by MainActivity to draw the
 * bell screen) and the notification bookkeeping (which shift epoch
 * has been seen, so history never spams).
 *
 * No key of any kind is stored: the gold bot's /api/shift endpoint
 * is public.
 *
 * NOTE (v1.1.0): the v1.0 signal/outcome fields (last_signal_ts,
 * signal_outcome, signal_direction/entry/sl/tp) are gone — the app
 * no longer shows Phil+RUDY signals. Their keys may linger in
 * SharedPreferences from an upgrade; they are never read.
 */
object Prefs {
    private const val NAME = "goldwatch"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun baselineDone(ctx: Context): Boolean =
        prefs(ctx).getBoolean("baseline_done", false)

    fun setBaselineDone(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("baseline_done", v).apply()

    /** Epoch seconds of the latest shift seen, 0 = none yet. */
    fun lastShiftEpoch(ctx: Context): Long =
        prefs(ctx).getLong("last_shift_epoch", 0L)

    fun setLastShiftEpoch(ctx: Context, epoch: Long) =
        prefs(ctx).edit().putLong("last_shift_epoch", epoch).apply()

    /** Recent shifts as display lines, newest first (max 10). */
    fun recentShifts(ctx: Context): String =
        prefs(ctx).getString("recent_shifts", "") ?: ""

    fun setRecentShifts(ctx: Context, lines: String) =
        prefs(ctx).edit().putString("recent_shifts", lines).apply()

    /** Live bell status line, e.g. "ARMED SELL — overbought". */
    fun shiftStatus(ctx: Context): String =
        prefs(ctx).getString("shift_status", "WAITING") ?: "WAITING"

    fun shiftRsi(ctx: Context): String =
        prefs(ctx).getString("shift_rsi", "") ?: ""

    fun shiftPrice(ctx: Context): String =
        prefs(ctx).getString("shift_price", "") ?: ""

    fun setShiftStatus(ctx: Context, status: String,
                       rsi: String, price: String) =
        prefs(ctx).edit()
            .putString("shift_status", status)
            .putString("shift_rsi", rsi)
            .putString("shift_price", price)
            .apply()

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
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(ms))
        val ok = p.getBoolean("last_poll_ok", false)
        val note = p.getString("last_poll_note", "") ?: ""
        return "last check $t — " + (if (ok) "OK $note" else "failed ($note)")
    }
}
