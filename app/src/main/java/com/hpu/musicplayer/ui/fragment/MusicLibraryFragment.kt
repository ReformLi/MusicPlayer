package com.hpu.musicplayer.ui.fragment

import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}