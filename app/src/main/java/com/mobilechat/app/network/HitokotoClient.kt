package com.mobilechat.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class Quote(val text: String, val attribution: String)

internal class HitokotoClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build(),
    private val url: String = "https://v1.hitokoto.cn/?encode=json&c=i",
) {
    suspend fun random(): Quote = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("一言接口暂时不可用")
            val body = parseObject(response.body.string())
            val text = body.stringValue("hitokoto").trim()
            require(text.isNotEmpty()) { "一言接口未返回内容" }
            val attribution = listOf(body.stringValue("from_who"), body.stringValue("from"))
                .filter(String::isNotBlank).distinct().joinToString(" · ")
            Quote(text, attribution)
        }
    }

    private fun JsonObject.stringValue(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
