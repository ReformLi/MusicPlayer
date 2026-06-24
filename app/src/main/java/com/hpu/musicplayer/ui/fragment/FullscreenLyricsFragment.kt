package com.hpu.musicplayer.ui.fragment

import android.animation.Animator
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.databinding.FragmentFullscreenLyricsBinding
import com.hpu.musicplayer.service.PlaybackState
import com.hpu.musicplayer.service.PlayerData
import com.hpu.musicplayer.ui.adapter.LyricAdapter
import com.hpu.musicplayer.utils.LrcLine
import com.hpu.musicplayer.utils.LrcParser
import com.hpu.musicplayer.utils.LyricConfig
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FullscreenLyricsFragment : Fragment() {

    private var _binding: FragmentFullscreenLyricsBinding? = null
    private val binding get() = _binding!!
    private val playerViewModel: PlayerViewModel by viewModels({ requireActivity() })

    private val lyricAdapter = LyricAdapter()
    private var lrcLines: List<LrcLine> = emptyList()
    private var lastSongId = -1L

    // 自定义滚动进度条
    private var isUserScrolling = false
    private var fadeAnimator: ValueAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFullscreenLyricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        hideSystemUI()

        lyricAdapter.fontSizeSp = LyricConfig.getFontSize(requireContext())
        binding.rvFullscreenLyrics.adapter = lyricAdapter
        binding.rvFullscreenLyrics.layoutManager = LinearLayoutManager(requireContext())

        setupScrollIndicator()
        setupControls()

        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.playerState.collect { data ->
                updateUI(data)
            }
        }
    }

    /** 设置自定义滚动进度条：仅在手动滑动时显示 */
    private fun setupScrollIndicator() {
        val rv = binding.rvFullscreenLyrics
        val bar = binding.scrollProgressBar

        // 触摸事件：手指按下时显示进度条，抬起后渐隐
        rv.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isUserScrolling = true
                    showProgressBar()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isUserScrolling = false
                    hideProgressBar()
                }
            }
            false // 不消费事件，正常传递给 RecyclerView
        }

        // 滚动事件：根据滚动位置更新进度条位置
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isUserScrolling) {
                    updateProgressBarPosition()
                }
            }
        })
    }

    private fun showProgressBar() {
        val bar = binding.scrollProgressBar
        bar.visibility = View.VISIBLE
        // 取消正在进行的渐隐动画
        fadeAnimator?.cancel()
        bar.animate().cancel()
        bar.alpha = 0.6f
    }

    private fun hideProgressBar() {
        val bar = binding.scrollProgressBar
        // 延迟 300ms 后渐隐
        bar.postDelayed({
            // 如果期间又开始了新的滑动，则不隐藏
            if (!isUserScrolling) {
                fadeAnimator = ValueAnimator.ofFloat(bar.alpha, 0f).apply {
                    duration = 400
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { anim ->
                        bar.alpha = anim.animatedValue as Float
                    }
                    addListener(object : Animator.AnimatorListener {
                        override fun onAnimationEnd(animation: Animator) {
                            bar.visibility = View.GONE
                        }
                        override fun onAnimationStart(animation: Animator) {}
                        override fun onAnimationCancel(animation: Animator) {}
                        override fun onAnimationRepeat(animation: Animator) {}
                    })
                    start()
                }
            }
        }, 300)
    }

    private fun updateProgressBarPosition() {
        val rv = binding.rvFullscreenLyrics
        val bar = binding.scrollProgressBar
        val layoutManager = rv.layoutManager as LinearLayoutManager

        val totalItems = rv.adapter?.itemCount ?: return
        if (totalItems == 0) return

        // 计算当前滚动进度
        val firstVisiblePos = layoutManager.findFirstVisibleItemPosition()
        val lastVisiblePos = layoutManager.findLastVisibleItemPosition()
        if (firstVisiblePos == RecyclerView.NO_POSITION) return

        // 基于可见范围的中间位置计算进度
        val visibleCenter = (firstVisiblePos + lastVisiblePos) / 2f
        val progress = (visibleCenter / (totalItems - 1)).coerceIn(0f, 1f)

        // 进度条可移动范围
        val rvHeight = rv.height
        val barHeight = bar.layoutParams.height
        val maxTranslation = (rvHeight - barHeight).toFloat()
        bar.translationY = progress * maxTranslation
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().window.setDecorFitsSystemWindows(false)
            requireActivity().window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().window.setDecorFitsSystemWindows(true)
            requireActivity().window.insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private suspend fun updateUI(data: PlayerData) {
        val safeProgress = data.progress.coerceIn(0L, data.duration.coerceAtLeast(1L))

        val song = data.currentSong
        if (song != null) {
            binding.tvFullscreenTitle.text = song.title
            binding.tvFullscreenArtist.text = song.artist

            if (song.id != lastSongId) {
                lastSongId = song.id
                loadLyrics(song)
            }

            // 自动滚动歌词（播放时），不触发进度条
            if (lrcLines.isNotEmpty()) {
                val offsetMs = LyricConfig.getOffset(requireContext())
                val adjustedProgress = safeProgress + offsetMs
                val index = lrcLines.indexOfLast { it.time <= adjustedProgress }
                if (index != -1) {
                    lyricAdapter.updateCurrentIndex(index)
                    val offset = binding.rvFullscreenLyrics.height / 2
                    (binding.rvFullscreenLyrics.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(index, offset)
                }
            }

            binding.btnFullscreenPlayPause.setImageResource(
                if (data.state == PlaybackState.PLAYING) R.drawable.ic_pause
                else R.drawable.ic_play
            )
        }
    }

    private fun loadLyrics(song: Song) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val lines = if (song.lrcPath != null) {
                LrcParser.parse(song.lrcPath)
            } else emptyList()
            withContext(Dispatchers.Main) {
                lrcLines = lines
                lyricAdapter.submitList(lrcLines)
            }
        }
    }

    private fun setupControls() {
        binding.btnFullscreenPlayPause.setOnClickListener {
            playerViewModel.togglePlayPause()
        }

        binding.btnFullscreenPrev.setOnClickListener {
            playerViewModel.playPrevious()
        }

        binding.btnFullscreenNext.setOnClickListener {
            playerViewModel.playNext()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        showSystemUI()
        _binding = null
    }
}
