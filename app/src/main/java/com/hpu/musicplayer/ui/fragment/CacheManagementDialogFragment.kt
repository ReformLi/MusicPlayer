package com.hpu.musicplayer.ui.dialog

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.hpu.musicplayer.R
import com.hpu.musicplayer.databinding.DialogCacheManagementBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CacheManagementDialogFragment : DialogFragment() {

    private var _binding: DialogCacheManagementBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.QueueDialogTheme)   // 复用之前的透明主题
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogCacheManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 点击外部区域关闭
        binding.rootLayout.setOnClickListener { dismiss() }
        binding.dialogContent.setOnClickListener { } // 拦截点击内部不关闭

        // 显示缓存大小
        lifecycleScope.launch {
            val cacheSize = getCoverCacheSize()
            binding.tvCacheSize.text = "封面缓存大小: $cacheSize"
        }

        // 清除缓存
        binding.btnClearCache.setOnClickListener {
            lifecycleScope.launch {
                clearCoverCache()
                val newSize = getCoverCacheSize()
                binding.tvCacheSize.text = "封面缓存大小: $newSize"
                Toast.makeText(requireContext(), "缓存已清除", Toast.LENGTH_SHORT).show()
            }
        }

        // 关闭
        binding.tvClose.setOnClickListener { dismiss() }
    }

    private suspend fun getCoverCacheSize(): String = withContext(Dispatchers.IO) {
        val cacheDir = File(requireContext().cacheDir, "album_art")
        if (!cacheDir.exists()) return@withContext "0 B"
        val size = cacheDir.walkTopDown().sumOf { if (it.isFile) it.length() else 0L }
        formatFileSize(size)
    }

    private suspend fun clearCoverCache() = withContext(Dispatchers.IO) {
        val cacheDir = File(requireContext().cacheDir, "album_art")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}