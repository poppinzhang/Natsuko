package com.poppinzhang.natsuko.processing.location

import android.content.Context
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.ProcessingSkipped
import com.poppinzhang.natsuko.model.ProcessingOutcome
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.requireText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.userMessage
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class LocationSource(val input: InputItem, val file: File, val location: LocationMetadata)
data class LocationConflict(val target: InputItem, val before: LocationMetadata, val after: LocationMetadata)
enum class LocationConflictDecision { OVERWRITE, SKIP, ABORT, OVERWRITE_ALL, SKIP_ALL }

/** A policy is scoped to one batch, and only applies to targets that already have a location. */
class LocationConflictPolicy {
    var subsequent: LocationConflictDecision? = null
        private set

    fun accept(decision: LocationConflictDecision): LocationConflictDecision = when (decision) {
        LocationConflictDecision.OVERWRITE_ALL -> LocationConflictDecision.OVERWRITE.also { subsequent = it }
        LocationConflictDecision.SKIP_ALL -> LocationConflictDecision.SKIP.also { subsequent = it }
        else -> decision
    }
}

class CopyLocationProcessor(
    private val context: Context,
    private val outputRepository: OutputRepository,
    private val readMetadata: suspend (File) -> LocationMetadata? = LocationMetadataEngine(context)::read,
    private val copyMetadata: suspend (File, File) -> Unit = LocationMetadataEngine(context)::copy,
) {
    suspend fun importSource(input: InputItem): LocationSource {
        val directory = File(context.cacheDir, "copy-location").also { it.mkdirs() }
        val file = File.createTempFile("source-", ".${extension(input)}", directory)
        var accepted = false
        try {
            copyInput(input, file)
            val location = readMetadata(file)
            requireText(location?.hasCoordinates == true) { text(R.string.location_invalid_source) }
            return LocationSource(input, file, checkNotNull(location)).also { accepted = true }
        } finally {
            if (!accepted) file.delete()
        }
    }

    suspend fun process(
        source: LocationSource,
        targets: List<InputItem>,
        onConflict: suspend (LocationConflict) -> LocationConflictDecision,
        onProgress: (Int, Int, UiText) -> Unit,
    ): ProcessingOutcome {
        requireText(source.location.hasCoordinates && source.file.isFile) { text(R.string.location_invalid_source) }
        requireText(targets.isNotEmpty()) { text(R.string.backend_select_image) }
        val directory = outputRepository.createRunDirectory(Feature.COPY_LOCATION)
        val outputs = mutableListOf<OutputArtifact>()
        val failures = mutableListOf<ProcessingFailure>()
        val skipped = mutableListOf<ProcessingSkipped>()
        val policy = LocationConflictPolicy()
        var completed = false
        try {
            for ((index, target) in targets.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(index, targets.size, text(R.string.location_processing, index + 1, targets.size, target.displayName))
                var output: File? = null
                var saved = false
                var aborted = false
                try {
                    val extension = extension(target)
                    // Bound UTF-8 filename length even for CJK names and keep surrogate pairs intact.
                    val stem = OutputRepository.safeStem(target.displayName).take(60).let {
                        if (it.last().isHighSurrogate()) it.dropLast(1) else it
                    }
                    val destination = File(directory, "${index + 1}_${stem}_location.$extension")
                    output = destination
                    copyInput(target, destination)
                    val before = readMetadata(destination)
                    val decision = if (before == null) LocationConflictDecision.OVERWRITE else {
                        policy.subsequent ?: policy.accept(onConflict(LocationConflict(target, before, source.location)))
                    }
                    when (decision) {
                        LocationConflictDecision.SKIP -> skipped += ProcessingSkipped(target.displayName, text(R.string.location_skipped))
                        LocationConflictDecision.ABORT -> {
                            // Completed copies remain reviewable; nothing is written for the current or later items.
                            targets.drop(index).forEach { skipped += ProcessingSkipped(it.displayName, text(R.string.location_aborted)) }
                            aborted = true
                        }
                        else -> {
                            copyMetadata(source.file, destination)
                            currentCoroutineContext().ensureActive()
                            outputs += OutputArtifact(
                                destination, checkNotNull(LocationFormat.mimeType(destination.name)),
                                target.displayName, detail = source.location.displayText,
                            )
                            saved = true
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures += ProcessingFailure(target.displayName, error.userMessage(R.string.location_copy_failed))
                } finally {
                    if (!saved) output?.delete()
                }
                if (aborted) break
                onProgress(index + 1, targets.size, text(R.string.location_processing, index + 1, targets.size, target.displayName))
            }
            completed = true
            return ProcessingOutcome(outputs, failures, skipped)
        } finally {
            // An externally cancelled/unpublished batch must not leave inaccessible files behind.
            if (!completed) directory.deleteRecursively()
            else if (outputs.isEmpty()) directory.delete()
        }
    }

    private fun extension(input: InputItem): String {
        val suffix = input.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (LocationFormat.mimeType("image.$suffix") != null) return suffix
        val fromMime = LocationFormat.supportedExtensions.firstOrNull {
            LocationFormat.mimeType("image.$it") == input.mimeType?.lowercase(Locale.ROOT)
        }
        requireText(fromMime != null) { text(R.string.location_unsupported_format) }
        return checkNotNull(fromMime)
    }

    private suspend fun copyInput(input: InputItem, destination: File) {
        val work = currentCoroutineContext()
        context.contentResolver.openInputStream(input.uri).use { stream ->
            checkText(stream != null) { text(R.string.location_read_failed) }
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    work.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        requireText(destination.length() > 0) { text(R.string.location_read_failed) }
    }
}
