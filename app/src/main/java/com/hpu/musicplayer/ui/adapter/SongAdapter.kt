package com.hpu.musicplayer.ui.adapter

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.card.MaterialCardView
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.databinding.ItemSongBinding
import java.io.File
import java.util.concurrent.TimeUnit

class SongAdapter(
    private val showMenu: Boolean = true,
    private val showDeleteMenu: Boolean = true,   // 新增：是否显示"删除"菜单
    private val onItemClick: (Song) -> Unit,
    private val onDeleteClick: (Song) -> Unit,
    private val onInfoClick: (Song) -> Unit,
    private val onFavoriteClick: (Song) -> Unit,
    private val onAddToQueueClick: (Song) -> Unit
) : ListAdapter<Song, SongAdapter.ViewHolder>(DiffCallback()) {

    // 当前播放歌曲的 ID，-1 表示无
    private var currentSongId: Long = -1

    /**
     * 由外部调用，更新当前播放歌曲 ID，刷新对应 item
     */
    fun updateCurrentSongId(songId: Long) {
        if (currentSongId == songId) return
        val previous = currentSongId
        currentSongId = songId
        // 刷新之前高亮的项和当前高亮的项
        notifyItemChangedById(previous)
        notifyItemChangedById(songId)
    }

    private fun notifyItemChangedById(id: Long) {
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = getItem(position)
        holder.bind(song, song.id == currentSongId)
        holder.binding.btnMore.visibility = if (showMenu) View.VISIBLE else View.GONE

        // 更多按钮点击弹出菜单
        holder.binding.btnMore.setOnClickListener { view ->
            val wrappedCtx = ContextThemeWrapper(view.context, R.style.Theme_HpuMusicPlayer_PopupMenu)
            val popup = PopupMenu(wrappedCtx, view)
            popup.menuInflater.inflate(R.menu.menu_song_item, popup.menu)
            // 如果不显示删除，则移除该项
            if (!showDeleteMenu) {
                popup.menu.removeItem(R.id.action_delete)
            }
            popup.setForceShowIcons()   // 反射强制显示图标
            // 统一设置图标颜色为暖陶土主题色
            val iconColor = ContextCompat.getColor(view.context, R.color.primary_warm)
            for (i in 0 until popup.menu.size()) {
                popup.menu.getItem(i).icon?.mutate()?.setTint(iconColor)
            }
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_add_to_queue -> {
                        onAddToQueueClick(getItem(holder.bindingAdapterPosition))
                        true
                    }
                    R.id.action_delete -> {
                        onDeleteClick(getItem(holder.bindingAdapterPosition))
                        true
                    }
                    R.id.action_info -> {
                        onInfoClick(getItem(holder.bindingAdapterPosition))
                        true
                    }
                    R.id.action_favorite -> {
                        onFavoriteClick(getItem(holder.bindingAdapterPosition))
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    inner class ViewHolder(val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, isCurrent: Boolean) {
            // 1. 填充数据
            binding.tvTitle.text = song.title
            binding.tvArtist.text = song.artist
            binding.tvDuration.text = formatDuration(song.duration)

            val coverPath = song.customCoverPath ?: song.coverPath
            if (!coverPath.isNullOrEmpty()) {
                binding.ivCover.load(File(coverPath)) {
                    placeholder(R.drawable.ic_music_note)
                    error(R.drawable.ic_music_note)
                }
            } else {
                binding.ivCover.setImageResource(R.drawable.ic_music_note)
            }

            // 3. 收藏图标
            if (song.isFavorite) {
                binding.ivFavorite.visibility = View.VISIBLE
                binding.ivFavorite.setImageResource(R.drawable.ic_favorite)
            } else {
                binding.ivFavorite.visibility = View.GONE
            }

            // 4. 更多按钮可见性（由外部控制，这里保留）
            binding.btnMore.visibility = if (showMenu) View.VISIBLE else View.GONE

            // 5. 点击事件
            binding.root.setOnClickListener { onItemClick(song) }

            // 6. 获取主题默认颜色（用于重置）
            val context = binding.root.context
            val defaultTitleColor = getColorFromAttr(context, com.google.android.material.R.attr.colorOnSurface)
            val defaultArtistColor = getColorFromAttr(context, com.google.android.material.R.attr.colorOnSurfaceVariant)
            val defaultBgColor = getColorFromAttr(context, com.google.android.material.R.attr.colorSurface)

            // 7. 重置为默认样式（防止复用残留）
            binding.tvTitle.setTextColor(defaultTitleColor)
            binding.tvTitle.setTypeface(null, Typeface.NORMAL)
            binding.tvArtist.setTextColor(defaultArtistColor)
            (binding.root as MaterialCardView).setCardBackgroundColor(defaultBgColor)

            if (isCurrent) {
                val primaryColor = getColorFromAttr(context, com.google.android.material.R.attr.colorPrimary)
                val highlightBg = (primaryColor and 0x00FFFFFF) or 0x1A000000

                binding.tvTitle.setTextColor(primaryColor)
                binding.tvTitle.setTypeface(null, Typeface.BOLD)
                binding.tvArtist.setTextColor(primaryColor)
                (binding.root as MaterialCardView).apply {
                    setCardBackgroundColor(highlightBg)
                    cardElevation = 0f
                    strokeWidth = 0
                }
            } else {
                // 恢复默认阴影（与 item_song.xml 中设置一致）
                (binding.root as MaterialCardView).cardElevation = 2.dpToPx().toFloat()
            }
        }
    }
    // 工具：从主题属性中取色
    private fun getColorFromAttr(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
    private fun Int.dpToPx(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this.toFloat(),
            Resources.getSystem().displayMetrics
        ).toInt()
    }

    private fun formatDuration(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    class DiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Song, newItem: Song) =
            oldItem == newItem
    }
}

// 扩展函数：强制 PopupMenu 显示图标
fun PopupMenu.setForceShowIcons() {
    try {
        val fields = this.javaClass.getDeclaredFields()
        for (field in fields) {
            if ("mPopup" == field.name) {
                field.isAccessible = true
                val menuPopupHelper = field.get(this)
                val classPopupHelper = Class.forName(menuPopupHelper.javaClass.name)
                val setForceIcons = classPopupHelper.getMethod(
                    "setForceShowIcon", Boolean::class.javaPrimitiveType
                )
                setForceIcons.invoke(menuPopupHelper, true)
                break
            }
        }
    } catch (e: Exception) {
        // 忽略，部分机型可能无效
    }
}