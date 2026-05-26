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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.databinding.FragmentSongsBinding
import com.hpu.musicplayer.ui.adapter.SongAdapter
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

class FavoritesFragment : Fragment() {

    private var _binding: FragmentSongsBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })
    private var allSongs = emptyList<Song>()
    private lateinit var adapter: SongAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true) // 可添加右上角菜单（如说明）
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSongsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SongAdapter(
            showMenu = true,
            showDeleteMenu = false,   // 不显示删除菜单
            onItemClick = { song ->
                playerViewModel.play(song)
                val bundle = Bundle().apply { putLong("songId", song.id) }
                findNavController().navigate(R.id.playerFragment, bundle)
            },
            onDeleteClick = { song -> removeFavorite(song) },  // 实际不会触发，但必须传
            onInfoClick = { song -> showSongInfo(song) },
            onFavoriteClick = { song -> toggleFavorite(song) }
        )

        binding.recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSongs.adapter = adapter

        val songDao = AppDatabase.getDatabase(requireContext()).songDao()
        lifecycleScope.launch {
            songDao.getFavoriteSongs().collect { songs ->
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

    private fun removeFavorite(song: Song) {
        lifecycleScope.launch {
            val updated = song.copy(isFavorite = false)
            AppDatabase.getDatabase(requireContext()).songDao().update(updated)
        }
    }

    private fun toggleFavorite(song: Song) {
        lifecycleScope.launch {
            val updated = song.copy(isFavorite = !song.isFavorite)
            AppDatabase.getDatabase(requireContext()).songDao().update(updated)
        }
    }

    private fun showSongInfo(song: Song) {
        val message = buildString {
            append("标题: ${song.title}\n")
            append("艺术家: ${song.artist}\n")
            append("专辑: ${song.album}\n")
            append("时长: ${formatDuration(song.duration)}\n")
            append("路径: ${song.path}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("歌曲信息")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun formatDuration(millis: Long): String {
        val minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}