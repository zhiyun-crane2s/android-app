package me.rajtech.crane2s

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.util.Log
import com.jiangdg.usbcamera.UVCCameraHelper
import com.jiangdg.usbcamera.widget.UVCCameraView
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class UVCVideoSource(private val activity: Activity) {

    companion object {
        private const val TAG = "UVCVideoSource"
    }

    var onInfo: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onFrameGenerated: ((ByteArray, Int, Int) -> Unit)? = null

    private var uvcCameraHelper: UVCCameraHelper? = null
    private var isPreviewing = false
    private var cameraView: UVCCameraView? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val deviceConnectListener = object : UVCCameraHelper.OnMyDevConnectListener {
        override fun onAttachDev(device: UsbDevice?) {
            Log.d(TAG, "USB device attached: ${device?.deviceName}")
            onInfo?.invoke("USB device attached: ${device?.deviceName}")
            if ((uvcCameraHelper?.usbDeviceList?.size ?: 0) > 0) {
                uvcCameraHelper?.requestPermission(0)
            }
        }

        override fun onDettachDev(device: UsbDevice?) {
            Log.d(TAG, "USB device detached: ${device?.deviceName}")
            onInfo?.invoke("USB device detached: ${device?.deviceName}")
            stopCamera()
        }

        override fun onConnectDev(device: UsbDevice?, isConnected: Boolean) {
            Log.d(TAG, "USB device connect result: ${device?.deviceName}, connected: $isConnected")

            if (isConnected) {
                onInfo?.invoke("UVC camera connected successfully")
                startPreview()
            } else {
                onError?.invoke("Failed to connect to UVC camera")
            }
        }

        override fun onDisConnectDev(device: UsbDevice?) {
            Log.d(TAG, "USB device disconnected: ${device?.deviceName}")
            onInfo?.invoke("USB device disconnected")
            stopCamera()
        }
    }

    fun initialize(cameraView: UVCCameraView) {
        Log.d(TAG, "Initializing UVC video source")
        this.cameraView = cameraView
        try {
            uvcCameraHelper = UVCCameraHelper.getInstance()
            uvcCameraHelper?.initUSBMonitor(activity, cameraView, deviceConnectListener)
            uvcCameraHelper?.setOnPreviewFrameListener { frame: ByteBuffer ->
                val bytes = ByteArray(frame.remaining())
                frame.get(bytes)
                val width = uvcCameraHelper?.getPreviewWidth() ?: 0
                val height = uvcCameraHelper?.getPreviewHeight() ?: 0
                onFrameGenerated?.invoke(bytes, width, height)
            }
            onInfo?.invoke("USB monitor initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize USB monitor", e)
            onError?.invoke("Failed to initialize USB monitor: ${e.message}")
        }
    }

    private fun startPreview() {
        if (uvcCameraHelper == null || cameraView == null || isPreviewing) {
            Log.w(TAG, "Cannot start preview - helper: ${uvcCameraHelper != null}, cameraView: ${cameraView != null}, started: $isPreviewing")
            return
        }

        try {
            // The library handles the preview on the CameraViewInterface
            isPreviewing = true
            onInfo?.invoke("Camera preview started successfully")
            Log.d(TAG, "UVC camera preview started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview", e)
            onError?.invoke("Failed to start camera preview: ${e.message}")
        }
    }

    private fun stopPreview() {
        if (!isPreviewing) return
        try {
            uvcCameraHelper?.stopPreview()
            isPreviewing = false
            onInfo?.invoke("Camera preview stopped")
            Log.d(TAG, "UVC camera preview stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping preview", e)
        }
    }

    fun startCamera() {
        Log.d(TAG, "Starting camera - looking for USB devices")
        scope.launch {
            try {
                if (uvcCameraHelper?.usbDeviceList?.isNotEmpty() == true) {
                    onInfo?.invoke("Found ${uvcCameraHelper?.usbDeviceList?.size} UVC camera(s)")
                    uvcCameraHelper?.requestPermission(0)
                } else {
                    onError?.invoke("No USB devices found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting camera", e)
                onError?.invoke("Error starting camera: ${e.message}")
            }
        }
    }

    fun stopCamera() {
        Log.d(TAG, "Stopping camera")
        scope.launch {
            stopPreview()
        }
    }

    fun release() {
        Log.d(TAG, "Releasing UVC video source")
        scope.cancel()
        stopCamera()
        try {
            uvcCameraHelper?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing UVC camera helper", e)
        }
    }
}
