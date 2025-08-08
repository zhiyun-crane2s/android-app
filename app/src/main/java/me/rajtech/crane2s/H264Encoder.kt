package me.rajtech.crane2s

import android.media.*
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val useSurfaceInput: Boolean = true
) {

    companion object {
        private const val TAG = "H264Encoder"
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    var inputSurface: Surface? = null
        private set

    @Volatile var lastSps: ByteArray = ByteArray(0)
    @Volatile var lastPps: ByteArray = ByteArray(0)

    @Volatile var onFrameEncoded: ((accessUnit: ByteArray, isKeyframe: Boolean, ptsUs: Long) -> Unit)? = null

    private val stopFlag = AtomicBoolean(false)
    private var drainThread: Thread? = null
    private var encodeThread: Thread? = null
    private var frameCount = 0L

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)

        // Set basic encoding parameters
        format.setInteger(MediaFormat.KEY_BIT_RATE, max(300_000, bitrate))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

        if (useSurfaceInput) {
            // Surface input mode (for OpenGL rendering)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            Log.d(TAG, "Using surface input mode")
        } else {
            // Buffer input mode - find best supported color format
            val colorFormat = findBestColorFormat()
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            Log.d(TAG, "Using buffer input mode with color format: $colorFormat")
        }

        // Set profile and level for better compatibility
        try {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set profile/level: ${e.message}")
        }

        try {
            Log.d(TAG, "Configuring encoder: ${width}x${height}@${fps}fps")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            if (useSurfaceInput) {
                inputSurface = codec.createInputSurface()
            }

            codec.start()
            Log.d(TAG, "H.264 encoder started: ${width}x${height}@${fps}fps")

            // Start draining thread
            startDrainThread()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure encoder: ${e.message}", e)
            throw e
        }
    }

    private fun findBestColorFormat(): Int {
        try {
            val codecInfo = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).codecInfo
            val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val supportedFormats = capabilities.colorFormats

            Log.d(TAG, "Supported color formats: ${supportedFormats.joinToString()}")

            // Try formats in order of preference for YUV input
            val preferredFormats = intArrayOf(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,  // NV12 - most common
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,      // I420/YV12
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,    // Flexible
                21, // COLOR_FormatYUV420PackedSemiPlanar (NV21)
                0x7FA30C00, // Qualcomm specific format
                0x7FA30C04  // Another Qualcomm format
            )

            for (testFormat in preferredFormats) {
                if (supportedFormats.contains(testFormat)) {
                    Log.d(TAG, "Selected color format: $testFormat")
                    return testFormat
                }
            }

            // If no preferred format found, use the first available
            val fallback = supportedFormats.firstOrNull() ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            Log.w(TAG, "Using fallback color format: $fallback")
            return fallback

        } catch (e: Exception) {
            Log.w(TAG, "Could not query supported formats: ${e.message}")
            return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }
    }

    fun encodeFrame(yuvData: ByteArray, width: Int, height: Int) {
        if (useSurfaceInput) {
            Log.w(TAG, "Cannot encode YUV data in surface input mode")
            return
        }

        try {
            val timestampUs = System.nanoTime() / 1000 // Convert to microseconds
            val inputBufferIndex = codec.dequeueInputBuffer(10000) // 10ms timeout

            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(yuvData)

                codec.queueInputBuffer(inputBufferIndex, 0, yuvData.size, timestampUs, 0)
                frameCount++

                if (frameCount % 30 == 0L) {
                    Log.d(TAG, "Encoded frame $frameCount (${yuvData.size} bytes)")
                }
            } else {
                Log.w(TAG, "No input buffer available for encoding")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding frame: ${e.message}", e)
        }
    }

    fun stop() {
        stopFlag.set(true)

        try {
            // Signal end of stream
            if (!useSurfaceInput) {
                val inputBufferIndex = codec.dequeueInputBuffer(1000)
                if (inputBufferIndex >= 0) {
                    codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }

            drainThread?.join(2000)
            codec.stop()
            codec.release()
            inputSurface?.release()

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder: ${e.message}", e)
        }
    }

    private fun startDrainThread() {
        stopFlag.set(false)
        drainThread = Thread({
            try {
                drainEncoder()
            } catch (e: Exception) {
                Log.e(TAG, "Error in drain thread: ${e.message}", e)
            }
        }, "EncoderDrain").apply { start() }
    }

    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (!stopFlag.get()) {
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)

                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Encoder output format changed: ${codec.outputFormat}")
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            processEncodedFrame(outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            } catch (e: Exception) {
                if (!stopFlag.get()) {
                    Log.e(TAG, "Error draining encoder: ${e.message}", e)
                }
                break
            }
        }
    }

    private fun processEncodedFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(data, 0, info.size)

        val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

        // Extract SPS/PPS for keyframes
        if (isKeyframe) {
            extractSpsAndPps(data)
        }

        onFrameEncoded?.invoke(data, isKeyframe, info.presentationTimeUs)
    }

    private fun extractSpsAndPps(data: ByteArray) {
        // Simple SPS/PPS extraction logic
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {

                val nalType = data[i + 4].toInt() and 0x1F
                when (nalType) {
                    7 -> { // SPS
                        val end = findNextStartCode(data, i + 4)
                        lastSps = data.copyOfRange(i, end)
                    }
                    8 -> { // PPS
                        val end = findNextStartCode(data, i + 4)
                        lastPps = data.copyOfRange(i, end)
                    }
                }
            }
            i++
        }
    }

    private fun findNextStartCode(data: ByteArray, start: Int): Int {
        for (i in start until data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                return i
            }
        }
        return data.size
    }
}
