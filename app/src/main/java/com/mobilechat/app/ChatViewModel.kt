package com.mobilechat.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.mobilechat.app.data.*
import com.mobilechat.app.network.ResponsesClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class Draft(val text: String = "", val attachments: List<AttachmentEntity> = emptyList())
data class Generation(val conversationId: String, val messageId: String, val text: String = "", val reasoning: String = "")

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val repository = (application as MobileChatApplication).repository
    private val client = ResponsesClient()
    val settings = repository.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val conversations = repository.dao.observeConversations().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedId = saved.getStateFlow<String?>("conversation", null)
    val messages = selectedId.flatMapLatest { it?.let(repository.dao::observeMessages) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val attachments = selectedId.flatMapLatest { it?.let(repository.dao::observeAttachments) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val drafts = MutableStateFlow<Map<String, Draft>>(emptyMap())
    val draft = combine(selectedId, drafts) { id, map -> map[id] ?: Draft() }.stateIn(viewModelScope, SharingStarted.Eagerly, Draft())
    val busy = MutableStateFlow(false)
    val importing = MutableStateFlow(saved.get<String>("cameraFile") != null)
    val commandPicker = MutableStateFlow<String?>(null)
    val generation = MutableStateFlow<Generation?>(null)
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val ready = MutableStateFlow(false)
    val editorKeys = mutableMapOf<String, String>()
    private var generationJob: Job? = null

    init { launchAction { repository.awaitReady(); ready.value = true } }
    private fun launchAction(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { events.emit(e.message ?: "操作失败，请重试") }
    }
    fun select(id: String?) { saved["conversation"] = id }
    fun newConversation() = launchAction {
        val provider = settings.value.providers.firstOrNull() ?: error("请先在设置中添加一个站点")
        select(repository.newConversation(provider))
    }
    fun rename(id: String, title: String) = launchAction {
        require(title.isNotBlank()) { "会话名称不能为空" }
        repository.dao.conversation(id)?.let { repository.dao.saveConversation(it.copy(title = title.trim())) }
    }
    fun deleteConversation(id: String) = launchAction {
        require(generation.value?.conversationId != id && !importing.value) { "请先停止生成或等待附件读取完成" }
        drafts.value[id]?.attachments?.forEach { withContext(Dispatchers.IO) { repository.files.delete(it.id) } }
        repository.deleteConversation(id)
        drafts.update { it - id }
        if (selectedId.value == id) select(null)
    }
    fun editText(text: String) {
        val id = selectedId.value ?: return
        drafts.update { it + (id to ((it[id] ?: Draft()).copy(text = text))) }
    }
    fun removeAttachment(id: String) = launchAction {
        val conversation = selectedId.value ?: return@launchAction
        drafts.update { it + (conversation to ((it[conversation] ?: Draft()).let { draft -> draft.copy(attachments = draft.attachments.filter { a -> a.id != id }) })) }
        withContext(Dispatchers.IO) { repository.files.delete(id) }
    }
    fun importAttachments(uris: List<Uri>, image: Boolean) {
        val id = selectedId.value ?: return
        if (importing.value) return
        importing.value = true
        launchAction {
            try {
                repository.awaitReady()
                for (uri in uris) {
                    val attachment = withContext(Dispatchers.IO) { repository.files.import(uri, image) }
                    drafts.update { it + (id to ((it[id] ?: Draft()).let { d -> d.copy(attachments = d.attachments + attachment) })) }
                }
            } finally { importing.value = false }
        }
    }
    fun takePhoto(launch: (Uri) -> Unit) {
        val id = selectedId.value ?: return
        if (busy.value || importing.value || !ready.value) return
        importing.value = true
        launchAction {
            try {
                repository.awaitReady()
                val photo = withContext(Dispatchers.IO) { repository.files.createCameraPhoto() }
                saved["cameraFile"] = photo.name
                saved["cameraConversation"] = id
                launch(repository.files.cameraUri(photo))
            } catch (e: Exception) {
                finishPhoto(false)
                throw e
            }
        }
    }
    fun finishPhoto(success: Boolean) = launchAction {
        val name = saved.get<String>("cameraFile") ?: return@launchAction
        val id = saved.get<String>("cameraConversation")
        try {
            if (success && id != null) {
                repository.awaitReady()
                require(repository.dao.conversation(id) != null) { "拍摄的会话已不存在" }
                val attachment = withContext(Dispatchers.IO) { repository.files.import(repository.files.cameraUri(repository.files.cameraPhoto(name)), true) }
                drafts.update { it + (id to ((it[id] ?: Draft()).let { d -> d.copy(attachments = d.attachments + attachment) })) }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { repository.files.cameraPhoto(name).delete() }
            saved.remove<String>("cameraFile")
            saved.remove<String>("cameraConversation")
            importing.value = false
        }
    }
    fun changeProvider(provider: Provider, model: String) = launchAction {
        require(!busy.value) { "生成期间不能切换站点或模型" }
        val id = selectedId.value ?: return@launchAction
        repository.dao.conversation(id)?.let { repository.dao.saveConversation(it.copy(providerId = provider.id, model = model)) }
    }
    fun changeEffort(effort: String?) = launchAction {
        require(!busy.value) { "生成期间不能切换推理强度" }
        require(effort == null || effort in reasoningEfforts) { "无效的推理强度" }
        val id = selectedId.value ?: return@launchAction
        repository.dao.conversation(id)?.let { repository.dao.saveConversation(it.copy(reasoningEffort = effort)) }
    }
    fun saveProvider(provider: Provider, plaintextKey: String, done: () -> Unit) = launchAction {
        provider.validate()
        val encrypted = withContext(Dispatchers.IO) { repository.vault.encrypt(plaintextKey.trim()) }
        repository.settings.saveProvider(provider.copy(encryptedKey = encrypted))
        done()
    }
    fun deleteProvider(id: String) = launchAction {
        require(!busy.value) { "请在生成结束后删除站点" }
        repository.settings.deleteProvider(id)
    }
    fun appearance(theme: String, glass: Boolean) = launchAction { repository.settings.appearance(theme, glass) }
    suspend fun readKey(provider: Provider) = withContext(Dispatchers.IO) { repository.vault.decrypt(provider.encryptedKey) }
    suspend fun testConnection(provider: Provider, plaintextKey: String): String = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            provider.validate()
            val response = client.generate(provider.copy(maxOutputTokens = 64, instructions = "仅回复 OK"), plaintextKey.trim(), listOf(InputMessage("user", "请仅回复 OK")))
                .filterIsInstance<ResponseUpdate.Completed>().first()
            "连接成功 · ${(System.nanoTime() - started) / 1_000_000} ms\n${response.text.take(120)}"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { "测试失败：${e.message}" }
    }
    fun send() {
        if (busy.value || importing.value || !ready.value) return
        val id = selectedId.value ?: return
        val draft = drafts.value[id] ?: return
        if (draft.text.isBlank() && draft.attachments.isEmpty()) return
        parseSlashCommand(draft.text)?.let { command ->
            busy.value = true
            launchAction {
                try {
                    val conversation = repository.dao.conversation(id) ?: error("会话不存在")
                    when (command) {
                        is SlashCommand.Invalid -> error(command.message)
                        is SlashCommand.Model -> if (command.value == null) commandPicker.value = "model" else {
                            require(settings.value.providers.any { it.id == conversation.providerId }) { "请先选择站点" }
                            repository.dao.saveConversation(conversation.copy(model = command.value))
                            events.emit("当前会话模型：${command.value}")
                        }
                        is SlashCommand.Effort -> if (command.value == null) commandPicker.value = "effort" else {
                            repository.dao.saveConversation(conversation.copy(reasoningEffort = command.value.takeUnless { it == "default" }))
                            events.emit("当前会话推理强度：${command.value.takeUnless { it == "default" } ?: "跟随站点"}")
                        }
                        SlashCommand.Help -> commandPicker.value = "help"
                    }
                    drafts.update { it + (id to ((it[id] ?: Draft()).copy(text = ""))) }
                } finally { busy.value = false }
            }
            return
        }
        startGeneration(id, draft, null)
    }
    fun regenerate(messageId: String) {
        if (busy.value || !ready.value) return
        val id = selectedId.value ?: return
        startGeneration(id, null, messageId)
    }
    fun stop() { generationJob?.cancel() }

    private fun startGeneration(id: String, draft: Draft?, targetId: String?) {
        busy.value = true
        generationJob = viewModelScope.launch {
            var responseMessage: MessageEntity? = null
            var receivedText = ""
            var receivedReasoning = ""
            var completed = false
            try {
                repository.awaitReady()
                val conversation = repository.dao.conversation(id) ?: error("会话不存在")
                val provider = settings.value.providers.firstOrNull { it.id == conversation.providerId }
                    ?.let { it.copy(defaultModel = conversation.model, models = (it.models + conversation.model).distinct(), reasoningEffort = conversation.reasoningEffort ?: it.reasoningEffort) }
                    ?: error("会话的站点已删除，请在顶部重新选择站点")
                val key = readKey(provider)
                val existing = repository.dao.messages(id)
                val old = targetId?.let { target -> existing.firstOrNull { it.id == target } ?: error("回复不存在") }
                val replacing = old?.let { it.replacesId ?: it.id }
                val original = replacing?.let { target -> existing.first { it.id == target } }
                if (original != null) require(original.id == existing.lastOrNull { it.role == "assistant" && it.replacesId == null }?.id) { "只能重新生成最后一条回复" }
                val history = if (original != null) existing.filter { it.position < original.position } else existing
                var input = withContext(Dispatchers.IO) { repository.input(history) }
                if (draft != null) input = input + InputMessage("user", draft.text.trim(), draft.attachments.map(repository.files::input))
                withContext(Dispatchers.IO) { validateAttachmentBudget(input) }
                val position = (existing.maxOfOrNull { it.position } ?: 0) + 1
                repository.database.withTransaction {
                    if (draft != null) {
                        val user = MessageEntity(UUID.randomUUID().toString(), id, "user", draft.text.trim(), position)
                        repository.dao.insertMessage(user)
                        repository.dao.insertAttachments(draft.attachments.map { it.copy(messageId = user.id) })
                        repository.dao.saveConversation(conversation.copy(title = if (conversation.title == "新对话") draft.text.trim().take(28).ifEmpty { draft.attachments.first().name.take(28) } else conversation.title, updatedAt = System.currentTimeMillis()))
                    }
                    existing.filter { it.replacesId == replacing && replacing != null }.forEach { repository.dao.deleteMessage(it.id) }
                    responseMessage = MessageEntity(UUID.randomUUID().toString(), id, "assistant", position = position + 1, status = "generating", replacesId = replacing, modelName = provider.defaultModel)
                    repository.dao.insertMessage(responseMessage)
                }
                if (draft != null) drafts.update { it + (id to Draft()) }
                val message = responseMessage!!
                generation.value = Generation(id, message.id)
                var lastPersisted = 0L
                suspend fun persistPartial() {
                    generation.value = Generation(id, message.id, receivedText, receivedReasoning)
                    if (System.nanoTime() - lastPersisted > 250_000_000) {
                        repository.dao.updateMessage(message.copy(text = receivedText, reasoning = receivedReasoning))
                        lastPersisted = System.nanoTime()
                    }
                }
                client.generate(provider, key, input, sessionId = id).collect { update ->
                    when (update) {
                        is ResponseUpdate.Text -> {
                            receivedText = update.text
                            persistPartial()
                        }
                        is ResponseUpdate.Reasoning -> { receivedReasoning = update.text; persistPartial() }
                        is ResponseUpdate.Completed -> {
                            receivedText = update.text; receivedReasoning = update.reasoning
                            generation.value = Generation(id, message.id, receivedText, receivedReasoning)
                            repository.finish(message, update); completed = true
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (!completed) withContext(NonCancellable) { responseMessage?.let { repository.dao.updateMessage(it.copy(text = receivedText, reasoning = receivedReasoning, status = "stopped", error = "已停止生成")) } }
            } catch (e: Exception) {
                responseMessage?.let { repository.dao.updateMessage(it.copy(text = receivedText, reasoning = receivedReasoning, status = "failed", error = e.message ?: "生成失败")) }
                events.emit(e.message ?: "生成失败")
            } finally { generation.value = null; busy.value = false }
        }
    }
}
