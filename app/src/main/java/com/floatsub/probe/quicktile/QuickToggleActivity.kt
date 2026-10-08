package com.floatsub.probe.quicktile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.floatsub.probe.AudioCaptureService
import com.floatsub.probe.CaptureMonitor

/**
 * Activity trong suốt chuyên phục vụ bật / tắt phụ đề nhanh 1 chạm
 * từ Quick Settings Tile và Thanh thông báo (Không làm gián đoạn màn hình người dùng).
 */
class QuickToggleActivity : AppCompatActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureMonitor.log("QuickToggle", "MediaProjection consent OK -> Bắt đầu phụ đề trực tiếp")

            val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(AudioCaptureService.EXTRA_CHUNK_DURATION_SEC, 1.5)
                putExtra(AudioCaptureService.EXTRA_ENGINE_MODE, AudioCaptureService.MODE_ON_DEVICE_STREAMING)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "Đã bật phụ đề trực tiếp FloatSub!", Toast.LENGTH_SHORT).show()
        } else {
            CaptureMonitor.log("QuickToggle", "Người dùng huỷ cấp quyền MediaProjection")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Nếu đang chạy phụ đề -> Tắt ngay lập tức
        if (CaptureMonitor.telemetry.value.isCapturing) {
            CaptureMonitor.log("QuickToggle", "Nhận lệnh toggle khi đang chạy -> Dừng phụ đề ngay")
            val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP
            }
            startService(stopIntent)
            try {
                stopService(Intent(this, AudioCaptureService::class.java))
            } catch (_: Exception) {}
            Toast.makeText(this, "Đã tắt phụ đề FloatSub", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 2. Nếu đang tắt -> Khởi chạy hộp thoại cấp quyền MediaProjection để bắt âm thanh
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Vui lòng cấp quyền Overlay để hiện phụ đề nổi!", Toast.LENGTH_LONG).show()
            val intent = Intent(this, com.floatsub.probe.MainActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val consentIntent = mpManager.createScreenCaptureIntent()
            projectionLauncher.launch(consentIntent)
        } catch (e: Exception) {
            CaptureMonitor.log("QuickToggle", "Lỗi khởi chạy MediaProjection: ${e.message}")
            finish()
        }
    }
}
