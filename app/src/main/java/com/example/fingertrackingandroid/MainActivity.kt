package com.example.fingertrackingandroid

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.slider.Slider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    // slider / UI
    private lateinit var slider: Slider
    private lateinit var sendMoneyText: TextView
    private lateinit var denyIcon: ImageView
    private lateinit var confirmIcon: ImageView

    // circle for "finger" demo
    private lateinit var fingerView: View

    // circle overlay for "hold" animation
    private lateinit var holdOverlayView: View

    // region detection
    private val motionData = mutableListOf<MotionEventData>()
    private val holdHandler = Handler(Looper.getMainLooper())
    private var holdRunnable: Runnable? = null
    private var pendingRegion: String? = null
    private var hasAccepted = false

    // Animators
    private var demoAnimator: ValueAnimator? = null

    // HOLD-ANIMATION:
    private var holdAnimator: ValueAnimator? = null
    private var isDemoRunning = false
    private var demoInterupted = false

    // constants
    private val defaultBgColor by lazy { resources.getColor(R.color.gray_light, theme) }

    // times
    private val holdTime = 3000L            // Must hold for 5s
    private val initialDemoDelay = 1000L    // Wait before first animation
    private val betweenSwipesDelay = 600L
    private val betweenCyclesDelay = 3000L
    private val resumeDelay = 6000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        actionBar?.hide()

        // find views
        slider = findViewById(R.id.swipeSlider)
        sendMoneyText = findViewById(R.id.sendMoneyText)
        denyIcon = findViewById(R.id.denyIcon)
        confirmIcon = findViewById(R.id.confirmIcon)
        fingerView = findViewById(R.id.fingerView)
        holdOverlayView = findViewById(R.id.holdOverlayView)

        slider.value = 50f

        // region: slider listeners
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                interruptDemoAnimation()

                motionData.clear()
                hasAccepted = false
                pendingRegion = null
                clearHoldRunnable()
                // HOLD-ANIMATION: If user touches, ensure we reset hold animation if it was running
                stopHoldAnimation()
            }

            override fun onStopTrackingTouch(slider: Slider) {
                if (!hasAccepted) {
                    // They lifted early without confirming => stop the hold
                    stopHoldAnimation()
                    clearHoldRunnable()
                    pendingRegion = null

                    resetSlider()

                    // Resume the demo if desired
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!hasAccepted) {
                            startDemoAnimation()
                        }
                    }, resumeDelay)
                }
            }
        })

        slider.addOnChangeListener { _, value, fromUser ->
            if (hasAccepted) return@addOnChangeListener

            val fraction = (value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)
            val currentColor = blendColors(Color.RED, Color.GREEN, fraction)
            slider.foregroundTintList = ColorStateList.valueOf(currentColor)

            // icon crossfade
            denyIcon.alpha = 1f - fraction
            confirmIcon.alpha = fraction

            // region detection only if fromUser = true
            if (fromUser) {
                when {
                    value <= 25 -> {
                        if (pendingRegion != "cancel") {
                            pendingRegion = "cancel"
                            positionHoldOverlayAtIcon(denyIcon, "red")
                            scheduleHoldCheck("cancel", holdTime)
                        }
                    }
                    value >= 75 -> {
                        if (pendingRegion != "confirm") {
                            pendingRegion = "confirm"
                            positionHoldOverlayAtIcon(confirmIcon, "green")
                            scheduleHoldCheck("confirm", holdTime)
                        }
                    }
                    else -> {
                        if (pendingRegion != null) {
                            clearHoldRunnable()
                            pendingRegion = null
                            // HOLD-ANIMATION: user moved out of region => stop overlay animation
                            stopHoldAnimation()
                        }
                    }
                }
            }
        }

        // track touches
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

        // start finger demo after initial delay
        Handler(Looper.getMainLooper()).postDelayed({ startDemoAnimation() }, initialDemoDelay)
    }

    // ---------------------------------
    // Region detection + hold logic
    // ---------------------------------
    private fun scheduleHoldCheck(region: String, delayMs: Long) {
        clearHoldRunnable()
        // Also start the hold animation
        startHoldAnimation()

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

        // The user completed the hold => finalize the overlay with a big "pop"
        finalizeHoldOverlay {
            // After the final expansion completes, do your normal confirm/cancel flow
            if (region == "cancel") {
                Toast.makeText(this, "Cancelled!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Confirmed!", Toast.LENGTH_SHORT).show()
            }
            saveDataToCsv(motionData, region)
            resetSlider()

            demoInterupted = false

            // resume the demo after 1s
            Handler(Looper.getMainLooper()).postDelayed({
                hasAccepted = false
                startDemoAnimation()
            }, resumeDelay)
        }
    }

    // Normal slider reset
    private fun resetSlider() {
        slider.value = 50f
        slider.foregroundTintList = ColorStateList.valueOf(defaultBgColor)
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

    // helper: color blending
    private fun blendColors(startColor: Int, endColor: Int, fraction: Float): Int {
        val inv = 1 - fraction
        val r = (Color.red(startColor) * inv + Color.red(endColor) * fraction).toInt()
        val g = (Color.green(startColor) * inv + Color.green(endColor) * fraction).toInt()
        val b = (Color.blue(startColor) * inv + Color.blue(endColor) * fraction).toInt()
        return Color.rgb(r, g, b)
    }

    // ----------------------------
    // HOLD ANIMATION
    // ----------------------------
    /** startHoldAnimation => scale holdOverlayView from (0..0) to (1..1) over [holdTime] */
    private fun startHoldAnimation() {
        stopHoldAnimation() // ensure we don't have a leftover
        holdOverlayView.visibility = View.VISIBLE
        holdOverlayView.scaleX = 0f
        holdOverlayView.scaleY = 0f

        holdAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = holdTime
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                holdOverlayView.scaleX = fraction
                holdOverlayView.scaleY = fraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    // reset
                    holdOverlayView.visibility = View.GONE
                    holdOverlayView.scaleX = 0f
                    holdOverlayView.scaleY = 0f
                }
                override fun onAnimationEnd(animation: Animator) {
                    // If ended normally => do nothing special here
                    // acceptAction calls finalizeHoldOverlay for the final pop
                }
            })
            start()
        }
    }

    private fun positionHoldOverlayAtIcon(iconView: ImageView, color: String) {
        // Wait until layout is done so we can measure
        holdOverlayView.post {
            // 1) Get icon's center (relative to parent)
            val iconLoc = IntArray(2)
            iconView.getLocationOnScreen(iconLoc)
            val iconCenterX = iconLoc[0] + iconView.width / 2f
            val iconCenterY = iconLoc[1] + iconView.height / 2f

            // 2) Measure holdOverlayView if not measured
            val overlayWidth = holdOverlayView.width
            val overlayHeight = holdOverlayView.height

            // Circle's center is its center if pivot = (width/2, height/2)
            val circleRadiusX = overlayWidth / 2f
            val circleRadiusY = overlayHeight / 2f

            // 3) Move the circle so that the circle center = icon center
            // We'll get our parent's location to convert the screen coords
            val parentLoc = IntArray(2)
            (holdOverlayView.parent as View).getLocationOnScreen(parentLoc)
            val parentX = parentLoc[0]
            val parentY = parentLoc[1]

            // circle's top-left => (iconCenterX - radius) minus parent's left
            holdOverlayView.x = (iconCenterX - circleRadiusX) - parentX
            holdOverlayView.y = (iconCenterY - circleRadiusY) - parentY

            // 4) pivot => center, so the scale expansions happen from center
            holdOverlayView.pivotX = overlayWidth / 2f
            holdOverlayView.pivotY = overlayHeight / 2f

            // 5) Optionally set color
            if (color == "red") {
                holdOverlayView.setBackgroundResource(R.drawable.circle_red)
            } else if (color == "green") {
                holdOverlayView.setBackgroundResource(R.drawable.circle_green)
            } else {
                error("Something went very wrong!")
            }

            holdOverlayView.visibility = View.VISIBLE
            // now at scale = 0, or however you do your hold animation
        }
    }

    /** stopHoldAnimation => cancel the holdAnimator & reset overlay to invisible */
    private fun stopHoldAnimation() {
        holdAnimator?.cancel()
        holdAnimator = null
        holdOverlayView.visibility = View.GONE
        holdOverlayView.scaleX = 0f
        holdOverlayView.scaleY = 0f
    }

    /**
     * finalizeHoldOverlay => called from acceptAction if the user
     * actually held long enough. Grows from scale=1f -> 5f in ~300ms,
     * then calls [onComplete].
     */
    private fun finalizeHoldOverlay(onComplete: () -> Unit) {
        // The holdAnimator might have ended or be null
        stopHoldAnimation()  // Clears old animation, sets scale=0
        // But we want to start from scale=1 for the final pop:
        holdOverlayView.visibility = View.VISIBLE
        holdOverlayView.scaleX = 1f
        holdOverlayView.scaleY = 1f

        ValueAnimator.ofFloat(1f, 5f).apply {
            duration = 300L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                holdOverlayView.scaleX = fraction
                holdOverlayView.scaleY = fraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // reset
                    holdOverlayView.visibility = View.GONE
                    holdOverlayView.scaleX = 0f
                    holdOverlayView.scaleY = 0f
                    onComplete()
                }
            })
            start()
        }
    }

    // ---------------------------------------
    // DEMO FINGER ANIMATION LOGIC
    // ---------------------------------------
    private fun interruptDemoAnimation() {
        demoAnimator?.cancel()
        demoAnimator = null
        isDemoRunning = false
        demoInterupted = true
        liftFinger()
    }

    private fun startDemoAnimation() {
        if (isDemoRunning || hasAccepted || demoInterupted) return

        isDemoRunning = true
        fingerView.visibility = View.VISIBLE

        slideCenterToRight {
            liftFinger()
            Handler(Looper.getMainLooper()).postDelayed({
                if (isDemoRunning || !demoInterupted) {
                slideCenterToLeft {
                    liftFinger()
                    Handler(Looper.getMainLooper()).postDelayed({
                        isDemoRunning = false
                        startDemoAnimation()
                    }, betweenCyclesDelay)
                }
                    }
            }, betweenSwipesDelay)
        }
    }

    private fun slideCenterToRight(onEnd: () -> Unit) {
        animateSliderValue(50f, 91f, 1500L, onEnd)
    }

    private fun slideCenterToLeft(onEnd: () -> Unit) {
        animateSliderValue(50f, 11.75f, 1500L, onEnd)
    }

    private fun animateSliderValue(
        fromValue: Float,
        toValue: Float,
        durationMs: Long,
        onEnd: () -> Unit
    ) {
        fingerView.visibility = View.VISIBLE
        slider.value = fromValue

        val trackWidth = slider.width - slider.paddingLeft - slider.paddingRight
        val safeTrack = max(trackWidth, 0)
        val fromX = safeTrack * (fromValue / 100f)
        val toX = safeTrack * (toValue / 100f)

        demoAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val currentValue = fromValue + fraction * (toValue - fromValue)
                slider.value = currentValue

                val currentX = fromX + fraction * (toX - fromX)
                fingerView.translationX = currentX
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    demoAnimator = null
                    Handler(Looper.getMainLooper()).postDelayed({
                        onEnd()
                    }, 1500L)
                }
                override fun onAnimationCancel(animation: Animator) {
                    // If canceled => do not call onEnd
                }
            })
            start()
        }
    }

    private fun liftFinger() {
        fingerView.visibility = View.GONE
        slider.value = 50f
        slider.foregroundTintList = ColorStateList.valueOf(defaultBgColor)
        denyIcon.alpha = 1f
        confirmIcon.alpha = 1f
    }

    override fun onDestroy() {
        super.onDestroy()
        interruptDemoAnimation()
        stopHoldAnimation()
    }

}
