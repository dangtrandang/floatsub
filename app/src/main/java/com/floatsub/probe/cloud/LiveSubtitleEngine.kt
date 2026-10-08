package com.floatsub.probe.cloud

import android.util.Log
import com.floatsub.probe.CaptureMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections

data class CapturedAudioChunk(
    val sequenceNumber: Long,
    val pcmBytes: ByteArray,
    val audioDurationSec: Double,
    val audioStartTimestamp: Long,
    val audioEndTimestamp: Long
)

data class LiveSubtitleResult(
    val chunkIndex: Long,
    val audioDurationSec: Double,
    val audioStartTimestamp: Long,
    val audioEndTimestamp: Long,
    val captureCompleteTimestamp: Long,
    val asrStartTimestamp: Long,
    val asrEndTimestamp: Long,
    val translationStartTimestamp: Long,
    val translationEndTimestamp: Long,
    val overlayRenderTimestamp: Long,

    val captureWaitMs: Long,
    val asrLatencyMs: Long,
    val transLatencyMs: Long,
    val processingLatencyMs: Long,
    val perceivedLatencyMs: Long,

    val asrHttpStatus: Int,
    val transcript: String,
    val detectedLanguage: String?,
    val transHttpStatus: Int,
    val translatedText: String,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

sealed class LiveSubtitleState {
    object Idle : LiveSubtitleState()
    object Listening : LiveSubtitleState()
    data class Processing(val chunkIndex: Int) : LiveSubtitleState()
    data class NewSubtitle(val result: LiveSubtitleResult) : LiveSubtitleState()
    data class Error(val message: String) : LiveSubtitleState()
}

object LiveSubtitleEngine {

    private val engineScope = CoroutineScope(Dispatchers.IO)

    private var chunkChannel: Channel<CapturedAudioChunk>? = null
    private var workerJob: Job? = null

    var isLiveActive: Boolean = false
        private set

    private var asrProvider: CloudAsrProvider? = null
    private var translationProvider: TranslationProvider? = null
    private var asrApiKey: String = ""
    private var transApiKey: String = ""
    private var asrModel: String = ""
    private var sourceLanguage: String = "auto"
    private var targetLanguage: String = "vi"

    private var lastRenderedSequenceNumber: Long = 0L

    // Metrics tracking
    private val perceivedLatencies = Collections.synchronizedList(ArrayList<Long>())
    private val asrLatencies = Collections.synchronizedList(ArrayList<Long>())
    private val transLatencies = Collections.synchronizedList(ArrayList<Long>())
    private val processingLatencies = Collections.synchronizedList(ArrayList<Long>())

    var totalChunksSubmitted: Long = 0L
        private set
    var totalChunksProcessed: Long = 0L
        private set
    var droppedChunksCount: Long = 0L
        private set
    var outOfOrderCount: Long = 0L
        private set

    private val _state = MutableStateFlow<LiveSubtitleState>(LiveSubtitleState.Idle)
    val state = _state.asStateFlow()

    fun startLive(
        asr: CloudAsrProvider,
        trans: TranslationProvider,
        asrKey: String,
        transKey: String,
        model: String,
        sourceLang: String,
        targetLang: String
    ): Boolean {
        if (asrKey.isBlank()) {
            _state.value = LiveSubtitleState.Error("Vui lòng nhập API Key cho ASR (${asr.providerName})!")
            return false
        }
        if (transKey.isBlank()) {
            _state.value = LiveSubtitleState.Error("Vui lòng nhập API Key cho Translation (${trans.providerName})!")
            return false
        }

        asrProvider = asr
        translationProvider = trans
        asrApiKey = asrKey.trim()
        transApiKey = transKey.trim()
        asrModel = model.trim()
        sourceLanguage = sourceLang.trim()
        targetLanguage = targetLang.trim()

        totalChunksSubmitted = 0L
        totalChunksProcessed = 0L
        droppedChunksCount = 0L
        outOfOrderCount = 0L
        lastRenderedSequenceNumber = 0L

        perceivedLatencies.clear()
        asrLatencies.clear()
        transLatencies.clear()
        processingLatencies.clear()

        // Bounded channel với capacity = 1 và DROP_OLDEST
        // Nếu network processing chậm hơn audio capture, stale chunk được drop ngay lập tức,
        // giữ subtitle luôn ở LIVE edge của video, không bao giờ trễ dồn tích lũy!
        chunkChannel = Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        isLiveActive = true
        _state.value = LiveSubtitleState.Listening

        CaptureMonitor.log("LiveEngine", "==================================================")
        CaptureMonitor.log("LiveEngine", "--- BẮT ĐẦU LIVE SUBTITLE PIPELINE (REALTIME ENGINE) ---")
        CaptureMonitor.log("LiveEngine", "ASR Provider: ${asr.providerName} ($asrModel)")
        CaptureMonitor.log("LiveEngine", "Trans Provider: ${trans.providerName}")
        CaptureMonitor.log("LiveEngine", "Language: [$sourceLanguage -> $targetLanguage]")
        CaptureMonitor.log("LiveEngine", "Pipeline: Capture và Network xử lý song song, bảo vệ độ trễ tối đa")
        CaptureMonitor.log("LiveEngine", "==================================================")

        startWorker()
        return true
    }

    private fun startWorker() {
        workerJob?.cancel()
        workerJob = engineScope.launch {
            val channel = chunkChannel ?: return@launch
            for (chunk in channel) {
                if (!isLiveActive) break
                processSingleChunk(chunk)
            }
        }
    }

    fun stopLive() {
        isLiveActive = false
        workerJob?.cancel()
        workerJob = null
        chunkChannel?.close()
        chunkChannel = null
        _state.value = LiveSubtitleState.Idle

        CaptureMonitor.log("LiveEngine", "Đã dừng Live Subtitle Pipeline.")
        CaptureMonitor.log("LiveEngine", getSummaryReport())
    }

    /**
     * Nhận chunk âm thanh được capture độc lập từ AudioCaptureService.
     * Không block luồng ghi âm PCM AudioRecord.
     */
    fun onLiveChunkCaptured(chunk: CapturedAudioChunk) {
        if (!isLiveActive) return
        totalChunksSubmitted++

        val channel = chunkChannel ?: return
        val sent = channel.trySend(chunk)
        if (!sent.isSuccess) {
            droppedChunksCount++
            CaptureMonitor.log("LiveEngine", "[Queue] Drop chunk #${chunk.sequenceNumber} do hàng đợi đầy")
        }
    }

    private suspend fun processSingleChunk(chunk: CapturedAudioChunk) {
        val asr = asrProvider ?: return
        val trans = translationProvider ?: return

        _state.value = LiveSubtitleState.Processing(chunk.sequenceNumber.toInt())

        val audioWaitMs = chunk.audioEndTimestamp - chunk.audioStartTimestamp
        val captureComplete = chunk.audioEndTimestamp

        // 1. Chuyển đổi PCM -> WAV
        val prepStart = System.currentTimeMillis()
        val conv = AudioWavConverter.pcmToWav(chunk.pcmBytes, 16000)
        val prepTimeMs = System.currentTimeMillis() - prepStart

        // 2. Cloud ASR Transcribe
        val asrStartTimestamp = System.currentTimeMillis()
        val asrRes = asr.transcribe(
            wavBytes = conv.wavBytes,
            audioDurationSec = chunk.audioDurationSec,
            apiKey = asrApiKey,
            model = asrModel,
            prepTimeMs = prepTimeMs,
            languageCode = if (sourceLanguage.equals("auto", ignoreCase = true)) null else sourceLanguage
        )
        val asrEndTimestamp = System.currentTimeMillis()
        val asrLatencyMs = asrEndTimestamp - asrStartTimestamp

        // 3. Translation
        val transStartTimestamp = System.currentTimeMillis()
        val transRes = if (asrRes.isSuccess && asrRes.transcript.isNotBlank()) {
            val effectiveSourceLang = if (sourceLanguage.equals("auto", ignoreCase = true)) {
                asrRes.detectedLanguage ?: "auto"
            } else {
                sourceLanguage
            }
            trans.translate(
                sourceText = asrRes.transcript,
                sourceLanguage = effectiveSourceLang,
                targetLanguage = targetLanguage,
                apiKey = transApiKey
            )
        } else {
            TranslationResult(
                providerName = trans.providerName,
                modelName = "",
                sourceText = asrRes.transcript,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translatedText = "",
                latencyMs = 0,
                httpStatus = 200,
                isSuccess = asrRes.isSuccess
            )
        }
        val transEndTimestamp = System.currentTimeMillis()
        val transLatencyMs = transEndTimestamp - transStartTimestamp

        // 4. Processing Latency (thời gian xử lý mạng kể từ khi thu xong audio)
        val processingLatencyMs = transEndTimestamp - captureComplete

        // 5. Overlay Render Timestamp
        val overlayRenderTimestamp = System.currentTimeMillis()

        // 6. TOTAL PERCEIVED LATENCY (từ lúc bắt đầu phát audio đến khi phụ đề hiển thị)
        val perceivedLatencyMs = overlayRenderTimestamp - chunk.audioStartTimestamp

        // Task 5: Stale Result & Out-of-order Protection
        if (chunk.sequenceNumber < lastRenderedSequenceNumber) {
            outOfOrderCount++
            CaptureMonitor.log(
                "LiveEngine",
                "[Stale] Bỏ qua Chunk #${chunk.sequenceNumber} vì đã hiển thị Chunk #$lastRenderedSequenceNumber"
            )
            return
        }
        lastRenderedSequenceNumber = chunk.sequenceNumber
        totalChunksProcessed++

        // Ghi nhận metrics
        perceivedLatencies.add(perceivedLatencyMs)
        asrLatencies.add(asrLatencyMs)
        transLatencies.add(transLatencyMs)
        processingLatencies.add(processingLatencyMs)

        val result = LiveSubtitleResult(
            chunkIndex = chunk.sequenceNumber,
            audioDurationSec = chunk.audioDurationSec,
            audioStartTimestamp = chunk.audioStartTimestamp,
            audioEndTimestamp = chunk.audioEndTimestamp,
            captureCompleteTimestamp = captureComplete,
            asrStartTimestamp = asrStartTimestamp,
            asrEndTimestamp = asrEndTimestamp,
            translationStartTimestamp = transStartTimestamp,
            translationEndTimestamp = transEndTimestamp,
            overlayRenderTimestamp = overlayRenderTimestamp,
            captureWaitMs = audioWaitMs,
            asrLatencyMs = asrLatencyMs,
            transLatencyMs = transLatencyMs,
            processingLatencyMs = processingLatencyMs,
            perceivedLatencyMs = perceivedLatencyMs,
            asrHttpStatus = asrRes.httpStatus,
            transcript = asrRes.transcript,
            detectedLanguage = asrRes.detectedLanguage,
            transHttpStatus = transRes.httpStatus,
            translatedText = transRes.translatedText,
            isSuccess = asrRes.isSuccess && transRes.isSuccess,
            errorMessage = asrRes.errorMessage ?: transRes.errorMessage
        )

        // Log đúng định dạng yêu cầu Task 1
        CaptureMonitor.log(
            "LiveEngine",
            "Chunk #${chunk.sequenceNumber} | AudioWindow=${audioWaitMs}ms | ASR=${asrLatencyMs}ms | Translation=${transLatencyMs}ms | Processing=${processingLatencyMs}ms | PerceivedLatency=${perceivedLatencyMs}ms | [${result.detectedLanguage ?: sourceLanguage}->$targetLanguage] \"${result.transcript}\" -> \"${result.translatedText}\""
        )

        Log.i(
            "LiveLatency",
            "Chunk #${chunk.sequenceNumber} | AudioWindow=${audioWaitMs}ms | ASR=${asrLatencyMs}ms | Translation=${transLatencyMs}ms | Processing=${processingLatencyMs}ms | PerceivedLatency=${perceivedLatencyMs}ms | Text: \"${result.translatedText}\""
        )

        _state.value = LiveSubtitleState.NewSubtitle(result)

        // Cứ mỗi 5 chunk in bảng tổng hợp thống kê
        if (totalChunksProcessed % 5 == 0L) {
            CaptureMonitor.log("LiveEngine", getSummaryReport())
        }
    }

    fun getSummaryReport(): String {
        synchronized(perceivedLatencies) {
            if (perceivedLatencies.isEmpty()) return "Chưa có dữ liệu chunk nào hoàn thành."
            val sortedPerceived = perceivedLatencies.sorted()
            val p50 = sortedPerceived[sortedPerceived.size / 2]
            val p95Idx = ((sortedPerceived.size * 0.95).toInt()).coerceAtMost(sortedPerceived.size - 1)
            val p95 = sortedPerceived[p95Idx]
            val maxPerceived = sortedPerceived.last()
            val avgPerceived = sortedPerceived.average().toLong()

            val avgAsr = if (asrLatencies.isNotEmpty()) asrLatencies.average().toLong() else 0L
            val avgTrans = if (transLatencies.isNotEmpty()) transLatencies.average().toLong() else 0L
            val avgProc = if (processingLatencies.isNotEmpty()) processingLatencies.average().toLong() else 0L

            val dropped = (totalChunksSubmitted - totalChunksProcessed).coerceAtLeast(0)

            return """
                ==================================================
                [LIVE SUBTITLE STATS SUMMARY]
                Total Chunks Submitted: $totalChunksSubmitted
                Total Chunks Processed: $totalChunksProcessed
                Dropped Chunks: $dropped
                Out-of-order Chunks: $outOfOrderCount
                Queue Depth: 1 (Bounded, Latest-First)
                Avg ASR Latency: ${avgAsr}ms
                Avg Translation Latency: ${avgTrans}ms
                Avg Processing Latency: ${avgProc}ms
                Avg Perceived Latency: ${avgPerceived}ms
                p50 Perceived Latency: ${p50}ms
                p95 Perceived Latency: ${p95}ms
                Max Perceived Latency: ${maxPerceived}ms
                ==================================================
            """.trimIndent()
        }
    }
}
