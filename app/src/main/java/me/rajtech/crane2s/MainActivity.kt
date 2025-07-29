package me.rajtech.crane2s.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import me.rajtech.crane2s.R
import me.rajtech.crane2s.ui.stream.StreamActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use the layout directly
        setContentView(R.layout.activity_main)

        // Find your buttons by ID
        val btnTracking    = findViewById<Button>(R.id.btn_tracking)
        val btnStream      = findViewById<Button>(R.id.btn_stream)
        val btnAccessory   = findViewById<Button>(R.id.btn_accessory_hub)

        // Wire up the Stream button
        btnStream.setOnClickListener {
            startActivity(Intent(this, StreamActivity::class.java))
        }
        // TODO: hook up TrackingActivity and AccessoryHubActivity when those exist
    }
}
