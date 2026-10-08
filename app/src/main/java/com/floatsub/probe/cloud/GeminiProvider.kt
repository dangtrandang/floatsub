package com.floatsub.probe.cloud

import android.os.SystemClock
import android.util.Base64
import com.floatsub.probe.CaptureMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class GeminiProvider(private val httpClient: OkHttpClient) : CloudAsrProvider {

    override val providerName: String = "Gemini"
    override val defaultModel: String = "gemini-1.5-flash"
    override val supportedModels: List<String> = listOf(
        "gemini-1.5-flash",
        "gemini-2.0-flash"
    )

    override suspend fun transcribe(
        wavBytes: ByteArray,
        audioDurationSec: Double,
        apiKey: String,
        model: String,
        prepTimeMs: Long,
        languageCode: String?
    ): AsrRunResult = withContext(Dispatchers.IO) {
        val targetModel = if (model.isNotBlank()) model.trim() else defaultModel

        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val promptText = if (!languageCode.isNullOrBlank() && !languageCode.equals("auto", ignoreCase = true)) {
            "Transcribe the following $languageCode speech verbatim. Return ONLY the transcribed text, without commentary or formatting."
        } else {
            "Transcribe the following speech verbatim. Return ONLY the transcribed text, without commentary or formatting."
        }

        // Build Gemini generateContent JSON payload
        val promptPart = JSONObject().apply {
            put("text", promptText)
        }
        val audioPart = JSONObject().apply {
            val inlineData = JSONObject().apply {
                put("mime_type", "audio/wav")
                put("data", base64Audio)
            }
            put("inline_data", inlineData)
        }
        val contentObj = JSONObject().apply {
            val partsArray = JSONArray().apply {
                put(promptPart)
                put(audioPart)
            }
            put("parts", partsArray)
        }
        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply { put(contentObj) })
        }

        val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        // Pass API key via x-goog-api-key header for security
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .post(requestBody)
            .build()

        val netStart = SystemClock.elapsedRealtime()
        try {
            httpClient.newCall(request).execute().use { response ->
                val netEnd = SystemClock.elapsedRealtime()
                val networkLatencyMs = netEnd - netStart
                val totalLatencyMs = prepTimeMs + networkLatencyMs
                val rtf = if (audioDurationSec > 0) (totalLatencyMs / 1000.0) / audioDurationSec else 0.0
                val httpStatus = response.code
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates")
                    val transcript = if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            parts.getJSONObject(0).optString("text", "").trim()
                        } else {
                            ""
                        }
                    } else {
                        ""
                    }

                    CaptureMonitor.log(
                        "SpikeC",
                        "Gemini OK [$httpStatus] | Net: ${networkLatencyMs}ms | Total: ${totalLatencyMs}ms | RTF: ${String.format(java.util.Locale.US, "%.3f", rtf)} | Text: \"$transcript\""
                    )

                    AsrRunResult(
                        providerName = providerName,
                        modelName = targetModel,
                        audioDurationSec = audioDurationSec,
                        prepTimeMs = prepTimeMs,
                        networkLatencyMs = networkLatencyMs,
                        totalLatencyMs = totalLatencyMs,
                        rtf = rtf,
                        httpStatus = httpStatus,
                        isSuccess = true,
                        transcript = transcript,
                        serverTimeInfo = "server_time = unavailable"
                    )
                } else {
                    val errorMsg = try {
                        val errJson = JSONObject(responseBody)
                        errJson.optJSONObject("error")?.optString("message") ?: responseBody
                    } catch (e: Exception) {
                        responseBody
                    }

                    CaptureMonitor.log(
                        "SpikeC",
                        "Gemini LỖI [$httpStatus] | Net: ${networkLatencyMs}ms | Message: $errorMsg"
                    )

                    AsrRunResult(
                        providerName = providerName,
                        modelName = targetModel,
                        audioDurationSec = audioDurationSec,
                        prepTimeMs = prepTimeMs,
                        networkLatencyMs = networkLatencyMs,
                        totalLatencyMs = totalLatencyMs,
                        rtf = rtf,
                        httpStatus = httpStatus,
                        isSuccess = false,
                        errorMessage = "HTTP $httpStatus: $errorMsg",
                        serverTimeInfo = "server_time = unavailable"
                    )
                }
            }
        } catch (e: IOException) {
            val netEnd = SystemClock.elapsedRealtime()
            val networkLatencyMs = netEnd - netStart
            val totalLatencyMs = prepTimeMs + networkLatencyMs

            CaptureMonitor.log("SpikeC", "Gemini Network Timeout/Error: ${e.message}")

            AsrRunResult(
                providerName = providerName,
                modelName = targetModel,
                audioDurationSec = audioDurationSec,
                prepTimeMs = prepTimeMs,
                networkLatencyMs = networkLatencyMs,
                totalLatencyMs = totalLatencyMs,
                rtf = if (audioDurationSec > 0) (totalLatencyMs / 1000.0) / audioDurationSec else 0.0,
                httpStatus = 0,
                isSuccess = false,
                errorMessage = "Network Error: ${e.message}",
                serverTimeInfo = "server_time = unavailable"
            )
        } catch (e: Exception) {
            val netEnd = SystemClock.elapsedRealtime()
            val networkLatencyMs = netEnd - netStart
            val totalLatencyMs = prepTimeMs + networkLatencyMs

            CaptureMonitor.log("SpikeC", "Gemini Exception: ${e.message}")

            AsrRunResult(
                providerName = providerName,
                modelName = targetModel,
                audioDurationSec = audioDurationSec,
                prepTimeMs = prepTimeMs,
                networkLatencyMs = networkLatencyMs,
                totalLatencyMs = totalLatencyMs,
                rtf = if (audioDurationSec > 0) (totalLatencyMs / 1000.0) / audioDurationSec else 0.0,
                httpStatus = 0,
                isSuccess = false,
                errorMessage = "Exception: ${e.message}",
                serverTimeInfo = "server_time = unavailable"
            )
        }
    }
}
