package com.looplingo.horizon.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.looplingo.horizon.R
import com.looplingo.horizon.core.TimeUtils

data class SavedLoop(
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val loopCount: Int
)

class LoopAdapter(
    private val onLoopClick: (SavedLoop, Int) -> Unit,
    private val onDeleteClick: (SavedLoop, Int) -> Unit
) : RecyclerView.Adapter<LoopAdapter.ViewHolder>() {

    private val loops = mutableListOf<SavedLoop>()

    fun setLoops(newLoops: List<SavedLoop>) {
        loops.clear()
        loops.addAll(newLoops)
        notifyDataSetChanged()
    }

    fun addLoop(loop: SavedLoop) {
        loops.add(loop)
        notifyItemInserted(loops.size - 1)
    }

    fun removeLoop(position: Int) {
        if (position in loops.indices) {
            loops.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun getLoops(): List<SavedLoop> = loops.toList()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_loop_name)
        val tvRange: TextView = view.findViewById(R.id.tv_loop_range)
        val ivDelete: ImageView = view.findViewById(R.id.iv_loop_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_loop, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val loop = loops[position]
        holder.tvName.text = loop.name
        val start = TimeUtils.formatMsToTime(loop.startMs)
        val end = TimeUtils.formatMsToTime(loop.endMs)
        val durationSec = ((loop.endMs - loop.startMs) / 1000).toInt()
        val durationText = if (durationSec >= 60) "${durationSec / 60}m ${durationSec % 60}s" else "${durationSec}s"
        holder.tvRange.text = "$start - $end  •  x${loop.loopCount}  •  $durationText"

        holder.itemView.setOnClickListener {
            onLoopClick(loop, holder.bindingAdapterPosition)
        }

        holder.ivDelete.setOnClickListener {
            onDeleteClick(loop, holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount() = loops.size
}
