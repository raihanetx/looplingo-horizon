package com.looplingo.horizon.model

/**
 * Domain model for a loop template.
 *
 * Represents a reusable loop configuration for a specific video.
 * Two template types are supported:
 *  - "dialogue_repeat": Automatically loops each dialogue segment × N times, sequentially
 *  - "time_range": Different loop counts for different time ranges within the video
 *
 * @param id Database ID (0 for new templates)
 * @param videoPath Path of the video this template belongs to
 * @param name User-visible name
 * @param type Template type: "dialogue_repeat" or "time_range"
 * @param defaultLoopCount Default number of loops
 * @param ranges Time ranges with specific loop counts (only for "time_range" type)
 * @param createdAt Timestamp when this template was created
 */
data class LoopTemplate(
    val id: Long = 0,
    val videoPath: String,
    val name: String,
    val type: String,
    val defaultLoopCount: Int = 3,
    val ranges: List<LoopTemplateRange> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A single time range within a loop template.
 *
 * @param id Database ID (0 for new ranges)
 * @param templateId Foreign key to the parent template
 * @param startMs Start time in milliseconds
 * @param endMs End time in milliseconds (-1 means "to end of video")
 * @param loopCount Number of times to loop this range
 */
data class LoopTemplateRange(
    val id: Long = 0,
    val templateId: Long = 0,
    val startMs: Long,
    val endMs: Long = -1L,
    val loopCount: Int
)
