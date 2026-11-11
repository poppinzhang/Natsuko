package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.userMessage
import com.poppinzhang.natsuko.model.checkText
import android.content.Context
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.data.twitter.SyndicationTwitterVideoSource
import com.poppinzhang.natsuko.data.twitter.TwitterDownloadScope
import com.poppinzhang.natsuko.data.twitter.TwitterStatusLink
import com.poppinzhang.natsuko.data.twitter.TwitterVideoSelection
import com.poppinzhang.natsuko.data.twitter.TwitterVideoSource
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.ProcessingOutcome
import com.poppinzhang.natsuko.processing.video.FfmpegVideoEngine
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class DownloadTwitterVideoProcessor(
    context: Context,
    private val outputRepository: OutputRepository,
    private val source: TwitterVideoSource = SyndicationTwitterVideoSource(),
    private val verifyVideo: suspend (File) -> Unit = { FfmpegVideoEngine(context).probe(it); Unit },
) {
    suspend fun process(
        link: String,
        onProgress: (Int, Int, UiText) -> Unit,
    ): ProcessingOutcome = process(link, { TwitterDownloadScope.ALL }, onProgress)

    suspend fun process(
        link: String,
        chooseScope: suspend (TwitterVideoSelection) -> TwitterDownloadScope,
        onProgress: (Int, Int, UiText) -> Unit,
    ): ProcessingOutcome = withContext(Dispatchers.IO) {
        val post = TwitterStatusLink.parse(link)
        onProgress(0, 1000, text(R.string.backend_reading_post))
        val videos = source.resolve(post)
        checkText(videos.isNotEmpty()) { text(R.string.backend_post_no_download) }
        val indexedVideos = videos.withIndex().toList()
        val selectedVideos = post.videoIndex?.let { mediaIndex ->
            val current = indexedVideos.singleOrNull { (it.value.mediaIndex ?: it.index + 1) == mediaIndex }
            checkText(current != null) { text(R.string.backend_post_video_index_invalid) }
            if (videos.size > 1 && chooseScope(TwitterVideoSelection(mediaIndex, videos.size)) == TwitterDownloadScope.CURRENT) {
                listOf(checkNotNull(current))
            } else indexedVideos
        } ?: indexedVideos
        currentCoroutineContext().ensureActive()
        val directory = outputRepository.createRunDirectory(Feature.DOWNLOAD_TWITTER_VIDEO)
        val outputs = mutableListOf<OutputArtifact>()
        val failures = mutableListOf<ProcessingFailure>()
        var completed = false
        try {
            selectedVideos.forEachIndexed { index, indexedVideo ->
                currentCoroutineContext().ensureActive()
                val video = indexedVideo.value
                val videoNumber = indexedVideo.index + 1
                val label = text(if (video.animatedGif) R.string.backend_post_gif_label else R.string.backend_post_video_label, post.id, videoNumber)
                val file = directory.resolve("twitter_${post.id}_$videoNumber.mp4")
                val temporary = directory.resolve(".${file.name}.part")
                var saved = false
                try {
                    checkText(video.url != null) { video.unavailableReason ?: text(R.string.backend_video_no_mp4) }
                    onProgress(index * 1000 / selectedVideos.size, 1000, text(R.string.backend_downloading_progress, index + 1, selectedVideos.size))
                    source.download(video, temporary) { bytes, total ->
                        val withinFile = if (total != null && total > 0) (bytes.toDouble() / total * 900).toInt().coerceIn(0, 900) else 0
                        val amount = if (total != null && total > 0) {
                            text(R.string.backend_download_kilobytes_total, bytes / 1024, total / 1024)
                        } else {
                            text(R.string.backend_download_kilobytes, bytes / 1024)
                        }
                        onProgress((index * 1000 + withinFile) / selectedVideos.size, 1000, text(R.string.backend_downloading_bytes, index + 1, selectedVideos.size, amount))
                    }
                    currentCoroutineContext().ensureActive()
                    checkText(temporary.length() > 0) { text(R.string.backend_download_video_empty) }
                    onProgress((index * 1000 + 950) / selectedVideos.size, 1000, text(R.string.backend_validating_video, index + 1, selectedVideos.size))
                    verifyVideo(temporary)
                    currentCoroutineContext().ensureActive()
                    Files.move(temporary.toPath(), file.toPath())
                    outputs += OutputArtifact(
                        file, "video/mp4", label,
                        detail = if (video.animatedGif) text(R.string.backend_twitter_gif_detail) else null,
                    )
                    saved = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    failures += ProcessingFailure(label, error.userMessage(R.string.backend_download_failed))
                } finally {
                    temporary.delete()
                    if (!saved) file.delete()
                }
                onProgress((index + 1) * 1000 / selectedVideos.size, 1000, text(R.string.backend_videos_processed, index + 1, selectedVideos.size))
            }
            currentCoroutineContext().ensureActive()
            completed = true
            ProcessingOutcome(outputs, failures)
        } finally {
            // An abandoned batch has no result page; do not leave unreviewable files behind.
            if (!completed) directory.deleteRecursively() else if (outputs.isEmpty()) directory.delete()
        }
    }
}
