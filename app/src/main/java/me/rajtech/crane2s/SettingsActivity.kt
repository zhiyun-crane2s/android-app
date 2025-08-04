package me.rajtech.crane2s

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val PREFS_NAME = "app_settings"
        private const val PREF_DARK_MODE = "dark_mode"
        private const val PREF_DEBUG_MODE = "debug_mode"

        fun isDarkModeEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_DARK_MODE, false)
        }

        fun isDebugModeEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_DEBUG_MODE, false)
        }
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var darkModeSwitch: SwitchMaterial
    private lateinit var debugModeSwitch: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupToolbar()
        setupSwitches()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupSwitches() {
        darkModeSwitch = findViewById(R.id.switch_dark_mode)
        debugModeSwitch = findViewById(R.id.switch_debug_mode)

        // Set initial states
        darkModeSwitch.isChecked = prefs.getBoolean(PREF_DARK_MODE, false)
        debugModeSwitch.isChecked = prefs.getBoolean(PREF_DEBUG_MODE, false)

        // Dark mode switch listener
        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Dark mode toggled: $isChecked")
            prefs.edit().putBoolean(PREF_DARK_MODE, isChecked).apply()

            // Apply theme change immediately and recreate activity
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )

            // Recreate the activity to apply theme immediately
            recreate()
        }

        // Debug mode switch listener
        debugModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Debug mode toggled: $isChecked")
            prefs.edit().putBoolean(PREF_DEBUG_MODE, isChecked).apply()

            // No need to recreate for debug mode, it will take effect in next activity
        }
    }
}
