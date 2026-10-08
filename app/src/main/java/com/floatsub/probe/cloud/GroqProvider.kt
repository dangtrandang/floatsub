package com.floatsub.probe.cloud

import android.os.SystemClock
import com.floatsub.probe.CaptureMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GroqProvider(private val httpClient: OkHttpClient) : CloudAsrProvider {

    override val providerName: String = "Groq"
    override val defaultModel: String = "whisper-large-v3-turbo"
    override val supportedModels: List<String> = listOf(
        "whisper-large-v3-turbo",
        "whisper-large-v3"
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

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.wav",
                wavBytes.toRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .addFormDataPart("model", targetModel)
            .addFormDataPart("response_format", "verbose_json")

        if (!languageCode.isNullOrBlank() && !languageCode.equals("auto", ignoreCase = true)) {
            multipartBuilder.addFormDataPart("language", languageCode.lowercase())
        }

        val requestBody = multipartBuilder.build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${apiKey.trim()}")
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

                // Check server timing header if provided by Groq
                val serverTimingHeader = response.header("x-groq-timing")
                val serverTimeInfo = if (!serverTimingHeader.isNullOrBlank()) {
                    "server_time = $serverTimingHeader"
                } else {
                    "server_time = unavailable"
                }

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val transcript = json.optString("text", "").trim()
                    val detectedLang = json.optString("language", "").ifBlank { null }

                    CaptureMonitor.log(
                        "SpikeC",
                        "Groq OK [$httpStatus] | Net: ${networkLatencyMs}ms | Total: ${totalLatencyMs}ms | Lang: $detectedLang | RTF: ${String.format(java.util.Locale.US, "%.3f", rtf)} | Text: \"$transcript\""
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
                        detectedLanguage = detectedLang,
                        serverTimeInfo = serverTimeInfo
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
                        "Groq LỖI [$httpStatus] | Net: ${networkLatencyMs}ms | Message: $errorMsg"
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
                        serverTimeInfo = serverTimeInfo
                    )
                }
            }
        } catch (e: IOException) {
            val netEnd = SystemClock.elapsedRealtime()
            val networkLatencyMs = netEnd - netStart
            val totalLatencyMs = prepTimeMs + networkLatencyMs
            val rtf = if (audioDurationSec > 0) (totalLatencyMs / 1000.0) / audioDurationSec else 0.0

            CaptureMonitor.log("SpikeC", "Groq Network Timeout/Error: ${e.message}")

            AsrRunResult(
                providerName = providerName,
                modelName = targetModel,
                audioDurationSec = audioDurationSec,
                prepTimeMs = prepTimeMs,
                networkLatencyMs = networkLatencyMs,
                totalLatencyMs = totalLatencyMs,
                rtf = rtf,
                httpStatus = 0,
                isSuccess = false,
                errorMessage = "Network Error: ${e.message}",
                serverTimeInfo = "server_time = unavailable"
            )
        } catch (e: Exception) {
            val netEnd = SystemClock.elapsedRealtime()
            val networkLatencyMs = netEnd - netStart
            val totalLatencyMs = prepTimeMs + networkLatencyMs

            CaptureMonitor.log("SpikeC", "Groq Exception: ${e.message}")

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
