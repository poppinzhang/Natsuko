package com.poppinzhang.natsuko

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.FeatureOrderPreferences
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.FeatureOrder
import com.poppinzhang.natsuko.ui.HomeScreen
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeFeatureOrderWorkflowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = FeatureOrderPreferences(context)
    private var originalOrder = FeatureOrder.default
    private val screenGeneration = mutableIntStateOf(0)
    private val openedFeatures = mutableListOf<Feature>()

    @Before
    fun resetOrder() {
        originalOrder = preferences.read()
        preferences.write(FeatureOrder.default)
    }

    @After
    fun restoreOrder() {
        composeRule.mainClock.autoAdvance = true
        preferences.write(originalOrder)
    }

    @Test
    fun tappingACardStillOpensItsFeature() {
        showHome()
        featureNode(Feature.IMAGE_MERGE).performTouchInput {
            down(center)
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf(Feature.IMAGE_MERGE), openedFeatures)
            assertEquals(FeatureOrder.default, preferences.read())
        }
    }

    @Test
    fun ordinarySwipeScrollsWithoutReorderingOrOpeningAFeature() {
        showHome()
        val viewport = bounds("home-list")
        val start = bounds(tag(Feature.DOWNLOAD_TWITTER_VIDEO)).center - viewport.topLeft
        val end = Offset(start.x, 20f)

        composeRule.onNodeWithTag("home-list").performTouchInput {
            down(start)
            for (step in 1..8) moveTo(start + (end - start) * (step / 8f), delayMillis = 20)
            up()
        }

        composeRule.runOnIdle {
            assertEquals(FeatureOrder.default, preferences.read())
            assertTrue(openedFeatures.isEmpty())
        }
        // The gesture must actually scroll, rather than merely leave the stored order unchanged.
        assertTrue(
            composeRule.onNodeWithTag("home-list").fetchSemanticsNode().config[
                SemanticsProperties.VerticalScrollAxisRange
            ].value() > 0f,
        )
    }

    @Test
    fun longPressDragReordersAndPersistsWithoutOpeningTheFeature() {
        showHome()
        dragFirstBelowSecond(cancelGesture = false)
        val expected = swappedFirstTwo()

        assertEquals(expected, FeatureOrderPreferences(context).read())
        assertTrue(openedFeatures.isEmpty())
        assertAbove(expected[0], expected[1])

        recreateHome()
        assertEquals(expected, preferences.read())
        assertAbove(expected[0], expected[1])
    }

    @Test
    fun cancellingADragRestoresThePreviousOrder() {
        showHome()
        dragFirstBelowSecond(cancelGesture = true)

        assertEquals(FeatureOrder.default, preferences.read())
        assertTrue(openedFeatures.isEmpty())
        assertAbove(Feature.IMAGE_MERGE, Feature.DOWNLOAD_TWITTER_VIDEO)
        recreateHome()
        assertAbove(Feature.IMAGE_MERGE, Feature.DOWNLOAD_TWITTER_VIDEO)
    }

    @Test
    fun accessibilityMovesPersistAndRespectFirstAndLastBoundaries() {
        showHome()
        val up = context.getString(R.string.main_move_up)
        val down = context.getString(R.string.main_move_down)
        assertEquals(listOf(down), actionLabels(Feature.IMAGE_MERGE))

        performMove(Feature.IMAGE_MERGE, down)
        assertEquals(swappedFirstTwo(), preferences.read())
        recreateHome()
        assertAbove(Feature.DOWNLOAD_TWITTER_VIDEO, Feature.IMAGE_MERGE)
        assertEquals(listOf(up, down), actionLabels(Feature.IMAGE_MERGE))

        performMove(Feature.IMAGE_MERGE, up)
        assertEquals(FeatureOrder.default, preferences.read())
        composeRule.onNodeWithTag("home-list").performScrollToNode(hasTestTag(tag(Feature.VIDEO_TO_GIF)))
        assertEquals(listOf(up), actionLabels(Feature.VIDEO_TO_GIF))
        assertTrue(openedFeatures.isEmpty())
    }

    @Test
    fun holdingDraggedCardAtBottomScrollsAcrossCategoriesToTheEnd() {
        showHome()
        val viewport = bounds("home-list")
        val start = bounds(tag(Feature.IMAGE_MERGE)).center - viewport.topLeft
        val edge = Offset(start.x, viewport.height - 4f)
        // Keep the finger still at the edge: scrolling must continue without new move events.
        performHeldDrag(Feature.IMAGE_MERGE, start, edge, holdAtEndMillis = 6_000)

        val expected = FeatureOrder.default.drop(1) + Feature.IMAGE_MERGE
        assertEquals(expected, preferences.read())
        assertTrue(openedFeatures.isEmpty())
        recreateHome()
        assertAbove(Feature.DOWNLOAD_TWITTER_VIDEO, Feature.IMAGES_TO_PDF)
        composeRule.onNodeWithTag("home-list").performScrollToNode(hasTestTag(tag(Feature.IMAGE_MERGE)))
        assertFalse(actionLabels(Feature.IMAGE_MERGE).contains(context.getString(R.string.main_move_down)))
    }

    @Test
    fun holdingDraggedCardAtTopScrollsAcrossCategoriesToTheBeginning() {
        showHome()
        composeRule.onNodeWithTag("home-list").performScrollToNode(hasTestTag(tag(Feature.VIDEO_TO_GIF)))
        val viewport = bounds("home-list")
        val start = bounds(tag(Feature.VIDEO_TO_GIF)).center - viewport.topLeft
        val edge = Offset(start.x, 4f)
        // This also checks that top content padding does not prevent edge scrolling.
        performHeldDrag(Feature.VIDEO_TO_GIF, start, edge, holdAtEndMillis = 6_000)

        val expected = listOf(Feature.VIDEO_TO_GIF) + FeatureOrder.default.dropLast(1)
        assertEquals(expected, preferences.read())
        assertTrue(openedFeatures.isEmpty())
        recreateHome()
        assertAbove(Feature.VIDEO_TO_GIF, Feature.IMAGE_MERGE)
        assertFalse(actionLabels(Feature.VIDEO_TO_GIF).contains(context.getString(R.string.main_move_up)))
    }

    private fun showHome() {
        composeRule.setContent {
            NatsukoTheme {
                key(screenGeneration.intValue) {
                    HomeScreen(
                        state = NatsukoUiState(),
                        onFeature = openedFeatures::add,
                        onSettings = {},
                        onResults = {},
                        onAbout = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun recreateHome() {
        // Dispose all remembered screen state so the new screen has to read the persisted order.
        composeRule.runOnIdle { screenGeneration.intValue += 1 }
        composeRule.waitForIdle()
    }

    private fun dragFirstBelowSecond(cancelGesture: Boolean) {
        val viewport = bounds("home-list")
        val first = bounds(tag(Feature.IMAGE_MERGE))
        val second = bounds(tag(Feature.DOWNLOAD_TWITTER_VIDEO))
        val start = first.center - viewport.topLeft
        val end = Offset(second.center.x, second.center.y + second.height / 4f) - viewport.topLeft
        performHeldDrag(Feature.IMAGE_MERGE, start, end, cancelGesture = cancelGesture)
    }

    private fun performHeldDrag(
        feature: Feature,
        start: Offset,
        end: Offset,
        holdAtEndMillis: Long = 0,
        cancelGesture: Boolean = false,
    ) {
        composeRule.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            // Flush down separately so the v2 test dispatcher starts the long-press timeout.
            composeRule.onNodeWithTag("home-list").performTouchInput { down(start) }
            pointerDown = true
            composeRule.mainClock.advanceTimeBy(1_000)
            val position = context.getString(
                R.string.home_feature_position,
                FeatureOrder.default.indexOf(feature) + 1,
                FeatureOrder.default.size,
            )
            assertEquals(
                "The long press must activate dragging before movement begins",
                context.getString(R.string.home_feature_dragging, position),
                featureNode(feature).fetchSemanticsNode().config[SemanticsProperties.StateDescription],
            )
            for (step in 1..8) {
                composeRule.onNodeWithTag("home-list").performTouchInput {
                    moveTo(start + (end - start) * (step / 8f), delayMillis = 50)
                }
                // Allow the keyed list to lay out each move before the next pointer event.
                composeRule.mainClock.advanceTimeBy(64)
            }
            if (holdAtEndMillis > 0) composeRule.mainClock.advanceTimeBy(holdAtEndMillis)
            composeRule.onNodeWithTag("home-list").performTouchInput {
                if (cancelGesture) cancel() else up()
            }
            pointerDown = false
        } finally {
            if (pointerDown) composeRule.onNodeWithTag("home-list").performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
    }

    private fun performMove(feature: Feature, label: String) {
        val action = featureNode(feature).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == label }
        composeRule.runOnIdle { assertTrue(action.action()) }
        composeRule.waitForIdle()
    }

    private fun actionLabels(feature: Feature) = featureNode(feature).fetchSemanticsNode()
        .config[SemanticsActions.CustomActions].map { it.label }

    private fun assertAbove(first: Feature, second: Feature) {
        assertTrue(bounds(tag(first)).top < bounds(tag(second)).top)
    }

    private fun swappedFirstTwo() = FeatureOrder.default.toMutableList().apply {
        val first = removeAt(0)
        add(1, first)
    }

    private fun featureNode(feature: Feature) = composeRule.onNodeWithTag(tag(feature), useUnmergedTree = true)

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun tag(feature: Feature) = when (feature) {
        Feature.DOWNLOAD_TWITTER_VIDEO -> "home-download-twitter"
        Feature.IMAGE_ADJUSTMENT -> "home-image-adjustment"
        Feature.COPY_LOCATION -> "home-copy-location"
        else -> "home-feature-${feature.name}"
    }
}
