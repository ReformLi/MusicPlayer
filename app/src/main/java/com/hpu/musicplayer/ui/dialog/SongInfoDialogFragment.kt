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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SongInfoDialogFragment : DialogFragment() {

    private var _binding: DialogSongInfoBinding? = null
    private val binding get() = _binding!!
    private var history: PlayHistory? = null
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

        // 点击外部区域关闭
        binding.rootLayout.setOnClickListener { dismiss() }
        binding.dialogContent.setOnClickListener { } // 拦截点击内部不关闭

        setupClickListeners()
        isViewCreated = true
        updateContent() // 只在视图创建后更新内容
    }

    private fun setupClickListeners() {
        binding.btnClose.setOnClickListener {
            dismiss()
        }
    }

    private fun updateContent() {
        if (!isViewCreated) return

        history?.let { playHistory ->
            binding.tvTitle.text = playHistory.title
            binding.tvArtist.text = playHistory.artist
            binding.tvAlbum.text = playHistory.album
            binding.tvDuration.text = "总长 " + formatDuration(playHistory.duration)
            binding.tvPlayTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(playHistory.playedAt))

            // 结束时间
            if (playHistory.endTime != null) {
                binding.tvEndTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(playHistory.endTime))
                binding.layoutEndTime.visibility = View.VISIBLE
            } else {
                binding.layoutEndTime.visibility = View.GONE
            }

            // 收听时长（始终显示）
            if (playHistory.endTime == null) {
                binding.tvListenTime.text = "播放中..."
            } else {
                binding.tvListenTime.text = formatDuration(playHistory.thisDuration)
            }
            binding.layoutListenTime.visibility = View.VISIBLE
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