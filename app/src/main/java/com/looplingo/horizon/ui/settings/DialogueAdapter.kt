package com.looplingo.horizon.ui.settings

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
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

    private val selectedPositions = mutableSetOf<Int>()
    private var activePlayPos = -1

    fun setActivePosition(pos: Int) {
        if (pos == activePlayPos) return
        val oldPos = activePlayPos
        activePlayPos = pos
        if (oldPos >= 0) notifyItemChanged(oldPos)
        if (pos >= 0) notifyItemChanged(pos)
    }

    fun toggleSelection(pos: Int) {
        if (selectedPositions.contains(pos)) {
            selectedPositions.remove(pos)
        } else {
            selectedPositions.add(pos)
        }
        notifyItemChanged(pos)
    }

    fun isSelected(pos: Int): Boolean = selectedPositions.contains(pos)

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
        val isActive = position == activePlayPos
        val isSelected = selectedPositions.contains(position)

        // Colors: selected/active = white, unselected = gray
        val textColor = if (isSelected || isActive) 0xFFFFFFFF.toInt() else 0xFFA1A1AA.toInt()
        val timestampColor = if (isSelected || isActive) 0xFFD4D4D8.toInt() else 0xFF71717A.toInt()

        // Build SpannableString
        val fullText = "$timestamp  ${segment.text}"
        val spannable = SpannableString(fullText)

        // Timestamp: mono, bold
        spannable.setSpan(
            ForegroundColorSpan(timestampColor),
            0, timestamp.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            TypefaceSpan("monospace"),
            0, timestamp.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            RelativeSizeSpan(0.75f),
            0, timestamp.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            0, timestamp.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // English text
        spannable.setSpan(
            ForegroundColorSpan(textColor),
            timestamp.length, fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        holder.tvText.text = spannable

        val translation = translations[segment.id]
        if (translation != null) {
            holder.tvTranslation.text = translation
            holder.tvTranslation.setTextColor(textColor)
            holder.tvTranslation.visibility = View.VISIBLE
        } else {
            holder.tvTranslation.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            toggleSelection(holder.bindingAdapterPosition)
            onSegmentClick(segment, holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount() = segments.size
}
