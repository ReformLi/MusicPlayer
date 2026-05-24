package com.hpu.musicplayer.ui.fragment

import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
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
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false

            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText.orEmpty().trim()
                val filtered = if (query.isEmpty()) allSongs
                else allSongs.filter {
                    it.title.contains(query, true) ||
                            it.artist.contains(query, true) ||
                            it.album.contains(query, true)
                }
                adapter.submitList(filtered)
                return true
            }
        })
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