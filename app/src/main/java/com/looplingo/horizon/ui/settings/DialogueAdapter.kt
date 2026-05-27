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

        // Build SpannableString: timestamp in mono #71717A 9sp bold, text in #A1A1AA 12sp bold
        val fullText = "$timestamp  ${segment.text}"
        val spannable = SpannableString(fullText)

        // Timestamp style: zinc-500 (#71717A), mono, 9sp (0.75x of 12sp), bold
        spannable.setSpan(
            ForegroundColorSpan(0xFF71717A.toInt()),
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

        // English text style: #A1A1AA, 12sp, bold (default size, already bold from XML)
        spannable.setSpan(
            ForegroundColorSpan(0xFFA1A1AA.toInt()),
            timestamp.length, fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        holder.tvText.text = spannable

        val translation = translations[segment.id]
        if (translation != null) {
            holder.tvTranslation.text = translation
            holder.tvTranslation.visibility = View.VISIBLE
        } else {
            holder.tvTranslation.visibility = View.GONE
        }

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
