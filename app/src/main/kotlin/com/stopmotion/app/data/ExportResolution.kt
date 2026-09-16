package com.stopmotion.app.data

/**
 * Resolution presets offered in the export screen.
 *
 * Each preset exposes a target width/height in pixels. The encoder will
 * center-crop / letterbox source bitmaps to fit while preserving aspect ratio.
 *
 * @property label  Display label (e.g. "720p", "1:1 (1080)").
 * @property width  Output width in pixels.
 * @property height Output height in pixels.
 * @property bitrate Recommended bitrate (bits per second) for the encoder.
 *                   Defaults to 4 Mbps for 720p, 8 Mbps for 1080p, etc.
 */
enum class ExportResolution(
    val label: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
) {
    P720(
        label = "720p (16:9)",
        width = 1280,
        height = 720,
        bitrate = 4_000_000,
    ),
    P1080(
        label = "1080p (16:9)",
        width = 1920,
        height = 1080,
        bitrate = 8_000_000,
    ),
    SQUARE_720(
        label = "Cuadrado 1:1",
        width = 720,
        height = 720,
        bitrate = 4_000_000,
    ),
    SQUARE_1080(
        label = "Cuadrado 1:1 (HD)",
        width = 1080,
        height = 1080,
        bitrate = 8_000_000,
    ),
    VERTICAL_720(
        label = "Vertical 9:16 (720)",
        width = 720,
        height = 1280,
        bitrate = 5_000_000,
    ),
    VERTICAL_1080(
        label = "Vertical 9:16 (HD)",
        width = 1080,
        height = 1920,
        bitrate = 10_000_000,
    ),
}
