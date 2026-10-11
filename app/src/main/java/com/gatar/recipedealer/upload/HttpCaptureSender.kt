package com.gatar.recipedealer.upload

import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.CaptureJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The example REST API (final-app.md §8): `PUT /v1/captures/{id}` multipart (part `capture` + one part per
 * attachment, named like `attachments[].name`), `GET /v1/captures/{id}`, `GET /v1/health`; bearer token (D3).
 */
class HttpCaptureSender(
    baseUrl: String,
    private val token: String?,
    private val client: OkHttpClient = defaultClient,
) : CaptureSender {
    private val base = baseUrl.trim().trimEnd('/')

    override suspend fun send(capture: Capture, files: Map<String, File>): SendResult = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("capture", null,
                CaptureJson.encodeToString(Capture.serializer(), capture).toRequestBody(JSON))
        for (a in capture.attachments) {
            val f = files[a.name]?.takeIf { it.exists() }
                ?: return@withContext SendResult.Invalid(listOf("missing attachment file: ${a.name}"))
            body.addFormDataPart(a.name, a.name, f.asRequestBody(a.mimeType.toMediaType()))
        }
        val request = request("/v1/captures/${capture.captureId}").put(body.build()).build()
        try {
            client.newCall(request).execute().use { r -> map(r) }
        } catch (e: IOException) {
            SendResult.Retry("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun map(r: Response): SendResult {
        val text = r.body?.string().orEmpty()
        return when (r.code) {
            200, 201 -> SendResult.Accepted(created = r.code == 201, destination = base)
            400 -> SendResult.Invalid(details(text).ifEmpty { listOf("HTTP 400") })
            401, 403 -> SendResult.Unauthorized(r.code)
            413 -> SendResult.TooLarge
            429 -> SendResult.Retry("HTTP 429")
            in 500..599 -> SendResult.Retry("HTTP ${r.code}")
            else -> SendResult.Invalid(listOf("HTTP ${r.code}") + details(text))
        }
    }

    /** `{"error": "invalid_capture", "details": ["…"]}` */
    private fun details(body: String): List<String> = runCatching {
        val o = CaptureJson.parseToJsonElement(body).jsonObject
        ((o["details"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty() +
            listOfNotNull((o["error"] as? JsonPrimitive)?.contentOrNull)).distinct()
    }.getOrDefault(emptyList())

    /** Optional endpoint: null when unavailable or unknown (the app works without it). */
    override suspend fun status(captureId: String): BackendStatus? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request("/v1/captures/$captureId").get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = CaptureJson.parseToJsonElement(r.body!!.string()).jsonObject
                BackendStatus(
                    status = (o["status"] as? JsonPrimitive)?.contentOrNull ?: return@use null,
                    titles = (o["recipes"] as? JsonArray).orEmpty().mapNotNull {
                        ((it as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull
                    },
                    message = (o["message"] as? JsonPrimitive)?.contentOrNull,
                )
            }
        }.getOrNull()
    }

    sealed interface Health {
        data object Ok : Health
        data object Unauthorized : Health
        data class Failed(val reason: String) : Health
    }

    /** Settings screen: tests URL and token. */
    suspend fun health(): Health = withContext(Dispatchers.IO) {
        try {
            client.newCall(request("/v1/health").get().build()).execute().use { r ->
                when {
                    r.code == 401 || r.code == 403 -> Health.Unauthorized
                    r.isSuccessful && r.body?.string()?.contains("\"ok\"") == true -> Health.Ok
                    else -> Health.Failed("HTTP ${r.code}")
                }
            }
        } catch (e: IOException) {
            Health.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            Health.Failed(e.message ?: "invalid URL")
        }
    }

    private fun request(path: String) = Request.Builder().url(base + path).apply {
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        header("Accept", "application/json")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
