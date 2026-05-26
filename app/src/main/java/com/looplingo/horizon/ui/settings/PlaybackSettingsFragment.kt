package com.looplingo.horizon.ui.settings

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
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackSettingsFragment : Fragment() {

    private var _binding: FragmentPlaybackSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaybackSettingsViewModel by viewModels()
    private val args: PlaybackSettingsFragmentArgs by navArgs()

    @Inject lateinit var uiBinder: PlaybackSettingsUiBinder

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) Timber.i("Notification permission granted")
        else Timber.w("Notification permission denied")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaybackSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val videoPath = args.videoPath
        if (videoPath.isBlank()) {
            findNavController().navigateUp()
            return
        }
        viewModel.loadConfigForVideo(videoPath)
        uiBinder.bind(
            fragment = this, binding = binding,
            viewModel = viewModel, videoPath = videoPath,
            contentUri = args.contentUri,
            requestNotificationPermission = ::requestNotificationPermissionIfNeeded,
            navigateUp = { findNavController().navigateUp() }
        )
    }

    override fun onResume() {
        super.onResume()
        uiBinder.onResume()
    }

    override fun onPause() {
        super.onPause()
        uiBinder.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiBinder.onDestroy()
        _binding = null
    }
}
