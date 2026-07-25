package com.hpu.musicplayer.ui.fragment

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.EditText
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.hpu.musicplayer.R
import com.hpu.musicplayer.databinding.FragmentSongsBinding
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import androidx.fragment.app.viewModels
import com.hpu.musicplayer.ui.adapter.SongAdapter
import kotlinx.coroutines.launch

class MusicLibraryFragment : Fragment() {
    private var _binding: FragmentSongsBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })
    private var allSongs = emptyList<com.hpu.musicplayer.data.Song>()
    private lateinit var adapter: SongAdapter

    private var currentQuery = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSongsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 点击进入详情页，不播放
        adapter = SongAdapter(
            showMenu = false,
            onItemClick = { song ->
                val bundle = Bundle().apply { putLong("songId", song.id) }
                findNavController().navigate(R.id.songDetailFragment, bundle)
            },
            onDeleteClick = {},
            onInfoClick = {},
            onFavoriteClick = {},
            onAddToQueueClick = {}
        )

        binding.recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSongs.adapter = adapter

        lifecycleScope.launch {
            playerViewModel.allSongs.collect { songs ->
                allSongs = songs
                // 根据 currentQuery 决定显示全部还是过滤
                val displayList = if (currentQuery.isEmpty()) {
                    songs
                } else {
                    songs.filter {
                        it.title.contains(currentQuery, true) ||
                                it.artist.contains(currentQuery, true) ||
                                it.album.contains(currentQuery, true)
                    }
                }
                adapter.submitList(displayList)
            }
        }

        setupSearchView()
    }

    private fun setupSearchView() {
        val searchEditText = binding.root.findViewById<EditText>(R.id.searchEditText)
        val clearButton = binding.root.findViewById<ImageView>(R.id.ivClear)

        // 初始状态：提示文字
        searchEditText.hint = "搜索音乐库"

        // ================== 焦点变化监听 ==================
        searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // 获得焦点时，若无文字则切换为详细提示
                if (searchEditText.text.isEmpty()) {
                    searchEditText.hint = "搜索本地歌曲、歌手"
                } else {
                    searchEditText.hint = ""
                }
                // 有焦点就显示清除按钮
                clearButton.visibility = View.VISIBLE
            } else {
                // 失去焦点：恢复默认提示，隐藏清除按钮
                searchEditText.hint = "搜索音乐库"
                clearButton.visibility = View.GONE
            }
        }

        // ================== 文字变化监听 ==================
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                currentQuery = query   // 记录当前查询

                // 动态调整 hint：焦点内无文字显示长提示，有文字则清空 hint
                if (searchEditText.hasFocus()) {
                    if (query.isEmpty()) {
                        searchEditText.hint = "搜索本地歌曲、歌手"
                    } else {
                        searchEditText.hint = ""
                    }
                }

                // 清除按钮的可见性完全由焦点监听控制，此处仅作安全保障
                if (searchEditText.hasFocus()) {
                    clearButton.visibility = View.VISIBLE
                } else {
                    clearButton.visibility = View.GONE
                }

                // 过滤并显示歌曲
                val filtered = if (query.isEmpty()) allSongs
                else allSongs.filter {
                    it.title.contains(query, true) ||
                            it.artist.contains(query, true) ||
                            it.album.contains(query, true)
                }
                adapter.submitList(filtered)
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // ================== 清除按钮点击逻辑 ==================
        clearButton.setOnClickListener {
            if (searchEditText.text.isNotEmpty()) {
                // 情况1：输入框有文字 → 清空文字，但保留焦点和清除按钮
                searchEditText.setText("")
                currentQuery = ""   // 重置查询
                // 手动设置 hint（因为 setText 不会自动触发 focus 时的 hint 变化）
                searchEditText.hint = "搜索本地歌曲、歌手"
                // 保持清除按钮可见（焦点仍在）
                clearButton.visibility = View.VISIBLE
                // 可选：保持光标位置并弹出键盘
                searchEditText.requestFocus()
            } else {
                // 情况2：输入框无文字 → 退出搜索状态
                searchEditText.clearFocus()
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
                // 焦点监听会自动隐藏清除按钮，并恢复默认 hint
            }
        }

        // ================== 返回键拦截（解决 Activity 可能重写 onBackPressed 的问题） ==================
        searchEditText.setOnKeyListener { _, keyCode, event ->
            // 仅在按下返回键且抬起时处理，避免重复触发
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                if (searchEditText.hasFocus()) {
                    // 搜索框有焦点 → 清除焦点并关闭键盘，文字内容保留
                    searchEditText.clearFocus()
                    val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
                    // 返回 true 表示事件已被消费，不会继续传递到 Activity
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}