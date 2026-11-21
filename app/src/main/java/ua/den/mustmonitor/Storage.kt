package ua.den.mustmonitor

import java.io.File
import java.util.Locale

/**
 * History persisted as CSV in the app's private storage so charts survive restarts.
 * Rows are appended; the file is rewritten (trimmed to the retention window) periodically.
 */
class HistoryStore(dir: File) {
    private val file = File(dir, "history.csv")
    private val header = (listOf("ts") + State.historyKeys).joinToString(",")
    private var needsRewrite = false
    private var appended = 0

    fun load(): List<DoubleArray> {
        if (!file.exists()) return emptyList()
        return try {
            val lines = file.readLines()
            if (lines.isEmpty()) return emptyList()
            val cols = lines[0].split(',')
            needsRewrite = lines[0] != header
            val idx = State.historyKeys.map { cols.indexOf(it) }
            val isLoad = State.historyKeys.map { it.startsWith("must_load_power") }
            val cutoff = (System.currentTimeMillis() - State.HISTORY_RETENTION_MS) / 1000.0
            lines.drop(1).mapNotNull { line ->
                val p = line.split(',')
                val ts = p[0].toDoubleOrNull() ?: return@mapNotNull null
                if (ts < cutoff || p.size != cols.size) return@mapNotNull null
                DoubleArray(idx.size + 1) { i ->
                    if (i == 0) ts else idx[i - 1].let { j ->
                        val x = if (j < 0) Double.NaN else p[j].toDoubleOrNull() ?: Double.NaN
                        // drop points polluted by the old unsigned-load glitch (65485 W)
                        if (isLoad[i - 1] && x > 10_000) Double.NaN else x
                    }
                }
            }
        } catch (_: Exception) {
            needsRewrite = true
            emptyList()
        }
    }

    fun append(row: DoubleArray) {
        try {
            if (needsRewrite || !file.exists() || ++appended >= 360) {
                rewrite(State.historySnapshot())
                needsRewrite = false
                appended = 0
            } else {
                file.appendText(line(row) + "\n")
            }
        } catch (_: Exception) {
            needsRewrite = true
        }
    }

    private fun rewrite(rows: List<DoubleArray>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write(header); w.write("\n")
            for (r in rows) { w.write(line(r)); w.write("\n") }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun line(r: DoubleArray): String = r.mapIndexed { i, x ->
        when {
            x.isNaN() -> ""
            i == 0 -> x.toLong().toString()
            else -> "%.2f".format(Locale.ROOT, x)
        }
    }.joinToString(",")
}

/** Grid on/off transitions, persisted as "ts_ms,1|0" lines. */
object EventLog {
    private const val KEEP = 500
    private var file: File? = null
    private val events = ArrayDeque<Pair<Long, Boolean>>()

    @Synchronized
    fun init(dir: File) {
        val f = File(dir, "events.csv")
        file = f
        events.clear()
        if (f.exists()) {
            try {
                f.readLines().forEach { l ->
                    val p = l.split(',')
                    val ts = p.getOrNull(0)?.toLongOrNull() ?: return@forEach
                    events.addLast(ts to (p.getOrNull(1) == "1"))
                }
            } catch (_: Exception) {
            }
        }
        while (events.size > KEEP) events.removeFirst()
    }

    @Synchronized
    fun add(ts: Long, on: Boolean) {
        events.addLast(ts to on)
        val f = file ?: return
        try {
            if (events.size > 2 * KEEP) {
                while (events.size > KEEP) events.removeFirst()
                f.writeText(events.joinToString("") { "${it.first},${if (it.second) 1 else 0}\n" })
            } else {
                f.appendText("$ts,${if (on) 1 else 0}\n")
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun snapshot(): List<Pair<Long, Boolean>> = events.toList()
}
