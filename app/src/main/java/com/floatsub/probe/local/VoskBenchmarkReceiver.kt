package com.floatsub.probe.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VoskBenchmarkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == "com.floatsub.probe.TEST_STREAMING_PIPELINE") {
            Log.i("VoskBench", "Bắt đầu kiểm thử OnDeviceStreamingEngine với fixture PCM...")
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val started = OnDeviceStreamingEngine.start(appContext)
                    Log.i("VoskBench", "OnDeviceStreamingEngine.start() result: $started")
                    if (!started) return@launch

                    val (pcm, _) = com.floatsub.probe.cloud.FixtureManager.loadFixturePcm(appContext)
                    // Chuyển byteArray thành shortArray
                    val shorts = ShortArray(pcm.size / 2)
                    java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

                    // Feed từng chunk 1024 mẫu (~64ms)
                    val chunkSize = 1024
                    var offset = 0
                    while (offset < shorts.size) {
                        val count = minOf(chunkSize, shorts.size - offset)
                        val chunk = ShortArray(count)
                        System.arraycopy(shorts, offset, chunk, 0, count)
                        OnDeviceStreamingEngine.feedAudio(chunk, count)
                        offset += count
                        kotlinx.coroutines.delay(64) // giả lập thời gian thực
                    }
                    Log.i("VoskBench", "Đã feed hết fixture PCM vào streaming engine.")
                } catch (e: Exception) {
                    Log.e("VoskBench", "Lỗi kiểm thử streaming pipeline: ${e.message}", e)
                }
            }
        } else if (action == "com.floatsub.probe.STOP_STREAMING_PIPELINE") {
            Log.i("VoskBench", "Dừng kiểm thử OnDeviceStreamingEngine & Service...")
            OnDeviceStreamingEngine.stop()
            val stopIntent = Intent(context, com.floatsub.probe.AudioCaptureService::class.java).apply {
                this.action = com.floatsub.probe.AudioCaptureService.ACTION_STOP
            }
            context.startService(stopIntent)
            try {
                context.stopService(Intent(context, com.floatsub.probe.AudioCaptureService::class.java))
            } catch (_: Exception) {}
        }
    }
}
