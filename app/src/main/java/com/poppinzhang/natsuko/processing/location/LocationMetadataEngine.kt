package com.poppinzhang.natsuko.processing.location

import android.content.Context
import android.os.Build
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.LocalizedIOException
import com.poppinzhang.natsuko.model.text
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Offline, metadata-only processing through the application's embedded ExifTool. */
class LocationMetadataEngine(context: Context) {
    private val context = context.applicationContext
    private val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)

    suspend fun read(file: File): LocationMetadata? = run(listOf("read", file.absolutePath))

    /** The caller supplies a working copy. Neither operation modifies imported source URIs. */
    suspend fun copy(sourceFile: File, targetFile: File) {
        val temporary = File(targetFile.parentFile, ".${targetFile.nameWithoutExtension}-location-${System.nanoTime()}.${targetFile.extension}")
        try {
            run(listOf("copy", sourceFile.absolutePath, targetFile.absolutePath, temporary.absolutePath))
        } finally {
            temporary.delete()
        }
    }

    private suspend fun run(arguments: List<String>): LocationMetadata? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val executable = File(nativeDirectory, "libnatsuko_perl.so")
        if (!executable.isFile || !executable.canExecute()) fail(R.string.location_backend_missing)
        val runtime = prepareRuntime()
        val stdout = BoundedOutput(512 * 1024)
        val stderr = BoundedOutput(16 * 1024)
        val readerFailure = AtomicReference<Throwable?>(null)
        val process = ProcessBuilder(
            listOf(executable.absolutePath, "-I", File(runtime, "lib").absolutePath,
                File(runtime, "location_bridge.pl").absolutePath) + arguments,
        ).apply {
            // Do not inherit interpreter configuration or search paths from the host.
            environment().keys.removeAll { it.startsWith("PERL") }
        }.start()
        process.outputStream.close()
        val readers = listOf(stdout to process.inputStream, stderr to process.errorStream).map { (output, stream) ->
            thread(name = "natsuko-metadata-output", isDaemon = true) {
                try { output.read(stream) }
                catch (error: Exception) { readerFailure.compareAndSet(null, error) }
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            do {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() > deadline) fail(R.string.location_metadata_failed)
            } while (!process.waitFor(100, TimeUnit.MILLISECONDS))
            readers.forEach { it.join() }
            currentCoroutineContext().ensureActive()
            readerFailure.get()?.let { throw LocalizedIOException(text(R.string.location_metadata_failed), it) }
            when (process.exitValue()) {
                0 -> Unit
                3 -> fail(R.string.location_format_unsupported)
                4 -> fail(R.string.location_write_verification_failed)
                else -> fail(R.string.location_metadata_failed)
            }
            try { LocationMetadataProtocol.parse(stdout.value()) }
            catch (error: Exception) { throw LocalizedIOException(text(R.string.location_metadata_failed), error) }
        } finally {
            // Kill and reap before the processor deletes any input or partial output.
            if (process.isAlive) process.destroyForcibly()
            process.waitFor()
            process.inputStream.close()
            process.errorStream.close()
            readers.forEach { it.join() }
        }
    }

    private suspend fun prepareRuntime(): File = runtimeMutex.withLock {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: fail(R.string.location_backend_missing)
        val update = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val root = File(context.noBackupFilesDir, "metadata-$update-$abi")
        val complete = File(root, ".complete")
        fun removePreviousRuntimes() {
            context.noBackupFilesDir.listFiles().orEmpty().filter {
                it != root && it.isDirectory && it.name.matches(Regex("metadata-\\d+-(arm64-v8a|x86_64)"))
            }.forEach(File::deleteRecursively)
        }
        if (complete.isFile) {
            removePreviousRuntimes()
            return@withLock root
        }
        root.deleteRecursively()
        if (!root.mkdirs()) fail(R.string.location_backend_missing)
        try {
            for (asset in listOf("metadata/perl-5.40.2-$abi.zip", "metadata/exiftool-13.53.zip")) {
                currentCoroutineContext().ensureActive()
                context.assets.open(asset).use { input ->
                    ZipInputStream(input).use { zip ->
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = zip.nextEntry ?: break
                            val destination = File(root, entry.name)
                            if (!destination.canonicalPath.startsWith(root.canonicalPath + File.separator)) fail(R.string.location_backend_missing)
                            if (!entry.isDirectory) {
                                destination.parentFile?.mkdirs()
                                destination.outputStream().use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        currentCoroutineContext().ensureActive()
                                        total += count
                                        if (total > 64L * 1024 * 1024) fail(R.string.location_backend_missing)
                                        out.write(buffer, 0, count)
                                    }
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
            }
            context.assets.open("metadata/location_bridge.pl").use { input ->
                File(root, "location_bridge.pl").outputStream().use(input::copyTo)
            }
            complete.writeText("ExifTool 13.53 / Perl 5.40.2")
            removePreviousRuntimes()
            root
        } catch (error: Exception) {
            root.deleteRecursively()
            throw error
        }
    }

    private class BoundedOutput(private val maximum: Int) {
        private val content = StringBuilder()
        private var exceeded = false
        fun read(input: InputStream) = input.reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (content.length + count <= maximum) content.append(buffer, 0, count) else exceeded = true
            }
        }
        fun value(): String {
            check(!exceeded) { "Metadata response too large" }
            return content.toString()
        }
    }

    private fun fail(resource: Int): Nothing = throw LocalizedIOException(text(resource))

    private companion object { val runtimeMutex = Mutex() }
}
