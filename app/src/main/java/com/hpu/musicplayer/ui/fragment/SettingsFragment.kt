package com.hpu.musicplayer.ui.fragment

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.PlaybackStateEntity
import com.hpu.musicplayer.databinding.FragmentSettingsBinding
import com.hpu.musicplayer.service.MusicService
import com.hpu.musicplayer.service.PlayMode
import com.hpu.musicplayer.ui.dialog.CacheManagementDialogFragment
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvCacheManagement.setOnClickListener {
            CacheManagementDialogFragment().show(parentFragmentManager, "CacheDialog")
        }

        binding.tvResetLibrary.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("重置歌曲库")
                .setMessage("确定要删除所有歌曲数据吗？这将清空本地歌曲库和播放历史，你需要重新扫描添加歌曲。")
                .setPositiveButton("确定") { _, _ -> resetLibrary() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun resetLibrary() {
        lifecycleScope.launch {
            // 1. 停止当前播放并清除数据（stopPlayback 会 stopSelf）
            MusicService.getInstance()?.stopPlayback()

            // 2. 删除数据库内容
            val db = AppDatabase.getDatabase(requireContext())
            db.songDao().deleteAll()
            db.playHistoryDao().deleteAll()
            db.playbackStateDao().saveState(
                PlaybackStateEntity(currentSongId = -1, position = 0, playMode = PlayMode.LIST_LOOP.name)
            )

            // 3. 重新启动服务（之前被 stopSelf 了）
            val intent = Intent(requireContext(), MusicService::class.java)
            requireContext().startService(intent)

            Toast.makeText(requireContext(), "歌曲库已重置，请重新扫描歌曲", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}