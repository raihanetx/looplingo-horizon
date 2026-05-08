package com.looplingo.horizon.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.FragmentMainBinding
import com.looplingo.horizon.model.SpeedPresets
import com.looplingo.horizon.playback.AudioPlaybackService
import com.looplingo.horizon.model.SortOrder
import com.looplingo.horizon.ui.adapter.VideoAdapter
import com.looplingo.horizon.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Main screen showing the list of video files found on the device.
 *
 * Features:
 *  - Requests storage permission and scans for videos on first launch
 *  - Click a video to start audio playback via [AudioPlaybackService]
 *  - Long-press a video to open its playback settings
 *  - Pull-to-refresh to rescan the media library
 *  - Mini player bar with instant speed control and A-B loop when audio is playing
 *  - Shows loading, empty, and error states with user feedback
 *  - Toolbar with sort and stop-playback actions
 */
@AndroidEntryPoint
class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()

    private lateinit var videoAdapter: VideoAdapter

    // Mini player state
    private val miniPlayerHandler = Handler(Looper.getMainLooper())
    private var miniPlayerPollingRunnable: Runnable? = null
    private var miniABStartMs: Long = 0L
    private var miniABEndMs: Long = -1L
    private var miniABLoopCount: Int = 3
    private var isABControlsVisible: Boolean = false

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
        setupMiniPlayer()
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
        videoAdapter.onVideoLongClick = { video ->
            try {
                val action = MainFragmentDirections.actionMainToPlaybackSettings(video.path)
                findNavController().navigate(action)
            } catch (e: Exception) {
                Timber.e(e, "Failed to navigate to settings for: %s", video.title)
                showSnackbar(getString(R.string.error_navigation))
            }
        }

        binding.btnEmptyRetry.setOnClickListener {
            viewModel.refreshVideos()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // MINI PLAYER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns the mini player root view, or null if not present in the current layout.
     * The include tag uses @+id/mini_player which overrides the root's @+id/mini_player_card,
     * so we must look up by mini_player.
     */
    private fun getMiniPlayerView(): View? =
        binding.root.findViewById(R.id.mini_player)

    private fun setupMiniPlayer() {
        val miniPlayer = getMiniPlayerView() ?: run {
            Timber.w("Mini player view not available in this layout configuration")
            return
        }

        // Play/Pause toggle
        miniPlayer.findViewById<View>(R.id.iv_mini_play_pause).setOnClickListener {
            try {
                val isPlaying = AudioPlaybackService.isPlaying
                if (isPlaying) {
                    AudioPlaybackService.stopService(requireContext())
                } else {
                    // Restart the last video — for simplicity, use the service's current path
                    val lastPath = AudioPlaybackService.currentVideoPath
                    if (lastPath.isNotBlank()) {
                        AudioPlaybackService.startService(requireContext(), lastPath)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to toggle playback from mini player")
            }
        }

        // Close/Stop button
        miniPlayer.findViewById<View>(R.id.iv_mini_close).setOnClickListener {
            AudioPlaybackService.stopService(requireContext())
        }

        // Speed chips — instant apply
        val chipGroup = miniPlayer.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chip_group_mini_speed)
        chipGroup.removeAllViews()
        for (preset in SpeedPresets.ALL) {
            val chip = Chip(requireContext()).apply {
                text = preset.label
                isCheckable = true
                id = View.generateViewId()
                tag = preset.speed
            }
            chip.setOnClickListener {
                // Instant speed change — no save needed
                AudioPlaybackService.setSpeed(requireContext(), preset.speed)
                Timber.d("Mini player speed changed to %s", preset.label)
            }
            chipGroup.addView(chip)
        }

        // A/B controls
        miniPlayer.findViewById<View>(R.id.btn_mini_set_a).setOnClickListener {
            val pos = AudioPlaybackService.currentPositionMs
            miniABStartMs = pos
            miniPlayer.findViewById<TextView>(R.id.tv_mini_a_time).text = formatMsToTime(pos)
            updateMiniABIndicator()
            showSnackbar(getString(R.string.mini_player_a_set, formatMsToTime(pos)))
        }

        miniPlayer.findViewById<View>(R.id.btn_mini_set_b).setOnClickListener {
            val pos = AudioPlaybackService.currentPositionMs
            miniABEndMs = pos
            miniPlayer.findViewById<TextView>(R.id.tv_mini_b_time).text = formatMsToTime(pos)
            updateMiniABIndicator()
        }

        // Try loop — preview without saving
        miniPlayer.findViewById<View>(R.id.btn_mini_try_loop).setOnClickListener {
            tryLoopFromMiniPlayer()
        }

        // Save loop — commit to database
        miniPlayer.findViewById<View>(R.id.btn_mini_save_loop).setOnClickListener {
            saveLoopFromMiniPlayer()
        }

        // Toggle A-B controls visibility on indicator click
        miniPlayer.findViewById<View>(R.id.tv_mini_ab_indicator).setOnClickListener {
            isABControlsVisible = !isABControlsVisible
            miniPlayer.findViewById<View>(R.id.layout_mini_ab_controls).visibility =
                if (isABControlsVisible) View.VISIBLE else View.GONE
        }
    }

    private fun tryLoopFromMiniPlayer() {
        val videoPath = AudioPlaybackService.currentVideoPath
        if (videoPath.isBlank()) return

        if (miniABEndMs > 0 && miniABEndMs > miniABStartMs) {
            AudioPlaybackService.setABLoop(
                requireContext(), videoPath,
                miniABStartMs, miniABEndMs, miniABLoopCount
            )
            showSnackbar(getString(R.string.loop_preview_active))
        } else {
            showSnackbar("Set both A and B points first")
        }
    }

    private fun saveLoopFromMiniPlayer() {
        val videoPath = AudioPlaybackService.currentVideoPath
        if (videoPath.isBlank()) return

        if (miniABEndMs > 0 && miniABEndMs > miniABStartMs) {
            // Save to repository via the service's config, then persist
            AudioPlaybackService.setABLoop(
                requireContext(), videoPath,
                miniABStartMs, miniABEndMs, miniABLoopCount
            )
            // Also persist to database
            lifecycleScope.launch {
                try {
                    viewModel.savePlaybackConfig(
                        videoPath, miniABStartMs, miniABEndMs, miniABLoopCount
                    )
                    showSnackbar(getString(R.string.settings_saved))
                } catch (e: Exception) {
                    Timber.e(e, "Failed to save loop from mini player")
                    showSnackbar(getString(R.string.error_save_failed))
                }
            }
        } else {
            showSnackbar("Set both A and B points first")
        }
    }

    private fun updateMiniABIndicator() {
        val miniPlayer = getMiniPlayerView() ?: return
        val indicator = miniPlayer.findViewById<TextView>(R.id.tv_mini_ab_indicator)
        if (miniABEndMs > 0 && miniABEndMs > miniABStartMs) {
            indicator.visibility = View.VISIBLE
            indicator.text = "AB"
        } else if (miniABStartMs > 0) {
            indicator.visibility = View.VISIBLE
            indicator.text = "A"
        } else {
            indicator.visibility = View.GONE
        }
    }

    private fun startMiniPlayerPolling() {
        stopMiniPlayerPolling()
        miniPlayerPollingRunnable = object : Runnable {
            override fun run() {
                try {
                    val miniPlayer = getMiniPlayerView()
                    if (miniPlayer == null) {
                        miniPlayerHandler.postDelayed(this, 2000L)
                        return
                    }

                    val isPlaying = AudioPlaybackService.isPlaying
                    val currentPath = AudioPlaybackService.currentVideoPath
                    val position = AudioPlaybackService.currentPositionMs

                    if (currentPath.isNotBlank()) {
                        miniPlayer.visibility = View.VISIBLE

                        // Update title
                        val title = currentPath.substringAfterLast("/").substringBeforeLast(".")
                        miniPlayer.findViewById<TextView>(R.id.tv_mini_title).text = title

                        // Update position
                        miniPlayer.findViewById<TextView>(R.id.tv_mini_position).text = formatMsToTime(position)

                        // Update play/pause icon
                        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        miniPlayer.findViewById<ImageView>(R.id.iv_mini_play_pause).setImageResource(playPauseIcon)
                    } else {
                        miniPlayer.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Mini player polling error")
                }
                miniPlayerHandler.postDelayed(this, 500L)
            }
        }
        miniPlayerHandler.post(miniPlayerPollingRunnable!!)
    }

    private fun stopMiniPlayerPolling() {
        miniPlayerPollingRunnable?.let { miniPlayerHandler.removeCallbacks(it) }
        miniPlayerPollingRunnable = null
    }

    /** Format milliseconds to "m:ss" or "h:mm:ss" */
    private fun formatMsToTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val hours = TimeUnit.SECONDS.toHours(totalSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // OBSERVERS
    // ══════════════════════════════════════════════════════════════════════

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.videos.collect { videoList ->
                        videoAdapter.submitList(videoList)
                        updateEmptyState(videoList.isEmpty(), isPermError = false)
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
                            updateEmptyState(isEmpty = true, isPermError = false)
                            showSnackbarWithRetry(it)
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

    private fun showSnackbarWithRetry(message: String) {
        view?.let { rootView ->
            Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.retry)) {
                    viewModel.refreshVideos()
                }
                .show()
        }
    }

    private fun showSnackbar(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        view?.let { rootView ->
            val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
            if (actionLabel != null && action != null) {
                snackbar.setAction(actionLabel) { action() }
            }
            snackbar.show()
        }
    }

    override fun onResume() {
        super.onResume()
        startMiniPlayerPolling()
    }

    override fun onPause() {
        super.onPause()
        stopMiniPlayerPolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopMiniPlayerPolling()
        _binding = null
    }
}
