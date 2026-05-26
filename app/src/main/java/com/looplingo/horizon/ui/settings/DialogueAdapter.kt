package com.looplingo.horizon.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.looplingo.horizon.R
import com.looplingo.horizon.data.remote.Segment
import com.looplingo.horizon.core.TimeUtils

class DialogueAdapter(
    private val segments: List<Segment>,
    private val translations: Map<Int, String>,
    private val onSegmentClick: (Segment, Int) -> Unit
) : RecyclerView.Adapter<DialogueAdapter.ViewHolder>() {

    private var selectedPos = -1

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTimestamp: TextView = view.findViewById(R.id.tv_cue_timestamp)
        val tvText: TextView = view.findViewById(R.id.tv_cue_text)
        val tvTranslation: TextView = view.findViewById(R.id.tv_cue_translation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subtitle_cue, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val segment = segments[position]
        holder.tvTimestamp.text = "[${TimeUtils.formatMsToTime(segment.startMs.toLong())}]"

        val translation = translations[segment.id]
        holder.tvText.text = segment.text
        if (translation != null) {
            holder.tvTranslation.text = translation
            holder.tvTranslation.visibility = View.VISIBLE
        } else {
            holder.tvTranslation.visibility = View.GONE
        }

        val isSelected = position == selectedPos
        holder.itemView.isSelected = isSelected
        holder.tvTimestamp.setTextColor(
            holder.itemView.context.getColor(if (isSelected) R.color.colorOnSurface else R.color.colorOnSurfaceVariant)
        )
        holder.tvText.setTextColor(
            holder.itemView.context.getColor(if (isSelected) R.color.colorOnSurface else R.color.colorOnSurfaceVariant)
        )

        holder.itemView.setOnClickListener {
            val oldPos = selectedPos
            selectedPos = holder.bindingAdapterPosition
            notifyItemChanged(oldPos)
            notifyItemChanged(selectedPos)
            onSegmentClick(segment, selectedPos)
        }
    }

    override fun getItemCount() = segments.size
}
