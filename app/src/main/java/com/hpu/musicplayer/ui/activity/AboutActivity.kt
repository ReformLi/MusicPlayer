package com.hpu.musicplayer.ui.activity

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hpu.musicplayer.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupAboutInfo()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "关于"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun setupAboutInfo() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersion.text = "版本 ${packageInfo.versionName}"
        } catch (e: PackageManager.NameNotFoundException) {
            binding.tvVersion.text = "版本 1.0.0"
        }
        binding.tvDescription.text = "一款简洁优雅的本地音乐播放器，支持多种音频格式，提供完整的播放控制和个性化设置。"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}