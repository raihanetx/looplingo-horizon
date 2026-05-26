package com.looplingo.horizon.ui.settings

import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.ui.settings.PlaybackSettingsViewModel
import com.looplingo.horizon.domain.audio.service.AudioPlaybackService
import com.looplingo.horizon.core.TimeUtils
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackUIHelper @Inject constructor() {

    internal fun setupHeader(
        binding: FragmentPlaybackSettingsBinding,
        onBackClick: () -> Unit,
        onSpeedClick: () -> Unit,
        onSubtitleClick: () -> Unit,
        subtitleGenerated: Boolean
    ) {
        binding.ivHeaderAvatar.setOnClickListener { onBackClick() }
        binding.tvHeaderSpeed.setOnClickListener { onSpeedClick() }
        binding.ivSendSubtitles.setOnClickListener { onSubtitleClick() }
        if (subtitleGenerated) {
            binding.ivSendSubtitles.visibility = View.GONE
        }
    }

    internal fun setupTabNavigation(
        binding: FragmentPlaybackSettingsBinding,
        onTabClick: (Int) -> Unit
    ) {
        binding.tabCleanBtn.setOnClickListener { onTabClick(PlaybackSettingsViewModel.TAB_CLEAN) }
        binding.tabTalkBtn.setOnClickListener { onTabClick(PlaybackSettingsViewModel.TAB_TALK) }
        binding.tabLoopBtn.setOnClickListener { onTabClick(PlaybackSettingsViewModel.TAB_LOOP) }
        binding.tabNotesBtn.setOnClickListener { onTabClick(PlaybackSettingsViewModel.TAB_NOTES) }
    }

    internal fun updateTabStyle(tabLayout: LinearLayout, isSelected: Boolean) {
        val context = tabLayout.context
        val iconView = tabLayout.getChildAt(0) as ImageView
        val textView = tabLayout.getChildAt(1) as TextView
        val resources = context.resources

        if (isSelected) {
            tabLayout.background = resources.getDrawable(R.drawable.bg_tab_indicator, null)
            iconView.imageTintList = resources.getColorStateList(R.color.colorOnPrimaryContainer, null)
            textView.setTextColor(resources.getColor(R.color.colorOnPrimaryContainer, null))
        } else {
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            tabLayout.background = resources.getDrawable(tv.resourceId, context.theme)
            iconView.imageTintList = resources.getColorStateList(R.color.colorOnSurfaceVariant, null)
            textView.setTextColor(resources.getColor(R.color.colorOnSurfaceVariant, null))
        }
    }

    internal fun setupTransportControls(
        binding: FragmentPlaybackSettingsBinding,
        onPlayPause: () -> Unit,
        onRewind: () -> Unit,
        onForward: () -> Unit
    ) {
        binding.ivPlayPause.setOnClickListener { onPlayPause() }
        binding.ivRewind5.setOnClickListener { onRewind() }
        binding.ivForward5.setOnClickListener { onForward() }
    }

    internal fun setupSeekBar(
        binding: FragmentPlaybackSettingsBinding,
        onSeek: (Long) -> Unit
    ) {
        binding.waveformSeekBar.onSeekListener = { progress ->
            val duration = AudioPlaybackService.durationMs
            if (duration > 0) {
                val newPos = (progress.toLong() * duration) / 1000
                binding.tvCurrentPosition.text = TimeUtils.formatMsToTime(newPos)
                onSeek(newPos)
            }
        }
    }

    internal fun setupLoopControls(
        binding: FragmentPlaybackSettingsBinding,
        initialLoopCount: Int,
        onLoopMinus: (Int) -> Unit,
        onLoopPlus: (Int) -> Unit
    ) {
        binding.tvLoopCount.text = initialLoopCount.toString()

        binding.btnLoopMinus.setOnClickListener { onLoopMinus(initialLoopCount) }
        binding.btnLoopPlus.setOnClickListener { onLoopPlus(initialLoopCount) }
    }

    internal fun setupNowPlayingCard(
        binding: FragmentPlaybackSettingsBinding,
        title: String,
        subtitle: String
    ) {
        binding.tvNowPlayingTitle.text = title
        binding.tvNowPlayingSubtitle.text = subtitle
        binding.tvCleanTitle.text = title
        binding.tvCurrentPosition.text = "0:00"
        binding.tvDuration.text = "0:00"
    }

    internal fun updateNowPlayingState(
        binding: FragmentPlaybackSettingsBinding,
        isPlaying: Boolean,
        title: String,
        currentPositionMs: Long,
        durationMs: Long,
        waveformProgress: Int
    ) {
        binding.ivPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        binding.tvNowPlayingTitle.text = title
        binding.tvCleanTitle.text = title

        binding.tvCurrentPosition.text = TimeUtils.formatMsToTime(currentPositionMs)
        binding.tvDuration.text = if (durationMs > 0) TimeUtils.formatMsToTime(durationMs) else "0:00"

        if (durationMs > 0) {
            binding.waveformSeekBar.progress = waveformProgress
        }
    }

    internal fun showSnackbar(view: View, message: String) {
        showSnackbar(view, message, fullErrorDetail = null)
    }

    internal fun showSnackbar(view: View, message: String, fullErrorDetail: String?) {
        val context = view.context
        val snackbar = Snackbar.make(view, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(context.resources.getColor(R.color.colorInverseSurface, null))
            .setTextColor(context.resources.getColor(R.color.colorInverseOnSurface, null))

        if (message.startsWith("ERROR") || message.startsWith("FAILED") || message.startsWith("WARNING")) {
            val copyText = fullErrorDetail ?: message
            snackbar.setAction("COPY") {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Error Log", copyText)
                clipboard.setPrimaryClip(clip)
            }
            snackbar.duration = Snackbar.LENGTH_INDEFINITE
            snackbar.view.post {
                val textView = snackbar.view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
                textView.maxLines = 10
            }
        }

        snackbar.show()
    }
}
