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

    fun setActivePosition(pos: Int) {
        if (pos == selectedPos) return
        val oldPos = selectedPos
        selectedPos = pos
        if (oldPos >= 0) notifyItemChanged(oldPos)
        if (pos >= 0) notifyItemChanged(pos)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
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
        val timestamp = "[${TimeUtils.formatMsToTime(segment.startMs.toLong())}]"
        holder.tvText.text = "$timestamp ${segment.text}"

        val translation = translations[segment.id]
        if (translation != null) {
            holder.tvTranslation.text = translation
            holder.tvTranslation.visibility = View.VISIBLE
        } else {
            holder.tvTranslation.visibility = View.GONE
        }

        val isActive = position == selectedPos
        holder.itemView.isSelected = isActive

        // Active = white, inactive = gray
        val textColor = holder.itemView.context.getColor(
            if (isActive) R.color.colorOnSurface else R.color.colorOnSurfaceVariant
        )
        val translationColor = holder.itemView.context.getColor(
            if (isActive) R.color.colorPrimary else R.color.colorOnSurfaceVariant
        )

        holder.tvText.setTextColor(textColor)
        holder.tvTranslation.setTextColor(translationColor)

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
