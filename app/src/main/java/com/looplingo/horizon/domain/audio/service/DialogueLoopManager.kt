package com.looplingo.horizon.domain.audio.service

import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import com.looplingo.horizon.domain.model.PlaybackConfig
import com.looplingo.horizon.domain.model.SubtitleCue
import org.json.JSONArray
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DialogueLoopManager @Inject constructor() {

    private val abHandler = Handler(Looper.getMainLooper())

    private var dialogueAutoLoopCues: List<SubtitleCue> = emptyList()
    private var dialogueAutoLoopAllCues: List<SubtitleCue> = emptyList()
    private var dialogueAutoLoopCount: Int = 3
    private var dialogueAutoLoopPauseMs: Long = 1000L
    private var dialogueAutoLoopCurrentIndex: Int = 0
    private var dialogueAutoLoopCurrentIteration: Int = 0
    var isDialogueAutoLoopActive: Boolean = false
        private set
    var isDialoguePauseActive: Boolean = false
        private set

    companion object {
        private const val CUE_BOUNDARY_GAP_MS = 80L
    }

    fun handleDialogueAutoLoop(
        videoPath: String, cuesJson: String, loopCount: Int,
        pauseMs: Long, selectedIndices: IntArray?,
        getConfig: () -> PlaybackConfig,
        setConfig: (PlaybackConfig) -> Unit,
        getExoPlayer: () -> ExoPlayer?,
        scheduleAbCheck: () -> Unit,
        updateNotification: () -> Unit
    ) {
        if (videoPath != getConfig().videoPath) return

        try {
            val jsonArray = JSONArray(cuesJson)
            val allCues = mutableListOf<SubtitleCue>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                allCues.add(SubtitleCue(
                    index = obj.getInt("index"),
                    startMs = obj.getLong("startMs"),
                    endMs = obj.getLong("endMs"),
                    text = obj.getString("text")
                ))
            }
            if (allCues.isEmpty()) { Timber.w("Dialogue auto-loop: no cues provided"); return }

            val cues = if (selectedIndices != null && selectedIndices.isNotEmpty()) {
                val indexSet = selectedIndices.toSet()
                allCues.filterIndexed { idx, _ -> idx in indexSet }
            } else { allCues }
            if (cues.isEmpty()) { Timber.w("Dialogue auto-loop: no cues after filtering"); return }

            dialogueAutoLoopCues = cues
            dialogueAutoLoopAllCues = allCues
            dialogueAutoLoopCount = loopCount.coerceAtLeast(1)
            dialogueAutoLoopPauseMs = pauseMs.coerceIn(200L, 5000L)
            dialogueAutoLoopCurrentIndex = 0
            dialogueAutoLoopCurrentIteration = 0
            isDialogueAutoLoopActive = true
            isDialoguePauseActive = false

            val firstCue = cues[0]
            val safeEnd = calculateSafeEndMs(firstCue, allCues)
            setConfig(getConfig().copy(rangeStartMs = firstCue.startMs, rangeEndMs = safeEnd, loopCount = loopCount))
            getExoPlayer()?.seekTo(firstCue.startMs)
            getExoPlayer()?.play()
            scheduleAbCheck()
            Timber.i("Dialogue auto-loop started: %d cues, x%d each, %dms pause", cues.size, loopCount, pauseMs)
            updateNotification()
        } catch (e: Exception) {
            Timber.e(e, "Failed to set up dialogue auto-loop")
        }
    }

    fun calculateSafeEndMs(cue: SubtitleCue, allCues: List<SubtitleCue>): Long {
        val nextCue = allCues.filter { it.startMs > cue.startMs }.minByOrNull { it.startMs }
        val safeEnd = if (nextCue != null) {
            minOf(cue.endMs, nextCue.startMs - CUE_BOUNDARY_GAP_MS)
        } else {
            cue.endMs
        }
        return safeEnd.coerceAtLeast(cue.startMs + 200L)
    }

    fun advanceToNextDialogueCue(
        getExoPlayer: () -> ExoPlayer?,
        getConfig: () -> PlaybackConfig,
        setConfig: (PlaybackConfig) -> Unit,
        setCurrentLoopIteration: (Int) -> Unit,
        setABLoopCompleted: (Boolean) -> Unit,
        scheduleAbCheck: () -> Unit,
        cancelAbMonitor: () -> Unit,
        setABSeeking: (Boolean) -> Unit,
        updateNotification: () -> Unit
    ) {
        if (!isDialogueAutoLoopActive || dialogueAutoLoopCues.isEmpty()) return

        dialogueAutoLoopCurrentIndex++
        if (dialogueAutoLoopCurrentIndex >= dialogueAutoLoopCues.size) {
            isDialogueAutoLoopActive = false
            cancelAbMonitor()
            Timber.i("Dialogue auto-loop completed: all %d cues done", dialogueAutoLoopCues.size)
            return
        }

        val nextCue = dialogueAutoLoopCues[dialogueAutoLoopCurrentIndex]
        val safeEnd = calculateSafeEndMs(nextCue, dialogueAutoLoopAllCues)
        setConfig(getConfig().copy(rangeStartMs = nextCue.startMs, rangeEndMs = safeEnd, loopCount = dialogueAutoLoopCount))
        setCurrentLoopIteration(0)
        setABLoopCompleted(false)

        if (dialogueAutoLoopPauseMs > 0) {
            isDialoguePauseActive = true
            abHandler.postDelayed({
                try {
                    isDialoguePauseActive = false
                    setABSeeking(true)
                    getExoPlayer()?.seekTo(nextCue.startMs)
                    getExoPlayer()?.play()
                    scheduleAbCheck()
                    Timber.d("Dialogue auto-loop: advancing to cue %d/%d", dialogueAutoLoopCurrentIndex + 1, dialogueAutoLoopCues.size)
                } catch (e: Exception) {
                    setABSeeking(false)
                    isDialoguePauseActive = false
                    Timber.e(e, "Failed to advance after pause")
                }
            }, dialogueAutoLoopPauseMs)
        } else {
            setABSeeking(true)
            getExoPlayer()?.seekTo(nextCue.startMs)
            getExoPlayer()?.play()
            scheduleAbCheck()
            Timber.d("Dialogue auto-loop: advancing to cue %d/%d", dialogueAutoLoopCurrentIndex + 1, dialogueAutoLoopCues.size)
        }
    }
}
