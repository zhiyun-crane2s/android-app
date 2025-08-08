package me.rajtech.crane2s

import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate
import com.jiangdg.usbcamera.UVCCameraHelper
import kotlinx.coroutines.*

class StreamActivity : ComponentActivity() {

    companion object {
        private const val TAG = "StreamActivity"
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
    }

    private var debugOverlay: DebugOverlay? = null
    private lateinit var textureView: TextureView
    private lateinit var btnStart: Button

    private var cameraSource: UVCVideoSource? = null
    private var isPreviewing = false

    private var encoder: H264Encoder? = null
    private var rtspServer: RtspServer? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream)

        initViews()
        setupCameraSource()
        setupEncoderAndServer()
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
        textureView = findViewById(R.id.texture_view)
        btnStart = findViewById(R.id.btn_start)
        debugOverlay = DebugOverlay(this)

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
            if (isPreviewing) {
                stopPreview()
            } else {
                startPreview()
            }
        }
        debugLog("Views initialized")
    }

    private fun setupCameraSource() {
        cameraSource = UVCVideoSource(this).apply {
            onInfo = { info ->
                debugLog("UVC Info: $info")
                showToast(info)
            }
            onError = { error ->
                debugLog("UVC Error: $error", "E")
                showToast("Error: $error")
                if (isPreviewing) {
                    stopPreview()
                }
            }
            onFrameGenerated = { data, _, _ ->
                encoder?.input(data)
            }
            initialize(textureView)
        }
    }

    private fun setupEncoderAndServer() {
        scope.launch(Dispatchers.IO) {
            try {
                debugLog("Setting up H.264 encoder")
                encoder = H264Encoder(PREVIEW_WIDTH, PREVIEW_HEIGHT, 30, 4000000).apply {
                    onFrame = { data, isKeyFrame ->
                        rtspServer?.sendVideo(data, isKeyFrame)
                    }
                    start()
                }
                debugLog("H.264 encoder started")

                debugLog("Creating RTSP server")
                rtspServer = RtspServer().apply {
                    start()
                }
                debugLog("RTSP server started")
            } catch (e: Exception) {
                debugLog("Encoder/RTSP setup failed: ${e.message}", "E")
                showToast("Encoder/RTSP setup failed")
            }
        }
    }

    private fun startPreview() {
        if (isPreviewing) return
        debugLog("Starting preview...")
        cameraSource?.startCamera()
        isPreviewing = true
        btnStart.text = "Stop Camera"
    }

    private fun stopPreview() {
        if (!isPreviewing) return
        debugLog("Stopping preview...")
        cameraSource?.stopCamera()
        isPreviewing = false
        btnStart.text = "Start Camera"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        cameraSource?.release()
        encoder?.stop()
        rtspServer?.stop()
        debugLog("StreamActivity destroyed")
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
