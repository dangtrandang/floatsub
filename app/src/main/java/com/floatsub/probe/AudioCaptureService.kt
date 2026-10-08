package com.floatsub.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import android.provider.Settings
import com.floatsub.probe.cloud.LiveSubtitleEngine
import com.floatsub.probe.cloud.LiveSubtitleState
import com.floatsub.probe.overlay.FloatingOverlayManager
import kotlinx.coroutines.flow.collectLatest

class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.floatsub.probe.ACTION_START"
        const val ACTION_STOP = "com.floatsub.probe.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "channel_audio_capture"
        private const val NOTIFICATION_ID = 1001

        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        const val EXTRA_CHUNK_DURATION_SEC = "extra_chunk_duration_sec"
        var configuredChunkDurationSec: Double = 1.5

        const val EXTRA_ENGINE_MODE = "extra_engine_mode"
        const val MODE_ON_DEVICE_STREAMING = "on_device_streaming"
        const val MODE_CLOUD_BATCH = "cloud_batch"
        var currentEngineMode: String = MODE_ON_DEVICE_STREAMING
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var overlayJob: Job? = null
    private var overlayManager: FloatingOverlayManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CaptureMonitor.log("Service", "Nhận lệnh ACTION_STOP")
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val chunkDuration = intent.getDoubleExtra(EXTRA_CHUNK_DURATION_SEC, 0.0)
                if (chunkDuration > 0.0) {
                    configuredChunkDurationSec = chunkDuration
                }
                currentEngineMode = intent.getStringExtra(EXTRA_ENGINE_MODE) ?: MODE_ON_DEVICE_STREAMING
                CaptureMonitor.log("Service", "Khởi động với Engine Mode: $currentEngineMode")
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != 0 && resultData != null) {
                    startForegroundServiceWithNotification()

                    if (overlayManager == null && Settings.canDrawOverlays(this)) {
                        overlayManager = FloatingOverlayManager(this) {
                            CaptureMonitor.log("Service", "Nhận tín hiệu dừng từ Floating Overlay")
                            stopCapture()
                            stopSelf()
                        }
                        overlayManager?.show()
                    }

                    startCapture(resultCode, resultData)
                } else {
                    CaptureMonitor.log("Service", "Lỗi: Không nhận được resultCode/resultData hợp lệ")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        // Tắt thông báo tĩnh để không bị trùng lặp 2 thông báo khi đang chạy
        com.floatsub.probe.quicktile.QuickNotificationManager.hideQuickNotification(this)

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1001, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            this, 1002, mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val badgeBitmap = com.floatsub.probe.quicktile.QuickNotificationManager.createColoredBadgeBitmap(this)

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("FloatSub: Đang chạy phụ đề")
            .setContentText("⏹ Chạm vào đây để TẮT phụ đề ngay")
            .setSmallIcon(R.drawable.ic_quick_subtitles)
            .setLargeIcon(badgeBitmap)
            .setContentIntent(stopPendingIntent) // Chạm thẳng vào notification là TẮT NGAY
            .addAction(android.R.drawable.ic_media_pause, "TẮT PHỤ ĐỀ", stopPendingIntent)
            .addAction(android.R.drawable.ic_menu_preferences, "Mở app", appPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Cập nhật trạng thái Quick Settings Tile thành Active
        try {
            android.service.quicksettings.TileService.requestListeningState(
                this,
                android.content.ComponentName(this, com.floatsub.probe.quicktile.FloatSubTileService::class.java)
            )
        } catch (_: Exception) {}
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (audioRecord != null) {
            CaptureMonitor.log("Capture", "AudioRecord đã chạy từ trước, bỏ qua")
            return
        }

        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            CaptureMonitor.log("MediaProjection", "Yêu cầu cấp token từ MediaProjectionManager...")

            val projection = mpManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                CaptureMonitor.log("MediaProjection", "LỖI: getMediaProjection trả về null!")
                stopSelf()
                return
            }
            mediaProjection = projection

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    CaptureMonitor.log("MediaProjection", "Callback onStop: Phiên capture đã bị đóng bởi hệ thống/user")
                    stopCapture()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            CaptureMonitor.log("MediaProjection", "MediaProjection đã sẵn sàng.")

            // Cấu hình AudioPlaybackCaptureConfiguration
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSizeInBytes = max(minBufferSize * 2, 4096)

            CaptureMonitor.log(
                "AudioRecord",
                "Khởi tạo AudioRecord: minBufferSize=$minBufferSize, allocated=$bufferSizeInBytes bytes"
            )

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                CaptureMonitor.log("AudioRecord", "LỖI: AudioRecord không khởi tạo được! (STATE != INITIALIZED)")
                record.release()
                stopSelf()
                return
            }

            audioRecord = record
            record.startRecording()
            CaptureMonitor.log("AudioRecord", "Bắt đầu ghi âm (startRecording thành công)")

            // Chạy luồng đọc PCM
            startReadingPcm(record)

            // Khởi chạy Floating Overlay nếu có quyền
            if (overlayManager == null && Settings.canDrawOverlays(this)) {
                overlayManager = FloatingOverlayManager(this) {
                    CaptureMonitor.log("Service", "Nhận tín hiệu dừng từ Floating Overlay")
                    com.floatsub.probe.local.OnDeviceStreamingEngine.stop()
                    LiveSubtitleEngine.stopLive()
                    stopCapture()
                    stopSelf()
                }
                overlayManager?.show()
            }

            // Lắng nghe kết quả để cập nhật phụ đề nổi
            overlayJob?.cancel()
            overlayJob = serviceScope.launch {
                if (currentEngineMode == MODE_ON_DEVICE_STREAMING) {
                    overlayManager?.updateSubtitle(
                        sourceText = "(Khởi động Vosk & ML Kit...)",
                        translatedText = "(Vui lòng chờ giây lát...)",
                        sourceLang = "EN",
                        targetLang = "VI"
                    )
                    val started = com.floatsub.probe.local.OnDeviceStreamingEngine.start(this@AudioCaptureService)
                    if (!started) {
                        overlayManager?.updateSubtitle(
                            sourceText = "Lỗi On-Device ASR",
                            translatedText = "Không nạp được model Vosk hoặc ML Kit!"
                        )
                        return@launch
                    }
                    overlayManager?.updateSubtitle(
                        sourceText = "(Live Caption On-Device)",
                        translatedText = "(Đang lắng nghe âm thanh...)",
                        sourceLang = "EN",
                        targetLang = "VI"
                    )
                    com.floatsub.probe.local.OnDeviceStreamingEngine.subtitleEvents.collectLatest { sub ->
                        overlayManager?.updateSubtitle(
                            sourceText = sub.englishText,
                            translatedText = sub.vietnameseText,
                            sourceLang = "EN",
                            targetLang = "VI"
                        )
                    }
                } else {
                    LiveSubtitleEngine.state.collectLatest { state ->
                        when (state) {
                            is LiveSubtitleState.NewSubtitle -> {
                                overlayManager?.updateSubtitle(
                                    sourceText = state.result.transcript,
                                    translatedText = state.result.translatedText,
                                    sourceLang = state.result.detectedLanguage,
                                    targetLang = null
                                )
                            }
                            is LiveSubtitleState.Listening -> {
                                overlayManager?.updateSubtitle(
                                    sourceText = "(Đang lắng nghe playback...)",
                                    translatedText = "(Mở video/YouTube để xem phụ đề)"
                                )
                            }
                            is LiveSubtitleState.Error -> {
                                overlayManager?.updateSubtitle(
                                    sourceText = "Lỗi:",
                                    translatedText = state.message
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }

        } catch (e: SecurityException) {
            CaptureMonitor.log("Lỗi Bảo Mật", "SecurityException: ${e.message}")
            stopSelf()
        } catch (e: Exception) {
            CaptureMonitor.log("Lỗi Khởi Tạo", "Ngoại lệ khi tạo AudioRecord: ${e.message}")
            stopSelf()
        }
    }

    private fun startReadingPcm(record: AudioRecord) {
        captureJob = serviceScope.launch {
            val chunkSamples = 1024 // ~64ms ở 16kHz
            val buffer = ShortArray(chunkSamples)

            val durationSec = configuredChunkDurationSec.coerceIn(0.5, 3.0)
            val targetChunkSize = (SAMPLE_RATE * durationSec).toInt()
            val speechAccumulator = ArrayList<Short>(targetChunkSize)

            var totalFrames: Long = 0
            var totalBytes: Long = 0
            val startTimeMs = System.currentTimeMillis()
            var lastUiUpdateMs = 0L
            var firstFrameReceived = false
            var isTranscribing = false
            var chunkSequence: Long = 0
            var currentChunkStartTimeMs = System.currentTimeMillis()

            CaptureMonitor.log("AudioRecord", "Cấu hình Audio Chunk Window: ${durationSec}s ($targetChunkSize samples)")

            while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val samplesRead = record.read(buffer, 0, buffer.size)

                if (samplesRead > 0) {
                    if (!firstFrameReceived) {
                        firstFrameReceived = true
                        currentChunkStartTimeMs = System.currentTimeMillis()
                        CaptureMonitor.log("PCM Stream", "Đã nhận gói PCM đầu tiên ($samplesRead samples)")
                    }

                    totalFrames += samplesRead
                    totalBytes += (samplesRead * 2L) // 16-bit = 2 bytes/sample

                    // Ghi vào Ring Buffer 3.0s của Quick Translate (chỉ tốn RAM nội bộ, 0 network API khi idle)
                    com.floatsub.probe.quicktranslate.QuickTranslateEngine.onPcmData(buffer, samplesRead)

                    // Đẩy audio trực tiếp vào On-Device Streaming Engine (~64ms/chunk)
                    if (currentEngineMode == MODE_ON_DEVICE_STREAMING && com.floatsub.probe.local.OnDeviceStreamingEngine.isRunning) {
                        com.floatsub.probe.local.OnDeviceStreamingEngine.feedAudio(buffer, samplesRead)
                    }

                    // Tính toán RMS và Peak
                    var sumSquares = 0.0
                    var maxAbs = 0

                    for (i in 0 until samplesRead) {
                        val sample = buffer[i].toInt()
                        sumSquares += (sample * sample)
                        val absVal = if (sample < 0) -sample else sample
                        if (absVal > maxAbs) {
                            maxAbs = absVal
                        }
                        speechAccumulator.add(buffer[i])
                    }

                    val meanSquare = sumSquares / samplesRead
                    val rms = sqrt(meanSquare)

                    // Quy đổi ra dBFS (Full Scale 32768 cho signed 16-bit PCM)
                    val rmsDb = if (rms > 0.0) {
                        (20.0 * log10(rms / 32768.0)).toFloat().coerceIn(-100f, 0f)
                    } else {
                        -100f
                    }

                    val peakDb = if (maxAbs > 0) {
                        (20.0 * log10(maxAbs.toDouble() / 32768.0)).toFloat().coerceIn(-100f, 0f)
                    } else {
                        -100f
                    }

                    val hasSignal = (rmsDb > -65f) && (maxAbs > 20)

                    // Khi đã đủ audio chunk
                    if (speechAccumulator.size >= targetChunkSize) {
                        val currentChunk = speechAccumulator.toShortArray()
                        speechAccumulator.clear()
                        val chunkEndTimeMs = System.currentTimeMillis()
                        chunkSequence++

                        // Nếu có tín hiệu, lưu lại mẫu live cho Fixture và đẩy vào Live Subtitle Engine
                        if (hasSignal) {
                            val pcmBytes = ByteArray(currentChunk.size * 2)
                            val bb = java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (s in currentChunk) {
                                bb.putShort(s)
                            }
                            com.floatsub.probe.cloud.FixtureManager.saveLivePcm(applicationContext, pcmBytes)

                            // Đẩy vào Live Subtitle Pipeline (chạy nền trên channel không block capture)
                            if (com.floatsub.probe.cloud.LiveSubtitleEngine.isLiveActive) {
                                val capturedChunk = com.floatsub.probe.cloud.CapturedAudioChunk(
                                    sequenceNumber = chunkSequence,
                                    pcmBytes = pcmBytes,
                                    audioDurationSec = durationSec,
                                    audioStartTimestamp = currentChunkStartTimeMs,
                                    audioEndTimestamp = chunkEndTimeMs
                                )
                                com.floatsub.probe.cloud.LiveSubtitleEngine.onLiveChunkCaptured(capturedChunk)
                            } else if (com.floatsub.probe.cloud.SpikeDEngine.isLiveActive) {
                                com.floatsub.probe.cloud.SpikeDEngine.onLiveChunkCaptured(pcmBytes)
                            }
                        }

                        // Reset mốc thời gian bắt đầu chunk tiếp theo
                        currentChunkStartTimeMs = System.currentTimeMillis()

                        // Nếu có tín hiệu và không chạy live cloud subtitle
                        if (hasSignal && !isTranscribing && !com.floatsub.probe.cloud.LiveSubtitleEngine.isLiveActive && !com.floatsub.probe.cloud.SpikeDEngine.isLiveActive) {

                            val floatSamples = FloatArray(currentChunk.size) { i -> currentChunk[i] / 32768.0f }
                            isTranscribing = true
                            serviceScope.launch {
                                try {
                                    WhisperBenchmarkEngine.transcribeChunk(floatSamples)
                                } finally {
                                    isTranscribing = false
                                }
                            }
                        }
                    }


                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateMs >= 100) { // Cập nhật telemetry UI mỗi 100ms
                        lastUiUpdateMs = now
                        val elapsedSec = (now - startTimeMs) / 1000.0

                        CaptureMonitor.updateTelemetry(
                            CaptureTelemetry(
                                isCapturing = true,
                                hasSignal = hasSignal,
                                rmsDb = rmsDb,
                                peakDb = peakDb,
                                totalFrames = totalFrames,
                                totalBytes = totalBytes,
                                durationSeconds = elapsedSec,
                                sampleRate = SAMPLE_RATE,
                                channelConfig = "Mono (PCM 16-bit)"
                            )
                        )
                    }

                } else if (samplesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    CaptureMonitor.log("PCM Stream", "Cảnh báo: AudioRecord.ERROR_INVALID_OPERATION")
                    break
                } else if (samplesRead == AudioRecord.ERROR_BAD_VALUE) {
                    CaptureMonitor.log("PCM Stream", "Cảnh báo: AudioRecord.ERROR_BAD_VALUE")
                    break
                }
            }

            CaptureMonitor.log("PCM Stream", "Vòng lặp đọc PCM kết thúc. Tổng nhận: $totalFrames frames, $totalBytes bytes")
        }
    }

    private fun stopCapture() {
        overlayJob?.cancel()
        overlayJob = null

        overlayManager?.hide()
        overlayManager = null

        com.floatsub.probe.local.OnDeviceStreamingEngine.stop()
        LiveSubtitleEngine.stopLive()

        captureJob?.cancel()
        captureJob = null

        audioRecord?.let {
            try {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                }
                it.release()
                CaptureMonitor.log("AudioRecord", "AudioRecord đã dừng và giải phóng bộ nhớ sạch sẽ.")
            } catch (e: Exception) {
                CaptureMonitor.log("AudioRecord", "Lỗi khi release AudioRecord: ${e.message}")
            }
        }
        audioRecord = null

        mediaProjection?.let {
            try {
                it.stop()
                CaptureMonitor.log("MediaProjection", "MediaProjection đã stop.")
            } catch (e: Exception) {
                CaptureMonitor.log("MediaProjection", "Lỗi khi stop MediaProjection: ${e.message}")
            }
        }
        mediaProjection = null

        // Huỷ Foreground notification và gỡ bỏ service khỏi status bar
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            CaptureMonitor.log("Service", "Lỗi huỷ Foreground Notification: ${e.message}")
        }

        CaptureMonitor.updateTelemetry(CaptureTelemetry(isCapturing = false))
        CaptureMonitor.reset()
        CaptureMonitor.log("Service", "Toàn bộ tài nguyên capture đã được giải phóng.")

        // Bật lại thông báo mở nhanh thường trực (chế độ sẵn sàng BẬT)
        try {
            if (com.floatsub.probe.quicktile.QuickNotificationManager.isEnabled(this)) {
                com.floatsub.probe.quicktile.QuickNotificationManager.showQuickNotification(this)
            }
            android.service.quicksettings.TileService.requestListeningState(
                this,
                android.content.ComponentName(this, com.floatsub.probe.quicktile.FloatSubTileService::class.java)
            )
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "FloatSub Audio Probe",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Theo dõi trạng thái bắt âm thanh playback của hệ thống"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
