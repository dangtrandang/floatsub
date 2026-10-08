package com.floatsub.probe

import android.content.Context
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object WhisperBenchmarkEngine {
    private var whisperContext: WhisperContext? = null
    private var currentModelName: String = ""
    private var systemInfo: String = ""
    private var totalProcessed: Int = 0
    var configuredThreadCount: Int = 4

    suspend fun loadModel(context: Context, modelFileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            whisperContext?.release()
            whisperContext = null

            CaptureMonitor.log("WhisperEngine", "Đang tìm kiếm model: $modelFileName")

            val candidateDirs = listOf(
                context.getExternalFilesDir("models"),
                File(context.filesDir, "models"),
                File("/sdcard/Download/floatsub_models"),
                File("/sdcard/Android/data/${context.packageName}/files/models")
            )

            var targetFile: File? = null
            for (dir in candidateDirs) {
                if (dir != null && dir.exists()) {
                    val file = File(dir, modelFileName)
                    if (file.exists() && file.length() > 0) {
                        targetFile = file
                        break
                    }
                }
            }

            if (targetFile == null) {
                CaptureMonitor.log("WhisperEngine", "LỖI: Không tìm thấy file model $modelFileName")
                CaptureMonitor.updateWhisperBenchmark(
                    WhisperBenchmarkResult(
                        modelName = modelFileName,
                        isLoaded = false,
                        transcriptText = "LỖI: Không tìm thấy file model trong bộ nhớ!"
                    )
                )
                return@withContext false
            }

            CaptureMonitor.log("WhisperEngine", "Tìm thấy model tại: ${targetFile.absolutePath} (${targetFile.length() / (1024 * 1024)} MB)")
            val loadStart = System.currentTimeMillis()

            val wContext = WhisperContext.createContextFromFile(targetFile.absolutePath)
            whisperContext = wContext
            currentModelName = modelFileName
            systemInfo = WhisperContext.getSystemInfo()
            totalProcessed = 0

            val loadTime = System.currentTimeMillis() - loadStart
            CaptureMonitor.log("WhisperEngine", "Nạp model thành công trong $loadTime ms!")
            CaptureMonitor.log("SysInfo", systemInfo)

            CaptureMonitor.updateWhisperBenchmark(
                WhisperBenchmarkResult(
                    modelName = modelFileName,
                    isLoaded = true,
                    transcriptText = "Model sẵn sàng ($loadTime ms). Đang chờ chunk âm thanh...",
                    sysInfo = systemInfo
                )
            )
            return@withContext true

        } catch (e: Exception) {
            CaptureMonitor.log("WhisperEngine", "LỖI khi nạp model: ${e.message}")
            CaptureMonitor.updateWhisperBenchmark(
                WhisperBenchmarkResult(
                    modelName = modelFileName,
                    isLoaded = false,
                    transcriptText = "Lỗi nạp model: ${e.message}"
                )
            )
            return@withContext false
        }
    }

    suspend fun transcribeChunk(floatSamples: FloatArray): String = withContext(Dispatchers.Default) {
        val context = whisperContext ?: return@withContext ""
        if (floatSamples.isEmpty()) return@withContext ""

        try {
            val audioDurationSec = floatSamples.size / 16000.0
            val startTime = System.currentTimeMillis()

            val rawText = context.transcribeData(
                data = floatSamples,
                printTimestamp = false,
                numThreads = configuredThreadCount
            )
            val inferenceTimeMs = System.currentTimeMillis() - startTime

            val rtf = (inferenceTimeMs / 1000.0) / audioDurationSec
            totalProcessed++

            val timings = context.getTimings()
            val sampleMs = timings.getOrElse(0) { 0f }
            val encodeMs = timings.getOrElse(1) { 0f }
            val decodeMs = timings.getOrElse(2) { 0f }

            val runtime = Runtime.getRuntime()
            val usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

            val cleanText = rawText.trim().replace("\n", " ")

            CaptureMonitor.updateWhisperBenchmark(
                WhisperBenchmarkResult(
                    modelName = currentModelName,
                    isLoaded = true,
                    transcriptText = if (cleanText.isEmpty()) "(Không nhận diện được giọng nói / Im lặng)" else cleanText,
                    inferenceTimeMs = inferenceTimeMs,
                    chunkDurationSec = audioDurationSec,
                    rtf = rtf,
                    memoryMb = usedMemoryMb,
                    totalChunksProcessed = totalProcessed,
                    encodeMs = encodeMs,
                    decodeMs = decodeMs,
                    sampleMs = sampleMs,
                    sysInfo = systemInfo
                )
            )

            // Log phân rã chi tiết Encoder / Decoder / Mel / Total
            CaptureMonitor.log(
                "Profile",
                "[#$totalProcessed] Model: $currentModelName | Th: $configuredThreadCount | Encode: ${encodeMs.toInt()}ms | Decode: ${decodeMs.toInt()}ms | Mel: ${sampleMs.toInt()}ms | Total: ${inferenceTimeMs}ms | RTF: ${String.format(Locale.US, "%.2f", rtf)} | Text: \"$cleanText\""
            )

            return@withContext cleanText
        } catch (e: Exception) {
            CaptureMonitor.log("Whisper", "Lỗi transcribe: ${e.message}")
            return@withContext ""
        }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        whisperContext?.release()
        whisperContext = null
        currentModelName = ""
        CaptureMonitor.log("WhisperEngine", "Đã giải phóng WhisperContext.")
    }
}
