package com.looplingo.horizon.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.FragmentMainBinding
import com.looplingo.horizon.playback.AudioPlaybackService
import com.looplingo.horizon.model.SortOrder
import com.looplingo.horizon.ui.adapter.VideoAdapter
import com.looplingo.horizon.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main screen showing the list of video files found on the device.
 *
 * Features:
 *  - Requests storage permission and scans for videos on first launch
 *  - Click a video to start audio playback via [AudioPlaybackService]
 *  - Long-press a video to open its playback settings
 *  - Pull-to-refresh to rescan the media library
 *  - Shows loading, empty, and error states with user feedback
 *  - Toolbar with sort and stop-playback actions
 */
@AndroidEntryPoint
class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()

    private lateinit var videoAdapter: VideoAdapter

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Timber.i("Storage permission granted — scanning videos")
            viewModel.refreshVideos()
        } else {
            Timber.w("Storage permission denied by user")
            showPermissionDenied()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Timber.i("Notification permission granted — media controls will be visible")
        } else {
            Timber.w("Notification permission denied — media controls won't appear in notification shade")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupObservers()
        setupSettingsButton()
        checkPermissionsAndScan()
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_sort -> {
                    Timber.d("Sort action clicked")
                    showSortDialog()
                    true
                }
                R.id.action_stop_playback -> {
                    Timber.d("Stop playback action clicked")
                    AudioPlaybackService.stopService(requireContext())
                    true
                }
                else -> false
            }
        }
    }

    private fun showSortDialog() {
        val sortOptions = arrayOf(
            getString(R.string.sort_by_date),
            getString(R.string.sort_by_title),
            getString(R.string.sort_by_duration),
            getString(R.string.sort_by_size)
        )
        val current = viewModel.sortOrder.value
        val checkedItem = when (current) {
            SortOrder.DATE -> 0
            SortOrder.TITLE -> 1
            SortOrder.DURATION -> 2
            SortOrder.SIZE -> 3
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.sort_by))
            .setSingleChoiceItems(sortOptions, checkedItem) { dialog, which ->
                val order = when (which) {
                    0 -> SortOrder.DATE
                    1 -> SortOrder.TITLE
                    2 -> SortOrder.DURATION
                    3 -> SortOrder.SIZE
                    else -> SortOrder.DATE
                }
                viewModel.setSortOrder(order)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter { video ->
            Timber.d("Video clicked: %s", video.title)
            try {
                AudioPlaybackService.startService(requireContext(), video.path)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start playback service for: %s", video.title)
                showSnackbar(getString(R.string.error_starting_playback))
            }
        }
        binding.rvVideoList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVideoList.adapter = videoAdapter

        // Performance: set fixed size for items to avoid unnecessary measure passes
        binding.rvVideoList.setHasFixedSize(false)  // Items have variable height due to path text

        // Performance: increase RecycledViewPool for smoother scrolling
        binding.rvVideoList.setRecycledViewPool(
            androidx.recyclerview.widget.RecyclerView.RecycledViewPool().apply {
                setMaxRecycledViews(0, 20)  // 20 cached ViewHolders for type 0
            }
        )

        // Item animations for smooth insert/remove
        binding.rvVideoList.itemAnimator?.apply {
            addDuration = 200
            removeDuration = 200
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.colorPrimary),
            ContextCompat.getColor(requireContext(), R.color.colorTertiary)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.colorSurfaceContainerHighest)
        )
        binding.swipeRefresh.setOnRefreshListener {
            Timber.d("Pull-to-refresh triggered")
            viewModel.refreshVideos()
        }
    }

    private fun setupSettingsButton() {
        // Long-press on a video navigates to its playback settings
        videoAdapter.onVideoLongClick = { video ->
            try {
                val action = MainFragmentDirections.actionMainToPlaybackSettings(video.path)
                findNavController().navigate(action)
            } catch (e: Exception) {
                Timber.e(e, "Failed to navigate to settings for: %s", video.title)
                showSnackbar(getString(R.string.error_navigation))
            }
        }

        // Empty state retry button
        binding.btnEmptyRetry.setOnClickListener {
            viewModel.refreshVideos()
        }
    }

    private fun setupObservers() {
        // Use repeatOnLifecycle(STARTED) so Flow collection pauses when the UI
        // is stopped and restarts when it's started again. This avoids delivering
        // updates to a stopped UI and prevents unnecessary background work.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Launch all collectors in the STARTED scope so they are
                // automatically cancelled and restarted with the lifecycle.

                // Observe video list
                launch {
                    viewModel.videos.collect { videoList ->
                        videoAdapter.submitList(videoList)
                        updateEmptyState(videoList.isEmpty(), isPermError = false)
                    }
                }

                // Observe loading state
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        if (isLoading) {
                            // If not triggered by swipe refresh, show the centered progress bar
                            if (!binding.swipeRefresh.isRefreshing) {
                                binding.progressBar.visibility = View.VISIBLE
                            }
                        } else {
                            binding.progressBar.visibility = View.GONE
                            binding.swipeRefresh.isRefreshing = false
                        }
                    }
                }

                // Observe error state
                launch {
                    viewModel.error.collect { errorMsg ->
                        errorMsg?.let {
                            binding.tvEmpty.text = it
                            updateEmptyState(isEmpty = true, isPermError = false)
                            showSnackbarWithRetry(it)
                        }
                    }
                }

                // Observe configured modes for badge display
                launch {
                    viewModel.configuredModes.collect { modes ->
                        videoAdapter.configuredModes = modes
                    }
                }
            }
        }
    }

    /**
     * Controls visibility of the empty state layout vs. the video list.
     * When [isEmpty] is true, shows the empty state with optional retry button.
     * When [isPermError] is true, shows the permission-specific empty state.
     */
    private fun updateEmptyState(isEmpty: Boolean, isPermError: Boolean) {
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

    private fun checkPermissionsAndScan() {
        // Request notification permission on Android 13+ so media controls appear
        requestNotificationPermissionIfNeeded()

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(
                requireContext(), permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                Timber.d("Permission already granted, scanning videos")
                viewModel.refreshVideos()
            }
            shouldShowRequestPermissionRationale(permission) -> {
                Timber.d("Showing permission rationale before request")
                showSnackbar(getString(R.string.permission_rationale), getString(R.string.retry)) {
                    requestPermissionLauncher.launch(permission)
                }
            }
            else -> {
                Timber.d("Requesting storage permission")
                requestPermissionLauncher.launch(permission)
            }
        }
    }

    /**
     * On Android 13+ (API 33), the POST_NOTIFICATIONS permission is required
     * for the media playback notification to appear. We request it once;
     * if denied, playback still works but the notification won't show.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Timber.d("Requesting POST_NOTIFICATIONS permission")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showPermissionDenied() {
        binding.tvEmpty.text = getString(R.string.permission_required)
        updateEmptyState(isEmpty = true, isPermError = true)
        showSnackbar(getString(R.string.permission_denied_message), getString(R.string.retry)) {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            requestPermissionLauncher.launch(permission)
        }
    }

    /**
     * Shows a Snackbar with a retry action that refreshes the video list.
     */
    private fun showSnackbarWithRetry(message: String) {
        view?.let { rootView ->
            Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.retry)) {
                    viewModel.refreshVideos()
                }
                .show()
        }
    }

    /**
     * Shows a Snackbar with an optional action.
     */
    private fun showSnackbar(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        view?.let { rootView ->
            val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
            if (actionLabel != null && action != null) {
                snackbar.setAction(actionLabel) { action() }
            }
            snackbar.show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
