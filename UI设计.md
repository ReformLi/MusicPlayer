# MusicPlayer UI 设计规范

> 版本：3.0「暖韵」 | 基准：Material Design 3 | 主题：浅色/深色自适应
>
> **本轮范围**：仅视觉层（颜色、字体、圆角、间距、组件样式）
> **不涉及**：页面布局结构、功能逻辑、Java/Kotlin 代码逻辑

---

## 一、设计理念

### 1.1 核心原则
- **温润克制**：暖调大地色系，不刺眼、不冰冷，给人归属感
- **统一精确**：所有页面严格遵循同一套颜色/字体/圆角规范
- **主题无损**：浅色/深色模式下所有 UI 清晰可读，禁止硬编码颜色
- **保留布局**：本次仅刷新视觉层，不改动任何页面的 View 层级和约束关系

### 1.2 设计语言
- 调色板：暖陶土主色 × 鼠尾草绿辅色，整体偏暖调
- 圆角：卡片 12dp，弹窗 24dp，按钮全圆 24dp，搜索栏全圆胶囊
- 阴影：统一降低，卡片用 0.5dp 细描边替代 2dp elevation
- 留白：保持现有间距体系，8dp 基准网格

---

## 二、色彩体系

### 2.1 语义色（主题自适应，通过 `?attr/colorXxx` 引用）

| 语义 Token | 浅色模式 | 深色模式 | 用途 |
|---|---|---|---|
| `colorPrimary` | `#C26B4A` 暖陶土 | `#E89073` 浅陶土 | 主按钮、选中态、重点强调 |
| `colorOnPrimary` | `#FFFFFF` | `#3D1506` | 主色上的文字 |
| `colorPrimaryContainer` | `#FFE0D0` | `#5C3426` | 主色容器背景 |
| `colorOnPrimaryContainer` | `#4A2013` | `#FFE0D0` | 主色容器上文字 |
| `colorSecondary` | `#6B8D78` 鼠尾草绿 | `#90AE9C` | 次要强调、次级选中态 |
| `colorOnSecondary` | `#FFFFFF` | `#0F2419` | 辅色上的文字 |
| `colorSecondaryContainer` | `#DCEFE4` | `#2A3D32` | 辅色容器背景 |
| `colorOnSecondaryContainer` | `#192B20` | `#DCEFE4` | 辅色容器上文字 |
| `colorBackground` | `#FAF8F5` 暖米白 | `#14120F` 暖棕黑 | 页面背景 |
| `colorOnBackground` | `#1E1A17` | `#EEE6DF` | 页面背景上文字 |
| `colorSurface` | `#FFFFFF` | `#1E1C19` | 卡片/弹窗表面 |
| `colorOnSurface` | `#1E1A17` | `#EEE6DF` | 主要内容文字 |
| `colorSurfaceVariant` | `#F2EEE9` | `#2A2723` | 次级表面（搜索框背景等） |
| `colorOnSurfaceVariant` | `#6B6460` | `#B8AFA7` | 次要/辅助文字 |
| `colorOutline` | `#8B857E` | `#99928B` | 边框/描边 |
| `colorOutlineVariant` | `#D0C9C0` | `#3A3530` | 细描边（卡片 0.5dp 边框） |
| `colorError` | `#C62828` | `#EF9A9A` | 错误/删除/危险操作 |
| `colorOnError` | `#FFFFFF` | `#4A1010` | 错误色上文字 |

### 2.2 固定色（不随主题变化的场景色）

| Token | 色值 | 用途 |
|---|---|---|
| `lyric_highlight` | `#FFFFFF` | 全屏歌词当前行（深色模式也固定白色） |
| `lyric_normal` | `#80FFFFFF` | 全屏歌词非当前行 |
| `play_page_overlay` | 半透明暗色遮罩 | 播放页背景渐变暗部（见播放页设计） |

### 2.3 状态色

| 状态 | 色值 | 用途 |
|---|---|---|
| 成功/在线 | `#4CAF50` | 同步成功、在线状态 |
| 警告 | `#FF9800` | 警告提示 |
| 播放中指示 | `colorPrimary` | 当前播放歌曲左侧竖线 + 标题高亮 |

---

## 三、排版规范

### 3.1 字体族
- **默认**：系统默认无衬线字体（Roboto / HarmonyOS Sans）
- **禁止**使用自定义字体文件（保持 Native 体验，减少包体积）

### 3.2 字号层级（Material 3 Typography Scale）

| 级别 | 大小 | 粗细 | 使用场景 |
|---|---|---|---|
| **Headline Large** | 32sp | 400 | 关于页应用名 |
| **Headline Medium** | 28sp | 500 | 页面主标题（设置、关于等） |
| **Headline Small** | 24sp | 400 | 播放页歌曲标题 |
| **Title Large** | 22sp | 500 | 弹窗标题 |
| **Title Medium** | 16sp | 500 | 设置项标题、迷你播放器标题 |
| **Title Small** | 14sp | 500 | 列表项主文字 |
| **Body Large** | 16sp | 400 | 大段正文、设置卡片副标题 |
| **Body Medium** | 14sp | 400 | 弹窗正文、播放页艺术家 |
| **Body Small** | 12sp | 400 | 辅助信息、时长、时间标签 |
| **Label Large** | 14sp | 500 | 标签、按钮文字 |
| **Label Medium** | 12sp | 500 | 小标签、输入框标签 |
| **Label Small** | 11sp | 500 | 极小标注 |

> **关键改动**：TitleLarge / TitleMedium / TitleSmall / HeadlineMedium 字重从 400 提升为 500，增强标题辨识度。

### 3.3 行高
- 正文使用系统默认行高（约 1.3-1.5 倍）
- 歌词行允许 1.6-2.0 倍行高

---

## 四、间距与圆角

### 4.1 间距（保持现有体系）
| 名称 | 值 | 用途 |
|---|---|---|
| xs | 4dp | 图标与文字间距 |
| sm | 8dp | 纵向元素间距、卡片内边距 |
| md | 12dp | 横向元素间距 |
| lg | 16dp | 页面水平边距 |
| xl | 24dp | 区域分隔 |
| xxl | 32dp | 页面顶部留白 |

### 4.2 圆角规范

| 级别 | 值 | 使用场景 |
|---|---|---|
| 小（small） | 8dp | 列表项封面缩略图、标签 |
| 中（medium） | 12dp | **所有卡片**（列表项、设置卡片、弹窗内嵌卡片） |
| 大（large） | 24dp | **弹窗外框**、**按钮全圆角**、**搜索栏全圆胶囊** |
| 超大 | 20dp | 迷你播放器、播放页专辑封面 |

### 4.3 Elevation（阴影层级）— 全面降低

| 级别 | 旧值 | 新值 | 使用场景 |
|---|---|---|---|
| 无 | 0dp | 0dp + 0.5dp 描边 | 卡片（列表项、设置卡片、搜索栏） |
| 低 | 2dp | — | （废弃，改用 0dp + 描边） |
| 中 | 6dp | 4dp | 弹窗 |
| 高 | 8dp | 4dp | 迷你播放器 |

> **关键改动**：所有非弹窗卡片从 `elevation: 2dp` 改为 `elevation: 0dp + strokeWidth: 0.5dp + strokeColor: ?attr/colorOutlineVariant`，视觉更轻盈现代。

---

## 五、组件规范（仅视觉属性，不动布局结构）

### 5.1 按钮

| 属性 | Filled 按钮 | Outlined 按钮 | Text 按钮 | Icon 按钮 |
|---|---|---|---|---|
| 圆角 | **24dp**（全圆） | **24dp**（全圆） | 无 | 无 |
| 高度 | 48dp | 44dp | — | 40-48dp 触摸区 |
| 背景 | `colorPrimary` | 透明 | 透明 | 透明 + 涟漪 |
| 边框 | 无 | 1dp `colorOutline` | 无 | 无 |
| 文字色 | `colorOnPrimary` | `colorPrimary` | `colorPrimary` | — |
| 图标色 | `colorOnPrimary` | `colorPrimary` | `colorPrimary` | `colorOnSurfaceVariant` |
| 字体 | Label Large | Label Large | Label Large | — |

### 5.2 卡片 (MaterialCardView)

| 属性 | 旧值 | **新值** |
|---|---|---|
| 圆角 | 12dp | 12dp（不变） |
| elevation | 2dp | **0dp** |
| strokeWidth | 0dp | **0.5dp** |
| strokeColor | — | `?attr/colorOutlineVariant` |
| 背景 | `?attr/colorSurface` | `?attr/colorSurface`（不变） |
| 内边距 | 12dp | 12dp（不变） |

> 影响：`item_song.xml`、`item_history_song.xml`、`item_rank.xml`、`item_queue_song.xml`、`fragment_settings.xml` 中所有卡片。

### 5.3 搜索栏

| 属性 | 旧值 | **新值** |
|---|---|---|
| 高度 | 40dp | **44dp** |
| 圆角 | 12dp | **22dp**（全圆胶囊形） |
| elevation | 2dp | **0dp** |
| strokeWidth | 0dp | **0.5dp** |
| 背景 | `?attr/colorSurfaceVariant` | `?attr/colorSurfaceVariant`（不变） |
| hint 文字色 | `colorOnSurfaceVariant` | `colorOnSurfaceVariant`（不变） |
| 图标大小 | 20dp | 20dp（不变） |

> 影响：`fragment_songs.xml`、`fragment_history.xml` 中的搜索栏。

### 5.4 弹窗 (DialogFragment)

| 属性 | 旧值 | **新值** |
|---|---|---|
| 外框圆角 | 16dp | **24dp** |
| elevation | 6dp | **4dp** |
| 背景 | `?attr/colorSurface` | `?attr/colorSurface`（不变） |
| 内边距 | 16dp / 24dp | **24dp**（统一） |
| 标题字体 | Title Large | Title Large（不变） |
| 标题-内容分隔线 | 无 / 隐式 | **0.5dp `colorOutlineVariant`**（明确分隔） |
| 内嵌信息卡片圆角 | 12dp | 12dp（不变） |
| 关闭按钮 | Outlined / 混合样式 | **统一 Outlined，24dp 全圆角** |
| 入场动画 | scale_in | scale_in（不变） |
| 退场动画 | fade_out | **scale_and_fade_out**（新增缩小退出） |

> **关键**：弹窗 Guideline 百分比约束、View 层级结构 **完全不变**。仅改圆角、elevation、内边距、按钮样式。

### 5.5 迷你播放器 (mini_player)

| 属性 | 旧值 | **新值** |
|---|---|---|
| 高度 | 72dp | **68dp** |
| 圆角 | 16dp | **20dp** |
| elevation | 8dp | **4dp** |
| strokeWidth | 0dp | **0.5dp** |
| 背景 | `?attr/colorSurface` | `?attr/colorSurface`（不变） |
| 封面大小 | 48dp | 48dp（不变） |
| 封面圆角 | 8dp | **10dp** |
| 进度线 | 无 | **顶部 2dp `colorPrimary` 横线**（显示当前播放进度） |

> 进度线实现：在 mini_player.xml 根布局顶部新增一条 View（2dp 高，宽 match_parent，背景 colorPrimary），在 Kotlin 代码中动态设置其宽度百分比。

### 5.6 列表项（各 item 布局）

**统一规范**（覆盖 item_song / item_history_song / item_rank / item_queue_song）：

| 属性 | 旧值 | **新值** |
|---|---|---|
| 卡片圆角 | 12dp | 12dp（不变） |
| 卡片 elevation | 2dp | **0dp + 0.5dp 描边** |
| 卡片背景 | `colorSurface` | `colorSurface`（不变） |
| 封面圆角 | 8dp | 8dp（不变） |
| 标题字体 | Title Small | Title Small（500 weight） |
| 标题颜色 | `colorOnSurface` | `colorOnSurface`（不变） |
| 副标题字体 | Label Medium / Body Small | Label Medium / Body Small |
| 副标题颜色 | `colorOnSurfaceVariant` | `colorOnSurfaceVariant`（不变） |
| 当前播放指示 | 颜色硬编码 | **`colorPrimary` 主题引用** |
| 收藏图标（实心） | `colorError` / 硬编码 | **`colorError` 主题引用** |

**各列表项特殊说明：**
- **item_song**：右侧有时长（Label Small）+ 收藏 + 更多按钮。更多按钮 36dp 触摸区域。
- **item_history_song**：右侧有播放时间 + 收听时长。收听时长用 `colorPrimary` 着色。
- **item_rank**：排名序号 32dp 圆，Top 1-3 用 `colorPrimary` 不同透明度填充。
- **item_queue_song**：左侧有拖拽手柄，右侧有删除按钮。
- **以上所有 View 层级和约束关系保持不变**。

### 5.7 开关 (SwitchMaterial)

| 属性 | 值 |
|---|---|
| 激活轨道色 | `colorPrimary` |
| 非激活轨道色 | `colorSurfaceVariant` |
| 滑块色 | 白色（固定） |
| 标签字体 | Body Medium |

### 5.8 空状态

| 属性 | 值 |
|---|---|
| 图标大小 | 80dp（从 64-96dp 统一） |
| 图标着色 | `colorOnSurfaceVariant`，alpha 0.4 |
| 标题字体 | Body Large |
| 标题颜色 | `colorOnSurfaceVariant` |
| 副标题字体 | Body Small |
| 副标题颜色 | `colorOnSurfaceVariant`，alpha 0.6 |
| 图标-文字间距 | 16dp |

### 5.9 下拉菜单 (PopupMenu)

| 属性 | 值 |
|---|---|
| 背景 | `?attr/colorSurface` |
| 文字色 | `?attr/colorOnSurface` |
| 图标色 | `?attr/colorOnSurfaceVariant` |
| 主题 | `Theme.HpuMusicPlayer.PopupMenu`（自适应） |

### 5.10 播放器进度条 (Slider)

| 属性 | 值 |
|---|---|
| 激活轨道色 | `colorPrimary` |
| 非激活轨道色 | `colorSurfaceVariant` |
| thumb 色 | `colorPrimary` |
| 轨道高度 | 2dp（不变） |
| halo 半径 | 12dp |
| 时间标签字体 | Body Small |
| 时间标签颜色 | `colorOnSurfaceVariant` |

---

## 六、各页面视觉规范（仅描述视觉属性，不动布局）

### 6.1 播放页面 (PlayerFragment)

保留现有 ConstraintLayout 层级，仅调整以下视觉属性：

| 组件 | 视觉属性 |
|---|---|
| **整体背景** | 保留渐变 drawable，深色模式下渐变端点调暖（`#1A1613` → `#24201C` → `#1A1613`） |
| **顶部区域** | 沉浸式，无状态栏背景色（保留现有） |
| **专辑封面卡片** | 圆角 **20dp**（旧 16dp），elevation 4dp（旧 8dp），背景透明 |
| **歌曲标题** | Headline Small，`?attr/colorOnBackground`（深色模式白色），居中 |
| **艺术家** | Body Medium，`?attr/colorOnSurfaceVariant`，居中 |
| **倒计时标签** | Body Small，`colorPrimary`，默认隐藏 |
| **歌词区域** | 高度占屏 20%，可点击入口，空状态 "暂无歌词" |
| **进度条** | Slider，thumbColor = trackColorActive = `colorPrimary`，时间标签 Body Small |
| **控制按钮** | 5 个 ImageButton 水平分布，播放/暂停按钮 56dp 圆形背板 `colorPrimary` |
| **按钮着色** | `colorOnSurface`（浅色黑 / 深色白），播放按钮 `colorOnPrimary` |

### 6.2 歌曲列表页 (SongsFragment)

保留现有 CoordinatorLayout + RecyclerView 结构。

| 组件 | 视觉属性 |
|---|---|
| **页面背景** | `?android:attr/colorBackground` |
| **搜索栏** | 见 §5.3 搜索栏规范 |
| **列表** | RecyclerView，padding 4dp |
| **空状态** | 见 §5.8 空状态规范（"点击右上角扫描本地音乐"） |

### 6.3 播放历史页 (HistoryFragment)

保留现有 LinearLayout + RecyclerView 结构。

| 组件 | 视觉属性 |
|---|---|
| **页面背景** | `?attr/colorBackground`（**重要修复**：从 `colorSurface` 统一为 `colorBackground`） |
| **搜索栏** | 见 §5.3 搜索栏规范（hint "搜索播放历史"） |
| **列表** | RecyclerView，padding 4dp |
| **空状态** | "暂无播放历史"，18sp，`colorOnSurfaceVariant` |
| **工具栏** | 时间范围下拉 + 选项按钮，边框颜色 `colorOutline` |

### 6.4 设置页 (SettingsFragment)

保留现有 CoordinatorLayout > NestedScrollView > LinearLayout 结构。

| 组件 | 视觉属性 |
|---|---|
| **页面背景** | `?android:attr/colorBackground` |
| **页面标题** | Headline Medium（28sp 500weight），`colorOnBackground` |
| **分区标题** | Label Medium（12sp 500），**`colorPrimary`**（旧 `colorPrimary`），letterSpacing 0.5 |
| **设置卡片** | 见 §5.2 卡片规范（0dp elevation + 0.5dp 描边） |
| **卡片内标题** | Title Medium（16sp 500），`colorOnSurface` |
| **卡片内副标题** | Body Medium（14sp），`colorOnSurfaceVariant` |
| **右侧箭头** | 24dp，`colorOnSurfaceVariant` |
| **危险操作文字** | `colorError` |
| **退出按钮** | 居中，Outlined 样式，文字/边框 `colorError` |

### 6.5 弹窗页面

保留现有 Guideline 约束 + ConstraintLayout 层级。

#### 歌曲信息弹窗 (dialog_song_info)

| 组件 | 视觉属性 |
|---|---|
| 弹窗外框 | 24dp 圆角，4dp elevation，`colorSurface` 背景，24dp 内边距 |
| 标题栏 | 图标 24dp `colorPrimary` + Title Large 文字，底部 0.5dp `colorOutlineVariant` 分隔线 |
| 信息卡片 | 12dp 圆角，0dp elevation + 0.5dp 描边，`colorSurfaceVariant` 背景，16dp 内边距 |
| 标签文字 | Label Medium，`colorOnSurfaceVariant` |
| 值文字 | Body Medium，`colorOnSurface` |
| 关闭按钮 | Outlined，24dp 全圆角，文字 `colorPrimary` |

#### 缓存管理弹窗 (dialog_cache_management)

| 组件 | 视觉属性 |
|---|---|
| 弹窗外框 | 24dp 圆角，4dp elevation，`colorSurface` 背景，24dp 内边距 |
| 标题栏 | 图标 24dp + Title Large，底部 0.5dp 分隔线 |
| 缓存信息卡片 | 12dp 圆角，0dp elevation + 0.5dp 描边，`colorSurfaceVariant` 背景 |
| 清除按钮 | Filled，24dp 全圆角，背景 `colorError`，文字 `colorOnError` |
| 关闭按钮 | Outlined，24dp 全圆角 |

#### 播放队列弹窗 (PlayQueueDialogFragment)

| 组件 | 视觉属性 |
|---|---|
| 弹窗外框 | 24dp 圆角，4dp elevation |
| 列表项 | 见 §5.6 列表项规范 |
| 操作按钮 | 底部水平分布，图标按钮 |

### 6.6 关于/帮助/编辑等副页面

保留现有 CoordinatorLayout + NestedScrollView 结构。

| 组件 | 视觉属性 |
|---|---|
| 页面背景 | `?attr/colorBackground` |
| 内容卡片 | 12dp 圆角，0dp elevation + 0.5dp 描边 |
| 应用图标 | 120dp 圆角方形 |
| 帮助 FAQ 标题 | Body Large 500weight |
| 帮助 FAQ 正文 | Body Medium |

### 6.7 抽屉导航 (NavigationView)

保留现有结构，仅调整颜色。

| 组件 | 视觉属性 |
|---|---|
| 头部背景 | 渐变色或图片（保留现有资源） |
| 头部文字 | `colorOnPrimary`（白色） |
| 菜单图标 | 24dp，`colorOnSurfaceVariant` |
| 菜单文字 | Body Large，`colorOnSurface` |
| 选中项背景 | `colorPrimaryContainer` |

### 6.8 全屏歌词 (FullscreenLyricsFragment)

| 组件 | 视觉属性 |
|---|---|
| 背景 | 纯黑 `#000000`（保留，歌词场景特殊需求） |
| 当前行 | 白色 100% alpha + bold |
| 非当前行 | 白色 50% alpha |
| 字体大小 | 14sp 默认，可调（12/14/16/18/20sp） |

---

## 七、颜色迁移对照表

以下是现有颜色到新暖色体系的精确映射。

### 7.1 colors.xml 迁移

| 旧颜色名 | 旧色值 | 新色值 | 说明 |
|---|---|---|---|
| `primary_purple` | `#3700B3` | `#C26B4A` | 作为 colorPrimary |
| `primary_container` | `#EADDFF` | `#FFE0D0` | 浅色容器 |
| `on_primary_container` | `#21005D` | `#4A2013` | 容器文字 |
| `accent_teal` | `#009688` | `#6B8D78` | 作为 colorSecondary |
| `secondary_container` | `#E8DEF8` | `#DCEFE4` | 辅色容器 |
| `on_secondary_container` | `#1D192B` | `#192B20` | 辅色容器文字 |
| `background_light` | `#FFFBFE` | `#FAF8F5` | 浅色页背景 |
| `surface_primary` | `#FFFBFE` | `#FFFFFF` | 浅色卡片表面 |
| `surface_variant` | `#E7E0EC` | `#F2EEE9` | 浅色次级表面 |
| `text_primary` | `#1C1B1F` | `#1E1A17` | 浅色主文字 |
| `text_secondary` | `#49454F` | `#6B6460` | 浅色次文字 |
| `text_tertiary` | `#79747E` | `#8B857E` | 浅色三级文字 |
| `text_hint` | `#9E9E9E` | `#B0A79F` | 浅色提示文字 |
| `error_red` | `#D32F2F` | `#C62828` | 错误红 |
| `dark_background` | `#121212` | `#14120F` | 深色页背景 |
| `dark_surface` | `#1E1E1E` | `#1E1C19` | 深色卡片表面 |
| `dark_surface_variant` | `#2D2D2D` | `#2A2723` | 深色次级表面 |
| `dark_text_primary` | `#FFFFFF` | `#EEE6DF` | 深色主文字（微暖） |
| `dark_text_secondary` | `#B3B3B3` | `#B8AFA7` | 深色次文字 |
| `dark_text_tertiary` | `#808080` | `#888078` | 深色三级文字 |

### 7.2 themes.xml 主题属性迁移

| 主题属性 | 旧引用 | 新引用 |
|---|---|---|
| `colorPrimary` | `@color/primary_purple` | `@color/primary_warm` |
| `colorOnPrimary` | `@color/white` | `@color/white` |
| `colorPrimaryContainer` | `@color/primary_container` | `@color/primary_container_warm` |
| `colorOnPrimaryContainer` | `@color/on_primary_container` | `@color/on_primary_container_warm` |
| `colorSecondary` | `@color/accent_teal` | `@color/secondary_sage` |
| `colorSecondaryContainer` | `@color/secondary_container` | `@color/secondary_container_sage` |
| `colorBackground` | `@color/background_light` | `@color/background_warm_light` |
| `colorSurface` | `@color/surface_primary` | `@color/surface_card` |
| `colorSurfaceVariant` | `@color/nav_drawer_light` | `@color/surface_variant_warm` |
| `colorOnSurface` | `@color/on_surface` | `@color/text_primary_warm` |
| `colorOnSurfaceVariant` | `@color/text_secondary` | `@color/text_secondary_warm` |
| `colorOutline` | `@color/text_tertiary` | `@color/outline_warm` |
| `colorOutlineVariant` | — | `@color/outline_variant_warm`（**新增**） |
| `colorError` | `@color/error_red` | `@color/error_warm` |

### 7.3 深色模式（values-night）

| 主题属性 | 新深色值 |
|---|---|
| `colorPrimary` | `#E89073` |
| `colorOnPrimary` | `#3D1506` |
| `colorPrimaryContainer` | `#5C3426` |
| `colorOnPrimaryContainer` | `#FFE0D0` |
| `colorSecondary` | `#90AE9C` |
| `colorBackground` | `#14120F` |
| `colorSurface` | `#1E1C19` |
| `colorSurfaceVariant` | `#2A2723` |
| `colorOnSurface` | `#EEE6DF` |
| `colorOnSurfaceVariant` | `#B8AFA7` |
| `colorOutline` | `#99928B` |
| `colorOutlineVariant` | `#3A3530` |
| `colorError` | `#EF9A9A` |
| `colorOnError` | `#4A1010` |

---

## 八、主题切换规范

### 8.1 三种模式（保留现有逻辑）
| 模式 | 设置值 | 效果 |
|---|---|---|
| 跟随系统 | `MODE_NIGHT_FOLLOW_SYSTEM` | 根据系统深色模式自动切换 |
| 浅色模式 | `MODE_NIGHT_NO` | 始终浅色 |
| 深色模式 | `MODE_NIGHT_YES` | 始终深色 |

### 8.2 实现要求
- 所有颜色通过 `?attr/colorXxx` 引用，禁止硬编码
- 切换时使用 `recreate()` 确保全局生效
- 深色模式下 StatusBar 白色图标（`windowLightStatusBar = false`）
- 播放页和全屏歌词页的固定暗色背景允许硬编码（沉浸式场景特殊处理）

---

## 九、实施清单

### 阶段 1：颜色重定义
- [ ] `values/colors.xml` — 所有颜色按 §7.1 迁移表更新
- [ ] `values-night/colors.xml` — 深色覆盖值更新
- [ ] `values/themes.xml` — 主题属性引用更新，新增 colorOutlineVariant、ShapeAppearance、Typography 字重调整
- [ ] `values-night/themes.xml` — 深色主题属性覆盖更新

### 阶段 2：卡片统一
- [ ] `item_song.xml` — elevation 0dp + 0.5dp 描边
- [ ] `item_history_song.xml` — 同上
- [ ] `item_rank.xml` — 同上
- [ ] `item_queue_song.xml` — 同上
- [ ] `fragment_settings.xml` — 所有卡片 elevation 0dp + 0.5dp 描边

### 阶段 3：搜索栏 & 迷你播放器
- [ ] `fragment_songs.xml` — 搜索栏 44dp 高 + 22dp 圆角 + 0dp elevation + 0.5dp 描边
- [ ] `fragment_history.xml` — 同上
- [ ] `mini_player.xml` — 68dp 高 + 20dp 圆角 + 4dp elevation + 0.5dp 描边 + 顶部进度线

### 阶段 4：弹窗样式
- [ ] `dialog_song_info.xml` — 24dp 圆角 + 4dp elevation + 24dp 内边距 + 分隔线
- [ ] `dialog_cache_management.xml` — 同上
- [ ] 播放队列弹窗 — 同上
- [ ] 弹窗按钮统一 24dp 全圆角

### 阶段 5：播放页 & 列表微调
- [ ] `fragment_player.xml` — 封面圆角 20dp，elevation 4dp
- [ ] `fragment_history.xml` — 根背景从 colorSurface 改 colorBackground
- [ ] `dialog_song_info.xml` / `dialog_cache_management.xml` — 内嵌卡片 elevation 0dp + 0.5dp 描边

### 阶段 6：动画 & 进度线
- [ ] 创建 `scale_and_fade_out.xml` 弹窗退出动画
- [ ] DialogAnimation 引用新退场动画
- [ ] MiniPlayer 代码中添加进度线更新逻辑

---

## 十、禁止事项

| ❌ 禁止 | 原因 |
|---|---|
| 修改 View 层级和约束 | 本次只做视觉层刷新 |
| 新增/删除 View | 不动布局结构 |
| 硬编码颜色值 `#XXXXXX` | 主题切换会失效 |
| 使用旧版 `CardView`（`androidx.cardview.widget.CardView`） | 统一 `MaterialCardView` |
| 修改功能逻辑代码 | 本次不改 Java/Kotlin 逻辑 |
| 改变 RecyclerView Adapter 绑定逻辑 | 除非颜色硬编码需要改为主题引用 |
| 修改 `app/build.gradle.kts` | 不新增依赖 |

---

> **文档维护规则**：本规范是 UI 实现的一等权威来源。所有视觉属性修改必须符合本规范。布局修改需同步更新本文档并标注版本号和日期。
