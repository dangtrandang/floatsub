package com.floatsub.probe.local

import android.content.Context
import android.util.Log
import com.floatsub.probe.CaptureMonitor
import com.floatsub.probe.speech.VoskSpeechEvent
import com.floatsub.probe.speech.VoskStreamingEngine
import com.floatsub.probe.translation.MlKitTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StreamingSubtitle(
    val englishText: String,
    val vietnameseText: String,
    val isFinal: Boolean,
    val transLatencyMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class OnDeviceStreamingState {
    object Idle : OnDeviceStreamingState()
    object Initializing : OnDeviceStreamingState()
    object Listening : OnDeviceStreamingState()
    data class Streaming(val subtitle: StreamingSubtitle) : OnDeviceStreamingState()
    data class Error(val message: String) : OnDeviceStreamingState()
}

object OnDeviceStreamingEngine {

    private const val TAG = "OnDeviceStreaming"
    private val scope = CoroutineScope(Dispatchers.IO)

    private var voskEngine: VoskStreamingEngine? = null
    private var mlKitTranslator: MlKitTranslator? = null

    private var audioChannel: Channel<ByteArray>? = null
    private var audioWorkerJob: Job? = null
    private var speechCollectorJob: Job? = null
    private var partialDebounceJob: Job? = null

    // Cấu hình thời gian dừng đọc tối thiểu (Điểm ngắt giữ câu dịch trên màn hình)
    private const val MIN_READING_LINGER_MS = 2200L
    private const val PARTIAL_DEBOUNCE_MS = 400L

    @Volatile
    private var lastCommittedVi: String = ""
    @Volatile
    private var lastCommittedTimestamp: Long = 0L
    @Volatile
    private var currentDisplayVi: String = ""

    @Volatile
    var isRunning: Boolean = false
        private set

    private val _state = MutableStateFlow<OnDeviceStreamingState>(OnDeviceStreamingState.Idle)
    val state: StateFlow<OnDeviceStreamingState> = _state.asStateFlow()

    private val _subtitleEvents = MutableSharedFlow<StreamingSubtitle>(extraBufferCapacity = 64)
    val subtitleEvents: SharedFlow<StreamingSubtitle> = _subtitleEvents.asSharedFlow()

    private val _latestSubtitle = MutableStateFlow<StreamingSubtitle?>(null)
    val latestSubtitle: StateFlow<StreamingSubtitle?> = _latestSubtitle.asStateFlow()

    /**
     * Khởi động toàn bộ pipeline On-Device Streaming:
     * 1. Nạp mô hình Vosk Offline (model-en)
     * 2. Tải/kiểm tra mô hình Google ML Kit Translate Offline (EN -> VI)
     * 3. Mở kênh nhận âm thanh và xử lý tức thời
     */
    suspend fun start(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) {
            Log.w(TAG, "OnDeviceStreamingEngine đã đang chạy.")
            return@withContext true
        }

        _state.value = OnDeviceStreamingState.Initializing
        CaptureMonitor.log(TAG, "Khởi động mô hình On-Device Streaming (Vosk + ML Kit)...")

        try {
            // 1. Khởi tạo Vosk Engine
            val vosk = VoskStreamingEngine(context.applicationContext)
            val voskOk = vosk.initialize()
            if (!voskOk) {
                val err = "Không thể nạp mô hình Vosk (kiểm tra thư mục model-en)!"
                _state.value = OnDeviceStreamingState.Error(err)
                CaptureMonitor.log(TAG, "LỖI: $err")
                return@withContext false
            }
            voskEngine = vosk

            // 2. Khởi tạo Google ML Kit Translator
            val mlKit = MlKitTranslator()
            val mlKitOk = mlKit.prepareModel()
            if (!mlKitOk) {
                Log.w(TAG, "Cảnh báo: ML Kit model chưa hoàn tất tải ngầm, sẽ thử tải tiếp trong quá trình dịch.")
            }
            mlKitTranslator = mlKit

            // Reset trạng thái hiển thị
            lastCommittedVi = ""
            lastCommittedTimestamp = 0L
            currentDisplayVi = ""

            // 3. Chuẩn bị Audio Channel
            val channel = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            audioChannel = channel

            // 4. Worker tiêu thụ PCM chunks và đẩy vào Vosk
            audioWorkerJob = scope.launch {
                for (pcmChunk in channel) {
                    vosk.feedPcmChunk(pcmChunk, pcmChunk.size)
                }
            }

            // 5. Worker lắng nghe sự kiện lời nói từ Vosk với bộ điều phối điểm ngắt (Pacing & Breakpoints)
            speechCollectorJob = scope.launch {
                vosk.speechEvents.collect { event ->
                    when (event) {
                        is VoskSpeechEvent.Final -> {
                            // Ranh giới ngắt câu / dứt lời (Primary Breakpoint)
                            partialDebounceJob?.cancel()
                            val enFinal = event.text.trim()
                            if (enFinal.isNotBlank()) {
                                val startTransTs = System.currentTimeMillis()
                                val rawVi = mlKit.translate(enFinal)
                                val viFinal = formatSubtitleCase(rawVi)
                                val transLatency = System.currentTimeMillis() - startTransTs

                                lastCommittedVi = viFinal
                                lastCommittedTimestamp = System.currentTimeMillis()
                                currentDisplayVi = viFinal

                                val subtitle = StreamingSubtitle(
                                    englishText = formatSubtitleCase(enFinal),
                                    vietnameseText = viFinal,
                                    isFinal = true,
                                    transLatencyMs = transLatency
                                )

                                _latestSubtitle.value = subtitle
                                _state.value = OnDeviceStreamingState.Streaming(subtitle)
                                _subtitleEvents.tryEmit(subtitle)

                                Log.d(TAG, "[FINAL BREAKPOINT] EN: \"$enFinal\" | VI (${transLatency}ms): \"$viFinal\"")
                            }
                        }

                        is VoskSpeechEvent.Partial -> {
                            // Lời nói đang tiếp diễn (Streaming word-by-word)
                            val enPartial = event.text.trim()
                            if (enPartial.isNotBlank()) {
                                val now = System.currentTimeMillis()
                                val words = enPartial.split("\\s+".toRegex()).filter { it.isNotBlank() }
                                val isLingerActive = (now - lastCommittedTimestamp < MIN_READING_LINGER_MS)

                                // Nếu câu trước vừa chốt chưa đủ thời gian để đọc, GIỮ NGUYÊN câu tiếng Việt đó
                                val viToShow = if (isLingerActive && lastCommittedVi.isNotBlank()) {
                                    lastCommittedVi
                                } else {
                                    currentDisplayVi.ifBlank { lastCommittedVi }
                                }

                                // Dòng tiếng Anh cập nhật liên tục tức thì (Zero latency feedback)
                                val streamSubtitle = StreamingSubtitle(
                                    englishText = formatSubtitleCase(enPartial),
                                    vietnameseText = viToShow,
                                    isFinal = false,
                                    transLatencyMs = 0
                                )
                                _latestSubtitle.value = streamSubtitle
                                _state.value = OnDeviceStreamingState.Streaming(streamSubtitle)
                                _subtitleEvents.tryEmit(streamSubtitle)

                                // Sau khi hết thời gian giữ đọc và đã có ít nhất 4 từ:
                                // Lập lịch dịch vế câu sau 400ms không có từ mới (Clause Breakpoint)
                                if (!isLingerActive && words.size >= 4) {
                                    partialDebounceJob?.cancel()
                                    partialDebounceJob = scope.launch {
                                        kotlinx.coroutines.delay(PARTIAL_DEBOUNCE_MS)
                                        if (isRunning) {
                                            val startTs = System.currentTimeMillis()
                                            val rawVi = mlKit.translate(enPartial)
                                            val viPartial = formatSubtitleCase(rawVi)
                                            val transLatency = System.currentTimeMillis() - startTs
                                            currentDisplayVi = viPartial

                                            val debouncedSub = StreamingSubtitle(
                                                englishText = formatSubtitleCase(enPartial),
                                                vietnameseText = viPartial,
                                                isFinal = false,
                                                transLatencyMs = transLatency
                                            )
                                            _latestSubtitle.value = debouncedSub
                                            _state.value = OnDeviceStreamingState.Streaming(debouncedSub)
                                            _subtitleEvents.tryEmit(debouncedSub)

                                            Log.d(TAG, "[CLAUSE BREAKPOINT] EN: \"$enPartial\" | VI (${transLatency}ms): \"$viPartial\"")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            isRunning = true
            _state.value = OnDeviceStreamingState.Listening
            CaptureMonitor.log(TAG, "On-Device Streaming Engine đã sẵn sàng! (Vosk + ML Kit)")
            true
        } catch (e: Exception) {
            val err = "Ngoại lệ khi khởi động: ${e.message}"
            _state.value = OnDeviceStreamingState.Error(err)
            CaptureMonitor.log(TAG, "LỖI: $err")
            stop()
            false
        }
    }

    /**
     * Nhận PCM ShortArray trực tiếp từ AudioRecord
     */
    fun feedAudio(buffer: ShortArray, samplesRead: Int) {
        if (!isRunning || samplesRead <= 0) return
        val channel = audioChannel ?: return

        val bytes = ByteArray(samplesRead * 2)
        for (i in 0 until samplesRead) {
            val sample = buffer[i].toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        channel.trySend(bytes)
    }

    /**
     * Dừng engine và giải phóng tài nguyên
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false

        audioWorkerJob?.cancel()
        audioWorkerJob = null

        speechCollectorJob?.cancel()
        speechCollectorJob = null

        partialDebounceJob?.cancel()
        partialDebounceJob = null

        lastCommittedVi = ""
        lastCommittedTimestamp = 0L
        currentDisplayVi = ""

        audioChannel?.close()
        audioChannel = null

        voskEngine?.close()
        voskEngine = null

        mlKitTranslator?.close()
        mlKitTranslator = null

        _latestSubtitle.value = null
        _state.value = OnDeviceStreamingState.Idle
        CaptureMonitor.log(TAG, "On-Device Streaming Engine đã dừng.")
    }

    /**
     * Chuẩn hoá chữ hoa / chữ thường:
     * Viết hoa chữ cái đầu tiên của câu, toàn bộ phần còn lại viết thường.
     * Triệt tiêu hiện tượng ký tự nhảy in hoa lộn xộn giữa chừng khi streaming.
     */
    private fun formatSubtitleCase(text: String): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        val lower = clean.lowercase(java.util.Locale.getDefault())
        return lower.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
        }
    }
}
