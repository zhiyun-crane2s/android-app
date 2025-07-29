// app/src/main/java/me/rajtech/crane2s/webrtc/UsbCameraCapturer.kt
package me.rajtech.crane2s.webrtc

import android.content.Context
import com.jiangdg.usbcamera.UVCCameraHelper
import org.webrtc.*

/**
 * Feeds raw UVC camera frames into WebRTC via CapturerObserver.
 */
class UsbCameraCapturer(
    private val context: Context,
    private val uvcHelper: UVCCameraHelper
) : VideoCapturer {

    private var observer: CapturerObserver?       = null
    private var textureHelper: SurfaceTextureHelper? = null

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        textureHelper = surfaceTextureHelper
        observer      = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        observer?.onCapturerStarted(true)
        uvcHelper.setOnPreviewFrameListener { data ->
            val i420 = JavaI420Buffer.allocate(width, height)
            // TODO: convert from YUYV in 'data' → I420 planes
            // i420.dataY.put(...); i420.dataU.put(...); i420.dataV.put(...)
            val frame = VideoFrame(i420, 0, System.nanoTime())
            observer?.onFrameCaptured(frame)
            frame.release()
        }
        uvcHelper.startPreview(textureHelper!!.surfaceTexture)
    }

    override fun stopCapture() {
        uvcHelper.stopPreview()
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {}
    override fun isScreencast(): Boolean = false
    override fun dispose() { stopCapture() }
}
