package com.looplingo.horizon.ui.settings

import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
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
    private var loopAdapter: LoopAdapter? = null
    private var noteAdapter: NoteAdapter? = null

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
                updateInfoLine(binding, viewModel)
            },
            onSubtitleClick = {
                if (!isGeneratingSubtitles) {
                    subtitleGenerated = false
                    triggerSubtitles(fragment, binding, viewModel, videoPath, contentUri)
                }
            },
            subtitleGenerated = subtitleGenerated
        )
        playbackUIHelper.setupTabNavigation(binding) { tab ->
            switchTab(binding, viewModel, tab)
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

        // Setup loop form
        setupLoopForm(fragment, binding, viewModel, activity, videoPath)

        // Setup note form
        setupNoteForm(fragment, binding, activity, videoPath)

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
            updateInfoLine(binding, viewModel)
        }
        autoLoadCachedSubtitles(fragment, binding, viewModel, videoPath)
        switchTab(binding, viewModel, viewModel.currentTab.value)
        updateInfoLine(binding, viewModel)
    }

    private fun setupLoopForm(
        fragment: Fragment,
        binding: FragmentPlaybackSettingsBinding,
        viewModel: PlaybackSettingsViewModel,
        activity: android.app.Activity,
        videoPath: String
    ) {
        // Setup loop RecyclerView
        loopAdapter = LoopAdapter(
            onLoopClick = { loop, _ ->
                // Apply loop settings
                binding.etLoopStart.setText(TimeUtils.formatMsToTime(loop.startMs))
                binding.etLoopEnd.setText(TimeUtils.formatMsToTime(loop.endMs))
                loopCount = loop.loopCount
                binding.tvLoopFormCount.text = loopCount.toString()
                binding.etLoopName.setText(loop.name)
                playbackUIHelper.showLoopForm(binding)
            },
            onDeleteClick = { _, position ->
                loopAdapter?.removeLoop(position)
                playbackUIHelper.updateLoopEmptyState(binding, loopAdapter?.getLoops()?.isNotEmpty() == true)
            }
        )
        binding.rvLoopList.layoutManager = LinearLayoutManager(fragment.requireContext())
        binding.rvLoopList.adapter = loopAdapter

        // Setup loop form controls
        playbackUIHelper.setupLoopForm(binding,
            onPreview = {
                val startMs = TimeUtils.parseTimeToMs(binding.etLoopStart.text.toString())
                val endMs = TimeUtils.parseTimeToMs(binding.etLoopEnd.text.toString())
                if (startMs >= 0 && endMs > startMs) {
                    // Preview the loop
                    AudioPlaybackService.seekToPosition(activity, videoPath, startMs)
                    playbackUIHelper.showSnackbar(binding.root, "Previewing loop: ${TimeUtils.formatMsToTime(startMs)} - ${TimeUtils.formatMsToTime(endMs)}")
                } else {
                    playbackUIHelper.showSnackbar(binding.root, "Please enter valid start and end times")
                }
            },
            onSave = {
                val name = binding.etLoopName.text.toString().trim()
                val startMs = TimeUtils.parseTimeToMs(binding.etLoopStart.text.toString())
                val endMs = TimeUtils.parseTimeToMs(binding.etLoopEnd.text.toString())

                if (name.isEmpty()) {
                    binding.tilLoopName.error = "Please enter a name"
                    return@setupLoopForm
                }
                binding.tilLoopName.error = null

                if (startMs < 0) {
                    binding.tilLoopStart.error = "Invalid start time"
                    return@setupLoopForm
                }
                binding.tilLoopStart.error = null

                if (endMs <= startMs) {
                    binding.tilLoopEnd.error = "End must be after start"
                    return@setupLoopForm
                }
                binding.tilLoopEnd.error = null

                val loop = SavedLoop(name, startMs, endMs, loopCount)
                loopAdapter?.addLoop(loop)
                playbackUIHelper.updateLoopEmptyState(binding, true)

                // Clear form and close
                binding.etLoopName.setText("")
                binding.etLoopStart.setText("0:00")
                binding.etLoopEnd.setText("")
                playbackUIHelper.hideLoopForm(binding)
                playbackUIHelper.showSnackbar(binding.root, "Loop saved: $name")
            },
            onClose = {
                binding.etLoopName.setText("")
                binding.etLoopStart.setText("0:00")
                binding.etLoopEnd.setText("")
                binding.tilLoopName.error = null
                binding.tilLoopStart.error = null
                binding.tilLoopEnd.error = null
            }
        )

        // Setup loop count controls in form
        binding.btnLoopFormMinus.setOnClickListener {
            if (loopCount > 1) {
                loopCount--
                binding.tvLoopFormCount.text = loopCount.toString()
                updateInfoLine(binding, viewModel)
            }
        }
        binding.btnLoopFormPlus.setOnClickListener {
            if (loopCount < 10000) {
                loopCount++
                binding.tvLoopFormCount.text = loopCount.toString()
                updateInfoLine(binding, viewModel)
            }
        }

        // Initialize empty state
        playbackUIHelper.updateLoopEmptyState(binding, false)
    }

    private fun setupNoteForm(
        fragment: Fragment,
        binding: FragmentPlaybackSettingsBinding,
        activity: android.app.Activity,
        videoPath: String
    ) {
        // Setup notes RecyclerView
        noteAdapter = NoteAdapter(
            onNoteClick = { note, _ ->
                // Seek to the note's timestamp
                AudioPlaybackService.seekToPosition(activity, videoPath, note.timestampMs)
            },
            onDeleteClick = { _, position ->
                noteAdapter?.removeNote(position)
                playbackUIHelper.updateNoteEmptyState(binding, noteAdapter?.getNotes()?.isNotEmpty() == true)
            }
        )
        binding.rvNotesList.layoutManager = LinearLayoutManager(fragment.requireContext())
        binding.rvNotesList.adapter = noteAdapter

        // Setup note form controls
        playbackUIHelper.setupNoteForm(binding,
            onSave = {
                val text = binding.etNoteText.text.toString().trim()
                if (text.isEmpty()) {
                    binding.tilNoteText.error = "Please enter a note"
                    return@setupNoteForm
                }
                binding.tilNoteText.error = null

                val currentPosMs = AudioPlaybackService.currentPositionMs
                val note = SavedNote(text, currentPosMs)
                noteAdapter?.addNote(note)
                playbackUIHelper.updateNoteEmptyState(binding, true)

                // Clear form and close
                binding.etNoteText.setText("")
                playbackUIHelper.hideNoteForm(binding)
                playbackUIHelper.showSnackbar(binding.root, "Note saved at ${TimeUtils.formatMsToTime(currentPosMs)}")
            },
            onClose = {
                binding.etNoteText.setText("")
                binding.tilNoteText.error = null
            }
        )

        // Initialize empty state
        playbackUIHelper.updateNoteEmptyState(binding, false)
    }

    private fun switchTab(binding: FragmentPlaybackSettingsBinding, viewModel: PlaybackSettingsViewModel, tab: Int) {
        configSetupManager.switchTab(binding, playbackUIHelper, tab, viewModel) {
            if (tab == PlaybackSettingsViewModel.TAB_CLEAN) { isCleanCycling = false; cleanCycleIndex = -1 }
        }
        updateInfoLine(binding, viewModel)
    }

    private fun updateInfoLine(binding: FragmentPlaybackSettingsBinding, viewModel: PlaybackSettingsViewModel) {
        val tabName = when (viewModel.currentTab.value) {
            PlaybackSettingsViewModel.TAB_CLEAN -> "Clean"
            PlaybackSettingsViewModel.TAB_TALK -> "Talk"
            PlaybackSettingsViewModel.TAB_LOOP -> "Loop"
            PlaybackSettingsViewModel.TAB_NOTES -> "Notes"
            else -> "Clean"
        }
        val speedLabel = SpeedPresets.ALL.getOrNull(currentSpeedIndex)?.label ?: SpeedPresets.DEFAULT.label
        val isInLoopMode = viewModel.currentTab.value == PlaybackSettingsViewModel.TAB_LOOP
        playbackUIHelper.updatePlayerInfoLine(binding, tabName, loopCount, speedLabel, isInLoopMode)
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
