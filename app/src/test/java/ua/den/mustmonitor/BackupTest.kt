package ua.den.mustmonitor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class BackupTest {
    private lateinit var dir: File
    private val now = System.currentTimeMillis() / 1000

    @Before fun setUp() {
        dir = createTempDir("backup")
        EventLog.init(dir)
        State.loadHistory(emptyList())
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        State.loadHistory(emptyList())
    }

    private fun row(ts: Long, load: Double) =
        DoubleArray(State.historyKeys.size + 1) { if (it == 0) ts.toDouble() else Double.NaN }
            .also { it[State.historyKeys.indexOf("must_load_power_watts") + 1] = load }

    @Test fun mergesHistoryAndEventsWithoutDuplicates() {
        // device already has newer data (after a reinstall it kept running)
        State.loadHistory(listOf(row(now - 60, 300.0), row(now - 30, 310.0)))
        EventLog.add(now * 1000 - 50_000, true)

        // backup: older rows, one overlapping, keys in a different order + an unknown column
        val keys = JSONArray(listOf("ts", "unknown_col", "must_load_power_watts"))
        val rows = JSONArray()
        for (ts in listOf(now - 120, now - 90, now - 60)) rows.put(JSONArray(listOf(ts, 1, 200)))
        val backup = JSONObject()
            .put("app", "must-monitor")
            .put("history", JSONObject().put("keys", keys).put("rows", rows))
            .put("events", JSONArray().put(JSONArray(listOf(now * 1000 - 100_000, false))).put(JSONArray(listOf(now * 1000 - 50_000, true))))

        val store = HistoryStore(dir)
        val summary = Backup.restoreData(backup, store)
        assertEquals(2, summary["rows"])
        assertEquals(1, summary["events"])

        val h = State.historySnapshot()
        assertEquals(listOf(now - 120, now - 90, now - 60, now - 30), h.map { it[0].toLong() })
        val iL = State.historyKeys.indexOf("must_load_power_watts") + 1
        assertEquals(200.0, h[0][iL], 0.0)
        assertEquals(300.0, h[2][iL], 0.0) // existing row kept, not overwritten
        assertEquals(2, EventLog.snapshot().size)

        // persisted: a fresh load sees the merged history
        assertEquals(4, HistoryStore(dir).load().size)
    }

    @Test fun exportedHistoryShapeRoundTrips() {
        State.loadHistory(listOf(row(now - 30, 123.0)))
        val keys = JSONArray(listOf("ts") + State.historyKeys)
        val r = JSONArray(); for (x in State.historySnapshot()[0]) r.put(if (x.isNaN()) JSONObject.NULL else x)
        val o = JSONObject().put("app", "must-monitor").put("history", JSONObject().put("keys", keys).put("rows", JSONArray().put(r)))
        State.loadHistory(emptyList())
        Backup.restoreData(JSONObject(o.toString()), null)
        val iL = State.historyKeys.indexOf("must_load_power_watts") + 1
        assertEquals(123.0, State.historySnapshot().single()[iL], 0.0)
        assertTrue(State.historySnapshot().single()[iL + 1].isNaN())
    }
}
