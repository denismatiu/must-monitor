package ua.den.mustmonitor

import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StateHistoryTest {
    private lateinit var dir: File
    @Before fun setUp() { dir = createTempDir("hist"); State.takeRow(); State.loadHistory(emptyList()) }
    @After fun tearDown() { dir.deleteRecursively(); State.takeRow(); State.loadHistory(emptyList()) }

    private fun col(k: String) = State.historyKeys.indexOf(k) + 1

    @Test fun avgAndMax() {
        for (w in listOf(100.0, 300.0, 200.0)) State.sample(mapOf("must_load_power_watts" to w))
        val r = State.takeRow()!!
        assertEquals(200.0, r[col("must_load_power_watts")], 1e-9)
        assertEquals(300.0, r[col("must_load_power_max_watts")], 1e-9)
        assertTrue(r[col("must_pv_power_watts")].isNaN())
        assertNull(State.takeRow())
    }

    @Test fun maxWithNegativesAndNaN() {
        for (w in listOf(-50.0, Double.NaN, -10.0, -80.0)) State.sample(mapOf("must_load_power_watts" to w))
        val r = State.takeRow()!!
        assertEquals(-10.0, r[col("must_load_power_max_watts")], 1e-9)
        assertEquals(-140.0 / 3, r[col("must_load_power_watts")], 1e-9)
    }

    @Test fun onlyNaNGivesNoRow() {
        State.sample(mapOf("must_load_power_watts" to Double.NaN))
        assertNull(State.takeRow())
    }

    @Test fun takeRowResets() {
        State.sample(mapOf("must_pv_power_watts" to 500.0)); State.takeRow()
        State.sample(mapOf("must_pv_power_watts" to 100.0))
        assertEquals(100.0, State.takeRow()!![col("must_pv_power_watts")], 1e-9)
    }

    @Test fun socPrefersEnergy() {
        assertEquals(40.0, State.soc(mapOf("must_battery_soc_estimate_percent" to 40.0))!!, 0.0)
        assertEquals(55.0, State.soc(mapOf("must_battery_soc_estimate_percent" to 40.0, "must_battery_soc_energy_percent" to 55.0))!!, 0.0)
        assertNull(State.soc(emptyMap()))
    }

    private fun row(ts: Long, load: Double = 100.0): DoubleArray =
        DoubleArray(State.historyKeys.size + 1) { if (it == 0) ts.toDouble() else Double.NaN }.also { it[col("must_load_power_watts")] = load }

    private fun now() = System.currentTimeMillis() / 1000

    @Test fun retentionAndSort() {
        State.loadHistory(listOf(row(now() - 10), row(now() - 90_000), row(now() - 20)))
        val s = State.historySnapshot()
        assertEquals(2, s.size)
        assertTrue(s[0][0] < s[1][0])
    }

    @Test fun maxRows() {
        State.loadHistory((0 until 3300).map { row(now() - 3300 + it) })
        assertEquals(State.HISTORY_MAX, State.historySnapshot().size)
        assertEquals((now() - 3300 + 100).toDouble(), State.historySnapshot().first()[0], 2.0)
    }

    @Test fun storeRoundTrip() {
        val st = HistoryStore(dir)
        val r1 = row(now() - 20, 123.456); val r2 = row(now() - 10, -5.0)
        State.addHistory(r1); st.append(r1)   // first append creates file via rewrite
        State.addHistory(r2); st.append(r2)
        val loaded = HistoryStore(dir).load()
        assertEquals(2, loaded.size)
        assertEquals(123.46, loaded[0][col("must_load_power_watts")], 1e-9)
        assertEquals(-5.0, loaded[1][col("must_load_power_watts")], 1e-9)
        assertTrue(loaded[0][col("must_pv_power_watts")].isNaN())
    }

    @Test fun corruptLinesAndGlitch() {
        val header = (listOf("ts") + State.historyKeys).joinToString(",")
        val n = State.historyKeys.size
        fun line(ts: Long, load: String) = (listOf(ts.toString(), load) + List(n - 1) { "1" }).joinToString(",")
        File(dir, "history.csv").writeText(listOf(header, line(now() - 5, "65485"), "garbage", "", "abc,1,2",
            line(now() - 4, "10"), line(now() - 200_000, "10"), "${now()},1,2").joinToString("\n"))
        val rows = HistoryStore(dir).load()
        assertEquals(2, rows.size)
        assertTrue(rows[0][col("must_load_power_watts")].isNaN())
        assertTrue(rows[0][col("must_load_power_max_watts")] == 1.0)
        assertEquals(10.0, rows[1][col("must_load_power_watts")], 0.0)
    }

    @Test fun headerChangeRemapsAndRewrites() {
        val keys = State.historyKeys
        val old = listOf("ts", keys[2], keys[0], "legacy_col")
        File(dir, "history.csv").writeText(old.joinToString(",") + "\n${now() - 5},7,9,99\n")
        val st = HistoryStore(dir)
        val rows = st.load()
        assertEquals(1, rows.size)
        assertEquals(9.0, rows[0][1], 0.0)
        assertEquals(7.0, rows[0][3], 0.0)
        assertTrue(rows[0][2].isNaN())
        State.loadHistory(rows)
        st.append(row(now()).also { State.addHistory(it) })
        val txt = File(dir, "history.csv").readLines()
        assertEquals((listOf("ts") + keys).joinToString(","), txt[0])
        assertEquals(3, txt.size)
    }

    @Test fun emptyAndMissingFile() {
        assertTrue(HistoryStore(dir).load().isEmpty())
        File(dir, "history.csv").writeText("")
        assertTrue(HistoryStore(dir).load().isEmpty())
    }

    @Test fun eventLog() {
        EventLog.init(dir)
        EventLog.add(1000, true); EventLog.add(2000, false)
        EventLog.init(dir)
        assertEquals(listOf(1000L to true, 2000L to false), EventLog.snapshot())
        File(dir, "events.csv").appendText("bad\n,\n3000,1\n")
        EventLog.init(dir)
        assertEquals(3000L to true, EventLog.snapshot().last())
        for (i in 0..1100) EventLog.add(i.toLong(), i % 2 == 0)
        assertTrue(EventLog.snapshot().size <= 1000)
        EventLog.init(dir)
        assertTrue(EventLog.snapshot().size <= 500)
    }
}
