package com.looplingo.horizon.ui

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
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.model.SpeedPresets
import com.looplingo.horizon.model.SubtitleCue
import com.looplingo.horizon.ui.viewmodel.PlaybackSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Playback settings with A-B loop, speed control, and transcript view.
 *
 * Transcript feature:
 *  - Automatically finds .srt/.vtt/.lrc files with the same name as the audio
 *  - Displays all subtitle lines with timestamps
 *  - Highlights the current line during playback (via MediaController polling)
 *  - Tap a subtitle line to seek to that position
 */
@AndroidEntryPoint
class PlaybackSettingsFragment : Fragment() {

    private var _binding: FragmentPlaybackSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaybackSettingsViewModel by viewModels()
    private val args: PlaybackSettingsFragmentArgs by navArgs()

    private lateinit var transcriptAdapter: SubtitleCueAdapter

    // Polling for playback position (used to sync transcript highlight)
    private val positionHandler = Handler(Looper.getMainLooper())
    private var positionPollingRunnable: Runnable? = null
    private val POSITION_POLL_INTERVAL_MS = 500L

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
        setupSpeedChips()
        setupTranscript()
        setupApplyButton()
        setupClearButton()
        setupObservers()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
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
    // TRANSCRIPT SETUP
    // ══════════════════════════════════════════════════════════════════════

    private fun setupTranscript() {
        transcriptAdapter = SubtitleCueAdapter { cue ->
            // When user taps a subtitle line, seek to that position in playback
            try {
                val videoPath = args.videoPath
                com.looplingo.horizon.playback.AudioPlaybackService.seekToPosition(
                    requireContext(), videoPath, cue.startMs
                )
                Timber.d("Tapped subtitle cue at %dms — seeking playback", cue.startMs)
            } catch (e: Exception) {
                Timber.e(e, "Failed to seek from subtitle tap")
            }
        }

        binding.rvTranscript.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = transcriptAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun updateTranscriptDisplay(cues: List<SubtitleCue>) {
        if (cues.isEmpty()) {
            binding.layoutNoSubtitles.visibility = View.VISIBLE
            binding.rvTranscript.visibility = View.GONE
            binding.cardActiveSubtitle.visibility = View.GONE
            return
        }

        binding.layoutNoSubtitles.visibility = View.GONE
        binding.rvTranscript.visibility = View.VISIBLE
        transcriptAdapter.submitList(cues)
    }

    /**
     * Start polling the service for playback position to sync transcript.
     * Uses a lightweight Handler-based approach — only active when the
     * fragment is visible and playback is ongoing.
     */
    private fun startPositionPolling() {
        stopPositionPolling()
        positionPollingRunnable = object : Runnable {
            override fun run() {
                try {
                    // Read current position from the service via a content provider
                    // or shared preference. For simplicity, we use a static holder
                    // in the service that the fragment can read.
                    val position = com.looplingo.horizon.playback.AudioPlaybackService.currentPositionMs
                    val isPlaying = com.looplingo.horizon.playback.AudioPlaybackService.isPlaying
                    val currentVideoPath = com.looplingo.horizon.playback.AudioPlaybackService.currentVideoPath

                    if (isPlaying && currentVideoPath == args.videoPath) {
                        viewModel.updatePlaybackPosition(position, true)
                    } else {
                        viewModel.updatePlaybackPosition(position, false)
                    }
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

    private fun updateActiveSubtitle(activeIndex: Int, cues: List<SubtitleCue>, isPlaying: Boolean) {
        // Update the active subtitle card
        if (isPlaying && activeIndex >= 0 && activeIndex < cues.size) {
            val cue = cues[activeIndex]
            binding.cardActiveSubtitle.visibility = View.VISIBLE
            binding.tvActiveTimestamp.text = getString(
                R.string.transcript_now_playing
            ) + " " + cue.startLabel
            binding.tvActiveText.text = cue.text
        } else {
            binding.cardActiveSubtitle.visibility = View.GONE
        }

        // Update the adapter's active index for highlighting
        transcriptAdapter.activeIndex = activeIndex
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

                launch {
                    viewModel.subtitleCues.collect { cues ->
                        updateTranscriptDisplay(cues)
                        // Start polling for position if we have subtitles
                        if (cues.isNotEmpty()) {
                            startPositionPolling()
                        }
                    }
                }

                launch {
                    viewModel.activeCueIndex.collect { index ->
                        updateActiveSubtitle(index, viewModel.subtitleCues.value, viewModel.isCurrentlyPlaying.value)
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

        // Try mm:ss format
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

        // Pure seconds
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
    // SUBTITLE CUE ADAPTER (inner class)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Simple RecyclerView adapter for subtitle cue items.
     * Highlights the currently active cue with a different background color.
     */
    private class SubtitleCueAdapter(
        private val onCueClick: (SubtitleCue) -> Unit
    ) : RecyclerView.Adapter<SubtitleCueAdapter.CueViewHolder>() {

        private val cues = mutableListOf<SubtitleCue>()
        var activeIndex: Int = -1
            set(value) {
                val oldIndex = field
                field = value
                if (oldIndex != value) {
                    if (oldIndex >= 0) notifyItemChanged(oldIndex)
                    if (value >= 0) notifyItemChanged(value)
                }
            }

        fun submitList(newCues: List<SubtitleCue>) {
            cues.clear()
            cues.addAll(newCues)
            activeIndex = -1
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CueViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_subtitle_cue, parent, false)
            return CueViewHolder(view)
        }

        override fun onBindViewHolder(holder: CueViewHolder, position: Int) {
            val cue = cues[position]
            holder.bind(cue, position == activeIndex)
            holder.itemView.setOnClickListener { onCueClick(cue) }
        }

        override fun getItemCount() = cues.size

        class CueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val timestamp: TextView = itemView.findViewById(R.id.tv_cue_timestamp)
            private val text: TextView = itemView.findViewById(R.id.tv_cue_text)
            private val layout: View = itemView.findViewById(R.id.layout_cue_item)

            fun bind(cue: SubtitleCue, isActive: Boolean) {
                timestamp.text = cue.startLabel
                text.text = cue.text

                if (isActive) {
                    // Highlight active cue with primary container color
                    layout.setBackgroundColor(
                        layout.context.getColor(com.looplingo.horizon.R.color.colorPrimaryContainer)
                    )
                    text.setTextColor(
                        layout.context.getColor(com.looplingo.horizon.R.color.colorOnPrimaryContainer)
                    )
                    timestamp.setTextColor(
                        layout.context.getColor(com.looplingo.horizon.R.color.colorOnPrimaryContainer)
                    )
                } else {
                    // Normal appearance
                    layout.setBackgroundColor(
                        layout.context.getColor(com.looplingo.horizon.R.color.colorSurfaceContainerLow)
                    )
                    text.setTextColor(
                        layout.context.getColor(com.looplingo.horizon.R.color.colorOnSurface)
                    )
                    timestamp.setTextColor(
                        layout.context.getColor(com.looplingo.horizon.R.color.colorOnSurfaceVariant)
                    )
                }
            }
        }
    }
}
