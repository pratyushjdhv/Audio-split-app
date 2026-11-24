package com.audiosplit.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Byte ring between the capture thread (one writer) and the playback thread (one reader).
 *
 * Exists so the capture side never waits on the output side. The platform capture buffer
 * for playback capture is fixed at Android's minimum (~64 ms) whatever size is requested,
 * and it silently drops audio when full. Anything that holds the capture thread up — a
 * Bluetooth burst blocking a write, the delay slider's added silence — used to land in
 * that tiny buffer and overflow it. Here it lands in [AudioSpec.RING_CAPACITY_MS] of app memory
 * instead.
 *
 * Every write and read is frame-aligned because AudioRecord only hands out whole frames,
 * so the positions stay aligned without extra bookkeeping.
 */
class PcmRing(capacityBytes: Int) {

    private val data = ByteArray(AudioSpec.alignToFrame(capacityBytes))
    private val capacity = data.size
    private val lock = ReentrantLock()
    private val dataArrived = lock.newCondition()

    private var readPos = 0
    private var size = 0
    private var closed = false

    /** Bytes thrown away because the ring was full. Should stay at zero; logged if not. */
    @Volatile
    var overflowBytes = 0L
        private set

    val available: Int
        get() = lock.withLock { size }

    /** Appends [length] bytes. If there's no room, the OLDEST audio is dropped. */
    fun write(src: ByteArray, offset: Int, length: Int) {
        lock.withLock {
            var off = offset
            var len = length
            if (len > capacity) {
                off += len - capacity
                len = capacity
            }
            val free = capacity - size
            if (len > free) {
                val drop = len - free
                readPos = (readPos + drop) % capacity
                size -= drop
                overflowBytes += drop
            }
            var writePos = (readPos + size) % capacity
            var remaining = len
            while (remaining > 0) {
                val n = minOf(remaining, capacity - writePos)
                System.arraycopy(src, off, data, writePos, n)
                off += n
                remaining -= n
                writePos = (writePos + n) % capacity
            }
            size += len
            dataArrived.signal()
        }
    }

    /**
     * Waits up to [timeoutMs] for [length] bytes, then copies out whatever is there, up to
     * [length]. Returns the number of bytes copied: less than [length] only if capture
     * stalled or the ring was closed.
     */
    fun read(dst: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        lock.withLock {
            var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (size < length && !closed && nanos > 0L) {
                nanos = dataArrived.awaitNanos(nanos)
            }
            val n = AudioSpec.alignToFrame(minOf(size, length))
            var out = offset
            var remaining = n
            while (remaining > 0) {
                val chunk = minOf(remaining, capacity - readPos)
                System.arraycopy(data, readPos, dst, out, chunk)
                out += chunk
                remaining -= chunk
                readPos = (readPos + chunk) % capacity
            }
            size -= n
            return n
        }
    }

    /** Discards up to [length] of the oldest bytes. Returns how many were discarded. */
    fun skip(length: Int): Int {
        lock.withLock {
            val n = AudioSpec.alignToFrame(minOf(size, length.coerceAtLeast(0)))
            readPos = (readPos + n) % capacity
            size -= n
            return n
        }
    }

    /** Wakes a waiting reader for good; used when the mirror stops. */
    fun close() {
        lock.withLock {
            closed = true
            dataArrived.signalAll()
        }
    }
}
