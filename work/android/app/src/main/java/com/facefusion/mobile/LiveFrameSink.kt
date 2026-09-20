package com.facefusion.mobile

/** Receives the clean, full-resolution post-swap BGR frame from [LiveEngine]. */
interface LiveFrameSink {
    fun frame(bgr: ByteArray, width: Int, height: Int)
}
