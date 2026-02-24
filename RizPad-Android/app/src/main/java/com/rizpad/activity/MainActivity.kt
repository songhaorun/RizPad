package com.rizpad.activity

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.rizpad.R
import com.rizpad.network.NetworkManager
import com.rizpad.touch.TouchKeyMapper

/**
 * Full-screen touch pad with slot-based key assignment (Android port of iOS ViewController).
 *
 * - Each new finger gets the lowest available key slot.
 * - Moving the finger does NOT change its key.
 * - Lifting releases the key and frees the slot.
 * - Circle + key label follows the finger.
 * - 60 Hz send loop via Choreographer.
 */
class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {

    // ── Dependencies ──

    private val network = NetworkManager()
    private val mapper = TouchKeyMapper()

    // ── UI references ──

    private lateinit var rootLayout: FrameLayout
    private lateinit var statusLabel: TextView
    private lateinit var ipLabel: TextView
    private lateinit var slotBar: LinearLayout
    private val slotIndicators = mutableListOf<TextView>()

    // ── Touch visuals ──

    /** Pointer ID → (circleView, keyLabel) */
    private val touchVisuals = mutableMapOf<Int, Pair<View, TextView>>()

    // ── Constants ──

    private val circleSizeDp = 90f
    private val circleLabelOffsetDp = -55f

    private val slotColors = intArrayOf(
        0xFFFF3B30.toInt(), // red
        0xFFFF9500.toInt(), // orange
        0xFFFFCC00.toInt(), // yellow
        0xFF34C759.toInt(), // green
        0xFF5AC8FA.toInt(), // teal
        0xFF007AFF.toInt(), // blue
        0xFF5856D6.toInt(), // indigo
        0xFFAF52DE.toInt(), // purple
        0xFFFF2D55.toInt(), // pink
        0xFFA2845E.toInt(), // brown
    )

    // ── Lifecycle ──

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        // Hide system bars AFTER setContentView (some devices crash if called before)
        hideSystemBars()

        rootLayout = findViewById(R.id.root_layout)
        statusLabel = findViewById(R.id.status_label)
        ipLabel = findViewById(R.id.ip_label)
        slotBar = findViewById(R.id.slot_bar)

        // Show device IP
        updateIpLabel()

        // Network callbacks
        network.onConnectionChanged = { connected ->
            runOnUiThread {
                statusLabel.text = if (connected) "已连接" else "未连接"
                statusLabel.setTextColor(if (connected) Color.GREEN else Color.WHITE)
            }
        }
        network.start()

        // Build bottom slot indicators
        buildSlotIndicators()

        // Start 60 Hz send loop
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Choreographer.getInstance().removeFrameCallback(this)
        network.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ── Immersive mode ──

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ── Touch handling ──

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val slot = mapper.assignSlot(id)
                if (slot != null) {
                    addVisuals(id, slot, event.getX(idx), event.getY(idx))
                }
                refreshAllVisuals()
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    moveVisuals(id, event.getX(i), event.getY(i))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                removeVisuals(id)
                mapper.releaseSlot(id)
                refreshAllVisuals()
            }

            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    removeVisuals(id)
                    mapper.releaseSlot(id)
                }
                refreshAllVisuals()
            }
        }
        return true
    }

    // ── Visual circle + key label ──

    private fun addVisuals(pointerId: Int, slot: Int, x: Float, y: Float) {
        val circleSizePx = dpToPx(circleSizeDp)

        // Circle view
        val circle = View(this).apply {
            val size = circleSizePx.toInt()
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = createCircleDrawable(slot)
            elevation = 4f
            translationX = x - circleSizePx / 2
            translationY = y - circleSizePx / 2
        }
        rootLayout.addView(circle)

        // Key label above circle
        val label = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            elevation = 5f
            translationX = x - dpToPx(25f)
            translationY = y + dpToPx(circleLabelOffsetDp)
        }
        rootLayout.addView(label)

        touchVisuals[pointerId] = Pair(circle, label)
    }

    private fun moveVisuals(pointerId: Int, x: Float, y: Float) {
        val (circle, label) = touchVisuals[pointerId] ?: return
        val circleSizePx = dpToPx(circleSizeDp)
        circle.translationX = x - circleSizePx / 2
        circle.translationY = y - circleSizePx / 2
        label.translationX = x - dpToPx(25f)
        label.translationY = y + dpToPx(circleLabelOffsetDp)
    }

    private fun removeVisuals(pointerId: Int) {
        val (circle, label) = touchVisuals.remove(pointerId) ?: return
        rootLayout.removeView(circle)
        rootLayout.removeView(label)
    }

    // ── Refresh all visuals from current state ──

    private fun refreshAllVisuals() {
        // Update circle color + label for every active touch
        for ((pointerId, vis) in touchVisuals) {
            val slot = mapper.slot(pointerId) ?: continue
            val color = slotColors[slot % slotColors.size]
            vis.first.background = createCircleDrawable(slot)
            vis.second.text = mapper.keyLabel(slot).uppercase()
        }

        // Reset all bottom indicators, then light up occupied slots
        for (i in 0 until slotIndicators.size) {
            setSlotIndicatorInactive(i)
        }
        for (slot in mapper.pointerSlots.values) {
            setSlotIndicatorActive(slot)
        }
    }

    // ── Circle drawable ──

    private fun createCircleDrawable(slot: Int): android.graphics.drawable.GradientDrawable {
        val color = slotColors[slot % slotColors.size]
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(adjustAlpha(color, 0.45f))
            setStroke(dpToPx(2f).toInt(), color)
        }
    }

    // ── Choreographer (60 Hz send loop) ──

    override fun doFrame(frameTimeNanos: Long) {
        val pressed = mapper.pressedKeys()
        network.sendKeyState(pressed)
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ── Bottom slot indicators ──

    private fun buildSlotIndicators() {
        slotBar.removeAllViews()
        slotIndicators.clear()

        for (i in 0 until TouchKeyMapper.PRIMARY_SLOT_COUNT) {
            val tv = TextView(this).apply {
                text = mapper.primaryKeyLabel(i).uppercase()
                gravity = Gravity.CENTER
                setTextColor(adjustAlpha(Color.WHITE, 0.3f))
                setBackgroundColor(adjustAlpha(Color.WHITE, 0.05f))
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, dpToPx(8f).toInt(), 0, dpToPx(8f).toInt())
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dpToPx(4f).toInt(), 0, dpToPx(4f).toInt(), 0)
                layoutParams = lp
            }
            slotBar.addView(tv)
            slotIndicators.add(tv)
        }
    }

    private fun setSlotIndicatorActive(slot: Int) {
        if (slot >= slotIndicators.size) return
        val color = slotColors[slot % slotColors.size]
        slotIndicators[slot].apply {
            setBackgroundColor(adjustAlpha(color, 0.5f))
            setTextColor(Color.WHITE)
        }
    }

    private fun setSlotIndicatorInactive(slot: Int) {
        if (slot >= slotIndicators.size) return
        slotIndicators[slot].apply {
            setBackgroundColor(adjustAlpha(Color.WHITE, 0.05f))
            setTextColor(adjustAlpha(Color.WHITE, 0.3f))
        }
    }

    // ── IP address display ──

    @Suppress("DEPRECATION")
    private fun updateIpLabel() {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip != 0) {
                val ipStr = String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff, (ip shr 8) and 0xff,
                    (ip shr 16) and 0xff, (ip shr 24) and 0xff
                )
                ipLabel.text = "$ipStr:${NetworkManager.PORT}"
            } else {
                // Try to get IP from network interfaces
                val ipStr = getLocalIpAddress()
                ipLabel.text = if (ipStr != null) "$ipStr:${NetworkManager.PORT}" else "IP 未知"
            }
        } catch (e: Exception) {
            val ipStr = getLocalIpAddress()
            ipLabel.text = if (ipStr != null) "$ipStr:${NetworkManager.PORT}" else "IP 未知"
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    // ── Utility ──

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
        )
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
