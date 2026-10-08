package com.floatsub.probe.cloud

import android.content.Context
import com.floatsub.probe.CaptureMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class BenchmarkState {
    object Idle : BenchmarkState()
    data class Running(val currentRun: Int, val totalRuns: Int, val lastResult: AsrRunResult?) : BenchmarkState()
    data class CompletedSingle(val result: AsrRunResult) : BenchmarkState()
    data class CompletedMulti(val stats: BenchmarkStats) : BenchmarkState()
    data class Error(val message: String) : BenchmarkState()
}

class CloudBenchmarkRunner(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val groqProvider = GroqProvider(httpClient)
    private val openAiProvider = OpenAiProvider(httpClient)
    private val geminiProvider = GeminiProvider(httpClient)

    private val _state = MutableStateFlow<BenchmarkState>(BenchmarkState.Idle)
    val state = _state.asStateFlow()

    fun getProvider(name: String): CloudAsrProvider {
        return when (name.lowercase()) {
            "groq" -> groqProvider
            "openai" -> openAiProvider
            "gemini" -> geminiProvider
            else -> groqProvider
        }
    }

    suspend fun runSingleTest(
        providerName: String,
        apiKey: String,
        model: String
    ): AsrRunResult = withContext(Dispatchers.IO) {
        val provider = getProvider(providerName)
        if (apiKey.isBlank()) {
            val errResult = AsrRunResult(
                providerName = provider.providerName,
                modelName = model,
                audioDurationSec = 2.0,
                prepTimeMs = 0,
                networkLatencyMs = 0,
                totalLatencyMs = 0,
                rtf = 0.0,
                httpStatus = 0,
                isSuccess = false,
                errorMessage = "Vui lòng nhập API Key cho ${provider.providerName}!"
            )
            _state.value = BenchmarkState.Error(errResult.errorMessage ?: "")
            return@withContext errResult
        }

        _state.value = BenchmarkState.Running(1, 1, null)

        val (pcmBytes, fixtureDesc) = FixtureManager.loadFixturePcm(context)
        val audioDurationSec = pcmBytes.size / 32000.0 // 16kHz mono 16-bit = 32000 B/s

        CaptureMonitor.log("SpikeC", "--- BẮT ĐẦU TEST CONNECTION (1 RUN) ---")
        CaptureMonitor.log("SpikeC", "Provider: ${provider.providerName} | Model: $model | Audio: $fixtureDesc")

        // Step 1: PCM -> WAV conversion
        val conversion = AudioWavConverter.pcmToWav(pcmBytes, 16000)
        CaptureMonitor.log(
            "SpikeC",
            "Chuyển đổi PCM -> WAV: ${conversion.prepTimeMs} ms (${conversion.wavBytes.size} bytes RIFF header 44B)"
        )

        // Step 2: Cloud ASR Transcribe
        val result = provider.transcribe(
            wavBytes = conversion.wavBytes,
            audioDurationSec = audioDurationSec,
            apiKey = apiKey,
            model = model,
            prepTimeMs = conversion.prepTimeMs
        )

        _state.value = BenchmarkState.CompletedSingle(result)
        return@withContext result
    }

    suspend fun runBatchBenchmark(
        providerName: String,
        apiKey: String,
        model: String,
        runs: Int = 10
    ): BenchmarkStats? = withContext(Dispatchers.IO) {
        val provider = getProvider(providerName)
        if (apiKey.isBlank()) {
            _state.value = BenchmarkState.Error("Vui lòng nhập API Key cho ${provider.providerName}!")
            return@withContext null
        }

        val (pcmBytes, fixtureDesc) = FixtureManager.loadFixturePcm(context)
        val audioDurationSec = pcmBytes.size / 32000.0

        CaptureMonitor.log("SpikeC", "==================================================")
        CaptureMonitor.log("SpikeC", "--- BẮT ĐẦU SPIKE C BENCHMARK BATCH ($runs RUNS) ---")
        CaptureMonitor.log("SpikeC", "Provider: ${provider.providerName} | Model: $model | Audio: $fixtureDesc")
        CaptureMonitor.log("SpikeC", "==================================================")

        val results = mutableListOf<AsrRunResult>()

        for (i in 1..runs) {
            _state.value = BenchmarkState.Running(i, runs, results.lastOrNull())

            // Convert PCM -> WAV each time to include prep overhead if any
            val conversion = AudioWavConverter.pcmToWav(pcmBytes, 16000)

            val res = provider.transcribe(
                wavBytes = conversion.wavBytes,
                audioDurationSec = audioDurationSec,
                apiKey = apiKey,
                model = model,
                prepTimeMs = conversion.prepTimeMs
            )
            results.add(res)

            // Short pacing pause between runs to avoid strict instant rate limits
            if (i < runs) {
                delay(150)
            }
        }

        val successful = results.filter { it.isSuccess }
        val successCount = successful.size
        val failureCount = results.size - successCount

        val latencies = successful.map { it.totalLatencyMs }.sorted()
        val minLat = latencies.firstOrNull() ?: 0L
        val maxLat = latencies.lastOrNull() ?: 0L
        val avgLat = if (latencies.isNotEmpty()) latencies.average() else 0.0
        val p50Lat = if (latencies.isNotEmpty()) latencies[latencies.size / 2] else 0L
        val p95Index = if (latencies.isNotEmpty()) ((latencies.size * 0.95).toInt()).coerceAtMost(latencies.size - 1) else 0
        val p95Lat = if (latencies.isNotEmpty()) latencies[p95Index] else 0L

        val avgRtf = if (audioDurationSec > 0 && avgLat > 0) (avgLat / 1000.0) / audioDurationSec else 0.0
        val sampleTranscript = successful.lastOrNull()?.transcript ?: "(Không có transcript thành công)"

        val stats = BenchmarkStats(
            providerName = provider.providerName,
            modelName = model,
            audioDurationSec = audioDurationSec,
            totalRuns = runs,
            successRuns = successCount,
            failureRuns = failureCount,
            minLatencyMs = minLat,
            p50LatencyMs = p50Lat,
            avgLatencyMs = avgLat,
            p95LatencyMs = p95Lat,
            maxLatencyMs = maxLat,
            avgRtf = avgRtf,
            sampleTranscript = sampleTranscript,
            allResults = results
        )

        CaptureMonitor.log("SpikeC", "==================================================")
        CaptureMonitor.log("SpikeC", "SPIKE C SUMMARY: ${provider.providerName} ($model)")
        CaptureMonitor.log("SpikeC", "Runs: $runs | Thành công: $successCount/$runs | Thất bại: $failureCount")
        CaptureMonitor.log("SpikeC", "Min: ${minLat}ms | P50: ${p50Lat}ms | Avg: ${String.format(Locale.US, "%.1f", avgLat)}ms | P95: ${p95Lat}ms | Max: ${maxLat}ms")
        CaptureMonitor.log("SpikeC", "Avg RTF: ${String.format(Locale.US, "%.4f", avgRtf)}")
        CaptureMonitor.log("SpikeC", "Sample Transcript: \"$sampleTranscript\"")
        CaptureMonitor.log("SpikeC", "==================================================")

        _state.value = BenchmarkState.CompletedMulti(stats)
        return@withContext stats
    }
}
