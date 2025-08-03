package me.rajtech.crane2s

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
import android.hardware.camera2.CameraCharacteristics.LENS_FACING
import android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
import android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL
import android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class Camera2VideoSource(private val context: Context) {

    var onInfo: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val manager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private val openCloseLock = Semaphore(1)

    suspend fun start(
        previewSurface: Surface,
        encoderSurface: Surface,
        desiredSize: Pair<Int, Int>,
        desiredFps: Int
    ) = withContext(Dispatchers.Main) {
        try {
            val id = chooseCameraId()
            onInfo?.invoke("Using camera: $id")
            openCamera(id)
            val sz = chooseSize(id, desiredSize.first, desiredSize.second)
            val fpsRange = chooseFpsRange(id, desiredFps)
            onInfo?.invoke("Size ${sz.width}x${sz.height} @ ${fpsRange.upper}fps")

            val targets = listOf(previewSurface, encoderSurface)
            createSession(camera!!, targets) { sess ->
                val request = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                    addTarget(previewSurface)
                    addTarget(encoderSurface)
                }.build()
                sess.setRepeatingRequest(request, null, null)
            }
        } catch (t: Throwable) {
            onError?.invoke(t.message ?: "Camera error")
            stop()
        }
    }

    fun stop() {
        try { session?.close() } catch (_: Throwable) {}
        try { camera?.close() } catch (_: Throwable) {}
        session = null
        camera = null
    }

    private fun chooseCameraId(): String {
        // Prefer external camera if present (USB UVC exposed by Camera2)
        val ids = manager.cameraIdList
        var back: String? = null
        for (id in ids) {
            val chars = manager.getCameraCharacteristics(id)
            when (chars.get(LENS_FACING)) {
                LENS_FACING_EXTERNAL -> return id
                LENS_FACING_BACK -> back = id
            }
        }
        return back ?: ids.first()
    }

    private fun chooseSize(cameraId: String, w: Int, h: Int): Size {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap
        val sizes = map.getOutputSizes(Surface::class.java)
        val target = Size(w, h)
        return sizes.minByOrNull { sz -> abs(sz.width * sz.height - target.width * target.height) }
            ?: target
    }

    private fun chooseFpsRange(cameraId: String, fps: Int): Range<Int> {
        val chars = manager.getCameraCharacteristics(cameraId)
        val ranges = chars.get(CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf(Range(30, 30))
        var best = ranges.first()
        var bestScore = Int.MAX_VALUE
        for (r in ranges) {
            val score = abs(r.upper - fps) + abs(r.lower - fps)
            if (score < bestScore) { best = r; bestScore = score }
        }
        return best
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera(cameraId: String) {
        if (!openCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Time out waiting to lock camera opening.")
        }
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) {
                openCloseLock.release()
                camera = c
            }
            override fun onDisconnected(c: CameraDevice) {
                openCloseLock.release()
                c.close()
                camera = null
            }
            override fun onError(c: CameraDevice, error: Int) {
                openCloseLock.release()
                c.close()
                camera = null
                onError?.invoke("Camera error $error")
            }
        }, null)

        // Wait until camera != null
        val start = System.currentTimeMillis()
        while (camera == null && System.currentTimeMillis() - start < 2500) {
            Thread.sleep(10)
        }
        if (camera == null) throw RuntimeException("Failed to open camera")
    }

    private fun createSession(
        cam: CameraDevice,
        targets: List<Surface>,
        onReady: (CameraCaptureSession) -> Unit
    ) {
        cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                this@Camera2VideoSource.session = session
                onReady(session)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError?.invoke("CaptureSession configure failed")
            }
        }, null)
    }
}
