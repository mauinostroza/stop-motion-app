package com.stopmotion.app.data

import android.net.Uri

/**
 * Represents a single photo frame within a stop motion project.
 *
 * A [Frame] may be backed by either:
 *  - a content [Uri] (typically when picked from the system Photo Picker), or
 *  - an absolute file path (when captured via CameraX ImageCapture).
 *
 * Both representations are mutually convertible and resolved by the
 * encoder/decoder pipeline when needed.
 *
 * @property id            Stable identifier (UUID-style string).
 * @property sourceUri     Source URI when the frame originates from the
 *                          photo picker. May be null for captured frames.
 * @property filePath      Absolute file path when captured with CameraX.
 *                          May be null for picked frames until cached locally.
 * @property order         Position of the frame within the project timeline.
 *                          0-indexed. Reordering mutates this field.
 * @property capturedAt    Epoch millis when the frame was added.
 * @property durationMs    Per-frame duration override (default null means
 *                          "use project-wide FPS" for the timeline).
 */
data class Frame(
    val id: String,
    val sourceUri: Uri?,
    val filePath: String?,
    var order: Int,
    val capturedAt: Long,
    val durationMs: Long? = null,
)
