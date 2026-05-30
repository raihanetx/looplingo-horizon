package com.looplingo.horizon.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.looplingo.horizon.BuildConfig
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.FragmentMainBinding
import com.looplingo.horizon.domain.model.SortOrder
import com.looplingo.horizon.ui.common.adapter.VideoAdapter
import com.looplingo.horizon.core.SecurePrefs
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

    private var isFilterOpen = false
    private var activeFilter = "All"

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
            Timber.i("Notification permission granted")
        } else {
            Timber.w("Notification permission denied")
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
        videoAdapter = helper.setupRecyclerView(this, binding, viewModel, notificationPermissionLauncher)
        setupSwipeRefresh()
        setupHeader()
        setupFilterDrawer()
        helper.setupObservers(this, binding, viewModel, videoAdapter)
        helper.setupSettingsButton(binding, viewModel)
        helper.setupSearchView(binding, viewModel)
        checkPermissionsAndScan()
    }

    private fun setupHeader() {
        binding.ivSettings.setOnClickListener {
            showSettingsDialog()
        }
        binding.ivFilterToggle.setOnClickListener {
            toggleFilterDrawer()
        }
    }

    private fun setupFilterDrawer() {
        val filterOptions = listOf("All", "Size ↓", "Size ↑", "Sub: Yes", "Sub: No", "Pinned")
        val chipContainer = binding.layoutFilterChips
        chipContainer.removeAllViews()

        filterOptions.forEach { filter ->
            val chip = MaterialButton(requireContext()).apply {
                text = filter
                textSize = 11f
                isAllCaps = false
                cornerRadius = 100
                setPadding(20, 6, 20, 6)
                minimumHeight = 0
                minimumWidth = 0
                setTextColor(if (filter == activeFilter) ContextCompat.getColor(context, R.color.white) else ContextCompat.getColor(context, R.color.gray600))
                backgroundTintList = if (filter == activeFilter) ContextCompat.getColorStateList(context, R.color.blue500) else ContextCompat.getColorStateList(context, R.color.gray100)
                strokeWidth = 0
                setOnClickListener {
                    activeFilter = filter
                    applyFilter(filter)
                    refreshFilterChips()
                }
            }
            val lp = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
            }
            chip.layoutParams = lp
            chipContainer.addView(chip)
        }
    }

    private fun refreshFilterChips() {
        val chipContainer = binding.layoutFilterChips
        for (i in 0 until chipContainer.childCount) {
            val chip = chipContainer.getChildAt(i) as? MaterialButton ?: continue
            val filter = chip.text.toString()
            val isActive = filter == activeFilter
            chip.setTextColor(if (isActive) ContextCompat.getColor(requireContext(), R.color.white) else ContextCompat.getColor(requireContext(), R.color.gray600))
            chip.backgroundTintList = if (isActive) ContextCompat.getColorStateList(requireContext(), R.color.blue500) else ContextCompat.getColorStateList(requireContext(), R.color.gray100)
            chip.strokeWidth = 0
        }
    }

    private fun toggleFilterDrawer() {
        isFilterOpen = !isFilterOpen
        binding.layoutFilterDrawer.visibility = if (isFilterOpen) View.VISIBLE else View.GONE
        binding.spacerFilter.visibility = if (isFilterOpen) View.VISIBLE else View.GONE
        binding.ivFilterToggle.setColorFilter(
            if (isFilterOpen) ContextCompat.getColor(requireContext(), R.color.blue500)
            else ContextCompat.getColor(requireContext(), R.color.gray500)
        )
    }

    private fun applyFilter(filter: String) {
        viewModel.setSortOrder(when (filter) {
            "Size ↓" -> SortOrder.SIZE
            "Size ↑" -> SortOrder.SIZE
            "Sub: Yes" -> SortOrder.DATE
            "Sub: No" -> SortOrder.DATE
            "Pinned" -> SortOrder.DATE
            else -> SortOrder.DATE
        })
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
        val currentPath = com.looplingo.horizon.domain.audio.service.AudioPlaybackService.currentVideoPath
        if (currentPath.isNotBlank()) {
            videoAdapter.playingPath = currentPath
        } else {
            videoAdapter.playingPath = null
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
