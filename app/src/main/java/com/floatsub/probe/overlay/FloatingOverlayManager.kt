package com.floatsub.probe.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.floatsub.probe.CaptureMonitor
import com.floatsub.probe.R
import kotlin.math.abs

class FloatingOverlayManager(
    private val context: Context,
    private val onStopRequested: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_FloatSubProbe)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var subtitleBoxView: View? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var subtitleParams: WindowManager.LayoutParams? = null

    private var isBubbleShowing = false
    private var isSubtitleBoxShowing = false
    private var isSourceTextVisible = true

    fun isPermissionGranted(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun show() {
        if (!isPermissionGranted()) {
            CaptureMonitor.log("Overlay", "Không thể hiển thị Overlay: Chưa có quyền SYSTEM_ALERT_WINDOW!")
            return
        }

        mainHandler.post {
            try {
                if (!isBubbleShowing) {
                    createAndAttachBubble()
                }
                if (!isSubtitleBoxShowing) {
                    createAndAttachSubtitleBox()
                }
            } catch (e: Exception) {
                CaptureMonitor.log("Overlay", "Lỗi khi đính kèm Overlay: ${e.message}")
            }
        }
    }

    fun hide() {
        mainHandler.post {
            removeBubble()
            removeSubtitleBox()
        }
    }

    fun updateSubtitle(
        sourceText: String,
        translatedText: String,
        sourceLang: String? = null,
        targetLang: String? = null
    ) {
        mainHandler.post {
            subtitleBoxView?.let { root ->
                val tvSource = root.findViewById<TextView>(R.id.tvOverlaySource)
                val tvTranslated = root.findViewById<TextView>(R.id.tvOverlayTranslated)
                val tvBadge = root.findViewById<TextView>(R.id.tvOverlayBadge)

                if (sourceText.isNotBlank()) {
                    val formattedSource = formatSentenceCase(sourceText)
                    tvSource.text = formatTwoLines(formattedSource, 28)
                }
                if (translatedText.isNotBlank()) {
                    val formattedTranslated = formatSentenceCase(translatedText)
                    tvTranslated.text = formatTwoLines(formattedTranslated, 24)
                }

                if (!sourceLang.isNullOrBlank() && !targetLang.isNullOrBlank()) {
                    tvBadge.text = "FloatSub [$sourceLang → $targetLang]"
                }
            }
        }
    }

    /**
     * Chuẩn hoá chữ hoa / chữ thường câu:
     * Viết hoa chữ cái đầu tiên, phần còn lại viết thường.
     * Triệt tiêu hiện tượng ký tự nhảy in hoa lộn xộn giữa chừng khi streaming.
     */
    private fun formatSentenceCase(text: String): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        val lower = clean.lowercase(java.util.Locale.getDefault())
        return lower.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
        }
    }

    /**
     * Tự động cân bằng phụ đề thành 2 dòng (Two-line subtitle balancing):
     * Tìm vị trí dấu cách gần trung điểm văn bản nhất để ngắt dòng tự nhiên,
     * tránh hiện tượng dồn tất cả chữ vào 1 hàng dài khó đọc.
     */
    private fun formatTwoLines(text: String, maxLineChars: Int = 24): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        if (clean.contains("\n")) return clean
        val words = clean.split("\\s+".toRegex()).filter { it.isNotBlank() }
        // Nếu quá ngắn (dưới 4 từ và <= maxLineChars ký tự), giữ nguyên 1 dòng ngắn
        if (words.size < 4 && clean.length <= maxLineChars) return clean

        val mid = clean.length / 2
        var bestIndex = -1
        var bestDiff = Int.MAX_VALUE
        var currentLen = 0
        for (i in 0 until words.size - 1) {
            currentLen += words[i].length + 1
            val diff = kotlin.math.abs(currentLen - mid)
            if (diff < bestDiff) {
                bestDiff = diff
                bestIndex = i
            }
        }
        return if (bestIndex != -1) {
            val line1 = words.subList(0, bestIndex + 1).joinToString(" ")
            val line2 = words.subList(bestIndex + 1, words.size).joinToString(" ")
            "$line1\n$line2"
        } else {
            clean
        }
    }

    private fun openMainActivity() {
        try {
            val intent = android.content.Intent(context, com.floatsub.probe.MainActivity::class.java).apply {
                action = android.content.Intent.ACTION_MAIN
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent.send()
            CaptureMonitor.log("Overlay", "Đã mở lại FloatSub MainActivity từ icon nổi (qua PendingIntent).")
        } catch (e: Exception) {
            try {
                val fallbackIntent = android.content.Intent(context, com.floatsub.probe.MainActivity::class.java).apply {
                    action = android.content.Intent.ACTION_MAIN
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(fallbackIntent)
                CaptureMonitor.log("Overlay", "Đã mở lại MainActivity bằng fallback startActivity.")
            } catch (e2: Exception) {
                CaptureMonitor.log("Overlay", "Lỗi mở MainActivity: ${e2.message}")
            }
        }
    }

    private fun createAndAttachBubble() {
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.layout_floating_bubble, null)
        bubbleView = view

        val layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 300
        }
        bubbleParams = params

        setupBubbleTouchListener(view, params)

        windowManager.addView(view, params)
        isBubbleShowing = true
        CaptureMonitor.log("Overlay", "Đã hiển thị Floating Bubble.")
    }

    private fun createAndAttachSubtitleBox() {
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.layout_floating_subtitles, null)
        subtitleBoxView = view

        val layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (screenHeight * 0.65).toInt() // Initial position around lower third of screen
        }
        subtitleParams = params

        setupSubtitleBoxControls(view, params)

        windowManager.addView(view, params)
        isSubtitleBoxShowing = true
        CaptureMonitor.log("Overlay", "Đã hiển thị Floating Subtitle Popup.")
    }

    private fun setupBubbleTouchListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isLongClickTriggered = false
        val longClickRunnable = Runnable {
            isLongClickTriggered = true
            CaptureMonitor.log("Overlay", "Người dùng nhấn giữ icon Float -> Mở app MainActivity")
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            openMainActivity()
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isLongClickTriggered = false
                    mainHandler.postDelayed(longClickRunnable, 400)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)
                    if (deltaX > 20 || deltaY > 20) {
                        mainHandler.removeCallbacks(longClickRunnable)
                    }
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longClickRunnable)
                    if (!isLongClickTriggered) {
                        val deltaX = abs(event.rawX - initialTouchX)
                        val deltaY = abs(event.rawY - initialTouchY)
                        // If moved less than 20px, treat as click: toggle subtitle box
                        if (deltaX < 20 && deltaY < 20) {
                            toggleSubtitleBoxVisibility()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupSubtitleBoxControls(view: View, params: WindowManager.LayoutParams) {
        val cardSubtitleBox = view.findViewById<View>(R.id.cardSubtitleBox)
        val dragHeader = view.findViewById<View>(R.id.layoutDragHeader)
        val btnToggleSource = view.findViewById<TextView>(R.id.btnToggleSourceView)
        val btnBgOpacity = view.findViewById<TextView>(R.id.btnOverlayBgOpacity)
        val btnMinimize = view.findViewById<TextView>(R.id.btnOverlayMinimize)
        val btnStop = view.findViewById<TextView>(R.id.btnOverlayStop)
        val tvSource = view.findViewById<TextView>(R.id.tvOverlaySource)


        // Background Opacity Cycler: Mờ (33%) -> Trong suốt (8%) -> Đậm (60%)
        val bgModes = listOf(
            Pair("Nền: Mờ", 0x55000000.toInt()),
            Pair("Nền: Trong", 0x15000000.toInt()),
            Pair("Nền: Đậm", 0x99000000.toInt())
        )
        var bgIndex = 0
        btnBgOpacity.setOnClickListener {
            bgIndex = (bgIndex + 1) % bgModes.size
            val (label, color) = bgModes[bgIndex]
            btnBgOpacity.text = label
            val drawable = cardSubtitleBox.background as? android.graphics.drawable.GradientDrawable
            drawable?.setColor(color)
            CaptureMonitor.log("Overlay", "Chuyển độ mờ overlay: $label")
        }

        // Draggable subtitle header
        var initialY = 0
        var initialTouchY = 0f
        dragHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        // Toggle Source Text Button
        btnToggleSource.setOnClickListener {
            isSourceTextVisible = !isSourceTextVisible
            tvSource.visibility = if (isSourceTextVisible) View.VISIBLE else View.GONE
            btnToggleSource.text = if (isSourceTextVisible) "Ẩn gốc" else "Hiện gốc"
        }

        // Minimize to bubble button
        btnMinimize.setOnClickListener {
            toggleSubtitleBoxVisibility()
        }

        // Stop live pipeline button
        btnStop.setOnClickListener {
            CaptureMonitor.log("Overlay", "Người dùng bấm DỪNG trên overlay.")
            onStopRequested()
        }
    }

    private fun toggleSubtitleBoxVisibility() {
        val view = subtitleBoxView ?: return
        if (view.visibility == View.VISIBLE) {
            view.visibility = View.GONE
            CaptureMonitor.log("Overlay", "Đã thu nhỏ hộp phụ đề.")
        } else {
            view.visibility = View.VISIBLE
            CaptureMonitor.log("Overlay", "Đã mở lại hộp phụ đề.")
        }
    }

    private fun removeBubble() {
        bubbleView?.let {
            try {
                if (isBubbleShowing) {
                    windowManager.removeView(it)
                    isBubbleShowing = false
                }
            } catch (e: Exception) {
                CaptureMonitor.log("Overlay", "Lỗi khi gỡ Bubble: ${e.message}")
            }
        }
        bubbleView = null
    }

    private fun removeSubtitleBox() {
        subtitleBoxView?.let {
            try {
                if (isSubtitleBoxShowing) {
                    windowManager.removeView(it)
                    isSubtitleBoxShowing = false
                }
            } catch (e: Exception) {
                CaptureMonitor.log("Overlay", "Lỗi khi gỡ Subtitle Box: ${e.message}")
            }
        }
        subtitleBoxView = null
    }
}
