package com.mobilechat.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilechat.app.ChatViewModel
import com.mobilechat.app.MobileChatApplication
import com.mobilechat.app.data.MessageEntity
import com.mobilechat.app.data.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LatestMessageButtonTest {
    @get:Rule val ui = createComposeRule()

    @Test fun showingAndHidingButtonKeepsComposerHeightAndReturnsToLatest() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileChatApplication
        val repository = application.repository
        val id = runBlocking {
            val id = repository.newConversation(Provider())
            repeat(40) { repository.dao.insertMessage(MessageEntity("$id:$it", id, "assistant", "回归检查消息 $it", it.toLong())) }
            id
        }
        lateinit var vm: ChatViewModel
        ui.runOnUiThread { vm = ChatViewModel(application, SavedStateHandle(mapOf("conversation" to id))) }
        try {
            ui.setContent { MobileTheme("light") { ChatScreen(vm, {}, {}) } }
            ui.waitUntil(10_000) { vm.messages.value.size == 40 && vm.conversations.value.any { it.id == id } }
            ui.waitForIdle()
            val composer = ui.onNodeWithTag("chat-composer")
            val before = composer.fetchSemanticsNode().boundsInRoot.height
            ui.onNodeWithTag("chat-messages").performTouchInput { swipeDown(durationMillis = 350) }
            ui.onNodeWithText("回到最新消息").assertIsDisplayed()
            val visible = composer.fetchSemanticsNode().boundsInRoot.height
            assertEquals(before, visible, 0.5f)
            ui.onNodeWithText("回到最新消息").performClick()
            ui.onNodeWithText("回到最新消息").assertDoesNotExist()
            val after = composer.fetchSemanticsNode().boundsInRoot.height
            assertEquals(before, after, 0.5f)
            File(requireNotNull(application.getExternalFilesDir(null)), "latest-button-regression.txt")
                .writeText("composerBefore=$before\ncomposerVisible=$visible\ncomposerAfter=$after\n")
        } finally {
            ui.runOnUiThread { vm.select(null) }
            runBlocking { repository.deleteConversation(id) }
        }
    }
}
