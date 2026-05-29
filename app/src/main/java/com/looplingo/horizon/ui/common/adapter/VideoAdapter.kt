package com.looplingo.horizon.ui.common.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.looplingo.horizon.R
import com.looplingo.horizon.data.local.entity.VideoEntity
import com.looplingo.horizon.databinding.VideoItemBinding
import java.util.concurrent.TimeUnit
import java.text.DecimalFormat

class VideoAdapter(
    private val onVideoClick: (VideoEntity) -> Unit
) : ListAdapter<VideoEntity, VideoAdapter.VideoViewHolder>(VideoDiffCallback) {

    var configuredModes: Map<String, String> = emptyMap()
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_BADGE_UPDATE)
        }

    var videosWithSubtitles: Set<String> = emptySet()
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SUBTITLE_UPDATE)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = VideoItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int, payloads: MutableList<Any>) {
        when {
            payloads.contains(PAYLOAD_BADGE_UPDATE) -> holder.bindBadge(getItem(position))
            payloads.contains(PAYLOAD_SUBTITLE_UPDATE) -> holder.bindSubtitleStatus(getItem(position))
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class VideoViewHolder(private val binding: VideoItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoEntity) {
            binding.tvTitle.text = video.title
            binding.tvDuration.text = formatDuration(video.duration)
            binding.tvSize.text = formatFileSize(video.size)

            bindBadge(video)
            bindSubtitleStatus(video)

            binding.root.setOnClickListener {
                onVideoClick(video)
            }

            binding.root.contentDescription = buildContentDescription(video)
        }

        fun bindBadge(video: VideoEntity) {
            val modeLabel = configuredModes[video.path]
            if (modeLabel != null) {
                binding.tvLoopBadge.text = modeLabel
                binding.tvLoopBadge.visibility = View.VISIBLE
            } else {
                binding.tvLoopBadge.visibility = View.GONE
            }
        }

        fun bindSubtitleStatus(video: VideoEntity) {
            if (videosWithSubtitles.contains(video.path)) {
                binding.tvSubtitleStatus.visibility = View.VISIBLE
                binding.dividerSubtitle.visibility = View.VISIBLE
                binding.tvSubtitleStatus.text = "Subtitles"
            } else {
                binding.tvSubtitleStatus.visibility = View.GONE
                binding.dividerSubtitle.visibility = View.GONE
            }
        }

        private fun buildContentDescription(video: VideoEntity): String {
            val base = "${video.title}, ${formatDuration(video.duration)}"
            val mode = configuredModes[video.path]
            return if (mode != null) "$base, configured as $mode" else base
        }

        private fun formatDuration(durationMs: Long): String {
            val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%d:%02d", minutes, seconds)
            }
        }

        private fun formatFileSize(sizeBytes: Long): String {
            if (sizeBytes <= 0) return ""
            val units = arrayOf("B", "KB", "MB", "GB")
            val digitGroups = (Math.log10(sizeBytes.toDouble()) / Math.log10(1024.0)).toInt()
                .coerceIn(0, units.lastIndex)
            val value = sizeBytes / Math.pow(1024.0, digitGroups.toDouble())
            return "${fileSizeFormat.format(value)} ${units[digitGroups]}"
        }
    }

    companion object {
        const val PAYLOAD_BADGE_UPDATE = "badge_update"
        const val PAYLOAD_SUBTITLE_UPDATE = "subtitle_update"

        private val fileSizeFormat = DecimalFormat("#,##0.#")

        private val VideoDiffCallback = object : DiffUtil.ItemCallback<VideoEntity>() {
            override fun areItemsTheSame(oldItem: VideoEntity, newItem: VideoEntity): Boolean {
                return oldItem.path == newItem.path
            }

            override fun areContentsTheSame(oldItem: VideoEntity, newItem: VideoEntity): Boolean {
                return oldItem == newItem
            }
        }
    }
}
