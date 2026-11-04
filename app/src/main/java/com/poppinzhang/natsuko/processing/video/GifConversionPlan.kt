package com.poppinzhang.natsuko.processing.video

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.requireText
import com.poppinzhang.natsuko.model.GifOptions
import com.poppinzhang.natsuko.processing.VideoFrameSchedule

/** Sampling controls source time; playback fps independently controls GIF time. */
internal data class GifConversionPlan(
    val intervalMs: Long,
    val frameCount: Int,
    val filter: String,
    val repeatCount: Int,
) {
    companion object {
        fun create(durationMs: Long, options: GifOptions, maxHeight: Int = 4096): GifConversionPlan {
            requireText(options.framesPerSecond in 1..30) { text(R.string.backend_gif_fps_invalid) }
            requireText(options.sampleIntervalMs in 50..1000) { text(R.string.backend_gif_interval_invalid) }
            requireText(options.maxWidth in 1..1080) { text(R.string.backend_gif_width_invalid) }
            requireText(options.maxFrames in 1..300) { text(R.string.backend_gif_frames_invalid) }
            requireText(options.loopCount in 0..100) { text(R.string.backend_gif_loop_invalid) }
            val timestamps = VideoFrameSchedule.timestamps(durationMs, options.sampleIntervalMs, options.maxFrames)
            val interval = maxOf(options.sampleIntervalMs, (durationMs - 1) / options.maxFrames + 1)
            val filter = listOf(
                "fps=fps=1000/$interval:start_time=0:round=near:eof_action=pass",
                "trim=end_frame=${timestamps.size}",
                // Account for non-square pixels, cap portrait height and never enlarge the source.
                "scale=w='max(1,trunc(min(min(${options.maxWidth},iw*sar),$maxHeight*iw*sar/ih)))':h='max(1,min($maxHeight,round(ih*ow/(iw*sar))))':flags=lanczos",
                "setsar=1",
                "settb=expr=1/1000",
                "setpts=N/(${options.framesPerSecond}*TB)",
            ).joinToString(",")
            return GifConversionPlan(
                intervalMs = interval,
                frameCount = timestamps.size,
                filter = filter,
                // GIF's Netscape extension counts repetitions after the initial playback.
                repeatCount = when (options.loopCount) { 0 -> 0; 1 -> -1; else -> options.loopCount - 1 },
            )
        }

        /** Bound palette histogram growth to at most 32 × 160 × 160 source colors. */
        fun forPalette(durationMs: Long, options: GifOptions): GifConversionPlan = create(
            durationMs,
            options.copy(maxWidth = minOf(options.maxWidth, 160), maxFrames = minOf(options.maxFrames, 32)),
            maxHeight = 160,
        )
    }
}
