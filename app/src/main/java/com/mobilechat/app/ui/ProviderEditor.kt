package com.mobilechat.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mobilechat.app.ChatViewModel
import com.mobilechat.app.data.Provider
import com.mobilechat.app.data.reasoningEfforts
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch

@Composable
fun ProviderEditor(vm: ChatViewModel, provider: Provider, onBack: () -> Unit) {
    var name by rememberSaveable(provider.id) { mutableStateOf(provider.name) }
    var url by rememberSaveable(provider.id) { mutableStateOf(provider.url) }
    var models by rememberSaveable(provider.id) { mutableStateOf(provider.models.joinToString("\n")) }
    var model by rememberSaveable(provider.id) { mutableStateOf(provider.defaultModel) }
    var instructions by rememberSaveable(provider.id) { mutableStateOf(provider.instructions) }
    var tokens by rememberSaveable(provider.id) { mutableStateOf(provider.maxOutputTokens?.toString().orEmpty()) }
    var effort by rememberSaveable(provider.id) { mutableStateOf(provider.reasoningEffort.orEmpty()) }
    var showReasoning by rememberSaveable(provider.id) { mutableStateOf(provider.showReasoning) }
    var codexCompatibility by rememberSaveable(provider.id) { mutableStateOf(provider.codexCompatibility) }
    var key by remember(provider.id) { mutableStateOf(vm.editorKeys[provider.id].orEmpty()) }
    var keyLoaded by remember(provider.id) { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val modelList = models.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    LaunchedEffect(provider.id) {
        try { if (!vm.editorKeys.containsKey(provider.id)) key = vm.readKey(provider); keyLoaded = true }
        catch (e: Exception) { error = e.message.orEmpty() }
    }
    fun form(): Provider {
        require(codexCompatibility || tokens.isBlank() || tokens.trim().toIntOrNull() != null) { "最大输出 token 数须为整数" }
        return provider.copy(name = name.trim(), url = url.trim(), models = modelList,
            defaultModel = model.takeIf { it in modelList } ?: modelList.firstOrNull().orEmpty(), instructions = instructions,
            maxOutputTokens = tokens.trim().toIntOrNull()?.takeIf { !codexCompatibility || it > 0 }, reasoningEffort = effort.ifBlank { null }, showReasoning = showReasoning,
            codexCompatibility = codexCompatibility).also { it.validate() }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageTitle("站点配置", onBack)
        LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { SectionCard {
                Text("连接信息", fontWeight = FontWeight.SemiBold)
                MiuixTextField(value = name, onValueChange = { name = it }, label = "站点名称", singleLine = true, modifier = Modifier.fillMaxWidth())
                MiuixTextField(value = url, onValueChange = { url = it }, label = "完整 Responses 接口地址", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Text("例如 https://example.com/v1/responses。HTTP 地址会以明文连接。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MiuixTextField(value = key, onValueChange = { key = it; vm.editorKeys[provider.id] = it; keyLoaded = true }, label = "API Key（可留空）",
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true,
                    trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "隐藏密钥" else "显示密钥") } }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Codex 兼容", fontWeight = FontWeight.SemiBold)
                        Text("适用于只允许 Codex 客户端的中转站", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MiuixSwitch(checked = codexCompatibility, onCheckedChange = { codexCompatibility = it; result = "" })
                }
            } }
            item { SectionCard {
                Text("模型", fontWeight = FontWeight.SemiBold)
                MiuixTextField(value = models, onValueChange = { models = it }, label = "模型名称，每行一个", modifier = Modifier.fillMaxWidth())
                Text("点击选择默认模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modelList.forEach { value -> FilterChip(selected = (model.takeIf { it in modelList } ?: modelList.firstOrNull()) == value, onClick = { model = value }, label = { Text(value) }) }
                }
            } }
            item { SectionCard {
                Text("可选请求参数", fontWeight = FontWeight.SemiBold)
                MiuixTextField(value = instructions, onValueChange = { instructions = it }, label = "系统提示词", modifier = Modifier.fillMaxWidth())
                if (!codexCompatibility) MiuixTextField(value = tokens, onValueChange = { tokens = it }, label = "最大输出 token 数", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("推理强度", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("") + reasoningEfforts).forEach { value ->
                        FilterChip(selected = effort == value, onClick = { effort = value }, label = { Text(value.ifBlank { "不发送" }) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("思考进展", fontWeight = FontWeight.SemiBold)
                        Text("请求模型提供思考摘要；站点不支持时可关闭", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MiuixSwitch(checked = showReasoning, onCheckedChange = { showReasoning = it; result = "" })
                }
                Text(if (codexCompatibility) "Codex 兼容模式不发送输出上限。推理参数和附件格式是否可用由站点及模型决定。"
                    else "留空的参数不会发送。推理参数和附件格式是否可用由站点及模型决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            if (error.isNotEmpty()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            item {
                MiuixButton(onClick = {
                    if (testing) { testJob?.cancel(); testing = false; result = "测试已停止" }
                    else {
                        try {
                            val configured = form(); testing = true; error = ""; result = "正在连接…"
                            testJob = scope.launch { try { result = vm.testConnection(configured, key) } finally { testing = false } }
                        } catch (e: Exception) { error = e.message.orEmpty() }
                    }
                }, enabled = keyLoaded && !saving, modifier = Modifier.fillMaxWidth()) { MiuixText(if (testing) "停止测试" else "测试连接") }
                if (result.isNotEmpty()) Text(result, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { MiuixButton(onClick = {
                try {
                    val configured = form(); saving = true; error = ""
                    scope.launch { try { vm.saveProvider(configured, key) { vm.editorKeys.remove(provider.id); onBack() }.join() } finally { saving = false } }
                } catch (e: Exception) { error = e.message.orEmpty() }
            }, enabled = keyLoaded && !testing && !saving, modifier = Modifier.fillMaxWidth()) { MiuixText(if (saving) "正在保存…" else "保存站点") } }
        }
    }
}
