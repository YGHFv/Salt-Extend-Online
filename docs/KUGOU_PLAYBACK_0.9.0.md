# 0.9.0 酷狗实际播放修复

歌单和歌曲信息可加载，但点播出现 `SSLHandshakeException`。实际调用 HTTPS
KA Music API 后发现：`/song/url` 返回的是
`http://fs.youthandroid2.kugou.com/...` 与 `http://fs.youthandroid.kugou.com/...`。
旧代码强制改为 HTTPS，CDN 的证书没有对应域名，证书检查因此拒绝连接。
原始 HTTP 地址可正常返回 `206`，文件总长度与歌曲权限信息一致。

## 修改

- 保留 HTTPS API 返回的音轨原始协议，不再改写成 HTTPS。
- HTTP 只允许上述两个精确域名和标准 80 端口，其余媒体沿用 HTTPS 策略。
  使用同一个 DNS 固定连接实现；拒绝私网地址、全局代理、Cookie、
  Authorization 和 API session 头。HTTPS 仍验证证书与域名。
- 音轨解析逐个验证最多六个不同候选地址，先验证完整文件大小再发布；
  第一个节点连接、TLS 或 Range 检查失败时继续尝试下一个。
  取消操作和账号撤销仍立即终止。
- Salt 后续音频 Range 读取使用同样的协议策略，拒绝 HTTPS 向 HTTP 降级跳转。
- 保留 hash 一致性、完整歌曲大小、试听排除与账号归属检查。
  安全阶段码如 `KUGOU/MEDIA_TLS`、`KUGOU/MEDIA_UNAVAILABLE` 可进入播放错误提示。

## 2026-11-10 本地验收

使用插件已有酷狗登录态、真实 API 和 CDN，经私有 Loopback Range、Salt 实际
HttpRangeSource 和 BASS 解码器验证三首不同曲目。BASS 使用无声音输出设备；
没有操作运行中的 Salt 播放队列。

| 音轨 | 解析耗时 | 原始媒体字节 | 完整解码 PCM 字节 |
| --- | ---: | ---: | ---: |
| 1 | 372 ms | 1,021,118 | 11,209,148 |
| 2 | 426 ms | 1,730,613 | 19,067,904 |
| 3 | 637 ms | 3,710,071 | 40,889,520 |

三首均通过非静音 PCM、跳转至中段、返回开头、完整 EOF 与流释放。
实时探针位于 `plugin/src/test/java/io/github/yghfv/saltneri/plugin/host/KugouPlaybackLiveProbe.java`，
只在显式调用时使用插件自己的 DPAPI 账号，不输出 session 或签名音频地址。

回归覆盖精确 HTTP 域名、禁止凭据、DNS 固定连接、端口与重定向限制、
原始协议保留、备用节点及全节点失败脱敏，另运行完整插件与无宿主回归。
84 项插件契约全部通过（含 java.base 重复运行共 116 份通过日志），
13 项无宿主插件契约、74 项核心检查、5 项发布脚本测试通过。
私有 Edge 启动以及合成浏览器 PCM 到实际 BASS 的完整解码回归也通过。
`0.9.0` 安装包通过打包隔离和版本检查，附带 SHA-256 校验文件。

API 对部分歌曲仍会返回 `HTTP_400`；这是已观察到的上游请求拒绝，
此次修复不会绕过该拒绝。`0.9.0` 保留 Apple Music 在 Salt 内播放的实现，
Apple 实际订阅歌曲仍待登录验收。
