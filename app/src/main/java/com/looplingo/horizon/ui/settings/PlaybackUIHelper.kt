package com.looplingo.horizon.ui.settings

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.DialogAddLoopBinding
import com.looplingo.horizon.databinding.DialogAddNoteBinding
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
        binding.btnLoopPreview.setOnClickListener { onPreview() }
        binding.btnSaveLoop.setOnClickListener { onSave() }
    }

    internal fun setupNoteForm(
        binding: FragmentPlaybackSettingsBinding,
        onSave: () -> Unit
    ) {
        binding.btnSaveNote.setOnClickListener { onSave() }
    }

    internal fun clearLoopForm(binding: FragmentPlaybackSettingsBinding) {
        binding.etLoopName.setText("")
        binding.etLoopStart.setText("0:00")
        binding.etLoopEnd.setText("")
        binding.etLoopCount.setText("3")
        binding.etLoopName.error = null
        binding.etLoopStart.error = null
        binding.etLoopEnd.error = null
    }

    internal fun clearNoteForm(binding: FragmentPlaybackSettingsBinding) {
        binding.etNoteText.setText("")
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
            binding.layoutInitialLoading.visibility = View.GONE
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
            binding.layoutInitialLoading.visibility = View.GONE
            binding.ivCleanIcon.visibility = View.GONE
            binding.tvCleanTitle.visibility = View.VISIBLE
            binding.tvCleanEnglish.visibility = View.GONE
            binding.tvCleanBangla.visibility = View.GONE
        }
    }

    internal fun hideInitialLoading(binding: FragmentPlaybackSettingsBinding) {
        binding.layoutInitialLoading.visibility = View.GONE
        binding.tvCleanTitle.visibility = View.VISIBLE
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
        // Hide initial loading when now playing card is set up
        hideInitialLoading(binding)
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

    internal fun showAddLoopDialog(
        context: android.content.Context,
        currentTimeMs: Long,
        onSave: (name: String, startMs: Long, endMs: Long, loopCount: Int) -> Unit
    ) {
        val dialogBinding = DialogAddLoopBinding.inflate(
            android.view.LayoutInflater.from(context)
        )
        dialogBinding.etDialogLoopStart.setText(TimeUtils.formatMsToTime(currentTimeMs))
        dialogBinding.etDialogLoopEnd.setText(TimeUtils.formatMsToTime(currentTimeMs + 10000))

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogBinding.root)
            .create()

        // Center the dialog
        dialog.window?.setGravity(android.view.Gravity.CENTER)

        dialogBinding.btnDialogLoopClose.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnDialogLoopCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnDialogLoopPreview.setOnClickListener {
            val startMs = TimeUtils.parseTimeToMs(dialogBinding.etDialogLoopStart.text.toString())
            val endMs = TimeUtils.parseTimeToMs(dialogBinding.etDialogLoopEnd.text.toString())
            if (startMs >= 0 && endMs > startMs) {
                showSnackbar(dialogBinding.root, "Preview: ${TimeUtils.formatMsToTime(startMs)} — ${TimeUtils.formatMsToTime(endMs)}")
            } else {
                showSnackbar(dialogBinding.root, "Enter valid start and end times")
            }
        }

        dialogBinding.btnDialogLoopEdit.setOnClickListener {
            // Edit functionality - fields are already editable
            showSnackbar(dialogBinding.root, "Edit the fields above")
        }

        dialogBinding.btnDialogLoopSave.setOnClickListener {
            val name = dialogBinding.etDialogLoopName.text.toString().trim()
            val startText = dialogBinding.etDialogLoopStart.text.toString().trim()
            val endText = dialogBinding.etDialogLoopEnd.text.toString().trim()
            val countText = dialogBinding.etDialogLoopCount.text.toString().trim()

            if (name.isEmpty()) {
                dialogBinding.etDialogLoopName.error = "Required"
                return@setOnClickListener
            }

            val startMs = TimeUtils.parseTimeToMs(startText)
            val endMs = TimeUtils.parseTimeToMs(endText)
            val loopCount = countText.toIntOrNull() ?: 3

            if (startMs < 0 || endMs < 0 || endMs <= startMs) {
                dialogBinding.etDialogLoopStart.error = "Invalid"
                dialogBinding.etDialogLoopEnd.error = "Invalid"
                return@setOnClickListener
            }

            onSave(name, startMs, endMs, loopCount)
            dialog.dismiss()
        }

        dialog.show()
    }

    internal fun showAddNoteDialog(
        context: android.content.Context,
        onSave: (text: String) -> Unit
    ) {
        val dialogBinding = DialogAddNoteBinding.inflate(
            android.view.LayoutInflater.from(context)
        )

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogBinding.root)
            .create()

        // Center the dialog
        dialog.window?.setGravity(android.view.Gravity.CENTER)

        dialogBinding.btnDialogNoteClose.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnDialogNoteCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnDialogNoteSave.setOnClickListener {
            val text = dialogBinding.etDialogNoteText.text.toString().trim()

            if (text.isEmpty()) {
                dialogBinding.etDialogNoteText.error = "Required"
                return@setOnClickListener
            }

            onSave(text)
            dialog.dismiss()
        }

        dialog.show()
    }

    internal fun setupAddLoopButton(
        binding: FragmentPlaybackSettingsBinding,
        onAddClick: () -> Unit
    ) {
        binding.btnAddLoop.setOnClickListener { onAddClick() }
    }

    internal fun setupAddNoteButton(
        binding: FragmentPlaybackSettingsBinding,
        onAddClick: () -> Unit
    ) {
        binding.btnAddNote.setOnClickListener { onAddClick() }
    }
}
