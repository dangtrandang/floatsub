package com.floatsub.probe.quicktile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import com.floatsub.probe.CaptureMonitor
import com.floatsub.probe.MainActivity
import com.floatsub.probe.R

object QuickNotificationManager {

    private const val CHANNEL_ID = "channel_quick_access"
    private const val NOTIFICATION_ID = 2001
    private const val PREFS_NAME = "floatsub_quick_prefs"
    private const val KEY_ENABLED = "quick_notification_enabled"

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            showQuickNotification(context)
        } else {
            hideQuickNotification(context)
        }
    }

    fun init(context: Context) {
        createNotificationChannel(context)
        if (isEnabled(context) && !CaptureMonitor.telemetry.value.isCapturing) {
            showQuickNotification(context)
        }
    }

    fun showQuickNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(context)

        // 1. Chạm vào notification -> BẬT PHỤ ĐỀ NGAY LẬP TỨC qua QuickToggleActivity
        val toggleIntent = Intent(context, QuickToggleActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val togglePendingIntent = PendingIntent.getActivity(
            context,
            2001,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Action phụ: Mở app cài đặt
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            context,
            2002,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val badgeBitmap = createColoredBadgeBitmap(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("FloatSub: Phụ Đề Trực Tiếp")
            .setContentText("▶ Chạm vào đây để BẬT phụ đề ngay")
            .setSmallIcon(R.drawable.ic_quick_subtitles)
            .setLargeIcon(badgeBitmap)
            .setContentIntent(togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_preferences, "Mở app", appPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setAutoCancel(false)
            .build()

        try {
            nm.notify(NOTIFICATION_ID, notification)
            CaptureMonitor.log("QuickAccess", "Đã hiện thông báo mở nhanh (Chạm để BẬT).")
        } catch (e: Exception) {
            CaptureMonitor.log("QuickAccess", "Lỗi hiện thông báo mở nhanh: ${e.message}")
        }
    }

    fun hideQuickNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    fun createColoredBadgeBitmap(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (64 * density).toInt().coerceAtLeast(64)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Nền tròn màu tím thương hiệu FloatSub
        val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8B5CF6")
            style = Paint.Style.FILL
        }
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paintBg)

        // Chữ "CC" trắng nổi bật, tương phản cao
        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val yOffset = (paintText.descent() + paintText.ascent()) / 2
        canvas.drawText("CC", radius, radius - yOffset, paintText)

        // Chấm xanh trạng thái trực tiếp
        val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#10B981")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size * 0.78f, size * 0.22f, size * 0.12f, paintDot)

        return bitmap
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FloatSub Truy Cập Nhanh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Thông báo thường trực cho phép chạm vào để bật/tắt nhanh FloatSub"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
