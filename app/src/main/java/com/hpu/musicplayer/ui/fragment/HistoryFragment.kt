package com.hpu.musicplayer.ui.fragment

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
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
import com.hpu.musicplayer.databinding.FragmentHistoryBinding
import com.hpu.musicplayer.ui.adapter.HistoryAdapter
import com.hpu.musicplayer.ui.dialog.SongInfoDialogFragment
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })
    private var allHistory = emptyList<PlayHistory>()
    private lateinit var adapter: HistoryAdapter

    // 当前搜索关键词
    private var currentQuery = ""

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
                        AppDatabase.getDatabase(requireContext()).playHistoryDao().deleteAll()
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

        adapter = HistoryAdapter { history ->
            showSongInfoDialog(history)
        }

        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter
        binding.rvHistory.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 300
            removeDuration = 300
            moveDuration = 300
            changeDuration = 300
        }

        // 监听数据库变化
        val historyDao = AppDatabase.getDatabase(requireContext()).playHistoryDao()
        lifecycleScope.launch {
            historyDao.getAllHistory().collect { list ->
                allHistory = list
                applyFilter()   // 根据 currentQuery 过滤
            }
        }

        // 初始化搜索（完全复用 FavoritesFragment 的交互逻辑）
        setupSearchView()

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
                lifecycleScope.launch { historyDao.delete(history) }
                Snackbar.make(binding.root, "已从历史中移除", Snackbar.LENGTH_LONG)
                    .setAction("撤销") {
                        lifecycleScope.launch { historyDao.insert(history) }
                    }
                    .show()
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvHistory)

        // 空状态
        lifecycleScope.launch {
            historyDao.getAllHistory().collect { list ->
                binding.tvEmptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ========== 搜索逻辑（完全与 FavoritesFragment 一致） ==========
    private fun setupSearchView() {
        val searchEditText = binding.root.findViewById<EditText>(R.id.searchEditText)
        val clearButton = binding.root.findViewById<ImageView>(R.id.ivClear)

        searchEditText.hint = "搜索播放历史"

        searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (searchEditText.text.isEmpty()) {
                    searchEditText.hint = "搜索歌曲、歌手"
                } else {
                    searchEditText.hint = ""
                }
                clearButton.visibility = View.VISIBLE
            } else {
                searchEditText.hint = "搜索播放历史"
                clearButton.visibility = View.GONE
            }
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                currentQuery = query
                if (searchEditText.hasFocus()) {
                    if (query.isEmpty()) {
                        searchEditText.hint = "搜索歌曲、歌手"
                    } else {
                        searchEditText.hint = ""
                    }
                }
                if (searchEditText.hasFocus()) {
                    clearButton.visibility = View.VISIBLE
                }
                applyFilter()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        clearButton.setOnClickListener {
            if (searchEditText.text.isNotEmpty()) {
                searchEditText.setText("")
                currentQuery = ""
                searchEditText.hint = "搜索歌曲、歌手"
                clearButton.visibility = View.VISIBLE
                searchEditText.requestFocus()
            } else {
                searchEditText.clearFocus()
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
            }
        }

        searchEditText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                if (searchEditText.hasFocus()) {
                    searchEditText.clearFocus()
                    val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    private fun applyFilter() {
        val filtered = if (currentQuery.isEmpty()) {
            allHistory
        } else {
            allHistory.filter { history ->
                history.title.contains(currentQuery, true) ||
                        history.artist.contains(currentQuery, true) ||
                        (history.album?.contains(currentQuery, true) == true)
            }
        }
        adapter.submitList(filtered)
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