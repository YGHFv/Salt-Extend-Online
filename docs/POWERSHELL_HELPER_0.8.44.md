# 移除登录流程对 PowerShell 的依赖 0.8.44

## 用户反馈与判断边界

2026-11-09 反馈用户提供完整错误提示 `NET_WEB/HELPER`。这是反馈用户的错误，不是开发者本机错误；不能从开发者本机安装版本或日志推断其环境。

0.8.43 的 `HELPER` 覆盖多个分支：SystemRoot／Windows PowerShell 不存在、模组脚本资源读取失败、临时脚本写入失败、CreateProcess 失败、管道失败、辅助进程非零退出、输出超限。该提示不能证明已执行到 Edge 启动，也不能证明安全软件一定拦截。之前只把 stderr 丢弃，没有固定失败分类。

用户明确要求尽量一次处理完成，不让反馈用户反复承担排查工作。因此最终方案不是只细分 `HELPER`：直接移除正式登录及账号保存／恢复对 PowerShell 的依赖。保留兼容诊断工具的短普通命令修复，但生产入口不再调用它。不宣称已复现远端机器的确切安全软件或系统限制。

## 变更

- 网易云／QQ 官方网页登录、网易云扫码设备环境改为 Java 调用 Salt 已有 JNA Windows 接口，直接创建 Edge，不启动 PowerShell、不运行登录脚本。
- 只给浏览器继承明确列出的匿名管道和 NUL 句柄，不继承 Salt 其他句柄、不打开 TCP 调试端口。
- 使用 `CREATE_SUSPENDED` 启动，在恢复执行前加入仅本次进程拥有的 kill-on-close Job；取消／超时／关闭清理整个本次浏览器进程树，不结束用户已有 Edge。
- 继续检查 Edge 安装目录／App Paths 和 32／64 位强制策略；每个请求有界等待，冷启动 25 秒，页面轮询 250 秒。
- 继续使用私有 ACL 全新配置，不导入默认浏览器 Cookie；QQ Cookie allowlist、两次稳定样本、官方鉴权后保存保持有效。
- 账号保护与恢复直接调用 Windows Crypt32 的 CurrentUser DPAPI，entropy 和数据文件格式保持不变；合成旧 .NET／新原生密文已双向解密验证，不迁移或触碰真实用户账户。
- CDP Cookie 含有小数时间戳，补充 BoundedJson 的有界 BigDecimal 编码，拒绝过大精度和指数，避免将解析后的 Cookie 重编码时失败。

以下改进保留在兼容诊断辅助程序中，生产网页登录／设备环境不会使用此程序：

- 使用短的普通 `-Command` 引导代替 `-EncodedCommand`，避免依赖编码命令形式；仍只在命令行传代码文件加载器，不传登录 Cookie，不将整份脚本塞入命令行。
- 保留原有私有 ACL 临时目录、UTF-8 代码文件、匿名输入／输出管道、取消／超时和精确子进程清理。
- 通过 SystemRoot／windir 查找 PowerShell，优先使用存在的 Sysnative，再使用 System32，兼容 32 位进程访问 64 位系统组件；不执行 PATH 中任意同名程序。
- 引导层检查 Windows PowerShell 5.1 版本及 `FullLanguage`；受限语言模式明确拒绝，不切换为完整语言，不改执行策略、不加 Bypass，不改注册表、不申请管理员权限。
- 引导层捕获脚本解析、初始化和读写异常，只通过固定 JSON 原因返回；异常文本／用户目录不输出。
- stderr 只在内存保留前 8 KiB 并持续排空，避免错误输出堵塞进程。仅匹配固定 PowerShell 安全错误标识，非零退出保留数字退出码；原始 stderr 不写日志、不展示，并在操作结束后清零。
- QQ 网页登录和网易云设备环境保留同一安全原因映射。
- 原生登录改动不修改 Edge 安装、播放／原生解码、QQ 音源、歌词或 UI；同一最终 0.8.45 包另外修复播放列表封面，详见 `PLAYLIST_COVER_0.8.45.md`。

## 新增安全错误码

| 原因 | 含义 |
| --- | --- |
| `HELPER_MISSING` | 未找到 Windows PowerShell 系统组件 |
| `HELPER_RESOURCE` | 模组脚本资源缺失／无法读取 |
| `HELPER_START` | PowerShell 进程启动失败 |
| `HELPER_POLICY` | 受限语言模式或已识别的脚本安全策略拒绝 |
| `HELPER_VERSION` | 系统 PowerShell 版本低于 5.1 |
| `HELPER_SCRIPT` | 辅助脚本解析／初始化异常 |
| `HELPER_IO` | 辅助脚本文件或管道读写失败 |
| `HELPER_EXIT=n` | 辅助进程非零退出，数字 n 为退出码 |
| `HELPER_OUTPUT` | 输出为空或超过上限 |

原有 Edge 启动、策略、连接超时和官方验证阶段继续单独显示。没有用“关闭安全软件”或“绕过执行策略”代替问题定位。

## 验证范围

合成输入覆盖组件查找、Sysnative／System32、windir 回退、引号处理、命令长度、无编码命令／无执行策略绕过、固定 stderr 安全分类、数字退出码与脱敏。

真实 Windows PowerShell 子进程覆盖脚本语法错误、IOException、通用异常、安全异常、非零退出、空输出和受限语言模式；受限模式只在一次性子进程内设置，不修改系统策略。真实隐藏 Edge 测试只打开 `about:blank`，使用合成 Cookie／设备脚本，验证取消、超时和临时配置清理，没有真实用户账号或外站导航。

最终 0.8.45 类文件的真实隐藏 Edge 验证通过 19 项：直接 Win32、句柄白名单、Job、私有配置、合成 QQ／网易云 Cookie、设备脚本、来源检查、取消、超时、特殊路径与清理；生产流程没有 PowerShell、外站导航或真实账号。原生登录合成契约通过 36 项，网页登录协议契约通过 168 项；旧 .NET／新原生 DPAPI 双向解密和原生篡改拒绝均通过。11 项宿主无关契约套件通过。

合并封面修复的最终包通过 76 项契约、103 次宿主／java.base 执行，391 个包条目与全新验证构建逐字节相同。包为 `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.45-native-login-cover.spmod`，967,075 字节；SHA-256 为 `AC0E8E92B2737F6167FBBE5229C459EDFAD8858E8D8108054B4AD63B58497EE1`。

本轮不安装模组、不重启本机 Salt。反馈用户只需更新最终 `0.8.45-native-login-cover` 并完整重启，不再要求先安装 PowerShell、关闭安全软件或反复运行排查命令。真实系统禁止 Edge 调试等明确策略仍会安全拒绝，不能承诺在任意策略下登录成功。
