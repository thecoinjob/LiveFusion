package com.facefusion.mobile

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Synchronous, in-place processing for Live's full-resolution post-swap BGR frame.
 *
 * The analyzer thread calls this exactly once per frame, before any [LiveFrameSink].
 * Implementations must not retain [bgr] because [LiveEngine] reuses the array on the next
 * frame. Keeping the dimensions fixed lets preview, recording and RTSP share one result.
 */
fun interface LiveFrameProcessor {
    fun process(bgr: ByteArray, width: Int, height: Int)
}

/** Receives Live's canonical full-resolution, final BGR frame after post-processing. */
interface LiveFrameSink {
    fun frame(bgr: ByteArray, width: Int, height: Int)
}

/**
 * Fan-out point between Live's single face-swap inference and all output surfaces.
 *
 * A route is snapshotted at the start of each camera frame. This makes attach/detach safe
 * from the UI thread without locking the analyzer for an encoder or future overlay change.
 */
class LiveFramePipeline {
    @Volatile
    var processor: LiveFrameProcessor? = null

    private val sinks = CopyOnWriteArrayList<LiveFrameSink>()

    fun addSink(sink: LiveFrameSink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: LiveFrameSink) {
        sinks.remove(sink)
    }

    internal class Route(
        val processor: LiveFrameProcessor?,
        val sinks: Array<LiveFrameSink>,
    ) {
        val needsFullFrame: Boolean get() = processor != null || sinks.isNotEmpty()
    }

    internal fun snapshot(): Route = Route(processor, sinks.toTypedArray())

    /**
     * Process once, then deliver that same final byte array to every snapshotted sink.
     *
     * @return true when a processor changed the frame and the preview bitmap must be
     * regenerated from [bgr].
     */
    internal fun publish(route: Route, bgr: ByteArray, width: Int, height: Int): Boolean {
        route.processor?.process(bgr, width, height)
        route.sinks.forEach { it.frame(bgr, width, height) }
        return route.processor != null
    }
}
