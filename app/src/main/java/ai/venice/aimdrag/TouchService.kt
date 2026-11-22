package ai.venice.aimdrag

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class TouchService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var guide: GuideView? = null
    private var capture: CaptureView? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> apply() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("aimdrag", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        guide?.let { runCatching { wm.removeView(it) } }
        capture?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    private fun fireCenter(): PointF {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        val r = sw * prefs.getFloat("fire_pct", 24f) / 100f / 2f
        return PointF(
            sw - sw * prefs.getFloat("off_x", 1.5f) / 100f - r,
            sh - sh * prefs.getFloat("off_y", 4f) / 100f - r
        )
    }

    private fun apply() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels

        if (prefs.getBoolean("guides", true)) {
            if (guide == null) {
                val v = GuideView(this)
                val lp = WindowManager.LayoutParams(
                    sw, sh,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                runCatching { wm.addView(v, lp) }
                guide = v
            }
            guide?.invalidate()
        } else {
            guide?.let { runCatching { wm.removeView(it) } }
            guide = null
        }

        val wantCapture = prefs.getBoolean("assist", false) && InjectionService.alive
        if (wantCapture) {
            if (capture == null) {
                val v = CaptureView(this)
                val lp = WindowManager.LayoutParams(
                    sw, sh,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                runCatching { wm.addView(v, lp) }
                capture = v
            }
        } else {
            capture?.let { runCatching { wm.removeView(it) } }
            capture = null
        }
    }

    fun shizukuLost() {
        if (capture != null) {
            runCatching { wm.removeView(capture) }
            capture = null
            Toast.makeText(this, "Shizuku отвалился, ассист выключен", Toast.LENGTH_LONG).show()
        }
    }

    private inner class GuideView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val dm = resources.displayMetrics
            val sw = dm.widthPixels.toFloat()
            val r = sw * prefs.getFloat("fire_pct", 24f) / 100f / 2f
            val f = fireCenter()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = Color.argb(150, 239, 68, 68)
            canvas.drawCircle(f.x, f.y, r, p)
            canvas.drawLine(f.x - r * 0.5f, f.y, f.x + r * 0.5f, f.y, p)
            canvas.drawLine(f.x, f.y - r * 0.5f, f.x, f.y + r * 0.5f, p)
            p.style = Paint.Style.FILL
            p.color = Color.argb(200, 255, 255, 255)
            p.textSize = sw * 0.026f
            canvas.drawText("drag", f.x - sw * 0.022f, f.y - r - sw * 0.02f, p)
        }
    }

    private inner class CaptureView(c: Context) : View(c) {
        private val origin = HashMap<Int, PointF>()
        private val s = InjectionService

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val svc = InjectionService.instance
            if (svc == null) return false

            val f = fireCenter()
            val rad = resources.displayMetrics.widthPixels * prefs.getFloat("fire_pct", 24f) / 100f / 2f
            val zone = rad * 1.6f

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    val x = e.getX(i)
                    val y = e.getY(i)
                    if (Math.hypot((x - f.x).toDouble(), (y - f.y).toDouble()) <= zone) {
                        origin[e.getPointerId(i)] = PointF(x, y)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    origin.remove(e.getPointerId(e.actionIndex))
                }
                MotionEvent.ACTION_CANCEL -> origin.clear()
            }
            svc.send(rebuild(e))
            return true
        }

        private fun rebuild(e: MotionEvent): MotionEvent {
            val dm = resources.displayMetrics
            val sw = dm.widthPixels.toFloat()
            val sh = dm.heightPixels.toFloat()
            val mult = prefs.getFloat("mult", 2f)
            val n = e.pointerCount
            val props = arrayOfNulls<MotionEvent.PointerProperties>(n)
            val coords = arrayOfNulls<MotionEvent.PointerCoords>(n)
            for (i in 0 until n) {
                val pp = MotionEvent.PointerProperties()
                e.getPointerProperties(i, pp)
                val pc = MotionEvent.PointerCoords()
                e.getPointerCoords(i, pc)
                origin[e.getPointerId(i)]?.let { o ->
                    pc.x = (o.x + (pc.x - o.x) * mult).coerceIn(1f, sw - 1f)
                    pc.y = (o.y + (pc.y - o.y) * mult).coerceIn(1f, sh - 1f)
                }
                props[i] = pp
                coords[i] = pc
            }
            return MotionEvent.obtain(
                e.downTime, e.eventTime, e.action, n,
                props, coords, e.metaState, e.buttonState,
                1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
        }
    }
}
