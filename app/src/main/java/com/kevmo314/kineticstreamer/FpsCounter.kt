package com.kevmo314.kineticstreamer

/** Frames per second over a sliding window, fed one [tick] per frame. */
class FpsCounter(private val windowMs: Long = 3000) {
    private val ticks = ArrayDeque<Long>()

    @Volatile
    var fps = 0f
        private set

    @Synchronized
    fun tick(nowMs: Long) {
        ticks.addLast(nowMs)
        while (ticks.first() < nowMs - windowMs) ticks.removeFirst()
        val spanMs = nowMs - ticks.first()
        if (spanMs > 0) fps = (ticks.size - 1) * 1000f / spanMs
    }

    @Synchronized
    fun reset() {
        ticks.clear()
        fps = 0f
    }
}
