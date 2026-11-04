package com.poppinzhang.natsuko.processing.video

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.quantityText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.errorText
import android.content.Context
import com.poppinzhang.natsuko.model.GifOptions
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToLong
import org.json.JSONObject

/** All video inspection, decoding, filtering and encoding belongs behind this embedded FFmpeg API. */
class FfmpegVideoEngine(context: Context) {
    private val runner = FfmpegRunner(context.applicationContext)
    private val cacheDirectory = context.applicationContext.cacheDir

    suspend fun probe(file: File, onWaiting: () -> Unit = {}): VideoMetadata {
        val result = runner.run(
            probe = true,
            arguments = listOf(
                "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "V:0",
                "-show_entries", "stream=width,height,duration:stream_side_data=rotation:format=duration",
                "-of", "json", file.absolutePath,
            ),
            onProgress = { onWaiting() },
        )
        val document = JSONObject(result)
        val stream = document.optJSONArray("streams")?.optJSONObject(0)
            ?: errorText(text(R.string.backend_video_no_frames))
        val streamDuration = stream.optDouble("duration")
        val seconds = if (streamDuration.isFinite() && streamDuration > 0) streamDuration else {
            document.optJSONObject("format")?.optDouble("duration") ?: Double.NaN
        }
        checkText(seconds.isFinite() && seconds > 0 && seconds < Long.MAX_VALUE / 1000.0) { text(R.string.backend_video_duration_invalid) }
        val width = stream.optInt("width")
        val height = stream.optInt("height")
        checkText(width > 0 && height > 0) { text(R.string.backend_video_dimensions_invalid) }
        val sideData = stream.optJSONArray("side_data_list")
        var rotation = 0
        if (sideData != null) for (index in 0 until sideData.length()) {
            val entry = sideData.optJSONObject(index) ?: continue
            if (entry.has("rotation")) rotation = entry.optInt("rotation")
        }
        val swapped = ((rotation % 360) + 360) % 180 == 90
        return VideoMetadata(
            durationMs = (seconds * 1000).roundToLong().coerceAtLeast(1),
            width = if (swapped) height else width,
            height = if (swapped) width else height,
        )
    }

    suspend fun createGif(
        input: File,
        output: File,
        options: GifOptions,
        onProgress: (Int, Int, UiText) -> Unit,
    ): Int {
        onProgress(0, 1000, text(R.string.backend_reading_video_info))
        val metadata = probe(input) { onProgress(0, 1000, text(R.string.backend_reading_video_info)) }
        val plan = GifConversionPlan.create(metadata.durationMs, options)
        val palettePlan = GifConversionPlan.forPalette(metadata.durationMs, options)
        val palette = Files.createTempFile(cacheDirectory.toPath(), "gif-palette-", ".png").toFile()
        var completed = false
        var lastProgress = 0
        fun report(value: Int, message: UiText) {
            lastProgress = maxOf(lastProgress, value.coerceIn(0, 1000))
            onProgress(lastProgress, 1000, message)
        }
        val inputArguments = listOf(
            "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-xerror",
            "-progress", "pipe:1", "-stats_period", "0.1", "-filter_complex_threads", "1",
            "-threads", "2", "-protocol_whitelist", "file,pipe", "-i", input.absolutePath,
        )
        try {
            // The null branch lets FFmpeg report progress while the global palette is accumulated.
            // Only the palette histogram and a few in-flight frames are kept in memory.
            report(20, text(R.string.backend_analyzing_video_colors))
            runner.run(
                arguments = inputArguments + listOf(
                    "-filter_complex", "[0:V:0]${palettePlan.filter},split[colors][timing];[colors]palettegen=reserve_transparent=1[palette]",
                    "-map", "[timing]", "-an", "-c:v", "wrapped_avframe", "-enc_time_base", "1:100",
                    "-fps_mode", "passthrough", "-f", "null", "-",
                    "-map", "[palette]", "-frames:v", "1", "-threads", "1", "-update", "1", palette.absolutePath,
                ),
            ) { progress ->
                val fraction = progress.frame.toDouble() / palettePlan.frameCount
                report(20 + (fraction.coerceIn(0.0, 1.0) * 430).toInt(), text(R.string.backend_analyzing_video_colors))
            }
            checkText(palette.isFile && palette.length() > 0) { text(R.string.backend_video_palette_failed) }
            report(450, text(R.string.backend_generating_gif))
            var encodedFrames = 0
            runner.run(
                arguments = inputArguments + listOf(
                    "-i", palette.absolutePath,
                    "-filter_complex", "[0:V:0]${plan.filter}[frames];[frames][1:v]paletteuse=dither=sierra2_4a:diff_mode=rectangle[gif]",
                    "-map", "[gif]", "-an", "-sn", "-dn", "-c:v", "gif", "-threads", "1",
                    "-frames:v", plan.frameCount.toString(), "-enc_time_base", "1:100",
                    "-fps_mode", "passthrough", "-loop", plan.repeatCount.toString(),
                    "-final_delay", ((100 + options.framesPerSecond / 2) / options.framesPerSecond).toString(),
                    "-f", "gif", output.absolutePath,
                ),
            ) { progress ->
                encodedFrames = maxOf(encodedFrames, progress.frame)
                report(450 + (progress.frame.toDouble() / plan.frameCount * 540).toInt(), text(R.string.backend_encoding_gif, progress.frame))
            }
            checkText(encodedFrames > 0 && output.isFile && output.length() > 0) { text(R.string.backend_video_no_decodable_frames) }
            report(1000, quantityText(R.plurals.backend_gif_complete, encodedFrames, encodedFrames))
            completed = true
            return encodedFrames
        } finally {
            palette.delete()
            if (!completed) output.delete()
        }
    }
}

data class VideoMetadata(val durationMs: Long, val width: Int, val height: Int)
