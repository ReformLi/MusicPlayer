package com.hpu.musicplayer.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.databinding.FragmentScanResultBinding
import com.hpu.musicplayer.databinding.ItemScanFolderBinding
import com.hpu.musicplayer.utils.ScanManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class ScanResultFragment : Fragment() {

    private var _binding: FragmentScanResultBinding? = null
    private val binding get() = _binding!!

    private var scanAllRequested = false
    private var customFolderUris: List<Uri>? = null
    private val songs = mutableListOf<Song>()
    private val folderMap = mutableMapOf<String, MutableList<Song>>()
    private val selectedFolders = mutableSetOf<String>()
    private lateinit var adapter: FolderAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            scanAllRequested = it.getBoolean("scanAll", false)
            val uriStrings = it.getStringArrayList("folderUris")
            if (uriStrings != null) {
                customFolderUris = uriStrings.map { u -> Uri.parse(u) }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScanResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化 adapter 和 RecyclerView
        adapter = FolderAdapter(folderMap, selectedFolders) { folder ->
            if (selectedFolders.contains(folder)) selectedFolders.remove(folder)
            else selectedFolders.add(folder)
            adapter.notifyDataSetChanged()
        }
        binding.rvFolders.adapter = adapter
        binding.rvFolders.layoutManager = LinearLayoutManager(requireContext())

        // 显示加载提示，禁用按钮
        binding.loadingContainer.visibility = View.VISIBLE
        binding.tvScanning.text = "正在扫描，已发现 0 首歌曲..."
        binding.btnAddSelected.isEnabled = false

        // 启动扫描，传入进度回调
        lifecycleScope.launch {
            try {
                val result = if (scanAllRequested) {
                    ScanManager.scanAll(requireContext()) { count ->
                        // 切回主线程更新UI
                        lifecycleScope.launch(Dispatchers.Main) {
                            binding.tvScanning.text = "正在扫描，已发现 $count 首歌曲..."
                        }
                    }
                } else {
                    ScanManager.scanFolders(requireContext(), customFolderUris ?: emptyList()) { count ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            binding.tvScanning.text = "正在扫描，已发现 $count 首歌曲..."
                        }
                    }
                }

                songs.clear()
                songs.addAll(result)

                // 按目录分组
                folderMap.clear()
                for (song in songs) {
                    val dir = getDirectoryName(song.path)
                    folderMap.getOrPut(dir) { mutableListOf() }.add(song)
                }

                // 更新摘要和列表
                binding.tvScanSummary.text = "共扫描到 ${songs.size} 首歌曲，分布在 ${folderMap.size} 个目录中"
                adapter.updateData(folderMap)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "扫描失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.loadingContainer.visibility = View.GONE
                binding.btnAddSelected.isEnabled = true
            }
        }

        // 添加按钮点击事件
        binding.btnAddSelected.setOnClickListener {
            if (selectedFolders.isEmpty()) {
                Toast.makeText(requireContext(), "请至少选择一个目录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedSongs = mutableListOf<Song>()
            for (folder in selectedFolders) {
                folderMap[folder]?.let { selectedSongs.addAll(it) }
            }
            lifecycleScope.launch {
                val db = AppDatabase.Companion.getDatabase(requireContext())
                db.songDao().deleteAll()
                db.songDao().insertAll(selectedSongs)
                Toast.makeText(requireContext(), "已添加 ${selectedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
        }
    }

    private fun getDirectoryName(path: String): String {
        // 处理 content:// URI（自定义扫描）
        if (path.startsWith("content://")) {
            try {
                val uri = Uri.parse(path)
                // SAF 树形 URI 格式: content://.../tree/<rootId>/document/<rootId>/...
                val segments = uri.pathSegments
                if (segments.size >= 3 && segments[0] == "tree") {
                    val root = Uri.decode(segments[1])            // 例如 primary:Music
                    var display = root.substringAfter(":")       // 去掉 primary:
                    if (display.isEmpty()) display = root         // 万一没有冒号
                    // 如果有子文件夹
                    val rootId = segments[2]    // document 段
                    if (segments.size > 4) {
                        // 跳过重复的 rootId 和最后的文件名
                        val subPath = segments.subList(3, segments.size - 1).joinToString("/")
                        display = "$display/$subPath"
                    }
                    return display
                }
            } catch (_: Exception) {}
            return "自定义文件夹"
        }
        // 普通文件路径（全盘扫描）
        return File(path).parentFile?.name ?: "未知目录"
    }

    inner class FolderAdapter(
        private var folders: Map<String, List<Song>>,
        private val selected: Set<String>,
        private val onToggle: (String) -> Unit
    ) : RecyclerView.Adapter<FolderAdapter.VH>() {

        private var folderList = folders.keys.toList()

        fun updateData(newFolders: Map<String, List<Song>>) {
            folders = newFolders
            folderList = newFolders.keys.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemScanFolderBinding.inflate(layoutInflater, parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val folder = folderList[position]
            val count = folders[folder]?.size ?: 0
            holder.binding.tvFolderPath.text = folder
            holder.binding.tvSongCount.text = "${count} 首歌曲"
            holder.binding.cbSelect.isChecked = selected.contains(folder)
            holder.binding.root.setOnClickListener { onToggle(folder) }
        }

        override fun getItemCount() = folderList.size

        inner class VH(val binding: ItemScanFolderBinding) : RecyclerView.ViewHolder(binding.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}