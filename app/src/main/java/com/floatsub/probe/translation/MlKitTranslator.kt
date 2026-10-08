package com.floatsub.probe.translation

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitTranslator(
    sourceLang: String = TranslateLanguage.ENGLISH,
    targetLang: String = TranslateLanguage.VIETNAMESE
) {
    companion object {
        private const val TAG = "MlKitTranslator"
    }

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(sourceLang)
        .setTargetLanguage(targetLang)
        .build()

    private val translator: Translator = Translation.getClient(options)
    private var isModelDownloaded = false

    /**
     * Tự động tải model offline (nếu chưa có trong máy).
     * Model EN-VI khoảng 30MB, tải 1 lần dùng vĩnh viễn không cần mạng.
     */
    suspend fun prepareModel(): Boolean = withContext(Dispatchers.IO) {
        if (isModelDownloaded) return@withContext true
        suspendCancellableCoroutine { continuation ->
            val conditions = DownloadConditions.Builder().build()
            Log.i(TAG, "Đang kiểm tra và tải model Google ML Kit Translate (EN -> VI)...")
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    Log.i(TAG, "Model ML Kit Translate đã sẵn sàng trên máy (Offline)!")
                    isModelDownloaded = true
                    continuation.resume(true)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Lỗi tải model ML Kit: ${e.message}", e)
                    continuation.resume(false)
                }
        }
    }

    /**
     * Dịch text tức thời bằng model on-device (chỉ mất ~10-25ms).
     */
    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isBlank()) return@withContext ""

        suspendCancellableCoroutine { continuation ->
            val startTs = System.currentTimeMillis()
            translator.translate(clean)
                .addOnSuccessListener { translated ->
                    val latencyMs = System.currentTimeMillis() - startTs
                    Log.d(TAG, "ML Kit Dịch [$latencyMs ms]: \"$clean\" -> \"$translated\"")
                    continuation.resume(translated)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Lỗi dịch ML Kit: ${e.message}")
                    // Nếu lỗi thì giữ nguyên text gốc
                    continuation.resume(clean)
                }
        }
    }

    fun close() {
        try {
            translator.close()
        } catch (_: Exception) {}
    }
}
