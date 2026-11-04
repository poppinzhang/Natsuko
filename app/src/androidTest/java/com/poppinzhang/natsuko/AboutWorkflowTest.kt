package com.poppinzhang.natsuko

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutWorkflowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeFooterOpensVersionAndOfflineLicenseAndRecreationPreservesNavigation() {
        composeRule.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-about"))
        composeRule.onNodeWithTag("home-about").assertIsDisplayed().performClick()
        waitForTag("about-version")
        composeRule.onNodeWithTag("about-version")
            .assertTextEquals(composeRule.activity.getString(R.string.about_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))

        composeRule.onNodeWithTag("about-list").performScrollToNode(hasTestTag("open-source-natsuko"))
        composeRule.onNodeWithTag("open-source-natsuko").performClick()
        waitForTag("open-source-notices")
        composeRule.onNodeWithTag("open-source-notices")
            .performScrollToNode(hasText("Apache License", substring = true))
        composeRule.onNodeWithText("Apache License", substring = true).assertExists()

        composeRule.activityRule.scenario.recreate()

        composeRule.activityRule.scenario.onActivity { activity ->
            val state = ViewModelProvider(activity)[NatsukoViewModel::class.java].state.value
            assertEquals(AppScreen.ABOUT, state.screen)
            assertEquals("natsuko", state.aboutProjectId)
        }
        waitForTag("open-source-notices")
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForTag("about-list")
        composeRule.activityRule.scenario.onActivity { activity ->
            val state = ViewModelProvider(activity)[NatsukoViewModel::class.java].state.value
            assertEquals(AppScreen.ABOUT, state.screen)
            assertNull(state.aboutProjectId)
        }
        composeRule.onNodeWithTag("about-back").performClick()
        composeRule.onNodeWithTag("home-list").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { activity ->
            assertEquals(AppScreen.HOME, ViewModelProvider(activity)[NatsukoViewModel::class.java].state.value.screen)
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
