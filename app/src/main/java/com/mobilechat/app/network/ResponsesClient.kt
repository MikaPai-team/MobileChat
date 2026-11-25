package com.mobilechat.app.network

import com.mobilechat.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.io.FilterOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

internal val wireJson = Json { ignoreUnknownKeys = true }

class ApiException(message: String) : Exception(message)

class ResponsesClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()) {

    suspend fun generateTitle(provider: Provider, apiKey: String, messages: List<InputMessage>, prompt: String, sessionId: String): String {
        val firstMessage = messages.firstOrNull { it.role == "user" } ?: error("缺少用户的第一条消息")
        val response = generate(provider.copy(instructions = prompt, reasoningEffort = "low", showReasoning = false, maxOutputTokens = 1024),
            apiKey, listOf(firstMessage), sessionId = "$sessionId:title").filterIsInstance<ResponseUpdate.Completed>().first()
        val title = response.text.trim().lineSequence().first().trim().removeSurrounding("\"")
            .removeSurrounding("“", "”").take(20)
        require(title.isNotBlank()) { "接口未返回会话标题" }
        return title
    }

    fun generate(provider: Provider, apiKey: String, messages: List<InputMessage>, sessionId: String = UUID.randomUUID().toString(), webSearch: Boolean = false): Flow<ResponseUpdate> = flow {
        provider.validate()
        require(apiKey.all { it.code in 32..126 }) { "API Key 含无效字符，请检查是否粘贴了换行或中文" }
        validateAttachmentBudget(messages)
        val request = Request.Builder().url(provider.url)
            .header("Accept", if (provider.codexCompatibility) "text/event-stream" else "text/event-stream, application/json")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                if (provider.codexCompatibility) {
                    // Match the public rust-v0.98.0 Codex CLI HTTP profile, independently of the app version.
                    header("User-Agent", "codex_cli_rs/0.98.0 (Linux 6.1.0; aarch64) unknown")
                    header("originator", "codex_cli_rs")
                    header("session_id", sessionId)
                }
            }
            .post(ResponsesBody(provider, messages, sessionId, webSearch)).build()
        val call = http.newCall(request)
        // Cancellation must close the socket while execute/read is blocked.
        val cancellation = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.peekBody(64 * 1024).string()
                    throw ApiException(describeError(response.code, body, apiKey, provider.codexCompatibility))
                }
                val body = response.body
                val source = body.source()
                val decoder = ResponseDecoder()
                suspend fun decodeFrame(frame: String, eventName: String) {
                    val obj = parseObject(frame)
                    if ((obj["type"]?.jsonPrimitive?.contentOrNull ?: eventName) in setOf("response.incomplete", "response.failed")) {
                        (obj["response"] as? JsonObject)?.let {
                            decoder.finalReasoning(it).takeIf(String::isNotEmpty)?.let { reasoning -> emit(ResponseUpdate.Reasoning(reasoning)) }
                            decoder.finalText(it).takeIf(String::isNotEmpty)?.let { text -> emit(ResponseUpdate.Text(text)) }
                        }
                    }
                    decoder.accept(obj, eventName)?.let { emit(it) }
                }
                if (body.contentType()?.subtype?.contains("event-stream") == true) {
                    var eventName = ""
                    val data = StringBuilder()
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) {
                            if (data.isNotEmpty()) {
                                val frame = data.toString().removeSuffix("\n")
                                if (frame != "[DONE]") {
                                    decodeFrame(frame, eventName)
                                }
                            }
                            data.setLength(0)
                            eventName = ""
                            if (decoder.terminal) break
                        } else if (line.startsWith("event:")) eventName = line.substringAfter(':').trim()
                        else if (line.startsWith("data:")) {
                            data.append(line.substringAfter(':').removePrefix(" ")).append('\n')
                            if (data.length > 2_000_000) throw ApiException("流式事件过大，无法读取")
                        }
                    }
                    if (!decoder.terminal && data.isNotEmpty() && data.toString().trim() != "[DONE]") {
                        decodeFrame(data.toString(), eventName)
                    }
                    if (!decoder.terminal) throw ApiException("连接已中断，回复尚未完成；已收到的内容已保留")
                } else {
                    val buffer = okio.Buffer()
                    while (source.read(buffer, 8192) != -1L) {
                        currentCoroutineContext().ensureActive()
                        if (buffer.size > 8_000_000) throw ApiException("响应过大，无法读取")
                    }
                    val result = parseObject(buffer.readUtf8())
                    decoder.finalReasoning(result).takeIf(String::isNotEmpty)?.let { emit(ResponseUpdate.Reasoning(it)) }
                    decoder.finalText(result).takeIf(String::isNotEmpty)?.let { emit(ResponseUpdate.Text(it)) }
                    emit(decoder.finish(result))
                }
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw ApiException((e.message ?: "无法连接接口，请检查地址和网络").redact(apiKey))
        } finally { cancellation.cancel() }
    // Deliver the last partial update before propagating an upstream failure.
    }.flowOn(Dispatchers.IO).buffer(0)
}

internal fun parseObject(value: String): JsonObject = try {
    wireJson.parseToJsonElement(value).jsonObject
} catch (_: Exception) { throw ApiException("接口返回了无效 JSON，请检查是否支持 Responses API") }

internal fun String.redact(key: String): String = if (key.isEmpty()) this else replace(key, "[已隐藏]")
internal fun describeError(status: Int, body: String, key: String, codexCompatibility: Boolean = false): String {
    val root = runCatching { parseObject(body) }.getOrNull()
    val error = root?.get("error")
    val detail = (error as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?: (error as? JsonPrimitive)?.contentOrNull
        ?: (root?.get("message") as? JsonPrimitive)?.contentOrNull
        ?: body.takeIf { root == null && it.isNotBlank() }
    val clientRejected = status in setOf(401, 403) &&
        (body.contains("unauthorized client", ignoreCase = true) || body.contains("unsupported client", ignoreCase = true))
    val prefix = if (clientRejected) {
        if (codexCompatibility) "站点仍拒绝 Codex 兼容请求，请确认站点的客户端权限"
        else "站点限制客户端类型，请在站点配置中开启「Codex 兼容」"
    } else when (status) {
        401, 403 -> "认证失败，请检查 API Key 和站点权限"
        404 -> "接口或模型不存在，请检查完整地址及模型名称"
        413 -> "附件或请求过大，请减少附件或新建会话"
        429 -> "请求受限或额度不足，请稍后重试"
        in 500..599 -> "中转站服务异常，请稍后重试"
        in 300..399 -> "接口发生重定向，请填写最终 Responses 地址"
        else -> "请求失败（HTTP $status），请检查模型能力、参数和上下文长度"
    }
    return prefix + (detail?.takeIf { it.isNotBlank() }?.let { "\n${it.redact(key).take(800)}" } ?: "")
}

internal class ResponsesBody(private val provider: Provider, private val messages: List<InputMessage>, private val sessionId: String, private val webSearch: Boolean = false) : RequestBody() {
    override fun contentType() = (if (provider.codexCompatibility) "application/json" else "application/json; charset=utf-8").toMediaType()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        validateAttachmentBudget(messages)
        val parameters = buildJsonObject {
            put("model", provider.defaultModel); put("stream", true); put("store", false)
            if (provider.codexCompatibility || provider.instructions.isNotBlank()) put("instructions", provider.instructions)
            if (!provider.codexCompatibility) provider.maxOutputTokens?.let { put("max_output_tokens", it) }
            val requestSummary = provider.showReasoning && provider.reasoningEffort != "none"
            if (provider.reasoningEffort != null || requestSummary) putJsonObject("reasoning") {
                provider.reasoningEffort?.let { put("effort", it) }
                if (requestSummary) put("summary", "auto")
            }
            if (provider.codexCompatibility || webSearch) {
                putJsonArray("tools") {
                    if (webSearch) addJsonObject { put("type", "web_search") }
                }
                put("tool_choice", "auto")
                putJsonArray("include") {
                    if (provider.codexCompatibility && (provider.reasoningEffort != null || requestSummary)) add("reasoning.encrypted_content")
                    if (webSearch) add("web_search_call.action.sources")
                }
            }
            if (provider.codexCompatibility) {
                put("parallel_tool_calls", true)
                if (provider.reasoningEffort == null && !requestSummary) put("reasoning", JsonNull)
                put("prompt_cache_key", sessionId)
            }
        }.toString().removeSuffix("}")
        sink.writeUtf8("$parameters,\"input\":[")
        messages.forEachIndexed { index, message ->
            if (index > 0) sink.writeUtf8(",")
            sink.writeUtf8(if (provider.codexCompatibility) "{\"type\":\"message\"," else "{")
            sink.writeUtf8("\"role\":${JsonPrimitive(message.role)},\"content\":[")
            var separator = false
            fun part(text: String) {
                if (separator) sink.writeUtf8(",")
                sink.writeUtf8(buildJsonObject { put("type", if (message.role == "assistant") "output_text" else "input_text"); put("text", text) }.toString())
                separator = true
            }
            if (message.text.isNotEmpty()) part(message.text)
            message.attachments.forEach { attachment ->
                if (attachment.kind == "text") part("文件：${attachment.name}\n${readTextFile(attachment)}")
                else {
                    if (separator) sink.writeUtf8(",")
                    if (attachment.kind == "image") sink.writeUtf8("{\"type\":\"input_image\",\"image_url\":\"data:${attachment.mime};base64,")
                    else sink.writeUtf8("{\"type\":\"input_file\",\"filename\":${JsonPrimitive(attachment.name)},\"file_data\":\"data:${attachment.mime};base64,")
                    val nonClosing = object : FilterOutputStream(sink.outputStream()) {
                        override fun close() { flush() }
                        override fun write(bytes: ByteArray, off: Int, len: Int) { out.write(bytes, off, len) }
                    }
                    Base64.getEncoder().wrap(nonClosing).use { encoded -> attachment.file.inputStream().use { it.copyTo(encoded) } }
                    sink.writeUtf8("\"}")
                    separator = true
                }
            }
            sink.writeUtf8("]}")
        }
        sink.writeUtf8("]}")
    }
}

internal fun readTextFile(attachment: InputAttachment): String {
    require(attachment.file.length() <= TEXT_LIMIT) { "文本文件不能超过 1 MB" }
    val bytes = attachment.file.readBytes()
    val charset = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    return try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: Exception) {
        Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}
