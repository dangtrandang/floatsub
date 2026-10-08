package com.floatsub.probe.quicktranslate

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Thread-safe Circular Ring Buffer for 16kHz, 16-bit Mono PCM audio.
 * Standard format: 16,000 samples/sec * 2 bytes/sample = 32,000 bytes/sec.
 */
class PcmRingBuffer(
    val capacitySeconds: Double = 3.0,
    val sampleRate: Int = 16000,
    val bytesPerSample: Int = 2
) {
    val bytesPerSecond: Int = sampleRate * bytesPerSample // 32,000 bytes/sec
    val capacityBytes: Int = (capacitySeconds * bytesPerSecond).toInt() // 96,000 bytes for 3.0s

    private val buffer = ByteArray(capacityBytes)
    private var head: Int = 0 // Next write index
    private var totalBytesWritten: Long = 0L
    private val lock = ReentrantLock()

    /**
     * Ghi một mảng byte PCM vào ring buffer theo vòng tròn.
     */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (length <= 0) return
        lock.withLock {
            var srcPos = offset
            var remaining = length

            while (remaining > 0) {
                val spaceToEnd = capacityBytes - head
                val toCopy = min(remaining, spaceToEnd)
                System.arraycopy(data, srcPos, buffer, head, toCopy)
                head = (head + toCopy) % capacityBytes
                srcPos += toCopy
                remaining -= toCopy
            }
            totalBytesWritten += length
        }
    }

    /**
     * Ghi một mảng Short (16-bit PCM) vào ring buffer.
     */
    fun write(samples: ShortArray, offset: Int = 0, count: Int = samples.size) {
        if (count <= 0) return
        val byteBuffer = ByteArray(count * 2)
        var bIdx = 0
        for (i in offset until (offset + count)) {
            val s = samples[i].toInt()
            byteBuffer[bIdx++] = (s and 0xFF).toByte()
            byteBuffer[bIdx++] = ((s shr 8) and 0xFF).toByte()
        }
        write(byteBuffer, 0, byteBuffer.size)
    }

    /**
     * Snapshot chính xác N giây audio gần nhất từ ring buffer.
     * Trả về một mảng ByteArray hoàn toàn độc lập (không lo bị đè bởi luồng ghi tiếp theo).
     *
     * Đảm bảo:
     * - Chỉ lấy tối đa [durationSec] gần nhất.
     * - Không lấy audio vượt quá cửa sổ buffer (không tràn ra ngoài 3s).
     * - Nếu buffer chưa có đủ audio (mới khởi động), chỉ lấy đúng số lượng đã ghi và zero-pad phần trước.
     */
    fun snapshotLast(durationSec: Double): ByteArray {
        val targetBytes = (durationSec * bytesPerSecond).toInt().coerceIn(0, capacityBytes)
        val result = ByteArray(targetBytes)

        lock.withLock {
            val available = min(totalBytesWritten, capacityBytes.toLong()).toInt()
            val bytesToCopy = min(targetBytes, available)
            if (bytesToCopy <= 0) {
                return result // Chưa có audio -> trả về silence
            }

            // Vị trí bắt đầu đọc trong circular buffer: head - bytesToCopy
            var startRead = (head - bytesToCopy) % capacityBytes
            if (startRead < 0) {
                startRead += capacityBytes
            }

            // Ghi vào cuối mảng result (nếu targetBytes > bytesToCopy thì phần đầu là 0/silence)
            val destOffset = targetBytes - bytesToCopy
            val firstChunk = min(bytesToCopy, capacityBytes - startRead)
            System.arraycopy(buffer, startRead, result, destOffset, firstChunk)

            val secondChunk = bytesToCopy - firstChunk
            if (secondChunk > 0) {
                System.arraycopy(buffer, 0, result, destOffset + firstChunk, secondChunk)
            }
        }

        return result
    }

    /**
     * Reset buffer: xóa toàn bộ dữ liệu, chống rò rỉ âm thanh giữa các Reel/phiên.
     */
    fun clear() {
        lock.withLock {
            buffer.fill(0)
            head = 0
            totalBytesWritten = 0L
        }
    }

    fun getTotalBytesWritten(): Long = lock.withLock { totalBytesWritten }

    fun getAvailableDurationSec(): Double = lock.withLock {
        val available = min(totalBytesWritten, capacityBytes.toLong())
        available.toDouble() / bytesPerSecond
    }
}
