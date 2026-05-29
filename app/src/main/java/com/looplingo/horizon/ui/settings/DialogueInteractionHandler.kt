package com.looplingo.horizon.ui.settings

import android.view.View
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
class DialogueInteractionHandler @Inject constructor(
    private val playbackUIHelper: PlaybackUIHelper
) {

    fun showDialogueList(
        binding: FragmentPlaybackSettingsBinding,
        segments: List<Segment>,
        translatedTexts: Map<Int, String>,
        onSegmentClick: (Segment, Int) -> Unit
    ): DialogueAdapter {
        playbackUIHelper.updateTalkEmptyState(binding, segments.isNotEmpty())
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

    fun updatePlaySelectedButton(
        binding: FragmentPlaybackSettingsBinding,
        adapter: DialogueAdapter?
    ) {
        val selectedCount = adapter?.getSelectedCount() ?: 0
        binding.btnPlaySelected.visibility = if (selectedCount >= 2) View.VISIBLE else View.GONE
        if (selectedCount >= 2) {
            binding.btnPlaySelected.text = "Play Selected ($selectedCount)"
        }
    }

    fun playSelectedDialogues(
        binding: FragmentPlaybackSettingsBinding,
        videoPath: String,
        adapter: DialogueAdapter?,
        loopCount: Int = 3
    ) {
        val indices = adapter?.getSelectedIndices() ?: return
        if (indices.size < 2) return

        val segments = adapter.getSegments()
        val selectedSegments = indices.map { segments[it] }.sortedBy { it.startMs }

        val rangeStartMs = selectedSegments.first().startMs.toLong()
        val rangeEndMs = selectedSegments.last().endMs.toLong()

        AudioPlaybackService.setABLoop(
            binding.root.context,
            videoPath,
            rangeStartMs,
            rangeEndMs,
            loopCount
        )

        adapter.clearSelection()
        updatePlaySelectedButton(binding, adapter)

        playbackUIHelper.showSnackbar(
            binding.root,
            "Playing ${selectedSegments.size} dialogues in loop"
        )
    }
}
