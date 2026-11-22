package com.agentkeyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentkeyboard.data.model.FcAuto
import com.agentkeyboard.data.model.FetchModelsResult
import com.agentkeyboard.data.model.ModelConfigLogic
import com.agentkeyboard.data.model.ModelEntry
import com.agentkeyboard.data.model.ModelService
import com.agentkeyboard.data.model.ModelStore
import com.agentkeyboard.data.model.ProviderConfig
import com.agentkeyboard.data.model.ProviderErrorCodes
import com.agentkeyboard.data.model.ProviderPreset
import com.agentkeyboard.data.model.ProviderStatus
import kotlinx.coroutines.launch

fun interface ImePanelTextFieldBinder {
    fun bind(initial: String, onChanged: (String) -> Unit)
}

private fun Modifier.panelImeInput(
    binder: ImePanelTextFieldBinder?,
    value: String,
    onChanged: (String) -> Unit,
): Modifier = if (binder == null) this else onFocusChanged { focus ->
    val listener: (String) -> Unit = if (focus.isFocused) onChanged else { _ -> }
    binder.bind(if (focus.isFocused) value else "", listener)
}

// PrimaryBlue/BodyText/MutedText/SectionText/ErrorText/SystemBg/OkGreen 共用 MainActivity.kt 的 internal 声明(M2-T4)
// M3-C audit#7:颜色走 AppTheme(light/dark 跟随系统)
private val ErrorBg @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.errorBg
private val LineGray @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.lineGray
private val SurfaceBg @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.surface
private val SystemBgM @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.systemBg
private val ChipBgM @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.chipBg
private val CardBorderM @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.cardBorder
private val OnPrimaryM @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.onPrimary

/** 子页顶栏:返回键(48dp 热区 IconButton,规避小热区 tap 丢失) */
@Composable
private fun BackTopBar(title: String, onBack: () -> Unit) {
    com.agentkeyboard.ui.AssistantPageBar(
        title = title,
        backLabel = androidx.compose.ui.res.stringResource(org.fcitx.fcitx5.android.R.string.qa_back),
        onBack = onBack,
    )
}

// ==================== P3 设置首页模型区块 ====================

/** P3(10 号设计 §5):当前模型行 + 模型管理行(副标题 N 服务商 · M 模型) */
@Composable
fun ModelSection(store: ModelStore, onOpenManage: () -> Unit) {
    var currentLabel by remember { mutableStateOf("未设置") }
    var summary by remember { mutableStateOf("0 个服务商 · 0 个模型") }
    var configError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        configError = store.configError()
        val file = store.load()
        val model = ModelConfigLogic.resolveDefaultModel(file)
        currentLabel = model?.display() ?: "未设置"
        summary = "${file.providers.size} 个服务商 · ${file.models.size} 个模型"
    }

    Column {
        Text("模型", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SectionText)
        // P2(评审修正单):配置 JSON 解析失败已重置 → 显式提示重新配置(不静默)
        if (configError != null) {
            Text(
                "模型配置文件损坏,已重置为空;请重新添加服务商与密钥(坏数据已备份到应用私有存储)",
                fontSize = 12.sp, color = ErrorText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, LineGray, RoundedCornerShape(10.dp)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenManage).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("当前模型", fontSize = 14.sp, color = BodyText)
                    Text(currentLabel, fontSize = 12.sp, color = MutedText)
                }
                Text("›", fontSize = 18.sp, color = MutedText)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(LineGray))
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenManage).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("模型管理", fontSize = 14.sp, color = BodyText)
                    Text(summary, fontSize = 12.sp, color = MutedText)
                }
                Text("›", fontSize = 18.sp, color = MutedText)
            }
        }
    }
}

// ==================== 徽章 / 状态点辅助 ====================

fun contextWindowLabel(model: ModelEntry): String? {
    if (model.contextWindow <= 0) return null
    return "${model.contextWindow / 1024}K"
}

fun errorCodeLabel(code: String): String = when (code) {
    ProviderErrorCodes.AUTH -> "密钥无效,点击更新"
    ProviderErrorCodes.NETWORK -> "无法连接,检查网络或网址"
    ProviderErrorCodes.PATH_404 -> "接口路径不存在,BaseURL 通常以 /v1 结尾"
    ProviderErrorCodes.MODELS_EMPTY -> "服务商没有返回模型列表"
    ProviderErrorCodes.URL_INVALID -> "网址格式不正确"
    else -> "验证未通过"
}

// ==================== P4 模型管理页 ====================

@Composable
fun ModelManageScreen(
    repo: com.agentkeyboard.data.SettingsRepository,
    store: ModelStore,
    onBack: () -> Unit,
    panelInputBinder: ImePanelTextFieldBinder? = null,
    handleSystemBack: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(ModelConfigLogic.empty()) }
    var page by remember { mutableStateOf(ManagePage.LIST) }
    var editProviderId by remember { mutableStateOf<String?>(null) }
    var editModelRef by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch { file = store.load() }
    }
    LaunchedEffect(Unit) { reload() }

    fun back() {
        val parent = page.parent
        if (parent == null) onBack() else {
            page = parent
            reload()
        }
    }

    if (handleSystemBack) androidx.activity.compose.BackHandler {
        back()
    }

    when (page) {
        ManagePage.LIST -> Column(modifier = Modifier.fillMaxSize().background(SurfaceBg).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // 顶栏
            BackTopBar("模型管理", onBack)
            Spacer(Modifier.height(16.dp))

            // 默认模型卡
            val default = ModelConfigLogic.resolveDefaultModel(file)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChipBgM, RoundedCornerShape(12.dp))
                    .border(1.dp, CardBorderM, RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text("默认模型", fontSize = 11.sp, color = MutedText)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        default?.display() ?: "未设置默认模型",
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = if (default != null) BodyText else MutedText,
                        modifier = Modifier.weight(1f),
                    )
                    if (default != null) {
                        Text(
                            "更换",
                            fontSize = 13.sp, color = PrimaryBlue, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                page = ManagePage.PICK_DEFAULT
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // 服务商列表
            if (file.providers.isEmpty()) {
                Text("还没有服务商,点右下角 + 添加", fontSize = 13.sp, color = MutedText)
            }
            file.providers.forEach { provider ->
                val models = file.models.filter { it.providerId == provider.id }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editProviderId = provider.id
                            page = ManagePage.PROVIDER_DETAIL
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 状态点:绿 OK / 红 ERROR / 灰 UNVERIFIED
                        val dot = when (provider.status) {
                            ProviderStatus.OK -> OkGreen
                            ProviderStatus.ERROR -> ErrorText
                            else -> com.agentkeyboard.ui.AppTheme.colors.mutedText
                        }
                        Box(Modifier.size(8.dp).background(dot, RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(provider.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BodyText)
                            if (provider.status == ProviderStatus.ERROR && provider.errorCode.isNotEmpty()) {
                                Text(errorCodeLabel(provider.errorCode), fontSize = 11.sp, color = ErrorText)
                            }
                        }
                        Text("${models.size} 个模型 ›", fontSize = 12.sp, color = MutedText)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(SystemBgM))
            }
            SearchServiceSettings(repo, panelInputBinder)
            // P5 补充:「添加服务商」入口(M1 遗留缺失:提示文案存在但无控件,右下角固定按钮)
            Spacer(Modifier.height(24.dp))
            androidx.compose.material3.Button(
                onClick = { page = ManagePage.ADD },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("+ 添加服务商", fontSize = 15.sp)
            }
            Spacer(Modifier.height(60.dp))
        }
        ManagePage.ADD -> AddProviderScreen(
            store = store,
            panelInputBinder = panelInputBinder,
            onBack = ::back,
            onSaved = ::back,
        )
        ManagePage.PROVIDER_DETAIL -> ProviderDetailScreen(
            store = store,
            file = file,
            providerId = editProviderId,
            panelInputBinder = panelInputBinder,
            onBack = ::back,
            onChanged = { reload() },
            onOpenModel = { ref ->
                editModelRef = ref
                page = ManagePage.MODEL_DETAIL
            },
        )
        ManagePage.MODEL_DETAIL -> ModelDetailScreen(
            store = store,
            file = file,
            modelRef = editModelRef,
            panelInputBinder = panelInputBinder,
            onBack = ::back,
            onChanged = { reload() },
        )
        ManagePage.PICK_DEFAULT -> PickDefaultScreen(
            store = store,
            file = file,
            onBack = ::back,
        )
    }
}

@Composable
private fun SearchServiceSettings(repo: com.agentkeyboard.data.SettingsRepository, panelInputBinder: ImePanelTextFieldBinder?) {
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repo) { configured = !repo.loadTavilyKey().isNullOrBlank() }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("联网搜索", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SectionText)
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            modifier = Modifier.fillMaxWidth().panelImeInput(panelInputBinder, keyInput) { keyInput = it },
            readOnly = panelInputBinder != null,
            label = { Text("Tavily API Key") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            notice?.let { Text(it, fontSize = 11.sp, color = OkGreen, modifier = Modifier.weight(1f)) }
            if (configured) {
                TextButton(onClick = {
                    scope.launch {
                        repo.saveTavilyKey("")
                        configured = false
                        keyInput = ""
                        notice = "搜索 Key 已移除"
                    }
                }) { Text("移除") }
            }
            TextButton(
                enabled = keyInput.isNotBlank(),
                onClick = {
                    scope.launch {
                        repo.saveTavilyKey(keyInput.trim())
                        keyInput = ""
                        configured = true
                        notice = "搜索 Key 已加密保存"
                    }
                },
            ) { Text("保存") }
        }
    }
}

internal enum class ManagePage(val parent: ManagePage?) {
    LIST(null), ADD(LIST), PROVIDER_DETAIL(LIST), MODEL_DETAIL(PROVIDER_DETAIL), PICK_DEFAULT(LIST),
}

// ==================== P5 添加服务商页 ====================

@Composable
private fun AddProviderScreen(
    store: ModelStore,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    panelInputBinder: ImePanelTextFieldBinder?,
) {
    val scope = rememberCoroutineScope()
    var presetKey by remember { mutableStateOf(ProviderPreset.ALL.first().key) }
    var keyInput by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    // 错误分支:行内红字 / 确认弹层(NETWORK/AUTH 可仍要保存)
    var inlineError by remember { mutableStateOf<String?>(null) }
    var confirmKind by remember { mutableStateOf<String?>(null) } // NETWORK / AUTH
    var connectedMsg by remember { mutableStateOf<String?>(null) }

    val preset = ProviderPreset.ALL.first { it.key == presetKey }
    val isCustom = preset.baseUrl.isEmpty()
    val effectiveUrl = if (isCustom) customUrl.trim() else preset.baseUrl

    fun doVerify(forceSave: Boolean) {
        inlineError = null
        if (!com.agentkeyboard.data.ModelConfig.validateBaseUrl(effectiveUrl)) {
            inlineError = "网址格式不正确,应以 http(s):// 开头"
            return
        }
        if (keyInput.isBlank()) {
            inlineError = "请输入 API Key"
            return
        }
        verifying = true
        scope.launch {
            val result = ModelService.fetchModels(effectiveUrl, keyInput.trim())
            verifying = false
            when (result) {
                is FetchModelsResult.Ok -> {
                    // 保存 provider(status=OK)+ FETCHED 模型(fc 白名单即时标注)
                    val providerId = java.util.UUID.randomUUID().toString()
                    store.saveKey(providerId, keyInput.trim())
                    store.upsertProvider(
                        ProviderConfig(
                            id = providerId, name = preset.name.ifBlank { "自定义服务商" },
                            baseUrl = effectiveUrl, status = ProviderStatus.OK, createdAt = System.currentTimeMillis(),
                        ),
                    )
                    // 单次 transform 提交:基于锁内最新 file 合并,避免与 IME 侧 markUsed 等并发写丢更新
                    store.update { f ->
                        ModelConfigLogic.applyFetchedModels(f, providerId, result.models, System.currentTimeMillis())
                    }
                    connectedMsg = "已连接,共 ${result.models.size} 个模型"
                    kotlinx.coroutines.delay(800)
                    onSaved()
                }
                is FetchModelsResult.Err -> when (result.errorCode) {
                    ProviderErrorCodes.AUTH -> if (!forceSave) confirmKind = ProviderErrorCodes.AUTH else saveWithError(store, effectiveUrl, preset.name, keyInput.trim(), result.errorCode, onSaved)
                    ProviderErrorCodes.NETWORK -> if (!forceSave) confirmKind = ProviderErrorCodes.NETWORK else saveWithError(store, effectiveUrl, preset.name, keyInput.trim(), result.errorCode, onSaved)
                    ProviderErrorCodes.PATH_404 -> inlineError = "接口路径不存在:BaseURL 通常以 /v1 结尾"
                    ProviderErrorCodes.MODELS_EMPTY -> {
                        val providerId = java.util.UUID.randomUUID().toString()
                        store.saveKey(providerId, keyInput.trim())
                        store.upsertProvider(
                            ProviderConfig(
                                id = providerId, name = preset.name.ifBlank { "自定义服务商" },
                                baseUrl = effectiveUrl, status = ProviderStatus.OK,
                                errorCode = ProviderErrorCodes.MODELS_EMPTY, createdAt = System.currentTimeMillis(),
                            ),
                        )
                        connectedMsg = "连接成功,但服务商没有返回模型列表。可稍后重新拉取,或手动添加模型 ID。"
                        kotlinx.coroutines.delay(1200)
                        onSaved()
                    }
                    else -> inlineError = "无法保存:未知错误"
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(SurfaceBg).verticalScroll(rememberScrollState()).padding(20.dp)) {
        BackTopBar("添加服务商", onBack)
        Spacer(Modifier.height(16.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderPreset.ALL.forEach { p ->
                FilterChip(
                    selected = p.key == presetKey,
                    onClick = {
                        if (presetKey != p.key) keyInput = ""
                        presetKey = p.key
                        confirmKind = null
                        inlineError = null
                    },
                    enabled = !verifying && connectedMsg == null,
                    label = { Text(p.name) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (isCustom) {
            OutlinedTextField(
                value = customUrl, onValueChange = { customUrl = it },
                modifier = Modifier.fillMaxWidth().panelImeInput(panelInputBinder, customUrl) { customUrl = it },
                readOnly = panelInputBinder != null,
                label = { Text("服务地址") },
                enabled = !verifying && connectedMsg == null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                singleLine = true, shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(10.dp))
        }

        OutlinedTextField(
            value = keyInput, onValueChange = { keyInput = it },
            modifier = Modifier.fillMaxWidth().panelImeInput(panelInputBinder, keyInput) { keyInput = it },
            readOnly = panelInputBinder != null,
            label = { Text("API Key") },
            enabled = !verifying && connectedMsg == null,
            singleLine = true, shape = RoundedCornerShape(10.dp),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
        )

        inlineError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = ErrorText)
        }
        connectedMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = OkGreen)
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { doVerify(forceSave = false) },
            enabled = !verifying && connectedMsg == null,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(if (verifying) "正在连接…" else "验证并保存", fontSize = 15.sp)
        }

        // NETWORK/AUTH 弹层(可仍要保存,status=ERROR,§4.1)
        confirmKind?.let { kind ->
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier.fillMaxWidth().background(ErrorBg, RoundedCornerShape(10.dp)).padding(14.dp),
            ) {
                Text(
                    if (kind == ProviderErrorCodes.AUTH) "密钥无效或没有权限:请检查 API Key。"
                    else "无法连接服务，请检查网络或服务地址。",
                    fontSize = 13.sp, color = ErrorText,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    Text(
                        "仍要保存", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PrimaryBlue,
                        modifier = Modifier.clickable { val k = confirmKind; confirmKind = null; doVerify(forceSave = true); }.padding(end = 20.dp),
                    )
                    Text(
                        "返回修改", fontSize = 13.sp, color = MutedText,
                        modifier = Modifier.clickable { confirmKind = null },
                    )
                }
            }
        }
    }
}

private suspend fun saveWithError(
    store: ModelStore,
    url: String,
    name: String,
    key: String,
    errorCode: String,
    onSaved: () -> Unit,
) {
    val providerId = java.util.UUID.randomUUID().toString()
    store.saveKey(providerId, key)
    store.upsertProvider(
        ProviderConfig(
            id = providerId, name = name.ifBlank { "自定义服务商" },
            baseUrl = url, status = ProviderStatus.ERROR, errorCode = errorCode,
            createdAt = System.currentTimeMillis(),
        ),
    )
    onSaved()
}

// ==================== P6 服务商详情页 ====================

@Composable
private fun ProviderDetailScreen(
    store: ModelStore,
    file: ModelConfigFileAlias,
    providerId: String?,
    panelInputBinder: ImePanelTextFieldBinder?,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onOpenModel: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val provider = file.providers.firstOrNull { it.id == providerId }
    if (provider == null) {
        Column(Modifier.fillMaxSize().background(SurfaceBg).padding(20.dp)) {
            BackTopBar("服务商详情", onBack)
        }
        return
    }
    val models = file.models.filter { it.providerId == provider.id }

    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var keyInput by remember(provider.id) { mutableStateOf("") }
    var hasKey by remember(provider.id) { mutableStateOf(false) }
    var showKeyEditor by remember { mutableStateOf(false) }
    var manualId by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var fetchMsg by remember { mutableStateOf<String?>(null) }
    var showFetchFail by remember { mutableStateOf(false) }
    var lastErr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(provider.id) { hasKey = store.hasKey(provider.id) }

    Column(modifier = Modifier.fillMaxSize().background(SurfaceBg).verticalScroll(rememberScrollState()).padding(20.dp)) {
        BackTopBar("服务商详情", onBack)
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth().panelImeInput(panelInputBinder, name) { name = it }, readOnly = panelInputBinder != null, label = { Text("名称") }, singleLine = true, shape = RoundedCornerShape(10.dp))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, modifier = Modifier.fillMaxWidth().panelImeInput(panelInputBinder, baseUrl) { baseUrl = it }, readOnly = panelInputBinder != null, label = { Text("服务地址") }, singleLine = true, shape = RoundedCornerShape(10.dp), visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false))
        Spacer(Modifier.height(8.dp))
        // API Key:保存后掩码;「更换 Key」进入编辑
        if (hasKey && !showKeyEditor) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("API Key 已配置", color = BodyText, modifier = Modifier.weight(1f))
                Text("更换 Key", fontSize = 13.sp, color = PrimaryBlue, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { showKeyEditor = true })
            }
        } else {
            OutlinedTextField(
                value = keyInput, onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth().panelImeInput(panelInputBinder, keyInput) { keyInput = it },
                readOnly = panelInputBinder != null,
                label = { Text("API Key") },
                singleLine = true, shape = RoundedCornerShape(10.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
            )
        }
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                if (fetching) return@Button
                val address = baseUrl.trim()
                val replacementKey = keyInput.trim().takeIf(String::isNotEmpty)
                val validAddress = com.agentkeyboard.data.ModelConfig.validateBaseUrl(address)
                if (!validAddress ||
                    provider.baseUrl.trimEnd('/') != address.trimEnd('/') && replacementKey == null
                ) {
                    lastErr = if (!validAddress) "服务地址无效"
                        else "更换服务地址时，请重新输入 API Key"
                    showFetchFail = true
                    return@Button
                }
                fetching = true
                scope.launch {
                    val newProvider = provider.copy(
                        name = name.trim().ifBlank { provider.name },
                        baseUrl = address,
                    )
                    try {
                        val key = store.updateProviderConnection(provider, newProvider, replacementKey)
                        keyInput = ""
                        hasKey = true
                        showKeyEditor = false
                        when (val result = ModelService.fetchModels(newProvider.baseUrl, key)) {
                            is FetchModelsResult.Ok -> {
                                store.update { file ->
                                    if (newProvider !in file.providers) file else ModelConfigLogic.applyFetchedModels(
                                        file.copy(providers = file.providers.map {
                                            if (it == newProvider) it.copy(status = ProviderStatus.OK, errorCode = "") else it
                                        }),
                                        newProvider.id, result.models, System.currentTimeMillis(),
                                    )
                                }
                                fetchMsg = "已保存，${result.models.size} 个模型"
                            }
                            is FetchModelsResult.Err -> {
                                store.update { file ->
                                    file.copy(providers = file.providers.map {
                                        if (it == newProvider) {
                                            it.copy(status = ProviderStatus.ERROR, errorCode = result.errorCode)
                                        } else it
                                    })
                                }
                                lastErr = errorCodeLabel(result.errorCode)
                                showFetchFail = true
                            }
                        }
                            onChanged()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        lastErr = "保存失败，请检查连接配置"
                        showFetchFail = true
                    } finally {
                        fetching = false
                    }
                }
            },
            enabled = !fetching,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            Text(if (fetching) "正在验证…" else "保存并重新验证", fontSize = 14.sp)
        }
        fetchMsg?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 12.sp, color = OkGreen)
        }
        Spacer(Modifier.height(16.dp))

        // 模型列表
        Text("模型列表(${models.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SectionText)
        Spacer(Modifier.height(6.dp))
        if (models.isEmpty()) Text("暂无模型,可重新拉取或手动添加", fontSize = 12.sp, color = MutedText)
        models.sortedByDescending { it.ref == file.defaultModelRef }.forEach { model ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenModel(model.ref) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(model.display(), fontSize = 14.sp, color = if (model.offlineFlag) MutedText else BodyText, modifier = Modifier.weight(1f))
                if (model.offlineFlag) Text(" 可能已下线", fontSize = 11.sp, color = MutedText)
                contextWindowLabel(model)?.let { Text(it, fontSize = 11.sp, color = MutedText, modifier = Modifier.padding(end = 6.dp)) }
                Text(if (file.defaultModelRef == model.ref) "★" else "☆", fontSize = 16.sp, color = PrimaryBlue, modifier = Modifier.clickable {
                    scope.launch {
                        store.setDefaultModel(model.ref)
                        store.markUsed(model.ref)
                        onChanged()
                    }
                })
            }
        }
        Spacer(Modifier.height(14.dp))

        // 操作组
        Text("重新拉取模型", fontSize = 14.sp, color = PrimaryBlue, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().clickable {
            scope.launch {
                val key = store.loadKey(provider.id).orEmpty()
                fetching = true
                val result = ModelService.fetchModels(provider.baseUrl, key)
                fetching = false
                when (result) {
                    is FetchModelsResult.Ok -> {
                        store.update { f -> ModelConfigLogic.applyFetchedModels(f, provider.id, result.models, System.currentTimeMillis()) }
                        fetchMsg = "已重新拉取,共 ${result.models.size} 个模型"
                        onChanged()
                    }
                    is FetchModelsResult.Err -> {
                        lastErr = errorCodeLabel(result.errorCode)
                        showFetchFail = true
                    }
                }
            }
        }.padding(vertical = 8.dp))
        Text("手动添加模型", fontSize = 14.sp, color = PrimaryBlue, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().clickable { showManual = true }.padding(vertical = 8.dp))
        Text("删除服务商", fontSize = 14.sp, color = ErrorText, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().clickable { showDelete = true }.padding(vertical = 8.dp))
        Spacer(Modifier.height(40.dp))
    }

    // 手动添加弹层
    if (showManual) {
        SimpleDialog(
            title = "手动添加模型",
            onDismiss = { showManual = false },
            content = {
                OutlinedTextField(value = manualId, onValueChange = { manualId = it }, label = { Text("模型 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().panelImeInput(panelInputBinder, manualId) { manualId = it }, readOnly = panelInputBinder != null)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = manualName, onValueChange = { manualName = it }, label = { Text("显示名(可选)") }, singleLine = true, modifier = Modifier.fillMaxWidth().panelImeInput(panelInputBinder, manualName) { manualName = it }, readOnly = panelInputBinder != null)
                Spacer(Modifier.height(10.dp))
                Row {
                    Text("添加", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PrimaryBlue, modifier = Modifier.clickable {
                        val id = manualId.trim()
                        if (id.isNotEmpty()) {
                            scope.launch {
                                store.upsertModel(
                                    ModelEntry(
                                        ref = ModelConfigLogic.modelRef(provider.id, id),
                                        providerId = provider.id, modelId = id,
                                        displayName = manualName.trim(),
                                        fcAuto = FcPresetTableOf(id),
                                        source = com.agentkeyboard.data.model.ModelSource.MANUAL,
                                        lastUsedAt = 0L,
                                    ),
                                )
                                manualId = ""; manualName = ""; showManual = false; onChanged()
                            }
                        }
                    }.padding(end = 20.dp))
                    Text("取消", fontSize = 13.sp, color = MutedText, modifier = Modifier.clickable { showManual = false })
                }
            },
        )
    }

    // 拉取失败弹层:[重试][手动添加模型](§6)
    if (showFetchFail) {
        SimpleDialog(
            title = "拉取失败",
            onDismiss = { showFetchFail = false },
            content = {
                Text(lastErr ?: "未知原因", fontSize = 12.sp, color = MutedText)
                Spacer(Modifier.height(12.dp))
                Row {
                    Text("重试", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PrimaryBlue, modifier = Modifier.clickable { showFetchFail = false }.padding(end = 20.dp))
                    Text("手动添加模型", fontSize = 13.sp, color = PrimaryBlue, modifier = Modifier.clickable { showFetchFail = false; showManual = true })
                }
            },
        )
    }

    // 删除服务商确认
    if (showDelete) {
        SimpleDialog(
            title = "删除服务商",
            onDismiss = { showDelete = false },
            content = {
                Text("删除后将移除其下 ${models.size} 个模型,历史对话保留。", fontSize = 13.sp, color = BodyText)
                Spacer(Modifier.height(12.dp))
                Row {
                    Text("删除", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ErrorText, modifier = Modifier.clickable {
                        scope.launch {
                            store.removeProvider(provider.id)
                            store.deleteKey(provider.id)
                            showDelete = false
                            onBack()
                        }
                    }.padding(end = 20.dp))
                    Text("取消", fontSize = 13.sp, color = MutedText, modifier = Modifier.clickable { showDelete = false })
                }
            },
        )
    }
}

private fun FcPresetTableOf(modelId: String): String =
    com.agentkeyboard.data.model.FcPresetTable.of(modelId) ?: FcAuto.UNKNOWN

// ==================== P7 模型详情页 ====================

@Composable
private fun ModelDetailScreen(
    store: ModelStore,
    file: ModelConfigFileAlias,
    modelRef: String?,
    panelInputBinder: ImePanelTextFieldBinder?,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val model = file.models.firstOrNull { it.ref == modelRef }
    if (model == null) {
        Column(Modifier.fillMaxSize().background(SurfaceBg).padding(20.dp)) {
            BackTopBar("模型详情", onBack)
        }
        return
    }
    var displayName by remember(model.ref) { mutableStateOf(model.display()) }
    var contextK by remember(model.ref) { mutableStateOf(if (model.contextWindow > 0) (model.contextWindow / 1024).toString() else "") }
    var isDefault by remember(model.ref, file.defaultModelRef) { mutableStateOf(file.defaultModelRef == model.ref) }
    // M3-C(audit#2):删除模型加二次确认弹层(此前点击即删,无挽回)
    var showDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(SurfaceBg).verticalScroll(rememberScrollState()).padding(20.dp)) {
        BackTopBar("模型详情", onBack)
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(value = displayName, onValueChange = { displayName = it }, modifier = Modifier.fillMaxWidth().panelImeInput(panelInputBinder, displayName) { displayName = it }, readOnly = panelInputBinder != null, label = { Text("显示名") }, singleLine = true, shape = RoundedCornerShape(10.dp))
        Spacer(Modifier.height(14.dp))

        // 工具调用三态(§4.2 手动覆盖;来源小字)
        Text("工具调用", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SectionText)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("自动" to -1, "强制开" to 1, "强制关" to 0).forEach { (label, v) ->
                val selected = model.fcOverride == v
                Text(
                    label,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected) OnPrimaryM else SectionText,
                    modifier = Modifier
                        .background(if (selected) PrimaryBlue else SystemBgM, RoundedCornerShape(8.dp))
                        .clickable {
                            scope.launch {
                                // P1(评审修正单):按 ref 局部更新,不用页面旧快照整条覆盖——
                                // 期间探测写回的 fcAuto/其他端改的 displayName 不丢
                                store.updateModel(model.ref) { it.copy(fcOverride = v) }
                                onChanged()
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // 上下文长度(单位 K token,§5 P7)
        OutlinedTextField(
            value = contextK, onValueChange = { contextK = it.filter { ch -> ch.isDigit() } },
            modifier = Modifier.fillMaxWidth().panelImeInput(panelInputBinder, contextK) { contextK = it.filter { ch -> ch.isDigit() } },
            readOnly = panelInputBinder != null,
            label = { Text("上下文长度(K token,留空=未知)") },
            singleLine = true, shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(14.dp))

        // 设为默认开关
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("设为默认模型", fontSize = 14.sp, color = BodyText, modifier = Modifier.weight(1f))
            Switch(checked = isDefault, onCheckedChange = { on ->
                isDefault = on
                scope.launch {
                    if (on) {
                        store.setDefaultModel(model.ref)
                        store.markUsed(model.ref)
                    } else {
                        store.setDefaultModel("")
                    }
                    onChanged()
                }
            })
        }
        Spacer(Modifier.height(16.dp))

        Text("保存修改", fontSize = 14.sp, color = PrimaryBlue, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().clickable {
            scope.launch {
                val k = contextK.toIntOrNull()
                // 基于 store 最新值覆盖文本字段(三态/默认开关已即时落库,不能被旧快照回写)
                val latest = store.load().models.firstOrNull { it.ref == model.ref } ?: model
                store.upsertModel(
                    latest.copy(
                        displayName = displayName.trim().ifBlank { model.modelId },
                        contextWindow = if (k != null && k > 0) k * 1024 else 0,
                    ),
                )
                onBack()
            }
        }.padding(vertical = 8.dp))

        Text("删除模型", fontSize = 14.sp, color = ErrorText, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().clickable {
            showDelete = true
        }.padding(vertical = 8.dp))
        Spacer(Modifier.height(40.dp))
    }

    // M3-C(audit#2):删除模型确认(10 号设计 §5 P7 补齐;文案风格同 P6 服务商删除确认)
    if (showDelete) {
        SimpleDialog(
            title = "删除模型",
            onDismiss = { showDelete = false },
            content = {
                Text("删除模型 ${model.display()}?", fontSize = 13.sp, color = BodyText)
                Spacer(Modifier.height(6.dp))
                Text("删除后该模型将从列表移除;历史对话保留。", fontSize = 12.sp, color = MutedText)
                Spacer(Modifier.height(12.dp))
                Row {
                    Text("删除", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ErrorText, modifier = Modifier.clickable {
                        scope.launch {
                            store.removeModel(model.ref)
                            showDelete = false
                            onBack()
                        }
                    }.padding(end = 20.dp))
                    Text("取消", fontSize = 13.sp, color = MutedText, modifier = Modifier.clickable { showDelete = false })
                }
            },
        )
    }
}

// ==================== 更换默认(P4 卡「更换」) ====================

@Composable
private fun PickDefaultScreen(store: ModelStore, file: ModelConfigFileAlias, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize().background(SurfaceBg).verticalScroll(rememberScrollState()).padding(20.dp)) {
        BackTopBar("更换默认模型", onBack)
        Spacer(Modifier.height(12.dp))
        file.models.filter { !it.offlineFlag }.forEach { model ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        store.setDefaultModel(model.ref)
                        store.markUsed(model.ref)
                        onBack()
                    }
                }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (file.defaultModelRef == model.ref) "✓" else "",
                    fontSize = 14.sp, color = OkGreen, modifier = Modifier.width(24.dp),
                )
                Text(model.display(), fontSize = 14.sp, color = BodyText, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ==================== 通用弹层 ====================

@Composable
private fun SimpleDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onDismiss)) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(28.dp).background(SurfaceBg, RoundedCornerShape(12.dp)).clickable(enabled = false) {}.padding(18.dp),
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BodyText)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 别名:文件类型在 UI 层反复出现,缩写 */
private typealias ModelConfigFileAlias = com.agentkeyboard.data.model.ModelConfigFile
