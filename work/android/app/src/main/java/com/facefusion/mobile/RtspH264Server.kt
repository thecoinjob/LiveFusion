package com.facefusion.mobile

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Minimal one-stream RTSP server supporting both RTP/UDP and RTP-over-RTSP/TCP. */
class RtspH264Server(
    private val port: Int,
    private val onStatus: (String) -> Unit = {},
) {
    val url: String get() = "rtsp://127.0.0.1:$port/live"
    val ready: Boolean get() = sps != null && pps != null

    private val running = AtomicBoolean(false)
    private var listener: ServerSocket? = null
    @Volatile private var session: ClientSession? = null
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var videoWidth = 1280
    @Volatile private var videoHeight = 720
    private val ssrc = 0x4c465553 // "LFUS"
    private var sequence = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "livefusion-rtsp", isDaemon = true) {
            try {
                val server = ServerSocket(port)
                listener = server
                onStatus("RTSP server started: $url")
                while (running.get()) {
                    val socket = server.accept()
                    thread(name = "livefusion-rtsp-client", isDaemon = true) {
                        handleClient(socket)
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) onStatus("RTSP server: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { listener?.close() }
        listener = null
        session?.close()
        session = null
    }

    fun setCodecConfig(csd0: ByteArray, csd1: ByteArray, width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        val nals = splitNals(csd0) + splitNals(csd1)
        nals.firstOrNull { nalType(it) == 7 }?.let { sps = it }
        nals.firstOrNull { nalType(it) == 8 }?.let { pps = it }
        if (ready) onStatus("Clean stream ready: $url")
    }

    fun sendAccessUnit(encoded: ByteArray, ptsUs: Long) {
        val nals = splitNals(encoded)
        if (nals.isEmpty()) return
        nals.firstOrNull { nalType(it) == 7 }?.let { sps = it }
        nals.firstOrNull { nalType(it) == 8 }?.let { pps = it }
        val client = session ?: return
        val media = nals.filter { nalType(it) !in 7..8 }
        if (media.isEmpty()) return
        val timestamp = ((ptsUs * 90L) / 1_000L).toInt()
        media.forEachIndexed { index, nal ->
            sendNal(client, nal, timestamp, index == media.lastIndex)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        try {
            val input = socket.getInputStream()
            while (running.get() && !socket.isClosed) {
                val request = readRequest(input) ?: break
                val lines = request.split("\r\n")
                val first = lines.firstOrNull()?.split(' ') ?: break
                if (first.size < 2) break
                val method = first[0]
                val requestUrl = first[1]
                val headers = lines.drop(1).mapNotNull {
                    val at = it.indexOf(':')
                    if (at <= 0) null else it.substring(0, at).trim().lowercase() to
                            it.substring(at + 1).trim()
                }.toMap()
                val cseq = headers["cseq"] ?: "1"
                when (method) {
                    "OPTIONS" -> respond(socket, cseq, 200, extra =
                        "Public: OPTIONS, DESCRIBE, SETUP, PLAY, GET_PARAMETER, TEARDOWN\r\n")
                    "DESCRIBE" -> {
                        val description = sdp(requestUrl)
                        if (description == null) respond(socket, cseq, 503)
                        else respond(socket, cseq, 200,
                            "Content-Base: $requestUrl/\r\nContent-Type: application/sdp\r\n",
                            description)
                    }
                    "SETUP" -> {
                        val transport = headers["transport"] ?: ""
                        session?.close()
                        val created = if (transport.contains("RTP/AVP/TCP", true)) {
                            val channels = Regex("interleaved=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE)
                                .find(transport)
                            ClientSession.tcp(socket, channels?.groupValues?.get(1)?.toIntOrNull() ?: 0)
                        } else {
                            val ports = Regex("client_port=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE)
                                .find(transport)
                            val rtpPort = ports?.groupValues?.get(1)?.toIntOrNull()
                            if (rtpPort == null) null
                            else ClientSession.udp(socket, socket.inetAddress, rtpPort)
                        }
                        if (created == null) respond(socket, cseq, 461)
                        else {
                            session = created
                            val replyTransport = if (created.tcp)
                                "Transport: RTP/AVP/TCP;unicast;interleaved=${created.channel}-${created.channel + 1}\r\n"
                            else "Transport: RTP/AVP;unicast;client_port=${created.clientPort}-${created.clientPort + 1};server_port=${created.localPort}-${created.localPort + 1}\r\n"
                            respond(socket, cseq, 200, replyTransport + "Session: livefusion\r\n")
                        }
                    }
                    "PLAY" -> {
                        respond(socket, cseq, 200,
                            "Session: livefusion\r\nRTP-Info: url=$requestUrl;seq=$sequence\r\n")
                        onStatus("VCAM connected to LiveFusion")
                    }
                    "GET_PARAMETER" -> respond(socket, cseq, 200, "Session: livefusion\r\n")
                    "TEARDOWN" -> {
                        respond(socket, cseq, 200, "Session: livefusion\r\n")
                        session?.close(); session = null
                        return
                    }
                    else -> respond(socket, cseq, 405)
                }
            }
        } catch (_: Throwable) {
        } finally {
            if (session?.control === socket) {
                session?.close(); session = null
            }
            runCatching { socket.close() }
        }
    }

    private fun sdp(requestUrl: String): String? {
        val s = sps ?: return null
        val p = pps ?: return null
        val profile = if (s.size >= 4) "%02X%02X%02X".format(
            s[1].toInt() and 0xFF, s[2].toInt() and 0xFF, s[3].toInt() and 0xFF)
        else "42E01F"
        val s64 = Base64.encodeToString(s, Base64.NO_WRAP)
        val p64 = Base64.encodeToString(p, Base64.NO_WRAP)
        return "v=0\r\n" +
            "o=- 0 0 IN IP4 127.0.0.1\r\n" +
            "s=LiveFusion clean stream\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "t=0 0\r\n" +
            "a=control:*\r\n" +
            "a=x-dimensions:$videoWidth,$videoHeight\r\n" +
            "m=video 0 RTP/AVP 96\r\n" +
            "a=rtpmap:96 H264/90000\r\n" +
            "a=framesize:96 $videoWidth-$videoHeight\r\n" +
            "a=fmtp:96 packetization-mode=1;profile-level-id=$profile;sprop-parameter-sets=$s64,$p64\r\n" +
            "a=control:${requestUrl.trimEnd('/')}/trackID=0\r\n"
    }

    private fun sendNal(client: ClientSession, nal: ByteArray, timestamp: Int, marker: Boolean) {
        if (nal.isEmpty()) return
        val maxPayload = 1_200
        if (nal.size <= maxPayload) {
            client.send(rtpPacket(nal, timestamp, marker))
            return
        }
        val indicator = (nal[0].toInt() and 0xE0) or 28
        val type = nal[0].toInt() and 0x1F
        var offset = 1
        var first = true
        while (offset < nal.size) {
            val count = minOf(maxPayload - 2, nal.size - offset)
            val last = offset + count >= nal.size
            val payload = ByteArray(count + 2)
            payload[0] = indicator.toByte()
            payload[1] = (type or (if (first) 0x80 else 0) or (if (last) 0x40 else 0)).toByte()
            System.arraycopy(nal, offset, payload, 2, count)
            client.send(rtpPacket(payload, timestamp, marker && last))
            offset += count
            first = false
        }
    }

    private fun rtpPacket(payload: ByteArray, timestamp: Int, marker: Boolean): ByteArray {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = (96 or if (marker) 0x80 else 0).toByte()
        val seq = sequence++ and 0xFFFF
        packet[2] = (seq ushr 8).toByte(); packet[3] = seq.toByte()
        packet[4] = (timestamp ushr 24).toByte(); packet[5] = (timestamp ushr 16).toByte()
        packet[6] = (timestamp ushr 8).toByte(); packet[7] = timestamp.toByte()
        packet[8] = (ssrc ushr 24).toByte(); packet[9] = (ssrc ushr 16).toByte()
        packet[10] = (ssrc ushr 8).toByte(); packet[11] = ssrc.toByte()
        System.arraycopy(payload, 0, packet, 12, payload.size)
        return packet
    }

    private fun respond(socket: Socket, cseq: String, code: Int,
                        extra: String = "", body: String? = null) {
        val reason = when (code) {
            200 -> "OK"; 405 -> "Method Not Allowed"; 461 -> "Unsupported Transport"
            503 -> "Service Unavailable"; else -> "Error"
        }
        val content = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val header = "RTSP/1.0 $code $reason\r\nCSeq: $cseq\r\n" + extra +
            (if (body != null) "Content-Length: ${content.size}\r\n" else "") + "\r\n"
        synchronized(socket) {
            socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII))
            if (content.isNotEmpty()) socket.getOutputStream().write(content)
            socket.getOutputStream().flush()
        }
    }

    private class ClientSession private constructor(
        val control: Socket,
        val tcp: Boolean,
        val channel: Int,
        val clientPort: Int,
        private val target: InetAddress?,
        private val udpSocket: DatagramSocket?,
    ) {
        val localPort: Int get() = udpSocket?.localPort ?: 0
        fun send(packet: ByteArray) {
            if (tcp) synchronized(control) {
                val out = control.getOutputStream()
                out.write(byteArrayOf('$'.code.toByte(), channel.toByte(),
                    (packet.size ushr 8).toByte(), packet.size.toByte()))
                out.write(packet); out.flush()
            } else {
                val address = target ?: return
                udpSocket?.send(DatagramPacket(packet, packet.size, address, clientPort))
            }
        }
        fun close() { runCatching { udpSocket?.close() } }
        companion object {
            fun tcp(control: Socket, channel: Int) =
                ClientSession(control, true, channel, 0, null, null)
            fun udp(control: Socket, address: InetAddress, port: Int): ClientSession {
                val socket = DatagramSocket()
                return ClientSession(control, false, 0, port, address, socket)
            }
        }
    }
}

private fun readRequest(input: InputStream): String? {
    val out = ByteArrayOutputStream()
    var matched = 0
    while (out.size() < 64 * 1024) {
        val b = input.read()
        if (b < 0) return null
        // Ignore interleaved RTCP sent by a TCP client.
        if (out.size() == 0 && b == '$'.code) {
            input.read()
            val hi = input.read(); val lo = input.read()
            if (hi < 0 || lo < 0) return null
            var left = (hi shl 8) or lo
            while (left-- > 0 && input.read() >= 0) { }
            continue
        }
        out.write(b)
        matched = when {
            matched == 0 && b == '\r'.code -> 1
            matched == 1 && b == '\n'.code -> 2
            matched == 2 && b == '\r'.code -> 3
            matched == 3 && b == '\n'.code -> 4
            b == '\r'.code -> 1
            else -> 0
        }
        if (matched == 4) return out.toString(Charsets.US_ASCII.name())
    }
    return null
}

private fun splitNals(data: ByteArray): List<ByteArray> {
    if (data.isEmpty()) return emptyList()
    val starts = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i + 3 < data.size) {
        val three = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
        val four = i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
            data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
        if (four) { starts += i to 4; i += 4 }
        else if (three) { starts += i to 3; i += 3 }
        else i++
    }
    if (starts.isNotEmpty()) return starts.mapIndexedNotNull { index, (at, prefix) ->
        val end = starts.getOrNull(index + 1)?.first ?: data.size
        if (end <= at + prefix) null else data.copyOfRange(at + prefix, end)
    }
    // Some encoders emit AVCC: a big-endian length before each NAL.
    val result = mutableListOf<ByteArray>()
    i = 0
    while (i + 4 <= data.size) {
        val size = ByteBuffer.wrap(data, i, 4).int
        if (size <= 0 || i + 4 + size > data.size) break
        result += data.copyOfRange(i + 4, i + 4 + size)
        i += 4 + size
    }
    return if (result.isNotEmpty() && i == data.size) result else listOf(data)
}

private fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F
