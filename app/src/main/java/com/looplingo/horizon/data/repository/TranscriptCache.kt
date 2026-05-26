package com.looplingo.horizon.data.repository

import com.looplingo.horizon.domain.model.SubtitleCue
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptCache @Inject constructor() {

    companion object {
        private const val MAX_CACHE_ENTRIES = 50
    }

    private val cache = ConcurrentHashMap<String, List<SubtitleCue>>()

    fun get(videoPath: String): List<SubtitleCue>? = cache[videoPath]

    fun put(videoPath: String, cues: List<SubtitleCue>) {
        cache[videoPath] = cues
        trim()
    }

    fun remove(videoPath: String) {
        cache.remove(videoPath)
    }

    fun clear() {
        cache.clear()
    }

    fun getActiveCue(videoPath: String, positionMs: Long): SubtitleCue? {
        val cues = cache[videoPath] ?: return null
        if (cues.isEmpty()) return null
        var low = 0
        var high = cues.lastIndex
        while (low <= high) {
            val mid = (low + high) / 2
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs > cue.endMs -> low = mid + 1
                else -> return cue
            }
        }
        return null
    }

    fun getActiveCueIndex(videoPath: String, positionMs: Long): Int {
        val cues = cache[videoPath] ?: return -1
        val activeCue = getActiveCue(videoPath, positionMs) ?: return -1
        return cues.indexOf(activeCue)
    }

    private fun trim() {
        synchronized(cache) {
            if (cache.size > MAX_CACHE_ENTRIES) {
                val keysToRemove = cache.keys.toList().take(cache.size - MAX_CACHE_ENTRIES + 10)
                keysToRemove.forEach { cache.remove(it) }
                Timber.d("Trimmed %d entries from subtitle cache", keysToRemove.size)
            }
        }
    }
}
