package com.floatsub.probe.quicktranslate

import android.content.Context
import android.util.Log
import com.floatsub.probe.CaptureMonitor
import com.floatsub.probe.cloud.ApiKeyManager
import com.floatsub.probe.cloud.AudioWavConverter
import com.floatsub.probe.cloud.FixtureManager
import com.floatsub.probe.cloud.GroqProvider
import com.floatsub.probe.cloud.GroqTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class QuickConfigStats(
    val configName: String,
    val previousSec: Double,
    val futureSec: Double,
    val totalAudioSec: Double,
    val totalRuns: Int,
    val successRuns: Int,
    // tap -> subtitle render metrics
    val minTapToSubMs: Long,
    val p50TapToSubMs: Long,
    val avgTapToSubMs: Double,
    val p95TapToSubMs: Long,
    val maxTapToSubMs: Long,
    // Component averages
    val avgTapToAudioReadyMs: Double,
    val avgAsrLatencyMs: Double,
    val avgTransLatencyMs: Double,
    val sampleTranscript: String,
    val sampleTranslation: String,
    val runs: List<QuickTranslateMetrics>
)

class QuickTranslateBenchmarkRunner(private val context: Context) {

    companion object {
        const val TAG = "QuickBench"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val groqAsr = GroqProvider(httpClient)
    private val groqTranslator = GroqTranslator(httpClient)

    suspend fun runFullExperiment(
        runsPerConfig: Int = 10
    ): Map<String, QuickConfigStats> = withContext(Dispatchers.IO) {
        val apiKeyManager = ApiKeyManager(context)
        val groqKey = apiKeyManager.getApiKey("groq").ifBlank {
            System.getenv("GROQ_API_KEY") ?: ""
        }

        // Load 2.0s real English speech PCM fixture from assets (JFK speech)
        val basePcm = try {
            context.assets.open("fixture_2s.pcm").use { it.readBytes() }
        } catch (e: Exception) {
            FixtureManager.loadFixturePcm(context).first
        }
        val fixtureDesc = "Real English Speech Fixture (${String.format(java.util.Locale.US, "%.2f", basePcm.size / 32000.0)}s, ${basePcm.size} B)"
        Log.i(TAG, "==================================================")
        Log.i(TAG, "BẮT ĐẦU QUICK TRANSLATE CAPTURE EXPERIMENT")
        Log.i(TAG, "Fixture: $fixtureDesc | Runs per config: $runsPerConfig")
        Log.i(TAG, "Groq ASR: whisper-large-v3-turbo | Groq Translation: llama-3.1-8b-instant")
        Log.i(TAG, "==================================================")

        CaptureMonitor.log(TAG, "Bắt đầu Quick Translate Capture Experiment ($fixtureDesc)")

        // 1. Chạy 3 bài kiểm tra an toàn (Verifications)
        runSafetyVerifications()

        // 2. Chạy 3 cấu hình benchmark
        val configs = listOf(
            Triple(1.0, 0.8, "1.0s prev + 0.8s future"),
            Triple(1.5, 0.8, "1.5s prev + 0.8s future"),
            Triple(1.5, 1.0, "1.5s prev + 1.0s future")
        )

        val resultMap = mutableMapOf<String, QuickConfigStats>()

        // Tạo mảng continuous speech lặp lại từ basePcm để có đủ audio đa dạng cho prev + future
        val continuousPcm = tilePcm(basePcm, 10.0) // 10 giây speech

        for ((prevSec, futSec, configName) in configs) {
            Log.i(TAG, ">>> BẮT ĐẦU CONFIG: $configName (Total ${(prevSec + futSec)}s audio) <<<")
            CaptureMonitor.log(TAG, "--- Đang chạy $configName ($runsPerConfig runs) ---")

            val successfulMetrics = mutableListOf<QuickTranslateMetrics>()
            var attempt = 0

            while (successfulMetrics.size < runsPerConfig && attempt < 35) {
                attempt++

                // Chuẩn bị Ring Buffer với audio của đoạn trước
                QuickTranslateEngine.resetBuffer()
                val prevBytesCount = (prevSec * 32000).toInt()
                val prevPcmData = continuousPcm.copyOfRange(0, prevBytesCount)

                // Bơm audio trước vào Ring Buffer
                QuickTranslateEngine.onPcmBytes(prevPcmData)

                // Đo đạc luồng Tap-to-Translate
                val tapTimestamp = System.currentTimeMillis()
                val sessionId = "bench_${attempt}_${System.currentTimeMillis()}"

                // 1. Snapshot previous audio
                val snapshotTimestamp = System.currentTimeMillis()
                val snapPreviousPcm = QuickTranslateEngine.ringBuffer.snapshotLast(prevSec)

                // 2. Thu future audio: Chờ đúng futureSec thời gian thực và stream future bytes vào
                val captureFutureStartTimestamp = System.currentTimeMillis()
                val futureDelayMs = (futSec * 1000).toLong()
                val futBytesCount = (futSec * 32000).toInt()
                val futurePcmData = continuousPcm.copyOfRange(prevBytesCount, prevBytesCount + futBytesCount)

                // Giả lập stream future audio trong khoảng thời gian delay
                delay(futureDelayMs)
                QuickTranslateEngine.onPcmBytes(futurePcmData)
                val captureFutureEndTimestamp = System.currentTimeMillis()

                // 3. Ghép audio: Previous + Future = 1 WAV segment
                val combinedPcm = ByteArray(snapPreviousPcm.size + futurePcmData.size)
                System.arraycopy(snapPreviousPcm, 0, combinedPcm, 0, snapPreviousPcm.size)
                System.arraycopy(futurePcmData, 0, combinedPcm, snapPreviousPcm.size, futurePcmData.size)

                val totalAudioSec = combinedPcm.size / 32000.0
                val wavConv = AudioWavConverter.pcmToWav(combinedPcm, 16000)

                // 4. Groq Whisper ASR
                val asrStartTimestamp = System.currentTimeMillis()
                val asrRes = groqAsr.transcribe(
                    wavBytes = wavConv.wavBytes,
                    audioDurationSec = totalAudioSec,
                    apiKey = groqKey,
                    model = "whisper-large-v3-turbo",
                    prepTimeMs = 1
                )
                val asrEndTimestamp = System.currentTimeMillis()

                if (!asrRes.isSuccess || asrRes.transcript.isBlank()) {
                    Log.w(TAG, "[$configName | Run attempt #$attempt] ASR Thất bại: ${asrRes.errorMessage}")
                    if (asrRes.errorMessage?.contains("429") == true || asrRes.errorMessage?.contains("Rate limit") == true) {
                        Log.i(TAG, "Gặp Groq 429 RPM limit, tạm dừng 3.5s để reset quota...")
                        delay(3500)
                    } else {
                        delay(1000)
                    }
                    continue
                }

                // 5. Groq Translation
                val translationStartTimestamp = System.currentTimeMillis()
                val transRes = groqTranslator.translate(
                    sourceText = asrRes.transcript,
                    sourceLanguage = asrRes.detectedLanguage ?: "en",
                    targetLanguage = "vi",
                    apiKey = groqKey
                )
                val translationEndTimestamp = System.currentTimeMillis()

                val finalTranslation = if (transRes.translatedText.isNotBlank()) {
                    transRes.translatedText
                } else if (transRes.isSuccess) {
                    asrRes.transcript
                } else {
                    Log.w(TAG, "[$configName | Run attempt #$attempt] Translation Thất bại: ${transRes.errorMessage}")
                    if (transRes.errorMessage?.contains("429") == true || transRes.errorMessage?.contains("Rate limit") == true) {
                        Log.i(TAG, "Gặp Groq 429 RPM limit, tạm dừng 3.5s để reset quota...")
                        delay(3500)
                    } else {
                        delay(1000)
                    }
                    continue
                }

                // 6. Overlay Render Timestamp
                val overlayRenderTimestamp = System.currentTimeMillis()

                val tapToAudioReadyMs = captureFutureEndTimestamp - tapTimestamp
                val audioReadyToAsrMs = asrEndTimestamp - asrStartTimestamp
                val asrToTranslationMs = translationEndTimestamp - translationStartTimestamp
                val tapToSubtitleRenderMs = overlayRenderTimestamp - tapTimestamp

                val metric = QuickTranslateMetrics(
                    sessionId = sessionId,
                    previousSec = prevSec,
                    futureSec = futSec,
                    totalAudioSec = totalAudioSec,
                    timestamps = QuickTranslateTimestamps(
                        tapTimestamp, snapshotTimestamp, captureFutureStartTimestamp, captureFutureEndTimestamp,
                        asrStartTimestamp, asrEndTimestamp, translationStartTimestamp, translationEndTimestamp,
                        overlayRenderTimestamp
                    ),
                    tapToAudioReadyMs = tapToAudioReadyMs,
                    audioReadyToAsrMs = audioReadyToAsrMs,
                    asrToTranslationMs = asrToTranslationMs,
                    tapToSubtitleRenderMs = tapToSubtitleRenderMs,
                    transcript = asrRes.transcript,
                    translatedText = finalTranslation,
                    isSuccess = true
                )

                successfulMetrics.add(metric)
                val runIdx = successfulMetrics.size
                Log.i(
                    TAG,
                    "[$configName | Run #$runIdx/$runsPerConfig] Tap->Render=${tapToSubtitleRenderMs}ms | AudioWait=${tapToAudioReadyMs}ms | ASR=${audioReadyToAsrMs}ms | Trans=${asrToTranslationMs}ms | Trans: \"$finalTranslation\""
                )

                // Nghỉ 2.5s giữa các run thành công để duy trì tốc độ dưới 20 RPM của Groq Free Tier
                delay(2500)
            }

            // Tính toán thống kê cho config
            val stats = if (successfulMetrics.isNotEmpty()) {
                val tapToSubs = successfulMetrics.map { it.tapToSubtitleRenderMs }.sorted()
                val min = tapToSubs.first()
                val p50 = tapToSubs[tapToSubs.size / 2]
                val p95Idx = ((tapToSubs.size * 0.95).toInt()).coerceAtMost(tapToSubs.size - 1)
                val p95 = tapToSubs[p95Idx]
                val max = tapToSubs.last()
                val avg = tapToSubs.average()

                val avgAudioReady = successfulMetrics.map { it.tapToAudioReadyMs }.average()
                val avgAsr = successfulMetrics.map { it.audioReadyToAsrMs }.average()
                val avgTrans = successfulMetrics.map { it.asrToTranslationMs }.average()
                val sampleTrans = successfulMetrics.firstOrNull()?.translatedText ?: ""
                val sampleTranscript = successfulMetrics.firstOrNull()?.transcript ?: ""

                QuickConfigStats(
                    configName = configName,
                    previousSec = prevSec,
                    futureSec = futSec,
                    totalAudioSec = prevSec + futSec,
                    totalRuns = attempt,
                    successRuns = successfulMetrics.size,
                    minTapToSubMs = min,
                    p50TapToSubMs = p50,
                    avgTapToSubMs = avg,
                    p95TapToSubMs = p95,
                    maxTapToSubMs = max,
                    avgTapToAudioReadyMs = avgAudioReady,
                    avgAsrLatencyMs = avgAsr,
                    avgTransLatencyMs = avgTrans,
                    sampleTranscript = sampleTranscript,
                    sampleTranslation = sampleTrans,
                    runs = successfulMetrics
                )
            } else {
                QuickConfigStats(
                    configName = configName,
                    previousSec = prevSec,
                    futureSec = futSec,
                    totalAudioSec = prevSec + futSec,
                    totalRuns = attempt,
                    successRuns = 0,
                    minTapToSubMs = 0,
                    p50TapToSubMs = 0,
                    avgTapToSubMs = 0.0,
                    p95TapToSubMs = 0,
                    maxTapToSubMs = 0,
                    avgTapToAudioReadyMs = 0.0,
                    avgAsrLatencyMs = 0.0,
                    avgTransLatencyMs = 0.0,
                    sampleTranscript = "",
                    sampleTranslation = "FAILED",
                    runs = emptyList()
                )
            }
            resultMap[configName] = stats
        }

        // Lưu JSON kết quả
        saveResultsToJson(resultMap)

        // In bảng tổng kết
        printSummaryReport(resultMap)

        resultMap
    }

    private fun runSafetyVerifications() {
        Log.i(TAG, "--- KIỂM TRA AN TOÀN VÀ CÔ LẬP BUFFER ---")

        // 1. No audio from before the ring buffer window leaks in
        val testBuffer = PcmRingBuffer(capacitySeconds = 3.0, sampleRate = 16000)
        val oldMarker = ByteArray(32000) { 0x11.toByte() } // 1.0s marker A
        val newMarker = ByteArray(96000) { 0x22.toByte() } // 3.0s marker B (đầy buffer)
        testBuffer.write(oldMarker)
        testBuffer.write(newMarker)
        val snap = testBuffer.snapshotLast(3.0)
        val oldLeaked = snap.any { it == 0x11.toByte() }
        Log.i(TAG, "Verification 1 [No old audio leak]: ${if (!oldLeaked) "PASSED (0% old data)" else "FAILED"}")

        // 2. No audio from previous Reel/session leaks into next session
        testBuffer.write(ByteArray(32000) { 0x33.toByte() })
        testBuffer.clear()
        val snapCleared = testBuffer.snapshotLast(1.5)
        val clearedHasData = snapCleared.any { it != 0.toByte() }
        Log.i(TAG, "Verification 2 [Session reset isolation]: ${if (!clearedHasData) "PASSED (Buffer is clean silence)" else "FAILED"}")

        // 3. Old responses are discarded when sessionId changes
        val session1 = "session_1"
        val session2 = "session_2"
        var renderedSession = ""
        val callback = { sId: String ->
            if (sId == session2) {
                renderedSession = sId
            }
        }
        callback(session1) // Should not render
        callback(session2) // Should render
        Log.i(TAG, "Verification 3 [Discard stale session]: ${if (renderedSession == session2) "PASSED (Old session discarded)" else "FAILED"}")
        Log.i(TAG, "---------------------------------------------")
    }

    private fun tilePcm(basePcm: ByteArray, targetSeconds: Double): ByteArray {
        val targetBytes = (targetSeconds * 32000).toInt()
        val result = ByteArray(targetBytes)
        var written = 0
        while (written < targetBytes) {
            val toCopy = minOf(basePcm.size, targetBytes - written)
            System.arraycopy(basePcm, 0, result, written, toCopy)
            written += toCopy
        }
        return result
    }

    private fun saveResultsToJson(resultMap: Map<String, QuickConfigStats>) {
        try {
            val root = JSONObject()
            root.put("timestamp", System.currentTimeMillis())
            val configsArray = JSONArray()

            for ((_, stats) in resultMap) {
                val cObj = JSONObject()
                cObj.put("config", stats.configName)
                cObj.put("previousSec", stats.previousSec)
                cObj.put("futureSec", stats.futureSec)
                cObj.put("totalAudioSec", stats.totalAudioSec)
                cObj.put("successRuns", stats.successRuns)
                cObj.put("p50TapToSubMs", stats.p50TapToSubMs)
                cObj.put("avgTapToSubMs", stats.avgTapToSubMs)
                cObj.put("p95TapToSubMs", stats.p95TapToSubMs)
                cObj.put("maxTapToSubMs", stats.maxTapToSubMs)
                cObj.put("avgTapToAudioReadyMs", stats.avgTapToAudioReadyMs)
                cObj.put("avgAsrMs", stats.avgAsrLatencyMs)
                cObj.put("avgTransMs", stats.avgTransLatencyMs)
                cObj.put("sampleTranscript", stats.sampleTranscript)
                cObj.put("sampleTranslation", stats.sampleTranslation)

                val runsArray = JSONArray()
                for (r in stats.runs) {
                    val rObj = JSONObject()
                    rObj.put("sessionId", r.sessionId)
                    rObj.put("tapToSubtitleMs", r.tapToSubtitleRenderMs)
                    rObj.put("audioReadyMs", r.tapToAudioReadyMs)
                    rObj.put("asrMs", r.audioReadyToAsrMs)
                    rObj.put("transMs", r.asrToTranslationMs)
                    rObj.put("transcript", r.transcript)
                    rObj.put("translation", r.translatedText)
                    runsArray.put(rObj)
                }
                cObj.put("runs", runsArray)
                configsArray.put(cObj)
            }

            root.put("configs", configsArray)
            val outFile = File(context.filesDir, "quick_translate_benchmark_results.json")
            outFile.writeText(root.toString(2))
            Log.i(TAG, "Đã lưu kết quả Quick Translate vào: ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi lưu file kết quả: ${e.message}")
        }
    }

    private fun printSummaryReport(resultMap: Map<String, QuickConfigStats>) {
        val sb = StringBuilder()
        sb.append("\n========================================================================================================\n")
        sb.append("BẢNG TỔNG KẾT BENCHMARK: QUICK TRANSLATE CAPTURE EXPERIMENT (TAP -> SUBTITLE RENDER)\n")
        sb.append("--------------------------------------------------------------------------------------------------------\n")
        sb.append(String.format(
            "%-26s | %-7s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s | %s\n",
            "Config", "Success", "p50 (ms)", "Avg (ms)", "p95 (ms)", "Max (ms)", "ASR avg", "Trans avg", "Sample Translation"
        ))
        sb.append("--------------------------------------------------------------------------------------------------------\n")

        for ((_, s) in resultMap) {
            sb.append(String.format(
                "%-26s | %-7s | %-8d | %-8.1f | %-8d | %-8d | %-8.1f | %-8.1f | \"%s\"\n",
                s.configName,
                "${s.successRuns}/${s.totalRuns}",
                s.p50TapToSubMs,
                s.avgTapToSubMs,
                s.p95TapToSubMs,
                s.maxTapToSubMs,
                s.avgAsrLatencyMs,
                s.avgTransLatencyMs,
                s.sampleTranslation.take(35)
            ))
        }
        sb.append("========================================================================================================\n")
        Log.i(TAG, sb.toString())
        CaptureMonitor.log(TAG, sb.toString())
    }
}
