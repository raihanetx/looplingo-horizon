package com.looplingo.horizon.ui.settings

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.looplingo.horizon.R
import com.looplingo.horizon.data.remote.Segment
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.domain.model.SpeedPresets
import com.looplingo.horizon.core.TimeUtils
import com.looplingo.horizon.ui.settings.PlaybackUIHelper
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private fun Fragment.safeRequireView(): View? {
    return if (isAdded) view else null
}

@Singleton
class ConfigSetupManager @Inject constructor() {

    internal fun setupObservers(
        fragment: Fragment,
        binding: FragmentPlaybackSettingsBinding,
        viewModel: PlaybackSettingsViewModel,
        playbackUIHelper: PlaybackUIHelper,
        onConfigLoaded: (loopCount: Int, speedIndex: Int) -> Unit
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            viewModel.config.collect { config ->
                config?.let {
                    val loadedLoopCount = it.loopCount
                    val configSpeed = it.speed
                    val speedIndex = SpeedPresets.ALL.indexOfFirst { kotlin.math.abs(it.speed - configSpeed) < 0.001f }
                    if (speedIndex >= 0) {
                        binding.tvHeaderSpeed.text = SpeedPresets.ALL[speedIndex].label
                    }
                    onConfigLoaded(loadedLoopCount, speedIndex)
                }
            }
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isSaved.collect { saved ->
                if (saved) {
                    fragment.safeRequireView()?.let {
                        playbackUIHelper.showSnackbar(it, fragment.getString(R.string.settings_saved))
                    }
                }
            }
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            viewModel.saveError.collect { error ->
                error?.let {
                    fragment.safeRequireView()?.let { view ->
                        playbackUIHelper.showSnackbar(view, fragment.getString(R.string.error_save_failed))
                    }
                }
            }
        }
    }

    internal fun setupCleanTab(
        binding: FragmentPlaybackSettingsBinding,
        dialogueSegments: List<Segment>,
        onCycle: () -> Unit
    ) {
        binding.panelClean.setOnLongClickListener {
            if (dialogueSegments.isNotEmpty()) {
                onCycle()
            }
            true
        }
    }

    internal fun showDialogueOnClean(
        binding: FragmentPlaybackSettingsBinding,
        segments: List<Segment>,
        translatedTexts: Map<Int, String>,
        index: Int
    ) {
        val segment = segments[index]
        val translation = translatedTexts[segment.id]
        binding.ivCleanIcon.visibility = View.GONE
        binding.tvCleanTitle.visibility = View.GONE
        binding.tvCleanSubtitle.visibility = View.GONE
        binding.tvCleanEnglish.text = "[${TimeUtils.formatMsToTime(segment.startMs)}] ${segment.text}"
        binding.tvCleanEnglish.visibility = View.VISIBLE
        binding.tvCleanBangla.text = translation ?: ""
        binding.tvCleanBangla.visibility = if (translation != null) View.VISIBLE else View.GONE
    }

    internal fun switchTab(
        binding: FragmentPlaybackSettingsBinding,
        playbackUIHelper: PlaybackUIHelper,
        tab: Int,
        viewModel: PlaybackSettingsViewModel,
        onTabSwitched: () -> Unit
    ) {
        viewModel.setCurrentTab(tab)

        binding.panelClean.visibility = if (tab == PlaybackSettingsViewModel.TAB_CLEAN) View.VISIBLE else View.GONE
        binding.panelTalk.visibility = if (tab == PlaybackSettingsViewModel.TAB_TALK) View.VISIBLE else View.GONE
        binding.panelLoop.visibility = if (tab == PlaybackSettingsViewModel.TAB_LOOP) View.VISIBLE else View.GONE
        binding.panelNotes.visibility = if (tab == PlaybackSettingsViewModel.TAB_NOTES) View.VISIBLE else View.GONE

        playbackUIHelper.updateTabStyle(binding.tabCleanBtn, tab == PlaybackSettingsViewModel.TAB_CLEAN)
        playbackUIHelper.updateTabStyle(binding.tabTalkBtn, tab == PlaybackSettingsViewModel.TAB_TALK)
        playbackUIHelper.updateTabStyle(binding.tabLoopBtn, tab == PlaybackSettingsViewModel.TAB_LOOP)
        playbackUIHelper.updateTabStyle(binding.tabNotesBtn, tab == PlaybackSettingsViewModel.TAB_NOTES)

        onTabSwitched()
    }
}
