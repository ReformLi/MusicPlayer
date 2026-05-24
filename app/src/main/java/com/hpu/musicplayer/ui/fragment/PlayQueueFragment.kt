package com.hpu.musicplayer.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.hpu.musicplayer.databinding.FragmentPlayQueueBinding
import com.hpu.musicplayer.ui.adapter.QueueAdapter
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

class PlayQueueFragment : Fragment() {

    private var _binding: FragmentPlayQueueBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })

    private lateinit var adapter: QueueAdapter
    private var currentIndex = -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = QueueAdapter(
            currentIndex = currentIndex,
            onItemClick = { index ->
                playerViewModel.playAtIndex(index)
            },
            onDragStart = { viewHolder ->
                itemTouchHelper.startDrag(viewHolder)
            }
        )

        binding.rvQueue.adapter = adapter
        binding.rvQueue.layoutManager = LinearLayoutManager(requireContext())

        // 拖拽与滑动删除
        itemTouchHelper.attachToRecyclerView(binding.rvQueue)

        // 观察播放列表
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.playlist.collect { list ->
                adapter.submitList(list)
                // 提交列表后重新应用当前索引，确保高亮
                adapter.updateCurrentIndex(playerViewModel.currentPlayIndex.value)
            }
        }
        // 观察当前索引
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.currentPlayIndex.collect { index ->
                currentIndex = index
                // 更新已显示的列表项高亮
                adapter.notifyDataSetChanged()
                adapter.updateCurrentIndex(index) // 自定义方法局部刷新
            }
        }
    }

    private val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            playerViewModel.moveSong(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            // 先获取被删除的歌曲信息，用于撤销
            val removedSongs = adapter.getSongs()
            if (position < 0 || position >= removedSongs.size) {
                adapter.notifyItemChanged(position) // 恢复显示
                return
            }
            val removedSong = removedSongs[position]
            // 先临时从适配器移除（乐观更新）
            adapter.submitList(removedSongs.toMutableList().also { it.removeAt(position) })
            // 显示 Snackbar 撤销
            val snackbar = Snackbar.make(binding.root, "已从队列中移除", Snackbar.LENGTH_LONG)
                .setAction("撤销") {
                    // 撤销：重新添加歌曲到原位置
                    adapter.submitList(removedSongs) // 恢复原列表
                    // 同时需要重新设置播放列表（因为实际未删除，需要与 Service 同步）
                    playerViewModel.setPlaylist(removedSongs)
                }
                .addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (event != DISMISS_EVENT_ACTION) {
                            // 超时或手动滑动消失，执行真正删除
                            playerViewModel.removeSongPermanently(position)
                        }
                    }
                })
            snackbar.show()
        }
    })

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}