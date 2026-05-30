package com.looplingo.horizon.ui.settings

import android.content.Context
import android.content.SharedPreferences
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
import org.json.JSONArray
import org.json.JSONObject
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
    private var editingNotePosition: Int = -1
    private var editingLoopPosition: Int = -1
    private var isAudioOnly: Boolean = false

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
        
        // Initial state: hide logo, show track name
        binding.ivCleanIcon.visibility = View.GONE
        binding.tvCleanTitle.visibility = View.VISIBLE
        
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
            onAudioModeClick = {
                isAudioOnly = !isAudioOnly
                playbackUIHelper.updateAudioMode(binding, isAudioOnly)
                updateInfoLine(binding, viewModel)
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

        // Setup + buttons for loop/note dialogs
        setupAddButtons(fragment, binding, activity, videoPath)

        // Setup play selected button
        binding.btnPlaySelected.setOnClickListener {
            dialogueInteractionHandler.playSelectedDialogues(binding, videoPath, dialogueAdapter)
        }

        val title = videoPath.substringAfterLast("/").substringBeforeLast(".")
        playbackUIHelper.setupNowPlayingCard(binding, title, fragment.getString(R.string.clean_view_subtitle))

        positionPollingManager = PositionPollingManager(binding, playbackUIHelper, videoPath,
            getDialogueSegments = { dialogueSegments }, isCleanCycling = { isCleanCycling },
            isAudioOnly = { isAudioOnly },
            showDialogueOnClean = { configSetupManager.showDialogueOnClean(binding, dialogueSegments, translatedTexts, it) },
            resetCleanView = {
                binding.ivCleanIcon.visibility = View.VISIBLE; binding.tvCleanTitle.visibility = View.VISIBLE
                binding.tvCleanEnglish.visibility = View.GONE; binding.tvCleanBangla.visibility = View.GONE
                binding.layoutProcessing.visibility = View.GONE
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
        val prefs = fragment.requireContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val savedLoops = loadLoops(prefs, videoPath)

        // Setup loop RecyclerView
        loopAdapter = LoopAdapter(
            onPlayClick = { loop, _ ->
                AudioPlaybackService.seekToPosition(activity, videoPath, loop.startMs)
                playbackUIHelper.showSnackbar(binding.root, "Playing: ${loop.name}")
            },
            onDeleteClick = { _, position ->
                loopAdapter?.removeLoop(position)
                val hasLoops = loopAdapter?.getLoops()?.isNotEmpty() == true
                playbackUIHelper.updateLoopEmptyState(binding, hasLoops)
                saveLoops(prefs, videoPath, loopAdapter?.getLoops() ?: emptyList())
            }
        )
        loopAdapter?.setLoops(savedLoops)
        binding.rvLoopList.layoutManager = LinearLayoutManager(fragment.requireContext())
        binding.rvLoopList.adapter = loopAdapter

        // Setup loop form controls
        playbackUIHelper.setupLoopForm(binding,
            onPreview = {
                val startMs = TimeUtils.parseTimeToMs(binding.etLoopStart.text.toString())
                val endMs = TimeUtils.parseTimeToMs(binding.etLoopEnd.text.toString())
                if (startMs >= 0 && endMs > startMs) {
                    AudioPlaybackService.seekToPosition(activity, videoPath, startMs)
                    playbackUIHelper.showSnackbar(binding.root, "Preview: ${TimeUtils.formatMsToTime(startMs)} — ${TimeUtils.formatMsToTime(endMs)}")
                } else {
                    playbackUIHelper.showSnackbar(binding.root, "Enter valid start and end times")
                }
            },
            onSave = {
                val name = binding.etLoopName.text.toString().trim()
                val startMs = TimeUtils.parseTimeToMs(binding.etLoopStart.text.toString())
                val endMs = TimeUtils.parseTimeToMs(binding.etLoopEnd.text.toString())
                val countText = binding.etLoopCount.text.toString().trim()
                val count = countText.toIntOrNull() ?: 3

                if (name.isEmpty()) {
                    binding.etLoopName.error = "Please enter a name"
                    return@setupLoopForm
                }
                binding.etLoopName.error = null

                if (startMs < 0) {
                    binding.etLoopStart.error = "Invalid start time"
                    return@setupLoopForm
                }
                binding.etLoopStart.error = null

                if (endMs <= startMs) {
                    binding.etLoopEnd.error = "End must be after start"
                    return@setupLoopForm
                }
                binding.etLoopEnd.error = null

                val validCount = count.coerceIn(1, 10000)

                if (editingLoopPosition >= 0) {
                    loopAdapter?.updateLoop(editingLoopPosition, name, startMs, endMs, validCount)
                    editingLoopPosition = -1
                } else {
                    val loop = SavedLoop(name, startMs, endMs, validCount)
                    loopAdapter?.addLoop(loop)
                }
                val hasLoops = loopAdapter?.getLoops()?.isNotEmpty() == true
                playbackUIHelper.updateLoopEmptyState(binding, hasLoops)
                saveLoops(prefs, videoPath, loopAdapter?.getLoops() ?: emptyList())

                playbackUIHelper.clearLoopForm(binding)
                playbackUIHelper.showSnackbar(binding.root, "Loop saved: $name")
            }
        )

        playbackUIHelper.updateLoopEmptyState(binding, savedLoops.isNotEmpty())
    }

    private fun setupNoteForm(
        fragment: Fragment,
        binding: FragmentPlaybackSettingsBinding,
        activity: android.app.Activity,
        videoPath: String
    ) {
        val prefs = fragment.requireContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val savedNotes = loadNotes(prefs, videoPath)

        // Setup notes RecyclerView
        noteAdapter = NoteAdapter(
            onNoteClick = { note, _ ->
                AudioPlaybackService.seekToPosition(activity, videoPath, note.timestampMs)
            },
            onDeleteClick = { _, position ->
                noteAdapter?.removeNote(position)
                val hasNotes = noteAdapter?.getNotes()?.isNotEmpty() == true
                playbackUIHelper.updateNoteEmptyState(binding, hasNotes)
                saveNotes(prefs, videoPath, noteAdapter?.getNotes() ?: emptyList())
            },
            onEditClick = { note, position ->
                editingNotePosition = position
                binding.etNoteText.setText(note.text)
                binding.etNoteText.requestFocus()
            }
        )
        noteAdapter?.setNotes(savedNotes)
        binding.rvNotesList.layoutManager = LinearLayoutManager(fragment.requireContext())
        binding.rvNotesList.adapter = noteAdapter

        // Setup note form controls
        playbackUIHelper.setupNoteForm(binding,
            onSave = {
                val text = binding.etNoteText.text.toString().trim()
                if (text.isEmpty()) return@setupNoteForm

                if (editingNotePosition >= 0) {
                    // Editing existing note
                    noteAdapter?.updateNote(editingNotePosition, text)
                    editingNotePosition = -1
                } else {
                    // Adding new note
                    val currentPosMs = AudioPlaybackService.currentPositionMs
                    val note = SavedNote(text, currentPosMs)
                    noteAdapter?.addNote(note)
                }
                val hasNotes = noteAdapter?.getNotes()?.isNotEmpty() == true
                playbackUIHelper.updateNoteEmptyState(binding, hasNotes)
                saveNotes(prefs, videoPath, noteAdapter?.getNotes() ?: emptyList())

                playbackUIHelper.clearNoteForm(binding)
            }
        )

        playbackUIHelper.updateNoteEmptyState(binding, savedNotes.isNotEmpty())
    }

    private fun setupAddButtons(
        fragment: Fragment,
        binding: FragmentPlaybackSettingsBinding,
        activity: android.app.Activity,
        videoPath: String
    ) {
        val prefs = fragment.requireContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

        // Add loop button
        playbackUIHelper.setupAddLoopButton(binding) {
            val currentTimeMs = AudioPlaybackService.currentPositionMs
            playbackUIHelper.showAddLoopDialog(
                context = fragment.requireContext(),
                currentTimeMs = currentTimeMs,
                onSave = { name, startMs, endMs, loopCount ->
                    val loop = SavedLoop(name, startMs, endMs, loopCount)
                    loopAdapter?.addLoop(loop)
                    val hasLoops = loopAdapter?.getLoops()?.isNotEmpty() == true
                    playbackUIHelper.updateLoopEmptyState(binding, hasLoops)
                    saveLoops(prefs, videoPath, loopAdapter?.getLoops() ?: emptyList())
                    playbackUIHelper.showSnackbar(binding.root, "Loop saved: $name")
                }
            )
        }

        // Add note button
        playbackUIHelper.setupAddNoteButton(binding) {
            val currentPosMs = AudioPlaybackService.currentPositionMs
            playbackUIHelper.showAddNoteDialog(
                context = fragment.requireContext(),
                onSave = { text ->
                    val note = SavedNote(text, currentPosMs)
                    noteAdapter?.addNote(note)
                    val hasNotes = noteAdapter?.getNotes()?.isNotEmpty() == true
                    playbackUIHelper.updateNoteEmptyState(binding, hasNotes)
                    saveNotes(prefs, videoPath, noteAdapter?.getNotes() ?: emptyList())
                    playbackUIHelper.showSnackbar(binding.root, "Note saved")
                }
            )
        }
    }

    companion object {
        private const val PREFS_FILE = "horizon_loop_notes"
        private const val KEY_LOOPS_PREFIX = "loops_"
        private const val KEY_NOTES_PREFIX = "notes_"

        private fun loadLoops(prefs: SharedPreferences, videoPath: String): List<SavedLoop> {
            val json = prefs.getString(KEY_LOOPS_PREFIX + videoPath, null) ?: return emptyList()
            val arr = JSONArray(json)
            val result = mutableListOf<SavedLoop>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(SavedLoop(
                    name = obj.getString("name"),
                    startMs = obj.getLong("startMs"),
                    endMs = obj.getLong("endMs"),
                    loopCount = obj.getInt("loopCount")
                ))
            }
            return result
        }

        private fun saveLoops(prefs: SharedPreferences, videoPath: String, loops: List<SavedLoop>) {
            val arr = JSONArray()
            loops.forEach { loop ->
                val obj = JSONObject()
                obj.put("name", loop.name)
                obj.put("startMs", loop.startMs)
                obj.put("endMs", loop.endMs)
                obj.put("loopCount", loop.loopCount)
                arr.put(obj)
            }
            prefs.edit().putString(KEY_LOOPS_PREFIX + videoPath, arr.toString()).apply()
        }

        private fun loadNotes(prefs: SharedPreferences, videoPath: String): List<SavedNote> {
            val json = prefs.getString(KEY_NOTES_PREFIX + videoPath, null) ?: return emptyList()
            val arr = JSONArray(json)
            val result = mutableListOf<SavedNote>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(SavedNote(
                    text = obj.getString("text"),
                    timestampMs = obj.getLong("timestampMs")
                ))
            }
            return result
        }

        private fun saveNotes(prefs: SharedPreferences, videoPath: String, notes: List<SavedNote>) {
            val arr = JSONArray()
            notes.forEach { note ->
                val obj = JSONObject()
                obj.put("text", note.text)
                obj.put("timestampMs", note.timestampMs)
                arr.put(obj)
            }
            prefs.edit().putString(KEY_NOTES_PREFIX + videoPath, arr.toString()).apply()
        }
    }

    private fun switchTab(binding: FragmentPlaybackSettingsBinding, viewModel: PlaybackSettingsViewModel, tab: Int) {
        configSetupManager.switchTab(binding, playbackUIHelper, tab, viewModel) {
            if (tab == PlaybackSettingsViewModel.TAB_CLEAN) { isCleanCycling = false; cleanCycleIndex = -1 }
        }
        if (tab == PlaybackSettingsViewModel.TAB_TALK) {
            playbackUIHelper.updateTalkEmptyState(binding, dialogueSegments.isNotEmpty())
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
        playbackUIHelper.updatePlayerInfoLine(binding, tabName, loopCount, speedLabel, isInLoopMode, isAudioOnly)
    }

    private fun triggerSubtitles(fragment: Fragment, binding: FragmentPlaybackSettingsBinding, viewModel: PlaybackSettingsViewModel, videoPath: String, contentUri: String) {
        subtitleGenerationManager.triggerSubtitleGeneration(fragment, binding, viewModel, groqApiClient, playbackUIHelper, videoPath, contentUri,
            onStart = {
                isGeneratingSubtitles = true
                playbackUIHelper.showProcessing(binding)
            },
            onSuccess = { segs, texts ->
                dialogueSegments = segs; translatedTexts = texts; selectedSegmentIndex = -1; subtitleGenerated = true; isGeneratingSubtitles = false
                playbackUIHelper.hideProcessing(binding)
            },
            onError = {
                isGeneratingSubtitles = false; subtitleGenerated = false
                playbackUIHelper.hideProcessing(binding)
            },
            showDialogueList = { segs ->
                dialogueAdapter = dialogueInteractionHandler.showDialogueList(binding, segs, translatedTexts) { segment, index ->
                    dialogueInteractionHandler.onDialogueSegmentSelected(binding, playbackUIHelper, videoPath, segment, index, dialogueAdapter)
                    dialogueInteractionHandler.updatePlaySelectedButton(binding, dialogueAdapter)
                }
            },
            switchTab = { switchTab(binding, viewModel, it) },
            getIsGenerating = { isGeneratingSubtitles }
        )
    }

    private fun autoLoadCachedSubtitles(fragment: Fragment, binding: FragmentPlaybackSettingsBinding, viewModel: PlaybackSettingsViewModel, videoPath: String) {
        subtitleGenerationManager.tryAutoLoadCachedSubtitles(fragment, viewModel, videoPath) { cues ->
            val (segs, texts) = subtitleGenerationManager.loadSubtitleCues(cues) { segs, texts ->
                dialogueAdapter = dialogueInteractionHandler.showDialogueList(binding, segs, texts) { segment, index ->
                    dialogueInteractionHandler.onDialogueSegmentSelected(binding, playbackUIHelper, videoPath, segment, index, dialogueAdapter)
                    dialogueInteractionHandler.updatePlaySelectedButton(binding, dialogueAdapter)
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
