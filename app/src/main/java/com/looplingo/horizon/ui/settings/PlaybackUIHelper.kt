package com.looplingo.horizon.ui.settings

import android.view.View
import android.view.animation.AnimationUtils
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
        onAudioModeClick: () -> Unit,
        subtitleGenerated: Boolean
    ) {
        binding.ivHeaderAvatar.setOnClickListener { onBackClick() }
        binding.tvHeaderSpeed.setOnClickListener { onSpeedClick() }
        binding.ivSendSubtitles.setOnClickListener { onSubtitleClick() }
        binding.tvAudioMode.setOnClickListener { onAudioModeClick() }
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
        val textView = tabLayout.getChildAt(0) as TextView
        val resources = context.resources

        if (isSelected) {
            tabLayout.background = null
            textView.setTextColor(resources.getColor(R.color.colorPrimary, null))
        } else {
            tabLayout.background = null
            textView.setTextColor(resources.getColor(R.color.colorOnSurfaceVariant, null))
        }
    }

    internal fun setupTransportControls(
        binding: FragmentPlaybackSettingsBinding,
        onPlayPause: () -> Unit,
        onRewind: () -> Unit,
        onForward: () -> Unit
    ) {
        binding.btnPlayPause.setOnClickListener { onPlayPause() }
        binding.btnRewind5.setOnClickListener { onRewind() }
        binding.btnForward5.setOnClickListener { onForward() }
    }

    internal fun setupSeekBar(
        binding: FragmentPlaybackSettingsBinding,
        onSeek: (Long) -> Unit
    ) {
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = AudioPlaybackService.durationMs
                    if (duration > 0) {
                        val newPos = (progress.toLong() * duration) / 1000
                        binding.tvCurrentPosition.text = TimeUtils.formatMsToTime(newPos)
                        onSeek(newPos)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    internal fun setupLoopForm(
        binding: FragmentPlaybackSettingsBinding,
        onPreview: () -> Unit,
        onSave: () -> Unit
    ) {
        binding.fabAddLoop.setOnClickListener {
            showLoopFormAnimated(binding)
        }
        binding.btnLoopPreview.setOnClickListener { onPreview() }
        binding.btnSaveLoop.setOnClickListener { onSave() }
    }

    internal fun setupNoteForm(
        binding: FragmentPlaybackSettingsBinding,
        onSave: () -> Unit
    ) {
        binding.fabAddNote.setOnClickListener {
            showNoteFormAnimated(binding)
        }
        binding.btnSaveNote.setOnClickListener { onSave() }
    }

    internal fun showLoopFormAnimated(binding: FragmentPlaybackSettingsBinding) {
        binding.fabAddLoop.visibility = View.GONE
        binding.layoutAddLoopForm.visibility = View.VISIBLE
        val slideDown = AnimationUtils.loadAnimation(binding.root.context, R.anim.slide_down)
        binding.layoutAddLoopForm.startAnimation(slideDown)
    }

    internal fun hideLoopFormAnimated(binding: FragmentPlaybackSettingsBinding) {
        val slideUp = AnimationUtils.loadAnimation(binding.root.context, R.anim.slide_up)
        slideUp.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                binding.layoutAddLoopForm.visibility = View.GONE
                binding.fabAddLoop.visibility = View.VISIBLE
            }
        })
        binding.layoutAddLoopForm.startAnimation(slideUp)
    }

    internal fun showLoopForm(binding: FragmentPlaybackSettingsBinding) {
        showLoopFormAnimated(binding)
    }

    internal fun hideLoopForm(binding: FragmentPlaybackSettingsBinding) {
        hideLoopFormAnimated(binding)
    }

    internal fun showNoteFormAnimated(binding: FragmentPlaybackSettingsBinding) {
        binding.fabAddNote.visibility = View.GONE
        binding.layoutAddNoteForm.visibility = View.VISIBLE
        val slideDown = AnimationUtils.loadAnimation(binding.root.context, R.anim.slide_down)
        binding.layoutAddNoteForm.startAnimation(slideDown)
    }

    internal fun hideNoteFormAnimated(binding: FragmentPlaybackSettingsBinding) {
        val slideUp = AnimationUtils.loadAnimation(binding.root.context, R.anim.slide_up)
        slideUp.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                binding.layoutAddNoteForm.visibility = View.GONE
                binding.fabAddNote.visibility = View.VISIBLE
            }
        })
        binding.layoutAddNoteForm.startAnimation(slideUp)
    }

    internal fun showNoteForm(binding: FragmentPlaybackSettingsBinding) {
        showNoteFormAnimated(binding)
    }

    internal fun hideNoteForm(binding: FragmentPlaybackSettingsBinding) {
        hideNoteFormAnimated(binding)
    }

    internal fun updateLoopEmptyState(binding: FragmentPlaybackSettingsBinding, hasLoops: Boolean) {
        binding.layoutLoopEmpty.visibility = if (hasLoops) View.GONE else View.VISIBLE
        binding.rvLoopList.visibility = if (hasLoops) View.VISIBLE else View.GONE
    }

    internal fun updateNoteEmptyState(binding: FragmentPlaybackSettingsBinding, hasNotes: Boolean) {
        binding.layoutNotesEmpty.visibility = if (hasNotes) View.GONE else View.VISIBLE
        binding.rvNotesList.visibility = if (hasNotes) View.VISIBLE else View.GONE
    }

    internal fun updateTalkEmptyState(binding: FragmentPlaybackSettingsBinding, hasDialogue: Boolean) {
        binding.layoutTalkEmpty.visibility = if (hasDialogue) View.GONE else View.VISIBLE
        binding.rvDialogueList.visibility = if (hasDialogue) View.VISIBLE else View.GONE
    }

    internal fun showProcessing(binding: FragmentPlaybackSettingsBinding) {
        binding.ivCleanIcon.visibility = View.GONE
        binding.tvCleanTitle.visibility = View.GONE
        binding.tvCleanEnglish.visibility = View.GONE
        binding.tvCleanBangla.visibility = View.GONE
        binding.layoutProcessing.visibility = View.VISIBLE
    }

    internal fun hideProcessing(binding: FragmentPlaybackSettingsBinding) {
        binding.layoutProcessing.visibility = View.GONE
    }

    internal fun updateAudioMode(binding: FragmentPlaybackSettingsBinding, isAudioOnly: Boolean) {
        val context = binding.tvAudioMode.context
        if (isAudioOnly) {
            binding.tvAudioMode.text = "Audio On"
            binding.tvAudioMode.setTextColor(context.resources.getColor(R.color.colorOnPrimaryContainer, null))
            binding.tvAudioMode.setBackgroundResource(R.drawable.bg_audio_mode_active)
            binding.tvAudioMode.setPadding(20, 4, 20, 4)
            binding.ivCleanIcon.setImageResource(R.drawable.ic_music_note)
            binding.ivCleanIcon.visibility = View.VISIBLE
            binding.tvCleanTitle.visibility = View.GONE
            binding.tvCleanEnglish.visibility = View.GONE
            binding.tvCleanBangla.visibility = View.GONE
            binding.layoutProcessing.visibility = View.GONE
        } else {
            binding.tvAudioMode.text = "Audio"
            binding.tvAudioMode.setTextColor(context.resources.getColor(R.color.colorOnSurfaceVariant, null))
            binding.tvAudioMode.background = null
            binding.tvAudioMode.setPadding(0, 0, 0, 0)
            binding.ivCleanIcon.visibility = View.GONE
            binding.tvCleanTitle.visibility = View.VISIBLE
            binding.tvCleanEnglish.visibility = View.GONE
            binding.tvCleanBangla.visibility = View.GONE
        }
    }

    internal fun setupNowPlayingCard(
        binding: FragmentPlaybackSettingsBinding,
        title: String,
        subtitle: String
    ) {
        binding.tvNowPlayingTitle.text = title
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
        binding.btnPlayPause.text = if (isPlaying) "Pause" else "Play"

        binding.tvNowPlayingTitle.text = title
        binding.tvCleanTitle.text = title

        binding.tvCurrentPosition.text = TimeUtils.formatMsToTime(currentPositionMs)
        binding.tvDuration.text = if (durationMs > 0) TimeUtils.formatMsToTime(durationMs) else "0:00"

        if (durationMs > 0) {
            binding.seekBar.progress = waveformProgress
        }
    }

    internal fun updatePlayerInfoLine(
        binding: FragmentPlaybackSettingsBinding,
        tabName: String,
        loopCount: Int,
        speedLabel: String,
        isInLoopMode: Boolean,
        isAudioOnly: Boolean
    ) {
        val loopText = if (isInLoopMode) "Loop:$loopCount" else "Loop:None"
        val audioText = if (isAudioOnly) "Audio:On" else "Audio:Off"
        binding.tvPlayerInfoLine.text = "Mode:$tabName | $loopText | Speed:$speedLabel | $audioText"
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
