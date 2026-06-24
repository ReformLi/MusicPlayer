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

        // 时间显示
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val startTime = sdf.format(Date(history.playedAt))

        if (history.endTime != null) {
            // 已结束：显示时间范围
            val endTime = sdf.format(Date(history.endTime))
            holder.binding.tvPlayTime.text = "$startTime ~ $endTime"

            // 显示收听时长
            if (history.thisDuration > 0) {
                holder.binding.tvDuration.visibility = android.view.View.VISIBLE
                holder.binding.tvDuration.text = "收听 ${formatDuration(history.thisDuration)}"
            } else {
                holder.binding.tvDuration.visibility = android.view.View.GONE
            }
        } else {
            // 正在播放中
            holder.binding.tvPlayTime.text = startTime
            holder.binding.tvDuration.visibility = android.view.View.VISIBLE
            holder.binding.tvDuration.text = "播放中..."
        }

        holder.binding.root.setOnClickListener { onItemClick(history) }
    }

    override fun getItemCount(): Int = items.size

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    inner class ViewHolder(val binding: ItemHistorySongBinding) : RecyclerView.ViewHolder(binding.root)
}
