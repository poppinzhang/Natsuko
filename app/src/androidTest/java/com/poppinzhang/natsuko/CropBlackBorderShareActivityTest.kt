package com.poppinzhang.natsuko

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputStoragePreferences
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CropBlackBorderShareActivityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launchingShareAliasAutomaticallyCropsScreenshotAndRecreationKeepsResult() = runBlocking {
        val preferences = OutputStoragePreferences(context)
        val originalSettings = preferences.read()
        val fixture = File(context.filesDir, "crop-share-activity-${UUID.randomUUID()}.png")
        val outputs = mutableListOf<OutputArtifact>()
        var scenario: ActivityScenario<MainActivity>? = null
        var viewModel: NatsukoViewModel? = null
        preferences.setEnabled(false)
        try {
            val bitmap = Bitmap.createBitmap(20, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLACK)
                for (y in 4 until 12) {
                    for (x in 4 until 16) setPixel(x, y, Color.WHITE)
                }
            }
            try {
                fixture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", fixture)
            val intent = Intent(Intent.ACTION_SEND)
                .setComponent(ComponentName(context, "com.poppinzhang.natsuko.share.CropBlackBorderShare"))
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("Screenshot", uri) }

            val launched = ActivityScenario.launch<MainActivity>(intent)
            scenario = launched
            launched.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[NatsukoViewModel::class.java]
            }
            val model = checkNotNull(viewModel)
            val completed = withTimeout(15_000) {
                model.state.first { it.outcome != null && !it.isProcessing && !it.isSavingToUserStorage }
            }
            outputs += completed.outcome!!.outputs
            assertEquals(Feature.CROP_BLACK_BORDER, completed.feature)
            assertEquals(AppScreen.HOME, completed.screen)
            assertFalse(completed.successMessage?.resolve(context).isNullOrBlank())
            assertNull(completed.errorMessage)
            assertTrue(completed.outcome.failures.isEmpty())
            assertEquals(1, completed.outcome.outputs.size)
            val decoded = BitmapFactory.decodeFile(completed.outcome.outputs.single().file.absolutePath)
            assertNotNull(decoded)
            try {
                assertEquals(12, decoded!!.width)
                assertEquals(8, decoded.height)
                assertEquals(Color.WHITE, decoded.getPixel(0, 0))
            } finally {
                decoded?.recycle()
            }

            launched.recreate()

            launched.onActivity { activity ->
                assertSame(model, ViewModelProvider(activity)[NatsukoViewModel::class.java])
                assertEquals(AppScreen.HOME, model.state.value.screen)
                assertEquals(completed.outcome, model.state.value.outcome)
            }
            // Observe briefly because an accidental re-import would start on the IO dispatcher.
            val restarted = withTimeoutOrNull(1_000) {
                model.state.first { it.isProcessing || it.outcome != completed.outcome }
            }
            assertNull("Recreating the share activity must not process the screenshot twice", restarted)
        } finally {
            outputs += viewModel?.state?.value?.outcome?.outputs.orEmpty()
            scenario?.close()
            outputs.map { it.file }.distinct().forEach { it.delete() }
            fixture.delete()
            preferences.setDirectory(originalSettings.treeUri, originalSettings.directoryLabel)
            preferences.setEnabled(originalSettings.enabled)
        }
    }
}
