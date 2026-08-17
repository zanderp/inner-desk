package dev.zanderp.innerdesk

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DexOverlayService : AccessibilityService() {

    private var overlayView: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var surfaceView: SurfaceView? = null
    private var pointerView: View? = null
    private var arrowView: TextView? = null
    private var menuView: View? = null
    private var tabletopMenuBtn: TextView? = null
    private var settingsView: View? = null
    private var loadingLabel: TextView? = null
    private var scope: CoroutineScope? = null
    private var activeDisplayId: Int = -1
    private var menuOpen = false
    private var applyingSpec = false
    private var pendingResize = false
    private var lastAppliedSpec: String? = null
    private var flexPanelRequested = false
    private var flexPanelTries = 0
    private var flexPanelShowing = false
    private var parkingOverlay = false
    private var watchingOverlay = false
    private var hingeSensor: Sensor? = null
    private var lastHingeTabletop: Boolean? = null
    private var lastHingeAngle: Float? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resizeRunnable = Runnable { resizeDexForScreen() }
    private val keepOverlayRunnable = object : Runnable {
            override fun run() {
                if (!AppSession.dexRunning) return
                restoreOverlayIfStolen()
                mainHandler.postDelayed(this, 1200)
            }
    }
    private val hingeListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent?) {
            val angle = event?.values?.firstOrNull() ?: return
            mainHandler.post { onHingeAngle(angle) }
        }
    }

    private val overlaySettingObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (!AppSession.dexRunning || overlayView == null || applyingSpec) return
            restoreOverlayIfStolen()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (!AppSession.dexRunning || overlayView == null) return
            mainHandler.post { rebindMirror() }
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (!AppSession.dexRunning || overlayView == null) return
            if (displayId == activeDisplayId) {
                AppSession.appendLog("DexOverlay: overlay display $displayId gone, restoring")
                mainHandler.post { restoreOverlayIfStolen() }
            }
        }
        override fun onDisplayChanged(displayId: Int) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (AppSession.dexRunning) maybeDexTextFocus(event)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!AppSession.dexRunning || overlayView == null) return
        val cls = event.className?.toString().orEmpty()
        val pkg = event.packageName?.toString().orEmpty()
        val recents = cls.contains("RecentsActivity", ignoreCase = true) ||
            cls.contains("RecentsView", ignoreCase = true) ||
            (pkg == "com.sec.android.app.launcher" && cls.contains("Recents", ignoreCase = true))
        if (recents) {
            AppSession.appendLog("DexOverlay: hide for Recents")
            hideOverlay()
            return
        }
        val flexPanel = cls.contains("FlexPanelActivity", ignoreCase = true)
        val touchpad = cls.contains("TouchpadActivity", ignoreCase = true) ||
            cls.contains("dextouchpad", ignoreCase = true)
        if (flexPanel) {
            AppSession.appendLog("DexOverlay: Flex Panel appeared, collapsing so DeX overlay display stays alive")
            collapseFlexPanelQuiet()
            restoreOverlayIfStolen()
            relayoutOverlay()
        } else if (touchpad) {
            AppSession.appendLog("DexOverlay: TouchpadActivity stole DeX, restoring overlay display")
            restoreOverlayIfStolen()
        }
    }

    private fun maybeDexTextFocus(event: AccessibilityEvent) {
        if (AppSession.foldLayout?.tabletop != true) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return
        val editable = isEditableEvent(event)
        if (editable) {
            AppSession.onDexTextFocus?.invoke(true)
        } else if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            AppSession.onDexTextFocus?.invoke(false)
        }
    }

    private fun isEditableEvent(event: AccessibilityEvent): Boolean {
        val src = event.source
        if (src != null) {
            return try {
                src.isEditable || src.isPassword
            } finally {
                src.recycle()
            }
        }
        if (event.isPassword) return true
        val cls = event.className?.toString().orEmpty()
        return cls.contains("EditText", true) ||
            cls.contains("AutoComplete", true) ||
            cls.contains("TextField", true) ||
            cls.contains("SearchView", true)
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "onServiceConnected")
        watchHinge()
        AppSession.onMirrorDisconnected = {
            android.os.Handler(mainLooper).post {
                if (activeDisplayId >= 0 && overlayView != null) {
                    AppSession.appendLog("DexOverlay: remirror after binder death")
                    rebindMirror()
                }
            }
        }
    }

    override fun onDestroy() {
        unwatchHinge()
        fullCleanup()
        if (AppSession.onMirrorDisconnected != null) {
            AppSession.onMirrorDisconnected = null
        }
        instance = null
        super.onDestroy()
    }

    fun showFullscreenMirror(displayId: Int) {
        Log.d(TAG, "showFullscreenMirror displayId=$displayId")
        if (overlayView != null) {
            attachDisplay(displayId)
            return
        }
        activeDisplayId = displayId
        menuOpen = false
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        val (screenW, screenH) = physicalSize()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            screenW,
            screenH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            overlayFlags(),
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val sv = SurfaceView(this)
        container.addView(sv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        val pointer = View(this).apply {
            background = getDrawable(R.drawable.cursor_dot)
            visibility = View.GONE
            elevation = dp(12).toFloat()
        }
        container.addView(pointer, FrameLayout.LayoutParams(dp(18), dp(18)))

        val loadingLabel = TextView(this).apply {
            text = "Loading desktop…"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        container.addView(loadingLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER })
        this.loadingLabel = loadingLabel

        val arrow = buildArrow()
        val menu = buildMenu()
        val settings = buildSettings()
        container.addView(arrow, FrameLayout.LayoutParams(dp(28), dp(64)).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        })
        container.addView(menu, FrameLayout.LayoutParams(
            dp(188),
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            marginEnd = dp(36)
        })
        container.addView(settings, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        arrowView = arrow
        menuView = menu
        settingsView = settings

        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val id = activeDisplayId
                Log.d(TAG, "surfaceCreated, starting mirror d=$id")
                AppSession.appendLog("DexOverlay: surface created, starting mirror d=$id")
                if (id >= 0) {
                    startMirror(id, sv)
                    this@DexOverlayService.loadingLabel?.visibility = View.GONE
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val id = activeDisplayId
                if (id >= 0 && width > 0 && height > 0) startMirror(id, sv)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "surfaceDestroyed")
                if (!applyingSpec && !parkingOverlay) {
                    AppSession.overlayMirror.stop()
                }
            }
        })

        sv.setOnTouchListener { v, ev -> OverlayTouch.handle(v, ev, activeDisplayId) }

        wm.addView(container, params)
        overlayView = container
        overlayParams = params
        surfaceView = sv
        pointerView = pointer
        if (displayId >= 0) {
            lastAppliedSpec = "${AppSession.overlayDisplayW}x${AppSession.overlayDisplayH}/${AppSession.overlayDpi}"
            watchOverlayDisplay()
        }
        fitSurfaceView()
        if (AppSession.foldLayout?.tabletop == true) applyImePolicy(true)
        Log.d(TAG, "addView succeeded")
    }

    fun showCover() {
        if (overlayView != null) return
        showFullscreenMirror(-1)
        AppSession.appendLog("DexOverlay: cover up before system overlay")
    }

    fun attachDisplay(displayId: Int) {
        if (displayId < 0) return
        activeDisplayId = displayId
        lastAppliedSpec = "${AppSession.overlayDisplayW}x${AppSession.overlayDisplayH}/${AppSession.overlayDpi}"
        watchOverlayDisplay()
        surfaceView?.let { startMirror(displayId, it) }
        loadingLabel?.visibility = View.GONE
        if (AppSession.foldLayout?.tabletop == true) applyImePolicy(true)
        AppSession.appendLog("DexOverlay: attached display $displayId")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlayView == null || !AppSession.dexRunning) return
        AppSession.appendLog(
            "DexOverlay: config ${newConfig.orientation} ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}",
        )
        scheduleResize()
    }

    private fun buildArrow(): TextView {
        return TextView(this).apply {
            text = "‹"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(12).toFloat(), dp(12).toFloat(), 0f, 0f, 0f, 0f, dp(12).toFloat(), dp(12).toFloat())
                setColor(Color.argb(170, 20, 20, 20))
            }
            setOnClickListener { toggleMenu() }
        }
    }

    private fun buildMenu(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = cardBg()
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        panel.addView(menuButton("Close desktop") { closeDexKeepApp() })
        if (isFoldDevice()) {
            val btn = menuButton(tabletopMenuLabel()) { toggleForcedTabletop() }
            tabletopMenuBtn = btn
            panel.addView(btn)
        }
        panel.addView(menuButton("Desktop settings") {
            hideMenu()
            showSettingsUi()
        })
        return panel
    }

    private fun buildSettings(): FrameLayout {
        val root = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { /* keep taps inside settings */ }
        }

        val dpiLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val resLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        }

        val dpiBar = SeekBar(this).apply {
            max = 400
            rotation = -90f
        }
        val resBar = SeekBar(this).apply {
            max = 50
        }

        fun refreshLabels() {
            val dpi = 160 + dpiBar.progress
            val scale = 50 + resBar.progress
            val (tw, th) = OverlayCanvas.overlayWindowSize(this)
            val w = ((tw * scale) / 100 / 2) * 2
            val h = ((th * scale) / 100 / 2) * 2
            dpiLabel.text = "DPI\n$dpi"
            resLabel.text = if (scale < 100) {
                "Resolution $scale%  ${w}×${h}  →  ${tw}×${th}"
            } else {
                "Resolution $scale%  ${w}×${h}"
            }
        }

        val applyFromSliders = {
            OverlayCanvas.save(this, 50 + resBar.progress, 160 + dpiBar.progress)
            applyFromPrefs()
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                applyFromSliders()
            }
        }
        dpiBar.setOnSeekBarChangeListener(listener)
        resBar.setOnSeekBarChangeListener(listener)

        dpiBar.progress = (OverlayCanvas.dpiPref(this) - 160).coerceIn(0, 400)
        resBar.progress = (OverlayCanvas.scalePercent(this) - 50).coerceIn(0, 50)
        refreshLabels()

        val done = menuButton("Done") {
            hideSettingsUi()
        }

        val dpiWrap = FrameLayout(this)
        dpiWrap.addView(dpiBar, FrameLayout.LayoutParams(dp(220), dp(36)).apply {
            gravity = Gravity.CENTER
        })
        dpiWrap.addView(dpiLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })

        root.addView(dpiWrap, FrameLayout.LayoutParams(dp(72), dp(240)).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            marginStart = dp(12)
        })
        root.addView(resLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(64)
            marginStart = dp(24)
            marginEnd = dp(24)
        })
        root.addView(resBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(28)
            marginStart = dp(24)
            marginEnd = dp(24)
        })
        root.addView(done, FrameLayout.LayoutParams(dp(120), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(18)
            marginEnd = dp(18)
        })
        return root
    }

    private fun tabletopMenuLabel(): String =
        if (AppSession.foldLayout?.tabletop == true) "Exit tabletop" else "Tabletop"

    private fun isFoldDevice(): Boolean {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)) return true
        return getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null
    }

    private fun toggleForcedTabletop() {
        val enable = AppSession.foldLayout?.tabletop != true
        AppSession.tabletopForced = enable
        lastHingeTabletop = enable
        applyTabletop(enable, if (enable) "menu" else "menu off")
        tabletopMenuBtn?.text = tabletopMenuLabel()
        hideMenu()
    }

    private fun menuButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(40, 255, 255, 255))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun cardBg() = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(210, 18, 18, 18))
    }

    private fun toggleMenu() {
        menuOpen = !menuOpen
        menuView?.visibility = if (menuOpen) View.VISIBLE else View.GONE
        arrowView?.text = if (menuOpen) "›" else "‹"
    }

    private fun hideMenu() {
        menuOpen = false
        menuView?.visibility = View.GONE
        arrowView?.text = "‹"
    }

    private fun closeDexKeepApp() {
        hideMenu()
        hideSettingsUi()
        lastAppliedSpec = null
        AppSession.setDexRunning(this, false)
        unwatchOverlayDisplay()
        hideOverlay()
        HudService.stopQuiet(this)
        CoroutineScope(Dispatchers.IO).launch {
            try { AppSession.shell?.let { DexStarter.clearSession(it) } } catch (_: Exception) {}
            try { AppSession.overlayMirror.release() } catch (_: Exception) {}
            try { AppSession.desktop?.release() } catch (_: Exception) {}
            AppSession.desktop = null
            AppSession.overlayBounds = null
        }
    }

    private fun showSettingsUi() {
        settingsView?.visibility = View.VISIBLE
        arrowView?.visibility = View.GONE
        setOverlayFocusable(true)
    }

    private fun hideSettingsUi() {
        settingsView?.visibility = View.GONE
        arrowView?.visibility = View.VISIBLE
        setOverlayFocusable(false)
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        params.flags = if (focusable) {
            overlayFlags() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            overlayFlags()
        }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    private fun scheduleResize() {
        mainHandler.removeCallbacks(resizeRunnable)
        mainHandler.postDelayed(resizeRunnable, 280)
    }

    private fun physicalSize(): Pair<Int, Int> = OverlayCanvas.overlayWindowSize(this)

    private fun overlayFlags(): Int {
        val tabletop = AppSession.foldLayout?.tabletop == true
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (!tabletop) {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return flags
    }

    private fun relayoutOverlay() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val (w, h) = physicalSize()
        params.width = w
        params.height = h
        params.x = 0
        params.y = 0
        params.flags = overlayFlags()
        params.gravity = Gravity.TOP or Gravity.START
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
            fitSurfaceView()
            syncPointerVisibility()
            AppSession.appendLog("DexOverlay: window ${w}x$h tabletop=${AppSession.foldLayout?.tabletop == true}")
        } catch (e: Exception) {
            AppSession.appendLog("DexOverlay: relayout failed: ${e.message}")
        }
    }

    private fun fitSurfaceView() {
        val sv = surfaceView ?: return
        val lp = sv.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.FILL
        lp.leftMargin = 0
        lp.topMargin = 0
        sv.layoutParams = lp
    }

    private fun syncPointerVisibility() {
        val tabletop = AppSession.foldLayout?.tabletop == true
        pointerView?.visibility = if (tabletop && AppSession.dexRunning) View.VISIBLE else View.GONE
    }

    fun movePointer(canvasX: Float, canvasY: Float) {
        val pv = pointerView ?: return
        val sv = surfaceView ?: overlayView ?: return
        val w = sv.width.coerceAtLeast(1)
        val h = sv.height.coerceAtLeast(1)
        val cw = AppSession.overlayDisplayW.coerceAtLeast(1)
        val ch = AppSession.overlayDisplayH.coerceAtLeast(1)
        pv.translationX = sv.x + canvasX * w / cw - pv.width / 2f
        pv.translationY = sv.y + canvasY * h / ch - pv.height / 2f
        if (AppSession.foldLayout?.tabletop == true) pv.visibility = View.VISIBLE
    }

    fun scheduleImeCheck() {
        mainHandler.removeCallbacks(imeCheckRunnable)
        mainHandler.postDelayed(imeCheckRunnable, 180)
    }

    private val imeCheckRunnable = Runnable {
        if (!AppSession.dexRunning || AppSession.foldLayout?.tabletop != true) return@Runnable
        if (hasEditableFocus()) AppSession.onDexTextFocus?.invoke(true)
    }

    private fun hasEditableFocus(): Boolean {
        val wins = try { windows } catch (_: Exception) { null } ?: return false
        for (win in wins) {
            val root = try { win.root } catch (_: Exception) { null } ?: continue
            try {
                val focus = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
                if (focus != null) {
                    try {
                        if (focus.isEditable || focus.isPassword) return true
                    } finally {
                        try { focus.recycle() } catch (_: Exception) {}
                    }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
        return false
    }

    private fun resizeDexForScreen() {
        if (overlayView == null || !AppSession.dexRunning) return
        if (applyingSpec) {
            pendingResize = true
            return
        }
        val (w, h) = OverlayCanvas.overlayWindowSize(this)
        val shrinking = (overlayParams?.height ?: 0) > h + 24
        val spec = AppSession.applyOverlaySpec(this, w, h)
        if (spec == lastAppliedSpec) {
            relayoutOverlay()
            surfaceView?.let { sv ->
                if (activeDisplayId >= 0) startMirror(activeDisplayId, sv)
            }
            return
        }
        applyOverlaySpec(spec, relayoutAfter = shrinking)
    }

    private fun applyFromPrefs() {
        if (overlayView == null || !AppSession.dexRunning) return
        if (applyingSpec) {
            pendingResize = true
            return
        }
        val (w, h) = OverlayCanvas.overlayWindowSize(this)
        val shrinking = (overlayParams?.height ?: 0) > h + 24
        applyOverlaySpec(AppSession.applyOverlaySpec(this, w, h), relayoutAfter = shrinking)
    }

    private fun applyOverlaySpec(spec: String, relayoutAfter: Boolean = false) {
        val shell = AppSession.shell ?: return
        applyingSpec = true
        lastAppliedSpec = spec
        if (!relayoutAfter) relayoutOverlay()
        val jobScope = scope ?: CoroutineScope(Dispatchers.Main + SupervisorJob()).also { scope = it }
        jobScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    DexStarter.startAsExternalMonitor(shell, spec)
                }
                var id: Int? = null
                for (i in 0 until 20) {
                    id = DexStarter.overlayDisplayId(
                        this@DexOverlayService,
                        AppSession.overlayDisplayW,
                        AppSession.overlayDisplayH,
                    )
                    if (id != null) break
                    delay(100)
                }
                if (id != null) {
                    val found = id
                    activeDisplayId = found
                    if (relayoutAfter) relayoutOverlay()
                    surfaceView?.let { startMirror(found, it) }
                    applyImePolicy(AppSession.foldLayout?.tabletop == true)
                    AppSession.appendLog("DexOverlay: spec $spec d=$found")
                    logSystemOverlayWindow()
                } else {
                    if (relayoutAfter) relayoutOverlay()
                    AppSession.appendLog("DexOverlay: spec $spec but overlay display missing")
                }
            } catch (e: Exception) {
                if (relayoutAfter) relayoutOverlay()
                AppSession.appendLog("DexOverlay: spec failed: ${e.message}")
            } finally {
                applyingSpec = false
                if (pendingResize) {
                    pendingResize = false
                    scheduleResize()
                }
            }
        }
    }

    private fun logSystemOverlayWindow() {
        val wins = try { windows } catch (_: Exception) { return }
        for (win in wins) {
            val title = try { win.title?.toString().orEmpty() } catch (_: Exception) { "" }
            if (!title.contains("Overlay #")) continue
            val bounds = android.graphics.Rect()
            try { win.getBoundsInScreen(bounds) } catch (_: Exception) { continue }
            AppSession.appendLog("DexOverlay: system overlay '$title' bounds=$bounds canvas=${overlayParams?.width}x${overlayParams?.height}")
        }
    }

    private fun watchOverlayDisplay() {
        if (!watchingOverlay) {
            watchingOverlay = true
            try {
                contentResolver.registerContentObserver(
                    Settings.Global.getUriFor("overlay_display_devices"),
                    false,
                    overlaySettingObserver,
                )
            } catch (_: Exception) {}
            try {
                getSystemService(DisplayManager::class.java)
                    .registerDisplayListener(displayListener, mainHandler)
            } catch (_: Exception) {}
        }
        mainHandler.removeCallbacks(keepOverlayRunnable)
        mainHandler.post(keepOverlayRunnable)
    }

    private fun unwatchOverlayDisplay() {
        watchingOverlay = false
        mainHandler.removeCallbacks(keepOverlayRunnable)
        try { contentResolver.unregisterContentObserver(overlaySettingObserver) } catch (_: Exception) {}
        try {
            getSystemService(DisplayManager::class.java)
                .unregisterDisplayListener(displayListener)
        } catch (_: Exception) {}
    }

    private fun restoreOverlayIfStolen() {
        if (!AppSession.dexRunning || applyingSpec) return
        val want = lastAppliedSpec ?: return
        val current = try {
            Settings.Global.getString(contentResolver, "overlay_display_devices")
        } catch (_: Exception) {
            null
        }
        if (current == want) return
        AppSession.appendLog("DexOverlay: overlay setting was '$current', restoring $want")
        applyOverlaySpec(want)
    }

    private fun watchHinge() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) ?: return
        hingeSensor = sensor
        sm.registerListener(hingeListener, sensor, SensorManager.SENSOR_DELAY_UI)
        AppSession.appendLog("DexOverlay: hinge sensor watching")
    }

    private fun unwatchHinge() {
        try {
            getSystemService(SensorManager::class.java)?.unregisterListener(hingeListener)
        } catch (_: Exception) {}
        hingeSensor = null
        lastHingeTabletop = null
        lastHingeAngle = null
    }

    private fun onHingeAngle(angle: Float) {
        AppSession.hingeTracked = true
        lastHingeAngle = angle
        val was = lastHingeTabletop ?: (AppSession.foldLayout?.tabletop == true)
        val natural = tabletopFromAngle(angle, was)
        if (natural) AppSession.tabletopForced = false
        val tabletop = natural || AppSession.tabletopForced
        if (tabletop == lastHingeTabletop) return
        lastHingeTabletop = tabletop
        applyTabletop(tabletop, "hinge ${angle.toInt()}°")
    }

    private fun tabletopFromAngle(angle: Float, was: Boolean): Boolean = when {
        was && angle in 40f..155f -> true
        !was && angle in 55f..140f -> true
        else -> false
    }

    private fun clearForcedTabletopIfFlat() {
        AppSession.tabletopForced = false
        val stillBent = lastHingeAngle?.let { it in 40f..155f } == true
        if (stillBent || AppSession.foldLayout?.tabletop != true) return
        val screen = OverlayCanvas.screen(this)
        AppSession.foldLayout = AppSession.FoldLayout(
            tabletop = false,
            topHeight = screen.height,
            width = screen.width,
            height = screen.height,
        )
        lastHingeTabletop = false
        AppSession.onTabletopChanged?.invoke(false)
    }

    private fun applyTabletop(tabletop: Boolean, reason: String) {
        val screen = OverlayCanvas.screen(this)
        AppSession.foldLayout = AppSession.FoldLayout(
            tabletop = tabletop,
            topHeight = if (tabletop) screen.height / 2 else screen.height,
            width = screen.width,
            height = screen.height,
        )
        AppSession.appendLog("DexOverlay: $reason → tabletop=$tabletop window=${OverlayCanvas.overlayWindowSize(this)}")
        tabletopMenuBtn?.text = tabletopMenuLabel()
        applyImePolicy(tabletop)
        AppSession.onTabletopChanged?.invoke(tabletop)
        if (!AppSession.dexRunning || overlayView == null) return
        scheduleResize()
        syncPointerVisibility()
        collapseFlexPanelQuiet()
    }

    private fun applyImePolicy(tabletop: Boolean) {
        val id = activeDisplayId
        if (id < 0) return
        // 1 = DISPLAY_IME_POLICY_FALLBACK_DISPLAY (IME on the inner screen)
        // 0 = DISPLAY_IME_POLICY_LOCAL (IME on DeX)
        val policy = if (tabletop) 1 else 0
        try {
            val out = AppSession.overlayMirror.getMirrorService()?.setDisplayImePolicy(id, policy)
            AppSession.appendLog("DexOverlay: ime policy d=$id tabletop=$tabletop → $out")
        } catch (e: Exception) {
            AppSession.appendLog("DexOverlay: ime policy failed: ${e.message}")
        }
    }

    private fun collapseFlexPanelQuiet() {
        if (!AppSession.dexRunning) return
        val jobScope = scope ?: CoroutineScope(Dispatchers.Main + SupervisorJob()).also { scope = it }
        jobScope.launch(Dispatchers.IO) {
            try {
                AppSession.shell?.let { DexStarter.collapseFlexPanel(it) }
            } catch (_: Exception) {}
        }
    }

    fun parkOverlay() {
        parkingOverlay = true
        overlayView?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        overlayParams = null
        surfaceView = null
        pointerView = null
        arrowView = null
        menuView = null
        tabletopMenuBtn = null
        settingsView = null
        loadingLabel = null
        parkingOverlay = false
        AppSession.appendLog("DexOverlay: parked for tabletop")
    }

    fun hideOverlay() {
        clearForcedTabletopIfFlat()
        unwatchOverlayDisplay()
        flexPanelRequested = false
        flexPanelTries = 0
        flexPanelShowing = false
        mainHandler.removeCallbacks(resizeRunnable)
        mainHandler.removeCallbacks(imeCheckRunnable)
        pendingResize = false
        lastAppliedSpec = null
        applyingSpec = false
        scope?.cancel()
        scope = null
        AppSession.overlayMirror.stop()
        overlayView?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        overlayParams = null
        surfaceView = null
        pointerView = null
        arrowView = null
        menuView = null
        tabletopMenuBtn = null
        settingsView = null
        loadingLabel = null
    }

    private fun fullCleanup() {
        lastAppliedSpec = null
        AppSession.setDexRunning(this, false)
        unwatchOverlayDisplay()
        hideOverlay()
        HudService.stopQuiet(this)
        try {
            DaemonShell.runQuiet("settings put global overlay_display_devices none")
            DaemonShell.runQuiet("settings delete global overlay_display_devices")
        } catch (_: Exception) {}
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppSession.shell?.let { DexStarter.clearSession(it) }
            } catch (_: Exception) {}
        }
    }

    fun rebindMirror() {
        val sv = surfaceView ?: return
        if (activeDisplayId >= 0) startMirror(activeDisplayId, sv)
    }

    private fun startMirror(displayId: Int, sv: SurfaceView) {
        if (displayId < 0) return
        val result = AppSession.overlayMirror.start(displayId, sv)
        AppSession.appendLog("DexOverlay: mirror result=$result")
        if (result.contains("waiting") || result.contains("svc null")) {
            scope?.launch {
                repeat(15) {
                    delay(400)
                    if (AppSession.overlayMirror.pingAlive()) {
                        AppSession.appendLog("DexOverlay: priv binder ready, remirror")
                        AppSession.overlayMirror.start(displayId, sv)
                        return@launch
                    }
                }
            }
        }
    }

    private var pointerDown = false

    private fun handleTouch(v: View, ev: MotionEvent, displayId: Int): Boolean {
        if (displayId < 0) return true
        if (menuOpen || settingsView?.visibility == View.VISIBLE) return true
        val tw = AppSession.overlayDisplayW.takeIf { it > 0 } ?: v.width
        val th = AppSession.overlayDisplayH.takeIf { it > 0 } ?: v.height
        val x = ev.x * tw / v.width.coerceAtLeast(1)
        val y = ev.y * th / v.height.coerceAtLeast(1)
        val action = ev.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val svc = AppSession.overlayMirror.getMirrorService()
                if (svc == null) {
                    pointerDown = false
                    return true
                }
                try {
                    svc.injectPointer(displayId, action, x, y, ev.downTime, ev.eventTime)
                    pointerDown = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                } catch (_: Exception) {
                    pointerDown = false
                }
            }
        }
        if (action == MotionEvent.ACTION_UP) v.performClick()
        return true
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val TAG = "DexOverlayService"

        @Volatile
        var instance: DexOverlayService? = null
            private set

        fun isRunning() = instance != null

        fun hasOverlay() = instance?.overlayView != null

        fun activeDisplay(): Int? {
            val id = instance?.activeDisplayId ?: return null
            return id.takeIf { it >= 0 }
        }

        fun movePointer(canvasX: Float, canvasY: Float) {
            instance?.movePointer(canvasX, canvasY)
        }

        fun scheduleImeCheck() {
            instance?.scheduleImeCheck()
        }

        fun showSettingsPanel() {
            val svc = instance ?: return
            svc.hideMenu()
            svc.showSettingsUi()
        }

        fun showCover() {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "showCover() called but instance is null")
                AppSession.appendLog("DexOverlay: service instance is NULL")
                return
            }
            try {
                svc.showCover()
            } catch (e: Exception) {
                Log.e(TAG, "showCover() failed", e)
                AppSession.appendLog("DexOverlay: cover FAILED: ${e.message}")
            }
        }

        fun show(displayId: Int) {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "show() called but instance is null")
                AppSession.appendLog("DexOverlay: service instance is NULL")
                return
            }
            try {
                if (svc.overlayView != null) {
                    svc.attachDisplay(displayId)
                    AppSession.appendLog("DexOverlay: overlay reused OK")
                } else {
                    svc.showFullscreenMirror(displayId)
                    AppSession.appendLog("DexOverlay: overlay added OK")
                }
            } catch (e: Exception) {
                Log.e(TAG, "show() failed", e)
                AppSession.appendLog("DexOverlay: show FAILED: ${e.message}")
            }
        }

        fun rebindMirror() {
            instance?.rebindMirror()
        }

        fun hide() {
            instance?.hideOverlay()
        }

        fun resizeForOrientation() {
            instance?.scheduleResize()
        }

        fun applyCurrentPrefs() {
            instance?.applyFromPrefs()
        }
    }
}
