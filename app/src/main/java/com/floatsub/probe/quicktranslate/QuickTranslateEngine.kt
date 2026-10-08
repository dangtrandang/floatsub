package com.floatsub.probe.quicktranslate

import android.content.Context
import android.util.Log
import com.floatsub.probe.CaptureMonitor
import com.floatsub.probe.cloud.ApiKeyManager
import com.floatsub.probe.cloud.AudioWavConverter
import com.floatsub.probe.cloud.GroqProvider
import com.floatsub.probe.cloud.GroqTranslator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class QuickTranslateTimestamps(
    val tapTimestamp: Long,
    val snapshotTimestamp: Long,
    val captureFutureStartTimestamp: Long,
    val captureFutureEndTimestamp: Long,
    val asrStartTimestamp: Long,
    val asrEndTimestamp: Long,
    val translationStartTimestamp: Long,
    val translationEndTimestamp: Long,
    val overlayRenderTimestamp: Long
)

data class QuickTranslateMetrics(
    val sessionId: String,
    val previousSec: Double,
    val futureSec: Double,
    val totalAudioSec: Double,
    val timestamps: QuickTranslateTimestamps,
    val tapToAudioReadyMs: Long,
    val audioReadyToAsrMs: Long,
    val asrToTranslationMs: Long,
    val tapToSubtitleRenderMs: Long,
    val transcript: String,
    val translatedText: String,
    val isSuccess: Boolean,
    val isDiscarded: Boolean = false,
    val errorMessage: String? = null
)

object QuickTranslateEngine {

    private const val TAG = "QuickTranslate"

    // 16kHz 16-bit Mono Ring buffer (3.0 seconds capacity)
    val ringBuffer = PcmRingBuffer(capacitySeconds = 3.0, sampleRate = 16000)

    private val sessionCounter = AtomicLong(0)
    private val activeSessionId = AtomicReference<String>("")

    private val engineScope = CoroutineScope(Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val groqAsr = GroqProvider(httpClient)
    private val groqTranslator = GroqTranslator(httpClient)

    // Future collector map (sessionId -> ActiveFutureCollector)
    private class FutureCollector(
        val targetBytes: Int,
        val deferred: CompletableDeferred<ByteArray>
    ) {
        val stream = ByteArrayOutputStream(targetBytes)
    }
    private val activeFutureCollectors = ConcurrentHashMap<String, FutureCollector>()

    /**
     * Nhận stream PCM 16kHz từ AudioCaptureService.
     * Khi idle: CHỈ ghi vào ring buffer RAM, KHÔNG gọi API, KHÔNG ASR, KHÔNG dịch.
     */
    fun onPcmData(samples: ShortArray, count: Int) {
        if (count <= 0) return
        ringBuffer.write(samples, 0, count)

        // Nếu có session đang cần gom tương lai:
        if (activeFutureCollectors.isNotEmpty()) {
            val byteCount = count * 2
            val bytes = ByteArray(byteCount)
            var bIdx = 0
            for (i in 0 until count) {
                val s = samples[i].toInt()
                bytes[bIdx++] = (s and 0xFF).toByte()
                bytes[bIdx++] = ((s shr 8) and 0xFF).toByte()
            }
            feedFutureCollectors(bytes, byteCount)
        }
    }

    /**
     * Nhận mảng bytes PCM trực tiếp.
     */
    fun onPcmBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length <= 0) return
        ringBuffer.write(bytes, offset, length)

        if (activeFutureCollectors.isNotEmpty()) {
            val slice = if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length)
            feedFutureCollectors(slice, length)
        }
    }

    private fun feedFutureCollectors(bytes: ByteArray, length: Int) {
        for ((sId, collector) in activeFutureCollectors) {
            synchronized(collector) {
                collector.stream.write(bytes, 0, length)
                if (collector.stream.size() >= collector.targetBytes) {
                    if (!collector.deferred.isCompleted) {
                        collector.deferred.complete(collector.stream.toByteArray().copyOfRange(0, collector.targetBytes))
                    }
                    activeFutureCollectors.remove(sId)
                }
            }
        }
    }

    /**
     * Xử lý sự kiện TAP từ người dùng:
     * 1. Sinh sessionId mới
     * 2. Snapshot [previousSec] từ ring buffer
     * 3. Chờ thu [futureSec]
     * 4. Ghép audio -> Groq ASR -> Groq Translation
     * 5. Kiểm tra sessionId trước khi render overlay
     */
    suspend fun executeTapToTranslate(
        context: Context,
        previousSec: Double,
        futureSec: Double,
        simulatedFutureProvider: (suspend (Int) -> ByteArray)? = null
    ): QuickTranslateMetrics = withContext(Dispatchers.IO) {
        val tapTimestamp = System.currentTimeMillis()
        val sessionId = "session_${sessionCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        activeSessionId.set(sessionId)

        Log.i(TAG, "[$sessionId] >>> TAP NHẬN ĐƯỢC: Prev=${previousSec}s, Future=${futureSec}s <<<")

        // 1. Snapshot previous audio
        val snapshotTimestamp = System.currentTimeMillis()
        val previousPcm = ringBuffer.snapshotLast(previousSec)

        // 2. Thu tiếp future audio
        val captureFutureStartTimestamp = System.currentTimeMillis()
        val targetFutureBytes = (futureSec * 32000).toInt()

        val futurePcm = if (simulatedFutureProvider != null) {
            // Chạy benchmark mô phỏng streaming
            simulatedFutureProvider(targetFutureBytes)
        } else {
            // Chạy live từ audio capture stream
            val deferred = CompletableDeferred<ByteArray>()
            val collector = FutureCollector(targetFutureBytes, deferred)
            activeFutureCollectors[sessionId] = collector

            val maxWaitMs = (futureSec * 1000).toLong() + 500L
            val result = withTimeoutOrNull(maxWaitMs) {
                deferred.await()
            }
            activeFutureCollectors.remove(sessionId)
            result ?: collector.stream.toByteArray()
        }

        val captureFutureEndTimestamp = System.currentTimeMillis()

        // Kiểm tra xem session có bị hủy bởi Tap mới không
        if (activeSessionId.get() != sessionId) {
            Log.w(TAG, "[$sessionId] BỊ HỦY vì người dùng đã TAP phiên mới!")
            return@withContext createDiscardedResult(
                sessionId, previousSec, futureSec,
                tapTimestamp, snapshotTimestamp, captureFutureStartTimestamp, captureFutureEndTimestamp
            )
        }

        // 3. Ghép audio: Previous + Future = 1 WAV segment
        val combinedPcm = ByteArray(previousPcm.size + futurePcm.size)
        System.arraycopy(previousPcm, 0, combinedPcm, 0, previousPcm.size)
        System.arraycopy(futurePcm, 0, combinedPcm, previousPcm.size, futurePcm.size)

        val totalAudioSec = combinedPcm.size / 32000.0
        val wavConv = AudioWavConverter.pcmToWav(combinedPcm, 16000)

        // 4. Lấy API keys
        val apiKeyManager = ApiKeyManager(context)
        val groqKey = apiKeyManager.getApiKey("groq").ifBlank {
            System.getenv("GROQ_API_KEY") ?: ""
        }

        // 5. Groq Whisper ASR
        val asrStartTimestamp = System.currentTimeMillis()
        val asrRes = groqAsr.transcribe(
            wavBytes = wavConv.wavBytes,
            audioDurationSec = totalAudioSec,
            apiKey = groqKey,
            model = "whisper-large-v3-turbo",
            prepTimeMs = 1
        )
        val asrEndTimestamp = System.currentTimeMillis()

        if (activeSessionId.get() != sessionId) {
            Log.w(TAG, "[$sessionId] BỊ HỦY sau ASR vì đã có session mới!")
            return@withContext createDiscardedResult(
                sessionId, previousSec, futureSec,
                tapTimestamp, snapshotTimestamp, captureFutureStartTimestamp, captureFutureEndTimestamp,
                asrStartTimestamp, asrEndTimestamp
            )
        }

        if (!asrRes.isSuccess || asrRes.transcript.isBlank()) {
            val endTs = System.currentTimeMillis()
            return@withContext QuickTranslateMetrics(
                sessionId = sessionId,
                previousSec = previousSec,
                futureSec = futureSec,
                totalAudioSec = totalAudioSec,
                timestamps = QuickTranslateTimestamps(
                    tapTimestamp, snapshotTimestamp, captureFutureStartTimestamp, captureFutureEndTimestamp,
                    asrStartTimestamp, asrEndTimestamp, endTs, endTs, endTs
                ),
                tapToAudioReadyMs = captureFutureEndTimestamp - tapTimestamp,
                audioReadyToAsrMs = asrEndTimestamp - asrStartTimestamp,
                asrToTranslationMs = 0,
                tapToSubtitleRenderMs = endTs - tapTimestamp,
                transcript = asrRes.transcript,
                translatedText = "",
                isSuccess = false,
                errorMessage = asrRes.errorMessage ?: "ASR rỗng"
            )
        }

        // 6. Groq Translation
        val translationStartTimestamp = System.currentTimeMillis()
        val transRes = groqTranslator.translate(
            sourceText = asrRes.transcript,
            sourceLanguage = asrRes.detectedLanguage ?: "auto",
            targetLanguage = "vi",
            apiKey = groqKey
        )
        val translationEndTimestamp = System.currentTimeMillis()

        if (activeSessionId.get() != sessionId) {
            Log.w(TAG, "[$sessionId] BỊ HỦY sau Translation vì đã có session mới!")
            return@withContext createDiscardedResult(
                sessionId, previousSec, futureSec,
                tapTimestamp, snapshotTimestamp, captureFutureStartTimestamp, captureFutureEndTimestamp,
                asrStartTimestamp, asrEndTimestamp, translationStartTimestamp, translationEndTimestamp
            )
        }

        // 7. Overlay Render Timestamp
        val overlayRenderTimestamp = System.currentTimeMillis()

        val tapToAudioReadyMs = captureFutureEndTimestamp - tapTimestamp
        val audioReadyToAsrMs = asrEndTimestamp - asrStartTimestamp
        val asrToTranslationMs = translationEndTimestamp - translationStartTimestamp
        val tapToSubtitleRenderMs = overlayRenderTimestamp - tapTimestamp

        Log.i(
            TAG,
            "[$sessionId] HOÀN TẤT: Tap->Render=${tapToSubtitleRenderMs}ms | AudioReady=${tapToAudioReadyMs}ms | ASR=${audioReadyToAsrMs}ms | Trans=${asrToTranslationMs}ms | Trans: \"${transRes.translatedText}\""
        )

        QuickTranslateMetrics(
            sessionId = sessionId,
            previousSec = previousSec,
            futureSec = futureSec,
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
            translatedText = transRes.translatedText,
            isSuccess = transRes.isSuccess,
            isDiscarded = false,
            errorMessage = transRes.errorMessage
        )
    }

    fun resetBuffer() {
        ringBuffer.clear()
        activeFutureCollectors.clear()
        activeSessionId.set("")
    }

    fun getActiveSessionId(): String = activeSessionId.get()

    private fun createDiscardedResult(
        sessionId: String,
        prevSec: Double,
        futSec: Double,
        tapTs: Long,
        snapTs: Long,
        futStartTs: Long,
        futEndTs: Long,
        asrStartTs: Long = 0,
        asrEndTs: Long = 0,
        transStartTs: Long = 0,
        transEndTs: Long = 0
    ): QuickTranslateMetrics {
        val now = System.currentTimeMillis()
        return QuickTranslateMetrics(
            sessionId = sessionId,
            previousSec = prevSec,
            futureSec = futSec,
            totalAudioSec = prevSec + futSec,
            timestamps = QuickTranslateTimestamps(
                tapTs, snapTs, futStartTs, futEndTs,
                asrStartTs, asrEndTs, transStartTs, transEndTs, now
            ),
            tapToAudioReadyMs = futEndTs - tapTs,
            audioReadyToAsrMs = if (asrEndTs > 0) asrEndTs - asrStartTs else 0,
            asrToTranslationMs = if (transEndTs > 0) transEndTs - transStartTs else 0,
            tapToSubtitleRenderMs = now - tapTs,
            transcript = "",
            translatedText = "",
            isSuccess = false,
            isDiscarded = true,
            errorMessage = "Discarded: user triggered newer tap session"
        )
    }
}
