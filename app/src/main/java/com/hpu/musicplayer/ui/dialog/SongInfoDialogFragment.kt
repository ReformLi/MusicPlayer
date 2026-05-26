package com.hpu.musicplayer.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.PlayHistory
import com.hpu.musicplayer.databinding.DialogSongInfoBinding
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch

class SongInfoDialogFragment : DialogFragment() {

    private var _binding: DialogSongInfoBinding? = null
    private val binding get() = _binding!!
    private var history: PlayHistory? = null
    private lateinit var playerViewModel: PlayerViewModel
    private var isViewCreated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.QueueDialogTheme)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSongInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化 ViewModel
        playerViewModel = ViewModelProvider(requireActivity())[PlayerViewModel::class.java]

        // 点击外部区域关闭
        binding.rootLayout.setOnClickListener { dismiss() }
        binding.dialogContent.setOnClickListener { } // 拦截点击内部不关闭

        setupClickListeners()
        isViewCreated = true
        updateContent() // 只在视图创建后更新内容
    }

    private fun setupClickListeners() {
        binding.btnPlay.setOnClickListener {
            history?.let { playHistory ->
                // 创建 Song 对象并播放
                val song = com.hpu.musicplayer.data.Song(
                    id = playHistory.songId,
                    title = playHistory.title,
                    artist = playHistory.artist,
                    album = playHistory.album,
                    duration = playHistory.duration,
                    path = playHistory.path,
                    coverPath = playHistory.coverPath,
                    lrcPath = playHistory.lrcPath
                )
                playerViewModel?.play(song)
                dismiss()
            }
        }

        binding.btnClose.setOnClickListener {
            dismiss()
        }
    }

    private fun updateContent() {
        if (!isViewCreated || !::playerViewModel.isInitialized) return

        history?.let { playHistory ->
            binding.tvTitle.text = playHistory.title
            binding.tvArtist.text = playHistory.artist
            binding.tvAlbum.text = playHistory.album
            binding.tvDuration.text = formatDuration(playHistory.duration)
            binding.tvPlayTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(playHistory.playedAt))
        }
    }

    private fun formatDuration(duration: Long): String {
        val minutes = duration / 60000
        val seconds = (duration % 60000) / 1000
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun setHistory(history: PlayHistory) {
        this.history = history
        if (isViewCreated) {
            updateContent()
        }
    }

    // No longer needed as ViewModel is initialized in onViewCreated

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): SongInfoDialogFragment {
            return SongInfoDialogFragment()
        }
    }
}