package com.mobilechat.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilechat.app.ChatViewModel
import com.mobilechat.app.BuildConfig
import com.mobilechat.app.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MobileChatApp(vm: ChatViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    var backStack by rememberSaveable { mutableStateOf(if (selected == null) listOf("home") else listOf("home", "chat")) }
    val route = backStack.last()
    LaunchedEffect(route) { if (route == "home") vm.refreshQuote() }
    val transitionState = remember { SeekableTransitionState(route) }
    val transition = rememberTransition(transitionState, label = "page")
    val pageStates = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    var popping by remember { mutableStateOf(false) }
    var popDirection by remember { mutableIntStateOf(direction) }
    var inPredictiveBack by remember { mutableStateOf(false) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(ready, selected, route) {
        if (ready && selected == null && route == "chat") backStack = listOf("home")
    }
    fun canNavigate() = !inPredictiveBack && transitionState.currentState == backStack.last() && transitionState.targetState == backStack.last()
    fun navigate(destination: String) {
        if (!canNavigate() || destination == backStack.last()) return
        focus.clearFocus()
        popping = false
        backStack = backStack + destination
    }
    fun back() {
        if (!canNavigate() || backStack.size <= 1) return
        focus.clearFocus()
        popping = true
        popDirection = direction
        backStack = backStack.dropLast(1)
    }
    PredictiveBackHandler(enabled = backStack.size > 1) { progress ->
        // Consume even a stale callback dispatched during another page transition.
        if (!canNavigate() || backStack.size <= 1) {
            progress.collect {}
        } else {
            popping = true
            popDirection = direction
            backProgress = 0f
            inPredictiveBack = true
            try {
                progress.collect {
                    popDirection = if (it.swipeEdge == BackEventCompat.EDGE_RIGHT) -1 else 1
                    backProgress = it.progress.coerceIn(0f, 1f)
                }
                focus.clearFocus()
                backStack = backStack.dropLast(1)
            } catch (e: CancellationException) {
                // Keep the stack and editor/chat data intact when the gesture is cancelled.
                throw e
            } finally {
                inPredictiveBack = false
            }
        }
    }
    if (inPredictiveBack) {
        LaunchedEffect(backProgress) { transitionState.seekTo(backProgress, backStack[backStack.lastIndex - 1]) }
    } else {
        LaunchedEffect(route) {
            val previous = transitionState.currentState
            if (previous != route) {
                transitionState.animateTo(route)
            } else if (transitionState.targetState != route) {
                // Retrace the same transition instead of navigating on a cancelled gesture.
                val duration = (transitionState.fraction * transition.totalDurationNanos / 1_000_000).toInt()
                animate(transitionState.fraction, 0f, animationSpec = tween(duration)) { value, _ ->
                    this@LaunchedEffect.launch {
                        if (value > 0f) transitionState.seekTo(value)
                        else transitionState.snapTo(route)
                    }
                }
            }
            if (previous !in backStack) pageStates.removeState(previous)
            // The outgoing chat must remain available throughout its return animation.
            if (route == "home") vm.select(null)
        }
    }
    MobileTheme(settings.theme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            transition.AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val motionDirection = if (popping) popDirection else direction
                    val enter = slideInHorizontally(tween(300)) { motionDirection * if (popping) -it / 4 else it } + fadeIn(tween(300))
                    val exit = slideOutHorizontally(tween(300)) { motionDirection * if (popping) it else -it / 4 } + fadeOut(tween(300))
                    ContentTransform(enter, exit, targetContentZIndex = backStack.indexOf(targetState).coerceAtLeast(0).toFloat(), sizeTransform = null)
                },
                contentKey = { it },
            ) { screen ->
                pageStates.SaveableStateProvider(screen) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        when {
                            screen == "home" -> ConversationList(vm, onOpen = {
                                if (canNavigate()) { vm.select(it); navigate("chat") }
                            }, onNew = {
                                if (canNavigate()) {
                                    if (settings.providers.isEmpty()) navigate("editor/${UUID.randomUUID()}")
                                    else vm.newConversation { navigate("chat") }
                                }
                            }, onSettings = { navigate("settings") })
                            screen == "chat" -> ChatScreen(vm, onBack = { back() }, onSettings = { navigate("settings") })
                            screen == "settings" -> SettingsScreen(settings, busy, vm, onBack = { back() }, onEdit = { navigate("editor/$it") })
                            screen.startsWith("editor/") -> {
                                val editId = screen.substringAfter('/')
                                ProviderEditor(vm, settings.providers.firstOrNull { it.id == editId } ?: Provider(id = editId), onBack = { back() })
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(16.dp))
        }
    }
}

@Composable
internal fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    MiuixCard(modifier = modifier.fillMaxWidth(), cornerRadius = 24.dp,
        colors = CardDefaults.defaultColors(color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun PageTitle(title: String, back: (() -> Unit)? = null, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun ConversationList(vm: ChatViewModel, onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val quote by vm.quote.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<ConversationEntity?>(null) }
    var rename by remember { mutableStateOf<ConversationEntity?>(null) }
    var delete by remember { mutableStateOf<ConversationEntity?>(null) }
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    Column(Modifier.fillMaxSize()) {
        PageTitle("对话", action = { IconButton(onSettings) { Icon(Icons.Default.Settings, "设置") } })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("MobileChat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(quote?.text ?: "山重水复疑无路，柳暗花明又一村。", fontSize = 32.sp,
                    fontFamily = FontFamily(Font(com.mobilechat.app.R.font.long_cang_regular)), lineHeight = 42.sp)
                Text(quote?.attribution?.ifBlank { null } ?: "陆游 · 游山西村", Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(if (settings.providers.isEmpty()) "先添加中转站地址、密钥与模型，即可开始交流。" else "文字、图片与文件，都可以放进对话。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                MiuixButton(onClick = onNew, enabled = ready, modifier = Modifier.fillMaxWidth()) { MiuixText(if (settings.providers.isEmpty()) "添加第一个站点" else "新建对话") }
                Spacer(Modifier.height(12.dp))
            }
            conversations.groupBy { Instant.ofEpochMilli(it.updatedAt).atZone(ZoneId.systemDefault()).toLocalDate() }
                .forEach { (date, datedConversations) ->
                item(key = "date:$date") {
                    Text(when (date) { today -> "今天"; yesterday -> "昨天"; else -> date.format(DateTimeFormatter.ofPattern("yyyy年M月d日", locale)) },
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                items(datedConversations, key = { it.id }) { conversation ->
                SectionCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onOpen(conversation.id) }.padding(vertical = 8.dp)) {
                            Text(conversation.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            Text(conversation.model, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            Text(SimpleDateFormat("MM-dd HH:mm", locale).format(Date(conversation.updatedAt)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Box {
                            IconButton(onClick = { menu = conversation }) { Icon(Icons.Default.MoreVert, "会话操作") }
                            DropdownMenu(expanded = menu?.id == conversation.id, onDismissRequest = { menu = null }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { rename = conversation; menu = null })
                                DropdownMenuItem(text = { Text("删除会话") }, onClick = { delete = conversation; menu = null })
                            }
                        }
                    }
                }
            }
            }
        }
        if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    rename?.let { conversation ->
        var title by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名会话") }, text = {
            OutlinedTextField(title, { title = it }, label = { Text("会话名称") }, singleLine = true)
        }, confirmButton = { TextButton(onClick = { vm.rename(conversation.id, title); rename = null }, enabled = title.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } })
    }
    delete?.let { conversation ->
        ConfirmDelete("删除“${conversation.title}”？", "会话内容和附件将从本机删除。", { delete = null }) { vm.deleteConversation(conversation.id); delete = null }
    }
}

@Composable
private fun SettingsScreen(settings: AppSettings, busy: Boolean, vm: ChatViewModel, onBack: () -> Unit, onEdit: (String) -> Unit) {
    var deleting by remember { mutableStateOf<Provider?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageTitle("设置", onBack)
        LazyColumn(contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.navigationBarsPadding()) {
            item { Text("外观", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp))
                SectionCard {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                            FilterChip(selected = settings.theme == value, onClick = { vm.appearance(value, settings.glass) }, label = { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("玻璃效果", fontWeight = FontWeight.SemiBold); Text(if (android.os.Build.VERSION.SDK_INT < 33) "此设备使用实色界面" else "仅用于顶部栏与输入栏", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        MiuixSwitch(checked = settings.glass, onCheckedChange = { vm.appearance(settings.theme, it) })
                    }
                }
            }
            item { Text("API 站点", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(settings.providers, key = { it.id }) { provider ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onEdit(provider.id) }) {
                            Text(provider.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Spacer(Modifier.height(8.dp)); Text(provider.url, fontSize = 13.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis)
                            Text("${provider.models.size} 个模型", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { deleting = provider }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "删除站点") }
                    }
                }
            }
            item { MiuixButton(onClick = { onEdit(UUID.randomUUID().toString()) }, modifier = Modifier.fillMaxWidth()) { MiuixText("添加站点") } }
            item { Text("MobileChat ${BuildConfig.VERSION_NAME} · R8 ${if (BuildConfig.R8_ENABLED) "开启" else "关闭"}\n会话和配置保存在本机，API Key 由 Android Keystore 加密。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    deleting?.let { provider -> ConfirmDelete("删除“${provider.name}”？", "已有会话会保留，继续交流时需要重新选择站点。", { deleting = null }) { vm.deleteProvider(provider.id); deleting = null } }
}

@Composable
internal fun ConfirmDelete(title: String, description: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(description) },
        confirmButton = { TextButton(confirm) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

@Composable
internal fun copyText(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { text: String -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MobileChat", text)) } }
}
