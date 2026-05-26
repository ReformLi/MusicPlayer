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
import com.hpu.musicplayer.ui.dialog.SongInfoDialogFragment
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
            showSongInfoDialog(history)
        }

        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter

        // 添加平滑的 item 动画
        binding.rvHistory.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 300
            removeDuration = 300
            moveDuration = 300
            changeDuration = 300
        }

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

    private fun showSongInfoDialog(history: PlayHistory) {
        val dialog = SongInfoDialogFragment.newInstance().apply {
            setHistory(history)
        }
        dialog.show(parentFragmentManager, "SongInfoDialog")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}