package com.floatsub.probe.cloud

import android.content.Context
import com.floatsub.probe.CaptureMonitor
import java.io.File

object FixtureManager {

    private const val LIVE_FIXTURE_FILE = "fixture_live_2s.pcm"
    private const val ASSET_FIXTURE_FILE = "fixture_2s.pcm"

    fun saveLivePcm(context: Context, pcmBytes: ByteArray) {
        try {
            val file = File(context.filesDir, LIVE_FIXTURE_FILE)
            file.writeBytes(pcmBytes)
            CaptureMonitor.log(
                "Fixture",
                "Đã lưu mẫu live playback vào ${file.name} (${pcmBytes.size} bytes = 2.0s PCM)"
            )
        } catch (e: Exception) {
            CaptureMonitor.log("Fixture", "Lỗi lưu live fixture: ${e.message}")
        }
    }

    fun hasLiveFixture(context: Context): Boolean {
        val file = File(context.filesDir, LIVE_FIXTURE_FILE)
        return file.exists() && file.length() >= 64000
    }

    fun loadFixturePcm(context: Context): Pair<ByteArray, String> {
        val liveFile = File(context.filesDir, LIVE_FIXTURE_FILE)
        if (liveFile.exists() && liveFile.length() > 0) {
            val bytes = liveFile.readBytes()
            val desc = "Live Capture PCM (${String.format(java.util.Locale.US, "%.2f", bytes.size / 32000.0)}s, ${bytes.size} B)"
            return Pair(bytes, desc)
        }

        // Fallback to bundled asset
        return try {
            val bytes = context.assets.open(ASSET_FIXTURE_FILE).use { it.readBytes() }
            val desc = "Bundled Test PCM (${String.format(java.util.Locale.US, "%.2f", bytes.size / 32000.0)}s, ${bytes.size} B)"
            Pair(bytes, desc)
        } catch (e: Exception) {
            CaptureMonitor.log("Fixture", "Không tìm thấy asset $ASSET_FIXTURE_FILE: ${e.message}")
            // Fallback empty or silence
            Pair(ByteArray(64000), "Silent PCM (2.00s, 64000 B)")
        }
    }
}
