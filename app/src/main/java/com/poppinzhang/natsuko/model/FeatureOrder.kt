package com.poppinzhang.natsuko.model

/** Home ordering is independent of enum declaration order and of translated feature titles. */
object FeatureOrder {
    val default: List<Feature> = (listOf(
        Feature.IMAGE_MERGE,
        Feature.DOWNLOAD_TWITTER_VIDEO,
        Feature.IMAGES_TO_PDF,
        Feature.CROP_BLACK_BORDER,
        Feature.IMAGE_ADJUSTMENT,
        Feature.COPY_LOCATION,
        Feature.PDF_TO_IMAGES,
        Feature.VIDEO_TO_GIF,
    ) + Feature.entries).distinct()

    /** Keep known saved IDs once; append new features without disturbing the user's order. */
    fun restore(ids: List<String>): List<Feature> {
        val featuresById = Feature.entries.associateBy(Feature::name)
        val restored = ids.mapNotNull(featuresById::get).distinct()
        return restored + default.filterNot(restored::contains)
    }

    /** Move [feature] to [target]'s current position, shifting intervening features one place. */
    fun move(order: List<Feature>, feature: Feature, target: Feature): List<Feature> {
        val normalized = restore(order.map(Feature::name))
        if (feature == target) return normalized
        return normalized.toMutableList().apply {
            val targetIndex = indexOf(target)
            remove(feature)
            add(targetIndex, feature)
        }
    }
}
