package com.example.fingertrackingandroid

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.slider.Slider
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var slider: Slider
    private lateinit var sendMoneyText: TextView
    private lateinit var denyIcon: ImageView
    private lateinit var confirmIcon: ImageView

    // We'll store all touch events for each swipe
    private val motionData = mutableListOf<MotionEventData>()

    // Handler for 2s hold logic
    private val holdHandler = Handler(Looper.getMainLooper())
    private var holdRunnable: Runnable? = null

    // "cancel", "confirm", or null
    private var pendingRegion: String? = null

    // If accepted, ignore further touches until next finger-down
    private var hasAccepted = false

    // Default background color (gray) for reset
    private val defaultBgColor by lazy { resources.getColor(R.color.gray_light, theme) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Tells the system that we do NOT want to handle insets by default
            WindowCompat.setDecorFitsSystemWindows(window, false)

            // Access the new WindowInsetsController
            val controller = window.insetsController
            if (controller != null) {
                // Hide both the navigation bar and the status bar
                controller.hide(
                    WindowInsets.Type.statusBars() or
                            WindowInsets.Type.navigationBars()
                )

                // Optional: Keep behavior so system bars can come back with a swipe
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        actionBar?.hide()

        slider = findViewById(R.id.swipeSlider)
        sendMoneyText = findViewById(R.id.sendMoneyText)
        denyIcon = findViewById(R.id.denyIcon)
        confirmIcon = findViewById(R.id.confirmIcon)

        // Start slider in middle
        slider.value = 50f

        // Finger down/up
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                motionData.clear()
                hasAccepted = false
                pendingRegion = null
                clearHoldRunnable()
            }

            override fun onStopTrackingTouch(slider: Slider) {
                // If no acceptance, reset immediately on finger lift
                if (!hasAccepted) {
                    resetSlider()
                }
            }
        })

        // Value changes => color fade + icon fade + region detection
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            if (hasAccepted) return@addOnChangeListener

            val fraction = (value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)

            // 1) Background color from red->green
            val currentColor = blendColors(Color.RED, Color.GREEN, fraction)
            slider.foregroundTintList = ColorStateList.valueOf(currentColor)

            // 2) Icon crossfade:
            // left = 1 - fraction, right = fraction
            denyIcon.alpha = 1f - fraction
            confirmIcon.alpha = fraction

            // 3) Region detection
            when {
                value <= 25 -> {
                    if (pendingRegion != "cancel") {
                        pendingRegion = "cancel"
                        scheduleHoldCheck("cancel", 3000)
                    }
                }
                value >= 75 -> {
                    if (pendingRegion != "confirm") {
                        pendingRegion = "confirm"
                        scheduleHoldCheck("confirm", 3000)
                    }
                }
                else -> {
                    // Middle => no region
                    if (pendingRegion != null) {
                        clearHoldRunnable()
                        pendingRegion = null
                    }
                }
            }
        }

        // Log touches, skip if accepted
        slider.setOnTouchListener { v, event ->
            if (!hasAccepted) {
                motionData.add(
                    MotionEventData(
                        timestamp = System.currentTimeMillis(),
                        x = event.x,
                        y = event.y,
                        action = event.action
                    )
                )
            }
            v.onTouchEvent(event)
        }
    }

    private fun scheduleHoldCheck(region: String, delayMs: Long) {
        clearHoldRunnable()
        holdRunnable = Runnable {
            if (!hasAccepted && pendingRegion == region) {
                acceptAction(region)
            }
        }
        holdHandler.postDelayed(holdRunnable!!, delayMs)
    }

    private fun clearHoldRunnable() {
        holdRunnable?.let { holdHandler.removeCallbacks(it) }
        holdRunnable = null
    }

    private fun acceptAction(region: String) {
        hasAccepted = true
        clearHoldRunnable()
        pendingRegion = null

        if (region == "cancel") {
            Toast.makeText(this, "Cancelled!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Confirmed!", Toast.LENGTH_SHORT).show()
        }

        // Save CSV
        saveDataToCsv(motionData, region)

        // Immediately reset so the slider + icons go back to default
        resetSlider()
    }

    private fun resetSlider() {
        // Reset slider to middle
        slider.value = 50f
        // Reset background color to default gray
        slider.foregroundTintList = ColorStateList.valueOf(defaultBgColor)

        // Reset icons to alpha=1
        denyIcon.alpha = 1f
        confirmIcon.alpha = 1f

    }

    private fun saveDataToCsv(data: List<MotionEventData>, action: String) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "swipe_${action}_$timeStamp.csv"

        val sb = StringBuilder("timestamp,x,y,action\n")
        for (d in data) {
            sb.append("${d.timestamp},${d.x},${d.y},${d.action}\n")
        }

        val dir = getExternalFilesDir(null)
        val outFile = File(dir, filename)
        outFile.writeText(sb.toString())

        Toast.makeText(this, "Data saved to $filename", Toast.LENGTH_SHORT).show()
    }

    // Helper to blend color from startColor to endColor by fraction [0..1]
    private fun blendColors(startColor: Int, endColor: Int, fraction: Float): Int {
        val inv = 1 - fraction
        val r = (Color.red(startColor) * inv + Color.red(endColor) * fraction).toInt()
        val g = (Color.green(startColor) * inv + Color.green(endColor) * fraction).toInt()
        val b = (Color.blue(startColor) * inv + Color.blue(endColor) * fraction).toInt()
        return Color.rgb(r, g, b)
    }
}
