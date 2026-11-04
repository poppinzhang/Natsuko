package com.poppinzhang.natsuko.processing.video

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class FfmpegProgress(val frame: Int = 0, val outputTimeUs: Long = 0)

/** The only process boundary for embedded FFmpeg tools. Arguments never pass through a shell. */
internal class FfmpegRunner(context: Context) {
    private val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)

    suspend fun run(
        probe: Boolean = false,
        arguments: List<String>,
        onProgress: (FfmpegProgress) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val executable = File(nativeDirectory, if (probe) "libnatsuko_ffprobe.so" else "libnatsuko_ffmpeg.so")
        checkText(executable.isFile && executable.canExecute()) { text(R.string.backend_ffmpeg_missing) }
        val progress = AtomicReference(FfmpegProgress())
        val stdout = BoundedText(1024 * 1024)
        val stderr = BoundedText(16 * 1024)
        val readerFailure = AtomicReference<Throwable?>(null)
        val process = ProcessBuilder(listOf(executable.absolutePath) + arguments).start()
        process.outputStream.close()
        val readers = listOf(
            thread(name = "natsuko-ffmpeg-stdout", isDaemon = true) {
                try {
                    if (probe) stdout.read(process.inputStream) else {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                val value = line.substringAfter('=', "").trim()
                                when (line.substringBefore('=')) {
                                    "frame" -> value.toIntOrNull()?.let { frame -> progress.updateAndGet { it.copy(frame = frame) } }
                                    "out_time_us" -> value.toLongOrNull()?.let { time -> progress.updateAndGet { it.copy(outputTimeUs = time) } }
                                }
                            }
                        }
                    }
                } catch (error: Exception) { readerFailure.compareAndSet(null, error) }
            },
            thread(name = "natsuko-ffmpeg-stderr", isDaemon = true) {
                try { stderr.read(process.errorStream) }
                catch (error: Exception) { readerFailure.compareAndSet(null, error) }
            },
        )
        try {
            do {
                currentCoroutineContext().ensureActive()
                onProgress(progress.get())
            } while (!process.waitFor(100, TimeUnit.MILLISECONDS))
            readers.forEach { it.join() }
            currentCoroutineContext().ensureActive()
            onProgress(progress.get())
            checkText(process.exitValue() == 0) {
                val reason = if (probe) text(R.string.backend_ffmpeg_probe_failed) else text(R.string.backend_ffmpeg_conversion_failed)
                text(R.string.backend_ffmpeg_error_detail, reason, errorHint(stderr.text()))
            }
            readerFailure.get()?.let { throw LocalizedIllegalStateException(text(R.string.backend_ffmpeg_result_failed), it) }
            stdout.text()
        } finally {
            // Wait for the child before callers remove temporary input/palette/partial output files.
            if (process.isAlive) process.destroyForcibly()
            process.waitFor()
            process.inputStream.close()
            process.errorStream.close()
            readers.forEach { it.join() }
        }
    }

    private fun errorHint(log: String): UiText = when {
        log.contains("No space left", ignoreCase = true) -> text(R.string.backend_storage_full)
        log.contains("Permission denied", ignoreCase = true) -> text(R.string.backend_file_permission_denied)
        log.contains("Decoder", ignoreCase = true) && log.contains("not found", ignoreCase = true) -> text(R.string.backend_video_codec_unsupported)
        else -> text(R.string.backend_try_another_video)
    }

    private class BoundedText(private val limit: Int) {
        private val content = StringBuilder()
        fun read(input: InputStream) {
            input.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    content.append(buffer, 0, count)
                    if (content.length > limit) content.delete(0, content.length - limit)
                }
            }
        }
        // Access only after joining the corresponding reader thread.
        fun text(): String = content.toString()
    }
}
