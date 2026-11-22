# 打字间

**一个能在当前输入框旁查资料的 Android 输入法。**

普通打字走 Fcitx5 和 libime，不依赖 AI 或网络。需要查资料时，在键盘内打开 AI 面板，向自己的资料库提问。回答可以附引用，也可以直接插入当前输入框、替换选区，或长按选择文字复制。

## 开始使用

1. 安装 APK，在系统设置中启用并切换到「打字间」。
2. 在 App 的 Agent 设置里添加回答模型，填写自己的 API 配置。
3. 要查私有文档，先在智谱、Dify 或 RAGFlow 后台管理文档，再在 App 里选中已建立的资料库。
4. 回到输入框，打开键盘工具栏的 AI 面板，输入问题并发送。

联网搜索需配置 Tavily；MCP 工具是可选项，只连接手动启用的服务。查资料和搜索可以在同一会话使用。应用不会自行创建资料库、上传文档或切换到本地检索。

## 数据与隐私

普通输入不上传，密码字段会停用 AI 面板。主动发起问答时，问题和命中的资料片段可能发给你配置的回答服务；使用外部工具时，调用参数可能发给对应服务。配置导出文件包含 API Key，不要公开分享。详见[隐私说明](docs/privacy.md)。

## 本地构建

在 Windows 上准备 JDK 17、Android SDK 37、NDK `28.0.13004108`、CMake `3.31.6` 和 GNU Gettext（`msgfmt`、`msgmerge`）。设置 `JAVA_HOME`、`PATH` 和 `ECM_DIR`（指向含 `ECMConfig.cmake` 的目录）。用 Android Studio 配置 SDK，或在 `android/local.properties` 指定本机 `sdk.dir`。

```powershell
git clone https://github.com/silent-ram/agent-keyboard.git
cd agent-keyboard
git submodule update --init --recursive
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest -PbuildABI=arm64-v8a --no-daemon --console=plain
```

Debug APK 位于 `android/app/build/outputs/apk/debug/`，包名 `com.agentkeyboard.debug`。应用最低支持 Android 8（API 26）。这里是源码构建命令，不是正式签名发布包。

## 项目资料

- [架构](docs/architecture.md) · [测试与验收](docs/testing.md) · [更新记录](CHANGELOG.md)
- [文档索引](docs/README.md) · [第三方来源与许可](docs/reference/third-party-notices.md) · [提交问题](https://github.com/silent-ram/agent-keyboard/issues)

由 **silent-ram** 维护。Android 应用源码在 `android/`，输入法基座锁定在 `third_party/fcitx5-android/` 子模块。
