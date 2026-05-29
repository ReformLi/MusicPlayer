package com.hpu.musicplayer.ui.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.PlaybackStateEntity
import com.hpu.musicplayer.databinding.FragmentSettingsBinding
import com.hpu.musicplayer.service.MusicService
import com.hpu.musicplayer.service.PlayMode
import com.hpu.musicplayer.ui.activity.AboutActivity
import com.hpu.musicplayer.ui.activity.HelpActivity
import com.hpu.musicplayer.ui.dialog.CacheManagementDialogFragment
import com.hpu.musicplayer.utils.CoverMigration
import com.hpu.musicplayer.utils.SettingsPreferences
import com.hpu.musicplayer.utils.ThemeChangeManager
import com.hpu.musicplayer.utils.ThemeHelper
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // 权限请求启动器
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 权限已授予，执行启用通知操作
            enableNotification()
        } else {
            // 用户拒绝，回弹开关并提示
            binding.switchNotificationControl.isChecked = false
            Toast.makeText(requireContext(), "需要通知权限才能启用控制", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupNotificationControl()
        setupThemeSwitch()
        setupHelp()
        setupAbout()

        binding.layoutCacheManagement.setOnClickListener {
            CacheManagementDialogFragment().show(parentFragmentManager, "CacheDialog")
        }

        binding.layoutResetLibrary.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("重置歌曲库")
                .setMessage("确定要删除所有歌曲数据吗？这将清空本地歌曲库和播放历史，你需要重新扫描添加歌曲。")
                .setPositiveButton("确定") { _, _ -> resetLibrary() }
                .setNegativeButton("取消", null)
                .show()
        }

        setupExitApp()
    }

    override fun onResume() {
        super.onResume()
        // 确保主题切换文本是最新的
        updateThemeSwitchText()
    }

    private fun setupNotificationControl() {
        // 初始化开关状态
        binding.switchNotificationControl.isChecked =
            SettingsPreferences.isNotificationControlEnabled(requireContext())

        binding.switchNotificationControl.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 用户尝试打开开关
                if (hasNotificationPermission()) {
                    // 已有权限，直接启用
                    enableNotification()
                } else {
                    // 无权限，发起申请
                    requestNotificationPermission()
                }
            } else {
                // 用户关闭开关
                disableNotification()
            }
        }
    }

    // 检查是否有通知权限
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // 低于 Android 13 无需权限
        }
    }

    // 请求通知权限
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // 理论上不会走到这里，但安全兜底
            enableNotification()
        }
    }

    // 启用通知的具体逻辑
    @SuppressLint("MissingPermission")
    private fun enableNotification() {
        SettingsPreferences.setNotificationControlEnabled(requireContext(), true)
        MusicService.getInstance()?.updateNotification()
        Toast.makeText(requireContext(), R.string.notification_enabled, Toast.LENGTH_SHORT).show()
    }

    // 禁用通知的具体逻辑
    private fun disableNotification() {
        SettingsPreferences.setNotificationControlEnabled(requireContext(), false)
        MusicService.getInstance()?.hideNotification()
        Toast.makeText(requireContext(), R.string.notification_disabled, Toast.LENGTH_SHORT).show()
    }

    private fun setupThemeSwitch() {
        updateThemeSwitchText()
        binding.layoutThemeSwitch.setOnClickListener {
            showThemeDialog()
        }
    }

    private fun updateThemeSwitchText() {
        val currentTheme = SettingsPreferences.getThemeMode(requireContext())
        val themeText = ThemeHelper.getThemeDisplayName(currentTheme, requireContext())
        // 更新主题描述文本以显示当前选择
        binding.tvThemeSwitch.text = "${getString(R.string.theme_switch)}\n当前: $themeText"
    }

    private fun setupHelp() {
        binding.layoutHelp.setOnClickListener {
            val intent = Intent(requireContext(), HelpActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupAbout() {
        binding.layoutAbout.setOnClickListener {
            val intent = Intent(requireContext(), AboutActivity::class.java)
            startActivity(intent)
        }
    }

    private fun showThemeDialog() {
        val themes = arrayOf(
            "${getString(R.string.theme_system)} (推荐)",
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val currentTheme = SettingsPreferences.getThemeMode(requireContext())
        val currentIndex = when (currentTheme) {
            "light" -> 1
            "dark" -> 2
            else -> 0
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_theme)
            .setSingleChoiceItems(themes, currentIndex) { dialog, which ->
                val themeMode = when (which) {
                    1 -> "light"
                    2 -> "dark"
                    else -> "system"
                }

                if (ThemeHelper.isValidThemeMode(themeMode)) {
                    SettingsPreferences.setThemeMode(requireContext(), themeMode)

                    // 通知主题变化
                    ThemeChangeManager.notifyThemeChanged(themeMode)

                    applyThemeAndRecreate(themeMode)
                } else {
                    Toast.makeText(requireContext(), R.string.invalid_theme, Toast.LENGTH_SHORT).show()
                }

                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("确定") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun applyThemeAndRecreate(themeMode: String) {
        // 应用主题模式到应用委托
        ThemeHelper.applyThemeMode(themeMode)

        Toast.makeText(requireContext(), R.string.theme_changed, Toast.LENGTH_SHORT).show()

        // 立即更新UI文本显示
        updateThemeSwitchText()

        // 重新创建活动以立即应用新主题
        if (activity is com.hpu.musicplayer.MainActivity) {
            (activity as com.hpu.musicplayer.MainActivity).recreateWithTheme()
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
            // 清空保存的私有专辑图片
            CoverMigration.clearAllCovers(requireContext())

            // 3. 重新启动服务（之前被 stopSelf 了）
            val intent = Intent(requireContext(), MusicService::class.java)
            requireContext().startService(intent)

            Toast.makeText(requireContext(), "歌曲库已重置，请重新扫描歌曲", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupExitApp() {
        binding.layoutExitApp.setOnClickListener {
            showExitConfirmationDialog()
        }
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("退出应用")
            .setMessage("确定要退出应用吗？")
            .setPositiveButton("确定") { _, _ -> exitApp() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exitApp() {
        // 停止音乐服务
        MusicService.getInstance()?.stopPlayback()
        val intent = Intent(requireContext(), MusicService::class.java)
        requireContext().stopService(intent)

        // 退出应用
        requireActivity().finishAffinity()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}