# 构建与自动化

## 单一入口

- `plugin.properties`：插件 ID、名称、提供者、实验版和被动检查版版本。PowerShell、Gradle、包验证器共用。
- `scripts/contract-tests.json`：96 个自动插件回归入口，其中 15 个纳入公共无宿主回归。`baseOnly` 表示额外验证只需 `java.base`，`offline` 表示完全不需要 Salt 类。
- `scripts/verify-project.py`：检查版本格式、稳定 ID、设置 JSON、测试重复／漏项和离线源文件清单。

本地完整构建先验证项目、第三方源码、class-file 检查器和核心策略，再编译 Java / Kotlin、校验实际宿主 ABI、运行插件测试，最后验证包隔离。

每个插件测试的日志独立保存在本次 `plugin/build/local/<GUID>/` 下；除了非零退出码，后台线程／Compose 协程的未捕获异常也会阻止打包。
每个测试默认最多运行 300 秒，可用 `-ContractTimeoutSeconds` 调整；歌单的多布局／DPI 完整渲染正常需要约 3 分钟，保留 CI 波动余量。超时终止该测试的进程树并保留日志。标准输出和错误输出同时读取，防止输出缓冲区塞满后互相等待。宿主相关测试的用户目录、`APPDATA` 和 `LOCALAPPDATA` 指向本次构建的临时目录。

## 无宿主回归

Windows、PowerShell、Python 3、JDK 25：

```powershell
./scripts/test-offline.ps1 -JdkHome 'C:\path\to\jdk25'
```

包括 JDK 17 目标的核心策略、Python 检查器测试、二维码协议与设备上下文安全、账号生命周期、网页登录风险停止、受限 JSON、HTTPS 固定目标、真实本机 Range HTTP 和磁盘缓存。HTTP 上游使用 fixture，不需要账号、不访问平台媒体、不启动播放器或浏览器。

这套回归不能替代原生 UI、Hook、真实账号扫码与宿主队列测试。

## 完整构建

需要合法安装 Windows / Steam Salt 1.19.3。PowerShell 脚本从安装配置读取宿主 classpath，检查包路径位于安装 `app` 内，不修改安装目录。

```powershell
./scripts/setup-native-ui.ps1
./scripts/build-local.ps1 -SaltHome 'D:\path\to\Salt Player for Windows' -JdkHome 'C:\path\to\jdk25' -Experimental
```

`setup-native-ui.ps1` 下载并核对锁定的 Kotlin / Compose 工具链 SHA-256；编译器和宿主文件均不进入 `.spmod`。每次构建使用新的 GUID 目录，避免残留 class 混入。

输出：`plugin/build/libs/plugin-<id>-<version>.spmod`。省略 `-Experimental` 得到不申请流 Hook 权限的被动检查包，不提供在线音乐功能。

自动测试包含真实宿主类、合成封面和离屏原生 UI，但不运行真实音频，也不登录用户账号。现场 `*Probe.java` 不在自动测试清单中。

1.0.0 新增真实 PF4J 加载器关闭后再清理 Compose 侧栏的回归，以及后台启动、AWT 响应、停止前等待初始化退出和取消旧初始化的生命周期回归。

1.0.1 将回归入口增至 94 个，增加慢连接取消时翻页和页面退出的 AWT 响应测试、当前及后三首视频预缓存测试，并扩展视频 Range 缓存、非对齐进度、分辨率降级和 CDN 备用地址检查。

1.0.2 将回归入口增至 96 个，增加日志投影隐私回归及后台文件导出／AWT 响应回归。仅使用合成日志和隔离目录，不导出真实用户日志；无宿主回归也覆盖 Cookie、编码／多行资料、伪造栈帧、异常类别保留与 4 MiB 尾部读取上限。

默认构建不显示桌面测试窗口、不抢焦点、不移动或点击鼠标。视频测试仍运行时钟、离屏渲染、原生解码控制和生命周期检查，屏幕采样部分明确标记为 `SKIP`。需要前台窗口的 Direct3D 帧率、真实鼠标和窗口消息回归，只能在本机显式传入 `-InteractiveDesktopTests` 后运行；该模式会创建置顶测试窗口并操作鼠标，应在专门的测试时段使用。`CI=true` 或 `GITHUB_ACTIONS=true` 时禁止启用，Gradle 自动检查也始终关闭此模式。

## Gradle

Gradle 配置同样读取元数据和测试清单，`check` 不再遗漏本地构建中的合集、预加载、引擎及离屏渲染测试。须提供宿主包路径；宿主包即安装 cfg 中 `app.classpath` 指向的归档，即使后缀不是 `.jar`。

```powershell
gradle :core:check :plugin:check -PsaltHostPackage='D:\path\to\Salt\app\host-archive.dll'
```

要求 JDK 17 / 25 toolchain、Kotlin 2.4.0 和 Workshop Gradle 插件可用。原生库目录从宿主归档所在目录派生。当前发布验收使用 `build-local.ps1`，未把未经执行的 Gradle 路径标记为已验证。

## GitHub Actions

工作流：`.github/workflows/build.yml`。

| 事件 | 公共 Windows runner | 专用 Salt runner |
| --- | --- | --- |
| 推送 main | 无宿主回归 | 配置启用后自动完整测试／打包 |
| Pull Request | 无宿主回归 | 不运行，避免不可信代码访问本机 |
| 手动运行 main | 无宿主回归 | 配置启用后完整测试／打包 |

### 启用自动打包

1. 准备支持 Node.js 24 Actions 的新版专用 Windows x64 runner，安装合法 Salt 1.19.3、JDK 25、Python 3 和 PowerShell 7；不要使用保存日常账号的个人桌面作为公共 PR runner。
2. 在本仓库注册 self-hosted runner，增加标签 `salt-1.19.3`；不要把 Salt 安装在 runner checkout 工作目录中。
3. 在 GitHub Settings → Environments 创建 `salt-build`，部署分支限制为 main；按需要添加人工审批。
4. 在此环境 Variables 中配置 `SALT_HOME`（Salt 安装目录）和 `SALT_JDK_HOME`（JDK 25 目录）。路径直接传参，不拼成待执行命令。
5. 在仓库 Settings → Secrets and variables → Actions → Variables 设置 `SALT_PACKAGE_ENABLED=true`。此开关必须是仓库变量，不能仅放在 environment 中。
6. 推送 main 或手动运行工作流；全部回归通过后，从该 run 的 Artifacts 下载 `Salt-Extend-Online-<version>`，其中包含 `.spmod` 和 `.sha256`，保留 14 天。

未设置开关时打包作业明确跳过。设置开关但 runner 离线时会等待 runner；新提交取消同分支旧作业，避免旧的排队构建阻塞当前提交。打包作业只读仓库，独立的 GitHub Linux 发布作业在完整回归通过后发布 `.spmod` 和校验文件，不上传宿主文件。版本与 runner 维护见 [自动打包与发布](SELF_HOSTED_RELEASE.md)。

已注册的本机 runner 可显式安装当前用户的守护计划任务：

```powershell
./scripts/setup-actions-runner.ps1 -RunnerRoot 'E:\MTool\Actions\salt-extend-runner'
```

任务在登录时启动，每分钟检查，异常退出后重试；运行窗口隐藏。同一时刻只允许一个守护进程，网络／代理暂时中断时保留官方 runner 的连接重试。此操作由维护者独立执行，构建脚本不安装任务。

## 手工验收

用用户自行登录的真实账号验证：双端发现／搜索／收藏，合集第一页／末页／排序／全部播放，随机与顺序切歌，旧项回播与封面，网易云歌词，退出／切换账户，以及升级重启后的系统媒体卡片名称。官方权限和上游可用性始终以实际接口为准。
