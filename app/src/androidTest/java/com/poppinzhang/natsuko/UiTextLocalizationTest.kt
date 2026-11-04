package com.poppinzhang.natsuko

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.ProcessingProgress
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.quantityText
import com.poppinzhang.natsuko.model.userMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextLocalizationTest {
    @Test
    fun retainedResultsProgressAndFailuresResolveInCurrentLanguage() {
        val english = contextFor("en")
        val chinese = contextFor("zh-CN")
        val output = OutputArtifact(
            File("merged.png"), "image/png", text(R.string.backend_merged_image),
            detail = text(R.string.backend_border_removed),
        )
        val progress = ProcessingProgress(1, 2, text(R.string.backend_saving_output, output.label))
        val failure = ProcessingFailure(
            output.label,
            text(R.string.backend_save_storage_error, text(R.string.backend_storage_full)),
        )

        assertEquals("Merged image", output.label.resolve(english))
        assertEquals("拼接图片", output.label.resolve(chinese))
        assertEquals("Black borders removed", output.detail!!.resolve(english))
        assertEquals("已去除黑边", output.detail!!.resolve(chinese))
        assertEquals("Saving Merged image", progress.message.resolve(english))
        assertEquals("正在保存 拼接图片", progress.message.resolve(chinese))
        assertEquals("Failed to save to the user storage directory: Storage is full.", failure.reason.resolve(english))
        assertEquals("保存至用户存储目录失败：存储空间不足", failure.reason.resolve(chinese))
        // The same retained object can switch back; no resolved locale was captured by the backend.
        assertEquals("Saving Merged image", progress.message.resolve(english))
    }

    @Test
    fun chineseVariantsUseChineseAndUnsupportedLanguagesUseEnglish() {
        val message = text(R.string.backend_processing_failed)
        listOf("zh-CN", "zh-TW", "zh-HK", "zh-Hans", "zh-Hant").forEach {
            assertEquals(it, "处理失败", message.resolve(contextFor(it)))
        }
        assertEquals("Processing failed.", message.resolve(contextFor("fr-FR")))
    }

    @Test
    fun backendExceptionsKeepResourceMessageAndExternalErrorsUseLocalizedFallback() {
        val message = text(R.string.backend_directory_permission_expired)
        val failure = runCatching { checkText(false) { message } }.exceptionOrNull()!!
        assertTrue(failure is LocalizedIllegalStateException)
        assertEquals(message, failure.userMessage(R.string.backend_processing_failed))
        val wrapped = IllegalStateException("Low-level diagnostic", failure)
        assertEquals(message, wrapped.userMessage(R.string.backend_processing_failed))
        val platformError = IllegalStateException("Raw platform message")
        assertEquals("处理失败", platformError.userMessage(R.string.backend_processing_failed).resolve(contextFor("zh")))
        assertEquals("example.png", UiText.Literal("example.png").resolve(contextFor("zh")))
    }

    @Test
    fun numericLabelsUseEnglishPluralsAndPreserveLiteralFileNames() {
        val english = contextFor("en")
        val chinese = contextFor("zh")
        val one = quantityText(R.plurals.backend_combined_pdf_label, 1, 1)
        val many = quantityText(R.plurals.backend_combined_pdf_label, 2, 2)
        assertEquals("Combined PDF (1 page)", one.resolve(english))
        assertEquals("Combined PDF (2 pages)", many.resolve(english))
        assertEquals("合并 PDF（1 页）", one.resolve(chinese))
        assertEquals("合并 PDF（2 页）", many.resolve(chinese))
        val gif = quantityText(R.plurals.backend_gif_output_label, 1, "100% \"clip\".mp4", 1)
        assertEquals("100% \"clip\".mp4 GIF (1 frame)", gif.resolve(english))
    }

    private fun contextFor(languageTag: String): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(languageTag))
        return context.createConfigurationContext(configuration)
    }
}
