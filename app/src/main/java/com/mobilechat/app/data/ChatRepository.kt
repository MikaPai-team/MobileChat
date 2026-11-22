package com.mobilechat.app.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.*
import java.util.UUID

class ChatRepository(context: Context, val database: ChatDatabase = ChatDatabase.create(context)) {
    val dao = database.dao()
    val settings = SettingsStore(context)
    val vault = KeyVault()
    val files = AttachmentStore(context)
    private val initialization = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
        dao.markInterrupted()
        files.cleanupOrphans(dao.fileIds().toSet())
    }
    suspend fun awaitReady() { initialization.await() }
    suspend fun newConversation(provider: Provider): String {
        awaitReady()
        val id = UUID.randomUUID().toString()
        dao.saveConversation(ConversationEntity(id, "新对话", provider.id, provider.defaultModel))
        return id
    }
    suspend fun deleteConversation(id: String) {
        awaitReady()
        val filesToDelete = dao.messages(id).flatMap { dao.attachments(it.id) }.map { it.id }
        dao.deleteConversation(id)
        withContext(Dispatchers.IO) { filesToDelete.forEach(files::delete) }
    }
    suspend fun input(messages: List<MessageEntity>): List<InputMessage> = messages.filter { it.replacesId == null && (it.text.isNotBlank() || it.role == "user") }
        .map { InputMessage(it.role, it.text, dao.attachments(it.id).map(files::input)) }
    suspend fun finish(message: MessageEntity, update: ResponseUpdate.Completed) {
        database.withTransaction {
            val updated = message.copy(text = update.text, reasoning = update.reasoning, status = "completed", error = null, inputTokens = update.inputTokens, outputTokens = update.outputTokens)
            if (message.replacesId != null) {
                val original = dao.messages(message.conversationId).first { it.id == message.replacesId }
                dao.updateMessage(updated.copy(id = original.id, position = original.position, replacesId = null))
                dao.deleteMessage(message.id)
            } else dao.updateMessage(updated)
            dao.conversation(message.conversationId)?.let { dao.saveConversation(it.copy(updatedAt = System.currentTimeMillis())) }
        }
    }
}
