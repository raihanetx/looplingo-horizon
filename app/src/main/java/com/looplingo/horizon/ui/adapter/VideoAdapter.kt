package com.looplingo.horizon.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.looplingo.horizon.data.entity.VideoEntity
import com.looplingo.horizon.databinding.VideoItemBinding
import java.util.concurrent.TimeUnit

class VideoAdapter(
    private val onVideoClick: (VideoEntity) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private val videos = mutableListOf<VideoEntity>()

    fun submitList(newVideos: List<VideoEntity>) {
        videos.clear()
        videos.addAll(newVideos)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = VideoItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(videos[position])
    }

    override fun getItemCount() = videos.size

    inner class VideoViewHolder(private val binding: VideoItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(video: VideoEntity) {
            binding.tvTitle.text = video.title
            binding.tvPath.text = video.path
            binding.tvDuration.text = formatDuration(video.duration)
            binding.root.setOnClickListener { onVideoClick(video) }
        }

        private fun formatDuration(durationMs: Long): String {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
    }
}
