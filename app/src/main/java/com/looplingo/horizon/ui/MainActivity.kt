package com.looplingo.horizon.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.looplingo.horizon.data.entity.VideoEntity
import com.looplingo.horizon.databinding.ActivityMainBinding
import com.looplingo.horizon.ui.adapter.VideoAdapter
import com.looplingo.horizon.util.FileScanner
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var videoAdapter: VideoAdapter
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) scanVideos() else showPermissionDenied() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRecyclerView()
        checkPermissionsAndScan()
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter { video ->
            com.looplingo.horizon.playback.AudioPlaybackService.startService(this, video.path)
        }
        binding.rvVideoList.layoutManager = LinearLayoutManager(this)
        binding.rvVideoList.adapter = videoAdapter
    }

    private fun checkPermissionsAndScan() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED -> scanVideos()
            else -> requestPermissionLauncher.launch(permission)
        }
    }

    private fun scanVideos() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.tvEmpty.visibility = android.view.View.GONE
        lifecycleScope.launch {
            val videos = FileScanner.scanVideos(this@MainActivity)
            videoAdapter.submitList(videos)
            binding.progressBar.visibility = android.view.View.GONE
            if (videos.isEmpty()) binding.tvEmpty.visibility = android.view.View.VISIBLE
        }
    }

    private fun showPermissionDenied() {
        binding.tvEmpty.text = "Permission required to scan videos"
        binding.tvEmpty.visibility = android.view.View.VISIBLE
    }
}
