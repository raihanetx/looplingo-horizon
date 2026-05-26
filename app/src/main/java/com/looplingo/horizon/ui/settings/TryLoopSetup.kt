package com.looplingo.horizon.ui.settings

import android.os.Handler
import android.os.Looper
import android.view.View
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.domain.audio.service.AudioPlaybackService
import com.looplingo.horizon.core.TimeUtils
import com.looplingo.horizon.ui.settings.PlaybackUIHelper
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TryLoopSetup @Inject constructor() {

    internal fun setupTryLoopButton(
        binding: FragmentPlaybackSettingsBinding,
        playbackUIHelper: PlaybackUIHelper,
        videoPath: String,
        getLoopCount: () -> Int,
        parseTimeToMs: (String) -> Long,
        onError: () -> Unit
    ) {
        binding.btnTryLoop.setOnClickListener {
            val rangeStartMs = parseTimeToMs(binding.etRangeStart.text.toString())
            val rangeEndMs = parseTimeToMs(binding.etRangeEnd.text.toString())
            val effectiveLoopCount = getLoopCount()

            var hasError = false
            if (rangeEndMs > 0 && rangeEndMs <= rangeStartMs) {
                binding.tilRangeEnd.error = binding.root.context.getString(R.string.error_range_end_before_start)
                hasError = true
            } else {
                binding.tilRangeEnd.error = null
            }

            if (effectiveLoopCount < 1) {
                playbackUIHelper.showSnackbar(binding.root, binding.root.context.getString(R.string.error_loop_count_minimum))
                hasError = true
            }

            if (hasError) {
                onError()
                return@setOnClickListener
            }

            val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
                AudioPlaybackService.currentVideoPath == videoPath

            if (isCurrentlyPlaying) {
                AudioPlaybackService.setABLoop(
                    binding.root.context, videoPath, rangeStartMs,
                    if (binding.etRangeEnd.text.isNullOrBlank()) -1L else rangeEndMs, effectiveLoopCount
                )
            } else {
                AudioPlaybackService.startService(binding.root.context, videoPath)
                Handler(Looper.getMainLooper()).postDelayed({
                    AudioPlaybackService.setABLoop(
                        binding.root.context, videoPath, rangeStartMs,
                        if (binding.etRangeEnd.text.isNullOrBlank()) -1L else rangeEndMs, effectiveLoopCount
                    )
                }, 1000)
            }
            playbackUIHelper.showSnackbar(binding.root, binding.root.context.getString(R.string.loop_preview_active))
        }
    }
}
