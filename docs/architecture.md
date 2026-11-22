# Agent Keyboard MVP 架构

版本：1.2
日期：2026-11-22
状态：当前架构事实源

本文描述当前生产路径：系统输入法、键盘内 AI 面板、远程资料库、模型配置、已启用的外部工具及结果交付。旧文档或旧代码不作为当前产品范围依据。

## 1. 总体边界

Agent Keyboard 是一个 Android 应用，系统输入法是入口，AI 面板是统一交互界面，用户选定的远程资料库是私有资料来源。普通打字和 AI 任务必须隔离：

```text
宿主输入框
    │ InputConnection
    ▼
FcitxInputMethodService
    ├── KeyboardView / 拼音与候选
    ├── PanelView / AI 对话面板
    ├── ImeContextProvider / 当前编辑器上下文
    └── 结果交付：插入、替换、复制

AI 面板
    │ 用户主动发送
    ▼
AgentEngine / AgentRuntime
    ├── 已选资料库的检索 API
    ├── 用户配置的 LLM
    └── 引用、状态、错误和交付结果

服务端资料管理 → 服务端解析、切片和索引
```

### 1.1 进程和入口

| 边界 | 现有入口 | 责任 |
|---|---|---|
| 应用启动 | `AgentKeyboardApp`、`MainActivity` | 设置、模型配置、知识库管理和引导 |
| 系统输入法 | Fcitx `FcitxInputMethodService` | Manifest 注册的生产 IME 服务，承载键盘和输入连接 |
| 键盘 UI | Fcitx `InputView`、`KawaiiBarComponent`、`CandidateUi` | 键盘布局、候选、工具行和 AI 入口 |
| AI 面板宿主 | `FcitxAgentPanelController`、`PanelView`、`PanelUiState` | 在当前输入窗口复用问答会话、引用、确认、页内模型管理和结果动作 |
| 面板输入隔离 | `PanelInputBuffer`、`ImeTextInputRouter` | Fcitx 预编辑、候选提交和退格只写面板草稿，不写宿主输入框 |
| 编辑器上下文 | `ImeContextProvider`、`EditorSessionTracker`、`KnowledgeInsertGuard` | 密码框判定、编辑器代次和选区快照校验 |
| Agent | `AgentEngine`、`AgentRuntime`、`AgentFactory` | Turn/Step 循环、会话持久化、工具调用和终态事件 |
| 持久化 | `AgentDatabase`、`SettingsRepository`、`ModelStore` | 会话、消息、权限日志、模型配置 |

`AgentKeyboardService` 仍留在代码中，但没有注册为系统输入法服务；生产键盘和面板只走 Manifest 注册的 Fcitx 服务。不要把旧服务当成第二个生产入口。

## 2. 模块职责

### 2.1 输入法模块

输入法模块只负责“可靠打字”和“打开 AI 面板”，不负责直接执行网络任务。

- Fcitx `InputView`：生产键盘布局；`KawaiiBarComponent` 在空闲栏和候选栏提供 AI 入口。
- 拼音引擎：系统输入走 Fcitx/libime；面板打开时键盘候选提交路由到面板草稿。
- `FcitxAgentPanelController`：复用既有 `AgentFactory`、知识问答任务和 `PanelView`，不另建会话/RAG 链路。
- `PanelInputBuffer` / `ImeTextInputRouter`：按 `HOST` / `ASSISTANT` 单一目标路由，不双写宿主与面板。
- `ImeContextProvider`：仅在允许的编辑器上下文中读取选中文本或有限上文。
- `EditorSessionTracker` / `KnowledgeInsertGuard`：问答结果绑定编辑器代次和请求时选区；密码/敏感输入拒绝打开、发送和交付。
- `ReplaceExecutor`、`QuickActionRunner`：处理已有替换型快捷动作；知识问答插入由面板控制器在守卫校验后提交。

输入法失败时，AI 面板可以不可用，但普通按键、退格、光标和候选提交不能被拖垮。

### 2.2 AI 面板模块

面板是所有 AI 任务的唯一用户入口，不按“改写、翻译、帮写”拆产品页。用户通过自然语言描述任务，结果通过统一动作交付。

面板至少包含：

1. 当前任务和模型/知识库范围；
2. 用户消息和模型流式回答；
3. 检索来源和引用定位；
4. 运行中、等待确认、成功、失败、取消状态；
5. 插入当前光标、替换选区、选中文字复制、继续追问。

面板输入使用独立状态。Compose 输入框维护 `TextFieldValue` 的光标和选区，并禁用平台 `TextInputService`，避免在输入法窗口内再启动一条系统输入会话；实际拼音预编辑、候选提交、选区替换和退格统一由 Fcitx 事件写入 `PanelInputBuffer`，不得发送到宿主输入框。普通关闭保留草稿以便继续；编辑器切换和敏感态清除草稿；异步预检完成后会再次核验面板可见性、编辑器代次和敏感态，避免过期请求启动。

**面板失焦（2026-11-22 交互改版）**：点击展开的扩展面板内容区时，面板输入框立即 `clearFocus()` 隐藏光标。实现必须**只观察不消费**指针事件（`awaitPointerEvent(PointerEventPass.Final)` + `changedToUp()`）——`detectTapGestures` 会消费 down 事件，导致回答正文的 `SelectionContainer` 无法长按划选，与"可直接选择复制"的需求直接冲突。

**回答正文**：面板用 CommonMark 识别加粗，显示为粗体，插入时交付去掉加粗标记的纯文本。面板与 App 均可长按正文划选复制。面板历史回放仅可查看或划选复制，App 对话仍提供全文复制。检索未完成但有实际回答时，面板保留原编辑器与选区快照和未核验状态，用户仍可在校验通过后主动插入。

### 2.3 Agent 编排模块

`AgentEngine` / `AgentRuntime` 负责：

- 创建会话和消息；
- 按 `TaskRequest` 白名单提供工具，并将工具结果回填模型上下文；
- 调用用户配置的 LLM；
- 处理流式增量、最多 4 次模型请求、停止、超时和错误；
- 以 `Finished`、`Incomplete`、`Failed`、`Stopped` 区分终态；
- 汇总真实工具来源引用，返回面板展示和安全交付。

`AgentRuntime` 不是独立 JSON 计划生成器或后台任务系统。工具权限、出网确认和活动日志继续由 `AgentToolRegistry` 与 `PermissionGate` 执行；键盘面板不默认读取宿主内容或剪贴板。

**终态保证**：`run()` 必须以恰好一个终态事件（`Finished`/`Incomplete`/`Failed`/`Stopped`）收尾。构造 `TaskRequest`（白名单快照）阶段的任何异常都被捕获并转成 `Failed(AgentError("INVALID_TASK", …))`——否则异常会逃出协程，UI 永久停在 busy 态且用户无法得知失败原因。

MCP 工具同步由用户发起的 Agent 任务或设置操作触发。打开 App 或装配 Agent 不发起 MCP 握手。

会话消息按数据库插入顺序恢复，工具步骤号只用于单轮 Agent 编排，不能作为跨轮对话排序键；继续旧会话会更新时间并移至历史列表前部。新会话创建独立上下文，删除会话级联删除其消息。

MVP 允许的工具边界：

| 工具 | MVP 状态 | 说明 |
|---|---|---|
| 资料库搜索 | 必须 | 每次检索单独授权；命中片段交给当前回答模型 |
| 读取当前编辑器上下文 | 受守卫控制 | 仅用户触发，密码框禁用 |
| 结果交付 | 必须 | 统一经过编辑器会话校验 |
| 用户配置的 LLM | 必须 | 首次发送和模型服务地址变更时确认，确认界面使用通用服务类别 |
| 网页搜索 | 支持 | Tavily；逐次权限门控，Key 加密保存并记录活动日志 |
| 网页抓取 | 不开放 | 不加入当前 Agent 工具集 |
| MCP 远程工具 | 支持(G12) | 仅用户显式配置的远程 server；工具逐次权限门控，见下节 |
| 设备自动化、读屏 | 不进入 MVP | 另立需求和权限评审 |

**工具结果契约(G2)**：`ToolResult` 携带稳定错误码（`ToolErrorCodes`：AUTH/RATE_LIMIT/TIMEOUT/NETWORK/EMPTY/BAD_RESPONSE/BAD_PARAMS/NOT_CONFIGURED/USER_DENIED/DISABLED/REMOTE_ERROR/INTERNAL）与 `retryable` 标记；错误回喂模型时为分隔符包裹的结构化 JSON（`{"error":{code,retryable,message}}`），展示文案与协议语义分离。错误码只追加不改值。

**MCP 工具桥(G12，包 `com.agentkeyboard.mcp`)**：

- `McpServerStore`：DataStore 保存用户显式添加的远程 server（名称/URL/启用开关）；Authorization 经 `SecureStore` AES-256-GCM 加密，策略同模型 Key；无任何默认 server，未配置零开销；
- `McpClient`：JSON-RPC 2.0 over Streamable HTTP（POST 单端点），兼容 JSON 与 SSE 响应，透传 `Mcp-Session-Id`；每次 list/call 独立握手，不维护常驻 session；不支持本地 stdio server，不做后台发现；
- `McpTool` 将服务端工具映射为 `Tool`。模型看到的是以 `mcp_` 开头的指纹标识，原始服务名称、地址和工具名用于本地映射。工具描述和参数定义通过 Runtime 检查后才发给模型，结果同样通过内容检查。
- `McpToolBridge`：同步工具进 `AgentToolRegistry.replaceByPrefix`；时机为每次任务开始前（`AgentRuntime.beforeRun`，TTL 60s）与设置页变更后（force）；`tools/list` 属能力探测不弹授权，`tools/call` 每次走既有权限门控，不一定弹确认；
  - **停用/删除 server 必须从注册表移除其工具**：桥接层记录已注册前缀集合 `registeredPrefixes`，同步后对不再生效的前缀调用 `unregisterByPrefix`——否则用户停用的 server 工具会永久留在注册表，LLM 仍可调用；
  - **配置变更指纹绕过 TTL**：`fingerprint`（server id/url/name 集合）变化时立即重同步，用户新增/改名/启停 server 不用等 60s；
  - **同步失败 ≠ 已下线**：单 server 同步失败保留其上次注册的工具并在设置页展示错误，只对确实不再生效的 server 做清理；
- 设置入口：App 设置页「工具扩展 · MCP」区块（`McpSettingsUi`），提供添加/删除/启用开关与同步状态行。

不引入子 Agent 委派体系。多步任务使用有上限的 Turn/Step 循环与结构化工具结果。

### 2.3.1 App 外壳与配置界面（2026-11-22 交互改版）

- **默认入口 = Agent 配置页**（`AgentConfigUi.AgentConfigScreen`）：模型与连接 / MCP / Skills 能力清单（数据来自 `AgentToolRegistry.schemas()`，诚实展示）/ Memory 显式记忆 / user.md 用户自述；模型管理从配置页进入、返回回到配置页（`manageReturnPage`）。
- **设置页（输入法设置）**只保留输入法相关配置（键盘高度等）与全局最近活动；模型与 MCP 配置不属于该页。
- **对话管理（原"对话问答"tab）**：两级结构——会话列表（新建/进入/每项独立活动记录入口）↔ 具体对话视图；活动记录经 `ActivityLogDao.bySession` 按会话过滤（`RecentActivityActivity.EXTRA_SESSION_ID`），每条活动日志本就携带 `session_id`。
- **G5 用户画像**：`agent_memories` 表（Room v7，`MIGRATION_6_7`）存显式记忆，完全显式、完全可删；user.md 存 DataStore；两者经 `AgentProfile` + `AgentRuntime.profileProvider` 在任务开始时注入 `SystemPrompts`（仅工具任务，不注入单轮快捷动作；空画像不产出文本）。
  - **按需带入**：用户说明最多 4000 字符。记忆在本机按当前提问做词面匹配，最多带入四条，单条最多 500 字符。记忆只辅助理解，不代替知识库原文。发送给模型前须完成对应的首次明示，搜索等工具参数命中说明或记忆原文仍须当场确认。
  - **面板内管理**：模型菜单 →「记忆与用户说明」。记忆可增删改，用户说明可编辑或清空。输入「记住：内容」直接在本地保存，不调用模型。
  - **Agent 仅可提议**：键盘面板问答首步可提交一条待确认建议。只接受本轮用户消息原句；调用参数不写会话历史、不回传下一步模型，也不交工具注册表执行。完整回答后出现行内保存或忽略；不确认就不写入。用户说明只能由用户编辑或明确确认追加。
- **对话列表的取值口径**：只列真实对话类会话（`task_type IN ('KNOWLEDGE_ASK','ASK','VERIFY')`），排除输入法单轮快捷动作会话；标题取首条用户提问，状态/时间作副标题；数据库读取在协程内完成，不得阻塞主线程。
- **会话草稿清理**：新建对话或切换到历史会话时必须清空输入框草稿，避免上一段内容被误发到新会话。

### 2.4 知识库模块

当前生产知识库链路：

```text
用户在资料库服务端维护的文档
    ↓
服务端解析、切片和索引
    ↓
HostedKnowledge → KnowledgeRetriever
    ↓
命中片段 → 用户选择的回答模型 → 引用
```

职责：

- `HostedKnowledgeSection` 提供智谱、Dify 和 RAGFlow 三种连接，选择列表用编号和文档数区分，名称和 ID 不进入界面。选库成功后以一次事务更新服务、库和加密凭据。
- `HostedKnowledge` 使用各服务的列表和检索接口。Dify 与 RAGFlow 要求各自的 HTTPS API 地址，应用只提供选择与检索。网络传输不跟随重定向。
- `KbSearchTool` 每次检索前通过 `PermissionGate` 授权，活动日志记录类别和结果，不保存地址、查询和资料库 ID。
- `AgentRuntime`：只把用户主动触发检索返回的命中片段交给当前选定的回答模型。

智谱沿用混合召回，Dify 使用语义检索，RAGFlow 使用其检索接口。客户端不指定自定义重排模型。

本地知识库实现已退役，不保留可切换的本地检索路径。数据库升级 `MIGRATION_5_6` 删除旧文档原文、切片、FTS5 和 vec0 表；迁移启用 `secure_delete`，一次性启动清理截断 WAL 并压缩数据库，再移除旧 Embedding 配置、下载模型与 vec0 扩展副本。此迁移不上传旧文档，不删除会话、模型凭据、授权状态或活动日志。智谱 API 失败直接报告错误，不切换检索后端。

检索只向当前选定的资料库发送查询。任一资料库请求失败即报错。资料检索与公网搜索或已启用的 MCP 可在同一轮和后续追问中使用。是否命中资料不改变 `PermissionGate` 的既有授权，不额外逐次确认，也不按资料原文匹配拦截外部参数。提示词要求模型默认使用公开搜索词，仅在用户明确要求时才将必要的资料内容交给外部工具；这不是本地强制校验。凭据、配置值和疑似提示注入检查保持不变，活动日志不保存工具参数。

### 2.5 配置与安全模块

- `SettingsRepository` / `ModelStore`：保存非敏感配置和模型列表；
- `SecureStore`：保存 API Key 等凭据；
- `PermissionGate`：控制首次明示、逐次授权和派生边界。模型授权记录使用地址指纹，换地址重新确认；
- `AgentContentSafety`：模型调用前检查当前输入和带入的历史，工具执行前检查参数，工具返回后隔离疑似注入的资料。Agent 在完整回答通过检查后才显示、保存及允许插入。已知配置值从回答和历史显示中遮盖。
- `LogSanitizer`：日志脱敏；
- `ActivityLogDao`：记录出网、工具、结果交付和失败原因。

Agent 配置页的「配置迁移」通过系统文件选择器读写 `.akcfg` 配置包，包含模型、资料库、搜索配置和对应凭据。格式版本 3 使用共享的公开格式参数、AES-256-GCM 和每个文件独立的随机 nonce。旧配置包缺省的资料库服务为智谱。持有文件的人可以恢复和使用其中的凭据，产品在导出前明确提示。

`ModelStore` 将迁移范围内的配置和凭据存入同一个 DataStore。升级时原样迁移旧存储中的设备密文。导入先完成认证、数据校验和目标设备密钥加密，再通过一次事务替换连接配置。读取生效模型和知识库连接时，分别从同一快照取得地址或库 ID 与凭据。

安全默认值：

1. 普通键盘输入不上网；
2. 不默认读取宿主上文；
3. 密码框不读取、不发送、不写日志；
4. LLM 请求必须由用户明确发送触发；
5. App 不上传文档；知识库列表仅供选择，检索走工具授权并逐次记录；
6. API Key 不进入源码、测试数据、普通日志或 Room 明文列。

### 2.6 持久化模块

Room 负责可查询业务数据：

- 会话和消息；
- 权限状态；
- 活动日志；
- 任务历史摘要。

DataStore 负责轻量配置：

- 当前模型；
- provider/base URL；
- 远程资料库选择；
- UI 和键盘偏好；
- 用户主动开启的功能开关。

持久化写路径必须可在真实 SQLite 上测试，不能只依赖 Robolectric shadow 或伪 DAO。

## 3. 核心数据流

### 3.1 普通打字

```text
按键 → Fcitx/libime → 候选栏 → 用户选词 → InputConnection.commitText
```

该路径不创建网络任务，不触发知识库检索，不等待模型初始化。

### 3.2 知识库问答

```text
打开 AI 面板
  → 用户输入问题
  → 用户点击发送
  → 选择知识库范围
  → 已选资料库的检索 API
  → 将必要片段发送给用户配置的 LLM
  → 展示回答和引用
  → 用户选择交付动作
```

### 3.3 结果交付

每张可交付结果保存一个编辑器会话快照：

- 编辑器代次（由包名、fieldId、inputType 和 privateImeOptions 跟踪）；
- 请求时选区起止；
- 结果卡本身的快照，不借用后续请求的可变状态。

交付时重新校验快照和密码/敏感态。编辑器切换或选区变化时拒绝上屏并保留结果卡；`InputConnection.commitText` 未确认成功时不显示“已插入”。

## 4. 状态和错误

Agent 会话持久化状态：

`RUNNING / DONE / INCOMPLETE / FAILED / STOPPED`

面板还会呈现模型请求中的流式状态和出网工具的等待确认状态；确认挂起由 `PermissionGate` 当前进程内暂停协程，不作为可恢复任务状态。

错误至少区分：

- 未配置模型；
- 凭据无效；
- 网络/超时/限流；
- 资料库未配置、无命中或服务错误；
- Tavily 未配置、无结果或服务错误；
- 编辑器上下文失效；
- 用户拒绝出网或交付。

错误消息应说明下一步，不得用“失败”覆盖真实原因。

## 5. 不在 MVP 的架构

以下能力暂不设计接口、不新增权限、不进入主流程：

- 悬浮窗任务面板；
- 无障碍读屏和设备自动化；
- 通知读取；
- 内置 MCP 服务器和本地 stdio MCP；
- 图片理解、语音输入；
- 后台通用 Agent 调度平台；
- 默认读取或上传全设备输入。

如未来立项，必须先补充独立的权限、数据流、停止机制、审计和真机验收设计。

## 6. 变更规则

1. 新增网络出口必须更新数据流、权限说明、日志和测试。
2. 新增 Android 权限必须有拒绝和撤销路径。
3. 输入法主流程和 AI 任务必须保持可独立失败。
4. 新的存储格式必须有迁移和真实库测试。
5. 公开文档描述当前行为。原始开发记录保留在工作区，不纳入版本控制。
