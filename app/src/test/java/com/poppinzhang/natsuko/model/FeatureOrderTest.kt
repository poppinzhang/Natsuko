package com.poppinzhang.natsuko.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureOrderTest {
    @Test
    fun freshInstallShowsEveryFeatureOnceInExistingHomeOrder() {
        assertEquals(
            listOf(
                Feature.IMAGE_MERGE,
                Feature.DOWNLOAD_TWITTER_VIDEO,
                Feature.IMAGES_TO_PDF,
                Feature.CROP_BLACK_BORDER,
                Feature.IMAGE_ADJUSTMENT,
                Feature.COPY_LOCATION,
                Feature.PDF_TO_IMAGES,
                Feature.VIDEO_TO_GIF,
            ),
            FeatureOrder.restore(emptyList()),
        )
        assertEquals(Feature.entries.toSet(), FeatureOrder.default.toSet())
        assertEquals(Feature.entries.size, FeatureOrder.default.size)
    }

    @Test
    fun savedOrderSurvivesRoundTripWithoutUsingEnumOrdinals() {
        val custom = FeatureOrder.default.reversed()
        assertEquals(custom, FeatureOrder.restore(custom.map(Feature::name)))
    }

    @Test
    fun unknownAndDuplicateIdsAreDiscardedAndMissingFeaturesAreAppended() {
        val order = FeatureOrder.restore(listOf("VIDEO_TO_GIF", "removed-feature", "VIDEO_TO_GIF", "IMAGE_MERGE", ""))
        assertEquals(
            listOf(Feature.VIDEO_TO_GIF, Feature.IMAGE_MERGE) +
                FeatureOrder.default.filterNot { it in setOf(Feature.VIDEO_TO_GIF, Feature.IMAGE_MERGE) },
            order,
        )
        assertEquals(Feature.entries.size, order.size)
    }

    @Test
    fun unknownOnlyIdsFallBackToDefault() {
        assertEquals(FeatureOrder.default, FeatureOrder.restore(listOf("", "unknown", "0", "image_merge")))
    }

    @Test
    fun moveDownAcrossCategoriesShiftsInterveningFeaturesAndLeavesInputUntouched() {
        val original = FeatureOrder.default.toList()
        val moved = FeatureOrder.move(original, Feature.IMAGE_MERGE, Feature.VIDEO_TO_GIF)
        assertEquals(original.drop(1) + Feature.IMAGE_MERGE, moved)
        assertEquals(FeatureOrder.default, original)
    }

    @Test
    fun moveUpAcrossCategoriesCanPlaceLastFeatureFirst() {
        val original = FeatureOrder.default
        assertEquals(
            listOf(Feature.VIDEO_TO_GIF) + original.dropLast(1),
            FeatureOrder.move(original, Feature.VIDEO_TO_GIF, Feature.IMAGE_MERGE),
        )
    }

    @Test
    fun moveToSamePositionKeepsCustomOrder() {
        val custom = FeatureOrder.default.reversed()
        assertEquals(custom, FeatureOrder.move(custom, Feature.IMAGES_TO_PDF, Feature.IMAGES_TO_PDF))
    }

    @Test
    fun moveNormalizesIncompleteOrderBeforeResolvingPositions() {
        val moved = FeatureOrder.move(listOf(Feature.IMAGE_MERGE, Feature.IMAGE_MERGE), Feature.VIDEO_TO_GIF, Feature.IMAGE_MERGE)
        assertEquals(Feature.VIDEO_TO_GIF, moved.first())
        assertEquals(Feature.entries.toSet(), moved.toSet())
        assertEquals(Feature.entries.size, moved.size)
    }
}
