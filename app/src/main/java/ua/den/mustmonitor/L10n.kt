package ua.den.mustmonitor

import java.util.Locale

/** Two UI languages: Ukrainian (default) and English. */
object L10n {
    /** Language of Telegram messages (a setting). */
    @Volatile var messages: String = "uk"

    /** Language of the phone UI (notification, fallback screen): Ukrainian if the system is Ukrainian/Russian. */
    val device: String
        get() = if (Locale.getDefault().language in setOf("uk", "ru")) "uk" else "en"

    fun pick(lang: String?, uk: String, en: String) = if (lang == "en") en else uk

    /** Telegram message text. */
    fun msg(uk: String, en: String) = pick(messages, uk, en)
}
