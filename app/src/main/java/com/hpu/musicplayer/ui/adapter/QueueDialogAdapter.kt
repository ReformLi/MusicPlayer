package com.hpu.musicplayer.ui.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.databinding.ItemQueueSongBinding

class QueueDialogAdapter(
    private var currentIndex: Int,
    private val onItemClick: (Int) -> Unit,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<QueueDialogAdapter.VH>() {

    private var songs = mutableListOf<Song>()

    fun submitList(list: List<Song>) {
        val diffCallback = SongDiffCallback(songs, list)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        songs.clear()
        songs.addAll(list)
        diffResult.dispatchUpdatesTo(this)
    }

    fun updateCurrentIndex(newIndex: Int) {
        val prev = currentIndex
        currentIndex = newIndex
        if (prev in songs.indices) notifyItemChanged(prev)
        if (currentIndex in songs.indices) notifyItemChanged(currentIndex)
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        val song = songs.removeAt(fromPosition)
        songs.add(toPosition, song)
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemQueueSongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding, parent.context)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = songs[position]
        holder.bind(song, position == currentIndex)
        holder.binding.root.setOnClickListener { onItemClick(position) }
        holder.binding.ivDelete.setOnClickListener { onDeleteClick(position) }
    }

    override fun getItemCount(): Int = songs.size

    inner class VH(
        val binding: ItemQueueSongBinding,
        private val context: Context
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, isCurrent: Boolean) {
            binding.tvQueueTitle.text = song.title
            binding.tvQueueArtist.text = song.artist
            
            // 高亮当前播放的歌曲
            if (isCurrent) {
                val accentColor = ContextCompat.getColor(context, R.color.accent_orange)
                binding.tvQueueTitle.setTextColor(accentColor)
                binding.tvQueueArtist.setTextColor(accentColor)
                binding.root.setBackgroundColor(ContextCompat.getColor(context, R.color.current_song_bg))
            } else {
                binding.tvQueueTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                binding.tvQueueArtist.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                binding.root.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private class SongDiffCallback(
        private val oldList: List<Song>,
        private val newList: List<Song>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}