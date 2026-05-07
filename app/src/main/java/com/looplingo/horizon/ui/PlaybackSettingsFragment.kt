package com.looplingo.horizon.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.model.LoopMode
import com.looplingo.horizon.model.PlaybackConfigValidator
import com.looplingo.horizon.model.StartAction
import com.looplingo.horizon.ui.viewmodel.PlaybackSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Fragment for configuring playback settings of a specific video.
 *
 * The user can set:
 *  - Start action: Auto-play or Load & Wait
 *  - Loop mode: Play Once, Loop X Times, Loop Infinite, Flow, Auto-Loop, A-B Pin
 *  - Loop count (for Loop X Times and Auto-Loop modes)
 *  - Playback range in seconds (for A-B Pin mode)
 *  - Auto-advance to next video after loop completes
 *
 * All inputs are validated before saving via [PlaybackConfigValidator].
 * The config is persisted to Room and will be loaded by the playback service.
 *
 * UI uses Material Design 3 components:
 *  - MaterialAutoCompleteTextView instead of Spinner
 *  - TextInputLayout for all text inputs with error states
 *  - MaterialSwitch instead of CheckBox
 *  - Card sections for visual grouping
 */
@AndroidEntryPoint
class PlaybackSettingsFragment : Fragment() {

    private var _binding: FragmentPlaybackSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaybackSettingsViewModel by viewModels()
    private val args: PlaybackSettingsFragmentArgs by navArgs()

    // Loop mode display labels matching the LoopMode enum order
    private val loopModeLabels by lazy {
        arrayOf(
            getString(R.string.play_once),
            getString(R.string.loop_x_times),
            getString(R.string.loop_infinite),
            getString(R.string.flow),
            getString(R.string.auto_loop),
            getString(R.string.ab_pin)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaybackSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val videoPath = args.videoPath
        if (videoPath.isBlank()) {
            Timber.e("PlaybackSettingsFragment launched with blank videoPath")
            showSnackbar(getString(R.string.error_invalid_video_path))
            findNavController().navigateUp()
            return
        }

        Timber.d("Opening playback settings for: %s", videoPath)
        viewModel.loadConfigForVideo(videoPath)

        setupToolbar()
        setupLoopModeDropdown()
        setupStartActionRadio()
        setupApplyButton()
        setupObservers()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupLoopModeDropdown() {
        val adapter = ArrayAdapter(requireContext(), R.layout.dropdown_item, loopModeLabels)
        (binding.actvLoopMode as AutoCompleteTextView).setAdapter(adapter)

        binding.actvLoopMode.setOnItemClickListener { _, _, position, _ ->
            val mode = LoopMode.entries.getOrElse(position) { LoopMode.LOOP_INFINITE }

            // Apply sensible defaults when switching to a mode that uses loopCount.
            // Only reset loopCount if the user hasn't previously customised it
            // for the new mode — otherwise preserve their value.
            val currentConfig = viewModel.config.value
            val previousMode = currentConfig.loopMode

            if (mode != previousMode) {
                if (mode.usesLoopCount) {
                    // Reset to the mode's default loop count so the user gets a
                    // sensible starting value (e.g., 3 for LOOP_X_TIMES instead of 1).
                    viewModel.updateConfig(loopMode = mode, loopCount = mode.defaultLoopCount)
                } else {
                    viewModel.updateConfig(loopMode = mode)
                }

                // Reset range fields when leaving A_B_PIN mode
                if (previousMode == LoopMode.A_B_PIN && mode != LoopMode.A_B_PIN) {
                    viewModel.updateConfig(rangeStartMs = 0L, rangeEndMs = -1L)
                }
            }

            updateVisibilityForMode(mode)
        }
    }

    private fun setupStartActionRadio() {
        binding.rbAutoplay.isChecked = true
        binding.rgStartAction.setOnCheckedChangeListener { _, checkedId ->
            val startAction = if (checkedId == binding.rbAutoplay.id) StartAction.AUTO_PLAY else StartAction.WAIT_MANUAL
            viewModel.updateConfig(startAction = startAction)
        }
    }

    /**
     * Show/hide UI elements based on the selected loop mode.
     * - Loop count is shown for modes where [LoopMode.usesLoopCount] is true
     * - Playback range is only shown for A-B Pin mode
     * - Auto-advance is disabled for FLOW mode (it always advances)
     * - Auto-advance is disabled for LOOP_INFINITE (it never ends)
     */
    private fun updateVisibilityForMode(mode: LoopMode) {
        // Loop count: visible for modes that use it
        binding.tilLoopCount.visibility =
            if (mode.usesLoopCount) {
                View.VISIBLE
            } else {
                View.GONE
            }

        // Playback range: only visible for A-B Pin mode
        binding.layoutRangeSection.visibility =
            if (mode == LoopMode.A_B_PIN) {
                View.VISIBLE
            } else {
                View.GONE
            }

        // Auto-advance: disable and dim for FLOW (always advances) and LOOP_INFINITE (never ends)
        val autoAdvanceApplicable = mode != LoopMode.FLOW && mode != LoopMode.LOOP_INFINITE
        binding.switchAutoAdvance.isEnabled = autoAdvanceApplicable
        binding.switchAutoAdvance.alpha = if (autoAdvanceApplicable) 1.0f else 0.4f
        if (!autoAdvanceApplicable) {
            binding.switchAutoAdvance.isChecked = mode == LoopMode.FLOW // FLOW always advances
            viewModel.updateConfig(autoAdvance = mode == LoopMode.FLOW)
        }

        // Clear any previous error when mode changes
        binding.tilLoopCount.error = null
        binding.tilRangeStart.error = null
        binding.tilRangeEnd.error = null
    }

    /**
     * Validate all user inputs before saving using the shared validator.
     * Shows inline errors on TextInputLayout fields.
     * Returns true if all inputs are valid.
     */
    private fun validateInputs(
        loopMode: LoopMode,
        loopCount: Int,
        rangeStartSec: Long,
        rangeEndSec: Long
    ): Boolean {
        var hasError = false

        // Validate loop count for relevant modes
        if (loopMode.usesLoopCount) {
            if (loopCount < 1) {
                binding.tilLoopCount.error = getString(R.string.error_loop_count_minimum)
                hasError = true
            } else if (loopCount > 10000) {
                binding.tilLoopCount.error = getString(R.string.error_loop_count_maximum)
                hasError = true
            } else {
                binding.tilLoopCount.error = null
            }
        }

        // Validate range — only relevant for A-B Pin mode
        if (loopMode == LoopMode.A_B_PIN) {
            if (rangeStartSec < 0) {
                binding.tilRangeStart.error = getString(R.string.error_range_start_negative)
                hasError = true
            } else {
                binding.tilRangeStart.error = null
            }

            if (rangeEndSec < 0) {
                binding.tilRangeEnd.error = getString(R.string.error_ab_pin_requires_end)
                hasError = true
            } else if (rangeEndSec <= rangeStartSec) {
                binding.tilRangeEnd.error = getString(R.string.error_range_end_before_start)
                hasError = true
            } else if ((rangeEndSec - rangeStartSec) < 1) {
                binding.tilRangeEnd.error = getString(R.string.error_ab_pin_range_too_short)
                hasError = true
            } else {
                binding.tilRangeEnd.error = null
            }
        } else {
            // Non A-B Pin modes don't use range — clear any residual errors
            binding.tilRangeStart.error = null
            binding.tilRangeEnd.error = null
        }

        return !hasError
    }

    private fun setupApplyButton() {
        binding.btnApply.setOnClickListener {
            val currentConfig = viewModel.config.value
            val loopCount = binding.etLoopCount.text.toString().toIntOrNull()
                ?: currentConfig.loopCount
            val rangeStartSec = binding.etRangeStart.text.toString().toLongOrNull()
                ?: (currentConfig.rangeStartMs / 1000)
            val rangeEndSec = binding.etRangeEnd.text.toString().toLongOrNull()
                ?: if (currentConfig.rangeEndMs <= 0) -1L else (currentConfig.rangeEndMs / 1000)
            val autoAdvance = binding.switchAutoAdvance.isChecked

            // Get the current loop mode from the ViewModel
            val currentMode = currentConfig.loopMode

            // Validate all inputs with inline error display
            val isValid = validateInputs(currentMode, loopCount, rangeStartSec, rangeEndSec)
            if (!isValid) {
                Timber.w("Input validation failed for mode: %s", currentMode)
                return@setOnClickListener
            }

            // Convert seconds to milliseconds for storage
            val rangeStartMs = rangeStartSec.coerceAtLeast(0L) * 1000
            val rangeEndMs = if (rangeEndSec < 0) -1L else rangeEndSec * 1000

            viewModel.updateConfig(
                loopCount = loopCount.coerceAtLeast(1),
                rangeStartMs = rangeStartMs,
                rangeEndMs = rangeEndMs,
                autoAdvance = autoAdvance
            )

            // Reset saved state before saving
            viewModel.resetSavedState()
            viewModel.saveConfig()
        }
    }

    private fun setupObservers() {
        // Use repeatOnLifecycle(STARTED) so Flow collection pauses when the UI
        // is stopped and restarts when it's started again.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe config changes and update UI
                launch {
                    viewModel.config.collect { config ->
                        // Update loop mode dropdown without triggering the listener
                        val modeIndex = LoopMode.entries.indexOf(config.loopMode)
                        if ((binding.actvLoopMode as AutoCompleteTextView).text.toString() != loopModeLabels.getOrElse(modeIndex) { "" }) {
                            binding.actvLoopMode.setText(loopModeLabels.getOrElse(modeIndex) { "" }, false)
                        }

                        binding.etLoopCount.setText(config.loopCount.toString())

                        // Display in seconds (stored in ms internally)
                        binding.etRangeStart.setText(
                            if (config.rangeStartMs == 0L) "0" else (config.rangeStartMs / 1000).toString()
                        )
                        binding.etRangeEnd.setText(
                            if (config.rangeEndMs <= 0L) "" else (config.rangeEndMs / 1000).toString()
                        )

                        binding.switchAutoAdvance.isChecked = config.autoAdvance

                        if (config.startAction == StartAction.AUTO_PLAY) binding.rbAutoplay.isChecked = true
                        else binding.rbWait.isChecked = true

                        updateVisibilityForMode(config.loopMode)
                    }
                }

                // Observe save success
                launch {
                    viewModel.isSaved.collect { saved ->
                        if (saved) {
                            Timber.i("Settings saved successfully, navigating back")
                            showSnackbar(getString(R.string.settings_saved))
                            findNavController().navigateUp()
                        }
                    }
                }

                // Observe save errors
                launch {
                    viewModel.saveError.collect { errorMsg ->
                        errorMsg?.let {
                            Timber.w("Save error: %s", it)
                            showSnackbar(it) {
                                viewModel.clearSaveError()
                                viewModel.saveConfig()
                            }
                        }
                    }
                }

                // Observe loading state
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        binding.btnApply.isEnabled = !isLoading
                        if (isLoading) {
                            binding.btnApply.text = getString(R.string.loading)
                        } else {
                            binding.btnApply.text = getString(R.string.apply_settings)
                        }
                    }
                }
            }
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
        _binding = null
    }
}
