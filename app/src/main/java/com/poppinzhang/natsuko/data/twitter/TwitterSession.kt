package com.poppinzhang.natsuko.data.twitter

import java.net.URI

/** Authentication material is never a data class, Parcelable, UI state, or plaintext preference. */
class TwitterSession private constructor(
    private val authToken: String,
    internal val csrfToken: String,
) {
    internal val cookieHeader: String get() = "auth_token=$authToken; ct0=$csrfToken"

    override fun toString(): String = "TwitterSession(redacted)"

    companion object {
        /** Keep only the two cookies needed by X. Never pass the browser's whole cookie jar onward. */
        fun fromCookieHeader(header: String?): TwitterSession? {
            if (header == null || header.length > 32_768 || header.any { it == '\r' || it == '\n' }) return null
            val values = mutableMapOf<String, String>()
            for (part in header.split(';')) {
                val pair = part.trim()
                val separator = pair.indexOf('=')
                if (separator < 1) continue
                val name = pair.substring(0, separator)
                if (name != "auth_token" && name != "ct0") continue
                val value = pair.substring(separator + 1)
                if (name in values || !validCookieValue(value)) return null
                values[name] = value
            }
            return TwitterSession(values["auth_token"] ?: return null, values["ct0"] ?: return null)
        }

        // RFC 6265 cookie-octet, with a bound before values become HTTP headers.
        private fun validCookieValue(value: String): Boolean = value.length in 1..4096 && value.all {
            it.code == 0x21 || it.code in 0x23..0x2B || it.code in 0x2D..0x3A ||
                it.code in 0x3C..0x5B || it.code in 0x5D..0x7E
        }
    }
}

/** Top-level login navigation is confined to X; never dispatch an intent or another browser. */
object TwitterLoginUrl {
    // Start at X's public entry, then let the user choose its sign-in action. Deep-linking
    // into its asynchronous onboarding flow can initially show only an empty shell and Back.
    const val START = "https://x.com/"

    fun isAllowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com") &&
            uri.userInfo == null && uri.port in listOf(-1, 443)
    }.getOrDefault(false)

    fun origin(url: String): String? = if (isAllowed(url)) "https://${URI(url).host.lowercase()}" else null
}
