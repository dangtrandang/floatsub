package com.floatsub.probe.cloud

data class AsrRunResult(
    val providerName: String,
    val modelName: String,
    val audioDurationSec: Double,
    val prepTimeMs: Long,
    val networkLatencyMs: Long,
    val totalLatencyMs: Long,
    val rtf: Double,
    val httpStatus: Int,
    val isSuccess: Boolean,
    val errorMessage: String? = null,
    val transcript: String = "",
    val detectedLanguage: String? = null,
    val serverTimeInfo: String = "server_time = unavailable"
)

data class BenchmarkStats(
    val providerName: String,
    val modelName: String,
    val audioDurationSec: Double,
    val totalRuns: Int,
    val successRuns: Int,
    val failureRuns: Int,
    val minLatencyMs: Long,
    val p50LatencyMs: Long,
    val avgLatencyMs: Double,
    val p95LatencyMs: Long,
    val maxLatencyMs: Long,
    val avgRtf: Double,
    val sampleTranscript: String,
    val allResults: List<AsrRunResult>
)

interface CloudAsrProvider {
    val providerName: String
    val defaultModel: String
    val supportedModels: List<String>

    suspend fun transcribe(
        wavBytes: ByteArray,
        audioDurationSec: Double,
        apiKey: String,
        model: String,
        prepTimeMs: Long,
        languageCode: String? = null
    ): AsrRunResult
}
