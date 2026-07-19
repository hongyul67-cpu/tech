package com.autoscroll.reader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 화면을 자동으로 천천히 스크롤해주는 접근성 서비스.
 *
 * - 다른 앱(소설 앱, 브라우저 등) 위에 작은 조작 패널을 띄운다.
 * - 스와이프 제스처(dispatchGesture)만 사용해 스크롤하므로 대상 앱의 내용을
 *   읽거나 조작하지 않는다. 순수하게 "손가락으로 밀어 올리는" 동작만 자동화한다.
 */
class AutoScrollAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var panel: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private var scrolling = false
    private var jitter = false

    /** 초당 스크롤 픽셀 수. 클수록 빠르다. */
    private var pxPerSec = 120

    /** true = 읽기 진행(손가락 위로 밀어 아래 내용 표시), false = 거꾸로 위로. */
    private var readForward = true

    private val metrics get() = resources.displayMetrics

    override fun onServiceConnected() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 이벤트를 사용하지 않는다. 제스처만 보낸다.
    }

    override fun onInterrupt() {
        stopScrolling()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopScrolling()
        removePanel()
        return super.onUnbind(intent)
    }

    private fun dp(v: Int) = (v * metrics.density).roundToInt()

    // ---------------------------------------------------------------------
    // 조작 패널 (플로팅 오버레이)
    // ---------------------------------------------------------------------

    private fun showPanel() {
        if (panel != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6202124.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }

        val handle = TextView(this).apply {
            text = "⠿  자동 스크롤"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(handle)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val playBtn = Button(this).apply {
            text = "▶ 시작"
            textSize = 13f
        }
        val dirBtn = Button(this).apply {
            text = "방향 ↑"
            textSize = 13f
        }
        val jitterBtn = Button(this).apply {
            text = "불규칙 OFF"
            textSize = 12f
        }
        row.addView(playBtn)
        row.addView(dirBtn)
        root.addView(row)
        root.addView(jitterBtn)

        val speedLabel = TextView(this).apply {
            setTextColor(0xFFCFCFCF.toInt())
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(speedLabel)

        val seek = SeekBar(this).apply {
            max = 100
            progress = progressForPx(pxPerSec)
            layoutParams = ViewGroup.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(seek)

        fun refreshSpeedLabel() {
            speedLabel.text = "속도: ${pxPerSec} px/s"
        }
        refreshSpeedLabel()

        playBtn.setOnClickListener {
            if (scrolling) {
                stopScrolling()
                playBtn.text = "▶ 시작"
            } else {
                startScrolling()
                playBtn.text = "⏸ 정지"
            }
        }

        dirBtn.setOnClickListener {
            readForward = !readForward
            dirBtn.text = if (readForward) "방향 ↑" else "방향 ↓"
        }

        jitterBtn.setOnClickListener {
            jitter = !jitter
            jitterBtn.text = if (jitter) "불규칙 ON" else "불규칙 OFF"
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                pxPerSec = pxForProgress(p)
                refreshSpeedLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(90)
        }

        enableDrag(handle)

        panel = root
        windowManager.addView(root, layoutParams)
    }

    private fun enableDrag(handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchX = e.rawX
                    touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = startX + (e.rawX - touchX).roundToInt()
                    layoutParams.y = startY + (e.rawY - touchY).roundToInt()
                    panel?.let { windowManager.updateViewLayout(it, layoutParams) }
                    true
                }
                else -> false
            }
        }
    }

    private fun removePanel() {
        panel?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        panel = null
    }

    // 0..100 슬라이더 <-> 40..700 px/s 매핑
    private fun pxForProgress(p: Int) = 40 + (p * 6.6f).roundToInt()
    private fun progressForPx(px: Int) = (((px - 40) / 6.6f).roundToInt()).coerceIn(0, 100)

    // ---------------------------------------------------------------------
    // 스크롤 루프
    // ---------------------------------------------------------------------

    private fun startScrolling() {
        if (scrolling) return
        scrolling = true
        loopStep()
    }

    private fun stopScrolling() {
        scrolling = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun loopStep() {
        if (!scrolling) return
        performScrollStep {
            if (!scrolling) return@performScrollStep
            val gap = if (jitter) Random.nextLong(60, 240) else 100L
            handler.postDelayed({ loopStep() }, gap)
        }
    }

    private fun performScrollStep(onDone: () -> Unit) {
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        val durationMs = 700L
        var distance = pxPerSec * durationMs / 1000f
        if (jitter) distance *= (0.75f + Random.nextFloat() * 0.5f)

        // 좌우 살짝 흔들어 자연스럽게 (불규칙 ON일 때만)
        val jx = if (jitter) Random.nextInt(-dp(20), dp(20)).toFloat() else 0f
        val x = (w * 0.5f + jx).coerceIn(w * 0.15f, w * 0.85f)

        val margin = h * 0.14f
        val startY: Float
        val endY: Float
        if (readForward) {
            // 손가락을 위로 밀어 아래 내용이 보이게 (읽기 진행)
            startY = h - margin
            endY = (startY - distance).coerceAtLeast(margin)
        } else {
            // 반대로 위 내용으로
            startY = margin
            endY = (startY + distance).coerceAtMost(h - margin)
        }

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { onDone() }
            override fun onCancelled(g: GestureDescription?) { onDone() }
        }, null)

        if (!dispatched) {
            // 제스처 접수 실패 시 잠시 뒤 재시도
            handler.postDelayed({ onDone() }, 300)
        }
    }
}
