package com.hpu.musicplayer.ui.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.databinding.FragmentPlayerBinding
import com.hpu.musicplayer.service.PlayMode
import com.hpu.musicplayer.service.PlaybackState
import com.hpu.musicplayer.service.PlayerData
import com.hpu.musicplayer.ui.adapter.LyricAdapter
import com.hpu.musicplayer.ui.fragment.PlayQueueDialogFragment
import com.hpu.musicplayer.utils.LrcLine
import com.hpu.musicplayer.utils.LrcParser
import com.hpu.musicplayer.utils.LyricConfig
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })

    private var songId: Long = -1L
    private val lyricAdapter = LyricAdapter()
    private var lrcLines: List<LrcLine> = emptyList()
    private var lastSongId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            songId = it.getLong("songId", -1)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 歌词 RecyclerView 初始化
        lyricAdapter.fontSizeSp = LyricConfig.getFontSize(requireContext())
        binding.rvLyrics.adapter = lyricAdapter
        binding.rvLyrics.layoutManager = LinearLayoutManager(requireContext())

        // 使用 viewLifecycleOwner.lifecycleScope 确保视图销毁时自动取消
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.playerState.collect { data ->
                updateUI(data)
            }
        }

        setupControls()
        loadCurrentSong()

        // 观察剩余时间并显示（例如在 tvSongTitle 下方加一个 TextView）
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.timerRemaining.collect { remaining ->
                if (remaining > 0) {
                    val seconds = remaining / 1000
                    binding.tvTimer.text = "⏳ ${seconds / 60}:${String.format("%02d", seconds % 60)}"
                    binding.tvTimer.visibility = View.VISIBLE
                } else {
                    binding.tvTimer.visibility = View.GONE
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    findNavController().popBackStack(R.id.songsFragment, false)
                }
            }
        )
    }

    fun PopupMenu.setForceShowIcons() {
        try {
            val fields = this.javaClass.getDeclaredFields()
            for (field in fields) {
                if ("mPopup" == field.name) {
                    field.isAccessible = true
                    val menuPopupHelper = field.get(this)
                    val classPopupHelper = Class.forName(menuPopupHelper.javaClass.name)
                    val setForceIcons = classPopupHelper.getMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
                    setForceIcons.invoke(menuPopupHelper, true)
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun updateUI(data: PlayerData) {
        val song = data.currentSong
        if (song != null) {
            // 歌曲信息
            binding.tvSongTitle.text = song.title
            binding.tvSongArtist.text = song.artist
            binding.tvCurrentTime.text = formatTime(data.progress)
            binding.tvTotalTime.text = formatTime(data.duration)

            // 封面
            if (!song.coverPath.isNullOrEmpty()) {
                binding.ivAlbumArt.load(File(song.coverPath)) {
                    placeholder(R.drawable.ic_music_note)
                    error(R.drawable.ic_music_note)
                }
            } else {
                binding.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
            }

            // 歌曲切换时加载歌词
            if (song.id != lastSongId) {
                lastSongId = song.id
                loadLyrics(song)
                Log.d("PlayerFragment", "歌词行数: ${lrcLines.size}, path: ${song.lrcPath}")
//                Toast.makeText(requireContext(), "歌词行数1: ${lyricAdapter.itemCount}", Toast.LENGTH_SHORT).show()
            }
//            Log.d("PlayerFragment", "lyricContainer height: ${binding.lyricContainer.height}, rvLyrics height: ${binding.rvLyrics.height}")
            // 歌词高亮与滚动
            if (lrcLines.isNotEmpty()) {
                val index = lrcLines.indexOfLast { it.time <= data.progress }
                if (index != -1) {
                    lyricAdapter.updateCurrentIndex(index)
                    // 居中显示当前歌词
                    val offset = binding.rvLyrics.height / 2
                    (binding.rvLyrics.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(index, offset)
                }
            }

            // 进度条（仅在非拖动时更新）
            if (!binding.seekBar.isPressed) {
                binding.seekBar.max = data.duration.toInt()
                binding.seekBar.progress = data.progress.toInt()
            }

            // 播放/暂停图标
            binding.btnPlayPause.setImageResource(
                if (data.state == PlaybackState.PLAYING) R.drawable.ic_pause
                else R.drawable.ic_play
            )

            // 歌词区域可见性
//            binding.tvNoLyrics.visibility = if (lrcLines.isEmpty()) View.VISIBLE else View.GONE
//            binding.rvLyrics.visibility = if (lrcLines.isEmpty()) View.GONE else View.VISIBLE
            // 歌词区域始终可见（占用固定高度）
            binding.rvLyrics.visibility = View.VISIBLE
            if (lrcLines.isEmpty()) {
                binding.tvNoLyrics.visibility = View.VISIBLE
                lyricAdapter.submitList(emptyList())   // 清空旧歌词
            } else {
                binding.tvNoLyrics.visibility = View.GONE
            }

        } else {
            // 无歌曲时重置界面
            binding.tvSongTitle.text = "未选择歌曲"
            binding.tvSongArtist.text = ""
            binding.tvCurrentTime.text = "00:00"
            binding.tvTotalTime.text = "00:00"
            binding.seekBar.progress = 0
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            binding.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
            binding.tvNoLyrics.visibility = View.VISIBLE
            binding.rvLyrics.visibility = View.GONE
        }
    }

    private fun loadLyrics(song: Song) {
        lrcLines = if (song.lrcPath != null) {
            LrcParser.parse(song.lrcPath)
        } else emptyList()
        lyricAdapter.submitList(lrcLines)
    }

    private fun loadCurrentSong() {
        if (songId == -1L) return

        // 如果当前正在播放同一首歌，什么都不做（UI 会从 playerState 自动更新）
        val currentSongId = playerViewModel.playerState.value.currentSong?.id
        if (currentSongId == songId) return

        // 否则从数据库加载并播放
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.Companion.getDatabase(requireContext())
            val song = db.songDao().getSongById(songId)
            song?.let { playerViewModel.play(it) }
        }
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            playerViewModel.togglePlayPause()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) playerViewModel.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnNext.setOnClickListener {
            playerViewModel.playNext()
        }

        binding.btnPrev.setOnClickListener {
            playerViewModel.playPrevious()
        }

        binding.btnMenu.setOnClickListener { view ->
            val popup = PopupMenu(requireContext(), view)
            popup.menuInflater.inflate(R.menu.menu_player_more, popup.menu)
            popup.setForceShowIcons()
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_play_queue -> {
                        val dialog = PlayQueueDialogFragment()
                        dialog.show(parentFragmentManager, "PlayQueueDialog")
                        true
                    }
                    R.id.action_timer -> {
                        showTimerDialog()
                        true
                    }
                    R.id.action_lyric_font_size -> {
                        showLyricFontSizeDialog()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

//        // 监听播放模式，更新图标
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.playMode.collect { mode ->
                val icon = when (mode) {
                    PlayMode.LIST_LOOP -> R.drawable.ic_mode_list_loop
                    PlayMode.SINGLE_LOOP -> R.drawable.ic_mode_single_loop
                    PlayMode.RANDOM -> R.drawable.ic_mode_random
                }
                binding.btnMode.setImageResource(icon)
            }
        }

        binding.btnMode.setOnClickListener {
            Log.d("PlayerFragment", "btnMode clicked")
            playerViewModel.cyclePlayMode()
        }
    }

    private fun showLyricFontSizeDialog() {
        val currentSize = LyricConfig.getFontSize(requireContext())
        val seekBar = SeekBar(requireContext()).apply {
            max = 24              // 最大 24sp
            progress = currentSize.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    // 实时更新歌词大小
                    lyricAdapter.updateFontSize(progress.toFloat())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        AlertDialog.Builder(requireContext())
            .setTitle("歌词字体大小 (${currentSize.toInt()}sp)")
            .setView(seekBar)
            .setPositiveButton("确定") { dialog, _ ->
                val newSize = seekBar.progress.toFloat()
                LyricConfig.setFontSize(requireContext(), newSize)
                lyricAdapter.updateFontSize(newSize)
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                // 恢复原大小
                lyricAdapter.updateFontSize(currentSize)
                dialog.dismiss()
            }
            .show()
    }

    private fun showTimerDialog() {
        val minutes = arrayOf("15", "30", "45", "60", "90", "120")
        AlertDialog.Builder(requireContext())
            .setTitle("定时停止播放（分钟）")
            .setItems(minutes) { _, which ->
                val min = minutes[which].toInt()
                playerViewModel.startTimer(min)
            }
            .setNegativeButton("取消定时") { _, _ ->
                playerViewModel.cancelTimer()
            }
            .show()
    }

    private fun formatTime(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}