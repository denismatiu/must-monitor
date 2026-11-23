package ua.den.mustmonitor

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingDeque

class TelegramException(val code: Int, msg: String) : IOException(msg)

/**
 * Sends messages through the Bot API. Messages are queued and retried, because
 * when the power goes out the internet often goes with it for a while.
 * The queue is persisted to a file, so a restart mid-outage doesn't lose messages.
 */
object Telegram {
    private const val RETRY_MS = 30_000L
    private const val MAX_AGE_MS = 24 * 3600_000L
    private val TOKEN_RE = Regex("^[0-9]+:[A-Za-z0-9_-]+$")

    private class Msg(val text: String, val created: Long)

    private val queue = LinkedBlockingDeque<Msg>()
    private lateinit var settings: Settings
    private var file: File? = null
    @Volatile private var started = false

    @Volatile var lastError: String? = null
    @Volatile var lastSent: Long = 0

    @Synchronized
    fun start(s: Settings, dir: File) {
        settings = s
        if (started) return
        file = File(dir, "telegram-queue.txt")
        load()
        started = true
        Thread(::loop, "telegram").apply { isDaemon = true }.start()
    }

    fun enqueue(text: String) {
        queue.offer(Msg(text, Clock.now()))
        persist()
    }

    // One message per line: "<created ms>\t<text with \\ and \n escaped>"
    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            f.readLines().forEach { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEach
                val created = line.substring(0, tab).toLongOrNull() ?: return@forEach
                queue.offer(Msg(unescape(line.substring(tab + 1)), created))
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun persist() {
        val f = file ?: return
        try {
            val snapshot = queue.toList()
            if (snapshot.isEmpty()) f.delete()
            else f.writeAtomically { w -> snapshot.forEach { w.write("${it.created}\t${escape(it.text)}\n") } }
        } catch (_: Exception) {
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(if (s[i + 1] == 'n') '\n' else s[i + 1])
                i += 2
            } else {
                append(c); i++
            }
        }
    }

    fun validToken(token: String) = TOKEN_RE.matches(token)

    private fun loop() {
        while (true) {
            val m = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }
            if (!settings.telegramReady || Clock.now() - m.created > MAX_AGE_MS) {
                persist()
                continue
            }
            try {
                send(settings.tgToken, settings.tgChatId, m.text)
                persist()
            } catch (e: TelegramException) {
                // 4xx = bad token / chat id; retrying won't help, except for rate limiting
                if (e.code == 429 || e.code >= 500) retryLater(m) else persist()
            } catch (e: Exception) {
                retryLater(m)
            }
        }
    }

    private fun retryLater(m: Msg) {
        queue.offerFirst(m)
        try {
            Thread.sleep(RETRY_MS)
        } catch (_: InterruptedException) {
        }
    }

    fun send(token: String, chatId: String, text: String) {
        try {
            call(token, "sendMessage", mapOf("chat_id" to chatId, "text" to text))
            lastSent = Clock.now()
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            throw e
        }
    }

    /** Chats that recently wrote to the bot, as (id, title). */
    fun detectChats(token: String): List<Pair<String, String>> {
        val res = call(token, "getUpdates", null).optJSONArray("result") ?: return emptyList()
        val out = LinkedHashMap<String, String>()
        for (i in 0 until res.length()) {
            val u = res.getJSONObject(i)
            for (key in listOf("message", "channel_post", "my_chat_member", "edited_message")) {
                val chat = u.optJSONObject(key)?.optJSONObject("chat") ?: continue
                val id = chat.opt("id")?.toString() ?: continue
                val title = chat.optString("title").ifBlank {
                    listOf(chat.optString("first_name"), chat.optString("last_name"))
                        .filter { it.isNotBlank() }.joinToString(" ")
                }.ifBlank { chat.optString("username") }
                out[id] = title
            }
        }
        return out.map { it.key to it.value }
    }

    private fun call(token: String, method: String, form: Map<String, String>?): JSONObject {
        if (!validToken(token)) throw TelegramException(400, "invalid token format / невірний формат токена")
        val c = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            if (form != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                val body = form.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val j = try {
                JSONObject(text)
            } catch (_: Exception) {
                null
            }
            if (code !in 200..299 || j?.optBoolean("ok") != true) {
                throw TelegramException(code, j?.optString("description")?.ifBlank { null } ?: "HTTP $code")
            }
            return j
        } finally {
            c.disconnect()
        }
    }
}
