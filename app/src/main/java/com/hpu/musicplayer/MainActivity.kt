package com.hpu.musicplayer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import coil.load
import com.google.android.material.navigation.NavigationView
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.databinding.ActivityMainBinding
import com.hpu.musicplayer.service.MusicService
import com.hpu.musicplayer.service.PlayMode
import com.hpu.musicplayer.service.PlaybackState
import com.hpu.musicplayer.utils.Permissions
import com.hpu.musicplayer.utils.SettingsPreferences
import com.hpu.musicplayer.utils.ThemeHelper
import com.hpu.musicplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController
    private var lastBackPressTime = 0L

    private lateinit var playerViewModel: PlayerViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            initializeApp()
        } else {
            showPermissionDeniedMessage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before super.onCreate
        applyTheme()
        super.onCreate(savedInstanceState)

        playerViewModel = ViewModelProvider(this)[PlayerViewModel::class.java]

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment?
        if (navHostFragment == null) {
            Toast.makeText(this, "NavHostFragment is null! Check layout.", Toast.LENGTH_LONG).show()
            return
        }
        navController = navHostFragment.navController

        // 仅主页设为顶级目的地，显示抽屉图标并可以点击打开侧边栏
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.songsFragment),
            binding.drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)

        val navigationView: NavigationView = binding.navView
        navigationView.setupWithNavController(navController)

        // 侧边栏点击处理
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.songsFragment, R.id.settingsFragment -> {
                    navController.navigate(menuItem.itemId, null,
                        NavOptions.Builder()
                            .setPopUpTo(R.id.songsFragment, false)
                            .setLaunchSingleTop(true)
                            .setEnterAnim(R.anim.slide_in_right)
                            .setExitAnim(R.anim.slide_out_left)
                            .setPopEnterAnim(R.anim.slide_in_left)
                            .setPopExitAnim(R.anim.slide_out_right)
                            .build()
                    )
                    binding.drawerLayout.closeDrawers()
                    true
                }
                // 收藏、音乐库、历史：直接导航，不清空栈，自动显示返回箭头
                R.id.favoritesFragment, R.id.musicLibraryFragment, R.id.historyFragment -> {
                    navController.navigate(menuItem.itemId, null,
                        NavOptions.Builder()
                            .setEnterAnim(R.anim.slide_in_right)
                            .setExitAnim(R.anim.slide_out_left)
                            .setPopEnterAnim(R.anim.slide_in_left)
                            .setPopExitAnim(R.anim.slide_out_right)
                            .build()
                    )
                    binding.drawerLayout.closeDrawers()
                    true
                }
                else -> false
            }
        }

        checkPermissions()

        // 导航目的地变化监听：控制 MiniPlayer 仅在主页可见
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.songsFragment -> refreshMiniPlayer()
                else -> binding.miniPlayer.root.visibility = View.GONE
            }
        }

        setupMiniPlayer()

        // 返回键处理：主页两次返回退出，其他页面正常返回
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    return
                }
                val currentDest = navController.currentDestination?.id
                if (currentDest == R.id.songsFragment) {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < 2000) {
                        finish()
                    } else {
                        Toast.makeText(this@MainActivity, "再按一次返回桌面", Toast.LENGTH_SHORT).show()
                        lastBackPressTime = now
                    }
                } else {
                    if (!navController.navigateUp()) {
                        finish()
                    }
                }
            }
        })
    }

    private fun setupMiniPlayer() {
        binding.miniPlayer.root.visibility = View.GONE

        lifecycleScope.launch {
            playerViewModel.playerState.collect { data ->
                if (navController.currentDestination?.id != R.id.songsFragment) return@collect

                if (data.currentSong != null) {
                    binding.miniPlayer.root.visibility = View.VISIBLE
                    binding.miniPlayer.miniTitle.text = data.currentSong?.title ?: "未知歌曲"
                    binding.miniPlayer.miniArtist.text = data.currentSong?.artist ?: "未知艺术家"

                    // 加载封面图片
                    val coverPath = data.currentSong?.coverPath
                    if (!coverPath.isNullOrEmpty()) {
                        binding.miniPlayer.miniCover.load(File(coverPath)) {
                            placeholder(R.drawable.ic_music_note)
                            error(R.drawable.ic_music_note)
                        }
                    } else {
                        binding.miniPlayer.miniCover.setImageResource(R.drawable.ic_music_note)
                    }

                    binding.miniPlayer.miniPlayPause.setImageResource(
                        if (data.state == PlaybackState.PLAYING) R.drawable.ic_pause
                        else R.drawable.ic_play
                    )
                } else {
                    binding.miniPlayer.root.visibility = View.GONE
                }
            }
        }

        binding.miniPlayer.root.setOnClickListener {
            val currentSongId = playerViewModel.playerState.value.currentSong?.id ?: return@setOnClickListener
            val bundle = Bundle().apply { putLong("songId", currentSongId) }
            navController.navigate(R.id.playerFragment, bundle,
                NavOptions.Builder()
                    .setEnterAnim(R.anim.slide_in_up)
                    .setExitAnim(R.anim.fade_out)
                    .setPopEnterAnim(R.anim.fade_in)
                    .setPopExitAnim(R.anim.slide_out_down)
                    .build()
            )
        }

        binding.miniPlayer.miniPlayPause.setOnClickListener {
            playerViewModel.togglePlayPause()
        }

        binding.miniPlayer.miniPrevious.setOnClickListener {
            playerViewModel.playPrevious()
        }

        binding.miniPlayer.miniNext.setOnClickListener {
            playerViewModel.playNext()
        }
    }

    private fun refreshMiniPlayer() {
        val data = playerViewModel.playerState.value
        if (data.currentSong != null) {
            binding.miniPlayer.root.visibility = View.VISIBLE
            binding.miniPlayer.miniTitle.text = data.currentSong?.title ?: "未知歌曲"
            binding.miniPlayer.miniArtist.text = data.currentSong?.artist ?: "未知艺术家"

            // 加载封面图片（与上面相同）
            val coverPath = data.currentSong?.coverPath
            if (!coverPath.isNullOrEmpty()) {
                binding.miniPlayer.miniCover.load(File(coverPath)) {
                    placeholder(R.drawable.ic_music_note)
                    error(R.drawable.ic_music_note)
                }
            } else {
                binding.miniPlayer.miniCover.setImageResource(R.drawable.ic_music_note)
            }

            binding.miniPlayer.miniPlayPause.setImageResource(
                if (data.state == PlaybackState.PLAYING) R.drawable.ic_pause
                else R.drawable.ic_play
            )
        } else {
            binding.miniPlayer.root.visibility = View.GONE
        }
    }

    private fun checkPermissions() {
        if (Permissions.hasStoragePermission(this)) {
            initializeApp()
        } else {
            requestPermissionLauncher.launch(Permissions.getStoragePermission())
        }
    }

    // 在 onResume 中检查权限是否被撤销
    override fun onResume() {
        super.onResume()
        if (!Permissions.hasStoragePermission(this)) {
            // 权限已被撤销，通知用户
            showPermissionDeniedMessage()
        }
    }

    private fun initializeApp() {
        lifecycleScope.launch {
            try {
                AppDatabase.getDatabase(this@MainActivity)

                startMusicService()

                restorePlaybackState()

                Toast.makeText(this@MainActivity, "初始化完成", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    //初始化/启动服务
    private fun startMusicService() {
        val intent = Intent(this, MusicService::class.java)
        startForegroundService(intent)
    }

    private suspend fun restorePlaybackState() {
        val db = AppDatabase.getDatabase(this@MainActivity)
        val savedState = db.playbackStateDao().getState() ?: return

        val mode = try {
            PlayMode.valueOf(savedState.playMode)
        } catch (e: Exception) {
            PlayMode.LIST_LOOP
        }
        // 通过 ViewModel 获取当前播放状态
        if (playerViewModel.playerState.value.currentSong != null) return

        playerViewModel.setPlayMode(mode)

        if (savedState.currentSongId != -1L) {

            val song = db.songDao().getSongById(savedState.currentSongId)
            if (song != null) {
                playerViewModel.restoreSong(song, savedState.position)
            }
        }
    }

    private fun showPermissionDeniedMessage() {
        Toast.makeText(
            this,
            "需要存储权限才能播放本地音乐",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun applyTheme() {
        val themeMode = SettingsPreferences.getThemeMode(this)
        setTheme(R.style.Theme_HpuMusicPlayer)

        // 应用主题模式到应用委托
        ThemeHelper.applyThemeMode(themeMode)
    }

    // 重新创建Activity以应用新主题
    fun recreateWithTheme() {
        // 重新应用主题模式
        ThemeHelper.applyThemeMode(SettingsPreferences.getThemeMode(this))
        recreate()
    }

    override fun onSupportNavigateUp(): Boolean {
        return NavigationUI.navigateUp(navController, appBarConfiguration) || super.onSupportNavigateUp()
    }
}