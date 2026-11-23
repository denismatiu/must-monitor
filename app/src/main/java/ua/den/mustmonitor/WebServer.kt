package ua.den.mustmonitor

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject

class WebServer(private val ctx: Context, private val settings: Settings, port: Int) : NanoHTTPD(port) {

    private val index: String by lazy {
        ctx.assets.open("index.html").bufferedReader().use { it.readText() }
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            guard(session)?.let { return it }
            when (session.uri) {
                "/", "/index.html" -> text(Response.Status.OK, "text/html; charset=utf-8", index)
                "/metrics" -> text(Response.Status.OK, "text/plain; version=0.0.4; charset=utf-8", metrics())
                "/api/state" -> json(state())
                "/api/history" -> json(history(session.parameters["since"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0))
                "/api/events" -> json(events())
                "/api/raw" -> raw(session)
                "/api/settings" -> settingsApi(session)
                "/api/telegram/test" -> telegramTest(session)
                "/api/telegram/detect" -> telegramDetect(session)
                "/api/backup" -> backup()
                "/api/restore" -> restore(session)
                else -> text(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (e: Exception) {
            text(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
    }

    /**
     * The server has no login, so at least stop other websites from driving it through a browser on the LAN:
     *  - POSTs must carry X-Must-Monitor: 1. A cross-site page can't add a custom header without a CORS
     *    preflight, and we never answer preflights, so the request is blocked.
     *  - Sensitive endpoints only answer when Host is an IP / localhost / a local name, which defeats DNS rebinding.
     */
    private fun guard(session: IHTTPSession): Response? {
        val sensitive = session.method == Method.POST || session.uri in setOf("/api/settings", "/api/raw", "/api/telegram/test", "/api/telegram/detect", "/api/backup", "/api/restore")
        if (!sensitive) return null
        if (!localHost(session.headers["host"])) return jsonError(Response.Status.FORBIDDEN, "open the dashboard by IP address or a local name")
        if (session.method == Method.POST && session.headers["x-must-monitor"] != "1") {
            return jsonError(Response.Status.FORBIDDEN, "missing X-Must-Monitor header")
        }
        if (session.method == Method.OPTIONS) return jsonError(Response.Status.FORBIDDEN, "CORS is not supported")
        return null
    }

    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val localSuffixes = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain")

    private fun localHost(header: String?): Boolean {
        if (header.isNullOrBlank()) return true // HTTP/1.0 clients (curl -0, scripts)
        val h = header.trim().lowercase()
        if (h.startsWith("[")) return true // IPv6 literal
        val host = h.substringBefore(':')
        return host == "localhost" || ipv4.matches(host) || !host.contains('.') || localSuffixes.any { host.endsWith(it) }
    }

    private fun form(session: IHTTPSession): Map<String, String> {
        if (session.method == Method.POST) session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull() ?: "" }
    }

    private fun jsonError(status: Response.Status, msg: String) =
        text(status, "application/json; charset=utf-8", JSONObject().put("error", msg).toString())

    private fun settingsJson(): String {
        val t = settings.tgToken
        return JSONObject()
            .put("enabled", settings.tgEnabled)
            .put("chatId", settings.tgChatId)
            .put("tokenSet", t.isNotEmpty())
            .put("tokenHint", if (t.length > 4) t.takeLast(4) else "")
            .put("lastError", Telegram.lastError ?: JSONObject.NULL)
            .put("lastSent", Telegram.lastSent)
            .put("batterySign", settings.batterySignManual)
            .put("batterySignLearned", settings.batterySignLearned)
            .put("batteryWh", settings.batteryWh)
            .put("batteryFullVolts", settings.batteryFullVolts)
            .put("phoneLowAlert", settings.phoneLowAlert)
            .put("phoneLowPercent", settings.phoneLowPercent)
            .put("inverterOfflineAlert", settings.inverterOfflineAlert)
            .put("tgLang", settings.tgLang)
            .put("backupLast", Backup.lastAuto)
            .put("backupError", Backup.lastAutoError ?: JSONObject.NULL)
            .toString()
    }

    private fun settingsApi(session: IHTTPSession): Response {
        if (session.method != Method.POST) return json(settingsJson())
        val f = form(session)
        val lang = f["lang"]
        val bad = { uk: String, en: String -> jsonError(Response.Status.BAD_REQUEST, L10n.pick(lang, uk, en)) }

        // Validate everything first, so a request is applied either fully or not at all.
        val token = f["token"].orEmpty().trim()
        if (token.isNotEmpty() && !Telegram.validToken(token)) return bad("невірний формат токена", "invalid token format")
        val chatId = f["chatId"]?.trim()
        if (chatId != null && chatId.isNotEmpty() && !Regex("^-?\\d{1,20}$|^@[A-Za-z0-9_]{4,64}$").matches(chatId)) {
            return bad("chat id — це число (напр. 123456789 або -100…) або @назва_каналу", "chat id is a number (e.g. 123456789 or -100…) or @channel_name")
        }
        val sign = f["batterySign"]?.let { it.toIntOrNull()?.takeIf { s -> s in -1..1 } ?: return bad("невірний напрям", "invalid direction") }
        val wh = f["batteryWh"]?.let { it.trim().toIntOrNull()?.takeIf { w -> w in 100..100_000 } ?: return bad("ємність має бути 100–100000 Wh", "capacity must be 100–100000 Wh") }
        val fullV = f["batteryFullVolts"]?.let {
            it.trim().replace(',', '.').toDoubleOrNull()?.takeIf { v -> v in 10.0..70.0 } ?: return bad("напруга повного заряду має бути 10–70 V", "full-charge voltage must be 10–70 V")
        }
        val tgLang = f["tgLang"]?.let { it.takeIf { l -> l == "uk" || l == "en" } ?: return bad("невірна мова", "invalid language") }
        val lowPct = f["phoneLowPercent"]?.let { it.trim().toIntOrNull()?.takeIf { p -> p in 1..90 } ?: return bad("поріг має бути 1–90%", "threshold must be 1–90%") }

        if (token.isNotEmpty()) settings.tgToken = token
        if (f["clearToken"] == "1") settings.tgToken = ""
        chatId?.let { settings.tgChatId = it }
        f["enabled"]?.let { settings.tgEnabled = it == "1" }
        sign?.let { settings.batterySignManual = it }
        wh?.let { settings.batteryWh = it }
        fullV?.let { settings.batteryFullVolts = it }
        f["phoneLowAlert"]?.let { settings.phoneLowAlert = it == "1" }
        f["inverterOfflineAlert"]?.let { settings.inverterOfflineAlert = it == "1" }
        lowPct?.let { settings.phoneLowPercent = it }
        tgLang?.let { settings.tgLang = it; L10n.messages = it }
        if (f["socReset"] == "1") Battery.reset(settings)
        Backup.dirty = true
        return json(settingsJson())
    }

    private fun telegramTest(session: IHTTPSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val f = form(session)
        val token = f["token"].orEmpty().trim().ifEmpty { settings.tgToken }
        val chat = f["chatId"].orEmpty().trim().ifEmpty { settings.tgChatId }
        val lang = f["lang"]
        if (token.isEmpty() || chat.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, L10n.pick(lang, "потрібні токен і chat id", "token and chat id are required"))
        val grid = when (State.gridOn) {
            true -> L10n.msg("світло є", "grid is on")
            false -> L10n.msg("світла немає", "grid is off")
            null -> L10n.msg("стан мережі ще невідомий", "grid state not known yet")
        }
        return try {
            Telegram.send(token, chat, L10n.msg("✅ Must Monitor: тестове повідомлення ($grid)", "✅ Must Monitor: test message ($grid)"))
            json(JSONObject().put("ok", true).toString())
        } catch (e: Exception) {
            jsonError(Response.Status.BAD_REQUEST, e.message ?: "error")
        }
    }

    private fun telegramDetect(session: IHTTPSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val f = form(session)
        val token = f["token"].orEmpty().trim().ifEmpty { settings.tgToken }
        if (token.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, L10n.pick(f["lang"], "спочатку вкажіть токен", "enter the token first"))
        return try {
            val arr = JSONArray()
            for ((id, title) in Telegram.detectChats(token)) arr.put(JSONObject().put("id", id).put("title", title))
            json(JSONObject().put("chats", arr).toString())
        } catch (e: Exception) {
            jsonError(Response.Status.BAD_REQUEST, e.message ?: "error")
        }
    }

    private fun backup(): Response {
        val r = text(Response.Status.OK, "application/json; charset=utf-8", Backup.export(settings).toString())
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(Clock.now()))
        r.addHeader("Content-Disposition", "attachment; filename=\"must-monitor-backup-$day.json\"")
        return r
    }

    private fun restore(session: IHTTPSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val lang = session.parameters["lang"]?.firstOrNull()
        val body = files["postData"] ?: return jsonError(Response.Status.BAD_REQUEST, L10n.pick(lang, "порожній файл", "empty file"))
        return try {
            val summary = Backup.restore(settings, JSONObject(body), Backup.store)
            json(JSONObject(summary as Map<*, *>).put("ok", true).toString())
        } catch (e: Exception) {
            jsonError(Response.Status.BAD_REQUEST, L10n.pick(lang, "не вдалося відновити: ", "restore failed: ") + (e.message ?: "error"))
        }
    }

    private fun events(): String {
        val o = JSONObject()
        o.put("gridOn", State.gridOn ?: JSONObject.NULL)
        o.put("gridSince", State.gridSince)
        val arr = JSONArray()
        for ((ts, on) in EventLog.snapshot()) arr.put(JSONArray().put(ts).put(on))
        o.put("events", arr)
        return o.toString()
    }

    private fun raw(session: IHTTPSession): Response {
        val addr = session.parameters["addr"]?.firstOrNull()?.toIntOrNull()
            ?: return text(Response.Status.BAD_REQUEST, "text/plain", "addr required")
        val count = session.parameters["count"]?.firstOrNull()?.toIntOrNull() ?: 1
        if (addr !in 0..65535 || count !in 1..125 || addr + count > 65536) {
            return text(Response.Status.BAD_REQUEST, "text/plain", "addr must be 0..65535, count 1..125")
        }
        val data = Inverter.raw(addr, count)
        val o = JSONObject()
        data.forEachIndexed { i, v -> o.put((addr + i).toString(), v) }
        return json(o.toString())
    }

    private fun text(status: Response.Status, mime: String, body: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, mime, body)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun json(body: String) = text(Response.Status.OK, "application/json; charset=utf-8", body)

    private fun metrics(): String {
        val sb = StringBuilder()
        val online = State.online()
        sb.append("# HELP must_up 1 if the inverter answered within the last 30s\n")
        sb.append("# TYPE must_up gauge\n")
        sb.append("must_up ").append(if (online) 1 else 0).append('\n')
        sb.append("# HELP must_last_success_timestamp_seconds Unix time of the last successful inverter read\n")
        sb.append("# TYPE must_last_success_timestamp_seconds gauge\n")
        sb.append("must_last_success_timestamp_seconds ").append(State.lastOk / 1000).append('\n')
        State.gridOn?.let {
            sb.append("# HELP must_grid_present 1 if grid voltage is present (debounced)\n")
            sb.append("# TYPE must_grid_present gauge\n")
            sb.append("must_grid_present ").append(if (it) 1 else 0).append('\n')
        }
        State.deviceBatteryPercent?.let {
            sb.append("# HELP must_device_battery_percent Battery of the phone running the app\n")
            sb.append("# TYPE must_device_battery_percent gauge\n")
            sb.append("must_device_battery_percent ").append(fmt(it)).append('\n')
            sb.append("# TYPE must_device_charging gauge\n")
            sb.append("must_device_charging ").append(if (State.deviceCharging) 1 else 0).append('\n')
        }
        if (!online) return sb.toString()

        val values = State.values
        for ((k, v) in values) {
            if (v.isNaN()) continue
            val type = if (k.endsWith("_total")) "counter" else "gauge"
            sb.append("# TYPE ").append(k).append(' ').append(type).append('\n')
            sb.append(k).append(' ').append(fmt(v)).append('\n')
        }
        val ws = values["must_work_state"]?.toInt()
        if (ws != null) {
            sb.append("# TYPE must_work_state_info gauge\n")
            sb.append("must_work_state_info{state=\"")
                .append(Registers.workStates[ws] ?: "Unknown$ws").append("\"} 1\n")
        }
        return sb.toString()
    }

    private fun state(): String {
        val o = JSONObject()
        o.put("now", Clock.now())
        o.put("clockApproximate", Clock.approximate)
        o.put("online", State.online())
        o.put("lastOk", State.lastOk)
        o.put("lastError", State.lastError ?: JSONObject.NULL)
        o.put("chargerError", State.chargerError ?: JSONObject.NULL)
        o.put("device", State.device ?: JSONObject.NULL)
        o.put("batteryWh", settings.batteryWh)
        o.put("gridOn", State.gridOn ?: JSONObject.NULL)
        o.put("gridSince", State.gridSince)
        o.put("socSource", when {
            State.values["must_battery_soc_energy_percent"] != null -> "energy"
            State.values["must_battery_soc_estimate_percent"] != null -> "voltage"
            else -> JSONObject.NULL
        })
        o.put("socReservePercent", Battery.RESERVE_PERCENT)
        o.put("deviceBattery", State.deviceBatteryPercent ?: JSONObject.NULL)
        o.put("deviceCharging", State.deviceCharging)
        o.put("startupErrors", JSONObject(State.startupErrors.toMap()))
        val ws = State.values["must_work_state"]?.toInt()
        o.put("workState", if (ws == null) JSONObject.NULL else Registers.workStates[ws] ?: "Unknown$ws")
        val v = JSONObject()
        for ((k, x) in State.values) v.put(k, if (x.isNaN()) JSONObject.NULL else x)
        o.put("values", v)
        return o.toString()
    }

    private fun history(since: Double): String {
        val o = JSONObject()
        o.put("keys", JSONArray(listOf("ts") + State.historyKeys))
        o.put("retentionSeconds", State.HISTORY_RETENTION_MS / 1000)
        val rows = JSONArray()
        for (r in State.historySince(since)) {
            val a = JSONArray()
            for (x in r) a.put(if (x.isNaN()) JSONObject.NULL else x)
            rows.put(a)
        }
        o.put("rows", rows)
        return o.toString()
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, v)
}
