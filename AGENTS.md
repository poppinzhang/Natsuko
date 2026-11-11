# AGENTS.md

## Project Overview

This project is an Android application for processing images, PDFs, and videos stored on Android devices, with a separate workflow for downloading videos from public X / Twitter posts.

`README.md` is the user-facing introduction. Keep setup commands, architecture details, implementation constraints, and agent instructions in this file or the linked technical documentation.

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

## Repository Layout

```text
app/src/main/java/com/poppinzhang/natsuko/
├── data/          # URI metadata, share import, preferences, downloads, and output storage
├── model/         # Features, options, progress, and processing results
├── processing/    # Image/PDF processors and feature orchestration
│   ├── location/  # Image location metadata processing
│   └── video/     # Shared FFmpeg video engine
└── ui/            # Compose screens, UI state, and ViewModel
```

Read [architecture and workflows](docs/architecture.md) before changing layer boundaries or import/output behavior. All video probing, decoding, frame extraction, filtering, and encoding must continue to use the shared video engine. The bundled backend supports `arm64-v8a` and `x86_64`; supported formats depend on the components included in that build.

## Implementation References

Read the relevant feature document before changing behavior. Keep parameter limits, validation rules, cancellation semantics, and test coverage in these documents instead of repeating them in the README.

| Area | Reference |
| :-- | :-- |
| Image merge, preview, ordering, horizontal/vertical/grid layouts, fill color, and size limits | [Image merge](docs/image_merge.md) |
| Automatic screenshot processing and conservative black-border detection | [Crop black border](docs/crop_black_border.md) |
| Crop, straighten, rotate, mirror, resize, undo/redo, and export size estimation | [Image adjustment](docs/image_adjustment.md) |
| Combined/per-image PDF output, page size, orientation, and margins | [Images to PDF](docs/image2pdf.md) |
| Batch PDF rendering, PNG/JPEG output, and resolution limits | [PDF to images](docs/pdf2image.md) |
| Video formats, palette, dithering, sampling, looping, and the frame limit | [Video to GIF](docs/video2gif.md) |
| Public-post URL validation, download boundaries, and conversion of downloaded MP4 files to GIF | [Download Twitter video](docs/download_twitter_video.md) |
| Source GPS validation, metadata formats, conflict decisions, and same-format output copies | [Copy image location](docs/copy_location.md) |
| App-specific output, user-directory saving, permissions, and per-file retries | [Output storage](docs/output_storage.md) |
| Persistent feature ordering, cross-category dragging, and TalkBack actions | [Home feature order](docs/home_feature_order.md) |
| Simplified Chinese, Traditional Chinese, Korean, English, and system-language fallback | [Internationalization](docs/i18n.md) |
| Persistent system/light/dark mode, including share import | [Appearance](docs/appearance.md) |
| Offline dependency notices and license maintenance | [About](docs/about.md) and [native licenses](docs/native_licenses.md) |

Share targets must preserve each feature's workflow: black-border removal starts automatic processing; a shared post opens download confirmation; image adjustment opens the editor after choosing one image when necessary; location copying validates a source before selecting targets. The remaining conversion features open their configuration screens. Keep activity aliases, MIME filters, and parser tests aligned with these workflows.

Generated files first go to app-specific storage. Optional user-directory saving uses `Download/Natsuko/<feature>/` or an authorized custom directory, without an additional timestamp folder in the user-visible destination. Preserve app-specific results when user-directory saving fails, and retry only files that have not been saved successfully.

Local processing must not upload media. Twitter downloads use X's public HTTPS endpoints and video CDN without account login or third-party parsing services. Do not introduce runtime downloads of executables, native libraries, or codecs; see the bundled [FFmpeg](third_party/ffmpeg/README.md) and [metadata runtime](third_party/metadata/README.md) build references.

## Build, Verification, and Release

Use JDK 17 and the repository's Gradle Wrapper. Toolchain versions, Android SDK setup, signing, CI, and artifact verification are documented in [build and release](docs/build_release.md).

On macOS with Homebrew and Android SDK Command-line Tools available, prepare the local build environment with:

```bash
./scripts/bootstrap.sh
```

For application changes, run the relevant checks. The standard local validation is:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

With an Android 16 device or emulator, also run `./gradlew connectedDebugAndroidTest`. Changes to packaging should run `./scripts/test-package.sh`; use the feature documents for additional targeted checks. Documentation-only edits need link and diff checks, not an APK rebuild.

After preparing the release changelog, version, and dedicated signing configuration, build signed release artifacts with:

```bash
./scripts/package.sh
```

Use `./scripts/package.sh --unsigned` only for explicitly unsigned validation builds; those APKs are not installable release packages. Artifacts, release notes, source archives, and SHA-256 checksums are written to `dist/<version>/`; an existing directory for the same version is backed up after successful packaging.

Follow [the release rules](docs/release.md). `version.properties` is the sole source of the version name and Android version code; do not maintain duplicate current-version values in the README. Local packaging does not authorize pushing tags or publishing a remote release.

Preserve [LICENSE](LICENSE), [NOTICE](NOTICE), and [third-party notices](THIRD_PARTY_NOTICES.md). FFmpeg remains LGPL-2.1-or-later, with corresponding source and rebuild materials in `natsuko-third-party-sources-<version>.tar.gz`. Character artwork is excluded from the Apache-2.0 grant; its usage and redistribution permissions must be verified before public distribution.

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

Keep documentation organized by audience:

* `README.md`: what the app does, how to use it, system requirements, saving results, privacy, and licensing. Use concise descriptions and links rather than implementation checklists, internal version codes, build commands, or source trees.
* `AGENTS.md`: development and agent instructions, repository structure, validation commands, release conventions, and references to implementation documents.
* `docs/`: detailed feature behavior, limits, design, testing, and maintenance procedures.
* `CHANGELOG.md`: released and unreleased changes. Mark unreleased functionality clearly in user-facing documentation; do not infer published downloads from a local build or version setting.

When adding or changing features:

* Update relevant project documentation
* Keep terminology consistent
* Keep the documentation for each feature up to date whenever its design or implementation changes.

## Terminology

Use the following terms consistently:

* **batch processing**: processing multiple imported images in one workflow
* **share import**: importing images through Android's share mechanism
* **automatic processing**: processing immediately after successful import, with minimal user interaction
