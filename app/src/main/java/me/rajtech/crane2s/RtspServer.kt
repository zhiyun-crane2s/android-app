package me.rajtech.crane2s

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class RtspServer(
    private val port: Int = 8554,
    private val spsProvider: () -> ByteArray,
    private val ppsProvider: () -> ByteArray
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    // State per single client (one at a time for simplicity)
    private var clientAddr: InetAddress? = null
    private var clientRtpPort: Int = 0
    private var clientRtcpPort: Int = 0
    private var sessionId: String = UUID.randomUUID().toString().replace("-", "")
    private var seq: Int = 0
    private var ssrc: Int = (System.nanoTime() and 0x7FFFFFFF).toInt()
    private val rtpSocket = DatagramSocket() // ephemeral server port for RTP
    private var startedStreaming = false
    private var timebase90k = 0L
    private val fps = 30
    private val tsStep = 90_000 / fps

    fun start(coroutineScope: CoroutineScope = scope) {
        if (running.getAndSet(true)) return
        coroutineScope.launch {
            serverSocket = ServerSocket(port, 1, InetAddress.getByName("0.0.0.0"))
            while (running.get()) {
                try {
                    val sock = serverSocket!!.accept()
                    handleClient(sock)
                } catch (_: Throwable) {
                    // ignore
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { rtpSocket.close() }
    }

    private fun handleClient(sock: Socket) = scope.launch {
        sock.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val writer = PrintWriter(sock.getOutputStream(), true)
        var cseq = "1"
        var trackReady = false

        while (running.get() && !sock.isClosed) {
            val requestLine = reader.readLine() ?: break
            if (requestLine.isBlank()) continue

            val headers = mutableMapOf<String, String>()
            var line: String
            while (true) {
                line = reader.readLine() ?: ""
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
            cseq = headers["cseq"] ?: "1"
            val method = requestLine.substringBefore(' ')
            val url = requestLine.substringAfter(' ').substringBefore(' ')
            val client = sock.inetAddress

            when (method) {
                "OPTIONS" -> {
                    writer.reply(cseq, 200, "Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY")
                }
                "DESCRIBE" -> {
                    val sps = spsProvider()
                    val pps = ppsProvider()
                    val spsB64 = Base64.encodeToString(sps.copyWithoutLengthPrefix(), Base64.NO_WRAP)
                    val ppsB64 = Base64.encodeToString(pps.copyWithoutLengthPrefix(), Base64.NO_WRAP)
                    val sdp = buildString {
                        appendLine("v=0")
                        appendLine("o=- 0 0 IN IP4 ${sock.localAddress.hostAddress}")
                        appendLine("s=Android H264")
                        appendLine("t=0 0")
                        appendLine("a=control:*")
                        appendLine("m=video 0 RTP/AVP 96")
                        appendLine("a=rtpmap:96 H264/90000")
                        appendLine("a=fmtp:96 packetization-mode=1; sprop-parameter-sets=$spsB64,$ppsB64")
                        appendLine("a=control:trackID=0")
                    }
                    writer.reply(cseq, 200, "Content-Base: $url\r\nContent-Type: application/sdp\r\nContent-Length: ${sdp.toByteArray().size}\r\n\r\n$sdp", raw = true)
                }
                "SETUP" -> {
                    val transport = headers["transport"] ?: ""
                    // We only handle UDP unicast
                    // Example: Transport: RTP/AVP;unicast;client_port=5000-5001
                    val clientPorts = transport.substringAfter("client_port=", "").substringBefore(";").split("-")
                    clientRtpPort = clientPorts.getOrNull(0)?.toIntOrNull() ?: 5000
                    clientRtcpPort = clientPorts.getOrNull(1)?.toIntOrNull() ?: (clientRtpPort + 1)
                    clientAddr = client
                    trackReady = true
                    val respTransport = "Transport: RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort;server_port=${rtpSocket.localPort}-${rtpSocket.localPort+1};ssrc=${ssrc.toUInt()}"
                    writer.reply(cseq, 200, "$respTransport\r\nSession: $sessionId")
                }
                "PLAY" -> {
                    if (trackReady) {
                        startedStreaming = true
                        timebase90k = 0L
                        writer.reply(cseq, 200, "RTP-Info: url=$url/trackID=0;seq=$seq;rtptime=$timebase90k\r\nSession: $sessionId")
                    } else {
                        writer.reply(cseq, 454, "Session Not Found")
                    }
                }
                "TEARDOWN" -> {
                    startedStreaming = false
                    writer.reply(cseq, 200, "Session: $sessionId")
                    sock.close()
                    break
                }
                else -> writer.reply(cseq, 501, "Not Implemented")
            }
        }
        runCatching { sock.close() }
    }

    /** Call this for each encoded access unit (AVCC length‑prefixed buffer). */
    fun pushH264AccessUnit(avccBuffer: ByteArray, isKeyframe: Boolean, ptsUs: Long) {
        if (!startedStreaming) return
        val addr = clientAddr ?: return
        val rtpPort = clientRtpPort
        val timestamp = timebase90k
        timebase90k += tsStep

        val nalUnits = AvccSplitter.split(avccBuffer)
        // Send SPS/PPS before first keyframe to help decoders
        if (isKeyframe) {
            val sps = spsProvider().copyWithoutLengthPrefix()
            val pps = ppsProvider().copyWithoutLengthPrefix()
            if (sps.isNotEmpty()) sendRtpNal(addr, rtpPort, sps, timestamp)
            if (pps.isNotEmpty()) sendRtpNal(addr, rtpPort, pps, timestamp)
        }
        for (nal in nalUnits) {
            sendRtpNal(addr, rtpPort, nal, timestamp)
        }
    }

    private fun sendRtpNal(addr: InetAddress, port: Int, nal: ByteArray, ts: Long) {
        val mtu = 1400
        if (nal.size + 12 <= mtu) {
            // Single NAL unit packet
            val pkt = ByteBuffer.allocate(12 + nal.size)
            writeRtpHeader(pkt, marker = 1, ts = ts)
            pkt.put(nal)
            pkt.flip()
            rtpSocket.send(DatagramPacket(pkt.array(), pkt.limit(), addr, port))
            seq = (seq + 1) and 0xFFFF
        } else {
            // FU-A fragmentation
            val nalHeader = nal[0].toInt()
            val nri = nalHeader and 0b0110_0000
            val type = nalHeader and 0b0001_1111
            var offset = 1
            var first = true
            while (offset < nal.size) {
                val remaining = nal.size - offset
                val maxPayload = mtu - 12 - 2 // FU-A header size
                val chunk = min(remaining, maxPayload)
                val pkt = ByteBuffer.allocate(12 + 2 + chunk)
                writeRtpHeader(pkt, marker = if (offset + chunk >= nal.size) 1 else 0, ts = ts)
                // FU indicator + FU header
                pkt.put((nri or 28).toByte())  // FU-A type = 28
                val fuHeader = (if (first) 0x80 else 0x00) or (if (offset + chunk >= nal.size) 0x40 else 0x00) or type
                pkt.put(fuHeader.toByte())
                pkt.put(nal, offset, chunk)
                pkt.flip()
                rtpSocket.send(DatagramPacket(pkt.array(), pkt.limit(), addr, port))
                seq = (seq + 1) and 0xFFFF
                offset += chunk
                first = false
            }
        }
    }

    private fun writeRtpHeader(buf: ByteBuffer, marker: Int, ts: Long) {
        buf.put(0x80.toByte())                 // V=2,P=0,X=0,CC=0
        buf.put(((marker shl 7) or 96).toByte()) // M + PT=96
        buf.putShort(seq.toShort())
        buf.putInt(ts.toInt())                 // 32-bit timestamp
        buf.putInt(ssrc)
    }

    private fun PrintWriter.reply(cseq: String, code: Int, extra: String = "", raw: Boolean = false) {
        if (raw) {
            print("RTSP/1.0 $code OK\r\nCSeq: $cseq\r\n$extra")
            flush()
            return
        }
        val body = if (extra.contains("\r\n\r\n")) extra.substringAfter("\r\n\r\n") else null
        val headers = if (extra.contains("\r\n\r\n")) extra.substringBefore("\r\n\r\n") else extra
        val lenHeader = if (body != null) "Content-Length: ${body.toByteArray().size}\r\n" else ""
        print("RTSP/1.0 $code OK\r\nCSeq: $cseq\r\n$headers\r\n$lenHeader\r\n${body ?: ""}")
        flush()
    }
}

/** Split AVCC (4-byte length prefixed) buffer into individual NAL units (no start code). */
private object AvccSplitter {
    fun split(buf: ByteArray): List<ByteArray> {
        val list = ArrayList<ByteArray>()
        var i = 0
        while (i + 4 <= buf.size) {
            val len = ((buf[i].toInt() and 0xFF) shl 24) or
                    ((buf[i+1].toInt() and 0xFF) shl 16) or
                    ((buf[i+2].toInt() and 0xFF) shl 8) or
                    (buf[i+3].toInt() and 0xFF)
            i += 4
            if (i + len <= buf.size) {
                val nal = buf.copyOfRange(i, i + len)
                list.add(nal)
            }
            i += len
        }
        return list
    }
}

private fun ByteArray.copyWithoutLengthPrefix(): ByteArray {
    if (size < 4) return this
    // Some devices return csd as full NAL (already without 4-byte length); if it looks like AVCC, strip it.
    val maybeLen = ((this[0].toInt() and 0xFF) shl 24) or
            ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or
            (this[3].toInt() and 0xFF)
    return if (maybeLen in 4..(this.size - 4) && maybeLen == this.size - 4) {
        copyOfRange(4, size)
    } else this
}
