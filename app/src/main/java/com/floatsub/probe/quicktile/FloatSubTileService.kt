package com.floatsub.probe.quicktile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.floatsub.probe.AudioCaptureService
import com.floatsub.probe.CaptureMonitor

class FloatSubTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isCapturing = CaptureMonitor.telemetry.value.isCapturing
        if (isCapturing) {
            // Khi đang chạy: Bấm vào là TẮT PHỤ ĐỀ NGAY
            CaptureMonitor.log("QuickTile", "Chạm Quick Tile khi đang chạy -> TẮT phụ đề ngay")
            val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP
            }
            startService(stopIntent)
            try {
                stopService(Intent(this, AudioCaptureService::class.java))
            } catch (_: Exception) {}
            updateTileState()
        } else {
            // Khi đang tắt: Bấm vào là BẮT ĐẦU PHỤ ĐỀ NGAY (qua QuickToggleActivity)
            CaptureMonitor.log("QuickTile", "Chạm Quick Tile khi đang tắt -> BẮT ĐẦU phụ đề ngay")
            val intent = Intent(this, QuickToggleActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this,
                    1004,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isCapturing = CaptureMonitor.telemetry.value.isCapturing
        tile.state = if (isCapturing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "FloatSub"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isCapturing) "Đang bật (Chạm để tắt)" else "Đã tắt (Chạm để bật)"
        }
        tile.contentDescription = if (isCapturing) "Chạm để tắt phụ đề FloatSub" else "Chạm để bật phụ đề FloatSub"
        tile.updateTile()
    }
}
