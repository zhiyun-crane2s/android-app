package me.rajtech.crane2s

import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate
import com.jiangdongguo.usbcamera.UVCCameraHelper
import com.jiangdongguo.usbcamera.UVCCameraTextureView
import com.jiangdongguo.usbcamera.utils.FileUtils
import kotlinx.coroutines.*

class StreamActivity : ComponentActivity() {

    companion object {
        private const val TAG = "StreamActivity"
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
    }

    private var debugOverlay: DebugOverlay? = null
    private lateinit var mUVCCameraView: UVCCameraTextureView
    private lateinit var btnStart: Button

    private var mCameraHelper: UVCCameraHelper? = null
    private var isRequest = false
    private var isConnect = false
    private var isPreview = false

    private var encoder: H264Encoder? = null
    private var rtspServer: RtspServer? = null
    private var encoderSurface: Surface? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before super.onCreate()
        applyTheme()

        super.onCreate(savedInstanceState)
        debugLog("onCreate called")

        setContentView(R.layout.activity_stream)

        initViews()
        initUVCCamera()
        setupEncoder()
    }

    private fun applyTheme() {
        val isDarkMode = SettingsActivity.isDarkModeEnabled(this)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun debugLog(message: String, level: String = "D") {
        when (level) {
            "E" -> Log.e(TAG, message)
            "W" -> Log.w(TAG, message)
            "I" -> Log.i(TAG, message)
            else -> Log.d(TAG, message)
        }
        debugOverlay?.addLog(TAG, message, level)
    }

    private fun initViews() {
        mUVCCameraView = findViewById(R.id.uvc_view)
        btnStart = findViewById(R.id.btn_start)

        // Create debug overlay
        debugOverlay = DebugOverlay(this)

        // Add debug overlay to the root container
        val rootContainer = findViewById<android.widget.FrameLayout>(R.id.stream_container)
        rootContainer.addView(
            debugOverlay,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                300
            ).apply {
                gravity = android.view.Gravity.BOTTOM
            }
        )

        debugOverlay?.updateVisibility()

        btnStart.setOnClickListener {
            if (!isConnect) {
                debugLog("Attempting to connect to UVC camera...")
                mCameraHelper?.requestPermission(0)
            } else {
                if (isPreview) {
                    debugLog("Stopping camera preview")
                    mCameraHelper?.stopPreview()
                    stopStreaming()
                    btnStart.text = "Start Camera"
                    isPreview = false
                } else {
                    debugLog("Starting camera preview")
                    mCameraHelper?.startPreview(mUVCCameraView)
                    startStreaming()
                    btnStart.text = "Stop Camera"
                    isPreview = true
                }
            }
        }

        debugLog("Views initialized")
    }

    private fun initUVCCamera() {
        debugLog("Initializing UVC camera helper")

        // Set callback for the UVC camera view
        mUVCCameraView.setCallback(object : UVCCameraTextureView.CameraViewInterface.Callback {
            override fun onSurfaceCreated(view: UVCCameraTextureView.CameraViewInterface?, surface: Surface?) {
                debugLog("UVC Camera surface created")
                if (!isPreview && mCameraHelper?.isCameraOpened == true) {
                    mCameraHelper?.startPreview(mUVCCameraView)
                    isPreview = true
                    btnStart.text = "Stop Camera"
                }
            }

            override fun onSurfaceChanged(view: UVCCameraTextureView.CameraViewInterface?, surface: Surface?, width: Int, height: Int) {
                debugLog("UVC Camera surface changed: ${width}x${height}")
            }

            override fun onSurfaceDestroy(view: UVCCameraTextureView.CameraViewInterface?, surface: Surface?) {
                debugLog("UVC Camera surface destroyed")
                if (isPreview && mCameraHelper?.isCameraOpened == true) {
                    mCameraHelper?.stopPreview()
                    isPreview = false
                }
            }
        })

        mCameraHelper = UVCCameraHelper.getInstance()

        mCameraHelper?.let { helper ->
            helper.setDefaultFrameFormat(UVCCameraHelper.FRAME_FORMAT_MJPEG)
            helper.initUSBMonitor(this, mUVCCameraView, listener)
            helper.setOnPreviewFrameListener { nv21Yuv ->
                // This callback receives raw camera frames
                debugLog("Received frame: ${nv21Yuv?.size ?: 0} bytes")
            }
        }

        debugLog("UVC camera helper initialized")
    }

    private fun setupEncoder() {
        debugLog("Setting up H.264 encoder")

        try {
            val width = PREVIEW_WIDTH
            val height = PREVIEW_HEIGHT
            val fps = 30
            val bitrate = 4_000_000

            encoder = H264Encoder(width, height, fps, bitrate).also { enc ->
                enc.start()
                encoderSurface = enc.inputSurface
                debugLog("H.264 encoder started: ${width}x${height}@${fps}fps")
            }

            // Start RTSP server
            rtspServer = RtspServer(
                port = 8554,
                spsProvider = { encoder?.lastSps ?: ByteArray(0) },
                ppsProvider = { encoder?.lastPps ?: ByteArray(0) }
            ).also { server ->
                server.start(scope)
                debugLog("RTSP server started on port 8554")
            }

            // Connect encoder output to RTSP
            encoder?.onFrameEncoded = { accessUnit, isKey, pts ->
                rtspServer?.pushH264AccessUnit(accessUnit, isKey, pts)
            }

        } catch (e: Exception) {
            debugLog("Error setting up encoder: ${e.message}", "E")
        }
    }

    private fun startStreaming() {
        debugLog("Starting RTSP streaming...")
        showShortMsg("RTSP stream available at rtsp://0.0.0.0:8554/stream")
    }

    private fun stopStreaming() {
        debugLog("Stopping streaming...")
        runCatching { encoder?.stop() }
        runCatching { rtspServer?.stop() }
    }

    // UVCCameraHelper listener
    private val listener = object : UVCCameraHelper.OnMyDevConnectListener {
        override fun onAttachDev(device: UsbDevice?) {
            debugLog("USB device attached: ${device?.deviceName}")
            // Request permission automatically when device is attached
            if (!isRequest) {
                isRequest = true
                mCameraHelper?.requestPermission(0)
            }
        }

        override fun onDettachDev(device: UsbDevice?) {
            debugLog("USB device detached: ${device?.deviceName}")
            if (isRequest) {
                isRequest = false
                mCameraHelper?.closeCamera()
                showShortMsg("USB device detached")
                isConnect = false
                isPreview = false
                runOnUiThread {
                    btnStart.text = "Start Camera"
                }
            }
        }

        override fun onConnectDev(device: UsbDevice?, isConnected: Boolean) {
            debugLog("USB device connect result: $isConnected")
            if (!isConnected) {
                showShortMsg("Failed to connect USB device")
                isPreview = false
                isRequest = false
                isConnect = false
                runOnUiThread {
                    btnStart.text = "Start Camera"
                }
            } else {
                isConnect = true
                showShortMsg("USB device connected - Ready to preview")
                runOnUiThread {
                    btnStart.text = "Start Preview"
                }
            }
        }

        override fun onDisConnectDev(device: UsbDevice?) {
            debugLog("USB device disconnected")
            showShortMsg("USB device disconnected")
            isPreview = false
            isRequest = false
            isConnect = false
            runOnUiThread {
                btnStart.text = "Start Camera"
            }
        }
    }

    private fun showShortMsg(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        debugLog("Toast: $msg")
    }

    override fun onStart() {
        super.onStart()
        debugLog("onStart called")
        mCameraHelper?.registerUSB()
    }

    override fun onStop() {
        super.onStop()
        debugLog("onStop called")
        mCameraHelper?.unregisterUSB()
    }

    override fun onDestroy() {
        super.onDestroy()
        debugLog("onDestroy called")

        FileUtils.releaseFile()
        mCameraHelper?.release()

        stopStreaming()
        scope.cancel()
    }

    override fun onResume() {
        super.onResume()
        debugOverlay?.updateVisibility()
    }
}
