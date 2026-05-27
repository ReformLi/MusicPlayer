package com.hpu.musicplayer.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.databinding.FragmentSongsBinding
import com.hpu.musicplayer.service.MusicService
import com.hpu.musicplayer.ui.adapter.SongAdapter
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SongsFragment : Fragment() {

    private var _binding: FragmentSongsBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })

    private var allSongs = emptyList<Song>()
    private lateinit var adapter: SongAdapter

    // 文件夹选择器
    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            requireActivity().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val bundle = Bundle().apply {
                putStringArrayList("folderUris", arrayListOf(uri.toString()))
            }
            findNavController().navigate(R.id.scanResultFragment, bundle)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_all -> {
                val bundle = Bundle().apply { putBoolean("scanAll", true) }
                findNavController().navigate(R.id.scanResultFragment, bundle)
                return true
            }
            R.id.action_scan_custom -> {
                selectFolderLauncher.launch(null)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
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

        // 1. 先创建 adapter
        adapter = SongAdapter(
            onItemClick = { song ->
//                Toast.makeText(requireContext(), "点击了: ${song.title}", Toast.LENGTH_SHORT).show()
                playerViewModel.play(song)
                val action = SongsFragmentDirections.actionSongsFragmentToPlayerFragment(song.id)
                findNavController().navigate(action)
            },
            onDeleteClick = { song -> deleteSong(song) },
            onInfoClick = { song -> showSongInfo(song) },
            onFavoriteClick = { song -> toggleFavorite(song) }
        )

        binding.recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSongs.adapter = adapter

        // 2. 监听数据库歌曲列表
        val songDao = AppDatabase.Companion.getDatabase(requireContext()).songDao()
        viewLifecycleOwner.lifecycleScope.launch {
            songDao.getAllSongs().collect { songs ->
                allSongs = songs
                adapter.submitList(songs)
                playerViewModel.setPlaylist(songs)
                binding.emptyView.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        // 3. 监听当前播放歌曲变化，更新列表高亮（此时 adapter 已初始化）
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.playerState.collect { data ->
                val songId = data.currentSong?.id ?: -1
                adapter.updateCurrentSongId(songId)
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
                // 有焦点时就显示清除按钮（即使内容为空）
                clearButton.visibility = View.VISIBLE
            } else {
                // 失去焦点时，显示默认hint
                searchEditText.hint = "搜索本地音乐"
                // 失去焦点时隐藏清除按钮
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
                    } else {
                        // 有内容时清空hint，避免与文字重叠
                        searchEditText.hint = ""
                    }
                }

                // 控制清除按钮的显示/隐藏：焦点由监听管理，此处不再处理
                // 保持原有逻辑即可，因为焦点监听已经处理了可见性

                val filtered = if (query.isEmpty()) allSongs
                else allSongs.filter { song ->
                    song.title.contains(query, ignoreCase = true) ||
                            song.artist.contains(query, ignoreCase = true) ||
                            song.album.contains(query, ignoreCase = true)
                }
                adapter.submitList(filtered)
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // 清除按钮点击事件（新逻辑）
        clearButton.setOnClickListener {
            if (searchEditText.text.isNotEmpty()) {
                // 有文字：清空文字，保留焦点和清除按钮
                searchEditText.setText("")
                // 手动恢复hint并确保光标可见
                if (searchEditText.hasFocus()) {
                    searchEditText.hint = "搜索本地歌曲、歌手"
                }
                // 不清除焦点，不清除按钮，焦点监听已设置可见性，无需再设
            } else {
                // 没有文字：退出搜索状态
                searchEditText.clearFocus()
                // 关闭软键盘
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
                // 焦点监听会自动将清除按钮隐藏
            }
        }

        // 添加点击监听器使整个搜索框可点击
        binding.root.findViewById<com.google.android.material.card.MaterialCardView>(R.id.searchCardView)?.setOnClickListener {
            searchEditText.requestFocus()
            searchEditText.requestFocusFromTouch()
        }

        // ===== 新增返回键监听 =====
        searchEditText.setOnKeyListener { v, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                // 当搜索框有焦点时，消费返回键事件
                if (searchEditText.hasFocus()) {
                    searchEditText.clearFocus()
                    val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
                    // 焦点监听会自动隐藏清除按钮
                    return@setOnKeyListener true   // 表示事件已被消费
                }
            }
            false   // 未消费，继续传递给 Activity
        }
    }

    // ---------- 歌曲操作 ----------
    private fun deleteSong(song: Song) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除歌曲")
            .setMessage("确定要删除 ${song.title} 吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val db = AppDatabase.Companion.getDatabase(requireContext())
                    db.songDao().delete(song)
                    // 若正在播放该歌曲，停止服务
                    val service = MusicService.Companion.getInstance()
                    if (service != null && MusicService.Companion.playerState.value.currentSong?.id == song.id) {
                        service.stopPlayback()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun toggleFavorite(song: Song) {
        lifecycleScope.launch {
            val updated = song.copy(isFavorite = !song.isFavorite)
            AppDatabase.Companion.getDatabase(requireContext()).songDao().insertAll(listOf(updated))
            // 因为 Room 主键冲突会替换，ListAdapter 会通过 collect 自动刷新
        }
    }

    private fun formatDuration(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}