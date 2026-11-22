package com.mobilechat.app.data

import org.junit.Assert.*
import org.junit.Test

class SlashCommandTest {
    @Test fun commandsAcceptSpacingAndCaseWithoutTreatingPathsAsCommands() {
        assertEquals(SlashCommand.Model(null), parseSlashCommand(" /model "))
        assertEquals(SlashCommand.Model("custom/model-v2"), parseSlashCommand("/MODEL   custom/model-v2"))
        assertEquals(SlashCommand.Effort("high"), parseSlashCommand("/effort HIGH"))
        assertEquals(SlashCommand.Effort("default"), parseSlashCommand("/effort default"))
        assertEquals(SlashCommand.Help, parseSlashCommand("/help"))
        assertNull(parseSlashCommand("请解释 /model"))
        assertNull(parseSlashCommand("/models"))
        assertNull(parseSlashCommand("/home/user/photo.jpg"))
    }
    @Test fun invalidValuesAreReportedBeforeChangingSettings() {
        assertTrue(parseSlashCommand("/effort unknown") is SlashCommand.Invalid)
        assertTrue(parseSlashCommand("/model two models") is SlashCommand.Invalid)
        reasoningEfforts.forEach { assertEquals(SlashCommand.Effort(it), parseSlashCommand("/effort $it")) }
    }
}
