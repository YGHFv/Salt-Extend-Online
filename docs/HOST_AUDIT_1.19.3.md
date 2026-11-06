# Steam Salt 1.19.3 宿主核验

核验日期：2026-11-06。结论：**已找到可适配的原生入口，完成静态签名／指纹检查与无播放副作用的媒体对象测试；未完成真实在线播放联调。**

## 1. 安装包事实

用户提供目录：`D:\SteamLibrary\steamapps\common\Salt Player for Windows`。
仅读取应用文件与已经安装的参考模组，未读取 Salt/Steam 账号配置、缓存会话或浏览器凭据。

| 项目 | 观测值 |
| --- | --- |
| 系统／渠道 | Windows / Steam |
| jpackage 版本 | `1.19.3` |
| 主类 | `MainKt` |
| 应用 classpath | `app/ffmpeg-x64.dll` |
| classpath 实际格式 | ZIP/JAR，包含 42,919 个条目；不是仅供 JNI 使用的普通 DLL |
| 应用包 SHA-256 | `75eb9f7185acbbb2302fbcf3356d70f08958a410d2eaf32443abedddafa0e9a0` |
| 相关宿主 class 版本 | major 69，即 Java 25 |
| 宿主 Kotlin metadata | `2.4.0`；Compose 插件构建仍需进一步核对宿主实际编译器依赖 |
| 参考 WebDAV 包 | Steam 已安装 `com.saltplayermod.webdav` `0.2.2`，声明 `class-transform` |

名称伪装不影响读取 classpath。**不要把该 DLL 改名、替换或重新打包放回安装目录。**
所有分析中间文件放在本工作区忽略的 `.reference/`，构建工具放 `.tools/`。
仓库只保存接口事实、指纹与本项目代码，不再分发 Salt 或参考模组的 class 实现。

## 2. 原生媒体与队列

实测 class：`com.xuncorp.pisces.PiscesMediaItem`。

```text
constructor(String id, String title, String artist,
            String album, String albumArtist, String path)
```

字段顺序由宿主构造器与 getter 核对。离线测试创建真实宿主对象并读取六字段，确认逐项一致；
没有初始化播放服务或调用队列方法。

原生控制器：`com.xuncorp.voxzen.service.PlaybackController`，确认以下普通方法存在：

```text
setPlaybackQueue(List<PiscesMediaItem>, int)
setPlaybackQueue(PiscesMediaItem)
addToPlay(PiscesMediaItem)
addToPlayNext(AddPosition, PiscesMediaItem[])
refreshCurrentCover()
```

控制器是 Kotlin 单例，初始化会创建播放服务，因此被动检查使用 `Class.forName(..., false, loader)`，
**不读取 `INSTANCE` 或 `service`**。真正接入时需在用户明确启动播放、授权通过后调用。
队列入口存在不代表它能直接理解自定义 URI，仍必须配合音频流适配。

## 3. 音频流入口

宿主 BassStreamInterceptor 接口实际类名：`androidx.compose.ui.\u015b`。
以下 `\u` 是真实混淆名的 ASCII 表示，不是源码中的占位名称。

```text
createStream-_2kQ-ls(
  PlatformContext, PiscesMediaItem, long offset, long length,
  int flags, Continuation
) -> Object
```

这是 Kotlin suspend + nullable Result 的 ABI，**不能**当普通方法直接通过公开 Hook 注册。
可研究原生 stream interceptor 列表的注册／包装机制，委托原拦截器处理非本插件的媒体项，
同时保留 suspend 完成语义与 Result 包装，不能把整形 stream handle 直接塞到任意回调里。

已确认主播放流读取播放器 Properties 的列表，另有自动混音和 hash 分析器的列表：

- `com.xuncorp.pisces.PiscesPlayer$Properties` 中的 `List` 字段；
- 自动混音分析器 `PiscesMorvaniumAnalyzer`，实际类名 `androidx.compose.ui.\u0454`；它不是主播放流创建器；
- hash 分析器 `androidx.compose.ui.\u017a`。

它们存在私有 final 字段。已实现可逆列表覆盖层并用实例 final 字段、模拟槽位测试恢复与其他 Mod 共存；
仍需在真实引擎验证反射写入的可见性、运行时安装、重建和停用；
不能仅一次覆盖列表而丢掉 WebDAV 模组，不能用 `Unspecified` 流 owner 清理所有插件的流。
当前插件主类仍只验证结构，不写这些字段。适配器只有显式 `attach()` 才会写入，并逐步校验授权、失败回滚。

## 4. 原生 HTTP Range 的限制

确认宿主提供：

```text
HttpRangeBassStream.create-0E7RQCE(String url, int flags, HttpRangeStreamOwner)
    -> Kotlin Result 的 JVM Object
```

`HttpRangeSource` 提供随机读取及 Range 缓存，用于 BASS 的 file-user stream。
该工厂只有 URL 参数，没有本插件的 Referer／Cookie／请求头接口；宿主还会记录传入 URL。

因此不能直接承诺：把 B 站签名 URL 丢给它就能满足防盗链／凭据隔离并且不泄漏日志。
后续传输层推荐 **仅本机回环的私有按需 Range 入口**，上游请求由本插件附加所需合法请求头，
远端签名与凭据不进入 Salt 的 URL 日志；不是公共代理，也不是下载整曲再播放。
传输实现已落地，并完成实际 `HttpRangeSource` 到真实本机 Socket／模拟 CDN 的 Range 联调；
**尚未打开 BASS stream 或请求真实平台 CDN**。详见 [播放底层进度](PLAYBACK_PROGRESS.md)。
实现使用 Socket／HttpsURLConnection 等 `java.base` 接口，不依赖宿主裁剪掉的 `java.net.http` 或 `jdk.httpserver`。

传输层验收至少包括：绑定回环随机端口、不可猜测的会话访问标识、限定方法／路径、
固定平台域及重定向校验、Range/206/416/HEAD、响应大小与内容范围检查、断连取消、
账号切换和流租约过期、只清理本插件连接。不能提供任意 URL 转发接口。
原生 HTTP Range 的内置缓存政策也需检查：它并非“完全零音频缓存”，但不等于完整预下载。

## 5. 封面链路

播放页 `PlaybackService.updateImageBitmap` 调用：

```text
TagParser.readImage-0E7RQCE(String path, int width, int height)
    -> Kotlin Result<ImageBitmap> 的 JVM Object
```

列表封面至少还调用：

```text
TagParser.readImageToByteArray(Vri) -> byte[]
TagParser.readImageToByteArrayOrThrow$Voxzen_composeApp(Vri) -> byte[]
```

上述 TagParser 入口是普通方法，可作为受授权、指纹限制且只匹配本插件媒体路径的 Hook 候选。
原生 `readImage` 会检查本地文件并读取内嵌封面；所以虚拟在线路径不会自动显示网上图片。

正确实现需要分别给播放页与列表提供缓存的图片字节／ImageBitmap，匹配实际 Kotlin Result ABI，
保留宿主的解码尺寸、引用计数和换曲门禁，并在更新后使用原生刷新入口。
不能只让插件列表有图就说 Salt 播放页封面完成；不能只替换 `path` 为 JPG 就假设内嵌图解析器可读。
当前没有安装这些 Hook，亦没有改动用户本地音频标签。

## 6. 已完成验证

- 提取并固定 17 个关键类 SHA-256 与 JVM 方法描述符，见 `profiles/windows-steam-1.19.3.json`。
- 运行时被动检查器核对这 17 个资源指纹及所需方法签名，不初始化控制器。
- 使用 Java 25 编译本项目插件，对真实宿主 `PiscesMediaItem` 做六字段创建／读取往返测试。
- 不同版本、不同渠道、变更 class 指纹均被拒绝。
- 70 项独立核心契约检查继续通过；class-file 检查脚本 6 组单元测试通过。
- 本地 `.spmod` 包包含本项目类、配置、指纹及许可，后续增加唯一MIT Nayuki二维码编码器，不含 Salt/API/Kotlin/Compose 或测试类。
- 新增父类 `androidx.compose.ui.\u0aae` 和抽象 `PiscesPlayer` 的指纹，核对 final `getPlayer()`、播放器字段及普通 `onPlayerSet`；当前总计 17 类。
- 后续新增 123 项传输、46 项拦截器、25 项播放会话检查；实际宿主 HTTP reader 验证探测、跳读、缓存、EOF、租约撤销。
- 传输检查在仅允许 `java.base` 的 JVM 上重复通过；这不是在 Salt 原生运行时启动插件的证明。

默认 `.spmod` 是 **被动宿主检查预览**；另有用户显式启用的实验接线／本机测试音包。
两者均未安装到播放器，未验证 PF4J 在真实运行中加载，
未打开原生网络 stream，未播放 B 站／网易云歌曲；这些不能由离线检查结果代替。

## 7. 本地构建和下一阶段

新增 `scripts/build-local.ps1`，以用户本机合法取得的宿主包为只读编译依赖，
可在 Gradle/JitPack 证书链问题尚未解决时完成被动检查包的编译、测试和打包。
该脚本不把宿主包收入产物，不修改安装目录，不自动给用户安装或启用 Mod。

输出：`plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.1.0-hostprobe.spmod`。
加`-Experimental`当前输出`0.7.6-flow-cover.spmod`：Salt内侧栏/二维码/列表封面/双击直接播放，无页面适配/确认控件；截图确认B站视频列表与音轨准备，提交后实播未验收。授权启用接侧栏与页面、自动校验本插件保存；加载列表后可见封面联网，双击曲目才解析、内部接流并提交。原生播放栏封面通过临时标签文件接入，用法及side effects见[实验包说明](EXPERIMENTAL_PLAYBACK.md)。

后续顺序：将已实现的 stream adapter + 私有本机 Range 传输接到真实引擎生命周期，
用固定授权测试媒体联调原生播放／拖动／暂停／切歌／停用；再接 Neri B 站与网易云授权／取流，
最后接齐词图来源、格式适配和原生入口。
未知版本仍使用不可用桥接，不能把此 profile 自动用于其他安装包。

## 8. 原生侧栏和页面

独立UI profile现为20个侧栏/主题/实际内容类指纹、127个生成的宿主方法/字段引用；0.7.0/0.7.1侧栏有用户截图，但平台页分派失败。
0.7.2通过实际页面singleton持有的ComposableLambdaImpl.update可逆分派两个精确平台键，不再依赖普通导航方法Hook，不替换带默认ViewModel参数的宿主调用。
47项实际缓存NavEntry/内容对象调用契约、12项可逆绑定检查和33项真实离屏Compose/Skia绘制检查通过，另保留6项旧方法变换夹具审计。其他模组委托原函数体，原内容键/metadata不变。
离屏绘制发现并修正Compose多参数容器强转与打包颜色误转透明问题；测试使用人工账号/二维码，没有初始化真实BASS、访问平台或打开窗口/浏览器。不把离屏绘制等同于运行中Salt的安装更新验收。
详情见 [原生UI进度](NATIVE_UI_PROGRESS.md)；新界面仍须在运行中的Salt手动验收。

## 0.7.6 播放命令与底部封面

`PlaybackService.addToPlay`和`play`进入同一个FIFO；现在明确提交两条命令，非playOrPause切换。TagParser的readImage只读取本地文件，metadata-only FLAC的PICTURE接入既有封面流程，真实音频路径仍被精确拦截映射到Range。实际宿主提取/解码验证与文件生命周期见[0.7.6说明](FLOW_POLISH_0.7.6.md)。
