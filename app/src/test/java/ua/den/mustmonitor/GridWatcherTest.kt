package ua.den.mustmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GridWatcherTest {
    private class MemStore(override var on: Boolean? = null, override var since: Long = 0, override var lastSeen: Long = 0) : GridStore

    private data class Event(val on: Boolean, val at: Long, val prevSince: Long, val unseen: Long = 0)

    private var now = 1_800_000_000_000L
    private val events = mutableListOf<Event>()
    private lateinit var store: MemStore

    private fun watcher() = GridWatcher(store, { on, at, prev, unseen, _ -> events += Event(on, at, prev, unseen) }, { now })
    private fun volts(v: Double) = mapOf("must_grid_voltage_volts" to v)

    @Before fun setUp() {
        store = MemStore()
        events.clear()
        State.gridOn = null
        State.gridSince = 0
    }

    @Test fun firstReadingSetsStateSilently() {
        val w = watcher()
        w.update(volts(225.0))
        assertEquals(true, store.on)
        assertTrue(events.isEmpty())
    }

    @Test fun changeNeedsThreeConsecutivePolls() {
        store.on = true; store.since = now - 3600_000
        val w = watcher()
        w.update(volts(0.0)); now += 5000
        w.update(volts(0.0)); now += 5000
        assertTrue(events.isEmpty())
        w.update(volts(0.0))
        assertEquals(1, events.size)
        assertFalse(events[0].on)
        // event time is the first poll that saw the change, and the previous period is reported
        assertEquals(now - 10_000, events[0].at)
        assertEquals(now - 10_000 - 3600_000, events[0].prevSince)
        assertEquals(false, store.on)
    }

    @Test fun dipIsIgnored() {
        store.on = true
        val w = watcher()
        w.update(volts(0.0)); w.update(volts(0.0)); w.update(volts(225.0)); w.update(volts(0.0))
        assertTrue(events.isEmpty())
    }

    @Test fun hysteresis() {
        store.on = false
        val w = watcher()
        repeat(5) { w.update(volts(160.0)) } // between thresholds: stays off
        assertTrue(events.isEmpty())
        repeat(3) { w.update(volts(180.0)) }
        assertEquals(listOf(true), events.map { it.on })
        repeat(5) { w.update(volts(155.0)) } // between thresholds: stays on
        assertEquals(1, events.size)
    }

    @Test fun stateRestoredFromStore() {
        store.on = false; store.since = 123
        watcher()
        assertEquals(false, State.gridOn)
        assertEquals(123L, State.gridSince)
    }

    @Test fun messagesSkipUnknownValues() {
        val off = GridMessages.text(false, now, now - 2 * 3600_000, mapOf("must_load_power_watts" to Double.NaN))
        assertFalse(off.contains("NaN"))
        assertFalse(off.contains("🔋"))
        assertTrue(off.contains("Було: 2 год 0 хв"))
        val on = GridMessages.text(true, now, 0, mapOf("must_battery_soc_percent" to 55.4, "must_battery_voltage_volts" to 26.1), approxTime = true)
        assertTrue(on.contains("≈"))
        assertTrue(on.contains("~55% · 26.1 V"))
        assertFalse(on.contains("Не було"))
    }

    @Test fun changeAfterMonitoringGapIsMarkedUnseen() {
        // the real case: last seen 19:34 during an outage, next poll 15 h later finds the grid back
        store.on = false; store.since = now - 2 * 3600_000; store.lastSeen = now
        val w = watcher()
        w.update(volts(0.0))
        val gapStart = now
        now += 15 * 3600_000L
        repeat(3) { w.update(volts(228.0)); now += 5000 }
        assertEquals(1, events.size)
        assertEquals(gapStart, events[0].unseen)
        val msg = GridMessages.text(true, events[0].at, events[0].prevSince, emptyMap(), unseenSince = events[0].unseen)
        assertTrue(msg.contains("помічено"))
        assertFalse(msg.contains("Не було"))
    }

    @Test fun noGapFlagWhenStateUnchangedAfterGap() {
        store.on = true; store.lastSeen = now
        val w = watcher()
        now += 3600_000
        w.update(volts(225.0)) // same state after the gap: clears it
        repeat(3) { now += 5000; w.update(volts(0.0)) }
        assertEquals(0L, events.single().unseen)
    }

    @Test fun linkWatcherAlertsOnceAndRecovers() {
        val msgs = mutableListOf<String>()
        var t = now
        val lw = LinkWatcher({ true }, { msgs += it }, { t })
        val ok = t
        lw.update(ok); t += 60_000; lw.update(ok)
        assertTrue(msgs.isEmpty())
        t += 3 * 60_000; lw.update(ok); lw.update(ok)
        assertEquals(1, msgs.size)
        lw.update(t)
        assertEquals(2, msgs.size)
        assertTrue(msgs[1].startsWith("✅"))
    }

    @Test fun englishMessages() {
        L10n.messages = "en"
        try {
            val off = GridMessages.text(false, now, now - 90 * 60_000, mapOf("must_battery_soc_percent" to 80.0, "must_load_power_watts" to 300.0))
            assertTrue(off.startsWith("🔴 Grid lost at "))
            assertTrue(off.contains("Was on for: 1 h 30 min"))
            assertTrue(off.contains("Load 300 W"))
            assertFalse(off.contains("Світло"))
        } finally {
            L10n.messages = "uk"
        }
    }
}
