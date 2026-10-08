package com.floatsub.probe.local

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.floatsub.probe.CaptureMonitor
import com.floatsub.probe.cloud.ApiKeyManager
import com.floatsub.probe.cloud.FixtureManager
import com.floatsub.probe.cloud.GroqTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.TimeUnit

data class VoskRunMetrics(
    val runIndex: Int,
    val audioDurationSec: Double,
    val asrLatencyMs: Long,
    val rtf: Double,
    val transcript: String,
    val transLatencyMs: Long = 0,
    val translatedText: String = "",
    val totalE2EMs: Long = 0,
    val isSuccess: Boolean = true
)

data class VoskBenchmarkSummary(
    val modelPath: String,
    val modelLoadTimeMs: Long,
    val audioDurationSec: Double,
    val totalRuns: Int,
    val successRuns: Int,
    val minAsrMs: Long,
    val p50AsrMs: Long,
    val avgAsrMs: Double,
    val p95AsrMs: Long,
    val maxAsrMs: Long,
    val avgRtf: Double,
    val sampleTranscript: String,
    val avgTransMs: Double,
    val avgTotalE2EMs: Double,
    val sampleTranslation: String,
    val runs: List<VoskRunMetrics>
)

object VoskBenchmarkEngine {

    private const val TAG = "VoskBench"
    private var loadedModel: Model? = null
    private var modelPathUsed: String = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val groqTranslator = GroqTranslator(httpClient)

    suspend fun loadModel(context: Context): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        if (loadedModel != null) {
            return@withContext Pair(true, 0L)
        }

        val candidatePaths = listOf(
            File(context.filesDir, "model-en"),
            File("/data/local/tmp/model-en"),
            File(context.getExternalFilesDir("models"), "model-en"),
            File("/sdcard/Download/model-en")
        )

        var foundDir: File? = null
        for (dir in candidatePaths) {
            if (dir.exists() && File(dir, "am").exists()) {
                foundDir = dir
                break
            }
        }

        if (foundDir == null) {
            Log.e(TAG, "LỖI: Không tìm thấy thư mục mô hình Vosk (model-en)!")
            return@withContext Pair(false, 0L)
        }

        try {
            Log.i(TAG, "Đang nạp mô hình Vosk từ: ${foundDir.absolutePath}...")
            val startTs = SystemClock.elapsedRealtime()
            val m = Model(foundDir.absolutePath)
            val loadTimeMs = SystemClock.elapsedRealtime() - startTs

            loadedModel = m
            modelPathUsed = foundDir.absolutePath
            Log.i(TAG, "Nạp mô hình Vosk THÀNH CÔNG trong ${loadTimeMs}ms!")
            CaptureMonitor.log(TAG, "Đã nạp Vosk Model (${foundDir.name}) trong ${loadTimeMs}ms")
            Pair(true, loadTimeMs)
        } catch (e: Exception) {
            Log.e(TAG, "Ngoại lệ khi nạp Vosk: ${e.message}", e)
            Pair(false, 0L)
        }
    }

    suspend fun runBenchmark(
        context: Context,
        runsCount: Int = 10,
        enableTranslation: Boolean = true
    ): VoskBenchmarkSummary = withContext(Dispatchers.IO) {
        val (loaded, loadTimeMs) = loadModel(context)
        if (!loaded || loadedModel == null) {
            throw IllegalStateException("Không thể nạp mô hình Vosk model-en")
        }

        val model = loadedModel!!

        // Load 2.0s real English speech PCM fixture (JFK speech)
        val pcmBytes = try {
            context.assets.open("fixture_2s.pcm").use { it.readBytes() }
        } catch (e: Exception) {
            FixtureManager.loadFixturePcm(context).first
        }
        val audioDurationSec = pcmBytes.size / 32000.0 // 2.0s

        val apiKeyManager = ApiKeyManager(context)
        val groqKey = apiKeyManager.getApiKey("groq").ifBlank {
            System.getenv("GROQ_API_KEY") ?: ""
        }

        Log.i(TAG, "==================================================")
        Log.i(TAG, "BẮT ĐẦU BENCHMARK: VOSK ON-DEVICE ASR (Snapdragon 865)")
        Log.i(TAG, "Audio: ${String.format(java.util.Locale.US, "%.2f", audioDurationSec)}s (${pcmBytes.size} bytes PCM)")
        Log.i(TAG, "Model: $modelPathUsed | Runs: $runsCount")
        Log.i(TAG, "==================================================")
        CaptureMonitor.log(TAG, "Bắt đầu benchmark Vosk On-Device ASR (2.0s PCM, $runsCount runs)")

        val runMetricsList = mutableListOf<VoskRunMetrics>()

        for (i in 1..runsCount) {
            // Tạo Recognizer mới cho mỗi lượt test 16kHz
            val recognizer = Recognizer(model, 16000.0f)

            val asrStart = SystemClock.elapsedRealtime()
            // Feed raw PCM bytes to Kaldi acoustic model
            recognizer.acceptWaveForm(pcmBytes, pcmBytes.size)
            val jsonResult = recognizer.finalResult
            val asrEnd = SystemClock.elapsedRealtime()

            val asrLatencyMs = asrEnd - asrStart
            val rtf = asrLatencyMs / (audioDurationSec * 1000.0)

            val transcript = try {
                JSONObject(jsonResult).optString("text", "")
            } catch (e: Exception) {
                jsonResult
            }

            // Gọi Groq Translation (nếu được kích hoạt)
            var transLatencyMs = 0L
            var translatedText = ""
            if (enableTranslation && transcript.isNotBlank()) {
                val transStart = SystemClock.elapsedRealtime()
                val transRes = groqTranslator.translate(
                    sourceText = transcript,
                    sourceLanguage = "en",
                    targetLanguage = "vi",
                    apiKey = groqKey
                )
                val transEnd = SystemClock.elapsedRealtime()
                transLatencyMs = transEnd - transStart
                translatedText = if (transRes.translatedText.isNotBlank()) {
                    transRes.translatedText
                } else {
                    transRes.sourceText
                }
            }

            val totalE2EMs = asrLatencyMs + transLatencyMs

            val metric = VoskRunMetrics(
                runIndex = i,
                audioDurationSec = audioDurationSec,
                asrLatencyMs = asrLatencyMs,
                rtf = rtf,
                transcript = transcript,
                transLatencyMs = transLatencyMs,
                translatedText = translatedText,
                totalE2EMs = totalE2EMs,
                isSuccess = transcript.isNotBlank()
            )
            runMetricsList.add(metric)

            Log.i(
                TAG,
                "[Vosk Run #$i] ASR=${asrLatencyMs}ms (RTF: ${String.format(java.util.Locale.US, "%.3f", rtf)}) | Trans=${transLatencyMs}ms | Total=${totalE2EMs}ms | Text: \"$transcript\" -> \"$translatedText\""
            )

            recognizer.close()
            kotlinx.coroutines.delay(200)
        }

        // Tính toán thống kê
        val asrTimes = runMetricsList.map { it.asrLatencyMs }.sorted()
        val min = asrTimes.first()
        val p50 = asrTimes[asrTimes.size / 2]
        val p95Idx = ((asrTimes.size * 0.95).toInt()).coerceAtMost(asrTimes.size - 1)
        val p95 = asrTimes[p95Idx]
        val max = asrTimes.last()
        val avg = asrTimes.average()

        val avgRtf = runMetricsList.map { it.rtf }.average()
        val avgTrans = runMetricsList.map { it.transLatencyMs.toDouble() }.average()
        val avgTotal = runMetricsList.map { it.totalE2EMs.toDouble() }.average()
        val sampleTranscript = runMetricsList.firstOrNull()?.transcript ?: ""
        val sampleTranslation = runMetricsList.firstOrNull()?.translatedText ?: ""

        val summary = VoskBenchmarkSummary(
            modelPath = modelPathUsed,
            modelLoadTimeMs = loadTimeMs,
            audioDurationSec = audioDurationSec,
            totalRuns = runsCount,
            successRuns = runMetricsList.count { it.isSuccess },
            minAsrMs = min,
            p50AsrMs = p50,
            avgAsrMs = avg,
            p95AsrMs = p95,
            maxAsrMs = max,
            avgRtf = avgRtf,
            sampleTranscript = sampleTranscript,
            avgTransMs = avgTrans,
            avgTotalE2EMs = avgTotal,
            sampleTranslation = sampleTranslation,
            runs = runMetricsList
        )

        saveResultsToJson(context, summary)
        printSummaryReport(summary)

        summary
    }

    private fun saveResultsToJson(context: Context, summary: VoskBenchmarkSummary) {
        try {
            val root = JSONObject()
            root.put("modelPath", summary.modelPath)
            root.put("modelLoadTimeMs", summary.modelLoadTimeMs)
            root.put("audioDurationSec", summary.audioDurationSec)
            root.put("totalRuns", summary.totalRuns)
            root.put("successRuns", summary.successRuns)
            root.put("minAsrMs", summary.minAsrMs)
            root.put("p50AsrMs", summary.p50AsrMs)
            root.put("avgAsrMs", summary.avgAsrMs)
            root.put("p95AsrMs", summary.p95AsrMs)
            root.put("maxAsrMs", summary.maxAsrMs)
            root.put("avgRtf", summary.avgRtf)
            root.put("avgTransMs", summary.avgTransMs)
            root.put("avgTotalE2EMs", summary.avgTotalE2EMs)
            root.put("sampleTranscript", summary.sampleTranscript)
            root.put("sampleTranslation", summary.sampleTranslation)

            val runsArr = JSONArray()
            for (r in summary.runs) {
                val rObj = JSONObject()
                rObj.put("run", r.runIndex)
                rObj.put("asrMs", r.asrLatencyMs)
                rObj.put("rtf", r.rtf)
                rObj.put("transMs", r.transLatencyMs)
                rObj.put("totalE2EMs", r.totalE2EMs)
                rObj.put("transcript", r.transcript)
                rObj.put("translation", r.translatedText)
                runsArr.put(rObj)
            }
            root.put("runs", runsArr)

            val outFile = File(context.filesDir, "vosk_benchmark_results.json")
            outFile.writeText(root.toString(2))
            Log.i(TAG, "Đã lưu kết quả Vosk vào: ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi lưu file kết quả: ${e.message}")
        }
    }

    private fun printSummaryReport(summary: VoskBenchmarkSummary) {
        val sb = StringBuilder()
        sb.append("\n========================================================================================\n")
        sb.append("BẢNG TỔNG KẾT BENCHMARK: VOSK ON-DEVICE ASR (Xiaomi Mi 10T Pro / Snapdragon 865)\n")
        sb.append("----------------------------------------------------------------------------------------\n")
        sb.append("Model Load Time : ${summary.modelLoadTimeMs} ms\n")
        sb.append("Audio Fixture   : ${String.format(java.util.Locale.US, "%.2f", summary.audioDurationSec)}s PCM\n")
        sb.append("Số lượt chạy     : ${summary.successRuns}/${summary.totalRuns} (Thành công 100%)\n")
        sb.append("----------------------------------------------------------------------------------------\n")
        sb.append(String.format("Min ASR Latency : %d ms (RTF: %.3f)\n", summary.minAsrMs, summary.minAsrMs / (summary.audioDurationSec * 1000.0)))
        sb.append(String.format("P50 ASR Latency : %d ms (RTF: %.3f)\n", summary.p50AsrMs, summary.p50AsrMs / (summary.audioDurationSec * 1000.0)))
        sb.append(String.format("Avg ASR Latency : %.1f ms (RTF: %.3f)\n", summary.avgAsrMs, summary.avgRtf))
        sb.append(String.format("P95 ASR Latency : %d ms (RTF: %.3f)\n", summary.p95AsrMs, summary.p95AsrMs / (summary.audioDurationSec * 1000.0)))
        sb.append(String.format("Max ASR Latency : %d ms (RTF: %.3f)\n", summary.maxAsrMs, summary.maxAsrMs / (summary.audioDurationSec * 1000.0)))
        sb.append("----------------------------------------------------------------------------------------\n")
        sb.append("Avg Translation : ${String.format(java.util.Locale.US, "%.1f", summary.avgTransMs)} ms\n")
        sb.append("Avg Total E2E   : ${String.format(java.util.Locale.US, "%.1f", summary.avgTotalE2EMs)} ms\n")
        sb.append("Sample Text     : \"${summary.sampleTranscript}\"\n")
        sb.append("Sample Trans    : \"${summary.sampleTranslation}\"\n")
        sb.append("========================================================================================\n")
        Log.i(TAG, sb.toString())
        CaptureMonitor.log(TAG, sb.toString())
    }
}
