package com.looplingo.horizon.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.FragmentMainBinding
import com.looplingo.horizon.domain.audio.service.AudioPlaybackService
import com.looplingo.horizon.ui.common.adapter.VideoAdapter
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MainFragmentHelper @Inject constructor(
    private val transcriptRepository: com.looplingo.horizon.data.repository.TranscriptRepository
) {

    internal fun requestNotificationPermissionIfNeeded(
        fragment: Fragment,
        notificationPermissionLauncher: ActivityResultLauncher<String>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    fragment.requireContext(), Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Timber.d("Requesting POST_NOTIFICATIONS permission")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    internal fun setupRecyclerView(
        fragment: Fragment,
        binding: FragmentMainBinding,
        viewModel: MainViewModel,
        notificationPermissionLauncher: ActivityResultLauncher<String>
    ): VideoAdapter {
        lateinit var videoAdapter: VideoAdapter
        videoAdapter = VideoAdapter(
            onVideoClick = { video ->
                Timber.d("Video clicked: %s", video.title)
                try {
                    requestNotificationPermissionIfNeeded(fragment, notificationPermissionLauncher)
                    val action = MainFragmentDirections.actionMainToPlaybackSettings(video.path, "")
                    fragment.findNavController().navigate(action)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to navigate for: %s", video.title)
                    Snackbar.make(
                        binding.root,
                        fragment.getString(R.string.error_starting_playback),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            },
            onVideoLongClick = { video ->
                val current = videoAdapter.selectedPath
                videoAdapter.selectedPath = if (current == video.path) null else video.path
            }
        )
        val adapter = videoAdapter
        binding.rvVideoList.layoutManager = LinearLayoutManager(fragment.requireContext())
        binding.rvVideoList.adapter = adapter
        binding.rvVideoList.setHasFixedSize(false)
        binding.rvVideoList.setRecycledViewPool(
            androidx.recyclerview.widget.RecyclerView.RecycledViewPool().apply {
                setMaxRecycledViews(0, 20)
            }
        )
        binding.rvVideoList.itemAnimator?.apply {
            addDuration = 200
            removeDuration = 200
        }
        return adapter
    }

    internal fun setupSettingsButton(binding: FragmentMainBinding, viewModel: MainViewModel) {
        binding.btnEmptyRetry.setOnClickListener {
            viewModel.refreshVideos()
        }
    }

    internal fun setupObservers(
        fragment: Fragment,
        binding: FragmentMainBinding,
        viewModel: MainViewModel,
        videoAdapter: VideoAdapter
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.videos.collect { videoList ->
                        videoAdapter.submitList(videoList)
                        showEmptyState(binding, videoList.isEmpty())
                        launch {
                            videoAdapter.videosLoadingSubtitles = videoList.map { it.path }.toSet()
                            val videosWithSubs = mutableSetOf<String>()
                            for (video in videoList) {
                                try {
                                    if (transcriptRepository.hasSubtitlesAsync(video.path)) {
                                        videosWithSubs.add(video.path)
                                    }
                                } catch (e: Exception) {
                                    Timber.w(e, "Failed to check subtitles for: %s", video.title)
                                }
                            }
                            videoAdapter.videosWithSubtitles = videosWithSubs
                            videoAdapter.videosLoadingSubtitles = emptySet()
                        }
                    }
                }
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        if (isLoading) {
                            if (!binding.swipeRefresh.isRefreshing) {
                                binding.progressBar.visibility = View.VISIBLE
                            }
                        } else {
                            binding.progressBar.visibility = View.GONE
                            binding.swipeRefresh.isRefreshing = false
                        }
                    }
                }
                launch {
                    viewModel.error.collect { errorMsg ->
                        errorMsg?.let {
                            binding.tvEmpty.text = it
                            showEmptyState(binding, isEmpty = true)
                            fragment.view?.let { rootView ->
                                Snackbar.make(rootView, it, Snackbar.LENGTH_LONG)
                                    .setBackgroundTint(
                                        fragment.resources.getColor(R.color.colorInverseSurface, null)
                                    )
                                    .setTextColor(
                                        fragment.resources.getColor(R.color.colorInverseOnSurface, null)
                                    )
                                    .setActionTextColor(
                                        fragment.resources.getColor(R.color.colorInversePrimary, null)
                                    )
                                    .setAction(fragment.getString(R.string.retry)) {
                                        viewModel.refreshVideos()
                                    }
                                    .show()
                            }
                        }
                    }
                }
                launch {
                    viewModel.configuredModes.collect { modes ->
                        videoAdapter.configuredModes = modes
                    }
                }
            }
        }
    }

    internal fun showEmptyState(
        binding: FragmentMainBinding,
        isEmpty: Boolean,
        isPermError: Boolean = false
    ) {
        if (isEmpty) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.rvVideoList.visibility = View.GONE
            binding.btnEmptyRetry.visibility = if (isPermError) View.VISIBLE else View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.rvVideoList.visibility = View.VISIBLE
            binding.btnEmptyRetry.visibility = View.GONE
        }
    }

    internal fun setupSearchView(binding: FragmentMainBinding, viewModel: MainViewModel) {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
        })
        binding.ivClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
            viewModel.clearSearch()
        }
    }
}
