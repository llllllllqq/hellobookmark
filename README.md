# HelloBookmark 网页书签启动器

仿照 AOSP 原生 Launcher 样式的网页启动器兼书签 Android 应用。

- 包名：`moe.hellobookmark`
- 应用自身不联网，搜索与书签均通过系统默认浏览器打开网址
- 上半部分：搜索框（纯打字、无联想），左侧按钮在**百度 / 谷歌**之间切换（默认百度）
- 下半部分：5×5 图标网格，图标为书签名称**首字符大字 + 透明背景**，下方显示名称
  - 点击图标 → 用系统浏览器打开
  - **长按图标** → 编辑名称/网址，或删除
  - 未使用空位隐藏，末尾自动出现 **+** 新增按钮，满 25 个后隐藏
- 跟随系统深浅色模式切换背景

## 构建

GitHub Actions 云编译（`.github/workflows/build-release.yml`）：push 到 `main` 后自动
在云端安装 Gradle 8.10.2 执行 `assembleRelease`，并发布到 GitHub Release（`v{versionName}`），
APK 名为 `hellobookmark-{versionName}-release.apk`。

本地无需安装 Gradle / Android SDK，纯云端编译。签名：CI 从仓库 secrets 恢复签名密钥；
未配置 secrets 时回退到 debug 签名。

## 版本历史

| 版本 | 说明 |
|------|------|
| 1.2  | 安全加固（1.1 基础上通过 lint）：URL scheme 显式白名单（阻断 intent:///javascript:/file: 等）、大写 scheme 兼容、搜索图标可点击、双击防抖、输入长度限制、旋转/深色切换恢复搜索内容、对话框防泄漏、备份规则限定仅书签数据、windowLightNavigationBar 移至 API 27+ 限定（兼容 Android 8.0）、CI 无签名密钥时拒绝发布、CI 增加 lint |
| 1.0  | 首个版本：搜索 + 引擎切换 + 5×5 书签网格 + 深色模式 |

## 签名密钥

签名密钥文件 `hellobookmark-release.jks` 与口令保存在本机（已被 .gitignore 排除），
同时以 secrets（`ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD` /
`ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`）存入仓库供 CI 使用。
