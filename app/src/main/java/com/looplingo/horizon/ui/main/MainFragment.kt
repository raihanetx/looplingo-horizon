package com.looplingo.horizon.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.BuildConfig
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.FragmentMainBinding
import com.looplingo.horizon.domain.audio.service.AudioPlaybackService
import com.looplingo.horizon.domain.model.SortOrder
import com.looplingo.horizon.ui.common.adapter.VideoAdapter
import com.looplingo.horizon.core.SecurePrefs
import com.looplingo.horizon.core.TimeUtils
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var helper: MainFragmentHelper

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
        videoAdapter = helper.setupRecyclerView(this, binding, viewModel, notificationPermissionLauncher)
        setupSwipeRefresh()
        helper.setupObservers(this, binding, viewModel, videoAdapter)
        helper.setupSettingsButton(binding, viewModel)
        helper.setupSearchView(binding, viewModel)
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
                R.id.action_settings -> {
                    showSettingsDialog()
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

        MaterialAlertDialogBuilder(requireContext())
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

    private fun showSettingsDialog() {
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_settings, null)
        val etApiKey = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_settings_api_key)
        val tvEngine = view.findViewById<TextView>(R.id.tv_settings_engine)

        tvEngine.text = "llama-3.3-70b-versatile"

        val prefs = SecurePrefs.get(requireContext())
        val savedKey = prefs.getString("groq_api_key", "") ?: ""
        if (savedKey.isNotBlank()) {
            etApiKey.setText(savedKey)
        } else if (BuildConfig.GROQ_API_KEY.isNotBlank()) {
            etApiKey.setText(BuildConfig.GROQ_API_KEY)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.action_settings))
            .setView(view)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val key = etApiKey.text?.toString()?.trim() ?: ""
                if (key.isNotBlank()) {
                    prefs.edit().putString("groq_api_key", key).apply()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun checkPermissionsAndScan() {
        helper.requestNotificationPermissionIfNeeded(this, notificationPermissionLauncher)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val videoGranted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            val audioGranted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (videoGranted && audioGranted) {
                Timber.d("Both media permissions granted, scanning videos")
                viewModel.refreshVideos()
            } else {
                val permissionsToRequest = mutableListOf<String>()
                if (!videoGranted) permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
                if (!audioGranted) permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
                if (permissionsToRequest.isNotEmpty()) {
                    Timber.d("Requesting media permissions: %s", permissionsToRequest)
                    requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
                }
            }
        } else {
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE

            when {
                ContextCompat.checkSelfPermission(
                    requireContext(), permission
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Timber.d("Storage permission already granted, scanning videos")
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
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Timber.i("All media permissions granted — scanning videos")
            viewModel.refreshVideos()
        } else {
            Timber.w("Some media permissions denied: %s", permissions.filter { !it.value }.keys)
            showPermissionDenied()
        }
    }

    private fun showPermissionDenied() {
        binding.tvEmpty.text = getString(R.string.permission_required)
        helper.showEmptyState(binding, isEmpty = true, isPermError = true)
        showSnackbar(getString(R.string.permission_denied_message), getString(R.string.retry)) {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun showSnackbar(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        view?.let { rootView ->
            val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
            snackbar.setBackgroundTint(resources.getColor(R.color.colorInverseSurface, null))
            snackbar.setTextColor(resources.getColor(R.color.colorInverseOnSurface, null))
            snackbar.setActionTextColor(resources.getColor(R.color.colorInversePrimary, null))
            if (actionLabel != null && action != null) {
                snackbar.setAction(actionLabel) { action() }
            }
            snackbar.show()
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
