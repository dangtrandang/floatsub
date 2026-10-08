package com.floatsub.probe

data class CaptureTelemetry(
    val isCapturing: Boolean = false,
    val hasSignal: Boolean = false,
    val rmsDb: Float = -100f,
    val peakDb: Float = -100f,
    val totalFrames: Long = 0L,
    val totalBytes: Long = 0L,
    val durationSeconds: Double = 0.0,
    val sampleRate: Int = 16000,
    val channelConfig: String = "Mono (PCM 16-bit)"
)

data class WhisperBenchmarkResult(
    val modelName: String = "",
    val isLoaded: Boolean = false,
    val transcriptText: String = "",
    val inferenceTimeMs: Long = 0L,
    val chunkDurationSec: Double = 0.0,
    val rtf: Double = 0.0,
    val memoryMb: Long = 0L,
    val totalChunksProcessed: Int = 0,
    val encodeMs: Float = 0f,
    val decodeMs: Float = 0f,
    val sampleMs: Float = 0f,
    val sysInfo: String = ""
)
