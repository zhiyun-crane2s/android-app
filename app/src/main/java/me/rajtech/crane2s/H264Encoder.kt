package me.rajtech.crane2s

import android.media.*
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int
) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    lateinit var inputSurface: Surface private set

    @Volatile var lastSps: ByteArray = ByteArray(0)
    @Volatile var lastPps: ByteArray = ByteArray(0)

    @Volatile var onFrameEncoded: ((accessUnit: ByteArray, isKeyframe: Boolean, ptsUs: Long) -> Unit)? = null

    private val stopFlag = AtomicBoolean(false)
    private var drainThread: Thread? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, max(300_000, bitrate))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // seconds

        // Try setting profile/level if available
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()

        stopFlag.set(false)
        drainThread = Thread(::drainLoop, "H264Drain").apply { start() }
    }

    fun stop() {
        stopFlag.set(true)
        try { drainThread?.join(1000) } catch (_: Throwable) {}
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (!stopFlag.get()) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 50_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = codec.outputFormat
                    // csd-0 = SPS, csd-1 = PPS (length-prefixed)
                    of.getByteBuffer("csd-0")?.let { lastSps = it.toByteArray() }
                    of.getByteBuffer("csd-1")?.let { lastPps = it.toByteArray() }
                }
                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex) ?: continue
                    val data = ByteArray(bufferInfo.size)
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    outBuf.get(data)
                    val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    // Emit one access unit (AVCC length-prefixed NALs in 'data')
                    onFrameEncoded?.invoke(data, isKey, bufferInfo.presentationTimeUs)
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val b = ByteArray(remaining())
        get(b)
        return b
    }
}
