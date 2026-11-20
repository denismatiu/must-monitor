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
            when (session.uri) {
                "/", "/index.html" -> text(Response.Status.OK, "text/html; charset=utf-8", index)
                "/metrics" -> text(Response.Status.OK, "text/plain; version=0.0.4; charset=utf-8", metrics())
                "/api/state" -> json(state())
                "/api/history" -> json(history())
                "/api/events" -> json(events())
                "/api/raw" -> raw(session)
                "/api/settings" -> settingsApi(session)
                "/api/telegram/test" -> telegramTest(session)
                "/api/telegram/detect" -> telegramDetect(session)
                else -> text(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (e: Exception) {
            text(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
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
            .toString()
    }

    private fun settingsApi(session: IHTTPSession): Response {
        if (session.method != Method.POST) return json(settingsJson())
        val f = form(session)
        val token = f["token"].orEmpty().trim()
        if (token.isNotEmpty()) {
            if (!Telegram.validToken(token)) return jsonError(Response.Status.BAD_REQUEST, "невірний формат токена")
            settings.tgToken = token
        }
        if (f["clearToken"] == "1") settings.tgToken = ""
        f["chatId"]?.let { settings.tgChatId = it }
        f["enabled"]?.let { settings.tgEnabled = it == "1" }
        f["batterySign"]?.toIntOrNull()?.let { if (it in -1..1) settings.batterySignManual = it }
        return json(settingsJson())
    }

    private fun telegramTest(session: IHTTPSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val f = form(session)
        val token = f["token"].orEmpty().trim().ifEmpty { settings.tgToken }
        val chat = f["chatId"].orEmpty().trim().ifEmpty { settings.tgChatId }
        if (token.isEmpty() || chat.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "потрібні токен і chat id")
        val grid = when (State.gridOn) { true -> "світло є"; false -> "світла немає"; null -> "стан мережі ще невідомий" }
        return try {
            Telegram.send(token, chat, "✅ Must Monitor: тестове повідомлення ($grid)")
            json(JSONObject().put("ok", true).toString())
        } catch (e: Exception) {
            jsonError(Response.Status.BAD_REQUEST, e.message ?: "error")
        }
    }

    private fun telegramDetect(session: IHTTPSession): Response {
        if (session.method != Method.POST) return jsonError(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val token = form(session)["token"].orEmpty().trim().ifEmpty { settings.tgToken }
        if (token.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "спочатку вкажіть токен")
        return try {
            val arr = JSONArray()
            for ((id, title) in Telegram.detectChats(token)) arr.put(JSONObject().put("id", id).put("title", title))
            json(JSONObject().put("chats", arr).toString())
        } catch (e: Exception) {
            jsonError(Response.Status.BAD_REQUEST, e.message ?: "error")
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
        val data = Inverter.raw(addr, count.coerceIn(1, 125))
        val o = JSONObject()
        data.forEachIndexed { i, v -> o.put((addr + i).toString(), v) }
        return json(o.toString())
    }

    private fun text(status: Response.Status, mime: String, body: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, mime, body)
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun json(body: String) = text(Response.Status.OK, "application/json; charset=utf-8", body)

    private fun metrics(): String {
        val sb = StringBuilder()
        val online = State.online()
        sb.append("# HELP must_up 1 if the inverter answered within the last 30s\n")
        sb.append("# TYPE must_up gauge\n")
        sb.append("must_up ").append(if (online) 1 else 0).append('\n')
        sb.append("# TYPE must_last_success_timestamp_seconds gauge\n")
        sb.append("must_last_success_timestamp_seconds ").append(State.lastOk / 1000).append('\n')
        State.gridOn?.let {
            sb.append("# HELP must_grid_present 1 if grid voltage is present (debounced)\n")
            sb.append("# TYPE must_grid_present gauge\n")
            sb.append("must_grid_present ").append(if (it) 1 else 0).append('\n')
        }
        if (!online) return sb.toString()

        val values = State.values
        for ((k, v) in values) {
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
        o.put("online", State.online())
        o.put("lastOk", State.lastOk)
        o.put("lastError", State.lastError ?: JSONObject.NULL)
        o.put("chargerError", State.chargerError ?: JSONObject.NULL)
        o.put("device", State.device ?: JSONObject.NULL)
        o.put("batteryWh", Registers.BATTERY_WH)
        o.put("gridOn", State.gridOn ?: JSONObject.NULL)
        o.put("gridSince", State.gridSince)
        val ws = State.values["must_work_state"]?.toInt()
        o.put("workState", if (ws == null) JSONObject.NULL else Registers.workStates[ws] ?: "Unknown$ws")
        val v = JSONObject()
        for ((k, x) in State.values) v.put(k, if (x.isNaN()) JSONObject.NULL else x)
        o.put("values", v)
        return o.toString()
    }

    private fun history(): String {
        val o = JSONObject()
        o.put("keys", JSONArray(listOf("ts") + State.historyKeys))
        val rows = JSONArray()
        for (r in State.historySnapshot()) {
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
