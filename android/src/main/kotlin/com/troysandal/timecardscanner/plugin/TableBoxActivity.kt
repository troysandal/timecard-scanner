package com.troysandal.timecardscanner.plugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.troysandal.timecardscanner.BoxDrag
import com.troysandal.timecardscanner.ScanRegion
import java.io.File
import kotlin.math.hypot

/**
 * Full-screen "drag the box over the checkpoint rows" step shown after the document scanner, so the saved photo can
 * be the whole card while only the table is read. The Android counterpart of iOS `TableBoxViewController`.
 * Input: the photo's path and the starting box (fractions). Result: RESULT_OK with the confirmed box in the extras
 * `x`, `y`, `width`, `height` (fractions of the upright photo), or RESULT_CANCELED.
 */
class TableBoxActivity : Activity() {
    private lateinit var boxView: BoxView
    private var bitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val photo = path?.let { ImageScanner.decodeUpright(File(it), DISPLAY_SIDE) }
        if (photo == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        bitmap = photo
        val start = if (savedInstanceState?.containsKey("x") == true) {
            BoxDrag.Frac(savedInstanceState.getDouble("x"), savedInstanceState.getDouble("y"), savedInstanceState.getDouble("width"), savedInstanceState.getDouble("height"))
        } else {
            BoxDrag.Frac(intent.getDoubleExtra("x", 0.04), intent.getDoubleExtra("y", 0.2), intent.getDoubleExtra("width", 0.92), intent.getDoubleExtra("height", 0.62))
        }
        boxView = BoxView(this, photo, start)

        val title = TextView(this).apply {
            text = getString(intent.getIntExtra(EXTRA_TITLE, R.string.timecard_scanner_box_title))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val cancel = Button(this).apply {
            text = getString(R.string.timecard_scanner_box_cancel)
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }
        val read = Button(this).apply {
            text = getString(intent.getIntExtra(EXTRA_CONFIRM, R.string.timecard_scanner_box_read))
            setOnClickListener { confirm() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(context), LinearLayout.LayoutParams(dp(16), 1))
            addView(read, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true // keep clear of the status and navigation bars
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(boxView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::boxView.isInitialized) return
        boxView.box.let { outState.putDouble("x", it.x); outState.putDouble("y", it.y); outState.putDouble("width", it.width); outState.putDouble("height", it.height) }
    }

    override fun onDestroy() {
        super.onDestroy()
        bitmap?.recycle()
        bitmap = null
    }

    private fun confirm() {
        val b = boxView.box
        val region = ScanRegion.of(b.x, b.y, b.width, b.height) ?: return
        setResult(RESULT_OK, Intent().putExtra("x", region.x).putExtra("y", region.y).putExtra("width", region.width).putExtra("height", region.height))
        finish()
    }

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PATH = "path"
        /** String resource ids for the title and the confirm button; the defaults are the "read the table" wording. */
        const val EXTRA_TITLE = "titleRes"
        const val EXTRA_CONFIRM = "confirmRes"

        /** Longest side the photo is decoded at for display; plenty for a phone screen. */
        private const val DISPLAY_SIDE = 2048

        fun intent(context: Context, photo: File, start: ScanRegion, titleRes: Int = R.string.timecard_scanner_box_title, confirmRes: Int = R.string.timecard_scanner_box_read): Intent =
            Intent(context, TableBoxActivity::class.java)
                .putExtra(EXTRA_PATH, photo.path)
                .putExtra(EXTRA_TITLE, titleRes).putExtra(EXTRA_CONFIRM, confirmRes)
                .putExtra("x", start.x).putExtra("y", start.y).putExtra("width", start.width).putExtra("height", start.height)
    }
}

/**
 * Draws the photo (aspect fit), the dimmed surround and the box with its four corner handles, and turns touches into
 * a new box through [BoxDrag]. A drag from near a corner resizes, a drag from inside moves.
 */
private class BoxView(context: Context, private val photo: Bitmap, start: BoxDrag.Frac) : View(context) {
    var box: BoxDrag.Frac = start
        private set

    private val density = context.resources.displayMetrics.density
    private val grab = 44 * density
    private val handleRadius = 10 * density
    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 2 * density }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var mode = -2 // -2 none, -1 move, 0..3 a corner
    private var downX = 0f
    private var downY = 0f
    private var startBox = start

    private fun photoRect(): RectF {
        val scale = minOf(width.toFloat() / photo.width, height.toFloat() / photo.height)
        val w = photo.width * scale
        val h = photo.height * scale
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun boxRect(p: RectF = photoRect()) = RectF(
        p.left + (box.x * p.width()).toFloat(), p.top + (box.y * p.height()).toFloat(),
        p.left + (box.right * p.width()).toFloat(), p.top + (box.bottom * p.height()).toFloat(),
    )

    private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom)

    override fun onDraw(canvas: Canvas) {
        val p = photoRect()
        canvas.drawBitmap(photo, null, p, bitmapPaint)
        val r = boxRect(p)
        val outside = Path().apply { fillType = Path.FillType.EVEN_ODD; addRect(p, Path.Direction.CW); addRect(r, Path.Direction.CW) }
        canvas.drawPath(outside, dim)
        canvas.drawRect(r, stroke)
        corners(r).forEach { (x, y) -> canvas.drawCircle(x, y, handleRadius, fill) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = photoRect()
        if (p.width() <= 0f || p.height() <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = boxRect(p)
                val nearest = corners(r).withIndex().minByOrNull { (_, c) -> hypot(c.first - e.x, c.second - e.y) }!!
                mode = when {
                    hypot(nearest.value.first - e.x, nearest.value.second - e.y) <= grab -> nearest.index
                    r.contains(e.x, e.y) -> -1
                    else -> -2
                }
                downX = e.x; downY = e.y; startBox = box
                parent?.requestDisallowInterceptTouchEvent(true)
                return mode != -2
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == -2) return false
                val dx = (e.x - downX) / p.width().toDouble()
                val dy = (e.y - downY) / p.height().toDouble()
                box = if (mode == -1) BoxDrag.move(startBox, dx, dy) else BoxDrag.resize(startBox, mode, dx, dy)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = -2
        }
        return true
    }
}
