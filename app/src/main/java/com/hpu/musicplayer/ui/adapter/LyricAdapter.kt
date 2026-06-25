package com.hpu.musicplayer.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hpu.musicplayer.R
import com.hpu.musicplayer.databinding.ItemLyricLineBinding
import com.hpu.musicplayer.utils.LrcLine

// 歌词适配器
class LyricAdapter : RecyclerView.Adapter<LyricAdapter.ViewHolder>() {

    private val lyrics = mutableListOf<LrcLine>()
    private var currentIndex = -1

    var fontSizeSp: Float = 16f   // 动态字体大小

    fun updateFontSize(newSize: Float) {
        if (fontSizeSp != newSize) {
            fontSizeSp = newSize
            notifyDataSetChanged()
        }
    }

    fun submitList(list: List<LrcLine>) {
        lyrics.clear()
        lyrics.addAll(list)
        currentIndex = -1
        notifyDataSetChanged()
    }

    fun updateCurrentIndex(index: Int) {
        if (index == currentIndex) return
        val previous = currentIndex
        currentIndex = index
        // 刷新之前高亮的行和当前行
        if (previous in lyrics.indices) notifyItemChanged(previous)
        if (currentIndex in lyrics.indices) notifyItemChanged(currentIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLyricLineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lyrics[position]
        val isCurrent = position == currentIndex
        val ctx = holder.itemView.context
        holder.binding.tvLyricLine.text = line.text
        // 当前行稍大，非当前行小一些
        val size = if (isCurrent) fontSizeSp * 1.2f else fontSizeSp
        holder.binding.tvLyricLine.textSize = size
        if (isCurrent) {
            holder.binding.tvLyricLine.setTextColor(ContextCompat.getColor(ctx, R.color.accent_orange))
        } else {
            holder.binding.tvLyricLine.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
    }

    override fun getItemCount(): Int = lyrics.size

    inner class ViewHolder(val binding: ItemLyricLineBinding) :
        RecyclerView.ViewHolder(binding.root)
}