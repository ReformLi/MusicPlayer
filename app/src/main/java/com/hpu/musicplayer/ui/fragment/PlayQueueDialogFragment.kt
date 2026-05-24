package com.hpu.musicplayer.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hpu.musicplayer.R
import com.hpu.musicplayer.databinding.DialogPlayQueueBinding
import com.hpu.musicplayer.service.PlayMode
import com.hpu.musicplayer.ui.adapter.QueueDialogAdapter
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

class PlayQueueDialogFragment : DialogFragment() {

    private var _binding: DialogPlayQueueBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })
    private lateinit var adapter: QueueDialogAdapter

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
            onDeleteClick = { index -> deleteSong(index) }
        )

        binding.rvQueue.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQueue.adapter = adapter

        // 观察播放列表
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.playlist.collect { list ->
                adapter.submitList(list)
            }
        }
        // 观察当前索引
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.currentPlayIndex.collect { index ->
                adapter.updateCurrentIndex(index)
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
                binding.tvSongCount.visibility = if (mode == PlayMode.SINGLE_LOOP) View.GONE else View.VISIBLE
                binding.tvSongCount.text = "(${adapter.itemCount}首)"
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

        binding.rootLayout.setOnClickListener {
            dismiss()
        }

        // 关闭弹框
        binding.tvClose.setOnClickListener { dismiss() }
        binding.root.setOnClickListener { dismiss() }   // 点击顶部空白区关闭
    }

    private fun deleteSong(index: Int) {
        playerViewModel.removeSong(index)   // 直接删除，不撤销
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}