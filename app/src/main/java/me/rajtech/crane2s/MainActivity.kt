package me.rajtech.crane2s

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import me.rajtech.crane2s.RtspServer
import kotlinx.coroutines.*
import me.rajtech.crane2s.Camera2VideoSource
import me.rajtech.crane2s.H264Encoder

class MainActivity : ComponentActivity() {

    private lateinit var previewView: SurfaceView
    private lateinit var overlayText: TextView

    private var cameraSource: Camera2VideoSource? = null
    private var encoder: H264Encoder? = null
    private var rtspServer: RtspServer? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startEverythingIfReady() else overlayText.text = "CAMERA permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep display on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Programmatic layout: SurfaceView + overlay
        val root = FrameLayout(this)
        previewView = SurfaceView(this)
        overlayText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24, 24, 24, 24)
            text = "Starting…"
            setBackgroundColor(0x66000000)
        }

        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        root.addView(overlayText, overlayParams)

        // Set content view BEFORE accessing decorView/insetsController
        setContentView(root)

        // Now safely configure immersive mode
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { ic ->
                ic.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                ic.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }

        // Surface callbacks
        previewView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startEverythingIfReady()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                // no-op
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopEverything()
            }
        })
    }



    private fun startEverythingIfReady() {
        if (!previewView.holder.surface.isValid) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        // Clean up any previous run
        stopEverything()

        val width = 1280
        val height = 720
        val fps = 30
        val bitrate = 4_000_000

        encoder = H264Encoder(width, height, fps, bitrate).also { it.start() }

        cameraSource = Camera2VideoSource(this).apply {
            onInfo = { info -> overlayText.text = info }
            onError = { err -> overlayText.text = "Error: $err" }
        }

        // Start RTSP server
        rtspServer = RtspServer(
            port = 8554,
            spsProvider = { encoder?.lastSps ?: ByteArray(0) },
            ppsProvider = { encoder?.lastPps ?: ByteArray(0) }
        ).also { server ->
            server.start(scope)
            //val ip = NetworkUtils.getWifiIp(this) ?: "0.0.0.0"

//            overlayText.text = "RTSP rtsp://$ip:8554/stream"
            overlayText.text = "RTSP rtsp://0.0.0.0:8554/stream"
        }

        // Connect encoder output to RTSP sender
        encoder?.onFrameEncoded = { accessUnit, isKey, pts ->
            rtspServer?.pushH264AccessUnit(accessUnit, isKey, pts)
        }

        // ⬇️ FIX: call suspend function from a coroutine
        scope.launch {
            cameraSource?.start(
                previewSurface = previewView.holder.surface,
                encoderSurface = encoder!!.inputSurface,
                desiredSize = Pair(width, height),
                desiredFps = fps
            )
        }
    }

    private fun stopEverything() {
        runCatching { cameraSource?.stop() }
        runCatching { encoder?.stop() }
        runCatching { rtspServer?.stop() }
        cameraSource = null
        encoder = null
        rtspServer = null
    }

    override fun onStop() {
        super.onStop()
        stopEverything()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
