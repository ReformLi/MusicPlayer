package com.hpu.musicplayer.ui.dialog

import android.app.Dialog
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

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        return dialog
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
        // 用 viewLifecycleOwner + IO 前捕获 context：弹窗关闭（view 销毁）后协程自动取消，
        // 且 IO 线程内不再调用 requireContext()（detach 后会抛异常）
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val cacheSize = getCoverCacheSize(appContext)
            _binding?.tvCacheSize?.text = "封面缓存大小: $cacheSize"
        }

        // 清除缓存
        binding.btnClearCache.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                clearCoverCache(appContext)
                val newSize = getCoverCacheSize(appContext)
                // 异步回来时弹窗可能已关闭，判空防 NPE
                _binding?.tvCacheSize?.text = "封面缓存大小: $newSize"
                if (isAdded) {
                    Toast.makeText(appContext, "扫描封面缓存已清除", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 关闭
        binding.tvClose.setOnClickListener { dismiss() }
    }

    /**
     * 统计扫描封面缓存大小。
     * 注意范围：仅 filesDir/album_art（扫描时从音频文件提取，可重新扫描再生）。
     * filesDir/covers 是用户自定义封面（SAF 权限已失效，删了无法重建），不属于缓存，不统计。
     */
    private suspend fun getCoverCacheSize(context: android.content.Context): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "album_art")
        val total = if (dir.exists()) dir.walkTopDown().sumOf { if (it.isFile) it.length() else 0L } else 0L
        formatFileSize(total)
    }

    /**
     * 清除扫描封面缓存（album_art，可通过重新扫描重建）。
     * 绝不能删 filesDir/covers：那是用户手动设置的自定义封面，源 SAF 权限已失效，删除即永久丢失。
     */
    private suspend fun clearCoverCache(context: android.content.Context) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "album_art")
        if (dir.exists()) {
            dir.listFiles()?.forEach { file ->
                if (!file.delete()) {
                    Log.w("CacheManagement", "Failed to delete ${file.name}")
                }
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
