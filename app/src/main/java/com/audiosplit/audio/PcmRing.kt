package com.audiosplit.audio

/**
 * Byte ring buffer decoupling the capture thread from the playback thread.
 *
 * The decoupling is the point. When capture and playback ran in one synchronous loop, the
 * amount of audio in flight was invisible — it accumulated in the kernel's capture buffer
 * where nothing could measure it, so drift between the capture clock and the USB DAC's own
 * crystal silently ate the headroom until the buffer overran and dropped audio. Here the
 * occupancy is a number the playback thread can read every iteration, which is what makes
 * the drift correction in MirrorEngine possible at all.
 *
 * Overflow discards the oldest bytes rather than blocking, so a stalled output can never
 * back-pressure into the capture thread and lose data at the OS level instead.
 */
class PcmRing(private val capacity: Int) {

    private val buf = ByteArray(capacity)
    private val lock = Object()
    private var head = 0
    private var count = 0

    /** Bytes discarded on overflow. Non-zero means the output could not keep up. */
    var droppedBytes = 0L
        private set

    val available: Int
        get() = synchronized(lock) { count }

    fun write(src: ByteArray, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            // A write larger than the ring can only keep its tail.
            var offset = 0
            var len = length
            if (len > capacity) {
                offset = len - capacity
                droppedBytes += offset.toLong()
                len = capacity
            }
            val overflow = count + len - capacity
            if (overflow > 0) {
                head = (head + overflow) % capacity
                count -= overflow
                droppedBytes += overflow.toLong()
            }
            var tail = (head + count) % capacity
            var remaining = len
            while (remaining > 0) {
                val n = minOf(remaining, capacity - tail)
                System.arraycopy(src, offset, buf, tail, n)
                tail = (tail + n) % capacity
                offset += n
                remaining -= n
            }
            count += len
        }
    }

    /** Copies up to [length] bytes out. Returns how many were actually available. */
    fun read(dst: ByteArray, length: Int): Int {
        if (length <= 0) return 0
        synchronized(lock) {
            val n = minOf(length, count)
            var offset = 0
            var remaining = n
            while (remaining > 0) {
                val m = minOf(remaining, capacity - head)
                System.arraycopy(buf, head, dst, offset, m)
                head = (head + m) % capacity
                offset += m
                remaining -= m
            }
            count -= n
            return n
        }
    }

    /** Discards up to [length] bytes of the oldest audio. Used to shed accumulated drift. */
    fun discard(length: Int): Int {
        if (length <= 0) return 0
        synchronized(lock) {
            val n = minOf(length, count)
            head = (head + n) % capacity
            count -= n
            return n
        }
    }
}
