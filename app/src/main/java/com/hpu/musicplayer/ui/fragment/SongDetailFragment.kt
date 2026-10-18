package com.hpu.musicplayer.ui.fragment

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import coil.load
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.databinding.FragmentSongDetailBinding
import com.hpu.musicplayer.utils.CoverMigration
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SongDetailFragment : Fragment() {
    private var _binding: FragmentSongDetailBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })
    private var currentSong: Song? = null
    private var songId: Long = -1

    // 选择封面图片
    private val pickCoverLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { saveCustomCover(it) }
    }
    // 选择歌词文件
    private val pickLrcLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { saveCustomLrc(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        songId = arguments?.getLong("songId", -1) ?: -1
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSongDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSong()

        binding.btnChangeCover.setOnClickListener {
            pickCoverLauncher.launch(arrayOf("image/*"))
        }
        binding.btnChangeLrc.setOnClickListener {
            pickLrcLauncher.launch(arrayOf("*/*")) // 可选择文本文件
        }
        binding.btnSave.setOnClickListener { saveSongDetails() }
        binding.btnCancel.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun loadSong() {
        lifecycleScope.launch {
            currentSong = playerViewModel.getSongById(songId)
            currentSong?.let { song ->
                binding.etTitle.setText(song.title)
                binding.etArtist.setText(song.artist)
                binding.etAlbum.setText(song.album)
                binding.tvType.text = "类型: ${song.path.substringAfterLast('.', "未知").uppercase()}"
                binding.tvDuration.text = "时长: ${formatDuration(song.duration)}"
                binding.tvFileSize.text = "文件大小: ${formatFileSize(song.fileSize)}"
                binding.tvFilePath.text = "路径: ${song.path}"

                val coverPath = song.customCoverPath ?: song.coverPath
                binding.tvCoverPath.text = "封面: ${coverPath ?: "无"}"
                if (!coverPath.isNullOrEmpty()) {
                    binding.ivDetailCover.load(File(coverPath)) {
                        placeholder(R.drawable.ic_music_note)
                        error(R.drawable.ic_music_note)
                    }
                }

                val lrcPath = song.customLrcPath ?: song.lrcPath
                binding.tvLrcPath.text = "歌词: ${lrcPath ?: "无"}"

                binding.swFavorite.isChecked = song.isFavorite
                binding.tvAddedDate.text = "创建时间: ${formatDate(song.addedDate)}"
            }
        }
    }

    private fun formatDate(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    private fun saveCustomCover(uri: Uri) {
        val song = currentSong ?: return
        val context = requireContext()
        lifecycleScope.launch {
            // SAF 读流 + 文件复制移到 IO 线程（回调在主线程，大图会卡 UI）
            val savedPath = withContext(Dispatchers.IO) {
                try {
                    val destFile = CoverMigration.getCoverFile(context, song.id)
                    // 临时文件 + 原子重命名：中途失败不留半成品（与 CoverMigration 一致）
                    val tmpFile = File(destFile.parentFile, destFile.name + ".tmp")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext null
                    if (!tmpFile.renameTo(destFile)) {
                        tmpFile.copyTo(destFile, overwrite = true)
                        tmpFile.delete()
                    }
                    // 删除旧自定义封面，避免残留
                    song.customCoverPath?.let { oldPath ->
                        val oldFile = File(oldPath)
                        if (oldFile.exists() && oldFile.absolutePath != destFile.absolutePath) {
                            oldFile.delete()
                        }
                    }
                    song.customCoverPath = destFile.absolutePath
                    destFile.absolutePath
                } catch (e: Exception) {
                    null
                }
            }
            if (savedPath != null) {
                playerViewModel.updateSong(song)
                if (_binding != null) {
                    binding.ivDetailCover.load(File(savedPath))
                    binding.tvCoverPath.text = "封面: $savedPath"
                }
            } else {
                Toast.makeText(context, "保存封面失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveCustomLrc(uri: Uri) {
        val song = currentSong ?: return
        val context = requireContext()
        lifecycleScope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                try {
                    val destFile = File(context.filesDir, "custom_lrcs/${songId}.lrc")
                    destFile.parentFile?.mkdirs()
                    val tmpFile = File(destFile.parentFile, destFile.name + ".tmp")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext null
                    if (!tmpFile.renameTo(destFile)) {
                        tmpFile.copyTo(destFile, overwrite = true)
                        tmpFile.delete()
                    }
                    song.customLrcPath = destFile.absolutePath
                    destFile.absolutePath
                } catch (e: Exception) {
                    null
                }
            }
            if (savedPath != null) {
                playerViewModel.updateSong(song)
                if (_binding != null) {
                    binding.tvLrcPath.text = "歌词: $savedPath"
                }
            } else {
                Toast.makeText(context, "保存歌词失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateCoverPreview(path: String) {
        binding.tvCoverPath.text = "封面: $path"
        binding.ivDetailCover.load(File(path)) {
            placeholder(R.drawable.ic_music_note)
            error(R.drawable.ic_music_note)
        }
    }

    private fun saveSongDetails() {
        val song = currentSong ?: return
        song.title = binding.etTitle.text.toString().ifBlank { song.title }
        song.artist = binding.etArtist.text.toString().ifBlank { song.artist }
        song.album = binding.etAlbum.text.toString().ifBlank { song.album }
        song.isFavorite = binding.swFavorite.isChecked

        lifecycleScope.launch {
            playerViewModel.updateSong(song)
            Toast.makeText(requireContext(), "保存成功", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
        }
    }

    private fun formatDuration(millis: Long): String {
        val minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}