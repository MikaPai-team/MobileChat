package com.mobilechat.app.network

import com.mobilechat.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.util.Base64
import java.util.concurrent.TimeUnit

class ResponsesClientTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }
    private fun provider() = Provider(url = server.url("/custom/responses?route=test").toString())
    private fun completed(text: String) = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(text)}}]}],"usage":{"input_tokens":12,"output_tokens":4}}"""
    private fun jsonReply(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(completed(text))
    private fun event(type: String, data: String) = "event: $type\r\ndata: $data\r\n\r\n"

    @Test fun exactEndpointModelsAuthenticationAndOptionalParameters() = runBlocking {
        server.enqueue(jsonReply("你好"))
        val station = provider().copy(defaultModel = "my/custom-model", models = listOf("my/custom-model"), instructions = "中文回答", maxOutputTokens = 123, reasoningEffort = "high")
        val updates = ResponsesClient().generate(station, "test-key", listOf(InputMessage("user", "问题"), InputMessage("assistant", "前文"), InputMessage("user", "继续"))).toList()
        val request = server.takeRequest()
        assertEquals("/custom/responses?route=test", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = parseObject(request.body.readUtf8())
        assertEquals("my/custom-model", body["model"]!!.jsonPrimitive.content)
        assertEquals(false, body["store"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(123, body["max_output_tokens"]!!.jsonPrimitive.int)
        assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(3, body["input"]!!.jsonArray.size)
        assertEquals("output_text", body["input"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("previous_response_id"))
        assertEquals(ResponseUpdate.Completed("你好", 12, 4), updates.last())
    }

    @Test fun blankOptionsAndKeyAreOmitted() = runBlocking {
        server.enqueue(jsonReply("OK"))
        ResponsesClient().generate(provider().copy(showReasoning = false), "", listOf(InputMessage("user", "你好"))).toList()
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("originator")); assertNull(request.getHeader("session_id"))
        val body = parseObject(request.body.readUtf8())
        assertFalse(body.containsKey("instructions")); assertFalse(body.containsKey("reasoning")); assertFalse(body.containsKey("max_output_tokens"))
        assertFalse(body.containsKey("tools")); assertFalse(body.containsKey("prompt_cache_key"))
        assertEquals("gpt-6.1-sol", body["model"]!!.jsonPrimitive.content)
    }

    @Test fun codexProfileUsesConversationIdentityAndTypedHistory() = runBlocking {
        server.enqueue(jsonReply("继续回答"))
        val station = provider().copy(codexCompatibility = true, instructions = "中文回答", maxOutputTokens = 123, reasoningEffort = "high")
        ResponsesClient().generate(station, "test-key", listOf(InputMessage("assistant", "前文"), InputMessage("user", "继续")), sessionId = "conversation-123").toList()
        val request = server.takeRequest()
        assertEquals("/custom/responses?route=test", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.getHeader("User-Agent").orEmpty().startsWith("codex_cli_rs/"))
        assertEquals("codex_cli_rs", request.getHeader("originator"))
        assertEquals("conversation-123", request.getHeader("session_id"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        val body = parseObject(request.body.readUtf8())
        assertEquals("conversation-123", body["prompt_cache_key"]!!.jsonPrimitive.content)
        assertEquals("中文回答", body["instructions"]!!.jsonPrimitive.content)
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertFalse(body.containsKey("max_output_tokens"))
        assertFalse(body.containsKey("previous_response_id"))
        assertTrue(body["tools"]!!.jsonArray.isEmpty())
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertTrue(body["parallel_tool_calls"]!!.jsonPrimitive.boolean)
        assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("reasoning.encrypted_content", body["include"]!!.jsonArray.single().jsonPrimitive.content)
        val input = body["input"]!!.jsonArray
        assertEquals("message", input.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("assistant", input.first().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("output_text", input.first().jsonObject["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("input_text", input.last().jsonObject["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun codexBlankOptionsKeepRequiredFieldsAndAllowEmptyKey() = runBlocking {
        server.enqueue(jsonReply("OK"))
        ResponsesClient().generate(provider().copy(codexCompatibility = true, showReasoning = false), "", listOf(InputMessage("user", "你好"))).toList()
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        val body = parseObject(request.body.readUtf8())
        assertEquals("", body["instructions"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["reasoning"])
        assertTrue(body["include"]!!.jsonArray.isEmpty())
        assertEquals(request.getHeader("session_id"), body["prompt_cache_key"]!!.jsonPrimitive.content)
        assertTrue(request.getHeader("session_id").orEmpty().isNotBlank())
    }

    @Test fun providerSettingsRemainReadableBeforeAndAfterAddingCodexOption() {
        val legacy = wireJson.decodeFromString<AppSettings>("""{"providers":[{"id":"legacy","encryptedKey":"existing-encrypted-key"}]}""")
        assertFalse(legacy.providers.single().codexCompatibility)
        assertTrue(legacy.providers.single().showReasoning)
        assertEquals("existing-encrypted-key", legacy.providers.single().encryptedKey)
        val updated = legacy.copy(providers = legacy.providers.map { it.copy(codexCompatibility = true) })
        val restored = wireJson.decodeFromString<AppSettings>(wireJson.encodeToString(updated))
        assertEquals(updated, restored)
    }

    @Test fun arbitraryBinaryFilesAndCameraPhotosKeepTheirBytes() = runBlocking {
        val binary = folder.newFile("任意文件.zip").apply { writeBytes(byteArrayOf(0, -1, 12, 34)) }
        val photo = folder.newFile("photo.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32, 0)) }
        server.enqueue(jsonReply("已收到"))
        ResponsesClient().generate(provider(), "", listOf(InputMessage("user", "", listOf(
            InputAttachment(binary.name, "application/zip", binary, "file"),
            InputAttachment(photo.name, "image/jpeg", photo, "image")
        )))).toList()
        val parts = parseObject(server.takeRequest().body.readUtf8())["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("input_file", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(binary.name, parts[0].jsonObject["filename"]!!.jsonPrimitive.content)
        assertArrayEquals(binary.readBytes(), Base64.getDecoder().decode(parts[0].jsonObject["file_data"]!!.jsonPrimitive.content.substringAfter(',')))
        assertEquals("input_image", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertArrayEquals(photo.readBytes(), Base64.getDecoder().decode(parts[1].jsonObject["image_url"]!!.jsonPrimitive.content.substringAfter(',')))
    }
    @Test fun summaryIsRequestedAndDeliveredBeforeAnswerEvenOnInterruptedStreams() = runBlocking {
        val stream = event("response.reasoning_summary_text.delta", """{"delta":"检查文件"}""") +
            event("response.output_text.delta", """{"delta":"部分答案"}""")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream))
        val updates = mutableListOf<ResponseUpdate>()
        val failure = runCatching { ResponsesClient().generate(provider(), "", listOf(InputMessage("user", "问题"))).collect { updates += it } }.exceptionOrNull()
        val body = parseObject(server.takeRequest().body.readUtf8())
        assertEquals("auto", body["reasoning"]!!.jsonObject["summary"]!!.jsonPrimitive.content)
        assertEquals(listOf(ResponseUpdate.Reasoning("检查文件"), ResponseUpdate.Text("部分答案")), updates)
        assertTrue(failure?.message.orEmpty().contains("中断"))
    }
    @Test fun noneEffortDoesNotRequestSummary() = runBlocking {
        server.enqueue(jsonReply("OK"))
        ResponsesClient().generate(provider().copy(reasoningEffort = "none"), "", listOf(InputMessage("user", "问题"))).toList()
        val reasoning = parseObject(server.takeRequest().body.readUtf8())["reasoning"]!!.jsonObject
        assertEquals("none", reasoning["effort"]!!.jsonPrimitive.content)
        assertFalse(reasoning.containsKey("summary"))
    }

    @Test fun clientRestrictionHasSpecificHintAndRedactsSecretWithoutRetry() = runBlocking {
        val detail = "unauthorized client detected: unit-test-secret"
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":${JsonPrimitive(detail)}}}"""))
        val failure = runCatching { ResponsesClient().generate(provider(), "unit-test-secret", listOf(InputMessage("user", "问题"))).toList() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Codex 兼容"))
        assertFalse(failure?.message.orEmpty().contains("认证失败"))
        assertFalse(failure?.message.orEmpty().contains("unit-test-secret"))
        assertEquals(1, server.requestCount)
        assertTrue(describeError(401, detail, "unit-test-secret", codexCompatibility = true).startsWith("站点仍拒绝"))
        assertFalse(describeError(401, detail, "unit-test-secret", codexCompatibility = true).contains("unit-test-secret"))
    }

    @Test fun attachmentsProduceValidStreamingJsonAndPreserveContents() = runBlocking {
        val image = folder.newFile("image.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val pdf = folder.newFile("paper.pdf").apply { writeText("%PDF-1.7\n测试") }
        val text = folder.newFile("笔记.md").apply { writeText("# 中文\n内容") }
        server.enqueue(jsonReply("分析完成"))
        ResponsesClient().generate(provider(), "", listOf(InputMessage("user", "看附件", listOf(
            InputAttachment("image.png", "image/png", image, "image"), InputAttachment("paper.pdf", "application/pdf", pdf, "pdf"),
            InputAttachment("笔记.md", "text/plain", text, "text"))))).toList()
        val parts = parseObject(server.takeRequest().body.readUtf8())["input"]!!.jsonArray.first().jsonObject["content"]!!.jsonArray
        assertEquals(4, parts.size)
        assertArrayEquals(image.readBytes(), Base64.getDecoder().decode(parts[1].jsonObject["image_url"]!!.jsonPrimitive.content.substringAfter(',')))
        assertArrayEquals(pdf.readBytes(), Base64.getDecoder().decode(parts[2].jsonObject["file_data"]!!.jsonPrimitive.content.substringAfter(',')))
        assertEquals("paper.pdf", parts[2].jsonObject["filename"]!!.jsonPrimitive.content)
        assertTrue(parts[3].jsonObject["text"]!!.jsonPrimitive.content.contains("# 中文\n内容"))
    }

    @Test fun fragmentedChineseMultilineFramesUnknownEventsAndFinalTextDoNotDuplicate() = runBlocking {
        val stream = ": keepalive\r\n\r\n" + event("response.created", "{\"type\":\"response.created\"}") +
            "event: response.output_text.delta\r\ndata: {\"type\":\"response.output_text.delta\",\r\ndata: \"delta\":\"你好\"}\r\n\r\n" +
            event("response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\"世界\"}") +
            event("response.output_text.done", "{\"type\":\"response.output_text.done\",\"text\":\"你好世界\"}") +
            event("response.completed", "{\"type\":\"response.completed\",\"response\":${completed("你好世界")}}")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream).throttleBody(1, 1, TimeUnit.MILLISECONDS))
        val results = ResponsesClient().generate(provider(), "", listOf(InputMessage("user", "你好"))).toList()
        assertEquals("你好", (results.first() as ResponseUpdate.Text).text)
        assertEquals("你好世界", (results.last() as ResponseUpdate.Completed).text)
        assertEquals(1, results.filterIsInstance<ResponseUpdate.Completed>().size)
    }

    @Test fun prematureEndRetainsPartialUpdatesAndReportsFailure() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(event("response.output_text.delta", "{\"delta\":\"部分回复\"}")))
        val results = mutableListOf<ResponseUpdate>()
        val failure = runCatching { ResponsesClient().generate(provider(), "", listOf(InputMessage("user", "问题"))).collect { results += it } }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("中断"))
        assertEquals(ResponseUpdate.Text("部分回复"), results.single())
    }

    @Test fun cancellationClosesBlockedRequestPromptly() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.Default) { ResponsesClient().generate(provider(), "", listOf(InputMessage("user", "问题"))).collect() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test fun authenticationErrorRedactsKeyAndDoesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad unit-test-secret"}}"""))
        val error = runCatching { ResponsesClient().generate(provider(), "unit-test-secret", listOf(InputMessage("user", "问题"))).toList() }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("认证失败")); assertFalse(error?.message.orEmpty().contains("unit-test-secret"))
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectsAreNotFollowedWithCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        val error = runCatching { ResponsesClient().generate(provider(), "test-key", listOf(InputMessage("user", "问题"))).toList() }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("重定向")); assertEquals(1, server.requestCount)
    }

    @Test fun contextIncludesOldAttachmentsWhenChangingStationOrModel() = runBlocking {
        server.enqueue(jsonReply("继续"))
        val file = folder.newFile("previous.txt").apply { writeText("之前的附件") }
        ResponsesClient().generate(provider().copy(defaultModel = "gpt-5.6-sol"), "", listOf(
            InputMessage("user", "原问题", listOf(InputAttachment("previous.txt", "text/plain", file, "text"))),
            InputMessage("assistant", "原回复"), InputMessage("user", "继续"))).toList()
        val body = parseObject(server.takeRequest().body.readUtf8())
        assertEquals("gpt-5.6-sol", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["input"].toString().contains("之前的附件"))
    }

    @Test fun incompleteJsonPreservesTextBeforeReportingOutputLimit() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(completed("部分内容").replace("\"status\":\"completed\"", "\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}")))
        val results = mutableListOf<ResponseUpdate>()
        val failure = runCatching { ResponsesClient().generate(provider(), "", listOf(InputMessage("user", "问题"))).collect { results += it } }.exceptionOrNull()
        assertEquals(ResponseUpdate.Text("部分内容"), results.single())
        assertTrue(failure?.message.orEmpty().contains("最大输出"))
    }

    @Test fun malformedKeyNeverAppearsInHeaderValidationError() = runBlocking {
        val secret = "unit-test-key\ninvalid"
        val failure = runCatching { ResponsesClient().generate(provider(), secret, listOf(InputMessage("user", "问题"))).toList() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("无效字符")); assertFalse(failure?.message.orEmpty().contains("unit-test-key"))
        assertEquals(0, server.requestCount)
    }

    @Test fun incompleteTerminalFrameWithoutBlankLinePreservesFinalText() = runBlocking {
        val response = completed("未完整的文本").replace("\"status\":\"completed\"", "\"status\":\"incomplete\"")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: response.incomplete\ndata: {\"response\":$response}\n"))
        val results = mutableListOf<ResponseUpdate>()
        val failure = runCatching { ResponsesClient().generate(provider(), "", listOf(InputMessage("user", "问题"))).collect { results += it } }.exceptionOrNull()
        assertEquals(ResponseUpdate.Text("未完整的文本"), results.single())
        assertNotNull(failure)
    }
}
