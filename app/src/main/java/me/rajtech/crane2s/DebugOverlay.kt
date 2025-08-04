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

        post {
            logQueue.offer(logEntry)

            // Keep only last maxLines
            while (logQueue.size > maxLines) {
                logQueue.poll()
            }

            // Update display
            val allLogs = logQueue.joinToString("\n")
            logTextView.text = allLogs

            // Auto-scroll to bottom
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }

            // Show overlay if debug mode is enabled
            visibility = if (SettingsActivity.isDebugModeEnabled(context)) VISIBLE else GONE
        }
    }

    fun updateVisibility() {
        visibility = if (SettingsActivity.isDebugModeEnabled(context)) VISIBLE else GONE
    }

    fun clear() {
        logQueue.clear()
        logTextView.text = ""
    }
}
