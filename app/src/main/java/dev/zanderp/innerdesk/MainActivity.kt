package dev.zanderp.innerdesk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import dev.zanderp.innerdesk.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var tabletop = false
    private var lastTabletopDex = false
    private var imeShown = false
    private var surfaceHooked = false
    private var tapLogs = 0
    private val desktop: DesktopDisplay
        get() = AppSession.desktop(this)
    private var cursorX = 80f
    private var cursorY = 80f
    private var pairingHost: String? = null
    private var pairingPort: Int = -1
    private var pairingMdns: io.github.muntashirakon.adb.android.AdbMdns? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var ensuringWireless = false
    private var batteryPrompted = false
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            insetLeft = bars.left
            insetTop = bars.top
            insetRight = bars.right
            insetBottom = bars.bottom
            applyRootInsets()
            insets
        }
        AppSession.shizuku = AppSession.shizuku ?: ShizukuShell()
        if (DexOverlayService.hasOverlay()) {
            AppSession.dexRunning = true
        }
        applyScale()
        if (intent?.action == ACTION_RESET_UI) restoreUi()
        if (intent?.action == ACTION_PAIR) showPairingUi()
        requestOverlayAndNotifs()
        binding.root.post { maybeRequestBatteryExemption() }
        AppSession.onDexRunningChanged = {
            runOnUiThread {
                refreshStatus()
                syncBottomChrome()
            }
        }
        AppSession.onTabletopChanged = { tt ->
            runOnUiThread {
                tabletop = tt
                binding.postureLabel.text = when {
                    tt && AppSession.dexRunning -> "Tabletop: desktop on top. Trackpad below; keyboard opens over the pad."
                    tt -> "Tabletop: desktop will use the upper half; pad on the lower half."
                    else -> "Inner screen flat."
                }
                layoutTabletopPanes()
                syncBottomChrome()
            }
        }
        AppSession.onShizukuChanged = {
            runOnUiThread { refreshStatus() }
        }
        AppSession.onLog = { text ->
            runOnUiThread { renderLog(text) }
        }
        renderLog(AppSession.logText())
        maybeOfferCrashShare()
        initShizuku()
        lifecycleScope.launch {
            val shizukuReady = AppSession.shizuku?.isAvailable() == true
            val privReady = withContext(Dispatchers.IO) { AppSession.overlayMirror.pingAlive() }
            if (shizukuReady) append("Shizuku connected.")
            if (privReady) {
                append("Priv daemon connected.")
                ShizukuKeepAlive.refreshKeepAlive(this@MainActivity)
            }
            if ((shizukuReady || privReady) && !AppSession.dexRunning) {
                withContext(Dispatchers.IO) {
                    try { AppSession.shell?.let { DexStarter.clearSession(it) } } catch (_: Exception) {}
                }
            }
            refreshStatus()
            ensureDaemon(openSettingsIfNeeded = true)
        }

        binding.btnDexSettings.setOnClickListener { DexStarter.openDesktopSettings(this) }
        binding.btnWireless.setOnClickListener { openWirelessDebug() }
        binding.btnPair.setOnClickListener { pairAndSpawnDaemon() }
        binding.btnStart.setOnClickListener {
            if (AppSession.dexRunning) stopDex() else startDex()
        }
        binding.btnShareLog.setOnClickListener { LogShare.share(this) }
        binding.btnToggleLog.setOnClickListener { toggleLogPanel() }
        binding.btnCanvasShare.setOnClickListener { LogShare.share(this) }
        binding.btnCanvasStop.setOnClickListener { stopDex() }
        binding.logo.setOnClickListener { AboutActivity.start(this) }
        binding.title.setOnClickListener { AboutActivity.start(this) }
        binding.btnAbout.setOnClickListener { AboutActivity.start(this) }
        binding.btnDiscord.setOnClickListener {
            AboutActivity.openUrl(this, AboutActivity.URL_DISCORD)
        }
        binding.btnDonate.setOnClickListener {
            AboutActivity.openUrl(this, AboutActivity.URL_KOFI, R.string.main_donate_failed)
        }
        maybeStartDesktopFromIntent(intent)

        binding.touchpad.onMove = { dx, dy ->
            if (AppSession.dexRunning) DexInput.move(dx * 2.8f, dy * 2.8f)
            else moveCursor(dx, dy)
        }
        binding.touchpad.onClick = {
            if (AppSession.dexRunning) DexInput.click()
            else tapCursor()
        }
        binding.touchpad.onRightClick = {
            if (AppSession.dexRunning) DexInput.clickRight()
        }
        binding.touchpad.onScroll = { dx, dy ->
            if (AppSession.dexRunning) DexInput.scroll(dx, dy)
        }
        binding.touchpad.onPinchBegin = { x0, y0, x1, y1 ->
            if (AppSession.dexRunning) DexInput.pinchBegin(x0, y0, x1, y1)
        }
        binding.touchpad.onPinch = { x0, y0, x1, y1 ->
            if (AppSession.dexRunning) DexInput.pinch(x0, y0, x1, y1)
        }
        binding.touchpad.onPinchEnd = {
            if (AppSession.dexRunning) DexInput.pinchEnd()
        }
        binding.btnPadLeft.setOnClickListener {
            if (AppSession.dexRunning) DexInput.click()
        }
        binding.btnPadRight.setOnClickListener {
            if (AppSession.dexRunning) DexInput.clickRight()
        }
        binding.btnPadKeyboard.setOnClickListener { /* IME is shown on this screen when a DeX field is focused */ }
        binding.keyboard.onKey = { key ->
            if (AppSession.dexRunning) sendDexKey(key) else sendKey(key)
        }

        refreshStatus()

        lifecycleScope.launch {
            WindowInfoTracker.getOrCreate(this@MainActivity)
                .windowLayoutInfo(this@MainActivity)
                .collect { info ->
                    binding.root.post { applyFold(info) }
                }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshStatus()
    }

    override fun onStop() {
        super.onStop()
        CrashGuard.persistSession(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        ensureDaemon(openSettingsIfNeeded = false)
        maybeRequestBatteryExemption()
        if (!AppSession.dexRunning) return
        if (AppSession.desktop?.virtualDisplay != null) {
            binding.root.post { showDesktopSurface(launchHome = false) }
        }
        val overlayId = DexStarter.overlayDisplayId(this)
        if (overlayId != null && DexOverlayService.isRunning() && !DexOverlayService.hasOverlay()) {
            DexOverlayService.show(overlayId)
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyScale()
        DexOverlayService.resizeForOrientation()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_RESET_UI) restoreUi()
        if (intent.action == ACTION_PAIR) showPairingUi()
        maybeStartDesktopFromIntent(intent)
    }

    override fun onDestroy() {
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuBinderListener)
            rikka.shizuku.Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        } catch (_: Exception) {}
        if (isFinishing) {
            AppSession.shutdown(this)
        }
        AppSession.onDexTextFocus = null
        AppSession.onTabletopChanged = null
        AppSession.onLog = null
        try { pairingMdns?.stop() } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun applyScale() {
        val sw = resources.configuration.smallestScreenWidthDp.coerceAtLeast(360)
        val factor = (sw / 360f).coerceIn(0.95f, 1.7f)
        val pad = (16 * factor).toInt()
        binding.topContent.setPadding(pad, pad, pad, pad)
        binding.title.textSize = 28f * factor
        binding.status.textSize = 14f * factor
        binding.connectionHint.textSize = 13f * factor
    }

    private fun applyFold(info: WindowLayoutInfo) {
        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        val halfOpen = fold != null && fold.state == FoldingFeature.State.HALF_OPENED
        val horizontal = fold?.orientation == FoldingFeature.Orientation.HORIZONTAL
        tabletop = if (AppSession.hingeTracked) {
            AppSession.foldLayout?.tabletop == true
        } else {
            halfOpen && horizontal
        }
        binding.postureLabel.text = when {
            tabletop && AppSession.dexRunning -> "Tabletop: desktop on top. Trackpad below; keyboard opens over the pad."
            tabletop -> "Tabletop: desktop will use the upper half; pad on the lower half."
            halfOpen -> "Half-open book. Lay it tabletop to split desktop / pad."
            fold != null -> "Inner screen flat."
            else -> ""
        }
        val screen = OverlayCanvas.screen(this)
        if (!AppSession.hingeTracked) {
            val prev = AppSession.foldLayout
            AppSession.foldLayout = if (tabletop) {
                AppSession.FoldLayout(true, screen.height / 2, screen.width, screen.height)
            } else {
                AppSession.FoldLayout(false, screen.height, screen.width, screen.height)
            }
            val layout = AppSession.foldLayout
            val geometryChanged = prev?.tabletop != layout?.tabletop
            if (AppSession.dexRunning && geometryChanged) {
                DexOverlayService.resizeForOrientation()
            }
        }
        layoutTabletopPanes()
        syncBottomChrome()
    }

    private fun applyRootInsets() {
        val dexPad = tabletop && AppSession.dexRunning
        if (dexPad) {
            binding.root.updatePadding(0, 0, 0, 0)
        } else {
            binding.root.updatePadding(insetLeft, insetTop, insetRight, insetBottom)
        }
    }

    private fun layoutTabletopPanes() {
        applyRootInsets()
        val topLp = binding.topPane.layoutParams as LinearLayout.LayoutParams
        val botLp = binding.bottomPane.layoutParams as LinearLayout.LayoutParams
        val dexPad = tabletop && AppSession.dexRunning
        if (dexPad) {
            topLp.height = 0
            topLp.weight = 0f
            botLp.height = 0
            botLp.weight = 1f
        } else {
            topLp.height = 0
            topLp.weight = 1f
            botLp.height = LinearLayout.LayoutParams.WRAP_CONTENT
            botLp.weight = 0f
        }
        binding.topPane.layoutParams = topLp
        binding.bottomPane.layoutParams = botLp
        placeCursor()
    }

    private fun syncBottomChrome() {
        val dexPad = tabletop && AppSession.dexRunning
        applyRootInsets()
        binding.bottomPane.isVisible = dexPad
        binding.hinge.isVisible = false
        binding.topScroll.isVisible = !dexPad && !binding.desktopSurface.isVisible
        binding.cursor.isVisible = false
        binding.padTitle.isVisible = false
        binding.keyboard.isVisible = false
        binding.clickBar.isVisible = dexPad
        binding.imeAnchor.isVisible = false
        val d = resources.displayMetrics.density
        val side = (14 * d).toInt()
        val bot = (10 * d).toInt()
        binding.bottomPane.setPadding(side, 0, side, bot)
        if (dexPad && !lastTabletopDex) DexInput.resetPointer()
        lastTabletopDex = dexPad
    }

    private fun showDexKeyboard() {
        val anchor = binding.imeAnchor
        anchor.visibility = android.view.View.INVISIBLE
        anchor.isFocusable = true
        anchor.isFocusableInTouchMode = true
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        anchor.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        anchor.post {
            imm?.showSoftInput(anchor, InputMethodManager.SHOW_FORCED)
        }
        imeShown = true
    }

    private fun hideDexKeyboard() {
        val anchor = binding.imeAnchor
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(anchor.windowToken, 0)
        anchor.clearFocus()
        imeShown = false
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val maxX = (binding.topPane.width - binding.cursor.width).toFloat().coerceAtLeast(0f)
        val maxY = (binding.topPane.height - binding.cursor.height).toFloat().coerceAtLeast(0f)
        cursorX = (cursorX + dx).coerceIn(0f, maxX)
        cursorY = (cursorY + dy).coerceIn(0f, maxY)
        placeCursor()
    }

    private fun placeCursor() {
        binding.cursor.translationX = cursorX
        binding.cursor.translationY = cursorY
    }

    private fun tapCursor() {
        val loc = IntArray(2)
        binding.topPane.getLocationOnScreen(loc)
        val x = (loc[0] + cursorX + binding.cursor.width / 2f).toInt()
        val y = (loc[1] + cursorY + binding.cursor.height / 2f).toInt()
        execBg("input tap $x $y")
    }

    private fun sendDexKey(key: String) {
        val code = when (key) {
            "⌫" -> KeyEvent.KEYCODE_DEL
            "space" -> KeyEvent.KEYCODE_SPACE
            "enter" -> KeyEvent.KEYCODE_ENTER
            else -> {
                DexInput.injectText(key)
                return
            }
        }
        DexInput.injectKey(KeyEvent.ACTION_DOWN, code)
        DexInput.injectKey(KeyEvent.ACTION_UP, code)
    }

    private fun sendKey(key: String) {
        val cmd = when (key) {
            "⌫" -> "input keyevent 67"
            "space" -> "input keyevent 62"
            "enter" -> "input keyevent 66"
            else -> "input text " + key.replace(" ", "%s").replace("'", "\\'")
        }
        execBg(cmd)
    }

    private fun execBg(command: String) {
        val shell = AppSession.shell ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            shell.runQuiet(command)
        }
    }

    private fun requestOverlayAndNotifs() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2003)
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun maybeRequestBatteryExemption() {
        if (batteryPrompted) return
        if (!Settings.canDrawOverlays(this)) return
        batteryPrompted = true
        ShizukuKeepAlive.requestBatteryExemption(this)
    }


    private val shizukuPermissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            append("Shizuku permission granted.")
            refreshStatus()
        }
    }

    private val shizukuBinderListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            AppSession.shizuku?.reset()
            if (ShizukuShell.hasPermission()) {
                append("Shizuku ready.")
                AppSession.overlayMirror.ensureBound()
                if (AppSession.dexRunning) {
                    DexOverlayService.rebindMirror()
                    Toast.makeText(this, "Shizuku reconnected.", Toast.LENGTH_SHORT).show()
                }
            } else {
                ShizukuShell.requestPermission(SHIZUKU_REQUEST_CODE)
            }
            AppSession.onShizukuChanged?.invoke(true)
            refreshStatus()
        }
    }

    private val shizukuBinderDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
        AppSession.shizuku?.reset()
        runOnUiThread {
            append("Shizuku died — restarting in the background.")
            Toast.makeText(this, "Shizuku dropped. InnerDesk is restarting it.", Toast.LENGTH_SHORT).show()
            AppSession.onShizukuChanged?.invoke(false)
            refreshStatus()
        }
    }

    private fun initShizuku() {
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
            rikka.shizuku.Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        } catch (_: Exception) {}
    }


    private fun startDex() {
        binding.btnStart.isEnabled = false
        lifecycleScope.launch {
            try {
                val kind = DexStarter.detectKind(this@MainActivity)
                append("Starting desktop (${DexStarter.kindLabel(kind)})…")
                var shell = withContext(Dispatchers.IO) { AppSession.shell }
                if (shell != null) {
                    append(
                        when (shell) {
                            BinderShell -> "Using priv binder shell."
                            DaemonShell -> "Using priv daemon socket."
                            else -> "Using Shizuku shell."
                        },
                    )
                }
                if (shell == null) {
                    append("Daemon not up. Starting it over Wireless debugging…")
                    val spawned = withContext(Dispatchers.IO) {
                        try {
                            WirelessAdb.get(this@MainActivity).startDaemon()
                        } catch (e: Exception) {
                            e.message ?: "daemon start failed"
                        }
                    }
                    append(spawned)
                    shell = AppSession.shell
                }
                if (shell == null) {
                    append("shell=null binder=${AppSession.overlayMirror.pingAlive()} socket=${DaemonShell.lastError}")
                    if (WirelessAdb.paired || WirelessAdb.live) {
                        Toast.makeText(
                            this@MainActivity,
                            "Paired, but the daemon did not start. Keep Wireless debugging on and try Start desktop again.",
                            Toast.LENGTH_LONG,
                        ).show()
                        AnonymousTelemetry.reportError(
                            this@MainActivity,
                            "daemon not ready after pair binder=${AppSession.overlayMirror.pingAlive()} socket=${DaemonShell.lastError}",
                        )
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Privileged daemon is not running. Pair Wireless debugging once this boot.",
                            Toast.LENGTH_LONG,
                        ).show()
                        openWirelessDebug()
                    }
                    return@launch
                }

                ShizukuKeepAlive.requestBatteryExemption(this@MainActivity)
                withContext(Dispatchers.IO) { AppSession.overlayMirror.ensureBound() }

                try {
                    desktop.release()
                } catch (_: Exception) {
                }
                AppSession.desktop = null

                val (physW, physH) = OverlayCanvas.overlayWindowSize(this@MainActivity)
                val spec = AppSession.applyOverlaySpec(this@MainActivity, physW, physH)
                append(OverlayCanvas.summary(this@MainActivity))

                if (!DexOverlayService.isRunning()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Enable InnerDesk in Settings → Accessibility first.",
                        Toast.LENGTH_LONG,
                    ).show()
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@launch
                }

                DexOverlayService.showCover()
                AppSession.setDexRunning(this@MainActivity, true)
                syncBottomChrome()
                kotlinx.coroutines.delay(80)

                HudService.showSession(this@MainActivity)

                val started = withContext(Dispatchers.IO) {
                    try { DexStarter.clearSession(shell) } catch (_: Exception) {}
                    try { ShizukuKeepAlive.applyDozeWhitelist(shell) } catch (_: Exception) {}
                    try { ShizukuKeepAlive.installWatchdog(this@MainActivity, shell) } catch (_: Exception) {}
                    val flags = try {
                        DexStarter.setDesktopFlags(shell, kind)
                    } catch (e: Exception) {
                        e.message ?: "flags failed"
                    }
                    Thread.sleep(300)
                    val overlay = try {
                        DexStarter.startAsExternalMonitor(shell, spec)
                    } catch (e: Exception) {
                        e.message ?: "overlay failed"
                    }
                    "flags:\n$flags\noverlay:\n$overlay"
                }
                append(started)

                var overlayId = overlayDisplayId()
                if (overlayId == null) {
                    kotlinx.coroutines.delay(800)
                    overlayId = overlayDisplayId()
                }
                val readyId = overlayId
                if (readyId != null) {
                    append("overlay display $readyId ready")
                    DexOverlayService.show(readyId)
                    append("mirror on display $readyId")
                } else {
                    append("no overlay display found")
                    AppSession.setDexRunning(this@MainActivity, false)
                    DexOverlayService.hide()
                    Toast.makeText(this@MainActivity, "Overlay display did not appear.", Toast.LENGTH_LONG).show()
                    AnonymousTelemetry.reportError(this@MainActivity, "no overlay display found")
                    return@launch
                }

                refreshStatus()
                syncBottomChrome()
                if (tabletop) DexOverlayService.resizeForOrientation()
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                HudService.showSession(this@MainActivity)
            } catch (e: Exception) {
                append("Start desktop failed: ${e.message}")
                AnonymousTelemetry.reportError(this@MainActivity, "Start desktop failed: ${e.message}")
                AppSession.setDexRunning(this@MainActivity, false)
                DexOverlayService.hide()
                Toast.makeText(this@MainActivity, e.message ?: "Start desktop failed", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnStart.isEnabled = true
                refreshStatus()
            }
        }
    }

    private suspend fun resolveShell(): PrivilegedShell? {
        val shell = AppSession.shell
        if (shell != null) {
            append(if (shell === DaemonShell) "Using priv daemon shell." else "Using Shizuku shell.")
            return shell
        }
        return null
    }

    private fun ensureDaemon(openSettingsIfNeeded: Boolean) {
        if (ensuringWireless) return
        if (AppSession.overlayMirror.pingAlive()) {
            hidePairingForm()
            ShizukuKeepAlive.refreshKeepAlive(this)
            return
        }
        ensuringWireless = true
        lifecycleScope.launch {
            try {
                requestOverlayAndNotifs()
                append("Checking privileged daemon…")
                val up = withContext(Dispatchers.IO) { bringDaemonUp() }
                val daemonUp = up || AppSession.overlayMirror.pingAlive()
                if (daemonUp) {
                    WirelessDebugUi.onAuthorized(this@MainActivity)
                    stopPairingDiscovery()
                    hidePairingForm()
                    append("Privileged daemon ready.")
                    Toast.makeText(this@MainActivity, getString(R.string.wireless_connected), Toast.LENGTH_SHORT).show()
                } else if (WirelessAdb.live || WirelessAdb.paired) {
                    hidePairingForm()
                    append("Wireless debugging is on, but the privileged daemon did not start yet.")
                } else {
                    askForWirelessDebug(openSettingsIfNeeded)
                }
                refreshStatus()
            } finally {
                ensuringWireless = false
            }
        }
    }

    private fun bringDaemonUp(): Boolean {
        if (AppSession.overlayMirror.pingAlive()) return true
        try { AppSession.overlayMirror.ensureBound() } catch (_: Exception) {}
        if (AppSession.overlayMirror.pingAlive()) return true
        try {
            val result = WirelessAdb.get(this).startDaemon()
            AppSession.appendLog(result)
            if (AppSession.overlayMirror.pingAlive() || result.contains("daemon ready", true)) return true
        } catch (e: Exception) {
            AppSession.appendLog("Wireless spawn: ${e.message}")
        }
        val shizuku = AppSession.shizuku
        if (shizuku?.isAvailable() == true) {
            try {
                ShizukuKeepAlive.installWatchdog(this, shizuku)
                repeat(8) {
                    if (AppSession.overlayMirror.pingAlive()) return true
                    Thread.sleep(250)
                }
            } catch (e: Exception) {
                AppSession.appendLog("Shizuku spawn: ${e.message}")
            }
        }
        return AppSession.overlayMirror.pingAlive()
    }

    private fun askForWirelessDebug(openSettings: Boolean) {
        if (!WirelessDebugUi.pairingNeeded()) {
            hidePairingForm()
            WirelessDebugUi.clear(this)
            return
        }
        WirelessDebugUi.onConnectFailed(this)
        showPairingUi()
        startPairingDiscovery()
        append("Daemon is down. Enable Wireless debugging and pair to start it.")
        if (openSettings) {
            WirelessDebugUi.notifyPairing(this, pairingHost, pairingPort.takeIf { it > 0 })
            Toast.makeText(this, getString(R.string.daemon_need_wireless), Toast.LENGTH_LONG).show()
            WirelessDebugUi.openSettings(this)
        }
    }

    private fun openWirelessDebug() {
        DexOverlayService.hide()
        requestOverlayAndNotifs()
        if (WirelessAdb.paired || WirelessAdb.live) {
            hidePairingForm()
            WirelessDebugUi.clear(this)
        } else {
            startPairingDiscovery()
            showPairingUi()
            WirelessDebugUi.notifyPairing(this, pairingHost, pairingPort.takeIf { it > 0 })
        }
        WirelessDebugUi.openSettings(this)
        append("Wireless debugging settings opened. Pair with the PIN if this device is not listed.")
        lifecycleScope.launch {
            val spawned = withContext(Dispatchers.IO) {
                try {
                    WirelessAdb.get(this@MainActivity).startDaemon()
                } catch (e: Exception) {
                    e.message
                }
            }
            if (spawned != null && (spawned.contains("daemon", true) || AppSession.overlayMirror.pingAlive())) {
                append("Wireless ADB: $spawned")
                WirelessDebugUi.onAuthorized(this@MainActivity)
                stopPairingDiscovery()
                hidePairingForm()
                refreshStatus()
                Toast.makeText(this@MainActivity, getString(R.string.wireless_connected), Toast.LENGTH_SHORT).show()
            } else if (WirelessAdb.paired) {
                WirelessDebugUi.onPaired(this@MainActivity)
                hidePairingForm()
                append("Paired. Daemon not up yet: ${spawned ?: "connect failed"}")
                refreshStatus()
            } else {
                WirelessDebugUi.onConnectFailed(this@MainActivity)
                showPairingUi()
                WirelessDebugUi.notifyPairing(this@MainActivity, pairingHost, pairingPort.takeIf { it > 0 })
                append("Enable Wireless debugging, then Pair with pairing code and enter the PIN.")
                refreshStatus()
            }
        }
    }

    private fun startPairingDiscovery() {
        if (pairingMdns != null) return
        try {
            val wifi = getSystemService(android.net.wifi.WifiManager::class.java)
            multicastLock = wifi?.createMulticastLock("innerdesk-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
        pairingMdns = WirelessAdb.discoverPairing(this) { host, port ->
            runOnUiThread {
                pairingHost = host
                pairingPort = port
                WirelessDebugUi.lastHost = host
                WirelessDebugUi.lastPort = port
                if (!WirelessDebugUi.pairingNeeded()) return@runOnUiThread
                binding.pairingHint.text = "Pairing port $port. Expand the notification and type the PIN there."
                WirelessDebugUi.notifyPairing(this, host, port)
                showPairingUi()
            }
        }
    }

    private fun stopPairingDiscovery() {
        try { pairingMdns?.stop() } catch (_: Exception) {}
        pairingMdns = null
    }

    private fun showPairingUi() {
        if (!WirelessDebugUi.pairingNeeded()) {
            hidePairingForm()
            WirelessDebugUi.clear(this)
            return
        }
        binding.pairingRow.isVisible = true
        binding.btnWireless.isVisible = true
        if (pairingPort > 0) {
            binding.pairingHint.text =
                "Pairing port $pairingPort. Wireless debugging → Pair with pairing code → type that PIN here."
        } else {
            binding.pairingHint.text =
                "Open Wireless debugging, tap Pair device with pairing code. The port appears here automatically."
        }
    }

    private fun hidePairingForm() {
        binding.pairingRow.isVisible = false
        binding.pinInput.text?.clear()
    }

    private fun pairAndSpawnDaemon() {
        val pin = binding.pinInput.text?.toString()?.trim().orEmpty()
        if (pin.length < 6) {
            Toast.makeText(this, "Enter the 6-digit PIN from the pairing dialog.", Toast.LENGTH_LONG).show()
            return
        }
        if (pairingPort <= 0) {
            Toast.makeText(this, "Waiting for pairing port. Keep Wireless debugging open.", Toast.LENGTH_LONG).show()
            startPairingDiscovery()
            return
        }
        binding.btnPair.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val adb = WirelessAdb.get(this@MainActivity)
                    adb.pairAt(pairingHost ?: "127.0.0.1", pairingPort, pin)
                    withContext(Dispatchers.Main) {
                        WirelessDebugUi.onPaired(this@MainActivity)
                        stopPairingDiscovery()
                        hidePairingForm()
                        refreshStatus()
                    }
                    adb.startDaemon()
                } catch (e: Exception) {
                    e.message ?: "pair failed"
                }
            }
            binding.btnPair.isEnabled = true
            append("Pair: $result")
            if (result.contains("daemon", true) || AppSession.overlayMirror.pingAlive()) {
                WirelessDebugUi.onAuthorized(this@MainActivity)
                stopPairingDiscovery()
                hidePairingForm()
                Toast.makeText(this@MainActivity, getString(R.string.wireless_connected), Toast.LENGTH_SHORT).show()
            } else if (WirelessAdb.paired) {
                WirelessDebugUi.onPaired(this@MainActivity)
                hidePairingForm()
                Toast.makeText(this@MainActivity, "Paired. Still starting the daemon…", Toast.LENGTH_LONG).show()
            } else {
                WirelessDebugUi.onConnectFailed(this@MainActivity)
                showPairingUi()
                Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        }
    }

    private fun stopDex() {
        AppSession.setDexRunning(this, false)
        hideDexKeyboard()
        lifecycleScope.launch(Dispatchers.IO) {
            try { AppSession.shell?.let { DexStarter.hideSamsungTouchpad(it) } } catch (_: Exception) {}
        }
        syncBottomChrome()
        refreshStatus()
        binding.btnStart.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppSession.shutdownBlocking(this@MainActivity)
            }
            hideDesktopSurface()
            showSystemBars()
            binding.btnStart.isEnabled = true
            refreshStatus()
            Toast.makeText(this@MainActivity, "Desktop stopped. Overlay cleared.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun overlayDisplayId(): Int? {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        val all = buildList {
            addAll(dm.displays)
            try {
                addAll(dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
            } catch (_: Exception) {
            }
        }.distinctBy { it.displayId }
        AppSession.appendLog(
            "appDisplays=" + all.joinToString { d -> "${d.displayId}:${d.name}:${d.width}x${d.height}" },
        )
        return all.firstOrNull { display ->
            display.displayId != android.view.Display.DEFAULT_DISPLAY &&
                (display.name.contains("overlay", true) || display.name.contains("Overlay", true))
        }?.displayId ?: all.firstOrNull {
            it.displayId != android.view.Display.DEFAULT_DISPLAY && it.displayId != 1
        }?.displayId
    }

    private fun restoreUi() {
        hideDesktopSurface()
        showSystemBars()
        AppSession.setDexRunning(this, false)
        refreshStatus()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun showDesktopSurface(launchHome: Boolean) {
        hideSystemBars()
        binding.topScroll.isVisible = false
        binding.desktopSurface.isVisible = true
        binding.canvasBar.isVisible = true
        binding.desktopSurface.setOnTouchListener { v, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                val displayId = overlayDisplayId() ?: desktop.displayId
                val tw = AppSession.overlayDisplayW.takeIf { it > 0 }
                    ?: desktop.width.takeIf { it > 0 }
                    ?: v.width
                val th = AppSession.overlayDisplayH.takeIf { it > 0 }
                    ?: desktop.height.takeIf { it > 0 }
                    ?: v.height
                val x = (ev.x * tw / v.width.coerceAtLeast(1)).toInt()
                val y = (ev.y * th / v.height.coerceAtLeast(1)).toInt()
                val cmd = if (displayId != null) {
                    "input -d $displayId tap ${x.coerceIn(0, (tw - 1).coerceAtLeast(0))} ${y.coerceIn(0, (th - 1).coerceAtLeast(0))}"
                } else {
                    null
                }
                if (cmd != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val result = AppSession.shell?.run(cmd) ?: "no shell"
                        if (tapLogs < 8) {
                            tapLogs++
                            runOnUiThread { append(result) }
                        }
                    }
                }
                v.performClick()
            }
            true
        }
        val holder = binding.desktopSurface.holder
        val start = {
            if (holder.surface.isValid && launchHome) {
                try {
                    val w = binding.desktopSurface.width.coerceAtLeast(1)
                    val h = binding.desktopSurface.height.coerceAtLeast(1)
                    val created = desktop.virtualDisplay == null
                    if (desktop.ensure(w, h, holder.surface)) {
                        if (created || desktop.needsHome) {
                            desktop.markHomeStarted()
                            lifecycleScope.launch(Dispatchers.IO) {
                                val currentShell = AppSession.shell
                                val launched = try {
                                    if (currentShell != null) {
                                        desktop.launchHome(this@MainActivity, currentShell)
                                    } else {
                                        "no shell available"
                                    }
                                } catch (e: Exception) {
                                    e.message ?: "launch failed"
                                }
                                runOnUiThread { append(launched) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    append("display failed: ${e.message}")
                    restoreUi()
                }
            }
        }
        if (holder.surface.isValid) start()
        else if (!surfaceHooked) {
            surfaceHooked = true
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) = start()
                override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (width >= 200 && height >= 200) start()
                }
                override fun surfaceDestroyed(h: SurfaceHolder) = Unit
            })
        }
    }

    private fun hideDesktopSurface() {
        AppSession.overlayMirror.stop()
        try {
            desktop.release()
        } catch (_: Exception) {
        }
        AppSession.desktop = null
        binding.desktopSurface.setOnTouchListener(null)
        binding.desktopSurface.isVisible = false
        binding.canvasBar.isVisible = false
        binding.topScroll.isVisible = true
    }

    private fun refreshStatus() {
        binding.btnStart.text = if (AppSession.dexRunning) getString(R.string.stop_dex) else getString(R.string.start_dex)
        val privOk = AppSession.overlayMirror.pingAlive()
        binding.status.text = when {
            AppSession.dexRunning && privOk -> "Desktop is on. Tap Stop desktop, or use the side arrow."
            AppSession.dexRunning -> "Desktop is on. Privileged daemon reconnecting…"
            privOk -> "Privileged daemon ready. Start desktop."
            else -> "Start the privileged daemon once this boot (Wireless debugging), then Start desktop."
        }
        val wirelessLive = WirelessAdb.live
        val wirelessPaired = WirelessAdb.paired
        binding.connectionHint.text = when {
            privOk && wirelessLive -> getString(R.string.wireless_connected)
            wirelessLive || wirelessPaired -> getString(R.string.wireless_paired)
            WirelessDebugUi.needsPairing -> "Unpaired. Open Wireless debugging → Pair with pairing code, then enter the PIN."
            privOk -> "Daemon is running."
            else -> "Tap Wireless debugging once this boot. Expand the notification and type the PIN there."
        }
        binding.btnWireless.text = when {
            privOk && wirelessLive -> getString(R.string.wireless_connected)
            wirelessLive || wirelessPaired -> getString(R.string.wireless_paired)
            else -> getString(R.string.open_wireless_debug)
        }
        binding.btnWireless.isVisible = true
        if (!WirelessDebugUi.pairingNeeded()) {
            hidePairingForm()
            WirelessDebugUi.clear(this)
        } else {
            showPairingUi()
        }
    }

    private fun toggleLogPanel() {
        val show = !binding.logPanel.isVisible
        binding.logPanel.isVisible = show
        binding.btnToggleLog.text = getString(if (show) R.string.main_hide_logs else R.string.main_logs)
        if (show) {
            binding.logScroll.post {
                binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun append(text: String) {
        AppSession.appendLog(text)
    }

    private fun renderLog(text: String) {
        if (!::binding.isInitialized) return
        binding.log.text = text.ifBlank { "" }
        binding.logScroll.post {
            binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun maybeOfferCrashShare() {
        if (crashShareOffered) return
        if (CrashGuard.pendingCrashText(this) == null) return
        crashShareOffered = true
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_share_title)
            .setMessage(R.string.crash_share_body)
            .setPositiveButton(R.string.crash_share_now) { _, _ ->
                toggleLogPanelIfHidden()
                LogShare.share(this)
            }
            .setNegativeButton(R.string.crash_share_later, null)
            .show()
    }

    private fun toggleLogPanelIfHidden() {
        if (!binding.logPanel.isVisible) toggleLogPanel()
    }

    private fun maybeStartDesktopFromIntent(intent: Intent?) {
        if (intent?.action != ACTION_START_DESKTOP) return
        if (AppSession.dexRunning) return
        binding.root.post { startDex() }
    }

    companion object {
        const val ACTION_RESET_UI = "dev.zanderp.innerdesk.RESET_UI"
        const val ACTION_PAIR = "dev.zanderp.innerdesk.PAIR"
        const val ACTION_START_DESKTOP = "dev.zanderp.innerdesk.START_DESKTOP"
        private const val SHIZUKU_REQUEST_CODE = 3001
        private var crashShareOffered = false

        fun startDesktopIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
                .setAction(ACTION_START_DESKTOP)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
        }
    }
}
