package com.poppinzhang.natsuko.model

import androidx.annotation.StringRes
import com.poppinzhang.natsuko.R

enum class Feature(
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int,
    val folderName: String,
) {
    CROP_BLACK_BORDER(R.string.feature_crop_black_border, R.string.feature_crop_black_border_description, "CropBlackBorder"),
    IMAGE_MERGE(R.string.feature_image_merge, R.string.feature_image_merge_description, "ImageMerge"),
    IMAGES_TO_PDF(R.string.feature_images_to_pdf, R.string.feature_images_to_pdf_description, "ImagesToPdf"),
    PDF_TO_IMAGES(R.string.feature_pdf_to_images, R.string.feature_pdf_to_images_description, "PdfToImages"),
    VIDEO_TO_GIF(R.string.feature_video_to_gif, R.string.feature_video_to_gif_description, "VideoToGif"),
    DOWNLOAD_TWITTER_VIDEO(R.string.feature_download_twitter_video, R.string.feature_download_twitter_video_description, "DownloadTwitterVideo"),
    IMAGE_ADJUSTMENT(R.string.feature_image_adjustment, R.string.feature_image_adjustment_description, "ImageAdjustment");

    companion object {
        fun fromShareComponent(className: String?): Feature? = when {
            className?.endsWith("CropBlackBorderShare") == true -> CROP_BLACK_BORDER
            className?.endsWith("ImageMergeShare") == true -> IMAGE_MERGE
            className?.endsWith("ImagesToPdfShare") == true -> IMAGES_TO_PDF
            className?.endsWith("PdfToImagesShare") == true -> PDF_TO_IMAGES
            className?.endsWith("VideoToGifShare") == true -> VIDEO_TO_GIF
            className?.endsWith("DownloadTwitterVideoShare") == true -> DOWNLOAD_TWITTER_VIDEO
            className?.endsWith("ImageAdjustmentShare") == true -> IMAGE_ADJUSTMENT
            else -> null
        }
    }
}
