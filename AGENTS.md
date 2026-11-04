# AGENTS.md

## Project Overview

This project is an Android application for processing images stored on Android devices.

## Project Name

The project is named after **Natsuko Hirose (広瀬ナツ子)**, the protagonist of the anime *ZENSHU* (「全修。」).

The name reflects the idea that, much like the title *ZENSHU* implies comprehensive reworking, this project is intended to provide a wide range of image-processing capabilities.

The application will use an avatar of **Natsuko Hirose (広瀬ナツ子)** as its app icon.

The root-level `natsuko.png` is the canonical source for every app-icon appearance. Use that image directly or a dimension-resized version of it; do not substitute separate artwork for launcher, round, share-target, splash, themed, or in-app branding icons.

## Project Goals

Design a simple and clean main screen that serves as the entry point for all supported image-processing features. All features should be grouped by category and displayed directly on the main screen.

When the user selects a feature, navigate to an image picker where they can choose an image from the device.

After an image is selected:

* If the selected feature requires additional configuration or options, open a dedicated screen for that feature and allow the user to configure the processing parameters.
* If the feature can process the image immediately without additional input, perform the operation directly, show a success message, and return to the main screen.

All newly generated images must be saved to an application-specific image directory managed by this app.

## Product Requirements

* The app only needs to support Android 16 and later. Backward compatibility with older Android versions is not required.
* The app must integrate with Android's system sharing flow. When an image is shared from another app, users should be able to select a specific feature of this app as the share target, and the app should automatically enter the processing flow for that feature with the shared image.

## Feature Overview

> The descriptions in this section only provide a high-level overview of each feature. Detailed design requirements and implementation notes are documented under the `docs/` directory, in the Markdown file corresponding to each feature name.

### Image Merge

Allow the user to select multiple images and seamlessly combine them into a single image.

Requirements:

* Support both horizontal and vertical layouts.
* Allow the user to control the position/order of each image in the merged result.
* If the source images have different dimensions along the non-merging axis, center the smaller image and fill the remaining area with a solid color.
* The fill color must be configurable by the user.

### Images to PDF

Allow the user to select multiple images and convert them to PDF files in batch.

Requirements:

* Support combining all selected images into a single PDF.
* Support generating a separate PDF file for each selected image.
* Provide commonly used PDF configuration options.

### PDF to Images

Allow the user to select multiple PDF files and batch-convert their pages into images.

### Video to GIF

Convert video files into animated GIFs.

Only commonly used video container formats need to be supported, such as:

* MP4
* MKV
* MOV

Only commonly used video codecs need to be supported, such as:

* H.264
* H.265
* AV1

Allow the user to configure common GIF output parameters, including:

* Output frame rate
* Frame sampling interval
* GIF resolution
* Other commonly used GIF conversion options

## Technical Direction

* The project is an **Android app**.
* Prefer modern Android development practices.
* Use the embedded FFmpeg backend for all current and future video-resource processing, including metadata probing, decoding, frame extraction, filtering, and encoding. Reuse the shared video engine rather than introducing separate Android media-processing paths.
* Bundle the required FFmpeg binaries and codecs in the application at build time. Video features must work immediately after installation without downloading executables, native libraries, or codecs at runtime.
* Keep the implementation maintainable and easy to extend.
* Favor a clear separation between:
  * UI layer
  * image import / intent handling
  * image processing logic
  * output / storage handling

## UX Expectations

* The app should be simple and fast for repeated use.
* The screenshot-to-app or shard-to-app workflow should require as little user interaction as possible.
* Batch processing progress should be visible to the user.
* Failures should be reported clearly for individual files.
* Successful results should be easy to review and save/share.

## Error Handling Expectations

The app should handle the following gracefully:

* Unsupported image/PDF/video formats
* Corrupted image/PDF/video files
* Permission or storage access failures
* Partial batch-processing failures
* Invalid or unexpected share-intent payloads

## Performance Expectations

* The app should remain responsive during processing.
* Large batch imports should not block the UI thread.
* Avoid unnecessary memory duplication for large images.
* Prefer efficient image decoding and processing strategies suitable for mobile devices.

## Testing Requirements

Changes should include appropriate tests whenever possible.

At minimum, test the following:

* Importing a single image/PDF
* Importing multiple images/PDFs
* Handling Android share intents
* Automatic processing on shared screenshots
* Failure handling for invalid inputs

## Code Change Rules

When modifying the project:

* Keep behavior deterministic
* Avoid introducing unnecessary complexity
* Prefer small, well-scoped changes
* Update tests together with feature changes
* Update documentation when behavior or workflow changes
* Follow `docs/release.md`: record feature changes and fixes in `CHANGELOG.md` under `## unstaged`; on release, rename that heading to the version and update `version.properties` with an increasing Android version code.

## Documentation Rules

When adding or changing features:

* Update relevant project documentation
* Keep terminology consistent
* Keep the documentation for each feature up to date whenever its design or implementation changes.

## Terminology

Use the following terms consistently:

* **batch processing**: processing multiple imported images in one workflow
* **share import**: importing images through Android's share mechanism
* **automatic processing**: processing immediately after successful import, with minimal user interaction
