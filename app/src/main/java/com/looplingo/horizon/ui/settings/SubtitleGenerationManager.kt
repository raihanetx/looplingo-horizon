package com.looplingo.horizon.ui.settings

import android.content.Context
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.looplingo.horizon.BuildConfig
import com.looplingo.horizon.R
import com.looplingo.horizon.data.remote.GroqApiClient
import com.looplingo.horizon.data.remote.ProgressCallback
import com.looplingo.horizon.data.remote.Segment
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.domain.model.SubtitleCue
import com.looplingo.horizon.core.SecurePrefs
import com.looplingo.horizon.ui.settings.PlaybackUIHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private fun Fragment.safeRequireView(): View? {
    return if (isAdded) view else null
}

@Singleton
class SubtitleGenerationManager @Inject constructor() {

    internal fun triggerSubtitleGeneration(
        fragment: Fragment,
        binding: FragmentPlaybackSettingsBinding,
        viewModel: PlaybackSettingsViewModel,
        groqApiClient: GroqApiClient,
        playbackUIHelper: PlaybackUIHelper,
        videoPath: String,
        contentUri: String,
        onStart: () -> Unit,
        onSuccess: (segments: List<Segment>, translatedTexts: Map<Int, String>) -> Unit,
        onError: () -> Unit,
        showDialogueList: (List<Segment>) -> Unit,
        switchTab: (Int) -> Unit,
        getIsGenerating: () -> Boolean
    ) {
        if (getIsGenerating()) return

        val apiKey = getGroqApiKey(fragment.requireContext())
        if (apiKey.isBlank()) {
            fragment.safeRequireView()?.let {
                playbackUIHelper.showSnackbar(it, fragment.getString(R.string.error_set_api_key_homepage))
            }
            return
        }

        onStart()
        binding.ivSendSubtitles.visibility = View.GONE
        showProgressOverlay(binding, "Preparing...", 0, "")
        fragment.safeRequireView()?.let {
            playbackUIHelper.showSnackbar(it, fragment.getString(R.string.subtitle_generating))
        }

        val effectivePath = if (contentUri.isNotBlank()) contentUri else videoPath

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    viewModel.clearTranscriptionCache(videoPath)
                }

                val result = withContext(Dispatchers.IO) {
                    groqApiClient.transcribeAndTranslate(
                        fragment.requireContext(), apiKey, effectivePath,
                        language = "en",
                        targetLanguage = "bn",
                        onProgress = object : ProgressCallback {
                            override fun onProgress(step: String) {
                                Timber.d("Subtitle progress: %s", step)
                            }

                            override fun onProgressUpdate(step: String, percent: Int, detail: String) {
                                fragment.activity?.runOnUiThread {
                                    updateProgressOverlay(binding, step, percent, detail)
                                }
                            }
                        }
                    )
                }

                if (!fragment.isAdded) return@launch

                if (result.segments.isEmpty()) {
                    hideProgressOverlay(binding)
                    fragment.safeRequireView()?.let {
                        playbackUIHelper.showSnackbar(
                            it,
                            "ERROR: No speech detected (segments=0). Check: 1) audio track exists  2) volume not too low  3) try a different file"
                        )
                    }
                    onError()
                    binding.ivSendSubtitles.visibility = View.VISIBLE
                    return@launch
                }

                if (result.translatedTexts.isEmpty()) {
                    Timber.w("WARNING: Translation returned 0 results for %d segments. Chat API may have failed.", result.segments.size)
                    fragment.safeRequireView()?.let {
                        playbackUIHelper.showSnackbar(
                            it,
                            "WARNING: English transcription OK (${result.segments.size} segments), but Bangla translation failed. Showing English only. Check Logcat for details."
                        )
                    }
                } else {
                    Timber.i("SUCCESS: Translation returned %d/%d results", result.translatedTexts.size, result.segments.size)
                    for ((segId, trans) in result.translatedTexts.entries.take(5)) {
                        Timber.i("  Sample[%d]: \"%s\"", segId, trans.take(100))
                    }
                }

                onSuccess(result.segments, result.translatedTexts)
                binding.ivSendSubtitles.visibility = View.GONE
                hideProgressOverlay(binding)

                viewModel.saveTranscription(
                    videoPath = videoPath,
                    segments = result.segments,
                    languageCode = "en",
                    isTranslation = false,
                    translatedTexts = result.translatedTexts,
                    translationLanguage = "bn"
                )

                showDialogueList(result.segments)
                val banglaCount = result.translatedTexts.size
                fragment.safeRequireView()?.let {
                    playbackUIHelper.showSnackbar(
                        it,
                        "${result.segments.size} segments, $banglaCount Bangla translations"
                    )
                }
                switchTab(PlaybackSettingsViewModel.TAB_TALK)
            } catch (e: Exception) {
                Timber.e(e, "Subtitle generation failed")
                hideProgressOverlay(binding)
                onError()
                binding.ivSendSubtitles.visibility = View.VISIBLE
                val summary = buildString {
                    append("FAILED: ")
                    append(e.javaClass.name.substringAfterLast('.'))
                    append(": ")
                    append(e.message?.take(500)?.trim() ?: "no message")
                }
                val fullDetail = buildString {
                    appendLine(summary)
                    appendLine()
                    appendLine("--- Full Stack Trace ---")
                    appendLine(e.stackTraceToString())
                    e.cause?.let {
                        appendLine()
                        appendLine("--- Caused by ---")
                        appendLine(it.stackTraceToString())
                    }
                }
                fragment.safeRequireView()?.let {
                    playbackUIHelper.showSnackbar(it, summary, fullErrorDetail = fullDetail)
                }
            }
        }
    }

    internal fun tryAutoLoadCachedSubtitles(
        fragment: Fragment,
        viewModel: PlaybackSettingsViewModel,
        videoPath: String,
        onCuesLoaded: (List<SubtitleCue>) -> Unit
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cachedData = withContext(Dispatchers.IO) {
                    viewModel.getTranscriptionCuesWithMeta(videoPath)
                }
                if (cachedData.cues.isNotEmpty()) {
                    onCuesLoaded(cachedData.cues)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to auto-load cached subtitles")
            }
        }
    }

    internal fun loadSubtitleCues(
        cues: List<SubtitleCue>,
        showDialogueList: (List<Segment>, Map<Int, String>) -> Unit
    ): Pair<List<Segment>, Map<Int, String>> {
        val dialogueSegments = cues.mapIndexed { index, cue ->
            Segment(
                id = index,
                text = cue.text.substringBefore("\n\u2192"),
                startSec = cue.startMs / 1000.0,
                endSec = cue.endMs / 1000.0
            )
        }
        val translatedTexts = cues.mapIndexedNotNull { index, cue ->
            val translationLine = cue.text.substringAfter("\n\u2192 ", "")
            if (translationLine.isNotEmpty() && dialogueSegments.getOrNull(index) != null) {
                dialogueSegments[index].id to translationLine
            } else null
        }.toMap()
        showDialogueList(dialogueSegments, translatedTexts)
        return Pair(dialogueSegments, translatedTexts)
    }

    internal fun getGroqApiKey(context: Context): String {
        val prefs = SecurePrefs.get(context)
        val savedKey = prefs.getString("groq_api_key", "") ?: ""
        if (savedKey.isNotBlank()) return savedKey
        return BuildConfig.GROQ_API_KEY
    }

    private fun showProgressOverlay(binding: FragmentPlaybackSettingsBinding, step: String, percent: Int, detail: String) {
        binding.layoutProgressOverlay.visibility = View.VISIBLE
        binding.tvProgressStep.text = step
        binding.progressBarGeneration.progress = percent
        binding.tvProgressDetail.text = detail
        binding.tvProgressPercent.text = "$percent%"
    }

    private fun updateProgressOverlay(binding: FragmentPlaybackSettingsBinding, step: String, percent: Int, detail: String) {
        binding.layoutProgressOverlay.visibility = View.VISIBLE
        binding.tvProgressStep.text = step
        binding.progressBarGeneration.progress = percent
        binding.tvProgressDetail.text = detail
        binding.tvProgressPercent.text = "$percent%"
    }

    private fun hideProgressOverlay(binding: FragmentPlaybackSettingsBinding) {
        binding.layoutProgressOverlay.visibility = View.GONE
    }
}
