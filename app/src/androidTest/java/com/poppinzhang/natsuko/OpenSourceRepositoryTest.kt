package com.poppinzhang.natsuko

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OpenSourceRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenSourceRepositoryTest {
    private val repository = OpenSourceRepository(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun packagedCatalogIncludesAppAndDependenciesWithCompleteOfflineNotices() = runBlocking {
        val projects = repository.projects()
        assertTrue(projects.size > 1)
        val app = projects.single { it.id == "natsuko" }
        assertTrue(app.license.contains("Apache"))
        assertTrue(projects.any { it.name.contains("FFmpeg", ignoreCase = true) })
        projects.forEach { project ->
            if (project.id != "natsuko") {
                assertTrue("Missing source address for ${project.name}", project.url.startsWith("https://"))
            }
            assertTrue("Missing version for ${project.name}", project.version.isNotBlank())
            val notices = repository.notices(project)
            assertEquals(project.licenseFiles.size, notices.size)
            assertTrue("Empty notice for ${project.name}", notices.all { it.text.isNotBlank() })
        }
        val appNotices = repository.notices(app).joinToString("\n") { it.text }
        assertTrue(appNotices.contains("Apache License"))
        assertTrue(appNotices.contains("END OF TERMS AND CONDITIONS"))
    }

    @Test
    fun malformedCatalogRejectsDuplicateProjectsAndMissingNoticeFiles() {
        val entry = """{"id":"example","name":"Example","version":"1.0","license":"Apache-2.0","url":"https://example.com","licenseFiles":["open_source/LICENSE.txt"]}"""
        assertThrows(IllegalArgumentException::class.java) {
            OpenSourceRepository.parseProjects("""{"projects":[$entry,$entry]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenSourceRepository.parseProjects("""{"projects":[${entry.replace("[\"open_source/LICENSE.txt\"]", "[]")}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenSourceRepository.parseProjects("""{"projects":[${entry.replace("open_source/LICENSE.txt", "../LICENSE.txt")}]}""")
        }
    }
}
