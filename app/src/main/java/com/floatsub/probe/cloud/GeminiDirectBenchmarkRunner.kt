package com.floatsub.probe.cloud

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.floatsub.probe.CaptureMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SingleRunMetrics(
    val runIndex: Int,
    val durationSec: Double,
    val audioPrepMs: Long,
    val httpLatencyMs: Long,
    val responseParseMs: Long,
    val totalClientMs: Long,
    val httpStatus: Int,
    val outputText: String,
    val isSuccess: Boolean,
    val isOnlyVietnamese: Boolean,
    val errorMessage: String? = null
)

data class DurationStats(
    val durationSec: Double,
    val runs: Int,
    val successCount: Int,
    val failureCount: Int,
    val minMs: Long,
    val p50Ms: Long,
    val avgMs: Double,
    val p95Ms: Long,
    val maxMs: Long,
    val sampleOutput: String,
    val allRuns: List<SingleRunMetrics>
)

class GeminiDirectBenchmarkRunner(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val TAG = "GeminiBench"
        const val DEFAULT_PROMPT =
            "Listen to this audio and translate the spoken content directly into natural Vietnamese subtitle text. Do not transcribe first. Return only the Vietnamese translation."
    }

    suspend fun runFullBenchmark(
        apiKey: String,
        model: String = "gemini-3.8-flash",
        runsPerDuration: Int = 10
    ): Map<Double, DurationStats> = withContext(Dispatchers.IO) {
        val effectiveKey = apiKey.trim().ifBlank {
            ApiKeyManager(context).getApiKey("gemini").ifBlank {
                System.getenv("GEMINI_API_KEY") ?: ""
            }
        }

        // Load 2.0s PCM from assets or live
        val (fullPcm, fixtureDesc) = FixtureManager.loadFixturePcm(context)
        Log.i(TAG, "==================================================")
        Log.i(TAG, "BẮT ĐẦU BENCHMARK: GEMINI DIRECT AUDIO -> VIETNAMESE")
        Log.i(TAG, "Model: $model | Device: Xiaomi Mi 10T Pro")
        Log.i(TAG, "Fixture: $fixtureDesc | Runs per duration: $runsPerDuration")
        Log.i(TAG, "Prompt: \"$DEFAULT_PROMPT\"")
        Log.i(TAG, "==================================================")

        CaptureMonitor.log(TAG, "Bắt đầu benchmark Gemini Audio -> Vietnamese ($model)")

        val durations = listOf(1.0, 1.5, 2.0)
        val resultMap = mutableMapOf<Double, DurationStats>()

        for (duration in durations) {
            val targetBytes = (duration * 32000).toInt()
            val sliceBytes = if (fullPcm.size >= targetBytes) {
                fullPcm.copyOfRange(0, targetBytes)
            } else {
                fullPcm
            }

            Log.i(TAG, ">>> BẮT ĐẦU DURATION: ${duration}s (${sliceBytes.size} bytes PCM) - $runsPerDuration RUNS <<<")
            CaptureMonitor.log(TAG, "--- Đang chạy duration ${duration}s ($runsPerDuration runs) ---")

            val runList = mutableListOf<SingleRunMetrics>()

            for (i in 1..runsPerDuration) {
                val runMetric = executeSingleRun(
                    runIndex = i,
                    durationSec = duration,
                    pcmBytes = sliceBytes,
                    apiKey = effectiveKey,
                    model = model
                )
                runList.add(runMetric)

                Log.i(
                    TAG,
                    "[${duration}s | Run #$i] Status=${runMetric.httpStatus} | Prep=${runMetric.audioPrepMs}ms | HTTP=${runMetric.httpLatencyMs}ms | Parse=${runMetric.responseParseMs}ms | Total=${runMetric.totalClientMs}ms | Text: \"${runMetric.outputText}\""
                )

                // Nghỉ nhẹ 300ms giữa các request để tránh rate-limit burst
                kotlinx.coroutines.delay(300)
            }

            // Tính toán thống kê
            val successes = runList.filter { it.isSuccess }
            val stats = if (successes.isNotEmpty()) {
                val totalTimes = successes.map { it.totalClientMs }.sorted()
                val min = totalTimes.first()
                val p50 = totalTimes[totalTimes.size / 2]
                val p95Idx = ((totalTimes.size * 0.95).toInt()).coerceAtMost(totalTimes.size - 1)
                val p95 = totalTimes[p95Idx]
                val max = totalTimes.last()
                val avg = totalTimes.average()
                val sampleText = successes.firstOrNull { it.outputText.isNotBlank() }?.outputText ?: ""

                DurationStats(
                    durationSec = duration,
                    runs = runsPerDuration,
                    successCount = successes.size,
                    failureCount = runList.size - successes.size,
                    minMs = min,
                    p50Ms = p50,
                    avgMs = avg,
                    p95Ms = p95,
                    maxMs = max,
                    sampleOutput = sampleText,
                    allRuns = runList
                )
            } else {
                DurationStats(
                    durationSec = duration,
                    runs = runsPerDuration,
                    successCount = 0,
                    failureCount = runList.size,
                    minMs = 0,
                    p50Ms = 0,
                    avgMs = 0.0,
                    p95Ms = 0,
                    maxMs = 0,
                    sampleOutput = "TẤT CẢ THẤT BẠI: ${runList.firstOrNull()?.errorMessage}",
                    allRuns = runList
                )
            }

            resultMap[duration] = stats
            Log.i(TAG, "KẾT QUẢ ${duration}s: Success=${stats.successCount}/$runsPerDuration | Min=${stats.minMs}ms | P50=${stats.p50Ms}ms | Avg=${String.format(Locale.US, "%.1f", stats.avgMs)}ms | P95=${stats.p95Ms}ms | Max=${stats.maxMs}ms | Sample: \"${stats.sampleOutput}\"")
        }

        // Lưu JSON kết quả
        saveResultsToJson(model, resultMap)
        printFinalReport(model, resultMap)

        resultMap
    }

    private fun executeSingleRun(
        runIndex: Int,
        durationSec: Double,
        pcmBytes: ByteArray,
        apiKey: String,
        model: String
    ): SingleRunMetrics {
        val prepStart = SystemClock.elapsedRealtime()

        // 1. PCM -> WAV
        val wavConv = AudioWavConverter.pcmToWav(pcmBytes, 16000)
        val base64Audio = Base64.encodeToString(wavConv.wavBytes, Base64.NO_WRAP)
        val prepEnd = SystemClock.elapsedRealtime()
        val audioPrepMs = prepEnd - prepStart

        // 2. Build JSON Request
        val promptPart = JSONObject().apply {
            put("text", DEFAULT_PROMPT)
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
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)
                put("maxOutputTokens", 100)
            })
        }

        val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(requestBody)
            .build()

        val httpStart = SystemClock.elapsedRealtime()
        try {
            httpClient.newCall(request).execute().use { response ->
                val httpEnd = SystemClock.elapsedRealtime()
                val httpLatencyMs = httpEnd - httpStart
                val httpStatus = response.code
                val responseBody = response.body?.string() ?: ""

                val parseStart = SystemClock.elapsedRealtime()
                var outputText = ""
                var isSuccess = false
                var errorMessage: String? = null

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            outputText = parts.getJSONObject(0).optString("text", "").trim()
                            isSuccess = true
                        }
                    }
                } else {
                    errorMessage = "HTTP $httpStatus: $responseBody"
                }

                val parseEnd = SystemClock.elapsedRealtime()
                val responseParseMs = parseEnd - parseStart
                val totalClientMs = audioPrepMs + httpLatencyMs + responseParseMs

                val isOnlyVietnamese = !outputText.contains("English", ignoreCase = true) &&
                        !outputText.contains("Here is", ignoreCase = true) &&
                        !outputText.contains("Translation:", ignoreCase = true)

                return SingleRunMetrics(
                    runIndex = runIndex,
                    durationSec = durationSec,
                    audioPrepMs = audioPrepMs,
                    httpLatencyMs = httpLatencyMs,
                    responseParseMs = responseParseMs,
                    totalClientMs = totalClientMs,
                    httpStatus = httpStatus,
                    outputText = outputText,
                    isSuccess = isSuccess,
                    isOnlyVietnamese = isOnlyVietnamese,
                    errorMessage = errorMessage
                )
            }
        } catch (e: Exception) {
            val httpEnd = SystemClock.elapsedRealtime()
            val httpLatencyMs = httpEnd - httpStart
            val totalClientMs = audioPrepMs + httpLatencyMs

            return SingleRunMetrics(
                runIndex = runIndex,
                durationSec = durationSec,
                audioPrepMs = audioPrepMs,
                httpLatencyMs = httpLatencyMs,
                responseParseMs = 0,
                totalClientMs = totalClientMs,
                httpStatus = 0,
                outputText = "",
                isSuccess = false,
                isOnlyVietnamese = false,
                errorMessage = e.message
            )
        }
    }

    private fun saveResultsToJson(model: String, statsMap: Map<Double, DurationStats>) {
        try {
            val root = JSONObject().apply {
                put("model", model)
                put("timestamp", System.currentTimeMillis())
                val arr = JSONArray()
                for ((dur, stats) in statsMap) {
                    val durObj = JSONObject().apply {
                        put("durationSec", dur)
                        put("runs", stats.runs)
                        put("successCount", stats.successCount)
                        put("minMs", stats.minMs)
                        put("p50Ms", stats.p50Ms)
                        put("avgMs", stats.avgMs)
                        put("p95Ms", stats.p95Ms)
                        put("maxMs", stats.maxMs)
                        put("sampleOutput", stats.sampleOutput)
                    }
                    arr.put(durObj)
                }
                put("durations", arr)
            }
            val outFile = File(context.filesDir, "gemini_benchmark_results.json")
            outFile.writeText(root.toString(2))
            Log.i(TAG, "Đã lưu kết quả vào file: ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi lưu JSON: ${e.message}")
        }
    }

    private fun printFinalReport(model: String, statsMap: Map<Double, DurationStats>) {
        val sb = StringBuilder()
        sb.append("\n========================================================================================\n")
        sb.append("BENCHMARK KẾT QUẢ CUỐI: GEMINI DIRECT AUDIO -> VIETNAMESE ($model)\n")
        sb.append("----------------------------------------------------------------------------------------\n")
        sb.append(String.format("%-10s | %-8s | %-7s | %-7s | %-7s | %-7s | %s\n", "Duration", "Success", "p50", "Avg", "p95", "Max", "Sample Output"))
        sb.append("----------------------------------------------------------------------------------------\n")
        for ((dur, stats) in statsMap) {
            sb.append(String.format(
                Locale.US,
                "%-10s | %-8s | %-7d | %-7.1f | %-7d | %-7d | %s\n",
                "${dur}s",
                "${stats.successCount}/${stats.runs}",
                stats.p50Ms,
                stats.avgMs,
                stats.p95Ms,
                stats.maxMs,
                "\"${stats.sampleOutput}\""
            ))
        }
        sb.append("========================================================================================\n")
        Log.i(TAG, sb.toString())
        CaptureMonitor.log(TAG, sb.toString())
    }
}
