package com.mobilechat.app.data

val reasoningEfforts = listOf("none", "minimal", "low", "medium", "high", "xhigh")

sealed interface SlashCommand {
    data class Model(val value: String?) : SlashCommand
    data class Effort(val value: String?) : SlashCommand
    data object Help : SlashCommand
    data class Invalid(val message: String) : SlashCommand
}

fun parseSlashCommand(text: String): SlashCommand? {
    val pieces = text.trim().split(Regex("\\s+"), limit = 2)
    val value = pieces.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    return when (pieces.first().lowercase()) {
        "/model" -> if (value?.any(Char::isWhitespace) == true) SlashCommand.Invalid("用法：/model 模型名称") else SlashCommand.Model(value)
        "/effort" -> when {
            value == null -> SlashCommand.Effort(null)
            value.lowercase() in reasoningEfforts + "default" -> SlashCommand.Effort(value.lowercase())
            else -> SlashCommand.Invalid("推理强度可选：default、${reasoningEfforts.joinToString("、")}")
        }
        "/help" -> SlashCommand.Help
        else -> null
    }
}
