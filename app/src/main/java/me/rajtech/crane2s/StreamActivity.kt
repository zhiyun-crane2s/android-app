package me.rajtech.crane2s.ui.stream

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jiangdongguo.usbcamera.UVCCameraHelper
import com.jiangdongguo.usbcamera.UVCCameraTextureView
import me.rajtech.crane2s.R
import java.net.Inet4Address
import java.net.NetworkInterface

class StreamActivity : AppCompatActivity() {

    private lateinit var cameraView: UVCCameraTextureView
    private lateinit var cameraHelper: UVCCameraHelper
    private lateinit var btnStart: Button
    private lateinit var tvUrl: TextView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream)

        // UI
        cameraView = findViewById(R.id.uvc_view)
        btnStart = findViewById(R.id.btn_start)
        tvUrl = findViewById(R.id.tv_url)
        tvStatus = findViewById(R.id.tv_status)

        tvUrl.text = "http://${getLocalIp()}:8080"

        cameraHelper = UVCCameraHelper.getInstance().apply {
            initUSBMonitor(this@StreamActivity, cameraView, null)
            setDefaultPreviewSize(1280, 720)
            setDefaultFrameFormat(UVCCameraHelper.FRAME_FORMAT_YUYV)
            setOnPreviewFrameListener { frameData ->
                // TODO: Stream to WebRTC/Encoder
            }
        }

        btnStart.setOnClickListener {
            if (!cameraHelper.isCameraOpened) {
                cameraHelper.startPreview(cameraView.surfaceTexture)
                tvStatus.text = "Status: Previewing"
            }
        }
    }

    override fun onStart() {
        super.onStart()
        cameraHelper.registerUSB()
    }

    override fun onStop() {
        cameraHelper.unregisterUSB()
        super.onStop()
    }

    override fun onDestroy() {
        cameraHelper.release()
        super.onDestroy()
    }

    private fun getLocalIp(): String {
        return NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress ?: "0.0.0.0"
    }
}
