package com.stopmotion.app.data

/**
 * Persistent state of a single stop motion project.
 *
 * @property id            Unique identifier for the project.
 * @property frames       Ordered list of frames currently in the timeline.
 *                          The first item is the first frame to be shown.
 * @property frameRateFps  Default frames-per-second used when no per-frame
 *                          duration override is set.
 * @property resolution    Selected resolution for export.
 * @property createdAt     Epoch millis when the project was created.
 */
data class Project(
    val id: String,
    val frames: MutableList<Frame>,
    var frameRateFps: Int = 12,
    var resolution: ExportResolution = ExportResolution.P720,
    val createdAt: Long = System.currentTimeMillis(),
)
