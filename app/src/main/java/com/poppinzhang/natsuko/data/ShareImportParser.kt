package com.poppinzhang.natsuko.data

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.requireText
import android.content.Intent
import android.net.Uri
import com.poppinzhang.natsuko.model.Feature

object ShareImportParser {
    /**
     * A share that has not been imported yet. Reopening a finished share task from Recents
     * delivers the intent that created it once more, marked as launched from history; that share
     * was already handled, and importing it again would repeat automatic processing.
     */
    fun isNewShare(intent: Intent): Boolean =
        (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) &&
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0

    fun feature(intent: Intent): Feature? = Feature.fromShareComponent(intent.component?.className)

    /** Read text only; never dereference a URI or HTML supplied by another application. */
    fun twitterText(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        return runCatching {
            val texts = mutableListOf<String>()
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                requireText(it.length <= 8192) { text(R.string.backend_share_text_too_long) }
                texts += it.toString()
            }
            intent.clipData?.let { clip ->
                requireText(clip.itemCount <= 100) { text(R.string.backend_share_too_many_items) }
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index).text?.takeIf { it.isNotBlank() }?.let {
                        requireText(it.length <= 8192) { text(R.string.backend_share_text_too_long) }
                        texts += it.toString()
                    }
                }
            }
            texts.distinct().joinToString("\n").takeIf { it.isNotBlank() && it.length <= 8192 }
        }.getOrNull()
    }

    fun uris(intent: Intent): List<Uri> {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        val result = mutableListOf<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(result::add)
        }
        if (intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(result::add)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(result::addAll)
        }
        return result.distinct()
    }
}
