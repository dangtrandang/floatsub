package com.floatsub.probe.cloud

import android.os.SystemClock
import com.floatsub.probe.CaptureMonitor
import com.floatsub.probe.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class TranslationResult(
    val providerName: String,
    val modelName: String,
    val sourceText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translatedText: String,
    val latencyMs: Long,
    val httpStatus: Int,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

interface TranslationProvider {
    val providerName: String

    suspend fun translate(
        sourceText: String,
        sourceLanguage: String,
        targetLanguage: String,
        apiKey: String
    ): TranslationResult
}

class GroqTranslator(private val httpClient: OkHttpClient) : TranslationProvider {

    override val providerName: String = "Groq"
    val defaultModel: String = "llama-3.1-8b-instant"

    private var resolvedModel: String? = null

    suspend fun resolveActiveModel(apiKey: String): String = withContext(Dispatchers.IO) {
        resolvedModel?.let { return@withContext it }

        try {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    val modelIds = mutableListOf<String>()
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val id = data.getJSONObject(i).optString("id")
                            if (id.isNotBlank()) {
                                modelIds.add(id)
                            }
                        }
                    }

                    val generativeModels = modelIds.filter { id ->
                        !id.contains("guard") && !id.contains("whisper") && !id.contains("orpheus") && !id.contains("audio")
                    }

                    CaptureMonitor.log("Translation", "Groq chat models khả dụng: $generativeModels")

                    val preferred = listOf(
                        "openai/gpt-oss-20b",
                        "qwen/qwen3.8-27b",
                        "llama-3.1-8b-instant",
                        "llama-3.3-70b-versatile",
                        "allam-2-7b",
                        "openai/gpt-oss-120b"
                    )
                    val chosen = preferred.firstOrNull { generativeModels.contains(it) }
                        ?: generativeModels.firstOrNull()
                        ?: defaultModel

                    CaptureMonitor.log("Translation", "Đã chọn Groq Translation model: $chosen")
                    resolvedModel = chosen
                    return@withContext chosen
                }
            }
        } catch (e: Exception) {
            CaptureMonitor.log("Translation", "Không lấy được danh sách model: ${e.message}")
        }

        return@withContext defaultModel
    }

    override suspend fun translate(
        sourceText: String,
        sourceLanguage: String,
        targetLanguage: String,
        apiKey: String
    ): TranslationResult = withContext(Dispatchers.IO) {
        val targetModel = resolveActiveModel(apiKey)
        val cleanInput = sourceText.trim()

        if (cleanInput.isEmpty()) {
            return@withContext TranslationResult(
                providerName = providerName,
                modelName = targetModel,
                sourceText = "",
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translatedText = "",
                latencyMs = 0,
                httpStatus = 200,
                isSuccess = true
            )
        }

        val targetLangName = Language.findByCode(targetLanguage)?.englishName ?: targetLanguage
        val systemPrompt = if (sourceLanguage.equals("auto", ignoreCase = true)) {
            "You are a professional live subtitle translator. Translate the given speech transcript into natural $targetLangName. Output ONLY the translated sentence, without quotes, notes, or explanations."
        } else {
            val sourceLangName = Language.findByCode(sourceLanguage)?.englishName ?: sourceLanguage
            "You are a professional live subtitle translator. Translate the given speech transcript from $sourceLangName into natural $targetLangName. Output ONLY the translated sentence, without quotes, notes, or explanations."
        }

        val requestJson = JSONObject().apply {
            put("model", targetModel)
            put("temperature", 0.1)
            put("max_tokens", 160)
            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", cleanInput)
                })
            }
            put("messages", messagesArray)
        }

        val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(requestBody)
            .build()

        val netStart = SystemClock.elapsedRealtime()
        try {
            httpClient.newCall(request).execute().use { response ->
                val netEnd = SystemClock.elapsedRealtime()
                val latencyMs = netEnd - netStart
                val httpStatus = response.code
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val choices = json.optJSONArray("choices")
                    val translatedText = if (choices != null && choices.length() > 0) {
                        val message = choices.getJSONObject(0).optJSONObject("message")
                        message?.optString("content", "")?.trim() ?: ""
                    } else {
                        ""
                    }

                    CaptureMonitor.log(
                        "Translation",
                        "Groq Translate OK [$httpStatus] | ${latencyMs}ms | [$sourceLanguage->$targetLanguage] \"$cleanInput\" -> \"$translatedText\""
                    )

                    TranslationResult(
                        providerName = providerName,
                        modelName = targetModel,
                        sourceText = cleanInput,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        translatedText = translatedText,
                        latencyMs = latencyMs,
                        httpStatus = httpStatus,
                        isSuccess = true
                    )
                } else {
                    val errorMsg = try {
                        val errJson = JSONObject(responseBody)
                        errJson.optJSONObject("error")?.optString("message") ?: responseBody
                    } catch (e: Exception) {
                        responseBody
                    }

                    CaptureMonitor.log(
                        "Translation",
                        "Groq Translate LỖI [$httpStatus] | ${latencyMs}ms | Message: $errorMsg"
                    )

                    TranslationResult(
                        providerName = providerName,
                        modelName = targetModel,
                        sourceText = cleanInput,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        translatedText = "",
                        latencyMs = latencyMs,
                        httpStatus = httpStatus,
                        isSuccess = false,
                        errorMessage = "HTTP $httpStatus: $errorMsg"
                    )
                }
            }
        } catch (e: IOException) {
            val netEnd = SystemClock.elapsedRealtime()
            val latencyMs = netEnd - netStart
            CaptureMonitor.log("Translation", "Groq Translate Timeout/IO: ${e.message}")

            TranslationResult(
                providerName = providerName,
                modelName = targetModel,
                sourceText = cleanInput,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translatedText = "",
                latencyMs = latencyMs,
                httpStatus = 0,
                isSuccess = false,
                errorMessage = "Network error: ${e.message}"
            )
        } catch (e: Exception) {
            val netEnd = SystemClock.elapsedRealtime()
            val latencyMs = netEnd - netStart
            CaptureMonitor.log("Translation", "Groq Translate Exception: ${e.message}")

            TranslationResult(
                providerName = providerName,
                modelName = targetModel,
                sourceText = cleanInput,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translatedText = "",
                latencyMs = latencyMs,
                httpStatus = 0,
                isSuccess = false,
                errorMessage = "Exception: ${e.message}"
            )
        }
    }
}

class GoogleTranslator(private val httpClient: OkHttpClient) : TranslationProvider {

    override val providerName: String = "Google"

    override suspend fun translate(
        sourceText: String,
        sourceLanguage: String,
        targetLanguage: String,
        apiKey: String
    ): TranslationResult = withContext(Dispatchers.IO) {
        val cleanInput = sourceText.trim()

        if (cleanInput.isEmpty()) {
            return@withContext TranslationResult(
                providerName = providerName,
                modelName = "google-translate-v2",
                sourceText = "",
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translatedText = "",
                latencyMs = 0,
                httpStatus = 200,
                isSuccess = true
            )
        }

        if (apiKey.isBlank()) {
            return@withContext TranslationResult(
                providerName = providerName,
                modelName = "google-translate-v2",
                sourceText = cleanInput,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translatedText = "",
                latencyMs = 0,
                httpStatus = 401,
                isSuccess = false,
                errorMessage = "Chưa cấu hình Google Translate API Key!"
            )
        }

        val requestJson = JSONObject().apply {
            put("q", JSONArray().apply { put(cleanInput) })
            put("target", targetLanguage.lowercase())
            put("format", "text")
            if (!sourceLanguage.equals("auto", ignoreCase = true)) {
                put("source", sourceLanguage.lowercase())
            }
        }

        val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        // Pass API key via header and query param for maximum Google Cloud API compatibility
        val request = Request.Builder()
            .url("https://translation.googleapis.com/language/translate/v2?key=${apiKey.trim()}")
            .header("X-goog-api-key", apiKey.trim())
            .post(requestBody)
            .build()

        val netStart = SystemClock.elapsedRealtime()
        try {
            httpClient.newCall(request).execute().use { response ->
                val netEnd = SystemClock.elapsedRealtime()
                val latencyMs = netEnd - netStart
                val httpStatus = response.code
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val data = json.optJSONObject("data")
                    val translations = data?.optJSONArray("translations")
                    val translatedText = if (translations != null && translations.length() > 0) {
                        translations.getJSONObject(0).optString("translatedText", "").trim()
                    } else {
                        ""
                    }

                    CaptureMonitor.log(
                        "Translation",
                        "Google Translate OK [$httpStatus] | ${latencyMs}ms | [$sourceLanguage->$targetLanguage] \"$cleanInput\" -> \"$translatedText\""
                    )

                    TranslationResult(
                        providerName = providerName,
                        modelName = "google-translate-v2",
                        sourceText = cleanInput,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        translatedText = translatedText,
                        latencyMs = latencyMs,
                        httpStatus = httpStatus,
                        isSuccess = true
                    )
                } else {
                    val errorMsg = try {
                        val errJson = JSONObject(responseBody).optJSONObject("error")
                        val reason = errJson?.optJSONArray("details")?.optJSONObject(0)?.optString("reason", "")
                        val msg = errJson?.optString("message", responseBody) ?: responseBody
                        if (reason.equals("API_KEY_SERVICE_BLOCKED", ignoreCase = true)) {
                            "API_KEY_SERVICE_BLOCKED: API Key này bị Google giới hạn, chưa mở quyền Cloud Translation API trong Google Cloud Console."
                        } else if (msg.contains("has not been used in project", ignoreCase = true) || msg.contains("is disabled", ignoreCase = true)) {
                            "Cloud Translation API chưa được Enable trên Google Cloud Console của project này."
                        } else {
                            msg
                        }
                    } catch (e: Exception) {
                        responseBody
                    }

                    CaptureMonitor.log(
                        "Translation",
                        "Google Translate LỖI [$httpStatus] | ${latencyMs}ms | Message: $errorMsg"
                    )

                    TranslationResult(
                        providerName = providerName,
                        modelName = "google-translate-v2",
                        sourceText = cleanInput,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        translatedText = "",
                        latencyMs = latencyMs,
                        httpStatus = httpStatus,
                        isSuccess = false,
                        errorMessage = "HTTP $httpStatus: $errorMsg"
                    )
                }
            }
        } catch (e: IOException) {
            val netEnd = SystemClock.elapsedRealtime()
            val latencyMs = netEnd - netStart
            CaptureMonitor.log("Translation", "Google Translate Timeout/IO: ${e.message}")

            TranslationResult(
                providerName = providerName,
                modelName = "google-translate-v2",
                sourceText = cleanInput,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translatedText = "",
                latencyMs = latencyMs,
                httpStatus = 0,
                isSuccess = false,
                errorMessage = "Network error: ${e.message}"
            )
        } catch (e: Exception) {
            val netEnd = SystemClock.elapsedRealtime()
            val latencyMs = netEnd - netStart
            CaptureMonitor.log("Translation", "Google Translate Exception: ${e.message}")

            TranslationResult(
                providerName = providerName,
                modelName = "google-translate-v2",
                sourceText = cleanInput,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translatedText = "",
                latencyMs = latencyMs,
                httpStatus = 0,
                isSuccess = false,
                errorMessage = "Exception: ${e.message}"
            )
        }
    }
}
