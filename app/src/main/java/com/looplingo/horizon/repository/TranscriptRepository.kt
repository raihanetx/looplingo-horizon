package com.looplingo.horizon.repository

import com.looplingo.horizon.model.SubtitleCue
import com.looplingo.horizon.util.SubtitleScanner
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing subtitle/transcript data.
 *
 * Responsibilities:
 *  - Load and cache subtitle cues for a given video file
 *  - Provide the current active cue based on playback position
 *  - Clear cache when memory is needed
 *
 * Subtitles are loaded from files (.srt, .vtt, .lrc) found by [SubtitleScanner].
 * They are cached in memory for fast access during playback.
 * No database storage is needed — subtitle files are the source of truth.
 */
@Singleton
class TranscriptRepository @Inject constructor(
    private val subtitleScanner: SubtitleScanner
) {
    /** In-memory cache of parsed subtitle cues, keyed by video path. */
    private val cache = mutableMapOf<String, List<SubtitleCue>>()

    /**
     * Load subtitle cues for the given video path.
     *
     * Uses cached data if available, otherwise scans for subtitle files
     * and parses them. Returns an empty list if no subtitles are found.
     */
    fun getSubtitlesForVideo(videoPath: String): List<SubtitleCue> {
        cache[videoPath]?.let { return it }

        val cues = subtitleScanner.findSubtitlesForVideo(videoPath)
        if (cues.isNotEmpty()) {
            cache[videoPath] = cues
            Timber.i("Cached %d subtitle cues for: %s", cues.size, videoPath.substringAfterLast("/"))
        }
        return cues
    }

    /**
     * Force reload subtitles for a video (e.g., after the user adds a new subtitle file).
     */
    fun reloadSubtitles(videoPath: String): List<SubtitleCue> {
        cache.remove(videoPath)
        return getSubtitlesForVideo(videoPath)
    }

    /**
     * Find the subtitle cue that is active at the given playback position.
     *
     * Uses binary search for efficient lookup in large subtitle files.
     * Returns null if no cue is active at the given position.
     */
    fun getActiveCue(videoPath: String, positionMs: Long): SubtitleCue? {
        val cues = cache[videoPath] ?: return null
        if (cues.isEmpty()) return null

        // Binary search for the cue whose range contains positionMs
        var low = 0
        var high = cues.lastIndex
        while (low <= high) {
            val mid = (low + high) / 2
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs > cue.endMs -> low = mid + 1
                else -> return cue  // positionMs is within [startMs, endMs]
            }
        }
        return null
    }

    /**
     * Get the index of the currently active cue (for RecyclerView scrolling).
     * Returns -1 if no cue is active.
     */
    fun getActiveCueIndex(videoPath: String, positionMs: Long): Int {
        val cues = cache[videoPath] ?: return -1
        val activeCue = getActiveCue(videoPath, positionMs) ?: return -1
        return cues.indexOf(activeCue)
    }

    /**
     * Check if a video has subtitles available.
     */
    fun hasSubtitles(videoPath: String): Boolean {
        return getSubtitlesForVideo(videoPath).isNotEmpty()
    }

    /**
     * Clear the subtitle cache to free memory.
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Clear cached subtitles for a specific video.
     */
    fun clearCacheForVideo(videoPath: String) {
        cache.remove(videoPath)
    }
}
