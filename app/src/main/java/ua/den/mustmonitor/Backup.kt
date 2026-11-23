package ua.den.mustmonitor

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup of everything worth keeping: settings (incl. Telegram), battery counter, grid event log and
 * history. Uninstalling an app wipes its private storage, so a copy is also written to the shared
 * Download/MustMonitor folder, which survives reinstalls; restoring is done from the dashboard.
 */
object Backup {
    const val FILE_NAME = "must-monitor-backup.json"
    const val FOLDER = "MustMonitor"
    private const val VERSION = 1

    /** Set when something worth backing up changed; the poller writes the auto backup soon after. */
    @Volatile var dirty = false
    @Volatile var lastAuto: Long = 0
    @Volatile var lastAutoError: String? = null
    /** Set by the service so a restore can persist merged history. */
    @Volatile var store: HistoryStore? = null

    fun export(s: Settings): JSONObject {
        val o = JSONObject()
        o.put("app", "must-monitor").put("version", VERSION).put("created", Clock.now())
        o.put("settings", JSONObject()
            .put("tgToken", s.tgToken).put("tgChatId", s.tgChatId).put("tgEnabled", s.tgEnabled).put("tgLang", s.tgLang)
            .put("batterySignManual", s.batterySignManual).put("batterySignLearned", s.batterySignLearned)
            .put("batteryWh", s.batteryWh).put("batteryFullVolts", s.batteryFullVolts)
            .put("phoneLowAlert", s.phoneLowAlert).put("phoneLowPercent", s.phoneLowPercent)
            .put("inverterOfflineAlert", s.inverterOfflineAlert))
        val soc = s.socEnergy
        if (!soc.isNaN()) o.put("battery", JSONObject().put("socEnergy", soc).put("socEnergyTs", s.socEnergyTs))
        val ev = JSONArray()
        for ((ts, on) in EventLog.snapshot()) ev.put(JSONArray().put(ts).put(on))
        o.put("events", ev)
        val rows = JSONArray()
        for (r in State.historySnapshot()) {
            val a = JSONArray()
            for (x in r) a.put(if (x.isNaN()) JSONObject.NULL else x)
            rows.put(a)
        }
        o.put("history", JSONObject().put("keys", JSONArray(listOf("ts") + State.historyKeys)).put("rows", rows))
        return o
    }

    /** Applies a backup; history and events are merged with what is already there. Returns a short summary. */
    fun restore(s: Settings, o: JSONObject, store: HistoryStore?): Map<String, Int> {
        require(o.optString("app") == "must-monitor") { "not a Must Monitor backup" }
        o.optJSONObject("settings")?.let { st ->
            if (st.has("tgToken")) st.optString("tgToken").takeIf { it.isEmpty() || Telegram.validToken(it) }?.let { s.tgToken = it }
            if (st.has("tgChatId")) s.tgChatId = st.optString("tgChatId")
            if (st.has("tgEnabled")) s.tgEnabled = st.optBoolean("tgEnabled", true)
            if (st.has("tgLang")) st.optString("tgLang").takeIf { it == "uk" || it == "en" }?.let { s.tgLang = it; L10n.messages = it }
            if (st.has("batterySignManual")) st.optInt("batterySignManual").takeIf { it in -1..1 }?.let { s.batterySignManual = it }
            if (st.has("batterySignLearned")) st.optInt("batterySignLearned").takeIf { it in -1..1 }?.let { s.batterySignLearned = it }
            if (st.has("batteryWh")) st.optInt("batteryWh").takeIf { it in 100..100_000 }?.let { s.batteryWh = it }
            if (st.has("batteryFullVolts")) st.optDouble("batteryFullVolts").takeIf { it in 10.0..70.0 }?.let { s.batteryFullVolts = it }
            if (st.has("phoneLowAlert")) s.phoneLowAlert = st.optBoolean("phoneLowAlert")
            if (st.has("phoneLowPercent")) st.optInt("phoneLowPercent").takeIf { it in 1..90 }?.let { s.phoneLowPercent = it }
            if (st.has("inverterOfflineAlert")) s.inverterOfflineAlert = st.optBoolean("inverterOfflineAlert", true)
        }
        // Battery counter: only if newer than ours (or we have none). It is re-validated on the next poll.
        o.optJSONObject("battery")?.let { b ->
            val soc = b.optDouble("socEnergy", Double.NaN)
            val ts = b.optLong("socEnergyTs", 0)
            if (!soc.isNaN() && soc in 0.0..100.0 && (s.socEnergy.isNaN() || ts > s.socEnergyTs)) {
                s.saveSocEnergy(soc, ts)
                Battery.resetState()
            }
        }
        return restoreData(o, store)
    }

    /** History and event log part of a restore (merge, never drop what we have). */
    fun restoreData(o: JSONObject, store: HistoryStore?): Map<String, Int> {
        var events = 0
        o.optJSONArray("events")?.let { arr ->
            val list = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONArray(i) ?: return@mapNotNull null
                e.optLong(0, -1).takeIf { it > 0 }?.let { it to e.optBoolean(1) }
            }
            events = EventLog.merge(list)
        }
        var rows = 0
        o.optJSONObject("history")?.let { h ->
            val keys = h.optJSONArray("keys") ?: return@let
            val idx = State.historyKeys.map { k -> (0 until keys.length()).firstOrNull { keys.optString(it) == k } ?: -1 }
            val arr = h.optJSONArray("rows") ?: return@let
            val imported = (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONArray(i) ?: return@mapNotNull null
                val ts = r.optDouble(0, Double.NaN).takeUnless { it.isNaN() } ?: return@mapNotNull null
                DoubleArray(idx.size + 1) { c -> if (c == 0) ts else idx[c - 1].let { j -> if (j < 0 || r.isNull(j)) Double.NaN else r.optDouble(j, Double.NaN) } }
            }
            rows = State.mergeHistory(imported)
            store?.rewriteFromState()
        }
        dirty = true
        return mapOf("events" to events, "rows" to rows)
    }

    /** Writes the backup into the shared Download/MustMonitor folder (survives uninstall). */
    fun writeAuto(ctx: Context, s: Settings) {
        try {
            val bytes = export(s).toString().toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= 29) writeMediaStore(ctx, bytes) else writeLegacy(ctx, bytes)
            lastAuto = Clock.now()
            lastAutoError = null
        } catch (e: Exception) {
            lastAutoError = e.message ?: e.javaClass.simpleName
        }
    }

    private fun writeMediaStore(ctx: Context, bytes: ByteArray) {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relPath = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"
        // Only files this installation created are visible here; after a reinstall a new file is created
        // next to the old one (Android adds " (1)" to the name) — both can be restored from.
        val existing = resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(FILE_NAME, relPath), null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        val uri = existing ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
        }) ?: error("cannot create backup file")
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("cannot open backup file")
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(ctx: Context, bytes: ByteArray) {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            error("no storage permission")
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        dir.mkdirs()
        File(dir, FILE_NAME).writeAtomically { w -> w.write(String(bytes, Charsets.UTF_8)) }
    }
}
