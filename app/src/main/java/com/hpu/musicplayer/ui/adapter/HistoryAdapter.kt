package com.hpu.musicplayer.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.PlayHistory
import com.hpu.musicplayer.databinding.ItemHistorySongBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onItemClick: (PlayHistory) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items = listOf<PlayHistory>()

    val currentList: List<PlayHistory> get() = items

    fun submitList(list: List<PlayHistory>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistorySongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val history = items[position]
        holder.binding.tvTitle.text = history.title
        holder.binding.tvArtist.text = history.artist

        // 封面
        if (!history.coverPath.isNullOrEmpty()) {
            holder.binding.ivCover.load(File(history.coverPath)) {
                placeholder(R.drawable.ic_music_note)
                error(R.drawable.ic_music_note)
            }
        } else {
            holder.binding.ivCover.setImageResource(R.drawable.ic_music_note)
        }

        // 播放时间
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        holder.binding.tvPlayedAt.text = sdf.format(Date(history.playedAt))

        holder.binding.root.setOnClickListener { onItemClick(history) }
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(val binding: ItemHistorySongBinding) : RecyclerView.ViewHolder(binding.root)
}