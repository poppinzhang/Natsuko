package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.quantityText
import com.poppinzhang.natsuko.model.userMessage
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.errorText
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import android.content.Context
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.GifOptions
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.ProcessingOutcome
import com.poppinzhang.natsuko.processing.video.FfmpegVideoEngine
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object VideoFrameSchedule {
    fun timestamps(durationMs: Long, requestedIntervalMs: Long, maxFrames: Int): List<Long> {
        require(durationMs > 0)
        require(requestedIntervalMs > 0)
        require(maxFrames > 0)
        val coveringInterval = (durationMs - 1) / maxFrames + 1
        val interval = maxOf(requestedIntervalMs, coveringInterval)
        return generateSequence(0L) { previous ->
            if (interval >= durationMs - previous) null else previous + interval
        }.take(maxFrames).toList()
    }
}

class VideoToGifProcessor(
    private val context: Context,
    private val outputRepository: OutputRepository,
) {
    private val videoEngine = FfmpegVideoEngine(context)

    suspend fun process(
        input: InputItem,
        options: GifOptions,
        onProgress: (Int, Int, UiText) -> Unit,
    ): ProcessingOutcome = withContext(Dispatchers.IO) {
        val cacheRoot = File(context.cacheDir, "video-processing")
        checkText(cacheRoot.mkdirs() || cacheRoot.isDirectory) { text(R.string.backend_video_cache_failed) }
        val workspace = Files.createTempDirectory(cacheRoot.toPath(), "gif-").toFile()
        var output: File? = null
        var completed = false
        try {
            currentCoroutineContext().ensureActive()
            onProgress(0, 1000, text(R.string.backend_importing_video))
            val localInput = File(workspace, "input.video")
            // A private seekable copy also supports cloud/pipe-backed content URIs and transient grants.
            context.contentResolver.openInputStream(input.uri)?.use { source ->
                localInput.outputStream().use { destination ->
                    val buffer = ByteArray(64 * 1024)
                    var imported = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        destination.write(buffer, 0, count)
                        imported += count
                        onProgress(0, 1000, text(R.string.backend_importing_video_bytes, imported / 1024))
                    }
                }
            } ?: errorText(text(R.string.backend_video_open_failed))
            checkText(localInput.length() > 0) { text(R.string.backend_video_empty) }
            output = outputRepository.createRunDirectory(Feature.VIDEO_TO_GIF)
                .resolve("${OutputRepository.safeStem(input.displayName)}.gif")
            val frames = videoEngine.createGif(localInput, output, options, onProgress)
            currentCoroutineContext().ensureActive()
            completed = true
            ProcessingOutcome(outputs = listOf(OutputArtifact(output, "image/gif", quantityText(R.plurals.backend_gif_output_label, frames, input.displayName, frames))))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            throw LocalizedIllegalStateException(error.userMessage(R.string.backend_video_processing_failed), error)
        } finally {
            workspace.deleteRecursively()
            if (!completed) {
                output?.delete()
                output?.parentFile?.delete()
            }
        }
    }
}
