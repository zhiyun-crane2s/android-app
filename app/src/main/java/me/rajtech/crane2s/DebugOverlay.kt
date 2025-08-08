package me.rajtech.crane2s

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class DebugOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val logTextView: TextView
    private val scrollView: ScrollView
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val maxLines = 50
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Throttling variables to prevent UI flood
    private var lastUpdateTime = 0L
    private val updateThrottleMs = 100L // Only update UI every 100ms
    private var pendingUpdate = false

    init {
        // Create scroll view
        scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                setMargins(16, 16, 16, 16)
            }
            alpha = 0.8f
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(12, 12, 12, 12)
        }

        // Create text view for logs
        logTextView = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.BOTTOM
            setPadding(8, 8, 8, 8)
        }

        scrollView.addView(logTextView)
        addView(scrollView)

        // Initially hidden
        visibility = GONE
    }

    fun addLog(tag: String, message: String, level: String = "D") {
        if (!SettingsActivity.isDebugModeEnabled(context)) return

        val timestamp = timeFormat.format(Date())
        val logEntry = "$timestamp $level/$tag: $message"

        // Add to queue immediately (thread-safe)
        logQueue.offer(logEntry)

        // Keep only last maxLines
        while (logQueue.size > maxLines) {
            logQueue.poll()
        }

        // More aggressive throttling to prevent ANY UI performance issues
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime > updateThrottleMs && !pendingUpdate) {
            pendingUpdate = true
            lastUpdateTime = currentTime

            // Use postDelayed instead of post for better performance
            postDelayed({
                try {
                    updateDisplay()
                } finally {
                    pendingUpdate = false
                }
            }, 50) // Small delay to batch operations
        } else if (!pendingUpdate) {
            // Schedule a delayed update if we're throttling - with longer delay
            pendingUpdate = true
            postDelayed({
                try {
                    updateDisplay()
                } finally {
                    pendingUpdate = false
                }
            }, updateThrottleMs + 50) // Extra delay for performance
        }
    }

    private fun updateDisplay() {
        try {
            // Update display
            val allLogs = logQueue.joinToString("\n")
            logTextView.text = allLogs

            // Auto-scroll to bottom (combine into single operation)
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)

            // Show overlay if debug mode is enabled
            visibility = if (SettingsActivity.isDebugModeEnabled(context)) VISIBLE else GONE
        } catch (e: Exception) {
            Log.e("DebugOverlay", "Error updating display: ${e.message}")
        }
    }

    fun updateVisibility() {
        visibility = if (SettingsActivity.isDebugModeEnabled(context)) VISIBLE else GONE
    }

    fun clear() {
        logQueue.clear()
        post {
            logTextView.text = ""
        }
    }
}
