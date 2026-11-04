package com.poppinzhang.natsuko.data.twitter

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.requireText
import java.net.URI
import java.util.Locale

class TwitterStatusLink private constructor(val id: String) {
    val canonicalUrl: String get() = "https://x.com/i/status/$id"

    companion object {
        private val hosts = setOf("x.com", "twitter.com").flatMap { host ->
            listOf(host, "www.$host", "mobile.$host", "m.$host")
        }.toSet()
        private val urls = Regex("https?://[^\\s<>\\\"'，。！？（）【】]+", RegexOption.IGNORE_CASE)
        private val statusPath = Regex("/(?:[A-Za-z0-9_]+/status|i/web/status|i/status|statuses)/([1-9][0-9]{0,19})(?:/(?:video|photo)/[1-9][0-9]*)?/?")

        fun parse(text: String): TwitterStatusLink {
            requireText(text.length <= 8192) { text(R.string.backend_post_link_too_long) }
            val ids = urls.findAll(text.trim()).mapNotNull { match ->
                val url = match.value.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
                val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
                if (uri.host?.lowercase(Locale.ROOT) !in hosts || uri.userInfo != null ||
                    uri.port !in listOf(-1, 80, 443)
                ) return@mapNotNull null
                statusPath.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
            }.distinct().toList()
            requireText(ids.isNotEmpty()) { text(R.string.backend_post_link_invalid) }
            requireText(ids.size == 1) { text(R.string.backend_post_link_single) }
            return TwitterStatusLink(ids.single())
        }
    }
}

/** The public embed endpoint uses ((Number(id) / 1e15) * Math.PI).toString(36), without dots/zeros. */
internal object TwitterSyndicationToken {
    fun create(id: String): String {
        val number = id.toDouble() / 1e15 * Math.PI
        require(number.isFinite() && number > 0)
        var integer = number.toLong()
        var fraction = number - integer
        var tolerance = maxOf(Double.MIN_VALUE, Math.ulp(number) / 2)
        val digits = mutableListOf<Int>()
        while (fraction >= tolerance) {
            fraction *= 36
            tolerance *= 36
            val digit = fraction.toInt()
            digits += digit
            fraction -= digit
            if ((fraction > 0.5 || (fraction == 0.5 && digit % 2 != 0)) && fraction + tolerance > 1) {
                var carry = true
                while (digits.isNotEmpty() && carry) {
                    val last = digits.removeAt(digits.lastIndex) + 1
                    if (last < 36) {
                        digits += last
                        carry = false
                    }
                }
                if (carry) integer += 1
                break
            }
        }
        return (integer.toString(36) + digits.joinToString("") { it.toString(36) }).replace("0", "")
    }
}
