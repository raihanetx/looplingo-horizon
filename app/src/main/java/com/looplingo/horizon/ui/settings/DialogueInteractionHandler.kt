package com.looplingo.horizon.ui.settings

import androidx.recyclerview.widget.LinearLayoutManager
import com.looplingo.horizon.R
import com.looplingo.horizon.data.remote.Segment
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.domain.audio.service.AudioPlaybackService
import com.looplingo.horizon.core.TimeUtils
import com.looplingo.horizon.ui.settings.PlaybackUIHelper
import com.looplingo.horizon.ui.settings.DialogueAdapter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DialogueInteractionHandler @Inject constructor() {

    fun showDialogueList(
        binding: FragmentPlaybackSettingsBinding,
        segments: List<Segment>,
        translatedTexts: Map<Int, String>,
        onSegmentSelected: (Segment, Int) -> Unit
    ) {
        binding.rvDialogueList.visibility = android.view.View.VISIBLE
        binding.rvDialogueList.apply {
            layoutManager = LinearLayoutManager(binding.root.context)
            adapter = DialogueAdapter(segments, translatedTexts) { segment, index ->
                onSegmentSelected(segment, index)
            }
        }
    }

    fun onDialogueSegmentSelected(
        binding: FragmentPlaybackSettingsBinding,
        playbackUIHelper: PlaybackUIHelper,
        videoPath: String,
        segment: Segment
    ) {
        binding.etRangeStart.setText(TimeUtils.formatMsToTime(segment.startMs))
        binding.etRangeEnd.setText(TimeUtils.formatMsToTime(segment.endMs))

        val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
            AudioPlaybackService.currentVideoPath == videoPath

        if (isCurrentlyPlaying) {
            AudioPlaybackService.seekToPosition(binding.root.context, videoPath, segment.startMs)
        }

        playbackUIHelper.showSnackbar(
            binding.root,
            binding.root.context.getString(R.string.dialogue_selected, segment.text.take(30))
        )
    }
}
