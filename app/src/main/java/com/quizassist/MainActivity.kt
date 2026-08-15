package com.quizassist

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.quizassist.cache.AnswerCache
import com.quizassist.cache.HistoryItem
import com.quizassist.model.AppSettings
import com.quizassist.model.ModelPreset
import com.quizassist.model.ModelPresets
import com.quizassist.model.ProviderConfig
import com.quizassist.model.RoiBox
import com.quizassist.overlay.OverlayController
import com.quizassist.overlay.ProjectionPermissionStore
import com.quizassist.questionbank.QuestionBankStore
import com.quizassist.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore
    private var importMessage by mutableStateOf("")

    private val importQuestionBank = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { QuestionBankStore(this@MainActivity).import(uri) }
                .onSuccess { result ->
                    settingsStore.update { it.copy(questionBankMode = true) }
                    withContext(Dispatchers.Main) {
                        importMessage = "\u5df2\u5bfc\u5165 ${result.count} \u9053\u9898\u76ee\uff1a${result.name}"
                        toast(importMessage)
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main) {
                        importMessage = "\u5bfc\u5165\u5931\u8d25\uff1a${it.message.orEmpty()}"
                        toast(importMessage)
                    }
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF5F7FB),
                ) {
                    SettingsScreen(
                        store = settingsStore,
                        importMessage = importMessage,
                        onImportQuestionBank = {
                            importQuestionBank.launch(arrayOf("application/json", "text/csv", "text/plain", "*/*"))
                        },
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onStartProjection = { requestProjection() },
                        onStartOverlay = { showOverlay() },
                    )
                }
            }
        }
    }

    private val startProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ProjectionPermissionStore.update(result.resultCode, result.data)
            OverlayController.ensureCaptureSession(this)
                .onSuccess { toast(Zh.captureAuthorized) }
                .onFailure { toast("${Zh.captureSessionFailed}: ${it.message.orEmpty()}") }
        } else {
            toast(Zh.captureDenied)
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure { toast("${Zh.openPermissionFailed}: ${it.message.orEmpty()}") }
    }

    private fun requestProjection() {
        runCatching {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startProjection.launch(manager.createScreenCaptureIntent())
        }.onFailure { toast("${Zh.captureLaunchFailed}: ${it.message.orEmpty()}") }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            toast(Zh.needOverlayPermission)
            return
        }
        runCatching {
            OverlayController.show(this).getOrThrow()
            toast(Zh.overlayStarting)
        }.onFailure { toast("${Zh.overlayStartFailed}: ${it.message.orEmpty()}") }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

@Composable
private fun SettingsScreen(
    store: SettingsStore,
    importMessage: String,
    onImportQuestionBank: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onStartProjection: () -> Unit,
    onStartOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsState(initial = AppSettings())
    var draft by remember(settings) { mutableStateOf(settings) }
    var saveMessage by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<HistoryItem>()) }
    var showHistory by remember { mutableStateOf(false) }
    val answerCache = remember(context) { AnswerCache(context) }
    val questionBankStore = remember(context) { QuestionBankStore(context) }
    var bankInfo by remember(importMessage) { mutableStateOf(questionBankStore.info()) }

    LaunchedEffect(Unit) {
        history = answerCache.recent()
        bankInfo = questionBankStore.info()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF17324D)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Zh.appTitle, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(Zh.subtitle, color = Color(0xFFD7E7F5), style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionCard(Zh.modelSection, Zh.modelSectionHint) {
            ProviderEditor(Zh.flashTitle, ModelPresets.flash, draft.flashProvider) {
                draft = draft.copy(flashProvider = it)
            }
            HorizontalDivider()
            ProviderEditor(Zh.deepTitle, ModelPresets.deep, draft.deepProvider) {
                draft = draft.copy(deepProvider = it)
            }
        }

        SectionCard(Zh.questionBankSection, Zh.questionBankHint) {
            Text(
                if (bankInfo.count > 0) "${Zh.importedCount}${bankInfo.count}${Zh.questionCountSuffix}\n${bankInfo.name}"
                else Zh.noQuestionBank,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImportQuestionBank) { Text(Zh.importBank) }
                OutlinedButton(
                    onClick = {
                        questionBankStore.clear()
                        bankInfo = questionBankStore.info()
                        draft = draft.copy(questionBankMode = false)
                    },
                    enabled = bankInfo.count > 0,
                ) { Text(Zh.clearBank) }
            }
            ToggleRow(Zh.enableBank, draft.questionBankMode) {
                draft = draft.copy(questionBankMode = it)
            }
            if (importMessage.isNotBlank()) Text(importMessage, color = MaterialTheme.colorScheme.primary)
        }

        SectionCard(Zh.behaviorSection, Zh.behaviorHint) {
            OutlinedTextField(
                value = draft.maxWaitTimeoutSeconds.toString(),
                onValueChange = { value ->
                    draft = draft.copy(maxWaitTimeoutSeconds = value.toIntOrNull()?.coerceIn(5, 180) ?: 60)
                },
                label = { Text(Zh.maxWait) },
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(Zh.visionFallback, draft.useVisionWhenOcrWeak) { draft = draft.copy(useVisionWhenOcrWeak = it) }
            ToggleRow(Zh.clickThrough, draft.clickThrough) { draft = draft.copy(clickThrough = it) }
            ToggleRow(Zh.useRoi, draft.roi != null) { enabled ->
                draft = draft.copy(roi = if (enabled) draft.roi ?: RoiBox(0.08f, 0.22f, 0.84f, 0.38f) else null)
            }
            Text("${Zh.overlayAlpha} ${(draft.overlayAlpha * 100).toInt()}%")
            Slider(
                value = draft.overlayAlpha,
                onValueChange = { draft = draft.copy(overlayAlpha = it.coerceIn(0.35f, 1f)) },
                valueRange = 0.35f..1f,
            )
        }

        SectionCard(Zh.actionsSection, Zh.actionsHint) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequestOverlayPermission) { Text(Zh.overlayPermission) }
                OutlinedButton(onClick = onStartProjection) { Text(Zh.authorizeCapture) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartOverlay) { Text(Zh.startOverlay) }
                OutlinedButton(
                    onClick = {
                        history = history.ifEmpty { emptyList() }
                        showHistory = true
                        scope.launch { history = answerCache.recent() }
                    },
                ) { Text(Zh.history) }
            }
            Button(
                onClick = {
                    scope.launch {
                        runCatching { store.save(draft) }
                            .onSuccess { saveMessage = Zh.saved }
                            .onFailure { saveMessage = "${Zh.saveFailed}: ${it.message.orEmpty()}" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(Zh.saveSettings) }
            if (saveMessage.isNotBlank()) Text(saveMessage, color = MaterialTheme.colorScheme.primary)
        }

        Text(Zh.securityNotice, color = Color(0xFF566273), style = MaterialTheme.typography.bodySmall)
        Text(Zh.setupGuide, color = Color(0xFF566273), style = MaterialTheme.typography.bodySmall)
    }

    if (showHistory) {
        HistoryDialog(
            history = history,
            onDismiss = { showHistory = false },
            onClear = {
                scope.launch {
                    answerCache.clear()
                    history = emptyList()
                }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, hint: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(hint, color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
                content()
            },
        )
    }
}

@Composable
private fun ProviderEditor(
    title: String,
    presets: List<ModelPreset>,
    provider: ProviderConfig,
    onChange: (ProviderConfig) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { expanded = true }) { Text(Zh.choosePreset) }
            Text(provider.modelName.ifBlank { Zh.notConfigured }, style = MaterialTheme.typography.bodyMedium)
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.label) },
                        onClick = {
                            expanded = false
                            onChange(preset.provider.copy(apiKey = provider.apiKey))
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { onChange(provider.copy(baseUrl = it)) },
            label = { Text(Zh.baseUrl) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = provider.apiKey,
            onValueChange = { onChange(provider.copy(apiKey = it)) },
            label = { Text(Zh.apiKey) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = provider.modelName,
            onValueChange = { onChange(provider.copy(modelName = it)) },
            label = { Text(Zh.modelName) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        ToggleRow(Zh.searchHint, provider.enableSearchHint) { onChange(provider.copy(enableSearchHint = it)) }
        ReasoningEditor(provider.reasoningEffort) { onChange(provider.copy(reasoningEffort = it)) }
    }
}

@Composable
private fun ReasoningEditor(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { expanded = true }) { Text(Zh.reasoningEffort) }
        Text(value.ifBlank { Zh.reasoningOff })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("", "low", "medium", "high", "xhigh", "max").forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.ifBlank { Zh.reasoningOff }) },
                    onClick = { expanded = false; onChange(item) },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HistoryDialog(
    history: List<HistoryItem>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Zh.history) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (history.isEmpty()) {
                    Text(Zh.historyEmpty)
                } else {
                    history.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.questionText.take(160), fontWeight = FontWeight.SemiBold)
                            Text(item.answer.answer.ifBlank { item.answer.raw }.take(240))
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Zh.close) } },
        dismissButton = { TextButton(onClick = onClear, enabled = history.isNotEmpty()) { Text(Zh.clearHistory) } },
    )
}

private object Zh {
    const val appTitle = "\u5c0f\u591a\u7b54\u9898\u52a9\u624b"
    const val subtitle = "\u622a\u56fe\u8bc6\u9898\u3001\u5feb\u901f\u56de\u7b54\u3001\u6df1\u5ea6\u6821\u9a8c"
    const val modelSection = "\u6a21\u578b\u914d\u7f6e"
    const val modelSectionHint = "\u4f7f\u7528\u9884\u8bbe\u6216\u586b\u5199\u517c\u5bb9\u63a5\u53e3\u5730\u5740"
    const val flashTitle = "\u5feb\u901f\u56de\u7b54"
    const val deepTitle = "\u6df1\u5ea6\u56de\u7b54"
    const val questionBankSection = "\u9898\u5e93"
    const val questionBankHint = "\u5bfc\u5165\u540e\u4f18\u5148\u4f7f\u7528\u672c\u5730\u5173\u952e\u8bcd\u5339\u914d"
    const val importedCount = "\u5df2\u5bfc\u5165 "
    const val questionCountSuffix = " \u9053\u9898"
    const val noQuestionBank = "\u5c1a\u672a\u5bfc\u5165\u9898\u5e93"
    const val importBank = "\u5bfc\u5165\u9898\u5e93"
    const val clearBank = "\u6e05\u9664\u9898\u5e93"
    const val enableBank = "\u542f\u7528\u672c\u5730\u9898\u5e93"
    const val behaviorSection = "\u7b54\u9898\u884c\u4e3a"
    const val behaviorHint = "\u8c03\u6574\u7b49\u5f85\u65f6\u95f4\u3001\u622a\u56fe\u533a\u57df\u4e0e\u60ac\u6d6e\u7a97\u4ea4\u4e92"
    const val actionsSection = "\u5f00\u59cb\u4f7f\u7528"
    const val actionsHint = "\u9996\u6b21\u4f7f\u7528\u8bf7\u5148\u6388\u6743\u5c4f\u5e55\u622a\u56fe\u548c\u60ac\u6d6e\u7a97"
    const val maxWait = "\u6df1\u5ea6\u56de\u7b54\u6700\u5927\u7b49\u5f85\u65f6\u95f4\uff08\u79d2\uff09"
    const val visionFallback = "\u8bc6\u5b57\u4e0d\u8db3\u65f6\u4f7f\u7528\u56fe\u7247\u7406\u89e3"
    const val clickThrough = "\u5141\u8bb8\u70b9\u51fb\u7a7f\u8fc7\u60ac\u6d6e\u7a97"
    const val useRoi = "\u4f7f\u7528\u56fa\u5b9a\u622a\u56fe\u533a\u57df"
    const val overlayAlpha = "\u60ac\u6d6e\u7a97\u900f\u660e\u5ea6"
    const val searchHint = "\u5141\u8bb8\u8054\u7f51\u68c0\u7d22"
    const val reasoningEffort = "\u63a8\u7406\u5f3a\u5ea6"
    const val reasoningOff = "\u5173\u95ed"
    const val apiKey = "\u5bc6\u94a5"
    const val baseUrl = "\u63a5\u53e3\u5730\u5740"
    const val modelName = "\u6a21\u578b\u540d\u79f0"
    const val choosePreset = "\u9884\u8bbe"
    const val notConfigured = "\u672a\u914d\u7f6e"
    const val history = "\u7b54\u9898\u8bb0\u5f55"
    const val historyEmpty = "\u6682\u65e0\u8bb0\u5f55"
    const val clearHistory = "\u6e05\u7a7a\u8bb0\u5f55"
    const val close = "\u5173\u95ed"
    const val securityNotice = "\u5e94\u7528\u4e0d\u4f1a\u4e0a\u4f20\u4f60\u7684\u5bc6\u94a5\u3002\u8bf7\u4ec5\u901a\u8fc7\u5b98\u65b9\u53d1\u5e03\u6e20\u9053\u83b7\u53d6\u5b89\u88c5\u5305\uff0c\u975e\u5b98\u65b9\u5b89\u88c5\u5305\u53ef\u80fd\u5bfc\u81f4\u5bc6\u94a5\u6cc4\u9732\u3002"
    const val setupGuide = "\u914d\u7f6e\uff1a\u9009\u62e9\u9884\u8bbe\u6216\u586b\u5199\u63a5\u53e3\u5730\u5740\u3001\u5bc6\u94a5\u548c\u6a21\u578b\u540d\u79f0\uff0c\u4fdd\u5b58\u540e\u6388\u6743\u622a\u56fe\u5e76\u542f\u52a8\u60ac\u6d6e\u7a97\u3002"
    const val saved = "\u8bbe\u7f6e\u5df2\u4fdd\u5b58"
    const val saveFailed = "\u4fdd\u5b58\u5931\u8d25"
    const val saveSettings = "\u4fdd\u5b58\u8bbe\u7f6e"
    const val overlayPermission = "\u60ac\u6d6e\u7a97\u6743\u9650"
    const val authorizeCapture = "\u6388\u6743\u622a\u56fe"
    const val startOverlay = "\u542f\u52a8\u60ac\u6d6e\u7a97"
    const val captureAuthorized = "\u622a\u56fe\u4f1a\u8bdd\u5df2\u5c31\u7eea"
    const val captureDenied = "\u672a\u6388\u6743\u622a\u56fe"
    const val needOverlayPermission = "\u8bf7\u5148\u5f00\u542f\u60ac\u6d6e\u7a97\u6743\u9650"
    const val overlayStarting = "\u6b63\u5728\u542f\u52a8\u60ac\u6d6e\u7a97"
    const val overlayStartFailed = "\u542f\u52a8\u60ac\u6d6e\u7a97\u5931\u8d25"
    const val openPermissionFailed = "\u6253\u5f00\u6743\u9650\u9875\u5931\u8d25"
    const val captureLaunchFailed = "\u542f\u52a8\u622a\u56fe\u6388\u6743\u5931\u8d25"
    const val captureSessionFailed = "\u622a\u56fe\u4f1a\u8bdd\u542f\u52a8\u5931\u8d25"
}
