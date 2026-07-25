package com.hpu.musicplayer.ui.fragment

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.hpu.musicplayer.data.repository.MusicRepository
import com.hpu.musicplayer.databinding.DialogRankingsBinding
import com.hpu.musicplayer.databinding.FragmentHistoryBinding
import com.hpu.musicplayer.ui.adapter.HistoryAdapter
import com.hpu.musicplayer.ui.adapter.RankAdapter
import com.hpu.musicplayer.ui.dialog.SongInfoDialogFragment
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })
    private var allHistory = emptyList<PlayHistory>()
    private lateinit var adapter: HistoryAdapter
    private lateinit var repository: MusicRepository

    // Toolbar 中的时间范围下拉
    private var toolbarTimeRangeView: LinearLayout? = null
    private var toolbarTimeRangeText: TextView? = null

    // 时间范围
    private enum class TimeRange(val label: String, val days: Int) {
        WEEK("最近一周", 7),
        MONTH("最近一月", 30),
        YEAR("最近一年", 365),
        ALL("全部", Int.MAX_VALUE)
    }
    private var currentTimeRange = TimeRange.WEEK

    // 搜索
    private var currentQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        setHasOptionsMenu(true)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = MusicRepository.getInstance(requireContext())

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

        // 加载数据
        loadHistory()

        // 搜索
        setupSearchView()

        // 滑动删除
        setupSwipeToDelete()

        lifecycleScope.launch {
            repository.getAllPlayHistory().collect { list ->
                binding.tvEmptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ========== Toolbar 菜单 ==========
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_history_toolbar, menu)
        super.onCreateOptionsMenu(menu, inflater)

        // 设置时间范围下拉
        val timeRangeItem = menu.findItem(R.id.action_time_range)
        val actionView = timeRangeItem.actionView as? LinearLayout
        toolbarTimeRangeView = actionView
        toolbarTimeRangeText = actionView?.findViewById(R.id.tvTimeRange)

        actionView?.setOnClickListener {
            showTimeRangePopup(it)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_options -> {
                // 以选项按钮自身作为 PopupMenu 锚点，保证菜单显示在按钮正下方
                val anchor = requireActivity().findViewById<View>(R.id.action_options) ?: return false
                showOptionsPopup(anchor)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showTimeRangePopup(anchor: View) {
        val timeRanges = TimeRange.values()
        val wrappedCtx = ContextThemeWrapper(requireContext(), R.style.Theme_HpuMusicPlayer_PopupMenu)
        val popup = PopupMenu(wrappedCtx, anchor)
        timeRanges.forEachIndexed { index, range ->
            popup.menu.add(0, index, index, range.label)
        }
        popup.setOnMenuItemClickListener { item ->
            currentTimeRange = timeRanges[item.itemId]
            toolbarTimeRangeText?.text = currentTimeRange.label
            loadHistory()
            true
        }
        popup.show()
    }

    private fun showOptionsPopup(anchor: View) {
        val wrappedCtx = ContextThemeWrapper(requireContext(), R.style.Theme_HpuMusicPlayer_PopupMenu)
        val popup = PopupMenu(wrappedCtx, anchor)
        popup.menu.add(0, R.id.id_action_rankings, 0, "排行榜")
        popup.menu.add(0, R.id.id_action_clear_all, 1, "清空历史")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.id_action_rankings -> {
                    showRankingDialog()
                    true
                }
                R.id.id_action_clear_all -> {
                    showClearAllDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun loadHistory() {
        val since = getSinceTimestamp()
        val flow = if (currentTimeRange == TimeRange.ALL) {
            repository.getAllPlayHistory()
        } else {
            repository.getPlayHistorySince(since)
        }

        lifecycleScope.launch {
            flow.collect { list ->
                allHistory = list
                applyFilter()
            }
        }
    }

    private fun getSinceTimestamp(): Long {
        if (currentTimeRange == TimeRange.ALL) return 0L
        return System.currentTimeMillis() - currentTimeRange.days * 24L * 3600 * 1000
    }

    private fun showClearAllDialog() {
        val currentRange = currentTimeRange
        val dialogLayout = layoutInflater.inflate(R.layout.dialog_clear_history, null, false) as LinearLayout
        val tvMessage = dialogLayout.findViewById<TextView>(R.id.tvClearMessage)
        val rgRange = dialogLayout.findViewById<RadioGroup>(R.id.rgClearRange)
        val rbClearCurrent = dialogLayout.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.rbClearCurrent)

        // 根据时间范围决定显示内容
        if (currentRange == TimeRange.ALL) {
            // 全部模式：只显示提示文字
            tvMessage.visibility = View.VISIBLE
            rgRange.visibility = View.GONE
        } else {
            // 分级模式：显示范围选择
            tvMessage.visibility = View.GONE
            rgRange.visibility = View.VISIBLE
            rbClearCurrent.text = "仅清空${currentRange.label}"
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogLayout)
            .create()

        dialogLayout.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogLayout.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConfirm).setOnClickListener {
            lifecycleScope.launch {
                if (currentRange == TimeRange.ALL) {
                    repository.deleteAllPlayHistory()
                } else {
                    val selectedId = rgRange.checkedRadioButtonId
                    if (selectedId == R.id.rbClearAll) {
                        repository.deleteAllPlayHistory()
                    } else {
                        repository.deletePlayHistorySince(getSinceTimestamp())
                    }
                }
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    // ========== 排行榜弹窗 ==========
    private fun showRankingDialog() {
        val dialogBinding = DialogRankingsBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogBinding.root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }

        val rankAdapter = RankAdapter(sortByDuration = false)
        dialogBinding.rvRankings.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.rvRankings.adapter = rankAdapter

        // 标题
        dialogBinding.tvRankTitle.text = "排行榜 · ${currentTimeRange.label}"

        // 排序方式下拉
        var sortByDuration = false

        dialogBinding.sortDropdownLayout.setOnClickListener { anchor ->
            val sortOptions = arrayOf("按播放次数", "按播放时长")
            val wrappedCtx = ContextThemeWrapper(requireContext(), R.style.Theme_HpuMusicPlayer_PopupMenu)
            val popup = PopupMenu(wrappedCtx, anchor)
            sortOptions.forEachIndexed { index, label ->
                popup.menu.add(0, index, index, label)
            }
            popup.setOnMenuItemClickListener { item ->
                sortByDuration = item.itemId == 1
                dialogBinding.tvSortMode.text = sortOptions[item.itemId]
                rankAdapter.setSortMode(sortByDuration)
                loadRankings(rankAdapter, sortByDuration)
                true
            }
            popup.show()
        }

        // 关闭
        dialogBinding.btnRankClose.setOnClickListener { dialog.dismiss() }

        // 加载数据
        loadRankings(rankAdapter, sortByDuration)

        dialog.show()
    }

    private fun loadRankings(adapter: RankAdapter, sortByDuration: Boolean) {
        val since = getSinceTimestamp()
        val flow = if (sortByDuration) {
            repository.getRankByDuration(since)
        } else {
            repository.getRankByCount(since)
        }

        lifecycleScope.launch {
            flow.collect { list ->
                adapter.submitList(list)
            }
        }
    }

    // ========== 搜索逻辑 ==========
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
                clearButton.visibility = if (searchEditText.hasFocus()) View.VISIBLE else View.GONE
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

    // ========== 滑动删除 ==========
    private fun setupSwipeToDelete() {
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
                lifecycleScope.launch { repository.deletePlayHistory(history) }
                Snackbar.make(binding.root, "已从历史中移除", Snackbar.LENGTH_LONG)
                    .setAction("撤销") {
                        lifecycleScope.launch { repository.insertOrReplacePlayHistory(history) }
                    }
                    .show()
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvHistory)
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
