package com.poppinzhang.natsuko

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.FeatureOrderPreferences
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.FeatureOrder
import com.poppinzhang.natsuko.ui.HomeScreen
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import kotlin.math.abs
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
    private val constrainedHeight = mutableStateOf<Dp?>(null)
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
    fun homeDisplaysTwoColumnEqualHeightGridWithCompactSpacing() {
        showHome()
        val first = bounds(tag(Feature.IMAGE_MERGE))
        val second = bounds(tag(Feature.DOWNLOAD_TWITTER_VIDEO))
        val third = bounds(tag(Feature.IMAGES_TO_PDF))
        val fourth = bounds(tag(Feature.CROP_BLACK_BORDER))

        assertTrue(abs(first.top - second.top) < 2f)
        assertTrue(first.right < second.left)
        assertTrue(third.top > first.bottom)
        assertTrue(abs(third.top - fourth.top) < 2f)
        assertTrue(abs(first.height - second.height) < 2f)
        assertTrue(abs(first.height - fourth.height) < 2f)

        val columnGapDp = (second.left - first.right) / context.resources.displayMetrics.density
        val rowGapDp = (third.top - first.bottom) / context.resources.displayMetrics.density
        assertTrue("Expected compact column gap around 8dp, got $columnGapDp", columnGapDp in 6f..10f)
        assertTrue("Expected compact row gap around 8dp, got $rowGapDp", rowGapDp in 6f..10f)
    }

    @Test
    fun tappingCardsInEitherColumnOpensTheirFeature() {
        showHome()
        featureNode(Feature.IMAGE_MERGE).performTouchInput {
            down(center)
            up()
        }
        featureNode(Feature.DOWNLOAD_TWITTER_VIDEO).performTouchInput {
            down(center)
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf(Feature.IMAGE_MERGE, Feature.DOWNLOAD_TWITTER_VIDEO), openedFeatures)
            assertEquals(FeatureOrder.default, preferences.read())
        }
    }

    @Test
    fun ordinarySwipeScrollsWithoutReorderingOrOpeningAFeature() {
        showHome(maxHeight = 420.dp)
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
    fun longPressDragReordersAcrossColumnsAndRowsAndPersistsWithoutOpening() {
        showHome()
        dragFirstOntoSecond(cancelGesture = false)
        val afterHorizontalSwap = swappedFirstTwo()

        assertEquals(afterHorizontalSwap, FeatureOrderPreferences(context).read())
        assertTrue(openedFeatures.isEmpty())
        assertLeftOf(afterHorizontalSwap[0], afterHorizontalSwap[1])

        // Drag IMAGE_MERGE (now row 0 col 1) diagonally down onto IMAGES_TO_PDF (row 1 col 0).
        val viewport = bounds("home-list")
        val start = bounds(tag(Feature.IMAGE_MERGE)).center - viewport.topLeft
        val target = bounds(tag(Feature.IMAGES_TO_PDF)).center - viewport.topLeft
        performHeldDrag(Feature.IMAGE_MERGE, start, target)

        val expected = FeatureOrder.move(afterHorizontalSwap, Feature.IMAGE_MERGE, Feature.IMAGES_TO_PDF)
        assertEquals(expected, FeatureOrderPreferences(context).read())
        assertTrue(openedFeatures.isEmpty())

        recreateHome()
        assertEquals(expected, preferences.read())
        assertLeftOf(expected[0], expected[1])
        assertAbove(expected[0], expected[2])
        assertLeftOf(expected[2], expected[3])
    }

    @Test
    fun cancellingADragRestoresThePreviousOrder() {
        showHome()
        dragFirstOntoSecond(cancelGesture = true)

        assertEquals(FeatureOrder.default, preferences.read())
        assertTrue(openedFeatures.isEmpty())
        assertLeftOf(Feature.IMAGE_MERGE, Feature.DOWNLOAD_TWITTER_VIDEO)
        recreateHome()
        assertLeftOf(Feature.IMAGE_MERGE, Feature.DOWNLOAD_TWITTER_VIDEO)
    }

    @Test
    fun accessibilityMovesPersistAndRespectFirstAndLastBoundaries() {
        showHome()
        val earlier = context.getString(R.string.main_move_earlier)
        val later = context.getString(R.string.main_move_later)
        assertEquals(listOf(later), actionLabels(Feature.IMAGE_MERGE))

        performMove(Feature.IMAGE_MERGE, later)
        assertEquals(swappedFirstTwo(), preferences.read())
        recreateHome()
        assertLeftOf(Feature.DOWNLOAD_TWITTER_VIDEO, Feature.IMAGE_MERGE)
        assertEquals(listOf(earlier, later), actionLabels(Feature.IMAGE_MERGE))

        performMove(Feature.IMAGE_MERGE, earlier)
        assertEquals(FeatureOrder.default, preferences.read())
        composeRule.onNodeWithTag("home-list").performScrollToNode(hasTestTag(tag(Feature.VIDEO_TO_GIF)))
        assertEquals(listOf(earlier), actionLabels(Feature.VIDEO_TO_GIF))
        assertTrue(openedFeatures.isEmpty())
    }

    @Test
    fun holdingDraggedCardAtBottomScrollsAcrossCategoriesToTheEnd() {
        showHome(maxHeight = 420.dp)
        val viewport = bounds("home-list")
        val start = bounds(tag(Feature.IMAGE_MERGE)).center - viewport.topLeft
        val edge = Offset(start.x, viewport.height - 4f)
        // Keep the finger still at the edge: scrolling must continue without new move events.
        performHeldDrag(Feature.IMAGE_MERGE, start, edge, holdAtEndMillis = 6_000)

        val expected = FeatureOrder.default.drop(1) + Feature.IMAGE_MERGE
        assertEquals(expected, preferences.read())
        assertTrue(openedFeatures.isEmpty())
        recreateHome()
        assertLeftOf(Feature.DOWNLOAD_TWITTER_VIDEO, Feature.IMAGES_TO_PDF)
        composeRule.onNodeWithTag("home-list").performScrollToNode(hasTestTag(tag(Feature.IMAGE_MERGE)))
        assertFalse(actionLabels(Feature.IMAGE_MERGE).contains(context.getString(R.string.main_move_later)))
    }

    @Test
    fun holdingDraggedCardAtTopScrollsAcrossCategoriesToTheBeginning() {
        showHome(maxHeight = 420.dp)
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
        assertLeftOf(Feature.VIDEO_TO_GIF, Feature.IMAGE_MERGE)
        assertFalse(actionLabels(Feature.VIDEO_TO_GIF).contains(context.getString(R.string.main_move_earlier)))
    }

    private fun showHome(maxHeight: Dp? = null) {
        constrainedHeight.value = maxHeight
        composeRule.setContent {
            NatsukoTheme {
                key(screenGeneration.intValue) {
                    val boxModifier = Modifier.fillMaxWidth().let { modifier ->
                        val height = constrainedHeight.value
                        if (height != null) modifier.height(height) else modifier
                    }
                    Box(boxModifier) {
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
        }
        composeRule.waitForIdle()
    }

    private fun recreateHome() {
        // Dispose all remembered screen state so the new screen has to read the persisted order.
        composeRule.runOnIdle { screenGeneration.intValue += 1 }
        composeRule.waitForIdle()
    }

    private fun dragFirstOntoSecond(cancelGesture: Boolean) {
        val viewport = bounds("home-list")
        val first = bounds(tag(Feature.IMAGE_MERGE))
        val second = bounds(tag(Feature.DOWNLOAD_TWITTER_VIDEO))
        val start = first.center - viewport.topLeft
        val end = second.center - viewport.topLeft
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
                preferences.read().indexOf(feature) + 1,
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
                // Allow the keyed grid to lay out each move before the next pointer event.
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

    private fun assertLeftOf(first: Feature, second: Feature) {
        val firstBounds = bounds(tag(first))
        val secondBounds = bounds(tag(second))
        assertTrue(abs(firstBounds.top - secondBounds.top) < 2f)
        assertTrue(firstBounds.left < secondBounds.left)
    }

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
