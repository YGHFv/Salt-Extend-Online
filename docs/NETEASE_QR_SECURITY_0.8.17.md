# 0.8.17 网易云扫码安全环境

## 对照结果

参考项目固定为 NeriPlayer `fd7fdb757821fda7a62aee3a2b7cecf3a4b8bfe0`：

- `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/auth/NeteaseQrLoginClient.kt`
- `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/auth/NeteaseYdDeviceTokenProvider.kt`

参考项目先在官方网页调用 `createNEFingerprint({appId, timeout}).getToken()`，获取 `ydDeviceToken`、`sDeviceId` 和对应 Cookie。生成的扫码 URL 与轮询请求使用同一 `chainId`。

此前插件只移植了无设备快照分支：`ydDeviceToken` 固定为空，`chainId` 使用随机 `unknown` 标识。这与参考项目的完整登录路径不同，是可能触发“安全风险”提示的兼容性缺失。未操作真实账号，不能据此断言每一次服务端风险提示都由这一原因造成。

## 修复路径

1. 用户明确点击扫码登录后，使用已安装的 Microsoft Edge 打开独立、无窗口的临时配置；不会在启动插件、发现、恢复已保存账号时预先启动设备准备。
2. 仅在 `https://music.163.com` 来源执行官方页面已加载的设备 SDK；不下载或注入替代 SDK，不生成假令牌，不隐藏自动化特征。准备过程有超时，可取消。
3. 校验非空令牌、合法设备 ID、对应的有效根路径 `sDeviceId` Cookie、User-Agent 和 Cookie 域／路径／数量／大小。新设备上下文不允许携带 `MUSIC_U` 登录凭据。
4. 设备准备成功后才创建二维码，三分钟有效期从创建成功起计算。创建、轮询和账号确认使用该设备上下文的同一 User-Agent、Cookie、令牌与链路标识；`ydDeviceToken` 位于 WEAPI 加密请求体，不出现在二维码 URL。
5. 二维码仅允许由创建它的客户端轮询；拒绝重构／外来会话，确认和过期时消费会话，关闭客户端立即取消请求。确认后仍必须通过独立账号接口校验 ID；不采用参考项目“校验失败也保留凭据”的后备分支。
6. 缺少 Edge、官方 SDK／设备信息不可用或设备准备失败时，不生成空令牌二维码；提示检查环境或使用「官方网页登录」。服务端 `8821`、`-462` 仍要求用户完成官方验证，不自动换设备重试。

## 安全与隐私边界

- 复用独立网页登录的受限临时目录与匿名管道，不开放 TCP 调试端口，不读取默认浏览器配置或其他软件账号。
- 子进程输出只通过父进程管道读取；清理辅助进程及本次临时目录。设备令牌仅用于当前扫码，不持久化或写入日志。登录成功后的账号 Cookie 仍遵循原有内存／Windows CurrentUser DPAPI 保存设置。
- Java 层拒绝非法 Header 值，设备上下文和会话的 `toString()` 脱敏。错误只含固定阶段／原因和数字状态码，不直接显示服务器原始消息或跳转链接。
- 不读取用户截图，不生成截图或把图片／base64 加入开发对话。
- 设备 SDK 由网易云官方页面执行，联网与设备信息处理遵循该官方页面的行为；不是禁用或绕过平台的设备检查。正式网站仍可能拒绝无窗口环境，此时使用可见的官方网页登录手动验证。

## 验证

- `NeteaseQrSecurityContractTest`：合成设备上下文、缺失／畸形令牌、Cookie 域和路径、设备 ID 不匹配、混入账号、UA／Header 注入、同次扫码链路与请求上下文、跨客户端／已消费／关闭／过期会话、风险响应停止、设备失败不发二维码请求、准备取消不接受迟到结果。
- 现有二维码协议、Cookie、WEAPI、账号生命周期与网页登录契约继续运行；安全契约也在 `java.base` 隔离环境复跑。这四组登录契约已加入公共无宿主回归，推送 GitHub 即自动执行，不依赖私有 Salt runner。
- `WindowsWebLoginProbe --explicit-headless-fixture` 仅打开 `about:blank`，用合成 JS SDK 和 Cookie 测试实际 PowerShell／Edge 匿名管道、设备脚本映射和来源拒绝、超时取消及临时目录清理。不访问网易云、不扫码、不操作真实账号、不启动音频。
- 完整本地构建运行统一契约清单、原生 UI／宿主 ABI 回归并打包；公共 CI 仍不具备真实账号或私有宿主安装。

这些测试验证客户端协议与隔离边界，不证明所有账号都能通过网易云风控。实际扫码确认和手机端“安全风险”提示是否消失，仍需用户在自己的账号上验收；若仍有提示，只需提供脱敏错误阶段和数字码，不要发送二维码、Cookie 或设备令牌。

### 本地结果（2026-11-08）

- 全部 51 个插件契约入口通过，含 `java.base` 复跑；新扫码安全契约 76 项、原生账号／搜索界面 227 项通过。
- 公共无宿主回归的 8 个插件入口、70 项核心策略及 6 个 Python 检查器测试通过。
- 真实隐藏 PowerShell／Edge 空白页探针通过，完成后临时配置目录清理验证通过。
- 本地包：`plugin-io.github.yghfv.saltneri.online-0.8.17-netease-qr-security.spmod`，243 项，576,785 字节。
- SHA-256：`6E794EFE26D7B623E6BE11A2A07DEF6A89ED7A3F1DEF8A395D9F685E4B537DB6`。
