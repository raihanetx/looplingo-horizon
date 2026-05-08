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
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.model.SpeedPresets
import com.looplingo.horizon.playback.AudioPlaybackService
import com.looplingo.horizon.ui.viewmodel.PlaybackSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Playback settings with A-B loop, speed control (instant apply), and try-before-save.
 *
 * Key UX improvements:
 *  - Speed changes apply instantly to playing audio (no save needed)
 *  - "Try Loop" button previews the A-B loop before saving
 *  - "Save" button commits the settings to the database
 *  - Transcript/subtitle section removed for simpler, focused UX
 *  - Now playing indicator when the current video is active
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
        setupTryLoopButton()
        setupApplyButton()
        setupClearButton()
        setupNowPlayingCard()
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
    // NOW PLAYING CARD
    // ══════════════════════════════════════════════════════════════════════

    private fun setupNowPlayingCard() {
        startPositionPolling()
    }

    private fun startPositionPolling() {
        stopPositionPolling()
        positionPollingRunnable = object : Runnable {
            override fun run() {
                try {
                    val isPlaying = AudioPlaybackService.isPlaying
                    val currentPath = AudioPlaybackService.currentVideoPath
                    val position = AudioPlaybackService.currentPositionMs

                    if (isPlaying && currentPath == args.videoPath) {
                        binding.cardNowPlaying.visibility = View.VISIBLE
                        val title = currentPath.substringAfterLast("/").substringBeforeLast(".")
                        binding.tvNowPlayingTitle.text = title
                        binding.tvNowPlayingPosition.text = formatMsToTime(position)
                    } else {
                        binding.cardNowPlaying.visibility = View.GONE
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
}
