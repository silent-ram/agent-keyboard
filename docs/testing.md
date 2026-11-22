# 测试与验收

环境与子模块初始化见[本地构建](../README.md#本地构建)。在 Windows 的 `android/` 运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest -PbuildABI=arm64-v8a --no-daemon --console=plain
.\gradlew.bat :app:assembleDebugAndroidTest -PbuildABI=arm64-v8a --no-daemon --console=plain
```

单独运行 JVM 测试时可加 `-x :app:generateDataDescriptor`，不跳过任何用例。构建仪器测试 APK 不等于已在设备运行测试。

## 回归范围

| 场景 | 检查点 |
| --- | --- |
| 输入法 | 拼音、候选、退格、回车；AI 未配置、断网或失败时仍可打字 |
| AI 面板 | 面板草稿与宿主输入框隔离；光标、换行、取消、历史恢复与结果插入 |
| 检索与工具 | 资料库逐次授权；联网搜索和已启用的 MCP 沿用各自授权；拒绝后没有相应请求 |
| 升级 | Room 和 DataStore 迁移、旧本地检索数据清理、会话与配置保留 |
| 隐私 | 密码框停用 AI；请求参数和 UI 不泄露已知敏感配置；活动日志不保存私有载荷 |

## 真机验收

1. 启用「打字间」，在普通输入框切换布局、选词、退格、提交。
2. 在当前输入框打开 AI 面板，核对键盘输入只编辑面板草稿。确认多行输入、停止及关闭后恢复打字。
3. 用非私有测试资料核对检索、引用和联网授权；在同一会话追问，验证已授权工具仍可使用。
4. 验证插入、选区替换和历史会话。在编辑器切换及密码框中，旧结果不能写入。
5. 用断网、无效凭据和拒绝授权分别验证错误反馈。发布前还需检查 Manifest 权限、APK 内的许可声明及真机升级。

截图不作为验收结论。提交问题时只附脱敏后的复现步骤，不上传 API Key、配置导出、私有资料或真实会话。
