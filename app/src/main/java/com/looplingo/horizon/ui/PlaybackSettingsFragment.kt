package com.looplingo.horizon.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.BuildConfig
import com.looplingo.horizon.R
import com.looplingo.horizon.api.GroqApiClient
import com.looplingo.horizon.api.GroqApiClient.Segment
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.model.SpeedPresets
import com.looplingo.horizon.playback.AudioPlaybackService
import com.looplingo.horizon.ui.viewmodel.PlaybackSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Playback settings with A-B loop, speed control (instant apply), try-before-save,
 * AI subtitle generation via Groq STT, and dialogue looping.
 *
 * Key UX improvements:
 *  - Speed changes apply instantly to playing audio (no save needed)
 *  - "Try Loop" button previews the A-B loop before saving
 *  - "Save" button commits the settings to the database
 *  - AI Subtitles: generate transcript from audio using Groq Whisper API
 *  - Dialogue looping: tap a dialogue line to loop it N times
 *  - Now playing indicator with transport controls (play/pause, stop, skip)
 */
@AndroidEntryPoint
class PlaybackSettingsFragment : Fragment() {

    private var _binding: FragmentPlaybackSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaybackSettingsViewModel by viewModels()
    private val args: PlaybackSettingsFragmentArgs by navArgs()

    // Polling for now-playing position update
    private val positionHandler = Handler(Looper.getMainLooper())
    private var positionPollingRunnable: Runnable? = null
    private val POSITION_POLL_INTERVAL_MS = 500L

    // AI Subtitle state
    private val groqApiClient = GroqApiClient()
    private var dialogueSegments: List<Segment> = emptyList()
    private var selectedSegmentIndex: Int = -1
    private var dialogueLoopCount: Int = 3
    private var isGeneratingSubtitles: Boolean = false

    // SharedPreferences for Groq API key
    private val prefsName = "looplingo_prefs"
    private val keyGroqApiKey = "groq_api_key"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaybackSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val videoPath = args.videoPath
        if (videoPath.isBlank()) {
            findNavController().navigateUp()
            return
        }

        viewModel.loadConfigForVideo(videoPath)
        setupToolbar()
        setupTransportControls()
        setupSpeedChips()
        setupTryLoopButton()
        setupApplyButton()
        setupClearButton()
        setupNowPlayingCard()
        setupSubtitleGeneration()
        setupDialogueLoopControls()
        setupObservers()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TRANSPORT CONTROLS — Play/Pause, Stop, Skip Forward/Backward
    // ══════════════════════════════════════════════════════════════════════

    private fun setupTransportControls() {
        // Play/Pause button
        binding.ivPlayPause.setOnClickListener {
            val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
                AudioPlaybackService.currentVideoPath == args.videoPath

            if (isCurrentlyPlaying) {
                // Toggle pause/play
                AudioPlaybackService.togglePlayback(requireContext())
            } else {
                // Start playback
                AudioPlaybackService.startService(requireContext(), args.videoPath)
            }
        }

        // Stop button
        binding.ivStop.setOnClickListener {
            AudioPlaybackService.stopService(requireContext())
        }

        // Rewind 10s
        binding.ivRewind10.setOnClickListener {
            AudioPlaybackService.seekBackward(requireContext(), 10000L)
        }

        // Rewind 5s
        binding.ivRewind5.setOnClickListener {
            AudioPlaybackService.seekBackward(requireContext(), 5000L)
        }

        // Forward 5s
        binding.ivForward5.setOnClickListener {
            AudioPlaybackService.seekForward(requireContext(), 5000L)
        }

        // Forward 10s
        binding.ivForward10.setOnClickListener {
            AudioPlaybackService.seekForward(requireContext(), 10000L)
        }
    }

    private fun updateTransportControlState() {
        val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
            AudioPlaybackService.currentVideoPath == args.videoPath

        // Update play/pause icon
        binding.ivPlayPause.setImageResource(
            if (isCurrentlyPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        // Update now playing info
        if (isCurrentlyPlaying) {
            val title = AudioPlaybackService.currentVideoPath.substringAfterLast("/").substringBeforeLast(".")
            binding.tvNowPlayingTitle.text = title
            binding.tvNowPlayingPosition.text = formatMsToTime(AudioPlaybackService.currentPositionMs)
        } else if (AudioPlaybackService.currentVideoPath.isBlank()) {
            // Service not running at all
            binding.tvNowPlayingTitle.text = args.videoPath.substringAfterLast("/").substringBeforeLast(".")
            binding.tvNowPlayingPosition.text = getString(R.string.now_playing_idle)
        }
    }

    private fun setupSpeedChips() {
        val chipGroup = binding.chipGroupSpeed
        chipGroup.removeAllViews()

        for (preset in SpeedPresets.ALL) {
            val chip = Chip(requireContext()).apply {
                text = preset.label
                isCheckable = true
                id = View.generateViewId()
                tag = preset.speed
            }
            chip.setOnClickListener {
                // Instant speed change — applies immediately to playing audio
                AudioPlaybackService.setSpeed(requireContext(), preset.speed)
                Timber.d("Speed changed to %s (instant)", preset.label)
            }
            chipGroup.addView(chip)
        }
    }

    private fun selectSpeedChip(speed: Float) {
        val chipGroup = binding.chipGroupSpeed
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            val chipSpeed = chip.tag as? Float ?: continue
            chip.isChecked = (chipSpeed == speed)
        }
    }

    private fun getSelectedSpeed(): Float {
        val chipId = binding.chipGroupSpeed.checkedChipId
        if (chipId == View.NO_ID) return SpeedPresets.DEFAULT.speed
        val chip = binding.chipGroupSpeed.findViewById<View>(chipId) as? Chip ?: return SpeedPresets.DEFAULT.speed
        return chip.tag as? Float ?: SpeedPresets.DEFAULT.speed
    }

    // ══════════════════════════════════════════════════════════════════════
    // TRY LOOP — Preview before saving
    // ══════════════════════════════════════════════════════════════════════

    private fun setupTryLoopButton() {
        binding.btnTryLoop.setOnClickListener {
            val rangeStartMs = parseTimeToMs(binding.etRangeStart.text.toString())
            val rangeEndMs = parseTimeToMs(binding.etRangeEnd.text.toString())
            val loopCount = binding.etLoopCount.text.toString().toIntOrNull() ?: 1

            // Validate
            var hasError = false
            if (rangeEndMs > 0 && rangeEndMs <= rangeStartMs) {
                binding.tilRangeEnd.error = getString(R.string.error_range_end_before_start)
                hasError = true
            } else {
                binding.tilRangeEnd.error = null
            }

            if (loopCount < 1) {
                binding.tilLoopCount.error = getString(R.string.error_loop_count_minimum)
                hasError = true
            } else {
                binding.tilLoopCount.error = null
            }

            if (hasError) return@setOnClickListener

            // Check if this video is currently playing
            val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
                AudioPlaybackService.currentVideoPath == args.videoPath

            if (isCurrentlyPlaying) {
                // Apply in real-time — preview the loop
                AudioPlaybackService.setABLoop(
                    requireContext(),
                    args.videoPath,
                    rangeStartMs,
                    if (binding.etRangeEnd.text.isNullOrBlank()) -1L else rangeEndMs,
                    loopCount
                )
                showSnackbar(getString(R.string.loop_preview_active))
            } else {
                // Start playback and apply the loop
                AudioPlaybackService.startService(requireContext(), args.videoPath)
                // Give the service a moment to start, then apply A-B loop
                positionHandler.postDelayed({
                    AudioPlaybackService.setABLoop(
                        requireContext(),
                        args.videoPath,
                        rangeStartMs,
                        if (binding.etRangeEnd.text.isNullOrBlank()) -1L else rangeEndMs,
                        loopCount
                    )
                }, 1000)
                showSnackbar(getString(R.string.loop_preview_active))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AI SUBTITLES — Groq STT Integration
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Get the Groq API key. Priority:
     * 1. User-entered key in the API key field (SharedPreferences)
     * 2. BuildConfig key (from local.properties / env var at build time)
     */
    private fun getGroqApiKey(): String {
        // First check SharedPreferences for user-entered key
        val prefs = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(keyGroqApiKey, "") ?: ""
        if (savedKey.isNotBlank()) return savedKey

        // Fallback to BuildConfig (build-time key)
        return BuildConfig.GROQ_API_KEY
    }

    /**
     * Save the Groq API key to SharedPreferences.
     */
    private fun saveGroqApiKey(apiKey: String) {
        requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyGroqApiKey, apiKey.trim())
            .apply()
    }

    /**
     * Load the saved API key into the input field.
     */
    private fun loadSavedApiKey() {
        val prefs = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(keyGroqApiKey, "") ?: ""
        if (savedKey.isNotBlank()) {
            binding.etGroqApiKey.setText(savedKey)
        } else if (BuildConfig.GROQ_API_KEY.isNotBlank()) {
            // Show the build-time key as pre-filled value
            binding.etGroqApiKey.setText(BuildConfig.GROQ_API_KEY)
        }
    }

    private fun setupSubtitleGeneration() {
        // Load saved API key
        loadSavedApiKey()

        // Save API key on text change (debounced via focus change)
        binding.etGroqApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val key = binding.etGroqApiKey.text.toString().trim()
                if (key.isNotBlank()) {
                    saveGroqApiKey(key)
                    showSnackbar(getString(R.string.groq_api_key_saved))
                }
            }
        }

        binding.btnGenerateSubtitles.setOnClickListener {
            if (isGeneratingSubtitles) return@setOnClickListener

            // Save API key before using it
            val enteredKey = binding.etGroqApiKey.text.toString().trim()
            if (enteredKey.isNotBlank()) {
                saveGroqApiKey(enteredKey)
            }

            val apiKey = getGroqApiKey()
            if (apiKey.isBlank()) {
                showSnackbar(getString(R.string.error_no_api_key))
                return@setOnClickListener
            }

            val videoPath = args.videoPath
            if (videoPath.isBlank()) {
                showSnackbar(getString(R.string.error_invalid_video_path))
                return@setOnClickListener
            }

            isGeneratingSubtitles = true
            binding.progressSubtitles.visibility = View.VISIBLE
            binding.tvSubtitleStatus.visibility = View.VISIBLE
            binding.tvSubtitleStatus.text = getString(R.string.subtitle_generating)
            binding.btnGenerateSubtitles.isEnabled = false

            lifecycleScope.launch {
                try {
                    val segments = withContext(Dispatchers.IO) {
                        groqApiClient.transcribeAudio(apiKey, videoPath)
                    }

                    dialogueSegments = segments
                    isGeneratingSubtitles = false
                    binding.progressSubtitles.visibility = View.GONE

                    if (segments.isEmpty()) {
                        binding.tvSubtitleStatus.text = getString(R.string.subtitle_no_segments)
                    } else {
                        binding.tvSubtitleStatus.text = getString(R.string.subtitle_generated, segments.size)
                        showDialogueList(segments)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to generate subtitles")
                    isGeneratingSubtitles = false
                    binding.progressSubtitles.visibility = View.GONE
                    binding.tvSubtitleStatus.text = getString(R.string.subtitle_error, e.message ?: "Unknown")
                    showSnackbar(getString(R.string.subtitle_error_short))
                } finally {
                    binding.btnGenerateSubtitles.isEnabled = true
                }
            }
        }
    }

    private fun showDialogueList(segments: List<Segment>) {
        binding.rvDialogueList.visibility = View.VISIBLE
        binding.layoutDialogueLoopControls.visibility = View.VISIBLE

        binding.rvDialogueList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = DialogueAdapter(segments) { segment, index ->
                selectedSegmentIndex = index
                onDialogueSegmentSelected(segment)
            }
        }
    }

    private fun onDialogueSegmentSelected(segment: Segment) {
        // Fill the A-B range with the segment's start/end times
        binding.etRangeStart.setText(formatMsToTime(segment.startMs))
        binding.etRangeEnd.setText(formatMsToTime(segment.endMs))

        // If currently playing, seek to the segment start
        val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
            AudioPlaybackService.currentVideoPath == args.videoPath

        if (isCurrentlyPlaying) {
            AudioPlaybackService.seekToPosition(requireContext(), args.videoPath, segment.startMs)
        }

        showSnackbar(getString(R.string.dialogue_selected, segment.text.take(30)))
    }

    // ══════════════════════════════════════════════════════════════════════
    // DIALOGUE LOOP CONTROLS
    // ══════════════════════════════════════════════════════════════════════

    private fun setupDialogueLoopControls() {
        binding.tvDialogueLoopCount.text = dialogueLoopCount.toString()

        binding.btnDialogueLoopMinus.setOnClickListener {
            if (dialogueLoopCount > 1) {
                dialogueLoopCount--
                binding.tvDialogueLoopCount.text = dialogueLoopCount.toString()
            }
        }

        binding.btnDialogueLoopPlus.setOnClickListener {
            if (dialogueLoopCount < 100) {
                dialogueLoopCount++
                binding.tvDialogueLoopCount.text = dialogueLoopCount.toString()
            }
        }

        binding.btnLoopDialogue.setOnClickListener {
            if (selectedSegmentIndex < 0 || selectedSegmentIndex >= dialogueSegments.size) {
                showSnackbar(getString(R.string.error_no_dialogue_selected))
                return@setOnClickListener
            }

            val segment = dialogueSegments[selectedSegmentIndex]
            val loopCount = dialogueLoopCount

            // Apply the dialogue loop as an A-B loop
            val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
                AudioPlaybackService.currentVideoPath == args.videoPath

            if (isCurrentlyPlaying) {
                AudioPlaybackService.setABLoop(
                    requireContext(),
                    args.videoPath,
                    segment.startMs,
                    segment.endMs,
                    loopCount
                )
            } else {
                AudioPlaybackService.startService(requireContext(), args.videoPath)
                positionHandler.postDelayed({
                    AudioPlaybackService.setABLoop(
                        requireContext(),
                        args.videoPath,
                        segment.startMs,
                        segment.endMs,
                        loopCount
                    )
                }, 1000)
            }

            showSnackbar(getString(R.string.dialogue_loop_active, loopCount, segment.text.take(20)))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // NOW PLAYING CARD
    // ══════════════════════════════════════════════════════════════════════

    private fun setupNowPlayingCard() {
        // Show video title immediately
        binding.tvNowPlayingTitle.text = args.videoPath.substringAfterLast("/").substringBeforeLast(".")
        binding.tvNowPlayingPosition.text = getString(R.string.now_playing_idle)
        startPositionPolling()
    }

    private fun startPositionPolling() {
        stopPositionPolling()
        positionPollingRunnable = object : Runnable {
            override fun run() {
                try {
                    updateTransportControlState()
                } catch (e: Exception) {
                    Timber.w(e, "Position polling error")
                }
                positionHandler.postDelayed(this, POSITION_POLL_INTERVAL_MS)
            }
        }
        positionHandler.post(positionPollingRunnable!!)
    }

    private fun stopPositionPolling() {
        positionPollingRunnable?.let { positionHandler.removeCallbacks(it) }
        positionPollingRunnable = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // BUTTONS & OBSERVERS
    // ══════════════════════════════════════════════════════════════════════

    private fun setupApplyButton() {
        binding.btnApply.setOnClickListener {
            val rangeStartMs = parseTimeToMs(binding.etRangeStart.text.toString())
            val rangeEndMs = parseTimeToMs(binding.etRangeEnd.text.toString())
            val loopCount = binding.etLoopCount.text.toString().toIntOrNull() ?: 1
            val speed = getSelectedSpeed()

            // Validate
            var hasError = false
            if (rangeStartMs < 0) {
                binding.tilRangeStart.error = getString(R.string.error_range_start_negative)
                hasError = true
            } else {
                binding.tilRangeStart.error = null
            }

            if (rangeEndMs > 0 && rangeEndMs <= rangeStartMs) {
                binding.tilRangeEnd.error = getString(R.string.error_range_end_before_start)
                hasError = true
            } else {
                binding.tilRangeEnd.error = null
            }

            if (loopCount < 1) {
                binding.tilLoopCount.error = getString(R.string.error_loop_count_minimum)
                hasError = true
            } else if (loopCount > 10000) {
                binding.tilLoopCount.error = getString(R.string.error_loop_count_maximum)
                hasError = true
            } else {
                binding.tilLoopCount.error = null
            }

            if (hasError) return@setOnClickListener

            viewModel.updateConfig(
                rangeStartMs = rangeStartMs,
                rangeEndMs = if (binding.etRangeEnd.text.isNullOrBlank()) -1L else rangeEndMs,
                loopCount = loopCount,
                speed = speed
            )
            viewModel.saveConfig()
        }
    }

    private fun setupClearButton() {
        binding.btnClear.setOnClickListener {
            viewModel.deleteConfig()
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.config.collect { config ->
                        binding.etRangeStart.setText(formatMsToTime(config.rangeStartMs))
                        binding.etRangeEnd.setText(if (config.rangeEndMs <= 0) "" else formatMsToTime(config.rangeEndMs))
                        binding.etLoopCount.setText(config.loopCount.toString())
                        selectSpeedChip(config.speed)
                    }
                }

                launch {
                    viewModel.isSaved.collect { saved ->
                        if (saved) {
                            showSnackbar(getString(R.string.settings_saved))
                            findNavController().navigateUp()
                        }
                    }
                }

                launch {
                    viewModel.saveError.collect { errorMsg ->
                        errorMsg?.let {
                            showSnackbar(it) { viewModel.clearSaveError(); viewModel.saveConfig() }
                        }
                    }
                }

                launch {
                    viewModel.savedTimestamps.collect { timestamps ->
                        updateSavedTimestampsList(timestamps)
                    }
                }
            }
        }
    }

    private fun updateSavedTimestampsList(timestamps: List<com.looplingo.horizon.data.entity.SavedTimestampEntity>) {
        val container = binding.savedTimestampsContainer
        container.removeAllViews()

        if (timestamps.isEmpty()) {
            binding.layoutSavedTimestamps.visibility = View.GONE
            return
        }

        binding.layoutSavedTimestamps.visibility = View.VISIBLE

        for (ts in timestamps) {
            val itemView = layoutInflater.inflate(R.layout.item_saved_timestamp, container, false)
            itemView.findViewById<TextView>(R.id.tv_timestamp_label).text = ts.label
            itemView.findViewById<TextView>(R.id.tv_timestamp_range).text =
                "${formatMsToTime(ts.rangeStartMs)} → ${formatMsToTime(ts.rangeEndMs)} (x${ts.loopCount})"

            itemView.findViewById<View>(R.id.btn_use_timestamp).setOnClickListener {
                binding.etRangeStart.setText(formatMsToTime(ts.rangeStartMs))
                binding.etRangeEnd.setText(formatMsToTime(ts.rangeEndMs))
                binding.etLoopCount.setText(ts.loopCount.toString())
            }

            itemView.findViewById<View>(R.id.btn_delete_timestamp).setOnClickListener {
                viewModel.deleteTimestamp(ts)
            }

            container.addView(itemView)
        }
    }

    /** Parse "1:23" or "83" to milliseconds. Supports mm:ss or pure seconds. */
    private fun parseTimeToMs(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val trimmed = text.trim()

        if (trimmed.contains(":")) {
            val parts = trimmed.split(":")
            if (parts.size == 2) {
                val minutes = parts[0].toLongOrNull() ?: 0L
                val seconds = parts[1].toLongOrNull() ?: 0L
                return (minutes * 60 + seconds) * 1000
            }
            if (parts.size == 3) {
                val hours = parts[0].toLongOrNull() ?: 0L
                val minutes = parts[1].toLongOrNull() ?: 0L
                val seconds = parts[2].toLongOrNull() ?: 0L
                return (hours * 3600 + minutes * 60 + seconds) * 1000
            }
        }

        val seconds = trimmed.toLongOrNull() ?: 0L
        return seconds * 1000
    }

    /** Format milliseconds to "m:ss" or "h:mm:ss" */
    private fun formatMsToTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val hours = TimeUnit.SECONDS.toHours(totalSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun showSnackbar(message: String, action: (() -> Unit)? = null) {
        view?.let { rootView ->
            val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
            if (action != null) {
                snackbar.setAction(getString(R.string.retry)) { action() }
            }
            snackbar.show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPositionPolling()
        _binding = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // DIALOGUE ADAPTER — RecyclerView for subtitle segments
    // ══════════════════════════════════════════════════════════════════════

    private inner class DialogueAdapter(
        private val segments: List<Segment>,
        private val onSegmentClick: (Segment, Int) -> Unit
    ) : RecyclerView.Adapter<DialogueAdapter.DialogueViewHolder>() {

        private var selectedPos = -1

        inner class DialogueViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTime: TextView = view.findViewById(R.id.tv_cue_timestamp)
            val tvText: TextView = view.findViewById(R.id.tv_cue_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DialogueViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_subtitle_cue, parent, false)
            return DialogueViewHolder(view)
        }

        override fun onBindViewHolder(holder: DialogueViewHolder, position: Int) {
            val segment = segments[position]
            holder.tvTime.text = "${formatMsToTime(segment.startMs)} → ${formatMsToTime(segment.endMs)}"
            holder.tvText.text = segment.text

            holder.itemView.isSelected = (position == selectedPos)
            holder.itemView.setOnClickListener {
                selectedPos = position
                notifyDataSetChanged()
                onSegmentClick(segment, position)
            }
        }

        override fun getItemCount() = segments.size
    }
}
