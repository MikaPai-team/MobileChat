package com.mobilechat.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalDataTest {
    private lateinit var database: ChatDatabase
    private lateinit var repository: ChatRepository
    private lateinit var directory: File
    private lateinit var context: Context
    private val alias = "mobilechat_test_${UUID.randomUUID()}"
    @Before fun prepare() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "local-data-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
        repository = ChatRepository(context, database)
        repository.awaitReady()
    }
    @After fun clean() {
        database.close()
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }
    @Test fun updatingConversationPreservesMessagesAndAttachments() = runBlocking {
        val id = repository.newConversation(Provider())
        val user = MessageEntity("user", id, "user", "你好", 1)
        repository.dao.insertMessage(user)
        repository.dao.insertAttachments(listOf(AttachmentEntity(UUID.randomUUID().toString(), user.id, "test.pdf", "application/pdf", "pdf", 5)))
        val before = repository.dao.conversation(id)!!
        repository.dao.saveConversation(before.copy(title = "改名", model = "gpt-5.6-sol", providerId = "other"))
        assertEquals(1, repository.dao.messages(id).size)
        assertEquals(1, repository.dao.attachments(user.id).size)
        assertEquals("gpt-5.6-sol", repository.dao.conversation(id)!!.model)
    }
    @Test fun regenerationPreservesOriginalUntilSuccessfulReplacement() = runBlocking {
        val id = repository.newConversation(Provider())
        val old = MessageEntity("old", id, "assistant", "原回复", 2)
        val stage = MessageEntity("stage", id, "assistant", "部分新回复", 3, "generating", replacesId = old.id)
        repository.dao.insertMessage(old); repository.dao.insertMessage(stage)
        repository.dao.markInterrupted()
        assertEquals("原回复", repository.dao.messages(id).first().text)
        repository.finish(stage, ResponseUpdate.Completed("新回复"))
        val after = repository.dao.messages(id)
        assertEquals(1, after.size); assertEquals("old", after.single().id); assertEquals("新回复", after.single().text)
    }
    @Test fun deletingConversationCleansOnlyItsAttachments() = runBlocking {
        val id = repository.newConversation(Provider())
        val otherId = repository.newConversation(Provider())
        val attachmentId = UUID.randomUUID().toString()
        val otherAttachment = UUID.randomUUID().toString()
        for ((thread, file) in listOf(id to attachmentId, otherId to otherAttachment)) {
            val user = MessageEntity(UUID.randomUUID().toString(), thread, "user", "附件", 1)
            repository.dao.insertMessage(user)
            repository.dao.insertAttachments(listOf(AttachmentEntity(file, user.id, "note.txt", "text/plain", "text", 4)))
            repository.files.file(file).writeText("text")
        }
        repository.deleteConversation(id)
        assertFalse(repository.files.file(attachmentId).exists()); assertTrue(repository.files.file(otherAttachment).exists())
        assertEquals(1, repository.dao.fileIds().size)
    }
    @Test fun keystoreCiphertextRoundTripAndTamperDetection() {
        val vault = KeyVault(alias)
        val encrypted = vault.encrypt("unit-test-secret")
        assertFalse(encrypted.contains("unit-test-secret"))
        assertEquals("unit-test-secret", vault.decrypt(encrypted))
        assertNotEquals(encrypted, vault.encrypt("unit-test-secret"))
        assertTrue(runCatching { vault.decrypt("invalid") }.isFailure)
        assertEquals("", vault.decrypt(vault.encrypt("")))
    }
    @Test fun startupInterruptionRetainsPartialText() = runBlocking {
        val id = repository.newConversation(Provider())
        repository.dao.insertMessage(MessageEntity("partial", id, "assistant", "已生成内容", 1, "generating"))
        repository.dao.markInterrupted()
        val message = repository.dao.messages(id).single()
        assertEquals("interrupted", message.status); assertEquals("已生成内容", message.text)
    }
}
