package com.hpu.musicplayer.ui.adapter

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.databinding.ItemQueueSongBinding

class QueueAdapter(
    private var currentIndex: Int,
    private val onItemClick: (Int) -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    private var songs = mutableListOf<Song>()

    fun updateCurrentIndex(newIndex: Int) {
        if (newIndex == currentIndex) return
        val previous = currentIndex
        currentIndex = newIndex
        if (previous in songs.indices) notifyItemChanged(previous)
        if (currentIndex in songs.indices) notifyItemChanged(currentIndex)
    }

    fun submitList(list: List<Song>) {
        songs.clear()
        songs.addAll(list)
        notifyDataSetChanged()
    }

    fun getSongs(): List<Song> = songs.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQueueSongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.binding.tvQueueTitle.text = song.title
        holder.binding.tvQueueArtist.text = song.artist
        // ---------- 高亮处理 ----------
        if (position == currentIndex) {
            // 当前播放：绿色文字 + 加粗 + 背景略暗
            holder.binding.tvQueueTitle.setTextColor(0xFF1DB954.toInt()) // 亮绿
            holder.binding.tvQueueTitle.setTypeface(null, Typeface.BOLD)
            holder.binding.tvQueueArtist.setTextColor(0xFF1DB954.toInt())
            holder.binding.root.setBackgroundColor(0x22FFFFFF) // 白色半透明背景
        } else {
            // 普通歌曲：白色，常规字体，透明背景
            holder.binding.tvQueueTitle.setTextColor(Color.WHITE)
            holder.binding.tvQueueTitle.setTypeface(null, Typeface.NORMAL)
            holder.binding.tvQueueArtist.setTextColor(0xFFAAAAAA.toInt())
            holder.binding.root.setBackgroundColor(Color.TRANSPARENT)
        }

//        // 封面加载
//        if (!song.coverPath.isNullOrEmpty()) {
//            holder.binding.ivQueueCover.load(File(song.coverPath)) {
//                placeholder(R.drawable.ic_music_note)
//                error(R.drawable.ic_music_note)
//            }
//        } else {
//            holder.binding.ivQueueCover.setImageResource(R.drawable.ic_music_note)
//        }
//
//        holder.binding.root.setOnClickListener { onItemClick(position) }
//        holder.binding.ivDragHandle.setOnTouchListener { _, _ ->
//            onDragStart(holder)
//            false
//        }
    }

//    fun updateCurrentIndex(newIndex: Int) {
//        val previous = currentIndex
//        currentIndex = newIndex
//        // 刷新前后两项即可
//        if (previous >= 0 && previous < itemCount) notifyItemChanged(previous)
//        if (newIndex >= 0 && newIndex < itemCount) notifyItemChanged(newIndex)
//    }

    override fun getItemCount(): Int = songs.size

    inner class ViewHolder(val binding: ItemQueueSongBinding) :
        RecyclerView.ViewHolder(binding.root)
}