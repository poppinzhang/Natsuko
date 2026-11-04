# Cropping black borders

## Overview

Cropping images stored on Android phones. Detect and remove black borders around images by cropping the border area. It is **not** an object segmentation tool, background remover, or content-aware image editor.

## Goal

Quickly import screenshots or existing images, detect the black border region around the image, crop it out accurately, and support batch processing.

## Primary Goals

- Crop away the **full black border area** around an image.
- Do **not** perform object segmentation, foreground extraction, or background removal.
- Support **batch import** and **batch processing**.
- Support importing images through the Android **share flow**, especially from screenshots.
- When an image is shared to the app, process it **automatically** whenever possible.

## Non-Goals

The following are out of scope unless explicitly added later:

- Object segmentation
- AI-based background removal
- Semantic understanding of image content
- Image beautification or enhancement
- Cloud-based image processing
- Server-side processing
- Support for Android versions below Android 16

## Core Functional Requirements

### 1. Image Import

Should support importing images from:

- Local image selection on the device
- Batch selection of multiple images
- Android share intents from other apps
- Screenshot share actions

### 2. Automatic Processing

When an image is shared to the app:

- Should start processing automatically when the input is valid.
- Minimize unnecessary manual steps.
- The workflow should be fast and suitable for frequent screenshot usage.

### 3. Black Border Cropping

The cropping logic should:

- Detect black borders along the outer edges of the image
- Remove the **entire continuous black border area**
- Preserve the actual image content as much as possible
- Avoid treating normal dark content inside the image as removable border area

### 4. Batch Processing

- Importing multiple images in one operation
- Processing them in sequence or in parallel, depending on implementation choice
- Producing cropped results for all valid inputs
- Clear handling of partial failures

## Functional Expectations

- Cropping should be deterministic and predictable.
- The result should be stable for repeated runs on the same input.
- Should prioritize correctness over aggressive cropping.
- If the border is ambiguous, prefer safer cropping behavior that avoids cutting real content.

## Input and Output Expectations

### Input

Supported input types may include:

- PNG screenshots
- JPEG photos or saved images
- Other Android-supported image formats if practical

### Output

- Output should be the cropped image.
- Output images should preserve reasonable visual quality.
- Output file naming should be consistent and easy to trace back to the original image.
- Batch outputs should be easy for users to locate.

## Image Processing Rules

When implementing black-border cropping, follow these principles:

- Detect borders from the outer edge inward
- Treat the task as **rectangular edge trimming**
- Do not infer object boundaries
- Do not use segmentation terminology unless a future feature explicitly adds it
- Be conservative when the image contains dark or black regions near the edge
- Prefer configurable thresholds if needed, but keep defaults simple and robust

## UX Expectations

- Should be simple and fast for repeated use.
- The screenshot-to-app workflow should require as little user interaction as possible.
- Batch processing progress should be visible to the user.
- Failures should be reported clearly for individual files.
- Successful results should be easy to review and save/share.

## Error Handling Expectations

Should handle the following gracefully:

- Unsupported image formats
- Corrupted image files
- Images without detectable black borders
- Permission or storage access failures
- Partial batch-processing failures
- Invalid or unexpected share-intent payloads

## Performance Expectations

- Should remain responsive during processing.
- Large batch imports should not block the UI thread.
- Avoid unnecessary memory duplication for large images.
- Prefer efficient image decoding and processing strategies suitable for mobile devices.

## Testing Requirements

Changes should include appropriate tests whenever possible.

At minimum, test the following:

- Importing a single image
- Importing multiple images
- Handling Android share intents
- Automatic processing on shared screenshots
- Correct cropping of obvious black-border cases
- Safe behavior for images that contain dark content near the edge
- Behavior when no black border is present
- Failure handling for invalid inputs

## Code Change Rules

When modifying the project:

- Preserve the main product goal: **crop black borders, do not segment content**
- Keep behavior deterministic
- Avoid introducing unnecessary complexity
- Prefer small, well-scoped changes
- Update tests together with feature changes
- Update documentation when behavior or workflow changes

## Documentation Rules

When adding or changing features:

- Update relevant project documentation
- Keep terminology consistent
- Explicitly distinguish:
  - border cropping
  - content segmentation
  - background removal

## Terminology

Use the following terms consistently:

- **black border cropping**: removing black edges by trimming the image bounds
- **batch processing**: processing multiple imported images in one workflow
- **share import**: importing images through Android's share mechanism
- **automatic processing**: processing immediately after successful import, with minimal user interaction

Avoid using these terms unless the project actually supports them:

- segmentation
- matting
- cutout
- background removal
- object extraction

## Current Implementation

### Entry Points and Workflow

- The home screen exposes **去除黑边** in the **图片** category. Local image selection accepts up to 100 images per operation.
- The independent Android share target `CropBlackBorderShare` accepts `image/*` through `ACTION_SEND` and `ACTION_SEND_MULTIPLE`, including screenshot shares. Share import reads `EXTRA_STREAM` and `ClipData`, deduplicates URIs, and validates the input type.
- Both local selection and share import start **automatic processing** after successful import. There is no configuration screen or additional start confirmation for this feature.
- **Batch processing** runs sequentially on the background dispatcher, with visible file progress and cancellation. A failed file does not stop other inputs from being processed.
- When every input succeeds, processing returns to the home screen with a success message and **查看结果**. The latest results remain available until another workflow starts or the current workflow is reset, so users can open, save, or share them after returning home.
- Partial or complete processing failures open the result screen with per-file diagnostics. If optional saving to user storage fails, the result screen retains the successful application-specific outputs and offers the existing save retry flow.

### Deterministic Detection

The detector trims rectangular bounds from the outer edges inward. A pixel qualifies as black only when it is fully opaque (`alpha == 255`) and every sRGB channel is at most `8`. An entire row or column must qualify before it can be removed; any brighter or transparent pixel prevents that row or column from being trimmed. Detection does not use a percentage of dark pixels or infer foreground shapes.

All contiguous qualifying edge rows and columns are removed. If no removable border is found, the full image is retained. If the entire image qualifies as near-black, it is also retained in full rather than producing empty bounds. Both cases generate a PNG result with status **未检测到可裁剪黑边（保持原尺寸）**; changed images have status **已去除黑边**. The result screen displays this status separately below the source filename so a long filename cannot hide whether cropping occurred.

This conservative rule preserves irregular dark content that shares an edge with brighter or transparent pixels. It cannot distinguish an actual border from real content that forms a complete, opaque, uniformly near-black strip at an edge; such strips can be removed. Conversely, a brighter pixel or compression artifact in an otherwise black border can stop trimming early. There is no object segmentation, background removal, adjustable threshold, or manual correction in this version.

### Image Quality, Limits, and Output

- Images are decoded with Android `ImageDecoder` as software bitmaps, with EXIF orientation applied and colors converted to sRGB. PNG and JPEG inputs are supported when readable by Android; other image formats depend on the platform decoder.
- Detection and cropping retain the original oriented pixel resolution. The longest side must be at most **8192 px**, and the total pixel count must be at most **16,000,000**. Inputs exceeding either limit fail individually; they are not downsampled because that could blur the exact border into adjacent content.
- Each successful input produces a new losslessly encoded PNG, including inputs whose bounds remain unchanged. The original file is not overwritten. Encoding as PNG preserves the decoded pixels; it is not a byte-for-byte copy of the original file.
- Filenames use `<accepted input index>_<sanitized original stem>_cropped.png`, starting the index at 1 after duplicate URIs and unsupported import types are filtered. Decode failures retain their position in this accepted input order. Sanitizing follows the shared output repository's filename rules, with excessively long stems shortened to at most 70 UTF-16 code units without splitting a supplementary character. This leaves room for the index and suffix within the filesystem's 255-byte filename limit; the index also prevents collisions between repeated names in one batch.
- Application-specific output goes to `Pictures/Natsuko/CropBlackBorder/<timestamp>/`. The optional user-storage setting also applies: the default destination is `Download/Natsuko/CropBlackBorder/`, or `CropBlackBorder/` beneath an authorized custom directory, with no per-run timestamp directory. The result screen supports opening, sharing, saving, and retrying failed saves. See [Output storage](output_storage.md).
- Bitmaps are processed one input at a time and released after each file. Detection uses a row buffer rather than duplicating the entire source pixel array. Cancellation checkpoints run during detection and output writing; incomplete output files are removed.

## Future Extensions

Possible future features may include:

- User-adjustable crop thresholds
- Preview before saving
- Manual correction tools
- Additional result export options
- Processing history
- More image format support

These are optional and not part of the current baseline scope.
