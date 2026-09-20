package com.facefusion.mobile

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/** Hardware-encodes Live's clean swapped BGR frames and publishes H.264 over local RTSP. */
class LiveRtspStreamer(
    port: Int = 8554,
    private val onStatus: (String) -> Unit = {},
) : LiveFrameSink {
    private val server = RtspH264Server(port, onStatus)
    private var encoder: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var startedNs = 0L
    private var stopped = false

    val url: String get() = server.url
    val ready: Boolean get() = server.ready

    init { server.start() }

    @Synchronized
    override fun frame(bgr: ByteArray, width: Int, height: Int) {
        if (stopped) return
        try {
            ensureEncoder(width, height)
            val enc = encoder ?: return
            drain(enc)
            val input = enc.dequeueInputBuffer(0)
            if (input < 0) return
            val ptsUs = (System.nanoTime() - startedNs) / 1_000L
            queueBgrFrame(enc, input, bgr, width, height, ptsUs) {
                onStatus("stream encoder: $it")
            }
            drain(enc)
        } catch (t: Throwable) {
            onStatus(codecWhy(t, "stream failed"))
        }
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        encoder?.let { enc ->
            runCatching {
                val input = enc.dequeueInputBuffer(20_000)
                if (input >= 0) enc.queueInputBuffer(
                    input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(enc)
            }
            runCatching { enc.stop() }
            runCatching { enc.release() }
        }
        encoder = null
        server.stop()
    }

    private fun ensureEncoder(w: Int, h: Int) {
        if (encoder != null) {
            if (w != width || h != height) error("stream frame size changed")
            return
        }
        width = w and 1.inv()
        height = h and 1.inv()
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 6).coerceAtLeast(2_000_000))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        encoder = enc
        startedNs = System.nanoTime()
        onStatus("Preparing clean stream…")
    }

    private fun drain(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = enc.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = enc.outputFormat
                    server.setCodecConfig(
                        f.getByteBuffer("csd-0")?.toByteArray() ?: ByteArray(0),
                        f.getByteBuffer("csd-1")?.toByteArray() ?: ByteArray(0),
                        width, height)
                }
                else -> if (index >= 0) {
                    val out = enc.getOutputBuffer(index)
                    if (out != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(info.size)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.get(bytes)
                        server.sendAccessUnit(bytes, info.presentationTimeUs)
                    }
                    enc.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                } else return
            }
        }
    }
}

private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
    val copy = duplicate()
    copy.position(0)
    return ByteArray(copy.remaining()).also { copy.get(it) }
}
