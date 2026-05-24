package com.hpu.musicplayer.ui.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.PlayHistory
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.data.dao.PlayHistoryDao
import com.hpu.musicplayer.databinding.FragmentHistoryBinding
import com.hpu.musicplayer.ui.adapter.HistoryAdapter
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
import okhttp3.internal.concurrent.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })
    private lateinit var historyDao: PlayHistoryDao
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_history, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_clear_all) {
            AlertDialog.Builder(requireContext())
                .setTitle("清空全部历史")
                .setMessage("确定要删除所有播放历史吗？")
                .setPositiveButton("确定") { _, _ ->
                    lifecycleScope.launch {
                        historyDao.deleteAll()
                        // 列表清空
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = HistoryAdapter { history ->
            val song = Song(
                id = history.songId,
                title = history.title,
                artist = history.artist,
                album = history.album,
                duration = history.duration,
                path = history.path,
                coverPath = history.coverPath,
                lrcPath = history.lrcPath
            )
//            playerViewModel.play(song)
            // 不播放，仅显示信息（可选）
            showHistoryInfo(history)
        }

        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter

        val historyDao = AppDatabase.Companion.getDatabase(requireContext()).playHistoryDao()
        lifecycleScope.launch {
            historyDao.getAllHistory().collect { list ->
                adapter.submitList(list)
            }
        }

        // 滑动删除
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val history = adapter.currentList[position]
                // 从数据库删除
                lifecycleScope.launch { historyDao.delete(history) }
                // 显示 Snackbar 允许撤销
                Snackbar.make(binding.root, "已从历史中移除", Snackbar.LENGTH_LONG)
                    .setAction("撤销") {
                        // 重新插入即可恢复
                        lifecycleScope.launch { historyDao.insert(history) }
                    }
                    .show()
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvHistory)

        // 清空全部
//        binding.fabClearHistory.setOnClickListener {
//            lifecycleScope.launch {
//                historyDao.deleteAll()
//            }
//        }
    }

    private fun showHistoryInfo(history: PlayHistory) {
        val message = buildString {
            append("标题: ${history.title}\n")
            append("艺术家: ${history.artist}\n")
            append("专辑: ${history.album}\n")
            append("时长: ${formatDuration(history.duration)}\n")
            append("路径: ${history.path}\n")
            append("上次播放: ${
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(
                    Date(history.playedAt)
            )}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("歌曲信息")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}