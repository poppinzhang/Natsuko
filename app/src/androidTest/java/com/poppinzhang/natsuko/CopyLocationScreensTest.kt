package com.poppinzhang.natsuko

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.processing.location.LocationConflict
import com.poppinzhang.natsuko.processing.location.LocationConflictDecision
import com.poppinzhang.natsuko.processing.location.LocationMetadata
import com.poppinzhang.natsuko.processing.location.LocationSource
import com.poppinzhang.natsuko.ui.CopyLocationConflictDialog
import com.poppinzhang.natsuko.ui.CopyLocationSourceScreen
import com.poppinzhang.natsuko.ui.CopyLocationTargetsScreen
import com.poppinzhang.natsuko.ui.CopyLocationUiState
import com.poppinzhang.natsuko.ui.LocationCandidate
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CopyLocationScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun imageWithoutCoordinatesCannotBeChosenAsSource() {
        var choseSource = false
        var repicked = false
        composeRule.setContent {
            NatsukoTheme {
                CopyLocationSourceScreen(
                    state = CopyLocationUiState(
                        candidates = listOf(LocationCandidate(
                            input = InputItem(Uri.parse("content://tests/screenshot.png"), "screenshot.png", "image/png"),
                            error = text(R.string.location_invalid_source),
                        )),
                    ),
                    onBack = {},
                    onPick = { repicked = true },
                    onChoose = { choseSource = true },
                )
            }
        }
        composeRule.onNodeWithTag("location-source-0").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("location-pick-source").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertTrue(repicked)
            assertTrue(!choseSource)
        }
    }

    @Test
    fun metadataValidationPreventsStartingAnotherPicker() {
        composeRule.setContent {
            NatsukoTheme {
                CopyLocationSourceScreen(
                    state = CopyLocationUiState(isLoading = true),
                    onBack = {},
                    onPick = {},
                    onChoose = {},
                )
            }
        }
        composeRule.onNodeWithTag("location-source-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("location-pick-source").assertIsNotEnabled()
    }

    @Test
    fun eligibleSourceCanBeChosenWithoutPreviewDecoding() {
        val input = InputItem(Uri.parse("content://tests/source.nef"), "source.nef", "image/x-nikon-nef")
        val source = LocationSource(input, File("source.nef"), LocationMetadata("35.0, 139.0", true))
        var chosen: LocationSource? = null
        composeRule.setContent {
            NatsukoTheme {
                CopyLocationSourceScreen(
                    state = CopyLocationUiState(candidates = listOf(LocationCandidate(input, source))),
                    onBack = {},
                    onPick = {},
                    onChoose = { chosen = it },
                )
            }
        }
        composeRule.onNodeWithTag("location-source-0").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(source, chosen) }
    }

    @Test
    fun conflictOffersEachDecisionAndKeepsBothLocationsVisible() {
        val decisions = mutableListOf<LocationConflictDecision>()
        composeRule.setContent {
            NatsukoTheme {
                CopyLocationConflictDialog(
                    conflict = LocationConflict(
                        InputItem(Uri.parse("content://tests/target.jpg"), "target.jpg", "image/jpeg"),
                        before = LocationMetadata("34.0, 135.0", true),
                        after = LocationMetadata("35.0, 139.0", true),
                    ),
                    onDecision = decisions::add,
                )
            }
        }
        composeRule.onNodeWithTag("location-conflict-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("34.0, 135.0", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("35.0, 139.0", substring = true).assertIsDisplayed()
        val buttons = listOf(
            "location-overwrite" to LocationConflictDecision.OVERWRITE,
            "location-skip" to LocationConflictDecision.SKIP,
            "location-overwrite-all" to LocationConflictDecision.OVERWRITE_ALL,
            "location-skip-all" to LocationConflictDecision.SKIP_ALL,
            "location-abort" to LocationConflictDecision.ABORT,
        )
        buttons.forEach { (tag, _) -> composeRule.onNodeWithTag(tag).assertIsDisplayed().performClick() }
        composeRule.runOnIdle { assertEquals(buttons.map { it.second }, decisions) }
    }

    @Test
    fun targetsCannotBePickedWithoutAnAcceptedSource() {
        composeRule.setContent {
            NatsukoTheme {
                CopyLocationTargetsScreen(
                    state = CopyLocationUiState(),
                    isBusy = false,
                    onBack = {},
                    onPick = {},
                )
            }
        }
        composeRule.onNodeWithTag("location-targets-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("location-pick-targets").performScrollTo().assertIsNotEnabled()
    }
}
