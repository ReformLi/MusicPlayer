package com.hpu.musicplayer.ui.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.color.MaterialColors
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.dao.RankEntry
import com.hpu.musicplayer.databinding.ItemRankBinding
import java.io.File

class RankAdapter(
    private var sortByDuration: Boolean = false
) : RecyclerView.Adapter<RankAdapter.ViewHolder>() {

    private var items = listOf<RankEntry>()

    val currentList: List<RankEntry> get() = items

    fun submitList(list: List<RankEntry>) {
        items = list
        notifyDataSetChanged()
    }

    fun setSortMode(byDuration: Boolean) {
        sortByDuration = byDuration
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRankBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        val rank = position + 1

        holder.binding.tvRankNumber.text = rank.toString()

        // 前三名特殊颜色：colorPrimary 背景 + colorOnPrimary 文字
        when (rank) {
            1 -> {
                holder.binding.tvRankNumber.backgroundTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(holder.binding.tvRankNumber, com.google.android.material.R.attr.colorPrimary)
                )
                holder.binding.tvRankNumber.setTextColor(
                    MaterialColors.getColor(holder.binding.tvRankNumber, com.google.android.material.R.attr.colorOnPrimary)
                )
                holder.binding.tvRankNumber.textSize = 18f
            }
            2 -> {
                holder.binding.tvRankNumber.backgroundTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(holder.binding.tvRankNumber, com.google.android.material.R.attr.colorPrimary)
                )
                holder.binding.tvRankNumber.setTextColor(
                    MaterialColors.getColor(holder.binding.tvRankNumber, com.google.android.material.R.attr.colorOnPrimary)
                )
                holder.binding.tvRankNumber.textSize = 16f
            }
            3 -> {
                holder.binding.tvRankNumber.backgroundTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(holder.binding.tvRankNumber, com.google.android.material.R.attr.colorPrimary)
                )
                holder.binding.tvRankNumber.setTextColor(
                    MaterialColors.getColor(holder.binding.tvRankNumber, com.google.android.material.R.attr.colorOnPrimary)
                )
                holder.binding.tvRankNumber.textSize = 16f
            }
            else -> {
                // 恢复默认背景（圆形的 rank_circle_bg drawable）
                holder.binding.tvRankNumber.backgroundTintList = null
                holder.binding.tvRankNumber.setTextColor(
                    MaterialColors.getColor(holder.binding.tvRankNumber, com.google.android.material.R.attr.colorOnPrimaryContainer)
                )
                holder.binding.tvRankNumber.textSize = 14f
            }
        }

        holder.binding.tvRankTitle.text = entry.title
        holder.binding.tvRankArtist.text = entry.artist

        // 封面
        if (!entry.coverPath.isNullOrEmpty()) {
            holder.binding.ivRankCover.load(File(entry.coverPath)) {
                placeholder(R.drawable.ic_music_note)
                error(R.drawable.ic_music_note)
            }
        } else {
            holder.binding.ivRankCover.setImageResource(R.drawable.ic_music_note)
        }

        // 统计值
        val valueText = if (sortByDuration) {
            formatDuration(entry.totalTime)
        } else {
            "${entry.count}次"
        }
        holder.binding.tvRankValue.text = valueText
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

    inner class ViewHolder(val binding: ItemRankBinding) : RecyclerView.ViewHolder(binding.root)
}
