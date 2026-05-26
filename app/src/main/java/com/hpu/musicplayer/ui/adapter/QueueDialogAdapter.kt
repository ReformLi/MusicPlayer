package com.hpu.musicplayer.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.databinding.ItemQueueSongBinding

class QueueDialogAdapter(
    private var currentIndex: Int,
    private val onItemClick: (Int) -> Unit,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<QueueDialogAdapter.VH>() {

    private var songs = mutableListOf<Song>()

    fun submitList(list: List<Song>) {
        songs.clear()
        songs.addAll(list)
        notifyDataSetChanged()
    }

    fun updateCurrentIndex(newIndex: Int) {
        val prev = currentIndex
        currentIndex = newIndex
        if (prev in songs.indices) notifyItemChanged(prev)
        if (currentIndex in songs.indices) notifyItemChanged(currentIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemQueueSongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = songs[position]
        holder.binding.tvQueueTitle.text = song.title
        holder.binding.tvQueueArtist.text = song.artist
//        holder.binding.tvQueueTitle.setTextColor(
//            if (position == currentIndex) 0xFF1DB954.toInt() else Color.WHITE
//        )
        holder.binding.root.setOnClickListener { onItemClick(position) }
        holder.binding.ivDelete.setOnClickListener { onDeleteClick(position) }
    }

    override fun getItemCount(): Int = songs.size

    inner class VH(val binding: ItemQueueSongBinding) : RecyclerView.ViewHolder(binding.root)
}