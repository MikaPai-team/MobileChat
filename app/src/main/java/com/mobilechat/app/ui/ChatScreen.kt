package com.mobilechat.app.ui

import android.os.Build

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mobilechat.app.ChatViewModel
import com.mobilechat.app.data.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults as MiuixTextFieldDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onBack: () -> Unit, onSettings: () -> Unit) {
    val id by vm.selectedId.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val allMessages by vm.messages.collectAsStateWithLifecycle()
    val allAttachments by vm.attachments.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val generation by vm.generation.collectAsStateWithLifecycle()
    val commandPicker by vm.commandPicker.collectAsStateWithLifecycle()
    val conversation = conversations.firstOrNull { it.id == id }
    if (conversation == null) {
        Column { PageTitle("对话", onBack); Text("正在读取会话…", Modifier.padding(24.dp)) }
        return
    }
    val messages = allMessages.filter { it.conversationId == id }
    val provider = settings.providers.firstOrNull { it.id == conversation.providerId }
    var choosingModel by remember { mutableStateOf(false) }
    var choosingEffort by remember { mutableStateOf(false) }
    var showingHelp by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var editingMessage by remember(conversation.id) { mutableStateOf<MessageEntity?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.importAttachments(it, true) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importAttachments(it, false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { vm.finishPhoto(it) }
    LaunchedEffect(commandPicker) {
        when (commandPicker) {
            "model" -> choosingModel = true
            "effort" -> choosingEffort = true
            "help" -> showingHelp = true
        }
        vm.commandPicker.value = null
    }
    val list = rememberSaveable(conversation.id, saver = LazyListState.Saver) { LazyListState() }
    val scope = rememberCoroutineScope()
    val copy = copyText()
    val backdrop = if (settings.glass && Build.VERSION.SDK_INT >= 33 && LocalView.current.isHardwareAccelerated) rememberLayerBackdrop() else null
    val density = LocalDensity.current
    var topHeight by remember { mutableIntStateOf(100) }
    var bottomHeight by remember { mutableIntStateOf(130) }
    var following by rememberSaveable(conversation.id) { mutableStateOf(true) }
    val nearLatest by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 32 } }
    LaunchedEffect(list, conversation.id) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false } }
    LaunchedEffect(list, conversation.id) { snapshotFlow { !list.isScrollInProgress && nearLatest }.collect { if (it) following = true } }
    LaunchedEffect(messages.size, generation?.text, generation?.reasoning, bottomHeight, following) { if (following && messages.isNotEmpty()) list.scrollToItem(0) }
    val lastAssistant = messages.lastOrNull { it.role == "assistant" && it.replacesId == null }?.id
    Box(Modifier.fillMaxSize().statusBarsPadding().imePadding().clipToBounds()) {
        Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .background(MaterialTheme.colorScheme.background)) {
            if (messages.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(20.dp)); Text("从一个问题开始", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp)); Text("也可以拍照或附上任意文件。\n输入 / 查看可用命令。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("chat-messages"), state = list, reverseLayout = true,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = with(density) { topHeight.toDp() } + 12.dp,
                    bottom = with(density) { bottomHeight.toDp() } + 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                items(messages.asReversed(), key = { it.id }) { message ->
                    val text = generation?.takeIf { it.messageId == message.id }?.text ?: message.text
                    val reasoning = generation?.takeIf { it.messageId == message.id }?.reasoning ?: message.reasoning
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start) {
                        if (message.role == "user") {
                            var messageMenu by remember(message.id) { mutableStateOf(false) }
                            Box {
                                Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                                    .combinedClickable(onClick = {}, onLongClick = { messageMenu = true }, onLongClickLabel = "复制或编辑消息").padding(16.dp)) {
                                    allAttachments.filter { it.messageId == message.id }.forEach { attachment ->
                                        if (attachment.kind == "image") AsyncImage(vm.repository.files.file(attachment.id), attachment.name, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                                        else Text("${attachment.name} · ${formatSize(attachment.size)}", style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    if (text.isNotBlank()) Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                DropdownMenu(expanded = messageMenu, onDismissRequest = { messageMenu = false }) {
                                    DropdownMenuItem(text = { Text("复制") }, enabled = text.isNotEmpty(), onClick = { copy(text); messageMenu = false })
                                    DropdownMenuItem(text = { Text("编辑") }, enabled = !busy, onClick = { editingMessage = message; messageMenu = false })
                                }
                            }
                        } else {
                            Text(if (message.replacesId != null) "重新生成 · ${message.modelName ?: conversation.model}" else message.modelName ?: conversation.model, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            if (reasoning.isNotBlank()) ReasoningProgress(message.id, reasoning, message.status == "generating" && text.isEmpty())
                            if (text.isNotEmpty()) MarkdownReply(text, copy, animate = generation?.messageId == message.id)
                            else if (message.status == "generating" && reasoning.isBlank()) Text("等待模型回复…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (message.status == "generating") LinearProgressIndicator(Modifier.width(40.dp).padding(top = 8.dp))
                            message.error?.let { Text(it, Modifier.padding(top = 8.dp), color = if (message.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (text.isNotEmpty()) TextButton(onClick = { copy(text) }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("复制") }
                                if (message.id == lastAssistant || message.replacesId == lastAssistant) TextButton(onClick = { vm.regenerate(message.id) }, enabled = !busy) {
                                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (message.status in setOf("failed", "interrupted", "stopped")) "重试" else "重新生成")
                                }
                            }
                            if (message.outputTokens != null) Text("输入 ${message.inputTokens ?: "—"} · 输出 ${message.outputTokens} tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topHeight = it.height }.padding(12.dp)
            .chrome(backdrop, settings.glass).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回会话列表") }
            TextButton(onClick = { choosingModel = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(provider?.name ?: "选择站点", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("${conversation.model} · ${conversation.reasoningEffort ?: provider?.reasoningEffort ?: "默认强度"}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                }
                Icon(Icons.Default.ExpandMore, "切换站点和模型")
            }
            IconButton(onSettings) { Icon(Icons.Default.Settings, "设置") }
        }
        AnimatedVisibility(
            visible = !nearLatest && messages.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = with(density) { bottomHeight.toDp() } + 8.dp),
            enter = fadeIn(tween(180)) + slideInVertically(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)) { it / 3 } +
                scaleIn(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 1f)),
            exit = fadeOut(tween(150)) + slideOutVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { it / 3 } +
                scaleOut(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow), targetScale = 0.92f, transformOrigin = TransformOrigin(1f, 1f)),
        ) {
            FilledTonalButton(onClick = {
                following = false
                scope.launch { list.animateScrollToItem(0); following = true }
            }) { Text("回到最新消息") }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("chat-composer").onSizeChanged { bottomHeight = it.height }.navigationBarsPadding().padding(12.dp)) {
            Column(Modifier.fillMaxWidth().chrome(backdrop, settings.glass).padding(8.dp)) {
                if (draft.text.trimStart().startsWith("/")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                        listOf("/model", "/effort", "/help").forEach { command ->
                            AssistChip(onClick = { vm.editText(command) }, enabled = !busy, label = { Text(command) })
                        }
                    }
                    Text("命令只切换当前会话参数，附件会保留。", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (draft.attachments.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(4.dp)) {
                    items(draft.attachments, key = { it.id }) { attachment ->
                        Row(Modifier.widthIn(max = 220.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { vm.removeAttachment(attachment.id) }, enabled = !busy && !importing) { Icon(Icons.Default.Close, "移除 ${attachment.name}", Modifier.size(16.dp)) }
                        }
                    }
                }
                MiuixTextField(value = draft.text, onValueChange = vm::editText, label = "说点什么，或输入 /…", maxLines = 5, modifier = Modifier.fillMaxWidth(),
                    colors = if (backdrop != null) MiuixTextFieldDefaults.textFieldColors(backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f))
                    else MiuixTextFieldDefaults.textFieldColors())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { attachMenu = true }, enabled = !busy && !importing) { Icon(Icons.Default.Add, "添加附件") }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(text = { Text("选择图片") }, onClick = { attachMenu = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                            DropdownMenuItem(text = { Text("拍摄照片") }, onClick = { attachMenu = false; vm.takePhoto { camera.launch(it) } })
                            DropdownMenuItem(text = { Text("选择任意文件") }, onClick = { attachMenu = false; documents.launch(arrayOf("*/*")) })
                        }
                    }
                    Text(if (importing) "正在准备附件…" else if (busy && generation?.conversationId != id) "正在处理…" else "拍照、任意文件", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (busy) FilledTonalButton(onClick = vm::stop) { Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("停止") }
                    else FilledIconButton(onClick = vm::send, enabled = !importing && (draft.text.isNotBlank() || draft.attachments.isNotEmpty())) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
                }
            }
        }
    }
    editingMessage?.let { message ->
        var editedText by remember(message.id) { mutableStateOf(message.text) }
        var saving by remember(message.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!saving) editingMessage = null }, title = { Text("编辑消息") }, text = {
            OutlinedTextField(value = editedText, onValueChange = { editedText = it }, modifier = Modifier.fillMaxWidth(),
                minLines = 3, maxLines = 10, enabled = !saving)
        }, confirmButton = {
            TextButton(enabled = !busy && !saving && (editedText.isNotBlank() || allAttachments.any { it.messageId == message.id }), onClick = {
                saving = true
                scope.launch { try { vm.editMessage(message.id, editedText) { editingMessage = null }.join() } finally { saving = false } }
            }) { Text("保存") }
        }, dismissButton = { TextButton(enabled = !saving, onClick = { editingMessage = null }) { Text("取消") } })
    }
    if (choosingModel) ModalBottomSheet(onDismissRequest = { choosingModel = false }) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("站点与模型", style = MaterialTheme.typography.headlineSmall) }
            settings.providers.forEach { station ->
                item(key = station.id) { Text(station.name, fontWeight = FontWeight.SemiBold) }
                items((station.models + listOfNotNull(conversation.model.takeIf { station.id == conversation.providerId })).distinct(), key = { "${station.id}:$it" }) { model ->
                    TextButton(onClick = { vm.changeProvider(station, model); choosingModel = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(model, Modifier.weight(1f)); if (station.id == conversation.providerId && model == conversation.model) Icon(Icons.Default.Check, "已选择")
                    }
                }
            }
            if (settings.providers.isEmpty()) item { TextButton(onClick = { choosingModel = false; onSettings() }) { Text("添加站点") } }
        }
    }
    if (choosingEffort) ModalBottomSheet(onDismissRequest = { choosingEffort = false }) {
        Column(Modifier.navigationBarsPadding().padding(24.dp)) {
            Text("推理强度", style = MaterialTheme.typography.headlineSmall)
            Text("当前会话：${conversation.reasoningEffort ?: "跟随站点（${provider?.reasoningEffort ?: "不发送"}）"}", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            (listOf<String?>(null) + reasoningEfforts).forEach { effort ->
                TextButton(onClick = { vm.changeEffort(effort); choosingEffort = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(effort ?: "跟随站点默认值", Modifier.weight(1f))
                    if (conversation.reasoningEffort == effort) Icon(Icons.Default.Check, "已选择")
                }
            }
        }
    }
    if (showingHelp) AlertDialog(onDismissRequest = { showingHelp = false }, title = { Text("会话命令") }, text = {
        Text("/model　打开模型选择\n/model 模型名称　直接切换模型\n/effort　选择推理强度\n/effort high　直接设置强度\n/effort default　跟随站点默认值\n/help　查看帮助\n\n推理强度：${reasoningEfforts.joinToString("、")}。命令不会发送到模型，附件会保留。")
    }, confirmButton = { TextButton(onClick = { showingHelp = false }) { Text("知道了") } })
}

@Composable
private fun ReasoningProgress(id: String, text: String, thinking: Boolean) {
    var expanded by rememberSaveable(id) { mutableStateOf(true) }
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (thinking) "思考进展" else "思考过程", style = MaterialTheme.typography.labelMedium, color = gray, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起思考过程" else "展开思考过程", Modifier.size(18.dp), tint = gray)
        }
        if (expanded) MarkdownReply(text, copyText(), animate = thinking, textColor = gray, textSize = 14.sp)
    }
}

private fun formatSize(bytes: Long): String = if (bytes >= 1_000_000) "${"%.1f".format(java.util.Locale.ROOT, bytes / 1_000_000f)} MB" else "${(bytes / 1000).coerceAtLeast(1)} KB"
