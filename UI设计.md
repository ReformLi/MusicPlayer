# MusicPlayer UI 设计规范

> 版本：1.0 | 基准：Material Design 3 | 主题：浅色/深色自适应

---

## 一、设计理念

### 1.1 核心原则
- **纯粹沉浸**：以内容（歌曲、歌词、封面）为中心，UI 只做必要辅助
- **统一克制**：所有控件严格遵守统一规范，不追求花哨效果
- **主题无损**：浅色/深色模式下所有 UI 必须清晰可读，不依赖硬编码颜色

### 1.2 设计语言
- 圆角柔和（小 8dp / 中 12dp / 大 16dp）
- 阴影轻微（elevation 1-8dp，不做重阴影）
- 留白充足（元素间距 ≥ 8dp，页面边距 16dp）
- 色彩克制（紫色主色调，仅关键操作用强调色）

---

## 二、色彩体系

### 2.1 语义色（主题自适应，通过 `?attr/` 引用）

| 语义 Token | 浅色模式 | 深色模式 | 用途 |
|---|---|---|---|
| `colorPrimary` | `#6200EE` 紫 | `#BB86FC` 浅紫 | 主按钮、开关、重点强调 |
| `colorOnPrimary` | `#FFFFFF` | `#000000` | 主色上的文字 |
| `colorPrimaryContainer` | `#EADDFF` | `#4F378B` | 重要但非首要容器背景 |
| `colorOnPrimaryContainer` | `#21005D` | `#EADDFF` | 主色容器上的文字 |
| `colorSecondary` | `#03DAC5` 青 | `#03DAC5` | 次要强调、选中态 |
| `colorSecondaryContainer` | `#E8DEF8` | `#4A4458` | 次要容器背景 |
| `colorBackground` | `#FFFBFE` | `#121212` | 页面背景 |
| `colorSurface` | `#FFFBFE` | `#1E1E1E` | 卡片/弹窗表面 |
| `colorSurfaceVariant` | `#F5F5F5` | `#2D2D2D` | 次级表面（标签背景等） |
| `colorOnSurface` | `#1C1B1F` | `#FFFFFF` | 主要内容文字 |
| `colorOnSurfaceVariant` | `#49454F` | `#B3B3B3` | 次要/辅助文字 |
| `colorOutline` | `#79747E` | `#808080` | 边框线条 |
| `colorError` | `#D32F2F` | `#F2B8B5` | 错误/删除/危险操作 |
| `colorOnError` | `#FFFFFF` | `#601410` | 错误色上文字 |

### 2.2 固定色（不随主题变化）

| Token | 色值 | 用途 |
|---|---|---|
| `waveform_primary` | `#6200EE` | 音频波形渐变起点 |
| `waveform_secondary` | `#BB86FC` | 音频波形渐变终点 |
| `current_song_bg` | `#1AFF9800` | 当前播放歌曲高亮（10% 橙色叠加） |
| `lyric_highlight` | `#FFFFFF` | 当前歌词行（深色模式也固定白色） |
| `lyric_normal` | `#80FFFFFF` | 非当前歌词行 |

### 2.3 状态叠加色（系统自动生成）
- **hover**：8% `colorOnSurface` 叠加
- **pressed**：12% `colorOnSurface` 叠加
- **selected**：8% `colorPrimary` 叠加

---

## 三、排版规范

### 3.1 字体族
- **默认**：系统默认无衬线字体（Roboto / HarmonyOS Sans）
- **等宽**：仅日志/调试场景使用
- **禁止**使用自定义字体文件（保持 Native 体验，减少包体积）

### 3.2 字号层级（Material 3 Typography Scale）

| 级别 | 大小 | 粗细 | 使用场景 |
|---|---|---|---|
| **Display Large** | 57sp | 400 | 暂不使用 |
| **Display Medium** | 45sp | 400 | 暂不使用 |
| **Display Small** | 36sp | 400 | 暂不使用 |
| **Headline Large** | 32sp | 400 | 关于页应用名 |
| **Headline Medium** | 28sp | 400 | 播放页歌曲标题 |
| **Headline Small** | 24sp | 400 | 页面主标题 |
| **Title Large** | 22sp | 400 | 弹窗标题、全屏歌词标题 |
| **Title Medium** | 16sp | 500 | 设置项标题、对话框标题 |
| **Title Small** | 14sp | 500 | 列表项主文字 |
| **Body Large** | 16sp | 400 | 大段正文 |
| **Body Medium** | 14sp | 400 | 卡片正文、弹窗内容 |
| **Body Small** | 12sp | 400 | 辅助信息 |
| **Label Large** | 14sp | 500 | 标签、按钮文字 |
| **Label Medium** | 12sp | 500 | 小标签、输入框标签 |
| **Label Small** | 11sp | 500 | 极小标注 |

### 3.3 行高
- 所有正文字体默认使用系统行高（约 1.3-1.5 倍），不强制覆盖
- 歌词行允许 1.6-2.0 倍行高（`lineSpacingMultiplier`）

---

## 四、间距与圆角

### 4.1 间距 scale（8dp 基准网格）
| 名称 | 值 | 用途 |
|---|---|---|
| `xs` | 4dp | 图标与文字间距、极小分隔 |
| `sm` | 8dp | 纵向元素间距、卡片内边距 |
| `md` | 12dp | 横向元素间距 |
| `lg` | 16dp | 页面水平边距、卡片间距 |
| `xl` | 24dp | 大段间距、区域分隔 |
| `xxl` | 32dp | 页面顶部留白 |

### 4.2 圆角
| 级别 | 值 | 使用场景 |
|---|---|---|
| **小（small）** | 8dp | 输入框、小按钮、标签 |
| **中（medium）** | 12dp | 卡片、列表项、弹窗 |
| **大（large）** | 16dp | 页面级卡片、大弹窗 |
| **全圆** | 50% | 封面图、圆形按钮 |

### 4.3 Elevation（阴影层级）
| 级别 | dp 值 | 使用场景 |
|---|---|---|
| 无 | 0dp | 纯平元素（背景、分隔线） |
| 低 | 1-2dp | 列表卡片 |
| 中 | 4-6dp | 弹窗、FAB |
| 高 | 8dp | 迷你播放器、顶部栏 |

---

## 五、组件规范

### 5.1 按钮

#### 5.1.1 主要操作按钮（Filled）
- 样式：`Widget.Material3.Button`
- 背景：`?attr/colorPrimary`
- 文字：`?attr/colorOnPrimary`，Label Large
- 圆角：大（16dp）
- 高度：48dp
- 场景：播放/暂停、确认删除、保存

#### 5.1.2 次要操作按钮（Outlined）
- 样式：`Widget.Material3.Button.OutlinedButton`
- 边框：`?attr/colorOutline`，1dp
- 文字：`?attr/colorPrimary`，Label Large
- 圆角：大（16dp）
- 高度：44dp
- 场景：取消、关闭、修改封面/歌词

#### 5.1.3 文字按钮（Text）
- 样式：`Widget.Material3.Button.TextButton`
- 文字：`?attr/colorPrimary`
- 场景：对话框中"取消"

#### 5.1.4 图标按钮（Icon）
- 大小：24dp（图标），40-48dp（触摸区域）
- 背景：`?attr/selectableItemBackgroundBorderless`（涟漪效果）
- 着色：`?attr/colorOnSurfaceVariant`
- 场景：更多、删除、返回、搜索清除

---

### 5.2 卡片 (MaterialCardView)

| 属性 | 规范 |
|---|---|
| 圆角 | 中（medium, 12dp） |
| 标高 | 低（2dp） |
| 背景 | `?attr/colorSurface` |
| 描边 | 无 |
| 内边距 | 水平 md(12dp)，垂直 sm(8dp) |
| 场景 | 歌曲列表项、历史记录项 |

**当前播放歌曲卡片**：
- 左边缘加 4dp `colorPrimary` 竖线（或整体 10% orange 背景叠加）
- 标题文字改为 `colorPrimary`

---

### 5.3 输入框

#### 5.3.1 TextInputLayout (OutlinedBox)
- 样式：`Widget.Material3.TextInputLayout.OutlinedBox`
- 圆角：小（8dp）
- 标签：Label Medium
- 输入文字：Body Large
- 错误文字：Body Small + `colorError`

#### 5.3.2 搜索框
- 容器：`MaterialCardView`，40dp 高，12dp 圆角，2dp elevation
- 背景：`?attr/colorSurfaceVariant`
- 图标：搜索（左 12dp，20dp）、清除（右 12dp，20dp）
- 文字：Body Medium，hint 用 `colorOnSurfaceVariant`
- 与列表间距：12dp

---

### 5.4 弹窗 (Dialog)

#### 5.4.1 确认对话框 (AlertDialog)
- 主题：Material 3 默认
- 标题：Title Medium，`colorOnSurface`
- 内容：Body Medium，`colorOnSurfaceVariant`
- 按钮：确定（Filled） + 取消（Text），右对齐
- 单选用：`setSingleChoiceItems` 内置 RadioButton 列表

#### 5.4.2 自定义弹窗 (DialogFragment)
- 窗口背景：透明（`@android:color/transparent`）
- 窗口暗度：0.5（`backgroundDimAmount`）
- 内容容器：`ConstraintLayout` + Guideline 约束比例
  - 横向：5%-95%
  - 纵向：30%-80%（信息弹窗）/ 20%-95%（队列弹窗）
- 内容卡片：`MaterialCardView`，16dp 圆角
  - 背景：`?attr/colorSurface`
  - 内边距：16dp
- 标题栏：图标 24dp + Title Large，顶部居中，底部 0.5dp 分隔线
- 底部按钮：Filled/Outlined，居中或右对齐，间距 12dp
- 动画：`scale_in` 入场 + `fade_out` 出场

#### 5.4.3 弹窗类型总览

| 弹窗 | 宽度约束 | 高度约束 | 特殊组件 |
|---|---|---|---|
| 播放队列 | 10%-90% | 20%-95% | RecyclerView（拖拽）、操作按钮栏 |
| 缓存管理 | 15%-85% | 25%-72% | 信息卡片 |
| 歌曲信息 | 5%-95% | 30%-80% | 标签-值多行列表 |
| 排行榜 | 全屏 | 全屏 | 工具栏：排序下拉 + 关闭，RecyclerView |
| 清空确认 | 系统 AlertDialog | wrap | 单选列表（时间范围选择） |

---

### 5.5 下拉菜单 (PopupMenu)

| 属性 | 规范 |
|---|---|
| 样式 | `androidx.appcompat.widget.PopupMenu` |
| 主题 | `Theme.HpuMusicPlayer.PopupMenu`（浅色/深色自适应） |
| 背景 | `?attr/colorSurface` |
| 文字 | `?attr/colorOnSurface` |
| 图标 | 24dp，`?attr/colorOnSurfaceVariant` 着色 |
| 定位 | 锚定到触发按钮正下方 |
| 圆角 | 由系统处理 |

**使用场景**：
| 位置 | 菜单项 |
|---|---|
| 歌曲列表项 ··· | 添加到队列、删除、歌曲信息、收藏切换 |
| 播放页 ··· | 播放队列、定时停止播放、歌词字体大小、歌词偏移 |
| 历史页时间下拉 | 最近一周、最近一月、最近一年、全部 |
| 历史页 ⋮ | 排行榜、清空历史 |
| 排行榜排序 | 按播放次数、按播放时长 |

---

### 5.6 列表项

#### 5.6.1 歌曲列表项 (item_song)
```
┌─ MaterialCardView 12dp 圆角 ───────────────────┐
│ [封面40dp]  标题（Title Small, 单行, 尾部省略）  │
│             艺术家（Body Small, colorOnSurfaceVariant）│
│             时长（Body Small, 右对齐）    [♥] [⋮] │
└──────────────────────────────────────────────────┘
```
- 封面：40dp 正方形，小圆角 8dp
- 封面占位：用 `ic_music_note` 图标 + `colorSurfaceVariant` 背景
- 时长：格式 `m:ss`，右对齐
- 收藏图标：实心心形（`ic_favorite`），`colorError` 着色，非收藏时不显示
- 更多按钮：`ic_more_vert`，20dp

#### 5.6.2 历史记录项 (item_history_song)
- 与歌曲列表项布局一致，额外显示：
  - 播放时间范围（Body Small, `colorOnSurfaceVariant`）
  - 收听时长（Body Small, `colorPrimary`）

#### 5.6.3 排行榜项 (item_rank)
```
┌─ MaterialCardView 10dp 圆角 ───────────────────┐
│ [#1] [封面36dp] 标题                  次数/时长  │
│                 艺术家                           │
└──────────────────────────────────────────────────┘
```
- 排名序号：32dp 圆形，`colorPrimaryContainer` 背景
  - Top 3 使用 `colorPrimary` 背景 + `colorOnPrimary` 文字

#### 5.6.4 队列项 (item_queue_song)
- 与歌曲列表项一致，更多按钮替换为删除按钮
- 支持拖拽手柄（左侧 `ic_drag_handle` 图标）

---

### 5.7 播放器控制

#### 5.7.1 控制按钮栏
- 布局：5 等分水平分布
- 按钮顺序：播放模式 → 上一首 → 播放/暂停 → 下一首 → 更多
- 播放模式图标：`ic_mode_list_loop` / `ic_mode_single_loop` / `ic_mode_random`（24dp）
- 上/下一首：`ic_skip_previous` / `ic_skip_next`（32dp）
- 播放/暂停：`ic_play` / `ic_pause`（36dp），带 `colorPrimary` 圆形背板（56dp）
- 更多：`ic_more_vert`（28dp）
- 着色：`?attr/colorOnSurface`

#### 5.7.2 进度条 (Material Slider)
- 样式：`Widget.Material3.Slider`
- 激活轨道：`colorPrimary`，2dp 高
- 非激活轨道：`colorSurfaceVariant`，2dp 高
- 滑块 thumb：4dp × 4dp 实心圆（`circle_thumb.xml`），`colorPrimary`
- 时间标签：`Body Small`，左当前时间，右总时长

#### 5.7.3 迷你播放器 (mini_player)
```
┌─ CardView 16dp 圆角, 72dp 高, 8dp elevation ─────────┐
│ [封面48dp] 标题（Title Small, 单行省略）  [⏮][⏯][⏭] │
│            艺术家（Body Small）                        │
└───────────────────────────────────────────────────────┘
```
- 显示条件：仅在歌曲列表页、有歌曲播放时显示
- 底部 margin: 8dp
- 点击：导航到播放页（slide_in_up 动画）
- 封面：48dp 正方形，8dp 圆角

---

### 5.8 开关与选择

#### 5.8.1 Switch (SwitchMaterial)
- 轨道激活：`colorPrimary`
- 轨道非激活：`colorSurfaceVariant`
- 滑块：白色（固定）
- 标签：右侧，Body Medium

#### 5.8.2 单选组 (RadioGroup)
- 位于对话框或专用区域中
- RadioButton：Material 3 默认样式

---

### 5.9 空状态

- 布局：垂直居中
- 图标：64-96dp，`colorOnSurfaceVariant` 40% alpha
- 标题：Body Large，`colorOnSurfaceVariant`
- 副标题：Body Small，`colorOnSurfaceVariant` 60% alpha
- 图标与文字间距：16dp

---

## 六、页面布局规范

### 6.1 播放页面 (PlayerFragment)

```
┌─────────── 背景：深色渐变 ──────────┐
│                                     │
│     [∅] → 无顶部栏，沉浸式全屏        │
│                                     │
│         [专辑封面 - 正方形]           │
│         (宽度 70%, 最大 360dp)       │
│          圆角 16dp, elevation 8dp   │
│                                     │
│    歌曲标题（Headline Medium, 居中）  │
│    艺术家（Title Small, 居中, 60%）   │
│    定时器标签（如有定时）              │
│                                     │
│    ─── 歌词区域（40% 高度）───        │
│    [当前行高亮]                      │
│    [普通歌词行]                      │
│    （可点击进入全屏）                  │
│                                     │
│  ── 进度条 + 时间 ──                 │
│  [0:00] ───●───────── [3:45]       │
│                                     │
│  ── 控制按钮 ──                      │
│  [🔀]  [⏮]  [▶️]  [⏭]  [⋮]         │
│                                     │
└─────────────────────────────────────┘
```

设计要点：
- 背景使用渐变 drawable：`#121212` → `#1E1E1E` → `#121212`（纵向）
- 封面区域占屏高度约 40%
- 歌词区域高度固定为屏幕 20%，可滚动
- 歌词无数据时显示 "暂无歌词" 空状态

---

### 6.2 歌曲列表/音乐库/收藏页

```
┌─ Toolbar ──────────────────────────┐
│ [≡] 我的歌曲              [🔍][⋮] │
├─ 搜索框 ────────────────────────────┤
│ [🔍 搜索歌曲...              ✕]    │
├─ RecyclerView ──────────────────────┤
│ [歌曲卡片 1]                         │
│ [歌曲卡片 2]                         │
│ ...                                 │
└── mini_player ──────────────────────┘
```

设计要点：
- 三页面复用同一布局（`fragment_songs.xml`）
- 区别仅在于数据源和菜单项
- 搜索框输入时实时过滤，带动画
- 空列表显示扫描引导

---

### 6.3 全屏歌词 (FullscreenLyricsFragment)

```
┌─ 纯黑背景, 沉浸式 (隐藏状态栏/导航栏) ──┐
│                                       │
│  歌曲标题（Title Large, 18sp, 居中）    │
│  艺术家（Body Medium, 14sp, 居中, 60%） │
│                                       │
│       ─── 歌词滚动区 ───               │
│       [上一行...]                      │
│       [上一行...]                      │
│       [▶ 当前播放行 - 高亮白色]         │
│       [下一行...]                      │
│       [下一行...]                      │
│                                       │
├─ 底部控制栏 ────────────────────────────┤
│  [⏮ 44dp]   [⏯ 52dp]   [⏭ 44dp]     │
└───────────────────────────────────────┘
```

设计要点：
- 歌词文字：14sp，非当前行 50% alpha 白色，当前行 100% 白色 + bold
- 滚动进度条：右侧 3dp 宽半透明白色细线，仅手动滑动时显示，松手 300ms 渐隐
- 字体大小可通过播放页菜单调节（12/14/16/18/20sp）

---

### 6.4 播放历史 (HistoryFragment)

```
┌─ Toolbar ────────────────────────────┐
│ [←] 播放历史    [最近一周 ▼] [⋮]    │
├─ 搜索框 ────────────────────────────┤
├─ RecyclerView ──────────────────────┤
│ [历史卡片 1]  收听 3:45               │
│ [历史卡片 2]  收听 1:20               │
└─────────────────────────────────────┘
```

设计要点：
- Toolbar 右侧：带边框下拉 + 选项按钮
- 时间下拉：PopupMenu 样式，锚定到下拉按钮
- 选项按钮：⋮ → PopupMenu → 排行榜 / 清空历史
- 清空确认：单选对话框（当前范围 / 全部）
- 滑动左滑可删除单条
- 空状态："暂无播放历史"

---

### 6.5 设置页 (SettingsFragment)

```
┌─ Toolbar ─────────────────┐
│ [←] 设置                  │
├────────────────────────────┤
│ 播放                      │
│ ┌────────────────────────┐ │
│ │ 通知控制    [Switch]    │ │
│ └────────────────────────┘ │
│                            │
│ 外观                      │
│ ┌────────────────────────┐ │
│ │ 主题  浅色模式    [›]   │ │
│ └────────────────────────┘ │
│                            │
│ 存储管理                  │
│ ┌────────────────────────┐ │
│ │ 缓存管理          [›]   │ │
│ └────────────────────────┘ │
│                            │
│ 关于                      │
│ ┌────────────────────────┐ │
│ │ 帮助              [›]   │ │
│ │ 关于应用          [›]   │ │
│ └────────────────────────┘ │
│                            │
│ 数据管理                  │
│ ┌────────────────────────┐ │
│ │ ⚠ 重置歌曲库           │ │
│ └────────────────────────┘ │
│                            │
│       [ 退出应用 ]         │
└────────────────────────────┘
```

设计要点：
- 分区标题：Body Small，`colorPrimary`，12dp 左边距，8dp 上下间距
- 设置卡片：无 elevation，`colorSurface` 背景，12dp 圆角
- 危险操作：`colorError` 文字
- 退出按钮：居中，Filled + `colorError`

---

### 6.6 关于/帮助页 (AboutActivity / HelpActivity)

- 顶部：CoordinatorLayout + AppBarLayout + MaterialToolbar
- 内容区域：NestedScrollView + MaterialCardView 卡片组
- 应用图标：120dp 圆角方形
- 帮助页 FAQ：卡片 + 问题（Body Large bold）+ 答案（Body Medium）

---

### 6.7 歌曲详情编辑 (SongDetailFragment)

- Toolbar："编辑歌曲信息"
- 滚动内容：封面卡片 + 3 个输入框 + 文件信息卡片
- 修改封面/歌词按钮：Outlined，44dp 高
- 收藏开关：SwitchMaterial + "收藏歌曲"
- 底部：取消（Outlined）+ 保存（Filled），右对齐

---

### 6.8 扫描结果 (ScanResultFragment)

- 黑色半透明背景
- 顶栏：扫描结果摘要 + 进度条
- 列表：文件夹 CheckBox 列表
- 底部：[添加选中的歌曲到本地] 按钮（初始禁用，选中后启用）

---

### 6.9 抽屉导航 (NavigationView)

```
┌─────────── 320dp 宽 ──────────┐
│ [背景图：渐变紫色 + 纹理]        │
│ [🎵 应用图标 64dp]             │
│ MusicPlayer                    │
│ 本地好歌，就在耳边               │
│ ────────────────────           │
│ [🎵] 音乐库                    │
│ [♥] 我的最爱                   │
│ [📋] 播放历史                   │
│ ────────────────────           │
│ [⚙] 设置                      │
└────────────────────────────────┘
```

设计要点：
- 头部背景：`nav_header_bg.png`（200dp 高）
- 头部文字：`colorOnPrimary`（白色）
- 菜单图标：24dp，`colorOnSurfaceVariant`
- 菜单文字：Body Large
- 选中项：`colorPrimaryContainer` 背景高亮

---

## 七、动画规范

### 7.1 页面切换
| 动画 | 时长 | 曲线 | 用途 |
|---|---|---|---|
| `slide_in_right` | 300ms | decelerate | 侧边栏导航前进 |
| `slide_out_left` | 300ms | accelerate | 侧边栏导航前进 |
| `slide_in_up` | 300ms | decelerate | mini → 播放页 |
| `slide_out_down` | 300ms | accelerate | 播放页 → mini |
| `fade` | 200ms | linear | Activity 过渡 |

### 7.2 弹窗
| 动画 | 时长 | 用途 |
|---|---|---|
| `scale_in` | 250ms | 弹窗入场（从 0.8 → 1.0 缩放 + 渐显） |
| `fade_out` | 200ms | 弹窗退场（渐隐） |

### 7.3 列表动画
- RecyclerView 使用 `DefaultItemAnimator`（默认 120ms 增删动画）
- 搜索过滤时避免闪烁，使用 DiffUtil 最小变更

---

## 八、主题切换

### 8.1 三种模式
| 模式 | 设置值 | 效果 |
|---|---|---|
| 跟随系统 | `MODE_NIGHT_FOLLOW_SYSTEM` | 根据系统深色模式自动切换 |
| 浅色模式 | `MODE_NIGHT_NO` | 始终使用浅色主题 |
| 深色模式 | `MODE_NIGHT_YES` | 始终使用深色主题 |

### 8.2 实现要求
- 所有颜色通过 `?attr/colorXxx` 引用 XML 主题属性
- 禁止在布局/代码中硬编码颜色值（`#XXXXXX`）
- 深色模式下 StatusBar 文字改为白色（`windowLightStatusBar = false`）
- 切换时使用 `recreate()` 确保全局生效

### 8.3 硬编码颜色检查清单
以下场景容易遗漏硬编码，需要统一改为主题引用：

| 场景 | 当前问题 | 应改为 |
|---|---|---|
| 弹窗按钮文字 | `android:textColor="@android:color/black"` | `?attr/colorOnSurface` |
| 搜索框背景 | 半透明白色 | `?attr/colorSurfaceVariant` |
| 播放页背景 | `@color/background_dark` 固定色 | 可保持，但深色模式下需对应加深 |
| 全屏歌词背景 | `android:background="#FF000000"` | 可保持纯黑（歌词场景特殊） |
| 迷你播放器卡片 | `app:cardBackgroundColor="@color/white"` | `?attr/colorSurface` |
| 抽屉头部文字 | `#FFFFFF` | `?attr/colorOnPrimary` |

---

## 九、响应式与适配

### 9.1 屏幕适配
- 所有布局使用 `dp` 和 `sp` 单位，不使用 `px`
- 封面使用百分比约束（`app:layout_constraintWidthPercent`）
- 弹窗使用 Guideline 百分比约束，根据屏幕比例自适应
- 列表项使用 `wrap_content` 高度

### 9.2 平板适配（远期考虑）
- 播放页可改为左右分栏：左侧封面+歌词，右侧队列
- 设置页可改为双栏：左侧导航，右侧内容
- 卡片最大宽度：600dp

---

## 十、UI 统一性检查清单

以下为当前代码中已知的不统一问题，后续重构时需修正：

| # | 问题 | 影响范围 | 修复方案 |
|---|---|---|---|
| 1 | 弹窗 `btnClose` 文字颜色硬编码黑色 | 播放队列、缓存管理 | 改为 `?attr/colorOnSurface` |
| 2 | 弹窗 `btnClose` 使用不同样式（Outlined vs Elevated） | 歌曲信息/缓存管理 | 统一为 Outlined |
| 3 | 部分 PopupMenu 图标着色不一致 | 各处 | 统一用 `?attr/colorOnSurfaceVariant` |
| 4 | 歌曲列表页标题使用 `android:textColorPrimary` | SongsFragment | 统一用 `?attr/colorOnSurface` |
| 5 | `tvDuration` 在历史项中颜色与 body 不同 | HistoryAdapter | 统一使用 LabelSmall + `colorOnSurfaceVariant` |
| 6 | 迷你播放器卡片背景可能硬编码白色 | mini_player.xml | 改为 `?attr/colorSurface` |
| 7 | 播放页背景渐变使用固定深色 | player_background_gradient.xml | 深色模式下需调整渐变端点值 |
| 8 | `action_time_range_dropdown` 边框颜色硬编码 | 历史页 Toolbar | 改为 `?attr/colorOutline` |

---

## 十一、设计文件交付物

### 后续待补充
- [ ] Figma/Sketch 源文件（含所有页面和组件）
- [ ] 组件库（Button / Card / Dialog / ListItem 等 Symbol）
- [ ] 交互动效原型（导航流程、弹窗动画）
- [ ] 图标统一导出（SVG 24dp 网格，2px 笔画）

---

> **文档维护规则**：本规范是 UI 实现的一等权威来源。所有 UI 代码修改必须符合本规范。规范修改需同步更新本文档并标注版本号和日期。
