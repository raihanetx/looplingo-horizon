package com.looplingo.horizon.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.looplingo.horizon.R
import com.looplingo.horizon.data.entity.VideoEntity
import com.looplingo.horizon.databinding.VideoItemBinding
import java.util.concurrent.TimeUnit
import java.text.DecimalFormat

/**
 * RecyclerView adapter for the video file list using [ListAdapter] + [DiffUtil]
 * for smooth, animated item updates.
 *
 * Design decisions:
 *  - ListAdapter handles DiffUtil automatically — no more notifyDataSetChanged()
 *  - Click and long-click are separate callbacks for clean separation
 *  - Loop mode badge shows at a glance whether a custom config is set
 *  - File size is formatted in human-readable form (KB, MB, GB)
 */
class VideoAdapter(
    private val onVideoClick: (VideoEntity) -> Unit
) : ListAdapter<VideoEntity, VideoAdapter.VideoViewHolder>(VideoDiffCallback) {

    /** Long-press callback — set externally for navigation to settings. */
    var onVideoLongClick: ((VideoEntity) -> Unit)? = null

    /** Map of video path → badge label for showing loop status. */
    var configuredModes: Map<String, String> = emptyMap()
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_BADGE_UPDATE)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = VideoItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_BADGE_UPDATE)) {
            // Partial update — just refresh the badge without rebinding everything
            holder.bindBadge(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class VideoViewHolder(private val binding: VideoItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoEntity) {
            binding.tvTitle.text = video.title
            binding.tvPath.text = video.path
            binding.tvDuration.text = formatDuration(video.duration)
            binding.tvSize.text = formatFileSize(video.size)

            // Loop mode badge — shows the first character of the configured mode
            bindBadge(video)

            // Click: start audio playback
            binding.root.setOnClickListener {
                onVideoClick(video)
            }

            // Long-press: open playback settings
            binding.root.setOnLongClickListener {
                onVideoLongClick?.invoke(video)
                true
            }

            // Accessibility: custom content description for TalkBack
            binding.root.contentDescription = buildContentDescription(video)
        }

        fun bindBadge(video: VideoEntity) {
            val modeLabel = configuredModes[video.path]
            if (modeLabel != null) {
                binding.tvLoopBadge.text = modeLabel
                binding.tvLoopBadge.visibility = android.view.View.VISIBLE
            } else {
                binding.tvLoopBadge.visibility = android.view.View.GONE
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
            return "${DecimalFormat("#,##0.#").format(value)} ${units[digitGroups]}"
        }
    }

    companion object {
        /** Payload key for badge-only updates (avoids full item rebind). */
        const val PAYLOAD_BADGE_UPDATE = "badge_update"

        /**
         * DiffUtil callback that compares VideoEntity items by path (unique key)
         * and checks title/duration/size for content changes.
         */
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
