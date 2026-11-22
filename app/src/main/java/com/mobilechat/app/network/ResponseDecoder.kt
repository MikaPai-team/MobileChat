package com.mobilechat.app.network

import com.mobilechat.app.data.ResponseUpdate
import kotlinx.serialization.json.*

internal class ResponseDecoder {
    private val parts = sortedMapOf<Pair<Int, Int>, String>(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
    private val summaries = sortedMapOf<Pair<Int, Int>, String>(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
    private val reasoningParts = sortedMapOf<Pair<Int, Int>, String>(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
    var terminal = false
        private set
    private val text get() = parts.values.joinToString("")
    private val reasoning get() = (summaries.takeIf { it.isNotEmpty() } ?: reasoningParts).values.joinToString("\n\n")

    fun accept(event: JsonObject, eventName: String = ""): ResponseUpdate? {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: eventName
        val slot = (event["output_index"]?.jsonPrimitive?.intOrNull ?: 0) to (event["content_index"]?.jsonPrimitive?.intOrNull ?: 0)
        return when (type) {
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta",
            "response.reasoning_summary_text.done", "response.reasoning_text.done" -> {
                val summary = type.startsWith("response.reasoning_summary")
                val target = if (summary) summaries else reasoningParts
                val index = slot.first to (event[if (summary) "summary_index" else "content_index"]?.jsonPrimitive?.intOrNull ?: 0)
                if (type.endsWith(".delta")) target[index] = target[index].orEmpty() + event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                else event["text"]?.jsonPrimitive?.contentOrNull?.let { target[index] = it }
                ResponseUpdate.Reasoning(reasoning)
            }
            "response.reasoning_summary_part.added", "response.reasoning_summary_part.done" -> {
                val value = (event["part"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                if (!value.isNullOrEmpty()) summaries[slot.first to (event["summary_index"]?.jsonPrimitive?.intOrNull ?: 0)] = value
                ResponseUpdate.Reasoning(reasoning)
            }
            "response.output_item.done" -> {
                val item = event["item"] as? JsonObject
                if (item?.get("type")?.jsonPrimitive?.contentOrNull == "reasoning") {
                    val summary = reasoningText(item)
                    if (summary.isNotEmpty()) {
                        summaries.keys.filter { it.first == slot.first }.toList().forEach(summaries::remove)
                        summaries[slot.first to 0] = summary
                    }
                    ResponseUpdate.Reasoning(reasoning)
                } else null
            }
            "response.output_text.delta", "response.refusal.delta" -> {
                parts[slot] = (parts[slot] ?: "") + (event["delta"]?.jsonPrimitive?.contentOrNull ?: "")
                ResponseUpdate.Text(text)
            }
            "response.output_text.done", "response.refusal.done" -> {
                val final = event["text"] ?: event["refusal"]
                final?.jsonPrimitive?.contentOrNull?.let { parts[slot] = it }
                ResponseUpdate.Text(text)
            }
            "response.completed" -> finish(event["response"]?.jsonObject ?: throw ApiException("完成事件缺少响应内容"))
            "response.failed", "response.incomplete" -> finish(event["response"]?.jsonObject ?: event)
            "error" -> throw ApiException(event["message"]?.jsonPrimitive?.contentOrNull ?: "接口返回流式错误")
            else -> null
        }
    }

    fun finish(response: JsonObject): ResponseUpdate.Completed {
        val status = response["status"]?.jsonPrimitive?.contentOrNull
        val error = response["error"] as? JsonObject
        if (error != null || status == "failed") throw ApiException(error?.get("message")?.jsonPrimitive?.contentOrNull ?: "生成失败")
        if (status == "incomplete") {
            val reason = (response["incomplete_details"] as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull
            throw ApiException(if (reason == "max_output_tokens") "已达到最大输出长度，回复尚未完成；可提高输出上限后重新生成" else "回复未完成：${reason ?: "服务端提前结束"}")
        }
        if (status != null && status != "completed") throw ApiException("接口尚未完成生成（$status）")
        val final = finalText(response)
        if (final.isBlank()) throw ApiException("接口未返回文本回复，请检查所选模型是否支持聊天")
        terminal = true
        val usage = response["usage"] as? JsonObject
        return ResponseUpdate.Completed(final, usage?.get("input_tokens")?.jsonPrimitive?.intOrNull, usage?.get("output_tokens")?.jsonPrimitive?.intOrNull, finalReasoning(response))
    }

    private fun reasoningText(item: JsonObject): String {
        fun read(key: String, type: String) = (item[key] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == type }
            ?.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }?.joinToString("\n\n").orEmpty()
        return read("summary", "summary_text").ifEmpty { read("content", "reasoning_text") }
    }

    fun finalReasoning(response: JsonObject): String = (response["output"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "reasoning" }?.map(::reasoningText)
        ?.filter(String::isNotEmpty)?.joinToString("\n\n").orEmpty().ifEmpty { reasoning }

    fun finalText(response: JsonObject): String {
        val output = response["output"] as? JsonArray
        val final = output?.mapNotNull { it as? JsonObject }?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
            ?.flatMap { (it["content"] as? JsonArray)?.toList() ?: emptyList() }
            ?.mapNotNull { it as? JsonObject }?.mapNotNull {
                when (it["type"]?.jsonPrimitive?.contentOrNull) {
                    "output_text" -> it["text"]?.jsonPrimitive?.contentOrNull
                    "refusal" -> it["refusal"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }?.joinToString("").orEmpty().ifEmpty { response["output_text"]?.jsonPrimitive?.contentOrNull.orEmpty() }.ifEmpty { text }
        return final
    }
}
