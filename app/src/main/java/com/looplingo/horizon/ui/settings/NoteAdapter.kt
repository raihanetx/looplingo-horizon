package com.looplingo.horizon.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.looplingo.horizon.R
import com.looplingo.horizon.core.TimeUtils

data class SavedNote(
    val text: String,
    val timestampMs: Long
)

class NoteAdapter(
    private val onNoteClick: (SavedNote, Int) -> Unit,
    private val onDeleteClick: (SavedNote, Int) -> Unit,
    private val onEditClick: (SavedNote, Int) -> Unit
) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

    private val notes = mutableListOf<SavedNote>()

    fun setNotes(newNotes: List<SavedNote>) {
        notes.clear()
        notes.addAll(newNotes)
        notifyDataSetChanged()
    }

    fun addNote(note: SavedNote) {
        notes.add(note)
        notifyItemInserted(notes.size - 1)
    }

    fun updateNote(position: Int, newText: String) {
        if (position in notes.indices) {
            notes[position] = notes[position].copy(text = newText)
            notifyItemChanged(position)
        }
    }

    fun removeNote(position: Int) {
        if (position in notes.indices) {
            notes.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun getNotes(): List<SavedNote> = notes.toList()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tv_note_text)
        val ivEdit: ImageView = view.findViewById(R.id.iv_note_edit)
        val ivDelete: ImageView = view.findViewById(R.id.iv_note_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_note, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val note = notes[position]
        holder.tvText.text = note.text

        holder.itemView.setOnClickListener {
            onNoteClick(note, holder.bindingAdapterPosition)
        }

        holder.ivEdit.setOnClickListener {
            onEditClick(note, holder.bindingAdapterPosition)
        }

        holder.ivDelete.setOnClickListener {
            onDeleteClick(note, holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount() = notes.size
}
