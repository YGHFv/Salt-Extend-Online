# 历史记录

当前功能与限制以 [README](../README.md)、[架构](ARCHITECTURE.md) 和 [构建](BUILD.md) 为准。早期文档含未实现设计、旧配置按钮或当时的验收状态，不应作为当前使用步骤。

| 版本／主题 | 记录 |
| --- | --- |
| 1.0.2 脱敏诊断日志导出 | [配置底部导出入口、后台文件写入、严格日志投影与隐私回归](DIAGNOSTIC_EXPORT_1.0.2.md) |
| 1.0.1 翻页卡顿与视频缓存 | [慢连接取消阻塞复现、默认分辨率降级、视频缓存与官方 CDN 备用地址](VIDEO_CACHE_UI_FIX_1.0.1.md) |
| 1.0.0 界面卡死与启动优化 | [PF4J 卸载后 Compose 清理缺类复现、后台初始化与旧歌单恢复优化](UI_STARTUP_FIX_1.0.0.md) |
| 0.9.11 QQ 缺失音频与测试窗口 | [404/410 官方节点与音质降级、单曲错误码、桌面测试显式开启](PLAYBACK_CI_FIX_0.9.11.md) |
| 0.9.10 更新后点击失效 | [窗口框架执行后观察、原生消息回调地址稳定性回归](WINDOW_INPUT_FIX_0.9.10.md) |
| 0.9.9 视频隐藏与点击恢复 | [独立胶囊背景同步隐藏、原生输入回调解除会话锁依赖](VIDEO_INPUT_FIX_0.9.9.md) |
| 0.9.8 视频按钮恢复 | [宿主支持的界面钩子、默认参数与真实转换器验证](VIDEO_HOOK_FIX_0.9.8.md) |
| 0.9.7 视频控件与播放页交互 | [窗口按钮同步隐藏、单击播放与原生展开收起](VIDEO_CONTROLS_0.9.7.md) |
| 0.9.6 菜单定位与视频崩溃修复 | [设置行锚点、单一原生标题栏与真实窗口回归](MENU_VIDEO_FIX_0.9.6.md) |
| 0.9.5 逐源代理与整页视频 | [代理二级设置、控件自动隐藏和视频连续播放](PROXY_VIDEO_0.9.5.md) |
| 0.9.4 视频流畅度修复 | [连续帧测量、后台转换与音频时钟同步](VIDEO_PACING_0.9.4.md) |
| 0.9.3 视频渲染修复 | [Direct3D 实际窗口绘制、异常恢复与播放图标](VIDEO_RENDER_0.9.3.md) |
| 0.9.2 播放页视频 | [B站视频、网易云／QQ MV、原生视频画面与音频进度同步](PLAYBACK_VIDEO_0.9.2.md) |
| 0.9.1 播放缓冲、配置与平台歌单 | [断续修复、二级设置、四平台换源、歌曲收藏、歌单订阅与播放全部](SETTINGS_PLAYLISTS_0.9.1.md) |
| 0.9.0 酷狗音乐与 Apple 内播放 | [CDN 原始协议、备用地址与真实原生解码](KUGOU_PLAYBACK_0.9.0.md)；[MusicKit PCM 通路](APPLE_NATIVE_0.8.51.md) |
| 0.8.51 Apple 原生播放 | [MusicKit PCM 与 Salt 原生播放通路](APPLE_NATIVE_0.8.51.md) |
| 0.8.50 酷狗音乐与加载修复 | [酷狗音乐、私人歌单、推荐速度和 Apple 客户端](KUGOU_0.8.50.md) |
| 0.8.49 登录与推荐 | [QQ 授权、网易云每日／漫游推荐与 Apple Music 接入](QQ_APPLE_0.8.49.md) |
| 0.8.48 登录认证 | [Cookie 就绪、账号验证、浏览器上下文与保存锁顺序](AUTH_FLOW_0.8.48.md) |
| 0.8.47 Edge 启动 | [Windows 标准匿名管道与登录错误保留](EDGE_PIPE_0.8.47.md) |
| 0.8.46 网易云设备 | [官方设备令牌与扫码上下文](NETEASE_DEVICE_0.8.46.md) |
| 0.8.45 切歌封面 | [持久队列封面补齐、重试与原生刷新](PLAYLIST_COVER_0.8.45.md) |
| 0.8.44 原生登录 | [移除 PowerShell 登录辅助依赖](POWERSHELL_HELPER_0.8.44.md) |
| 0.8.43 网页登录 | [Edge 发现与启动兼容](EDGE_WEB_LOGIN_0.8.43.md) |
| 0.8.42 QQ 正式音轨 | [媒体身份与官方 vkey 返回兼容](QQ_FULL_STREAM_0.8.42.md) |
| 0.8.41 QQ 网页与封面 | [网页登录验证、封面与图标](QQ_WEB_ARTWORK_0.8.41.md) |
| 0.8.40 切歌卡死 | [解码器切换与关闭顺序](DECODER_SWITCH_0.8.40.md) |
| 0.8.39 QQ 逐字歌词 | [登录优化与 QRC 解密、时间轴及翻译](QQMUSIC_LOGIN_QRC_0.8.39.md) |
| 0.8.38 QQ 音乐 | [第三平台接入](QQMUSIC_0.8.38.md) |
| 0.8.36 CDN 与预缓存 | [官方跳转、后三首预缓存与播放恢复](CDN_PREFETCH_0.8.36.md) |
| 0.8.35 首页与导航 | [启动首页、平台栏目与侧栏管理](NAVIGATION_SETTINGS_0.8.35.md) |
| 0.8.34 顶部对齐 | [侧栏按钮隐藏与缩放布局](SIDEBAR_ALIGNMENT_0.8.34.md) |
| 0.8.33 侧栏恢复 | [Compose 追加钩子与入口恢复](SIDEBAR_RECOVERY_0.8.33.md) |
| 0.8.32 原生页面 | [合集与作者主页的原生接入](NATIVE_SOURCE_0.8.32.md) |
| 0.8.31 播放与侧栏 | [播放意图确认与侧栏设置](PLAYBACK_SIDEBAR_0.8.31.md) |
| 0.8.30 播放缓冲 | [首曲准备与连续分段预读](PLAYBACK_BUFFER_0.8.30.md) |
| 0.8.29 顶部布局 | [快捷入口裁切、图标与原色](SIDEBAR_LAYOUT_0.8.29.md) |
| 0.8.28 侧栏分发 | [原生 UI 分发与默认参数](SIDEBAR_DISPATCH_0.8.28.md) |
| 0.8.27 顶部快捷入口 | [侧栏优化与本地歌单](SIDEBAR_SHORTCUTS_0.8.27.md) |
| 0.8.26 分页 | [可配置上限与增量滚动](CATALOG_PAGING_0.8.26.md) |
| 0.8.25 合集与作者 | [完整快照与作者恢复](SEASON_SNAPSHOT_0.8.25.md) |
| 0.8.24 整单播放 | [持久身份批量保存](BULK_START_0.8.24.md) |
| 0.8.23 重启恢复 | [在线队列重新绑定](RESTORED_QUEUE_0.8.23.md) |
| 0.8.22 原生歌单 | [插播、菜单与持久化](NATIVE_PLAYLISTS_0.8.22.md) |
| 0.8.21 播放生命周期 | [读取关闭与流隔离](STREAM_LIFECYCLE_0.8.21.md) |
| 0.8.20 连续播放 | [缓存、地址有效期与断流重试](PLAYBACK_FLOW_0.8.20.md) |
| 0.8.19 原生搜索 | [官方搜索组件](NATIVE_SEARCH_0.8.19.md) |
| 0.8.18 原生标题栏 | [标题、工具栏与布局](NATIVE_TOOLBAR_0.8.18.md) |
| 0.8.17 网易云扫码 | [官方安全设备环境](NETEASE_QR_SECURITY_0.8.17.md) |
| 0.8.16 歌单与封面 | [网易云目录与图片加载](NETEASE_CATALOG_0.8.16.md) |
| 0.8.15 页面导航 | [返回、布局与作者主页](CATALOG_NAVIGATION_0.8.15.md) |
| 0.8.14 列表与排序 | [并行读取、布局、裁切、作者主页](CATALOG_LAYOUT_0.8.14.md) |
| 0.8.13 项目整理 | [缓存、资源路径、失败恢复、构建与 CI](MAINTENANCE_0.8.13.md) |
| 0.8.12 合集播放 | [固定封面路径、完整读取、随机顺序与预加载](PLAYLIST_PRELOAD_0.8.12.md) |
| 0.8.11 队列与设置 | [续期回播、音质、缓存与系统媒体身份](QUEUE_SETTINGS_0.8.11.md) |
| 0.8.10 默认发现 | [歌词、菜单图标、首次进入发现](DISCOVERY_START_0.8.10.md) |
| 0.8.9 加载 | [分页与发现缓存](LOADING_0.8.9.md) |
| 0.8.8 封面 | [MIME 与原比例图片](COVER_MIME_0.8.8.md) |
| 0.8.7 搜索 | [网易云搜索、重试与刷新](SEARCH_REFRESH_0.8.7.md) |
| 0.8.6 网页登录 | [网易云官方 Edge 登录](NETEASE_WEB_0.8.6.md) |
| 0.8.5 账号 | [网易云凭据与权限处理](NETEASE_AUTH_0.8.5.md) |
| 0.8.0 合集与发现 | [整单动作与发现源](PLAYLIST_DISCOVERY_0.8.0.md) |
| 精简前的介绍 | [完整 0.8.48 README 快照](README_0.8.48_ARCHIVE.md) |
| 历史总览 | [完整 0.8.12 README 快照](README_0.8.12_ARCHIVE.md) |

设计与核验背景：[初始设计](DESIGN.md)、[宿主适配约定](SALT_HOST_ADAPTER.md)、[Steam 1.19.3 核验](HOST_AUDIT_1.19.3.md)、[NeriPlayer 对齐来源](NERIPLAYER_PARITY.md)。
