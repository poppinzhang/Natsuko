package com.poppinzhang.natsuko.processing

import android.content.Context
import android.graphics.ImageDecoder
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.data.twitter.SyndicationTwitterVideoSource
import com.poppinzhang.natsuko.data.twitter.TwitterImage
import com.poppinzhang.natsuko.data.twitter.TwitterImageSource
import com.poppinzhang.natsuko.data.twitter.TwitterStatusLink
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.ProcessingOutcome
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.userMessage
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class VerifiedTwitterImage(
    val mimeType: String,
    val extension: String,
)

class DownloadTwitterImagesProcessor(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val outputRepository: OutputRepository,
    private val source: TwitterImageSource = SyndicationTwitterVideoSource(),
    private val verifyImage: suspend (File) -> VerifiedTwitterImage = { verifyDownloadedTwitterImage(it) },
) {
    suspend fun process(
        link: String,
        onProgress: (Int, Int, UiText) -> Unit,
    ): ProcessingOutcome = withContext(Dispatchers.IO) {
        val post = TwitterStatusLink.parse(link)
        onProgress(0, 1000, text(R.string.backend_reading_post_images))
        val images = source.resolveImages(post)
        checkText(images.isNotEmpty()) { text(R.string.backend_post_no_image_download) }
        currentCoroutineContext().ensureActive()

        val directory = outputRepository.createRunDirectory(Feature.DOWNLOAD_TWITTER_IMAGES)
        val outputs = mutableListOf<OutputArtifact>()
        val failures = mutableListOf<ProcessingFailure>()
        var completed = false
        try {
            images.forEachIndexed { index, image ->
                currentCoroutineContext().ensureActive()
                val imageNumber = index + 1
                val label = text(R.string.backend_post_image_label, post.id, imageNumber)
                val temporary = directory.resolve(".twitter_${post.id}_$imageNumber.part")
                var finalFile: File? = null
                var saved = false
                try {
                    checkText(image.url != null) { image.unavailableReason ?: text(R.string.backend_no_downloadable_image) }
                    onProgress(
                        index * 1000 / images.size,
                        1000,
                        text(R.string.backend_downloading_progress, imageNumber, images.size),
                    )
                    source.downloadImage(image, temporary) { bytes, total ->
                        val withinFile = if (total != null && total > 0) {
                            (bytes.toDouble() / total * 900).toInt().coerceIn(0, 900)
                        } else 0
                        val amount = if (total != null && total > 0) {
                            text(R.string.backend_download_kilobytes_total, bytes / 1024, total / 1024)
                        } else {
                            text(R.string.backend_download_kilobytes, bytes / 1024)
                        }
                        onProgress(
                            (index * 1000 + withinFile) / images.size,
                            1000,
                            text(R.string.backend_downloading_bytes, imageNumber, images.size, amount),
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    checkText(temporary.length() > 0) { text(R.string.backend_download_image_empty) }
                    onProgress(
                        (index * 1000 + 950) / images.size,
                        1000,
                        text(R.string.backend_validating_image, imageNumber, images.size),
                    )
                    val verified = verifyImage(temporary)
                    val extension = verified.extension.ifBlank { image.formatExtension }
                    val mimeType = verified.mimeType.ifBlank { mimeTypeForExtension(extension) }
                    val file = directory.resolve("twitter_${post.id}_$imageNumber.$extension")
                    finalFile = file
                    currentCoroutineContext().ensureActive()
                    Files.move(temporary.toPath(), file.toPath())
                    outputs += OutputArtifact(file, mimeType, label)
                    saved = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    failures += ProcessingFailure(label, error.userMessage(R.string.backend_download_failed))
                } finally {
                    temporary.delete()
                    if (!saved) finalFile?.delete()
                }
                onProgress(
                    imageNumber * 1000 / images.size,
                    1000,
                    text(R.string.backend_images_processed, imageNumber, images.size),
                )
            }
            currentCoroutineContext().ensureActive()
            completed = true
            ProcessingOutcome(outputs, failures)
        } finally {
            // An abandoned batch has no result page; do not leave unreviewable files behind.
            if (!completed) directory.deleteRecursively() else if (outputs.isEmpty()) directory.delete()
        }
    }

    companion object {
        private const val VERIFY_MAX_EDGE = 2048

        internal fun verifyDownloadedTwitterImage(file: File): VerifiedTwitterImage = try {
            var detectedMimeType = ""
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                checkText(info.size.width > 0 && info.size.height > 0) { text(R.string.backend_download_image_invalid) }
                detectedMimeType = info.mimeType.lowercase(Locale.ROOT)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > VERIFY_MAX_EDGE) {
                    val scale = VERIFY_MAX_EDGE.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
            bitmap.recycle()
            normalizedFormat(detectedMimeType)
        } catch (localized: LocalizedIllegalStateException) {
            throw localized
        } catch (error: Exception) {
            throw LocalizedIllegalStateException(text(R.string.backend_download_image_invalid), error)
        }

        private fun normalizedFormat(mimeType: String): VerifiedTwitterImage = when (mimeType) {
            "image/jpeg", "image/jpg" -> VerifiedTwitterImage("image/jpeg", "jpg")
            "image/png" -> VerifiedTwitterImage("image/png", "png")
            "image/webp" -> VerifiedTwitterImage("image/webp", "webp")
            "image/gif" -> VerifiedTwitterImage("image/gif", "gif")
            "image/heif", "image/heic" -> VerifiedTwitterImage(mimeType, "heic")
            "image/avif" -> VerifiedTwitterImage("image/avif", "avif")
            "image/bmp", "image/x-ms-bmp" -> VerifiedTwitterImage("image/bmp", "bmp")
            else -> VerifiedTwitterImage("", "")
        }

        private fun mimeTypeForExtension(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            else -> "image/jpeg"
        }
    }
}
