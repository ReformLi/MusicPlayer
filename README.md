## 🎵 本地音乐播放器 (MyMusicPlayer)

一款纯本地音乐播放器，基于 Android Media3 (ExoPlayer) 开发，支持扫描手机存储中的音频文件，提供完整的播放控制、歌词显示、播放队列管理、播放历史、收藏等功能。界面采用 **暖韵 v3.0** 设计风格（主色暖陶土 `#C26B4A`，辅色鼠尾草绿 `#6B8D78`），支持亮色/暗色主题切换。

---

## 📱 功能特性

### 媒体扫描
- **全盘扫描**：扫描系统媒体库中所有音频文件，支持 Android Q+
- **自定义文件夹扫描**：通过 SAF 选择文件夹，递归扫描其内所有音频文件
- **自动匹配**：自动匹配同目录下的封面图片 (.jpg/.png) 和歌词文件 (.lrc)
- **增量更新**：保留用户自定义的歌词、封面、收藏等字段
- **扫描预览**：按文件夹分组展示，支持多选目录后确认添加

### 歌曲管理
- **歌曲列表**：展示所有歌曲，支持实时搜索过滤
- **当前播放高亮**：粉红加粗文字 + 淡粉背景
- **歌曲操作**：删除、查看信息、收藏
- **音乐库**：侧边栏独立页面，可编辑标题、艺术家、专辑等元数据
- **收藏功能**：「我的最爱」页面展示收藏歌曲

### 播放控制
- **前台服务**：支持后台播放及锁屏/通知栏控制 (MediaStyle 通知)
- **播放界面**：
  - 专辑封面、歌曲标题/艺术家、进度条 (带时间标签)
  - 播放/暂停、上下首切换
  - 播放模式：列表循环 / 单曲循环 / 随机播放
  - 定时停止功能（播放完当前歌曲后停止）
  - 歌词同步显示，支持字体大小调节、偏移调整
  - 无歌词时禁止进入全屏歌词页
  - 全屏歌词页：状态栏安全区适配、屏幕常亮（播放时）、Toolbar 背景色统一
- **MiniPlayer**：底部全局控制栏，68dp 高度、20dp 圆角、顶部 2dp 进度线
- **状态记忆**：退出时保存当前歌曲、进度、播放模式，重新打开自动恢复

### 播放队列
- **弹窗式队列管理**：顶部留 30%，左右下留 10%，深色圆角背景
- **队列操作**：
  - 点击切换歌曲
  - 滑动删除 (带确认提示)
  - 拖拽排序
  - 清空队列 (带确认)
  - 重新加载全部歌曲
- **添加到队列**：长按歌曲可通过菜单「添加到队列」

### 播放历史
- 自动记录播放时间
- 历史列表按时间倒序
- 单条滑动删除 (带撤销)
- 清空全部 (带确认)

### 设置
- **缓存管理**：显示封面缓存大小，可清除
- **重置歌曲库**：清空所有歌曲、历史，停止播放
- **主题切换**：支持亮色/暗色/跟随系统三种模式

### 高级功能
- **音频焦点管理**：来电/其他应用抢占音频时自动暂停或降低音量
- **耳机断开暂停**：耳机拔出时自动暂停播放
- **WakeLock 保持_CPU 运行**：后台播放时保持 CPU 不休眠
- **主题跟随**：亮色/暗色模式自动适配

---

## 🛠 技术栈

- **开发语言**：Kotlin
- **架构模式**：MVVM (Model-View-ViewModel)
- **UI 框架**：Material Design 3 + ViewBinding + Navigation Component
- **播放器**：Android Media3 (ExoPlayer)
- **数据库**：Room (SQLite)
- **图片加载**：Coil
- **歌词解析**：自定义 LRC 解析器
- **媒体扫描**：MediaStore (全盘扫描) + SAF (自定义扫描)
- **异步处理**：Kotlin 协程 (Coroutines) + Flow
- **通知控制**：MediaSessionService + MediaStyle 通知
- **UI 设计**：暖韵 v3.0 色彩体系（主色暖陶土 `#C26B4A`，辅色鼠尾草绿 `#6B8D78`，浅色背景暖米白 `#FAF8F5`，深色背景暖棕黑 `#14120F`）

---

## 📋 权限说明

| 权限 | 用途 | 备注 |
|------|------|------|
| `READ_MEDIA_AUDIO` | 读取音频文件 (Android 13+) | 必需 |
| `READ_EXTERNAL_STORAGE` | 读取存储 (Android 12 及以下) | 必需 |
| `WRITE_EXTERNAL_STORAGE` | 写入存储 (Android 10 及以下) | 可选 |
| `FOREGROUND_SERVICE` | 前台播放服务 | 必需 |
| `WAKE_LOCK` | 保持 CPU 运行 | 可选 |
| `INTERNET` | 无 (纯本地应用) | 未使用 |

---

## 🚀 构建与安装

### 构建要求
- Android Studio Hedgehog | 2023.1.1 或更高版本
- Android Gradle Plugin 8.0+
- Kotlin 1.9+
- 最低 SDK：API 24 (Android 7.0)
- 目标 SDK：API 34 (Android 14)

### 构建命令
```bash
# 调试版
./gradlew assembleDebug

# 发布版
./gradlew assembleRelease
```

### 安装
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 📂 项目结构

```
app/src/main/java/com/hpu/musicplayer/
├── data/
│   ├── entity/          # Room 实体类 (Song, PlayHistory, PlaybackState)
│   ├── dao/             # Data Access Objects
│   ├── database/        # Room 数据库配置
│   └── repository/      # 数据仓库层
├── service/
│   └── MusicService.kt  # 播放服务 (MediaSessionService)
├── ui/
│   ├── activity/        # 主活动 (MainActivity)
│   ├── fragment/        # 所有页面 Fragment
│   ├── adapter/         # RecyclerView 适配器
│   └── dialog/          # 弹窗 (歌曲信息、缓存管理)
├── viewmodel/
│   └── PlayerViewModel.kt  # 播放状态 ViewModel
├── utils/
│   ├── MediaScanner.kt  # 媒体扫描器
│   ├── LrcParser.kt     # 歌词解析器
│   ├── ThemeHelper.kt   # 主题切换工具
│   └── PermissionHelper.kt  # 权限检查工具
└── receiver/
    └── NotificationReceiver.kt  # 通知栏按钮接收器
```

---

## 🔧 最近更新

### v3.0 (当前版本 · 暖韵重设计)
- ✅ **暖韵 v3.0 UI 重设计**：全新色彩体系、卡片 0dp elevation + 0.5dp 描边、按钮全圆角 24dp、弹框 24dp 圆角 + 4dp elevation
- ✅ 修复播放页/全屏歌词页黑色背景（亮主题下）
- ✅ 修复所有 PopupMenu 弹出框颜色、图标着色统一
- ✅ 修复播放历史页选项按钮（三个点）白色不可见问题
- ✅ 修复清空历史/定时停止/歌词字体大小/歌词偏移调整弹框样式（统一圆角、Material 风格）
- ✅ 修复播放队列「(103首)」竖排显示问题
- ✅ 修复 MusicService ANR（startForeground 未及时调用）
- ✅ 全屏歌词页状态栏安全区适配（刘海/挖孔屏）
- ✅ 全屏歌词页屏幕常亮（仅播放时）
- ✅ 全屏歌词页无歌词时禁止进入
- ✅ 全屏歌词页 Toolbar 背景色与页面统一
- ✅ 修复歌曲信息弹框颜色融合问题
- ✅ 播放队列「随机播放」文字移除，仅保留图标

### v1.0
- ✅ 修复音频焦点管理逻辑
- ✅ 添加耳机断开自动暂停功能
- ✅ 实现播放队列拖拽排序
- ✅ 添加「添加到队列」功能
- ✅ 添加「重新加载全部」功能
- ✅ 优化播放队列删除确认提示
- ✅ 修复歌词显示重叠问题
- ✅ 添加当前播放歌曲高亮背景
- ✅ 优化数据库 Migration 框架

---

## 📝 待实现功能

- [ ] 歌曲详情页自定义封面（类似微信换头像：相册选图 → 拖拽缩放 → 方形框裁剪保存，接入 uCrop）
- [ ] 播放页封面背景渐变 (Palette 取主色)
- [ ] 桌面小部件 (4×1 / 4×2)
- [ ] 歌词翻译显示 (双语 LRC)
- [ ] 均衡器 (十段 EQ)
- [ ] 歌单系统 (多歌单管理)
- [ ] 数据库 Migration 链 (从 v1 开始)
- [ ] 批量标签编辑器

---

## 📄 开源协议

本项目采用 MIT 协议开源。

---

## 👨‍💻 开发者

- **开发者**：hpu
- **包名**：com.hpu.musicplayer
- **GitHub**：[待添加]

---

## 📧 联系方式

如有问题或建议，请提交 Issue 或联系开发者。
