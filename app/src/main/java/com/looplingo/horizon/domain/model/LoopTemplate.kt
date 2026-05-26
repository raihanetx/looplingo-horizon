package com.looplingo.horizon.domain.model

data class LoopTemplate(
    val id: Long = 0,
    val videoPath: String,
    val name: String,
    val type: String,
    val defaultLoopCount: Int = 3,
    val ranges: List<LoopTemplateRange> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class LoopTemplateRange(
    val id: Long = 0,
    val templateId: Long = 0,
    val startMs: Long,
    val endMs: Long = -1L,
    val loopCount: Int
)
