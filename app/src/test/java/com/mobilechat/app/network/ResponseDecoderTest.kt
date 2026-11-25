package com.mobilechat.app.network

import com.mobilechat.app.data.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class ResponseDecoderTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun reasoningStreamsSeparatelyAndDoneEventsDoNotDuplicateIt() {
        val decoder = ResponseDecoder()
        assertEquals(ResponseUpdate.Reasoning("先分析"), decoder.accept(parseObject("""{"type":"response.reasoning_summary_text.delta","delta":"先分析","output_index":0,"summary_index":0}""")))
        decoder.accept(parseObject("""{"type":"response.reasoning_summary_text.delta","delta":"附件","output_index":0,"summary_index":0}"""))
        assertEquals(ResponseUpdate.Reasoning("先分析附件"), decoder.accept(parseObject("""{"type":"response.reasoning_summary_text.done","text":"先分析附件","output_index":0,"summary_index":0}""")))
        decoder.accept(parseObject("""{"type":"response.reasoning_summary_text.delta","delta":"再回答","output_index":0,"summary_index":1}"""))
        assertEquals(ResponseUpdate.Text("正式回答"), decoder.accept(parseObject("""{"type":"response.output_text.delta","delta":"正式回答","output_index":1}""")))
        val final = decoder.finish(parseObject("""{"status":"completed","output":[]}"""))
        assertEquals("正式回答", final.text)
        assertEquals("先分析附件\n\n再回答", final.reasoning)
    }
    @Test fun completeJsonAndItemDoneExposeOnlyReturnedReasoningText() {
        val decoder = ResponseDecoder()
        val item = """{"type":"reasoning","encrypted_content":"must-not-be-shown","summary":[{"type":"summary_text","text":"检查资料"}]}"""
        assertEquals(ResponseUpdate.Reasoning("检查资料"), decoder.accept(parseObject("""{"type":"response.output_item.done","output_index":0,"item":$item}""")))
        val final = decoder.finish(parseObject("""{"status":"completed","output":[$item,{"type":"message","content":[{"type":"output_text","text":"答案"}]}]}"""))
        assertEquals("检查资料", final.reasoning)
        assertEquals("答案", final.text)
        assertFalse(final.reasoning.contains("must-not-be-shown"))
    }
    @Test fun explicitReasoningTextAndSummaryAreNotConcatenatedTwice() {
        val decoder = ResponseDecoder()
        assertEquals(ResponseUpdate.Reasoning("返回的进展"), decoder.accept(parseObject("""{"type":"response.reasoning_text.delta","delta":"返回的进展"}""")))
        assertEquals(ResponseUpdate.Reasoning("摘要"), decoder.accept(parseObject("""{"type":"response.reasoning_summary_text.delta","delta":"摘要"}""")))
    }
    @Test fun refusalAndMultipleTextPartsAreHandled() {
        val decoder = ResponseDecoder()
        decoder.accept(parseObject("""{"type":"response.refusal.delta","delta":"无法","output_index":0} """))
        decoder.accept(parseObject("""{"type":"response.refusal.delta","delta":"提供","output_index":0} """))
        decoder.accept(parseObject("""{"type":"response.output_text.delta","delta":"帮助","output_index":1} """))
        assertEquals("无法提供\n\n帮助", decoder.finish(parseObject("{\"status\":\"completed\",\"output\":[]}")).text)
    }
    @Test fun segmentBoundariesMatchStreamingAndFinalOutputWithoutDuplicatingDoneEvents() {
        val decoder = ResponseDecoder()
        decoder.accept(parseObject("""{"type":"response.output_text.delta","output_index":0,"delta":"第一"}"""))
        decoder.accept(parseObject("""{"type":"response.output_text.delta","output_index":0,"delta":"段"}"""))
        decoder.accept(parseObject("""{"type":"response.output_text.done","output_index":0,"text":"第一段"}"""))
        assertEquals(ResponseUpdate.Text("第一段\n\n第二段"), decoder.accept(parseObject(
            """{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"第二段"}""")))
        val response = parseObject("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"第一段"}]},{"type":"message","content":[{"type":"output_text","text":"第二段"}]}]}""")
        assertEquals("第一段\n\n第二段", decoder.finish(response).text)
    }
    @Test fun contentPartsAndExistingParagraphBreaksArePreserved() {
        val decoder = ResponseDecoder()
        decoder.accept(parseObject("""{"type":"response.content_part.done","part":{"type":"output_text","text":"第一段\n\n"},"content_index":0}"""))
        assertEquals(ResponseUpdate.Text("第一段\n\n第二段"), decoder.accept(parseObject(
            """{"type":"response.content_part.done","part":{"type":"output_text","text":"第二段"},"content_index":1}""")))
        assertEquals("第一段\n\n第二段", decoder.finish(parseObject("""{"status":"completed","output":[]}""")).text)
    }
    @Test fun incompleteResponseNeverBecomesSuccess() {
        val decoder = ResponseDecoder()
        val error = runCatching { decoder.finish(parseObject("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}""")) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("最大输出")); assertFalse(decoder.terminal)
    }
    @Test fun failedEventAndInvalidJsonAreActionableErrors() {
        val error = runCatching { ResponseDecoder().accept(parseObject("""{"type":"response.failed","response":{"status":"failed","error":{"message":"模型不支持附件"}}}""")) }.exceptionOrNull()
        assertEquals("模型不支持附件", error?.message)
        assertTrue(runCatching { parseObject("<html>bad gateway</html>") }.exceptionOrNull() is ApiException)
    }
    @Test fun unsupportedOutputIsNotInventedAsText() {
        assertTrue(runCatching { ResponseDecoder().finish(parseObject("""{"status":"completed","output":[{"type":"reasoning"}]}""")) }.isFailure)
    }
    @Test fun webSearchProgressAndCitationsReachTheConversationText() {
        val decoder = ResponseDecoder()
        assertEquals(ResponseUpdate.WebSearch("正在搜索网络…"), decoder.accept(parseObject("""{"type":"response.web_search_call.searching"}""")))
        val response = parseObject("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"结论。","annotations":[{"type":"url_citation","url":"https://example.com/article","title":"参考资料"}]}]}]}""")
        assertEquals("结论。\n\n来源：[参考资料](https://example.com/article)", decoder.finish(response).text)
    }
    @Test fun attachmentBudgetIncludesFullHistoryAndMissingFilesFail() {
        val large = folder.newFile("big.pdf")
        RandomAccessFile(large, "rw").use { it.setLength(25_000_000) }
        val attachment = InputAttachment("big.pdf", "application/pdf", large, "pdf")
        assertTrue(runCatching { validateAttachmentBudget(listOf(InputMessage("user", "", listOf(attachment)), InputMessage("user", "", listOf(attachment)))) }.isFailure)
        large.delete()
        assertTrue(runCatching { validateAttachmentBudget(listOf(InputMessage("user", "", listOf(attachment)))) }.isFailure)
    }
    @Test fun utf16BomAndChineseLegacyTextAreDecoded() {
        val file = folder.newFile("note.txt")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "中文".toByteArray(Charsets.UTF_16LE))
        assertEquals("中文", readTextFile(InputAttachment("note.txt", "text/plain", file, "text")))
        file.writeBytes("中文".toByteArray(java.nio.charset.Charset.forName("GB18030")))
        assertEquals("中文", readTextFile(InputAttachment("note.txt", "text/plain", file, "text")))
    }
    @Test fun providerValidationAndDefaultModels() {
        assertEquals(listOf("gpt-6.1-sol", "gpt-5.6-sol"), Provider().models)
        assertTrue(runCatching { Provider(url = "ftp://example.com").validate() }.isFailure)
        assertTrue(runCatching { Provider(maxOutputTokens = 0).validate() }.isFailure)
    }
}
