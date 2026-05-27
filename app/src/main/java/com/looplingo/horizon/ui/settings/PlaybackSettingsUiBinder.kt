package com.looplingo.horizon.ui.settings

import android.view.View
import androidx.fragment.app.Fragment
import com.looplingo.horizon.R
import com.looplingo.horizon.data.remote.GroqApiClient
import com.looplingo.horizon.data.remote.Segment
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.domain.model.SpeedPresets
import com.looplingo.horizon.domain.audio.service.AudioPlaybackService
import com.looplingo.horizon.core.TimeUtils
import com.looplingo.horizon.ui.settings.PlaybackUIHelper
import com.looplingo.horizon.ui.settings.PositionPollingManager
import com.looplingo.horizon.ui.settings.DialogueAdapter
import javax.inject.Inject

class PlaybackSettingsUiBinder @Inject constructor(
    private val groqApiClient: GroqApiClient,
    private val playbackUIHelper: PlaybackUIHelper,
    private val subtitleGenerationManager: SubtitleGenerationManager,
    private val configSetupManager: ConfigSetupManager,
    private val tryLoopSetup: TryLoopSetup,
    private val dialogueInteractionHandler: DialogueInteractionHandler
) {
    private var dialogueSegments: List<Segment> = emptyList()
    private var translatedTexts: Map<Int, String> = emptyMap()
    private var selectedSegmentIndex: Int = -1
    private var subtitleGenerated: Boolean = false
    private var isGeneratingSubtitles: Boolean = false
    private var currentSpeedIndex: Int = SpeedPresets.ALL.indexOf(SpeedPresets.DEFAULT)
    private var loopCount: Int = 3
    private var dialogueAdapter: DialogueAdapter? = null
    private var cleanCycleIndex: Int = -1
    private var isCleanCycling: Boolean = false
    private var positionPollingManager: PositionPollingManager? = null

    fun bind(
        fragment: Fragment,
        binding: FragmentPlaybackSettingsBinding,
        viewModel: PlaybackSettingsViewModel,
        videoPath: String,
        contentUri: String,
        requestNotificationPermission: () -> Unit,
        navigateUp: () -> Unit
    ) {
        val activity = fragment.requireActivity()
        playbackUIHelper.setupHeader(binding,
            onBackClick = navigateUp,
            onSpeedClick = {
                currentSpeedIndex = (currentSpeedIndex + 1) % SpeedPresets.ALL.size
                val preset = SpeedPresets.ALL[currentSpeedIndex]
                binding.tvHeaderSpeed.text = preset.label
                AudioPlaybackService.setSpeed(activity, preset.speed)
            },
            onSubtitleClick = {
                if (!isGeneratingSubtitles && !subtitleGenerated) {
                    triggerSubtitles(fragment, binding, viewModel, videoPath, contentUri)
                }
            },
            subtitleGenerated = subtitleGenerated
        )
        playbackUIHelper.setupTabNavigation(binding) { tab ->
            if (tab == PlaybackSettingsViewModel.TAB_NOTES) {
                switchTab(binding, viewModel, tab)
                binding.layoutAddNote.visibility = View.VISIBLE
            } else {
                switchTab(binding, viewModel, tab)
            }
        }
        configSetupManager.setupCleanTab(binding, dialogueSegments) {
            isCleanCycling = true
            cleanCycleIndex = (cleanCycleIndex + 1) % dialogueSegments.size
            configSetupManager.showDialogueOnClean(binding, dialogueSegments, translatedTexts, cleanCycleIndex)
            val segment = dialogueSegments[cleanCycleIndex]
            if (AudioPlaybackService.isPlaying && AudioPlaybackService.currentVideoPath == videoPath) {
                AudioPlaybackService.seekToPosition(activity, videoPath, segment.startMs)
            }
        }
        playbackUIHelper.setupTransportControls(binding,
            onPlayPause = {
                val isPlaying = AudioPlaybackService.isPlaying && AudioPlaybackService.currentVideoPath == videoPath
                if (isPlaying) AudioPlaybackService.togglePlayback(activity)
                else {
                    requestNotificationPermission()
                    AudioPlaybackService.startService(activity, videoPath)
                }
            },
            onRewind = { AudioPlaybackService.seekBackward(activity, 5000L) },
            onForward = { AudioPlaybackService.seekForward(activity, 5000L) }
        )
        playbackUIHelper.setupSeekBar(binding) { newPos ->
            val currentPath = AudioPlaybackService.currentVideoPath
            if (currentPath.isNotBlank()) AudioPlaybackService.seekToPosition(activity, currentPath, newPos)
        }
        playbackUIHelper.setupLoopControls(binding, loopCount,
            onLoopMinus = { count -> if (count > 1) { loopCount = count - 1; binding.tvLoopCount.text = loopCount.toString() }},
            onLoopPlus = { count -> if (count < 10000) { loopCount = count + 1; binding.tvLoopCount.text = loopCount.toString() }}
        )
        tryLoopSetup.setupTryLoopButton(binding, playbackUIHelper, videoPath,
            getLoopCount = { loopCount }, parseTimeToMs = TimeUtils::parseTimeToMs, onError = {})
        configSetupManager.setupApplyButton(binding, viewModel, playbackUIHelper, fragment,
            getLoopCount = { loopCount },
            getCurrentSpeed = { SpeedPresets.ALL.getOrNull(currentSpeedIndex)?.speed ?: SpeedPresets.DEFAULT.speed },
            parseTimeToMs = TimeUtils::parseTimeToMs, onSuccess = {})
        configSetupManager.setupClearButton(binding, viewModel) {
            loopCount = 3; currentSpeedIndex = SpeedPresets.ALL.indexOf(SpeedPresets.DEFAULT)
            binding.tvLoopCount.text = loopCount.toString(); binding.tvHeaderSpeed.text = SpeedPresets.DEFAULT.label
        }
        val title = videoPath.substringAfterLast("/").substringBeforeLast(".")
        playbackUIHelper.setupNowPlayingCard(binding, title, fragment.getString(R.string.clean_view_subtitle))

        positionPollingManager = PositionPollingManager(binding, playbackUIHelper, videoPath,
            getDialogueSegments = { dialogueSegments }, isCleanCycling = { isCleanCycling },
            showDialogueOnClean = { configSetupManager.showDialogueOnClean(binding, dialogueSegments, translatedTexts, it) },
            resetCleanView = {
                binding.ivCleanIcon.visibility = View.VISIBLE; binding.tvCleanTitle.visibility = View.VISIBLE
                binding.tvCleanSubtitle.visibility = View.VISIBLE
                binding.tvCleanEnglish.visibility = View.GONE; binding.tvCleanBangla.visibility = View.GONE
            },
            onActiveSegmentChanged = { segIndex ->
                dialogueAdapter?.setActivePosition(segIndex)
                if (segIndex >= 0) {
                    binding.rvDialogueList.smoothScrollToPosition(segIndex)
                }
            }
        )
        positionPollingManager?.startPositionPolling()
        configSetupManager.setupObservers(fragment, binding, viewModel, playbackUIHelper) { lc, si ->
            loopCount = lc; if (si >= 0) currentSpeedIndex = si
        }
        autoLoadCachedSubtitles(fragment, binding, viewModel, videoPath)
        switchTab(binding, viewModel, viewModel.currentTab.value)
    }

    private fun switchTab(binding: FragmentPlaybackSettingsBinding, viewModel: PlaybackSettingsViewModel, tab: Int) {
        configSetupManager.switchTab(binding, playbackUIHelper, tab, viewModel) {
            if (tab == PlaybackSettingsViewModel.TAB_CLEAN) { isCleanCycling = false; cleanCycleIndex = -1 }
        }
    }

    private fun triggerSubtitles(fragment: Fragment, binding: FragmentPlaybackSettingsBinding, viewModel: PlaybackSettingsViewModel, videoPath: String, contentUri: String) {
        subtitleGenerationManager.triggerSubtitleGeneration(fragment, binding, viewModel, groqApiClient, playbackUIHelper, videoPath, contentUri,
            onStart = { isGeneratingSubtitles = true },
            onSuccess = { segs, texts ->
                dialogueSegments = segs; translatedTexts = texts; selectedSegmentIndex = -1; subtitleGenerated = true; isGeneratingSubtitles = false
            },
            onError = { isGeneratingSubtitles = false; subtitleGenerated = false },
            showDialogueList = { segs ->
                dialogueAdapter = dialogueInteractionHandler.showDialogueList(binding, segs, translatedTexts) { segment, _ ->
                    dialogueInteractionHandler.onDialogueSegmentSelected(binding, playbackUIHelper, videoPath, segment)
                }
            },
            switchTab = { switchTab(binding, viewModel, it) },
            getIsGenerating = { isGeneratingSubtitles }
        )
    }

    private fun autoLoadCachedSubtitles(fragment: Fragment, binding: FragmentPlaybackSettingsBinding, viewModel: PlaybackSettingsViewModel, videoPath: String) {
        subtitleGenerationManager.tryAutoLoadCachedSubtitles(fragment, viewModel, videoPath) { cues ->
            val (segs, texts) = subtitleGenerationManager.loadSubtitleCues(cues) { segs, texts ->
                dialogueAdapter = dialogueInteractionHandler.showDialogueList(binding, segs, texts) { segment, _ ->
                    dialogueInteractionHandler.onDialogueSegmentSelected(binding, playbackUIHelper, videoPath, segment)
                }
            }
            dialogueSegments = segs; translatedTexts = texts; selectedSegmentIndex = -1; subtitleGenerated = true
            binding.ivSendSubtitles.visibility = View.GONE
        }
    }

    fun onResume() { positionPollingManager?.startPositionPolling() }
    fun onPause() { positionPollingManager?.stopPositionPolling() }
    fun onDestroy() { positionPollingManager?.stopPositionPolling(); positionPollingManager = null }
}
