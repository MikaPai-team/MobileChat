package com.mobilechat.app.data

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID

val defaultModels = listOf("gpt-6.1-sol", "gpt-5.6-sol")

@Serializable
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "自定义站点",
    val url: String = "https://api.openai.com/v1/responses",
    val models: List<String> = defaultModels,
    val defaultModel: String = defaultModels.first(),
    val instructions: String = "",
    val maxOutputTokens: Int? = null,
    val reasoningEffort: String? = null,
    val encryptedKey: String = "",
    val codexCompatibility: Boolean = false,
    val showReasoning: Boolean = true,
) {
    fun validate() {
        require(name.isNotBlank()) { "请填写站点名称" }
        val parsed = url.toHttpUrlOrNull()
        require(parsed != null && parsed.fragment == null && parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "请填写完整的 HTTP(S) Responses 接口地址，不含用户名、密码或片段"
        }
        require(models.isNotEmpty() && models.all { it.isNotBlank() } && defaultModel in models) { "请设置模型列表及默认模型" }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "最大输出 token 数须大于 0" }
    }
}

@Serializable
data class AppSettings(val providers: List<Provider> = emptyList(), val theme: String = "system", val glass: Boolean = false)

data class InputMessage(val role: String, val text: String, val attachments: List<InputAttachment> = emptyList())
data class InputAttachment(val name: String, val mime: String, val file: File, val kind: String)

sealed interface ResponseUpdate {
    data class Text(val text: String) : ResponseUpdate
    data class Reasoning(val text: String) : ResponseUpdate
    data class WebSearch(val status: String) : ResponseUpdate
    data class Completed(val text: String, val inputTokens: Int? = null, val outputTokens: Int? = null, val reasoning: String = "") : ResponseUpdate
}

const val FILE_LIMIT = 50_000_000L
const val TEXT_LIMIT = 1_000_000L

fun validateAttachmentBudget(messages: List<InputMessage>) {
    val files = messages.flatMap { it.attachments }
    require(files.all { it.file.isFile && it.file.length() > 0 }) { "附件不存在或为空，请重新选择" }
    require(files.all { it.file.length() < FILE_LIMIT } && files.sumOf { it.file.length() } < FILE_LIMIT) {
        "本次请求包含历史的附件总大小须小于 50 MB，请新建会话或减少附件"
    }
    require(files.filter { it.kind == "text" }.all { it.file.length() <= TEXT_LIMIT }) { "文本文件不能超过 1 MB" }
}
