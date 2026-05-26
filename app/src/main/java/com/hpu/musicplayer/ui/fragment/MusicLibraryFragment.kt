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
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.databinding.FragmentSongsBinding
import com.hpu.musicplayer.ui.adapter.SongAdapter
import kotlinx.coroutines.launch

class MusicLibraryFragment : Fragment() {
    private var _binding: FragmentSongsBinding? = null
    private val binding get() = _binding!!
    private var allSongs = emptyList<com.hpu.musicplayer.data.Song>()
    private lateinit var adapter: SongAdapter

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
            onFavoriteClick = {}
        )

        binding.recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSongs.adapter = adapter

        val songDao = AppDatabase.getDatabase(requireContext()).songDao()
        lifecycleScope.launch {
            songDao.getAllSongs().collect { songs ->
                allSongs = songs
                adapter.submitList(songs)
            }
        }

        setupSearchView()
    }

    private fun setupSearchView() {
        val searchEditText = binding.root.findViewById<EditText>(R.id.searchEditText)
        val clearButton = binding.root.findViewById<ImageView>(R.id.ivClear)

        // 设置初始hint文本
        searchEditText.hint = "搜索本地音乐"

        // 搜索框焦点变化监听
        searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // 获得焦点时，如果内容为空，显示详细hint
                if (searchEditText.text.isEmpty()) {
                    searchEditText.hint = "搜索本地歌曲、歌手"
                }
                // 有焦点时就显示清除按钮
                clearButton.visibility = View.VISIBLE
            } else {
                // 失去焦点时，显示默认hint
                searchEditText.hint = "搜索本地音乐"
                clearButton.visibility = View.GONE
            }
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""

                // 根据是否有焦点来设置不同的hint文本
                if (searchEditText.hasFocus()) {
                    if (query.isEmpty()) {
                        searchEditText.hint = "搜索本地歌曲、歌手"
                    }
                }

                // 控制清除按钮的显示/隐藏
                if (searchEditText.hasFocus()) {
                    clearButton.visibility = View.VISIBLE
                } else {
                    clearButton.visibility = View.GONE
                }

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

        // 清除按钮点击事件
        clearButton.setOnClickListener {
            if (searchEditText.text.isNotEmpty()) {
                // 如果有文字，清空文字
                searchEditText.setText("")
                searchEditText.hint = "搜索本地歌曲、歌手"
                clearButton.visibility = View.GONE
                searchEditText.requestFocus()
            } else {
                // 如果没有文字，失去焦点
                searchEditText.clearFocus()
                searchEditText.hint = "搜索本地音乐"
                clearButton.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}