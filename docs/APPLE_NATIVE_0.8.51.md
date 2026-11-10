# 0.8.51 Apple Music 在 Salt 内播放

播放入口现在使用 MusicKit → Web Audio PCM → Salt 私有 Range 流 → BASS。
Windows Apple Music 客户端启动、UIA 点击及 SMTC 控制代码已移除。

## 使用

在 Apple Music 页面点击账号图标，在独立 Edge 中登录有订阅的账号。
回到 Salt 双击歌曲，或者进入资料库歌单选择播放全部。音量、暂停、跳转、
插播和随机顺序使用 Salt 的原生播放器与队列。曲目可保存到 Salt 歌单并重启恢复。
资料库中无 catalog ID 的上传音轨仍不可点播。

浏览器只是后台 MusicKit 解码环境，采用独立临时 profile 和私有 CDP 管道。
音频图的输出保持静音，解码出的 PCM16 / 48 kHz / 双声道交给 Salt；不会启动
Windows Apple Music 客户端。登录态保存在插件的 DPAPI 账号存储中。

## 实现

- Apple 曲目以 storefront + catalog ID 标识，保存原始来源，队列恢复时重新授权解析。
- MusicKit 检查账号授权、完整播放类型、曲目 ID 和时长。试听或无订阅不能作为完整歌曲提交。
- 浏览器 AudioWorklet 通过有上限的 PCM 队列输出，Salt 按需读取 WAV Range。
  暂停时浏览器停止生成；跨区域跳转映射到 MusicKit seekToTime。
- 插件的 PCM 窗口最多 16 MiB，不进入持久 CDN 磁盘缓存或整首预下载。
  Salt 的原生 HttpRangeSource 会另建临时 Range 文件，关闭流时由宿主清理。
- 退出、切换账号、权限撤销或模组关闭时撤销对应流并关闭私有浏览器。
- Apple 歌单不启用后三首预下载，避免提前创建多个订阅播放会话。

## 参考项目

MineRadio Apple Music Edition 固定提交
`e0ddc69ff62d717515e804c3c2d9be7a5e4a35fd` 用于网页登录与资料库 API。
其 Apple 播放由官方客户端承担，本版已移除对应控制代码。

Cider 固定提交 `953bb5a9e26080bba2bb20438f0ddf38b0a8b260` 用于架构核对：
MusicKit、媒体元素 Web Audio 与 AudioWorklet PCM。未复制 Cider 源码或运行时；
本项目独立实现 PCM 桥接，使用已安装的 Microsoft Edge 和 Apple 在线 SDK。

## 验证范围

已用真实无界面 Edge 验证非静音 AudioWorklet PCM、0 / 7 / 1 秒跳转；浏览器
报告 Widevine 与 PlayReady 可用。相同 PCM 通路通过 Salt 实际 HttpRangeSource
及实际 BASS 解码，完成非静音、原生跳转、完整 EOF 和流释放检查；均使用合成测试音频，
不访问用户账号或输出声音。另有立体声样本身份、非对齐 Range、内存缓存、
撤销、持久歌单封面和原生 UI 回归。

当前环境没有 Apple Music 登录态。订阅账号歌曲的实际授权、DRM 解码及连续发声
仍需用户登录后验收，合成音频验证不能代表已验证真实订阅内容。

本地验证：84 项插件契约日志均通过；13 项无宿主契约通过；发布脚本 5 项测试通过。
该实现包含在 `0.9.0` 开发预览安装包中。真实订阅内容验收仍待有有效订阅的账号登录。
