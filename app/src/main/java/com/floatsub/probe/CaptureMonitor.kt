package com.floatsub.probe

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CaptureMonitor {
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _telemetry = MutableStateFlow(CaptureTelemetry())
    val telemetry = _telemetry.asStateFlow()

    private val _whisperBenchmark = MutableStateFlow(WhisperBenchmarkResult())
    val whisperBenchmark = _whisperBenchmark.asStateFlow()

    private val _logEvents = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val logEvents = _logEvents.asSharedFlow()

    fun updateTelemetry(telemetry: CaptureTelemetry) {
        _telemetry.value = telemetry
    }

    fun updateWhisperBenchmark(result: WhisperBenchmarkResult) {
        _whisperBenchmark.value = result
    }

    fun log(tag: String, message: String) {
        val time = timeFormat.format(Date())
        val formatted = "[$time] [$tag] $message"
        _logEvents.tryEmit(formatted)
        android.util.Log.i("FloatSubProbe", formatted)
    }

    fun reset() {
        _telemetry.value = CaptureTelemetry()
    }
}
