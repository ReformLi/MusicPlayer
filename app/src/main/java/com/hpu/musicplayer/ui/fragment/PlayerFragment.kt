package com.hpu.musicplayer.ui.fragment

import android.animation.ObjectAnimator
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hpu.musicplayer.databinding.DialogLyricFontSizeBinding
import com.hpu.musicplayer.databinding.DialogLyricOffsetBinding
import com.hpu.musicplayer.databinding.DialogSleepTimerBinding
import com.google.android.material.slider.Slider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hpu.musicplayer.R
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // 歌词源路径：与 lastSongId 分开跟踪，元数据更新（如设置自定义歌词）后也能触发重载
    private var lastLrcSource: String? = null
    // 歌词异步加载令牌：快速切歌时旧解析协程晚到，凭 token 丢弃过期结果，
    // 否则旧歌词会覆盖新歌歌词且 lastSongId 已更新导致永久错位
    private var lyricLoadToken = 0
    // 用户滚动歌词列表期间暂停自动滚动，停止后 3 秒内也不拉回（避免刚抬手就被拽回高亮行）
    private var isUserScrolling = false
    private var suppressAutoScrollUntil = 0L
    // 歌词偏移量调节弹窗展示期间暂停 updateUI 的歌词高亮/滚动，避免实时预览被 100ms 进度回调覆盖
    private var isOffsetDialogVisible = false
    private var isDiscShowing = false
    private var discRotator: ObjectAnimator? = null

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
        // 歌词列表底部留白（半屏高），配合 clipToPadding=false，
        // 使末行歌词也能滚动到歌词区域中间位置；顶部不留白，第一行歌词紧贴顶部
        binding.rvLyrics.clipToPadding = false
        binding.rvLyrics.post {
            if (binding.rvLyrics.height > 0) {
                val halfHeight = binding.rvLyrics.height / 2
                binding.rvLyrics.setPadding(0, 0, 0, halfHeight)
            }
        }
        // rvLyrics 会消费触摸事件（用于滚动），导致 lyricContainer 收不到点击
        // 通过触摸监听拦截：手指按下和抬起在同一 item 内且没有滚动时，视为点击
        binding.rvLyrics.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            private var downX = 0f
            private var downY = 0f
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val dx = Math.abs(e.rawX - downX)
                        val dy = Math.abs(e.rawY - downY)
                        if (dx < 10f && dy < 10f) {
                            // 视为点击，触发 lyricContainer 的点击逻辑
                            binding.lyricContainer.performClick()
                            return true
                        }
                    }
                }
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })

        // 用户滚动检测：滚动期间（含惯性 SETTLING）暂停自动滚动；
        // 停止后 3 秒内也不拉回，让用户能自由浏览歌词。
        // scrollToPositionWithOffset 是瞬时定位，不经过 SETTLING，不会误判
        binding.rvLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING,
                    RecyclerView.SCROLL_STATE_SETTLING -> isUserScrolling = true
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        isUserScrolling = false
                        suppressAutoScrollUntil = android.os.SystemClock.elapsedRealtime() + 3000
                    }
                }
            }
        })

        // 使用 viewLifecycleOwner.lifecycleScope 确保视图销毁时自动取消
        var initialPlayDone = false
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.playerState.collect { data ->
                updateUI(data)
                // 冷启动补救：仅在首次、有目标歌曲、且 Service 完全无歌时执行
                if (!initialPlayDone && songId != -1L && data.currentSong == null) {
                    initialPlayDone = true
                    try {
                        val song = playerViewModel.getSongById(songId)
                        if (song != null) {
                            playerViewModel.play(song)
                        }
                    } catch (e: Exception) {
                        Log.e("PlayerFragment", "Cold start restore failed: ${e.message}")
                    }
                }
            }
        }

        setupControls()

        // 点击歌词区域进入全屏歌词（仅当有歌词时才响应）
        val goFullscreen: (View) -> Unit = {
            if (lrcLines.isNotEmpty()) {
                findNavController().navigate(R.id.action_playerFragment_to_fullscreenLyricsFragment)
            }
        }
        binding.lyricContainer.setOnClickListener(goFullscreen)
        binding.tvNoLyrics.setOnClickListener(goFullscreen)

        // 观察剩余时间并显示（例如在 tvSongTitle 下方加一个 TextView）
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.timerRemaining.collect { remaining ->
                if (remaining > 0) {
                    val seconds = remaining / 1000
                    binding.tvTimer.text = "⏳ ${seconds / 60}:${String.format(java.util.Locale.US, "%02d", seconds % 60)}"
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
        // 1. 规范化 duration 和 progress（防止 ExoPlayer 返回无效值）
        val safeDuration = if (data.duration > 0) data.duration else 0L
        val safeProgress = data.progress.coerceIn(0L, safeDuration)

        val song = data.currentSong
        if (song != null) {
            // 歌曲信息
            binding.tvSongTitle.text = song.title
            binding.tvSongArtist.text = song.artist

            // 时间显示（使用安全值）
            binding.tvCurrentTime.text = formatTime(safeProgress)
            binding.tvTotalTime.text = formatTime(safeDuration)

            // 封面：仅在歌曲切换时加载一次，避免每 100ms 进度更新重复触发 Coil 加载
            if (song.id != lastSongId) {
                lastSongId = song.id
                val coverPath = song.customCoverPath ?: song.coverPath
                if (!coverPath.isNullOrEmpty()) {
                    binding.ivAlbumArt.load(File(coverPath)) {
                        placeholder(R.drawable.ic_music_note)
                        error(R.drawable.ic_music_note)
                    }
                } else {
                    binding.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
                }
            }
            // 歌词：按"歌词源路径"重载。切歌和元数据更新（SongDetail 设置自定义歌词后
            // Service 侧刷新 song 对象）都会改变源路径，两种场景都需重载
            val lrcSource = song.customLrcPath ?: song.lrcPath
            if (lrcSource != lastLrcSource) {
                loadLyrics(song)
            }

            // 歌词高亮与滚动（偏移量调节弹窗展示期间暂停，避免覆盖实时预览）
            if (lrcLines.isNotEmpty() && !isOffsetDialogVisible) {
                val offsetMs = LyricConfig.getOffset(requireContext())
                val adjustedProgress = safeProgress + offsetMs
                val index = lrcLines.indexOfLast { it.time <= adjustedProgress }
                if (index >= 0) {
                    lyricAdapter.updateCurrentIndex(index)
                    // 仅在用户没有滚动歌词时自动滚动；高亮行仍实时更新
                    if (canAutoScrollLyrics()) {
                        // 同步滚动，保持丝滑；高亮行字体放大1.2倍，额外高度约
                        // 0.2*fontSizeSp*density，中心需上移一半即 0.1*fontSizeSp*density
                        val density = resources.displayMetrics.density
                        val highlightShift = (lyricAdapter.fontSizeSp * density * 0.2f).toInt()
                        val offset = binding.rvLyrics.height / 2 - binding.rvLyrics.paddingTop - highlightShift
                        (binding.rvLyrics.layoutManager as LinearLayoutManager)
                            .scrollToPositionWithOffset(index, offset)
                    }
                } else {
                    // 进度在所有歌词之前（如拖到开头），清除高亮并滚到顶部
                    lyricAdapter.updateCurrentIndex(-1)
                    if (canAutoScrollLyrics()) {
                        (binding.rvLyrics.layoutManager as LinearLayoutManager)
                            .scrollToPositionWithOffset(0, 0)
                    }
                }
            }

            // 进度条（仅在非拖动时更新）
            if (!binding.seekBar.isPressed) {
                // 与开头规范化保持一致：duration 为负时 coerceIn(0, 负数) 会抛 IllegalArgumentException
                val max = safeDuration.coerceAtLeast(1L).toFloat()
                val pos = safeProgress.toFloat()

                // 先设置 valueTo，再设置 value，并确保在主线程中一次性完成
                binding.seekBar.apply {
                    // 取消可能存在的 pending 更新，避免冲突
                    removeCallbacks(null)
                    valueFrom = 0f
                    valueTo = max
                    // 直接设置 value（因为已经在主线程）
                    value = pos
                    // 可选：强制重新测量
                    postInvalidate()
                }
            }

            // 播放/暂停图标
            binding.btnPlayPause.setImageResource(
                if (data.state == PlaybackState.PLAYING) R.drawable.ic_pause
                else R.drawable.ic_play
            )

            // 光盘显示/隐藏（播放时渐入+旋转，暂停时渐出）
            if (data.state == PlaybackState.PLAYING && !isDiscShowing) {
                showDisc()
            } else if (data.state != PlaybackState.PLAYING && isDiscShowing) {
                hideDisc()
            }

            // 歌词区域可见性
//            binding.tvNoLyrics.visibility = if (lrcLines.isEmpty()) View.VISIBLE else View.GONE
//            binding.rvLyrics.visibility = if (lrcLines.isEmpty()) View.GONE else View.VISIBLE
            // 歌词区域始终可见（占用固定高度）
            binding.rvLyrics.visibility = View.VISIBLE
            if (lrcLines.isEmpty()) {
                binding.tvNoLyrics.visibility = View.VISIBLE
                // 注意：不清 adapter——loadLyrics 完成回调已 submitList(lrcLines) 提交过空列表，
                // 此处每 100ms 的进度回调里重复 submitList + notifyDataSetChanged 纯属浪费
            } else {
                binding.tvNoLyrics.visibility = View.GONE
            }

        } else {
            // 无歌曲时重置界面；同时重载标记，否则同一首歌再次播放时封面/歌词不会重载
            lastSongId = -1L
            lastLrcSource = null
            // 歌词数据也必须清：否则停止播放（lrcSource 均为 null）后，
            // 下一首无歌词的歌不会触发重载（null != null 为 false），残留上一首歌词
            if (lrcLines.isNotEmpty()) {
                lrcLines = emptyList()
                lyricAdapter.submitList(emptyList())
            }
            binding.tvSongTitle.text = "未选择歌曲"
            binding.tvSongArtist.text = ""
            binding.tvCurrentTime.text = "00:00"
            binding.tvTotalTime.text = "00:00"
            binding.seekBar.value = 0f
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            binding.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
            binding.tvNoLyrics.visibility = View.VISIBLE
            binding.rvLyrics.visibility = View.GONE
        }
    }

    private fun loadLyrics(song: Song) {
        // token 防竞态：快速切歌时旧协程晚到，凭 token 丢弃过期结果
        val token = ++lyricLoadToken
        val lrcPath = song.customLrcPath ?: song.lrcPath
        lastLrcSource = lrcPath
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val lines = if (lrcPath != null) {
                LrcParser.parse(lrcPath)
            } else emptyList()
            withContext(Dispatchers.Main) {
                if (token != lyricLoadToken || _binding == null) return@withContext
                lrcLines = lines
                lyricAdapter.submitList(lrcLines)
                // 歌词加载完成后同步更新"暂无歌词"可见性，避免与异步加载竞态导致重叠
                if (lrcLines.isEmpty()) {
                    binding.tvNoLyrics.visibility = View.VISIBLE
                } else {
                    binding.tvNoLyrics.visibility = View.GONE
                    // 偏移弹窗展示期间跳过自动滚动：避免弹窗打开时自动切歌，
                    // 新歌词的加载滚动覆盖弹窗的实时预览位置
                    if (isOffsetDialogVisible) return@withContext
                    // 歌词异步加载完成后，补滚到当前播放进度位置
                    // （updateUI 中的滚动逻辑此时可能因 lrcLines 还为空而跳过）
                    val currentProgress = playerViewModel.playerState.value.progress
                    val offsetMs = LyricConfig.getOffset(requireContext())
                    val adjustedProgress = currentProgress + offsetMs
                    val index = lrcLines.indexOfLast { it.time <= adjustedProgress }
                    if (index != -1) {
                        lyricAdapter.updateCurrentIndex(index)
                        val density = resources.displayMetrics.density
                        val highlightShift = (lyricAdapter.fontSizeSp * density * 0.2f).toInt()
                        val offset = binding.rvLyrics.height / 2 - binding.rvLyrics.paddingTop - highlightShift
                        (binding.rvLyrics.layoutManager as LinearLayoutManager)
                            .scrollToPositionWithOffset(index, offset)
                    }
                }
            }
        }
    }

//    private fun loadCurrentSong() {
//        if (songId == -1L) return
//
//        // 如果当前正在播放同一首歌，什么都不做（UI 会从 playerState 自动更新）
//        val currentSongId = playerViewModel.playerState.value.currentSong?.id
//        if (currentSongId == songId) return
//
//        // 仅当播放器完全没有歌曲时才加载（例如冷启动从通知进入）
//        if (currentSongId == null) {
//            viewLifecycleOwner.lifecycleScope.launch {
//                val db = AppDatabase.Companion.getDatabase(requireContext())
//                val song = db.songDao().getSongById(songId)
//                song?.let { playerViewModel.play(it) }
//            }
//        }
//    }

    /** 用户未在滚动歌词（且停止滚动已超过 3 秒）时才允许自动滚动到高亮行 */
    private fun canAutoScrollLyrics(): Boolean {
        if (isUserScrolling) return false
        return android.os.SystemClock.elapsedRealtime() >= suppressAutoScrollUntil
    }

    private fun setupControls() {
        binding.seekBar.valueFrom = 0f
//        binding.seekBar.valueTo = 100f   // 仅用于初始化，播放后会立即更新
//        binding.seekBar.value = 0f

        val thumbDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.circle_thumb)
        if (thumbDrawable != null) {
            binding.seekBar.setCustomThumbDrawable(thumbDrawable)
            val radiusPx = 8.dpToPx()  // 8dp 半径，直径 16dp
            binding.seekBar.setThumbRadius(radiusPx)
        } else {
            // 如果资源加载失败，可以设置一个默认颜色或使用其他方式恢复圆形
            Log.w("PlayerFragment", "Failed to load thumb drawable, using default")
            binding.seekBar.setCustomThumbDrawable(createDefaultThumbDrawable())
            val radiusPx = 8.dpToPx()  // 8dp 半径，直径 16dp
            binding.seekBar.setThumbRadius(radiusPx)
        }

        binding.btnPlayPause.setOnClickListener {
            playerViewModel.togglePlayPause()
        }
        // 设置进度条标签格式化
        binding.seekBar.setLabelFormatter { progress ->
            formatTime(progress.toLong())
        }

        binding.seekBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                playerViewModel.seekTo(slider.value.toLong())
            }
        })

        binding.btnNext.setOnClickListener {
            playerViewModel.playNext()
        }

        binding.btnPrev.setOnClickListener {
            playerViewModel.playPrevious()
        }

        binding.btnMenu.setOnClickListener { view ->
            val wrappedCtx = ContextThemeWrapper(requireContext(), R.style.Theme_HpuMusicPlayer_PopupMenu)
            val popup = PopupMenu(wrappedCtx, view)
            popup.menuInflater.inflate(R.menu.menu_player_more, popup.menu)
            popup.setForceShowIcons()
            // 将所有菜单项 icon 颜色统一改为主题色
            val iconColor = ContextCompat.getColor(requireContext(), R.color.primary_warm)
            for (i in 0 until popup.menu.size()) {
                popup.menu.getItem(i).icon?.mutate()?.setTint(iconColor)
            }
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
                    R.id.action_lyric_offset -> {
                        showLyricOffsetDialog()
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
//            Log.d("PlayerFragment", "btnMode clicked")
            playerViewModel.cyclePlayMode()
        }
    }

    private fun createDefaultThumbDrawable(): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(requireContext(), R.color.accent_orange))
            setSize(16.dpToPx(), 16.dpToPx())
        }
    }

    // 扩展函数：dp 转 px
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showLyricFontSizeDialog() {
        val currentSize = LyricConfig.getFontSize(requireContext())
        val dialogView = DialogLyricFontSizeBinding.inflate(layoutInflater)

        // 初始化预览和滑块
        dialogView.tvFontSizeValue.text = "${currentSize.toInt()} sp"
        dialogView.tvLyricPreview.textSize = currentSize
        dialogView.sliderFontSize.value = currentSize.coerceIn(10f, 34f)

        dialogView.sliderFontSize.addOnChangeListener { _, value, _ ->
            dialogView.tvFontSizeValue.text = "${value.toInt()} sp"
            dialogView.tvLyricPreview.textSize = value
            lyricAdapter.updateFontSize(value)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.btnFontCancel.setOnClickListener {
            lyricAdapter.updateFontSize(currentSize)
            dialog.dismiss()
        }
        dialogView.btnFontConfirm.setOnClickListener {
            val newSize = dialogView.sliderFontSize.value
            LyricConfig.setFontSize(requireContext(), newSize)
            lyricAdapter.updateFontSize(newSize)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showLyricOffsetDialog() {
        val currentOffset = LyricConfig.getOffset(requireContext())
        val dialogView = DialogLyricOffsetBinding.inflate(layoutInflater)

        dialogView.tvOffsetValue.text = "${currentOffset} ms"
        dialogView.sliderOffset.value = currentOffset.toFloat().coerceIn(-5000f, 5000f)

        dialogView.sliderOffset.addOnChangeListener { _, value, _ ->
            dialogView.tvOffsetValue.text = "${value.toInt()} ms"
            // 实时预览
            val safeProgress = playerViewModel.playerState.value.progress
            val adjustedProgress = safeProgress + value.toLong()
            if (lrcLines.isNotEmpty()) {
                val index = lrcLines.indexOfLast { it.time <= adjustedProgress }
                if (index != -1) lyricAdapter.updateCurrentIndex(index)
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // 弹窗展示期间暂停 updateUI 的歌词高亮/滚动，否则 100ms 进度回调会覆盖实时预览
        isOffsetDialogVisible = true
        dialog.setOnDismissListener { isOffsetDialogVisible = false }

        dialogView.btnOffsetReset.setOnClickListener {
            dialogView.sliderOffset.value = 0f
            dialogView.tvOffsetValue.text = "0 ms"
        }
        dialogView.btnOffsetCancel.setOnClickListener {
            lyricAdapter.notifyDataSetChanged()
            dialog.dismiss()
        }
        dialogView.btnOffsetConfirm.setOnClickListener {
            val newOffset = dialogView.sliderOffset.value.toInt()
            LyricConfig.setOffset(requireContext(), newOffset)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showTimerDialog() {
        val dialogView = DialogSleepTimerBinding.inflate(layoutInflater)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val optionMap = mapOf(
            dialogView.option15 to 15,
            dialogView.option30 to 30,
            dialogView.option45 to 45,
            dialogView.option60 to 60,
            dialogView.option90 to 90,
            dialogView.option120 to 120
        )
        optionMap.forEach { (view, min) ->
            view.setOnClickListener {
                playerViewModel.startTimer(min)
                dialog.dismiss()
            }
        }
        dialogView.btnCancelTimer.setOnClickListener {
            playerViewModel.cancelTimer()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun formatTime(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }

    /**
     * 播放时显示光盘：专辑图片往左平移，光盘从后面露出右侧部分，整体居中，自身圆心旋转
     */
    private fun showDisc() {
        // postDelayed 重试路径可能在视图销毁后回调，此时直接放弃
        if (_binding == null || !isAdded) return
        if (isDiscShowing) return
        isDiscShowing = true
        binding.albumDiscContainer.post {
            if (_binding == null || !isAdded) return@post
            val albumSize = binding.cardAlbum.height
            if (albumSize == 0) {
                // 布局尚未完成，延迟重试
                isDiscShowing = false
                binding.albumDiscContainer.postDelayed({ showDisc() }, 100)
                return@post
            }

            // 光盘被专辑遮住 4/5，右侧只露出 1/5
            binding.ivDisc.translationX = albumSize * 0.15f
            binding.ivDisc.visibility = View.VISIBLE
            binding.ivDisc.alpha = 0f
            binding.ivDisc.animate().alpha(0.85f).setDuration(300).start()

            // 启动旋转（ObjectAnimator 绕自身圆心旋转，不受 translationX 影响）
            discRotator = ObjectAnimator.ofFloat(binding.ivDisc, "rotation", 0f, 360f).apply {
                duration = 8000
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }

            // 专辑图片往左平移，使「专辑+光碟」整体视觉居中
            // 光盘露出 20%，即整体多出 albumSize*0.2，一半 = albumSize*0.1
            binding.cardAlbum.animate()
                .translationX(-albumSize * 0.1f)
                .setDuration(300)
                .start()
        }
    }

    /**
     * 暂停时隐藏光盘：专辑图片归位，光碟渐出
     */
    private fun hideDisc() {
        isDiscShowing = false
        discRotator?.cancel()
        discRotator = null
        binding.cardAlbum.animate()
            .translationX(0f)
            .setDuration(300)
            .start()
        binding.ivDisc.animate().alpha(0f).setDuration(300).withEndAction {
            if (_binding == null || !isAdded) return@withEndAction
            binding.ivDisc.visibility = View.GONE
            binding.ivDisc.rotation = 0f
            binding.ivDisc.translationX = 0f
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // 从其他页面返回时，如果正在播放但光碟未显示（如从全屏歌词返回视图重建），主动恢复
        if (!isDiscShowing && playerViewModel.playerState.value.state == PlaybackState.PLAYING) {
            showDisc()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        discRotator?.cancel()
        discRotator = null
        isDiscShowing = false
        // 视图重建（如从全屏歌词返回）后 ImageView 是新 inflate 的，
        // 若不重置标记，song.id == lastSongId 会跳过封面加载，显示占位图直到切歌
        lastSongId = -1L
        lastLrcSource = null
        isUserScrolling = false
        isOffsetDialogVisible = false
        _binding = null
    }
}

