package com.poppinzhang.natsuko.model

import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.PluralsRes
import java.io.IOException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Retains resource identifiers so results and in-flight progress follow the current app language. */
sealed interface UiText {
    data class Literal(val value: String) : UiText
    data class Resource(@param:StringRes val id: Int, val arguments: List<Any> = emptyList()) : UiText
    data class Plural(@param:PluralsRes val id: Int, val quantity: Int, val arguments: List<Any> = emptyList()) : UiText

    fun resolve(context: Context): String = when (this) {
        is Literal -> value
        is Resource -> context.getString(id, *arguments.resolveArguments(context))
        is Plural -> context.resources.getQuantityString(id, quantity, *arguments.resolveArguments(context))
    }
}

private fun List<Any>.resolveArguments(context: Context): Array<Any> =
    map { if (it is UiText) it.resolve(context) else it }.toTypedArray()

fun text(@StringRes id: Int, vararg arguments: Any): UiText = UiText.Resource(id, arguments.toList())
fun quantityText(@PluralsRes id: Int, quantity: Int, vararg arguments: Any): UiText =
    UiText.Plural(id, quantity, arguments.toList())

/** Backend failures carry translatable messages without capturing an Android context or a locale. */
interface LocalizedFailure { val userMessage: UiText }
class LocalizedIllegalArgumentException(override val userMessage: UiText) :
    IllegalArgumentException(userMessage.toString()), LocalizedFailure
class LocalizedIllegalStateException(override val userMessage: UiText, cause: Throwable? = null) :
    IllegalStateException(userMessage.toString(), cause), LocalizedFailure
class LocalizedIOException(override val userMessage: UiText, cause: Throwable? = null) :
    IOException(userMessage.toString(), cause), LocalizedFailure

/** Raw platform/library diagnostics remain in the cause; the UI gets a localized actionable fallback. */
fun Throwable.userMessage(@StringRes fallback: Int): UiText =
    (this as? LocalizedFailure)?.userMessage ?: (cause as? LocalizedFailure)?.userMessage ?: text(fallback)

@OptIn(ExperimentalContracts::class)
inline fun requireText(value: Boolean, lazyMessage: () -> UiText) {
    contract { returns() implies value }
    if (!value) throw LocalizedIllegalArgumentException(lazyMessage())
}

@OptIn(ExperimentalContracts::class)
inline fun checkText(value: Boolean, lazyMessage: () -> UiText) {
    contract { returns() implies value }
    if (!value) throw LocalizedIllegalStateException(lazyMessage())
}

@OptIn(ExperimentalContracts::class)
inline fun <T : Any> checkNotNullText(value: T?, lazyMessage: () -> UiText): T {
    contract { returns() implies (value != null) }
    if (value == null) throw LocalizedIllegalStateException(lazyMessage())
    return value
}

fun errorText(message: UiText): Nothing = throw LocalizedIllegalStateException(message)
