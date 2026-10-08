package com.floatsub.probe.cloud

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioWavConverter {

    data class ConversionResult(
        val wavBytes: ByteArray,
        val prepTimeMs: Long
    )

    /**
     * Converts 16-bit Mono PCM raw bytes (at sampleRate, default 16000Hz)
     * to a standard 44-byte RIFF WAV byte array.
     */
    fun pcmToWav(pcmBytes: ByteArray, sampleRate: Int = 16000): ConversionResult {
        val startTime = SystemClock.elapsedRealtime()

        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmBytes.size
        val chunkSize = 36 + dataSize

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        buffer.put('R'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.putInt(chunkSize)
        buffer.put('W'.code.toByte())
        buffer.put('A'.code.toByte())
        buffer.put('V'.code.toByte())
        buffer.put('E'.code.toByte())

        // "fmt " sub-chunk
        buffer.put('f'.code.toByte())
        buffer.put('m'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put(' '.code.toByte())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1.toShort()) // AudioFormat 1 = PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())

        // "data" sub-chunk
        buffer.put('d'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.putInt(dataSize)

        val wavBytes = ByteArray(44 + dataSize)
        System.arraycopy(header, 0, wavBytes, 0, 44)
        System.arraycopy(pcmBytes, 0, wavBytes, 44, dataSize)

        val prepTimeMs = SystemClock.elapsedRealtime() - startTime
        return ConversionResult(wavBytes, prepTimeMs)
    }
}
