// app/src/main/java/me/rajtech/crane2s/ui/stream/VideoEncoder.kt
package me.rajtech.crane2s.ui.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build

/**
 * Simple wrapper around MediaCodec for H.264/H.265 encoding.
 */
class VideoEncoder(
    private val mime: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onEncoded: (ByteArray, MediaCodec.BufferInfo) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private var codec: MediaCodec? = null

    fun start() {
        try {
            codec = MediaCodec.createEncoderByType(mime).apply {
                val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                    if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC && Build.VERSION.SDK_INT >= 29) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                    } else {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    }
                }
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
                drainLoop()
            }
        } catch (e: Throwable) {
            onError(e)
        }
    }

    private fun drainLoop() {
        Thread {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val idx = codec?.dequeueOutputBuffer(info, 10_000) ?: break
                if (idx >= 0) {
                    val buf = codec!!.getOutputBuffer(idx)!!
                    val data = ByteArray(info.size).also { buf.get(it) }
                    onEncoded(data, info)
                    codec!!.releaseOutputBuffer(idx, false)
                } else break
            }
        }.start()
    }

    fun stop() {
        codec?.apply {
            stop()
            release()
        }
    }
}
