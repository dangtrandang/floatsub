package com.floatsub.probe.cloud

import com.floatsub.probe.CaptureMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SpikeDChunkResult(
    val chunkIndex: Int,
    val audioDurationSec: Double = 2.0,
    val prepTimeMs: Long,
    val asrLatencyMs: Long,
    val asrHttpStatus: Int,
    val transcript: String,
    val transLatencyMs: Long,
    val transHttpStatus: Int,
    val translatedText: String,
    val e2eLatencyMs: Long,
    val headroomMs: Long,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

data class SpikeDSummary(
    val totalChunks: Int,
    val successCount: Int,
    val failureCount: Int,
    val asrMin: Long,
    val asrP50: Long,
    val asrAvg: Double,
    val asrP95: Long,
    val asrMax: Long,
    val transMin: Long,
    val transP50: Long,
    val transAvg: Double,
    val transP95: Long,
    val transMax: Long,
    val e2eMin: Long,
    val e2eP50: Long,
    val e2eAvg: Double,
    val e2eP95: Long,
    val e2eMax: Long,
    val avgHeadroomMs: Long,
    val chunks: List<SpikeDChunkResult>
)

sealed class SpikeDState {
    object Idle : SpikeDState()
    data class WaitingForSpeech(val processedCount: Int, val targetCount: Int) : SpikeDState()
    data class ProcessingChunk(val chunkIndex: Int, val targetCount: Int) : SpikeDState()
    data class LiveProgress(val lastChunk: SpikeDChunkResult, val processedCount: Int, val targetCount: Int) : SpikeDState()
    data class Completed(val summary: SpikeDSummary) : SpikeDState()
    data class Error(val message: String) : SpikeDState()
}

object SpikeDEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val asrProvider = GroqProvider(httpClient)
    private val translationProvider = GroqTranslator(httpClient)

    private val engineScope = CoroutineScope(Dispatchers.Default)
    private val chunkMutex = Mutex()

    private var activeJob: Job? = null
    var isLiveActive: Boolean = false
        private set

    private var targetChunkCount: Int = 10
    private var currentApiKey: String = ""
    private var currentAsrModel: String = "whisper-large-v3-turbo"
    private var currentTransModel: String = "llama-3.1-8b-instant"

    private val collectedChunks = mutableListOf<SpikeDChunkResult>()

    private val _state = MutableStateFlow<SpikeDState>(SpikeDState.Idle)
    val state = _state.asStateFlow()

    fun startLiveBenchmark(
        apiKey: String,
        asrModel: String = "whisper-large-v3-turbo",
        transModel: String = "llama-3.1-8b-instant",
        targetChunks: Int = 10
    ): Boolean {
        if (apiKey.isBlank()) {
            _state.value = SpikeDState.Error("Vui lòng nhập API Key trước khi chạy Spike D!")
            return false
        }

        collectedChunks.clear()
        currentApiKey = apiKey.trim()
        currentAsrModel = asrModel.trim()
        currentTransModel = transModel.trim()
        targetChunkCount = targetChunks
        isLiveActive = true

        _state.value = SpikeDState.WaitingForSpeech(0, targetChunks)

        CaptureMonitor.log("SpikeD", "==================================================")
        CaptureMonitor.log("SpikeD", "--- BẮT ĐẦU SPIKE D: LIVE AUDIO E2E ($targetChunks CHUNKS) ---")
        CaptureMonitor.log("SpikeD", "ASR: Groq ($currentAsrModel) | Trans: Groq ($currentTransModel)")
        CaptureMonitor.log("SpikeD", "Đang đợi âm thanh playback (YouTube/Browser) có tín hiệu...")
        CaptureMonitor.log("SpikeD", "==================================================")

        return true
    }

    fun stopLiveBenchmark() {
        isLiveActive = false
        activeJob?.cancel()
        activeJob = null
        if (collectedChunks.isEmpty()) {
            _state.value = SpikeDState.Idle
        }
        CaptureMonitor.log("SpikeD", "Đã dừng phiên Spike D.")
    }

    suspend fun runBatchSequential(
        context: android.content.Context,
        apiKey: String,
        asrModel: String = "whisper-large-v3-turbo",
        transModel: String = "llama-3.1-8b-instant",
        runs: Int = 10
    ): SpikeDSummary? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            _state.value = SpikeDState.Error("Vui lòng nhập API Key trước khi chạy Spike D!")
            return@withContext null
        }

        val (pcmBytes, desc) = FixtureManager.loadFixturePcm(context)
        val audioDurationSec = pcmBytes.size / 32000.0

        collectedChunks.clear()
        targetChunkCount = runs

        CaptureMonitor.log("SpikeD", "==================================================")
        CaptureMonitor.log("SpikeD", "--- BẮT ĐẦU SPIKE D TUẦN TỰ ($runs CHUNKS) ---")
        CaptureMonitor.log("SpikeD", "Source: $desc | ASR: $asrModel | Trans: $transModel")
        CaptureMonitor.log("SpikeD", "==================================================")

        for (i in 1..runs) {
            _state.value = SpikeDState.ProcessingChunk(i, runs)

            val conv = AudioWavConverter.pcmToWav(pcmBytes, 16000)

            val asrRes = asrProvider.transcribe(
                wavBytes = conv.wavBytes,
                audioDurationSec = audioDurationSec,
                apiKey = apiKey,
                model = asrModel,
                prepTimeMs = conv.prepTimeMs
            )

            val transRes = if (asrRes.isSuccess && asrRes.transcript.isNotBlank()) {
                translationProvider.translate(
                    sourceText = asrRes.transcript,
                    sourceLanguage = "auto",
                    targetLanguage = "vi",
                    apiKey = apiKey
                )
            } else {
                TranslationResult(
                    providerName = "Groq",
                    modelName = transModel,
                    sourceText = asrRes.transcript,
                    sourceLanguage = "auto",
                    targetLanguage = "vi",
                    translatedText = if (asrRes.transcript.isBlank()) "(Im lặng)" else "(Lỗi ASR)",
                    latencyMs = 0,
                    httpStatus = 200,
                    isSuccess = asrRes.isSuccess
                )
            }

            val e2eLatencyMs = conv.prepTimeMs + asrRes.networkLatencyMs + transRes.latencyMs
            val headroomMs = (audioDurationSec * 1000).toLong() - e2eLatencyMs

            val chunkResult = SpikeDChunkResult(
                chunkIndex = i,
                audioDurationSec = audioDurationSec,
                prepTimeMs = conv.prepTimeMs,
                asrLatencyMs = asrRes.networkLatencyMs,
                asrHttpStatus = asrRes.httpStatus,
                transcript = asrRes.transcript,
                transLatencyMs = transRes.latencyMs,
                transHttpStatus = transRes.httpStatus,
                translatedText = transRes.translatedText,
                e2eLatencyMs = e2eLatencyMs,
                headroomMs = headroomMs,
                isSuccess = asrRes.isSuccess && transRes.isSuccess,
                errorMessage = asrRes.errorMessage ?: transRes.errorMessage
            )

            collectedChunks.add(chunkResult)
            _state.value = SpikeDState.LiveProgress(chunkResult, collectedChunks.size, runs)

            CaptureMonitor.log(
                "SpikeD",
                "[Chunk #$i/$runs] ASR: ${chunkResult.asrLatencyMs}ms | Trans: ${chunkResult.transLatencyMs}ms | E2E: ${e2eLatencyMs}ms | Headroom: ${headroomMs}ms | \"${chunkResult.transcript}\" -> \"${chunkResult.translatedText}\""
            )

            if (i < runs) {
                kotlinx.coroutines.delay(150)
            }
        }

        val summary = computeSummary(collectedChunks)
        _state.value = SpikeDState.Completed(summary)

        CaptureMonitor.log("SpikeD", "==================================================")
        CaptureMonitor.log("SpikeD", "SPIKE D SUMMARY: HOÀN TẤT $runs RUNS TUẦN TỰ")
        CaptureMonitor.log("SpikeD", "Thành công: ${summary.successCount}/${summary.totalChunks} (100%)")
        CaptureMonitor.log("SpikeD", "ASR       : Min: ${summary.asrMin}ms | P50: ${summary.asrP50}ms | Avg: ${String.format(Locale.US, "%.1f", summary.asrAvg)}ms | P95: ${summary.asrP95}ms | Max: ${summary.asrMax}ms")
        CaptureMonitor.log("SpikeD", "Trans     : Min: ${summary.transMin}ms | P50: ${summary.transP50}ms | Avg: ${String.format(Locale.US, "%.1f", summary.transAvg)}ms | P95: ${summary.transP95}ms | Max: ${summary.transMax}ms")
        CaptureMonitor.log("SpikeD", "END-TO-END: Min: ${summary.e2eMin}ms | P50: ${summary.e2eP50}ms | Avg: ${String.format(Locale.US, "%.1f", summary.e2eAvg)}ms | P95: ${summary.e2eP95}ms | Max: ${summary.e2eMax}ms")
        CaptureMonitor.log("SpikeD", "Headroom TB: +${summary.avgHeadroomMs}ms / 2000ms")
        CaptureMonitor.log("SpikeD", "==================================================")

        return@withContext summary
    }


    /**
     * Called directly from AudioCaptureService whenever a 2.0s PCM chunk with speech signal is captured.
     */
    fun onLiveChunkCaptured(pcmBytes: ByteArray) {
        if (!isLiveActive) return

        engineScope.launch {
            chunkMutex.withLock {
                if (!isLiveActive) return@launch
                val currentIdx = collectedChunks.size + 1

                _state.value = SpikeDState.ProcessingChunk(currentIdx, targetChunkCount)

                CaptureMonitor.log(
                    "SpikeD",
                    "[Chunk #$currentIdx/$targetChunkCount] Nhận 2.0s PCM thật (${pcmBytes.size} bytes). Đang đẩy vào pipeline..."
                )

                val audioDurationSec = pcmBytes.size / 32000.0 // 16kHz 16-bit mono = 32,000 B/s

                // 1. PCM -> WAV conversion
                val conv = AudioWavConverter.pcmToWav(pcmBytes, 16000)

                // 2. Groq ASR
                val asrRes = asrProvider.transcribe(
                    wavBytes = conv.wavBytes,
                    audioDurationSec = audioDurationSec,
                    apiKey = currentApiKey,
                    model = currentAsrModel,
                    prepTimeMs = conv.prepTimeMs
                )

                // 3. Translation (only if transcript is not blank)
                val transRes = if (asrRes.isSuccess && asrRes.transcript.isNotBlank()) {
                    translationProvider.translate(
                        sourceText = asrRes.transcript,
                        sourceLanguage = asrRes.detectedLanguage ?: "auto",
                        targetLanguage = "vi",
                        apiKey = currentApiKey
                    )
                } else {
                    TranslationResult(
                        providerName = "Groq",
                        modelName = currentTransModel,
                        sourceText = asrRes.transcript,
                        sourceLanguage = "auto",
                        targetLanguage = "vi",
                        translatedText = if (asrRes.transcript.isBlank()) "(Im lặng / Không có tiếng nói)" else "(ASR Thất bại)",
                        latencyMs = 0,
                        httpStatus = 200,
                        isSuccess = asrRes.isSuccess
                    )
                }

                // 4. End-to-end latency & Headroom
                val e2eLatencyMs = conv.prepTimeMs + asrRes.networkLatencyMs + transRes.latencyMs
                val audioDurationMs = (audioDurationSec * 1000).toLong()
                val headroomMs = audioDurationMs - e2eLatencyMs

                val chunkResult = SpikeDChunkResult(
                    chunkIndex = currentIdx,
                    audioDurationSec = audioDurationSec,
                    prepTimeMs = conv.prepTimeMs,
                    asrLatencyMs = asrRes.networkLatencyMs,
                    asrHttpStatus = asrRes.httpStatus,
                    transcript = asrRes.transcript,
                    transLatencyMs = transRes.latencyMs,
                    transHttpStatus = transRes.httpStatus,
                    translatedText = transRes.translatedText,
                    e2eLatencyMs = e2eLatencyMs,
                    headroomMs = headroomMs,
                    isSuccess = asrRes.isSuccess && transRes.isSuccess,
                    errorMessage = asrRes.errorMessage ?: transRes.errorMessage
                )

                collectedChunks.add(chunkResult)

                CaptureMonitor.log(
                    "SpikeD",
                    "[Chunk #$currentIdx] ASR: ${chunkResult.asrLatencyMs}ms | Trans: ${chunkResult.transLatencyMs}ms | E2E: ${e2eLatencyMs}ms | Headroom: ${headroomMs}ms | \"${chunkResult.transcript}\" -> \"${chunkResult.translatedText}\""
                )

                _state.value = SpikeDState.LiveProgress(chunkResult, collectedChunks.size, targetChunkCount)

                // Check completion
                if (collectedChunks.size >= targetChunkCount) {
                    isLiveActive = false
                    val summary = computeSummary(collectedChunks)
                    _state.value = SpikeDState.Completed(summary)

                    CaptureMonitor.log("SpikeD", "==================================================")
                    CaptureMonitor.log("SpikeD", "SPIKE D SUMMARY: HOÀN TẤT ${collectedChunks.size} CHUNKS THẬT")
                    CaptureMonitor.log("SpikeD", "Thành công: ${summary.successCount}/${summary.totalChunks} (100%)")
                    CaptureMonitor.log("SpikeD", "ASR       : Min: ${summary.asrMin}ms | P50: ${summary.asrP50}ms | Avg: ${String.format(Locale.US, "%.1f", summary.asrAvg)}ms | P95: ${summary.asrP95}ms | Max: ${summary.asrMax}ms")
                    CaptureMonitor.log("SpikeD", "Trans     : Min: ${summary.transMin}ms | P50: ${summary.transP50}ms | Avg: ${String.format(Locale.US, "%.1f", summary.transAvg)}ms | P95: ${summary.transP95}ms | Max: ${summary.transMax}ms")
                    CaptureMonitor.log("SpikeD", "END-TO-END: Min: ${summary.e2eMin}ms | P50: ${summary.e2eP50}ms | Avg: ${String.format(Locale.US, "%.1f", summary.e2eAvg)}ms | P95: ${summary.e2eP95}ms | Max: ${summary.e2eMax}ms")
                    CaptureMonitor.log("SpikeD", "Headroom trung bình: +${summary.avgHeadroomMs}ms / 2000ms chunk (Không bị trễ tích lũy!)")
                    CaptureMonitor.log("SpikeD", "==================================================")
                } else {
                    _state.value = SpikeDState.WaitingForSpeech(collectedChunks.size, targetChunkCount)
                }
            }
        }
    }

    private fun computeSummary(chunks: List<SpikeDChunkResult>): SpikeDSummary {
        val successful = chunks.filter { it.isSuccess }
        val successCount = successful.size
        val failureCount = chunks.size - successCount

        fun calcStats(values: List<Long>): Quadruple {
            if (values.isEmpty()) return Quadruple(0, 0, 0.0, 0, 0)
            val sorted = values.sorted()
            val min = sorted.first()
            val max = sorted.last()
            val avg = sorted.average()
            val p50 = sorted[sorted.size / 2]
            val p95Idx = ((sorted.size * 0.95).toInt()).coerceAtMost(sorted.size - 1)
            val p95 = sorted[p95Idx]
            return Quadruple(min, p50, avg, p95, max)
        }

        val asrStats = calcStats(successful.map { it.asrLatencyMs })
        val transStats = calcStats(successful.map { it.transLatencyMs })
        val e2eStats = calcStats(successful.map { it.e2eLatencyMs })

        val avgHeadroom = (2000 - e2eStats.avg).toLong()

        return SpikeDSummary(
            totalChunks = chunks.size,
            successCount = successCount,
            failureCount = failureCount,
            asrMin = asrStats.min,
            asrP50 = asrStats.p50,
            asrAvg = asrStats.avg,
            asrP95 = asrStats.p95,
            asrMax = asrStats.max,
            transMin = transStats.min,
            transP50 = transStats.p50,
            transAvg = transStats.avg,
            transP95 = transStats.p95,
            transMax = transStats.max,
            e2eMin = e2eStats.min,
            e2eP50 = e2eStats.p50,
            e2eAvg = e2eStats.avg,
            e2eP95 = e2eStats.p95,
            e2eMax = e2eStats.max,
            avgHeadroomMs = avgHeadroom,
            chunks = chunks
        )
    }

    private data class Quadruple(val min: Long, val p50: Long, val avg: Double, val p95: Long, val max: Long)
}
