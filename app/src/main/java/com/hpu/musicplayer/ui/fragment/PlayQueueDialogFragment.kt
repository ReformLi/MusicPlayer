package com.hpu.musicplayer.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hpu.musicplayer.R
import com.hpu.musicplayer.databinding.DialogPlayQueueBinding
import com.hpu.musicplayer.service.PlayMode
import com.hpu.musicplayer.ui.adapter.QueueDialogAdapter
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlayQueueDialogFragment : DialogFragment() {

    private var _binding: DialogPlayQueueBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })
    private lateinit var adapter: QueueDialogAdapter
    private var currentPlaylistSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.QueueDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPlayQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = QueueDialogAdapter(
            currentIndex = playerViewModel.currentPlayIndex.value,
            onItemClick = { index -> playerViewModel.playAtIndex(index) },
            onDeleteClick = { index -> confirmDeleteSong(index) }
        )

        binding.rvQueue.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQueue.adapter = adapter

        // 观察播放列表
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.playlist.collect { list ->
                adapter.submitList(list)
                currentPlaylistSize = list.size
            }
        }
        // 观察当前索引
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.currentPlayIndex.collect { index ->
                adapter.updateCurrentIndex(index)
                // 自动滚动到当前播放歌曲位置
                if (index >= 0 && ::adapter.isInitialized) {
                    binding.rvQueue.smoothScrollToPosition(index)
                }
            }
        }
        // 观察播放模式
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.playMode.collect { mode ->
                val iconRes = when (mode) {
                    PlayMode.LIST_LOOP -> R.drawable.ic_mode_list_loop
                    PlayMode.RANDOM -> R.drawable.ic_mode_random
                    PlayMode.SINGLE_LOOP -> R.drawable.ic_mode_single_loop
                }
                val modeName = when (mode) {
                    PlayMode.LIST_LOOP -> "列表循环"
                    PlayMode.RANDOM -> "随机播放"
                    PlayMode.SINGLE_LOOP -> "单曲循环"
                }
                binding.ivPlayMode.setImageResource(iconRes)
                binding.tvPlayModeName.text = modeName
                // 单曲循环不显示数量
                if (mode == PlayMode.SINGLE_LOOP) {
                    binding.tvSongCount.visibility = View.GONE
                } else {
                    binding.tvSongCount.visibility = View.VISIBLE
                    binding.tvSongCount.text = "(${adapter.itemCount}首)"
                }
            }
        }

        // 重新加载全部
        binding.tvReloadAll.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val songs = playerViewModel.allSongs.first()
                if (songs.isNotEmpty()) {
                    playerViewModel.setPlaylist(songs)
                    android.widget.Toast.makeText(
                        requireContext(),
                        "已重新加载 ${songs.size} 首歌曲",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "数据库中没有歌曲",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // 清空列表
        binding.tvClearAll.setOnClickListener {
            // 确认后清空播放队列
            AlertDialog.Builder(requireContext())
                .setTitle("清空播放队列")
                .setMessage("确定要清空所有歌曲吗？")
                .setPositiveButton("确定") { _, _ ->
                    playerViewModel.clearPlaylist()
                    dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 添加拖拽排序功能
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                adapter.moveItem(fromPosition, toPosition)
                playerViewModel.moveSong(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // 不支持滑动删除
            }
        })

        itemTouchHelper.attachToRecyclerView(binding.rvQueue)

        binding.rootLayout.setOnClickListener {
            dismiss()
        }

        // 关闭弹框
        binding.tvClose.setOnClickListener { dismiss() }
        binding.root.setOnClickListener { dismiss() }   // 点击顶部空白区关闭
    }

    private fun confirmDeleteSong(index: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除歌曲")
            .setMessage("确定要从播放队列中删除这首歌曲吗？")
            .setPositiveButton("确定") { _, _ ->
                playerViewModel.removeSongByIndex(index)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}