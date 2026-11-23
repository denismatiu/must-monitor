package ua.den.mustmonitor

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Wall clock that survives boxes booting with a wrong date (no RTC battery, no internet during an outage).
 * If the system time is earlier than the last time we know was correct, time is approximated as
 * "last known good + uptime since start" until the system clock catches up (NTP sync).
 * Approximated timestamps are a bit early (downtime is unknown) but stay monotonic.
 */
object Clock {
    /** 2026-01-01T00:00:00Z: anything earlier is certainly an unset clock. */
    const val FLOOR_MS = 1_767_225_600_000L

    private var prefs: SharedPreferences? = null
    private var anchorWall = 0L
    private var anchorUptime = 0L
    @Volatile var approximate = false
        private set

    @Synchronized
    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("clock", Context.MODE_PRIVATE)
        prefs = p
        val floor = maxOf(p.getLong("last_good", 0), FLOOR_MS)
        if (System.currentTimeMillis() < floor) {
            approximate = true
            anchorWall = floor
            anchorUptime = SystemClock.elapsedRealtime()
        }
    }

    fun now(): Long {
        val wall = System.currentTimeMillis()
        if (!approximate) return wall
        synchronized(this) {
            if (!approximate) return wall
            if (wall >= anchorWall) {
                approximate = false
                return wall
            }
            return anchorWall + (SystemClock.elapsedRealtime() - anchorUptime)
        }
    }

    /** Remember the current time as known-good (call periodically). */
    fun save() {
        if (approximate) return
        prefs?.edit()?.putLong("last_good", System.currentTimeMillis())?.apply()
    }
}
