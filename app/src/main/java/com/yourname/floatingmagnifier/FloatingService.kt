package com.yourname.floatingmagnifier

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingService : Service() {

    companion object {
        const val TAG = "FloatingService"
        const val CHANNEL_ID = "magnifier_channel"
        const val NOTIF_ID = 1
        const val RECONNECT_NOTIF_ID = 2
        const val ACTION_CHANGE_RATIO = "com.yourname.floatingmagnifier.ACTION_CHANGE_RATIO"
        const val ACTION_CHANGE_ZOOM = "com.yourname.floatingmagnifier.ACTION_CHANGE_ZOOM"
        // 서비스가 현재 실행 중인지 (중복 시작 방지용)
        @Volatile var isRunning = false
    }

    private lateinit var windowManager: WindowManager

    private var aimView: View? = null
    private var aimDot: View? = null
    private var deleteZoneView: View? = null       // 하단 X(앱 종료) 영역 오버레이
    private var deleteZoneParams: WindowManager.LayoutParams? = null
    private lateinit var aimParams: WindowManager.LayoutParams

    private var viewerView: ImageView? = null
    private var viewerParams: WindowManager.LayoutParams? = null

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var zoom = 2.5f
    private val VIEWER_BASE_DP = 180   // 확대창 기준 긴 변 길이
    private val IDLE_DP = 56
    private var viewerW = 0
    private var viewerH = 0
    private var aimSizePx = 0
    private var gapPx = 0
    private var density = 1f

    private var aimX = 0
    private var aimY = 400

    private var isActive = false
    private var projectionDead = false   // 화면 잠금 등으로 캡처 세션이 무효화된 상태
    private var isCapturing = false
    private var ignoreNextStop = false

    private val handler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 재시작을 위해 일부러 멈춘 경우엔 서비스를 종료하지 않음
            if (ignoreNextStop) {
                ignoreNextStop = false
                return
            }
            isCapturing = false
            // 화면 잠금 등 시스템이 캡처를 무효화한 경우: 서비스와 조준점은 유지하고
            // '재동의 필요' 상태로 표시 → 사용자가 조준점을 탭하면 다시 동의받아 재개
            handler.post { markProjectionDead() }
        }
    }

    private lateinit var displayManager: DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                handler.post { onRotationChanged() }
            }
        }
    }

    // 화면 꺼짐/켜짐 감지: 꺼지면 캡처 일시중지, 켜지면 캡처 파이프라인 재연결
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 화면이 꺼지는 동안은 캡처해봐야 의미 없는 프레임이라 루프만 멈춤
                    stopCaptureLoop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 화면이 다시 켜지면 캡처 파이프라인(VirtualDisplay)을 새로 생성해 복구
                    handler.postDelayed({ reattachCapture() }, 400)
                }
            }
        }
    }
    // 화면 on/off 이후 캡처 파이프라인을 새로 연결
    // 화면 잠금 시 One UI가 캡처 세션을 무효화하므로, 살아있는 MediaProjection으로 재생성 시도.
    // OS가 재생성을 막으면(SecurityException) 조준점은 유지한 채 '재동의 필요' 상태로 표시.
    private fun reattachCapture() {
        val mp = mediaProjection ?: run { markProjectionDead(); return }
        updateScreenSize()

        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mp.createVirtualDisplay(
                "MagnifierCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            projectionDead = false
        } catch (e: Exception) {
            // 재생성이 차단됨 → 서비스는 유지하고 조준점만 남긴 뒤, 탭 시 재동의 유도
            Log.e(TAG, "reattach blocked: ${e.message}")
            markProjectionDead()
            return
        }

        if (isActive && !isCapturing) {
            startCaptureLoop()
        }
    }

    // 캡처 세션이 죽었음을 표시. 확대창이 열려 있었다면 닫고, 조준점은 그대로 유지.
    private fun markProjectionDead() {
        projectionDead = true
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        if (isActive) {
            isActive = false
            stopCaptureLoop()
            viewerView?.let { if (it.isAttachedToWindow) try { windowManager.removeView(it) } catch (_: Exception) {} }
            viewerView = null
            viewerParams = null
        }
        // 조준점을 눈에 띄게 (탭하면 다시 시작됨을 암시) 100% 불투명으로
        aimDot?.alpha = 1.0f
        handler.post {
            Toast.makeText(this, getString(R.string.toast_screen_off), Toast.LENGTH_LONG).show()
        }
    }

    override fun onBind(intent: Intent?) = null

    private fun updateScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            screenWidth = b.width()
            screenHeight = b.height()
        } else {
            @Suppress("DEPRECATION")
            val d = windowManager.defaultDisplay
            val p = Point()
            @Suppress("DEPRECATION")
            d.getRealSize(p)
            screenWidth = p.x
            screenHeight = p.y
        }
    }

    private fun onRotationChanged() {
        updateScreenSize()

        // VirtualDisplay 크기만 변경 (재생성 금지 - Android 16에서 크래시)
        virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)

        // ImageReader는 크기가 바뀌면 새로 만들어야 함
        imageReader?.close()
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.surface = imageReader?.surface

        // 조준점 위치를 새 화면 범위 안으로 클램프
        aimParams.x = aimParams.x.coerceIn(0, screenWidth - aimParams.width)
        aimParams.y = aimParams.y.coerceIn(0, screenHeight - aimParams.height)
        aimX = aimParams.x; aimY = aimParams.y
        val av = aimView
        if (av != null && av.isAttachedToWindow) {
            windowManager.updateViewLayout(av, aimParams)
        }

        if (isActive) updateViewerPosition()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, handler)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenStateReceiver, screenFilter)
        }

        val m = resources.displayMetrics
        screenDensity = m.densityDpi
        density = m.density
        aimSizePx = (IDLE_DP * m.density).toInt()
        gapPx = (20 * m.density).toInt()
        zoom = Prefs.getZoom(this)
        applyRatio(Prefs.getRatio(this))

        updateScreenSize()
        aimX = screenWidth - aimSizePx
    }

    // 실행 중 비율 변경 즉시 반영
    private fun applyViewerSizeImmediately() {
        val vp = viewerParams ?: return
        val vv = viewerView ?: return
        vp.width = viewerW
        vp.height = viewerH
        if (vv.isAttachedToWindow) {
            windowManager.updateViewLayout(vv, vp)
            updateViewerPosition()
        }
    }

    // 저장된 비율로 확대창 가로/세로 크기 계산 (세로 고정, 가로 변동)
    private fun applyRatio(ratio: String) {
        val (rw, rh) = Prefs.ratioToWH(ratio)
        val base = VIEWER_BASE_DP * density
        // 세로를 base로 고정하고, 가로는 비율에 맞게 늘림
        viewerH = base.toInt()
        viewerW = (base * rw / rh).toInt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        // 비율 변경 요청: 캡처는 그대로 두고 확대창 크기만 즉시 적용
        if (intent?.action == ACTION_CHANGE_RATIO) {
            val ratio = intent.getStringExtra("ratio")
            if (ratio != null) {
                applyRatio(ratio)
                if (isActive) applyViewerSizeImmediately()
            }
            return START_NOT_STICKY
        }

        // 배율 변경 요청: 다음 캡처 프레임부터 새 배율 적용
        if (intent?.action == ACTION_CHANGE_ZOOM) {
            zoom = intent.getFloatExtra("zoom", zoom)
                .coerceIn(Prefs.ZOOM_MIN, Prefs.ZOOM_MAX)
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: run {
            stopSelf(); return START_NOT_STICKY
        }
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        } ?: run { stopSelf(); return START_NOT_STICKY }

        val mp = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 재동의 케이스: 조준점(aimView)이 이미 살아있으면 조준점은 유지하고 캡처만 새로 연결
        if (aimView != null && aimView!!.isAttachedToWindow) {
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            try { imageReader?.close() } catch (_: Exception) {}
            imageReader = null
            try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            try { mediaProjection?.stop() } catch (_: Exception) {}

            mediaProjection = mp.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(projectionCallback, handler)
            setupCapture()
            projectionDead = false
            aimDot?.alpha = 1.0f
            isRunning = true
            handler.post { Toast.makeText(this, getString(R.string.toast_restarted), Toast.LENGTH_SHORT).show() }
            return START_NOT_STICKY
        }

        // 이미 실행 중이면 기존 캡처/뷰를 깨끗이 정리한 뒤 새로 시작 (중복 시작 시 종료되는 문제 방지)
        teardownProjection()

        mediaProjection = mp.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, handler)

        setupCapture()
        setupAimView()
        projectionDead = false
        isRunning = true
        return START_NOT_STICKY
    }

    // 기존 프로젝션/캡처/조준점 정리 (stopSelf 없이)
    private fun teardownProjection() {
        if (isActive) deactivate()
        stopCaptureLoop()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        ignoreNextStop = true
        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        aimView?.let { if (it.isAttachedToWindow) try { windowManager.removeView(it) } catch (_: Exception) {} }
        aimView = null
    }

    private fun setupCapture() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MagnifierCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun setupAimView() {
        aimView = LayoutInflater.from(this).inflate(R.layout.floating_lens, null)
        aimDot = aimView!!.findViewById(R.id.idleButton)

        aimParams = WindowManager.LayoutParams(
            aimSizePx, aimSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = aimX
            y = aimY
        }
        windowManager.addView(aimView, aimParams)

        var initX = 0; var initY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false

        aimView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = aimParams.x; initY = aimParams.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    // 누르는 동안 완전 투명 (활성 여부 관계없이)
                    if (isActive) aimDot?.alpha = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    aimParams.x = (initX + dx).coerceIn(0, screenWidth - aimParams.width)
                    aimParams.y = (initY + dy).coerceIn(0, screenHeight - aimParams.height)
                    aimX = aimParams.x; aimY = aimParams.y
                    val av = aimView
                    if (av != null && av.isAttachedToWindow) {
                        windowManager.updateViewLayout(av, aimParams)
                    }
                    if (isActive) updateViewerPosition()

                    // 대기 상태(비활성)에서 드래그할 때만 하단 X 알약 노출
                    // 확대창이 켜진 상태에서는 읽으며 움직이는 중이라 뜨면 안 됨
                    if (moved && !isActive) {
                        showDeleteZone()
                        highlightDeleteZone(isOverDeleteZone())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 대기 상태에서 X 영역 위에 놓으면 앱 종료 (활성 상태에선 종료 안 함)
                    if (moved && !isActive && isOverDeleteZone()) {
                        hideDeleteZone()
                        stopSelf()
                        return@setOnTouchListener true
                    }
                    hideDeleteZone()
                    // 손 떼면 활성 상태에 맞게 투명도 복구
                    aimDot?.alpha = if (isActive) 0.3f else 1.0f
                    if (!moved) toggle()
                    else if (!isActive) snapToEdge()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggle() {
        // 캡처 세션이 죽은 상태면, 확대창을 여는 대신 재동의 화면을 띄운다
        if (projectionDead || mediaProjection == null) {
            requestReconsent()
            return
        }
        if (isActive) deactivate() else activate()
    }

    // 화면 캡처 권한 재동의를 위해 MainActivity를 재동의 모드로 실행
    private fun requestReconsent() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("auto_restart", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun activate() {
        isActive = true
        aimDot?.alpha = 0.3f   // 활성화 시 투명도 30%
        viewerView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        viewerParams = WindowManager.LayoutParams(
            viewerW, viewerH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // 확대창 위에서 두 손가락 핀치로 배율 조절
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // 벌리면 배율 증가, 오므리면 감소
                zoom = (zoom * detector.scaleFactor).coerceIn(Prefs.ZOOM_MIN, Prefs.ZOOM_MAX)
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // 조절 끝나면 배율 저장 (다음 실행 때도 유지)
                Prefs.setZoom(this@FloatingService, zoom)
                viewerView?.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
        })

        viewerView!!.setOnTouchListener { _, event ->
            // 두 손가락(핀치)일 때만 처리, 한 손가락은 무시(아래 화면 조작 방해 최소화)
            if (event.pointerCount >= 2) {
                scaleDetector.onTouchEvent(event)
                true
            } else {
                false
            }
        }

        windowManager.addView(viewerView, viewerParams)
        updateViewerPosition()
        startCaptureLoop()
    }

    private fun deactivate() {
        isActive = false
        aimDot?.alpha = 1.0f   // 비활성화 시 투명도 복귀
        stopCaptureLoop()
        viewerView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        viewerView = null
        viewerParams = null
        snapToEdge()
    }

    private fun updateViewerPosition() {
        val vp = viewerParams ?: return
        val aimCx = aimParams.x + aimSizePx / 2
        val above = aimParams.y - gapPx - viewerH
        val below = aimParams.y + aimSizePx + gapPx
        val vy = when {
            above >= 0 -> above
            below + viewerH <= screenHeight -> below
            else -> above.coerceAtLeast(0)
        }
        vp.x = (aimCx - viewerW / 2).coerceIn(0, screenWidth - viewerW)
        vp.y = vy
        viewerView?.let {
            if (it.isAttachedToWindow) windowManager.updateViewLayout(it, vp)
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isCapturing) return
            captureAndRender()
            handler.postDelayed(this, 40)
        }
    }

    private fun startCaptureLoop() {
        isCapturing = true
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, 120)
    }

    private fun stopCaptureLoop() {
        isCapturing = false
        handler.removeCallbacks(captureRunnable)
    }

    private fun captureAndRender() {
        val image = try { imageReader?.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bitmapWidth = screenWidth + rowPadding / pixelStride

            val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            val cropW = (viewerW / zoom).toInt().coerceAtLeast(1)
            val cropH = (viewerH / zoom).toInt().coerceAtLeast(1)
            val cx = aimParams.x + aimSizePx / 2
            val cy = aimParams.y + aimSizePx / 2
            // 실제 화면 영역(screenWidth) 기준으로 크롭 위치를 제한해야 함.
            // bitmapWidth에는 정렬용 패딩이 포함돼 있어, 그대로 쓰면 화면 밖 빈 영역(흰색)이 잡힘.
            val maxLeft = (screenWidth - cropW).coerceAtLeast(0)
            val maxTop = (screenHeight - cropH).coerceAtLeast(0)
            val left = (cx - cropW / 2).coerceIn(0, maxLeft)
            val top = (cy - cropH / 2).coerceIn(0, maxTop)

            val cropped = Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
            val scaled = Bitmap.createScaledBitmap(cropped, viewerW, viewerH, true)
            val result = makeRoundedRect(scaled)

            handler.post { viewerView?.setImageBitmap(result) }

            bitmap.recycle(); cropped.recycle(); scaled.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "render error: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun makeRoundedRect(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val radius = minOf(w, h) * 0.12f
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

        // 1) 둥근 사각형 마스크
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawRoundRect(rect, radius, radius, paint)

        // 2) 마스크 안쪽을 캡처 이미지로 채움. 먼저 불투명 흰 배경을 깔아
        //    캡처 이미지의 투명 픽셀 뒤로 실제 화면이 비치는 것을 막는다.
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        // 흰 배경 위에 이미지를 합성한 임시 비트맵을 만들어 통째로 얹기
        val opaque = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val oc = Canvas(opaque)
        oc.drawColor(Color.WHITE)                 // 불투명 배경
        oc.drawBitmap(src, 0f, 0f, null)          // 그 위에 캡처
        canvas.drawBitmap(opaque, 0f, 0f, paint)  // 둥근 마스크로 잘라 얹기
        paint.xfermode = null
        opaque.recycle()

        // 3) 빨간 테두리
        paint.color = Color.parseColor("#EF4444")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), radius, radius, paint)
        return output
    }

    // ===== 하단 X(앱 종료) 알약 =====
    private fun showDeleteZone() {
        if (deleteZoneView != null) return
        val tv = android.widget.TextView(this).apply {
            text = getString(R.string.drag_to_close)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = try {
                androidx.core.content.res.ResourcesCompat.getFont(this@FloatingService, R.font.moneygraphy)
            } catch (_: Exception) { android.graphics.Typeface.DEFAULT_BOLD }
            setBackgroundResource(R.drawable.delete_pill)
            val padH = (24 * density).toInt()
            val padV = (14 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt()   // 하단에서 살짝 띄움
        }
        deleteZoneView = tv
        deleteZoneParams = params
        try { windowManager.addView(tv, params) } catch (_: Exception) {}
    }

    private fun hideDeleteZone() {
        // isAttachedToWindow 조건으로 걸러내면, addView 직후 attach가
        // 아직 안 끝난 타이밍에 hide가 호출될 때 제거가 스킵되어
        // 참조만 null이 되고 뷰는 화면에 그대로 남는 문제가 있었음.
        // 항상 제거를 시도하고, 이미 제거된 경우의 예외만 무시한다.
        deleteZoneView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        deleteZoneView = null
        deleteZoneParams = null
    }

    // 조준점 중심이 실제 종료 알약(버튼) 위에 있는지 판정
    private fun isOverDeleteZone(): Boolean {
        val pill = deleteZoneView ?: return false
        if (!pill.isAttachedToWindow || pill.width == 0) return false

        // 알약의 화면상 실제 위치·크기
        val loc = IntArray(2)
        pill.getLocationOnScreen(loc)
        // 판정 여유(터치 편의를 위해 알약 주변으로 약간 확장)
        val margin = (24 * density).toInt()
        val left = loc[0] - margin
        val top = loc[1] - margin
        val right = loc[0] + pill.width + margin
        val bottom = loc[1] + pill.height + margin

        // 조준점 중심 좌표
        val cx = aimParams.x + aimParams.width / 2
        val cy = aimParams.y + aimParams.height / 2

        return cx in left..right && cy in top..bottom
    }

    // X 영역 위에 있을 때 강조 표시
    private fun highlightDeleteZone(active: Boolean) {
        (deleteZoneView as? android.widget.TextView)?.apply {
            if (active) {
                setBackgroundResource(R.drawable.delete_pill_active)
                text = getString(R.string.release_to_close)
                val padH = (24 * density).toInt(); val padV = (14 * density).toInt()
                setPadding(padH, padV, padH, padV)
            } else {
                setBackgroundResource(R.drawable.delete_pill)
                text = getString(R.string.drag_to_close)
                val padH = (24 * density).toInt(); val padV = (14 * density).toInt()
                setPadding(padH, padV, padH, padV)
            }
        }
    }

    private fun snapToEdge() {
        val centerX = aimParams.x + aimParams.width / 2
        aimParams.x = if (centerX < screenWidth / 2) 0 else screenWidth - aimParams.width
        aimX = aimParams.x
        val av = aimView
        if (av != null && av.isAttachedToWindow) {
            windowManager.updateViewLayout(av, aimParams)
        }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_zoom)
            .addAction(android.R.drawable.ic_delete, getString(R.string.notif_stop), stop)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        stopCaptureLoop()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        viewerView?.let { if (it.isAttachedToWindow) try { windowManager.removeView(it) } catch (_: Exception) {} }
        aimView?.let { if (it.isAttachedToWindow) try { windowManager.removeView(it) } catch (_: Exception) {} }
        hideDeleteZone()
    }
}
