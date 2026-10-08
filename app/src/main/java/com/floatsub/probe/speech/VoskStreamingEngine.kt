package com.floatsub.probe.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

sealed class VoskSpeechEvent {
    data class Partial(val text: String) : VoskSpeechEvent()
    data class Final(val text: String) : VoskSpeechEvent()
}

class VoskStreamingEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoskStreaming"
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var lastPartialText = ""

    private val _speechEvents = MutableSharedFlow<VoskSpeechEvent>(extraBufferCapacity = 64)
    val speechEvents: SharedFlow<VoskSpeechEvent> = _speechEvents.asSharedFlow()

    @Volatile
    var isInitialized: Boolean = false
        private set

    /**
     * Khởi tạo mô hình và mở Recognizer sống lâu dài (Long-lived session)
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        val candidatePaths = listOf(
            File(context.filesDir, "model-en"),
            File("/data/local/tmp/model-en"),
            File(context.getExternalFilesDir("models"), "model-en")
        )

        var foundDir: File? = null
        for (dir in candidatePaths) {
            if (dir.exists() && File(dir, "am").exists()) {
                foundDir = dir
                break
            }
        }

        if (foundDir == null) {
            Log.e(TAG, "Không tìm thấy thư mục model-en!")
            return@withContext false
        }

        try {
            Log.i(TAG, "Đang nạp mô hình Vosk từ ${foundDir.absolutePath}...")
            val m = Model(foundDir.absolutePath)
            // Khởi tạo Recognizer duy nhất với cấu hình siêu nhẹ (disable word timestamps & alternatives)
            val rec = Recognizer(m, 16000.0f).apply {
                setWords(false)
                setMaxAlternatives(0)
            }

            model = m
            recognizer = rec
            isInitialized = true
            Log.i(TAG, "Vosk Streaming Session đã sẵn sàng!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi nạp Vosk Streaming: ${e.message}", e)
            false
        }
    }

    /**
     * Nhận mẩu PCM ~100ms (1600 samples) từ AudioRecord và xử lý tức thời
     */
    fun feedPcmChunk(pcmBytes: ByteArray, length: Int) {
        val rec = recognizer ?: return
        if (length <= 0) return

        try {
            val isFinalBoundary = rec.acceptWaveForm(pcmBytes, length)

            if (isFinalBoundary) {
                // Đã chạm ranh giới ngắt nghỉ / kết thúc câu
                val json = rec.result
                val text = JSONObject(json).optString("text", "").trim()
                if (text.isNotBlank()) {
                    lastPartialText = ""
                    _speechEvents.tryEmit(VoskSpeechEvent.Final(text))
                }
            } else {
                // Đang trong quá trình nói -> lấy partialResult thời gian thực
                val json = rec.partialResult
                val partial = JSONObject(json).optString("partial", "").trim()
                if (partial.isNotBlank() && partial != lastPartialText) {
                    lastPartialText = partial
                    _speechEvents.tryEmit(VoskSpeechEvent.Partial(partial))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi xử lý audio chunk: ${e.message}")
        }
    }

    fun finishSession(): String {
        val rec = recognizer ?: return ""
        return try {
            val json = rec.finalResult
            val text = JSONObject(json).optString("text", "").trim()
            lastPartialText = ""
            text
        } catch (e: Exception) {
            ""
        }
    }

    fun close() {
        try {
            recognizer?.close()
            recognizer = null
            model?.close()
            model = null
            isInitialized = false
        } catch (_: Exception) {}
    }
}
