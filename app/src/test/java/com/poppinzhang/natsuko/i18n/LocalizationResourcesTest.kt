package com.poppinzhang.natsuko.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Checks the source catalogs, including resources added after this test was written. */
class LocalizationResourcesTest {
    private val resources = listOf(File("src/main/res"), File("app/src/main/res"))
        .firstOrNull { it.isDirectory }
        ?: error("Cannot find the app resource directory")

    @Test
    fun englishFallbackAndChineseAreBothDeclaredAndHaveCatalogs() {
        val localeConfig = parse(File(resources, "xml/locale_config.xml"))
        val namespace = "http://schemas.android.com/apk/res/android"
        assertEquals("en", localeConfig.getAttributeNS(namespace, "defaultLocale"))
        assertEquals(
            setOf("en", "zh"),
            localeConfig.children().map { it.getAttributeNS(namespace, "name") }.toSet(),
        )
        // English lives in values, making it the fallback for any unsupported language.
        for (directory in listOf("values", "values-zh")) {
            val catalog = readCatalog(directory)
            assertFalse("$directory must contain translated app strings", catalog.isEmpty())
            assertTrue("$directory must contain an application name", "string/app_name" in catalog)
        }
    }

    @Test
    fun everyAppStringHasBothEnglishAndChineseTranslations() {
        val english = readCatalog("values")
        val chinese = readCatalog("values-zh")
        assertEquals(
            "Missing Chinese translations: ${english.keys - chinese.keys}; " +
                "missing English fallbacks: ${chinese.keys - english.keys}",
            english.keys,
            chinese.keys,
        )
        for ((language, catalog) in listOf("en" to english, "zh" to chinese)) {
            for ((key, entry) in catalog) {
                assertTrue("$language/$key must contain text", entry.variants.isNotEmpty())
                entry.variants.forEach { (variant, value) ->
                    assertTrue("$language/$key ($variant) must not be empty", value.isNotBlank())
                }
            }
        }
    }

    @Test
    fun translationsPreserveFormatArgumentPositionsAndTypes() {
        val english = readCatalog("values")
        val chinese = readCatalog("values-zh")
        for ((key, source) in english) {
            val translated = chinese[key] ?: error("Missing Chinese translation: $key")
            when (source.type) {
                "plurals" -> {
                    assertTrue("$key needs an English other quantity", "other" in source.variants)
                    assertTrue("$key needs a Chinese other quantity", "other" in translated.variants)
                    val arguments = formatArguments(source.variants.getValue("other"))
                    // Chinese only needs 'other'; check every branch present in either language.
                    for ((language, entry) in listOf("en" to source, "zh" to translated)) {
                        entry.variants.forEach { (quantity, value) ->
                            assertEquals("$key ($language/$quantity) format arguments", arguments, formatArguments(value))
                        }
                    }
                }
                else -> {
                    assertEquals("$key must preserve array items", source.variants.keys, translated.variants.keys)
                    source.variants.forEach { (variant, value) ->
                        assertEquals(
                            "$key ($variant) format arguments",
                            formatArguments(value),
                            formatArguments(translated.variants.getValue(variant)),
                        )
                    }
                }
            }
        }
    }

    private fun readCatalog(directory: String): Map<String, Entry> {
        val files = File(resources, directory).listFiles()
            .orEmpty().filter { it.extension == "xml" }
            .sortedBy { it.name }
        assertTrue("$directory must contain resource XML files", files.isNotEmpty())
        val catalog = linkedMapOf<String, Entry>()
        files.forEach { file ->
            parse(file).children().forEach elementLoop@{ element ->
                if (element.tagName !in setOf("string", "plurals", "string-array")) return@elementLoop
                val name = element.getAttribute("name")
                assertTrue("Resource in ${file.name} must have a name", name.isNotBlank())
                val key = "${element.tagName}/$name"
                val variants = when (element.tagName) {
                    "string" -> mapOf("text" to element.textContent)
                    "plurals" -> element.children().associate { it.getAttribute("quantity") to it.textContent }
                    else -> element.children().mapIndexed { index, item -> index.toString() to item.textContent }.toMap()
                }
                assertFalse("Duplicate resource $key in $directory", key in catalog)
                catalog[key] = Entry(element.tagName, variants)
            }
        }
        return catalog
    }

    private fun formatArguments(value: String): Map<Int, Set<String>> {
        var nextImplicitIndex = 1
        var previousIndex = 0
        val arguments = linkedMapOf<Int, MutableSet<String>>()
        FORMAT.findAll(value).forEach { match ->
            val conversion = match.groupValues[6]
            if (conversion == "%" || conversion == "n") return@forEach
            val index = when {
                '<' in match.groupValues[2] -> previousIndex
                match.groupValues[1].isNotEmpty() -> match.groupValues[1].toInt()
                else -> nextImplicitIndex++
            }
            assertTrue("Invalid argument position in $value", index > 0)
            previousIndex = index
            val type = match.groupValues[5] + conversion
            arguments.getOrPut(index) { linkedSetOf() }.add(type)
        }
        return arguments
    }

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file).documentElement

    private fun Element.children(): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }

    private data class Entry(val type: String, val variants: Map<String, String>)

    companion object {
        // java.util.Formatter: optional position, flags, width, precision, date prefix, conversion.
        private val FORMAT = Regex("""%(?:(\d+)\$)?([-#+ 0,(<]*)(\d+)?(?:\.(\d+))?([tT])?([a-zA-Z%])""")
    }
}
