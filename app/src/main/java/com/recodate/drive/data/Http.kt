package com.recodate.drive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HttpError(val code: Int) : RuntimeException("HTTP $code")

/** 주행 중 네트워크가 약할 수 있으니 짧은 타임아웃을 쓴다 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { res -> parse(res, url) }
        }

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { res -> parse(res, url) }
        }

    /** 실패 원인을 로그로 남긴다. 주소의 키 값은 가린다 */
    private fun parse(res: okhttp3.Response, url: String): JsonElement {
        val body = res.body?.string().orEmpty()
        val safeUrl = url.substringBefore('?')
        if (!res.isSuccessful) {
            android.util.Log.w("Http", "HTTP ${res.code} $safeUrl")
            throw HttpError(res.code)
        }
        return runCatching { json.parseToJsonElement(body) }.getOrElse {
            android.util.Log.w("Http", "non-JSON $safeUrl")
            throw it
        }
    }
}
