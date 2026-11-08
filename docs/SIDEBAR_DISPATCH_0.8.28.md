# 0.8.28 侧栏启用无效果修复

> 这是本机过渡验证版的分发修复记录，仍存在左侧容器裁切与图标对齐问题。请使用 [0.8.29 布局修复](SIDEBAR_LAYOUT_0.8.29.md) 或更新版本。

## 根因

0.8.27 的配置监听和 hook 注册本身正常，但原生 SidebarCaptionButton 调用 caption 组件时默认参数 mask 为 880。安装版 Salt 的 UI 转换器在 mask 非零时保留原组件、跳过 `RenderReplacement`。因此注册 `replaceComposable` 虽成功，增强内容根本没有执行。

原来的离屏测试直接调用 `SaltSidebarContent.Content`，验证了组件内逻辑，却没有经过宿主转换器的 mask 判断。这是测试覆盖遗漏，不是用户设置问题。

## 修复

- 改为官方 `afterComposable`，在同一 caption 调用之后追加图标区域；这个分支不受默认参数 mask 限制。
- 不再模拟或重复调用原按钮，点击、悬停、默认参数全部由宿主处理。追加图标在原有 Box 内根据原生 caption 宽度留出左侧空间，排列在切换按钮右侧。
- 开关关闭、侧栏展开或权限撤销时只移除追加内容，不更改原生展开设置。
- 保留原生导航图标、在线平台、本地歌单、选中态和窄窗口横向滚动。
- 诊断的 `sidebar` 字段增加 `rendered` 次数和当前图标区域 `width` 像素数，区分“注册成功”和“实际参与布局”。隐藏增强区域时宽度归零。

## 回归

`SidebarHookTransformFixture` 使用真实 Salt resolver 和 `WorkshopUiHookTransformer` 转换安装版 SidebarCaptionButton 的字节码，再通过隔离的注册桥执行实际按钮。原生 mask=880 与原始参数均保留，测试明确断言 replacement 从未执行、after 确实执行。

`SidebarOptimizationRenderTest` 改为经此路径渲染，而非直接调用增强组件。验证原生点击收起／展开、配置运行时切换、每个导航入口与选中态、两个在线平台、本地歌单更新、1280／520 宽度下位置及溢出边界、权限撤销，以及真实测量宽度。数据源采用合成 Flow，不加载用户库、不执行真实播放。

升级保持 `ui.sidebarOptimization` 设置不变，无需重新配置。
