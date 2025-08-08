package me.rajtech.crane2s

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.google.android.material.button.MaterialButton

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called - Loading homepage")

        // Set the homepage layout
        setContentView(R.layout.activity_main)

        // Set up button click listeners
        setupButtonListeners()

        Log.d(TAG, "Homepage loaded successfully")
    }

    private fun setupButtonListeners() {
        // Tracking button
        findViewById<MaterialButton>(R.id.btn_tracking).setOnClickListener {
            Log.d(TAG, "Tracking button clicked")
            // TODO: Navigate to tracking activity when implemented
        }

        // Stream button - navigate to StreamActivity
        findViewById<MaterialButton>(R.id.btn_stream).setOnClickListener {
            Log.d(TAG, "Stream button clicked - navigating to StreamActivity")
            val intent = Intent(this, StreamActivity::class.java)
            startActivity(intent)
        }

        // Accessory Hub button
        findViewById<MaterialButton>(R.id.btn_accessory_hub).setOnClickListener {
            Log.d(TAG, "Accessory Hub button clicked")
            // TODO: Navigate to accessory hub activity when implemented
        }

        // Settings button
        findViewById<android.widget.ImageButton>(R.id.btn_settings).setOnClickListener {
            Log.d(TAG, "Settings button clicked")
            // TODO: Navigate to settings activity when implemented
        }
    }
}
