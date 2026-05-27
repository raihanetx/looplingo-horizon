package com.looplingo.horizon.ui.settings

import androidx.recyclerview.widget.LinearLayoutManager
import com.looplingo.horizon.R
import com.looplingo.horizon.data.remote.Segment
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.domain.audio.service.AudioPlaybackService
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
        onSegmentClick: (Segment, Int) -> Unit
    ): DialogueAdapter {
        binding.rvDialogueList.visibility = android.view.View.VISIBLE
        val adapter = DialogueAdapter(segments, translatedTexts) { segment, index ->
            onSegmentClick(segment, index)
        }
        binding.rvDialogueList.apply {
            layoutManager = LinearLayoutManager(binding.root.context)
            this.adapter = adapter
        }
        return adapter
    }

    fun onDialogueSegmentSelected(
        binding: FragmentPlaybackSettingsBinding,
        playbackUIHelper: PlaybackUIHelper,
        videoPath: String,
        segment: Segment,
        index: Int,
        adapter: DialogueAdapter?
    ) {
        // Clicking the already-active segment toggles it off
        if (adapter?.isActivePosition(index) == true) {
            adapter.setActivePosition(-1)
            playbackUIHelper.showSnackbar(
                binding.root,
                binding.root.context.getString(R.string.dialogue_deselected)
            )
            return
        }

        // Always seek and play from this segment
        AudioPlaybackService.seekToPosition(binding.root.context, videoPath, segment.startMs)

        adapter?.setActivePosition(index)
        binding.rvDialogueList.smoothScrollToPosition(index)

        playbackUIHelper.showSnackbar(
            binding.root,
            binding.root.context.getString(R.string.dialogue_selected, segment.text.take(30))
        )
    }
}
