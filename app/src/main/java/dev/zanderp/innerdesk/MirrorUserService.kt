package dev.zanderp.innerdesk

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MirrorUserService : IMirrorService.Stub() {

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var inputManager: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null
    private var injectArity = 2
    private var mirrorSc: Any? = null
    private var vdCallback: Any? = null
    private var callbackBinder: Binder? = null
    private var idm: Any? = null

    override fun startMirror(
        displayId: Int,
        surface: Surface,
        parent: SurfaceControl?,
        width: Int,
        height: Int,
        dpi: Int,
    ): String {
        stopMirror()
        initInputManager()

        val sc = trySurfaceControlMirror(displayId, parent, width, height)
        if (sc != null) return sc

        val vd = tryVdMirror(displayId, surface, width, height, dpi)
        if (vd != null) return vd

        val hw = startHardwareCapture(displayId, surface, width, height)
        if (hw != null) return hw

        val captureId = findWorkingDisplayId(displayId)
            ?: return "ERR: no valid capture target for d=$displayId"
        startRawCapture(captureId, surface, width, height)
        return "OK raw id=$captureId d=$displayId ${width}x$height input=${inputManager != null}"
    }

    private fun trySurfaceControlMirror(displayId: Int, parent: SurfaceControl?, destW: Int, destH: Int): String? {
        if (parent == null || !parent.isValid) return null
        val scClass = try { Class.forName("android.view.SurfaceControl") } catch (_: Exception) { return null }
        val txnClass = try { Class.forName("android.view.SurfaceControl\$Transaction") } catch (_: Exception) { return null }

        var sc: Any? = null
        try {
            val iwm = getIWindowManager()
            if (iwm != null) {
                val iwmClass = Class.forName("android.view.IWindowManager")
                val outCtor = scClass.getDeclaredConstructor()
                outCtor.isAccessible = true
                val outSc = outCtor.newInstance()
                val method = iwmClass.methods.firstOrNull { m ->
                    m.name == "mirrorDisplay" && m.parameterTypes.size == 2
                }
                if (method != null) {
                    method.invoke(iwm, displayId, outSc)
                    val valid = try { scClass.getMethod("isValid").invoke(outSc) as Boolean } catch (_: Exception) { true }
                    if (valid) sc = outSc
                }
            }
        } catch (_: Exception) {}

        if (sc == null) {
            try {
                val mirror = scClass.methods.firstOrNull { m ->
                    m.name == "mirrorDisplay" && m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == Int::class.javaPrimitiveType
                } ?: scClass.declaredMethods.firstOrNull { m ->
                    m.name == "mirrorDisplay" && m.parameterTypes.size == 1
                }
                if (mirror != null) {
                    mirror.isAccessible = true
                    sc = mirror.invoke(null, displayId)
                }
            } catch (_: Exception) {}
        }

        val layer = sc ?: return null
        val valid = try { scClass.getMethod("isValid").invoke(layer) as Boolean } catch (_: Exception) { true }
        if (!valid) return null

        return try {
            val txn = txnClass.getConstructor().newInstance()
            txnClass.getMethod("reparent", scClass, scClass).invoke(txn, layer, parent)
            try {
                txnClass.getMethod("setPosition", scClass, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .invoke(txn, layer, 0f, 0f)
            } catch (_: Exception) {}
            val src = displayLogicalSize(displayId)
            val srcW = src?.first ?: 0
            val srcH = src?.second ?: 0
            if (srcW > 0 && srcH > 0 && (srcW != destW || srcH != destH)) {
                val sx = destW.toFloat() / srcW
                val sy = destH.toFloat() / srcH
                var scaled = false
                try {
                    txnClass.getMethod(
                        "setScale",
                        scClass,
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                    ).invoke(txn, layer, sx, sy)
                    scaled = true
                } catch (_: Exception) {}
                if (!scaled) {
                    try {
                        txnClass.getMethod(
                            "setMatrix",
                            scClass,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                        ).invoke(txn, layer, sx, 0f, 0f, sy)
                        scaled = true
                    } catch (_: Exception) {}
                }
                try {
                    txnClass.getMethod("setWindowCrop", scClass, android.graphics.Rect::class.java)
                        .invoke(txn, layer, android.graphics.Rect(0, 0, srcW, srcH))
                } catch (_: Exception) {}
                if (!scaled) {
                    try {
                        txnClass.getMethod("setDestinationFrame", scClass, android.graphics.Rect::class.java)
                            .invoke(txn, layer, android.graphics.Rect(0, 0, destW, destH))
                    } catch (_: Exception) {}
                }
            } else {
                try {
                    txnClass.getMethod("setDestinationFrame", scClass, android.graphics.Rect::class.java)
                        .invoke(txn, layer, android.graphics.Rect(0, 0, destW, destH))
                } catch (_: Exception) {
                    try {
                        txnClass.getMethod(
                            "setGeometry",
                            scClass,
                            android.graphics.Rect::class.java,
                            android.graphics.Rect::class.java,
                            Int::class.javaPrimitiveType,
                        ).invoke(txn, layer, null, android.graphics.Rect(0, 0, destW, destH), 0)
                    } catch (_: Exception) {}
                }
            }
            try {
                txnClass.getMethod("setVisibility", scClass, Boolean::class.javaPrimitiveType)
                    .invoke(txn, layer, true)
            } catch (_: Exception) {}
            try {
                txnClass.getMethod("show", scClass).invoke(txn, layer)
            } catch (_: Exception) {}
            try {
                txnClass.getMethod("setLayer", scClass, Int::class.javaPrimitiveType)
                    .invoke(txn, layer, Int.MAX_VALUE)
            } catch (_: Exception) {}
            txnClass.getMethod("apply").invoke(txn)
            mirrorSc = layer
            "OK sc-mirror d=$displayId ${destW}x$destH input=${inputManager != null}"
        } catch (_: Exception) {
            try { scClass.getMethod("release").invoke(layer) } catch (_: Exception) {}
            null
        }
    }

    private fun displayLogicalSize(displayId: Int): Pair<Int, Int>? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "display") as IBinder
            val dm = Class.forName("android.hardware.display.IDisplayManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            val info = Class.forName("android.hardware.display.IDisplayManager")
                .getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                .invoke(dm, displayId) ?: return null
            val cls = info.javaClass
            val w = cls.getField("logicalWidth").getInt(info)
            val h = cls.getField("logicalHeight").getInt(info)
            if (w > 0 && h > 0) w to h else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getIWindowManager(): Any? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "window") as IBinder
            Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (_: Exception) { null }
    }

    private fun tryVdMirror(displayId: Int, surface: Surface, width: Int, height: Int, dpi: Int): String? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val dmBinder = smClass.getMethod("getService", String::class.java)
                .invoke(null, "display") as IBinder
            val dm = Class.forName("android.hardware.display.IDisplayManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, dmBinder)
            idm = dm

            val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val builder = builderClass.getConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).newInstance("InnerDeskMirror", width, height, dpi)

            builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
            builderClass.getMethod("setDisplayIdToMirror", Int::class.javaPrimitiveType)
                .invoke(builder, displayId)
            // PUBLIC | AUTO_MIRROR | TRUSTED. WM mirroring selects the overlay
            // instead of SurfaceFlinger always copying display 0.
            builderClass.getMethod("setFlags", Int::class.javaPrimitiveType)
                .invoke(builder, 1 or 16 or 1024)
            try {
                builderClass.getMethod("setWindowManagerMirroringEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
            } catch (_: Exception) {}
            val config = builderClass.getMethod("build").invoke(builder)

            val cbInterface = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            val binder = object : Binder() {
                override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                    return true
                }
            }
            binder.attachInterface(null, "android.hardware.display.IVirtualDisplayCallback")
            callbackBinder = binder
            val cb = Proxy.newProxyInstance(cbInterface.classLoader, arrayOf(cbInterface)) { _, method, _ ->
                if (method.name == "asBinder") binder else null
            }
            vdCallback = cb

            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val mpClass = Class.forName("android.media.projection.IMediaProjection")
            val vdId = Class.forName("android.hardware.display.IDisplayManager")
                .getMethod("createVirtualDisplay", configClass, cbInterface, mpClass, String::class.java)
                .invoke(dm, config, cb, null, "com.android.shell") as Int
            if (vdId < 0) return null

            try {
                Class.forName("android.hardware.display.IDisplayManager")
                    .getMethod("setDisplayIdToMirror", IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(dm, binder, displayId)
            } catch (_: Exception) {}

            "OK compositor vd=$vdId mirror=$displayId ${width}x$height input=${inputManager != null}"
        } catch (_: Exception) {
            vdCallback = null
            callbackBinder = null
            idm = null
            null
        }
    }

    private fun startHardwareCapture(displayId: Int, surface: Surface, width: Int, height: Int): String? {
        val capture = resolveScreenCapture(displayId, width, height) ?: return null
        running = true
        captureThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            var prev: HwFrame? = null
            val dst = Rect()
            while (running && surface.isValid) {
                try {
                    val result = capture.capture() ?: continue
                    val bmp = result.toBitmap() ?: continue
                    val canvas = try {
                        surface.lockHardwareCanvas()
                    } catch (_: Exception) {
                        surface.lockCanvas(null)
                    }
                    if (canvas == null) {
                        result.close()
                        continue
                    }
                    try {
                        dst.set(0, 0, canvas.width, canvas.height)
                        canvas.drawBitmap(bmp, null, dst, null)
                    } finally {
                        surface.unlockCanvasAndPost(canvas)
                    }
                    prev?.close()
                    prev = result
                } catch (_: Exception) {
                    if (!running) break
                    Thread.sleep(4)
                }
            }
            prev?.close()
        }.apply {
            name = "InnerDeskHwCap"
            isDaemon = true
            start()
        }
        return "OK hw ${capture.label} d=$displayId ${width}x$height input=${inputManager != null}"
    }

    private class HwFrame(val bitmap: Bitmap, val buffer: HardwareBuffer?) {
        fun toBitmap(): Bitmap = bitmap
        fun close() {
            try { buffer?.close() } catch (_: Exception) {}
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                try { bitmap.recycle() } catch (_: Exception) {}
            }
        }
    }

    private class HwCapturer(
        val label: String,
        private val invoke: () -> HwFrame?,
    ) {
        fun capture(): HwFrame? = invoke()
    }

    private fun resolveScreenCapture(displayId: Int, width: Int, height: Int): HwCapturer? {
        val classNames = arrayOf(
            "android.window.ScreenCapture",
            "android.view.SurfaceControl",
        )
        val argsNames = arrayOf(
            "android.window.ScreenCapture\$DisplayCaptureArgs",
            "android.view.SurfaceControl\$DisplayCaptureArgs",
        )

        for (clsName in classNames) {
            val capClass = try { Class.forName(clsName) } catch (_: Exception) { continue }
            for (argsName in argsNames) {
                val argsClass = try { Class.forName(argsName) } catch (_: Exception) { continue }
                val builderClass = try { Class.forName("$argsName\$Builder") } catch (_: Exception) { continue }
                val args = buildCaptureArgs(builderClass, displayId, width, height) ?: continue
                val captureDisplay = try {
                    capClass.getMethod("captureDisplay", argsClass)
                } catch (_: Exception) { continue }

                val probe = try {
                    wrapHwFrame(captureDisplay.invoke(null, args))
                } catch (_: Exception) { null }
                if (probe == null) continue
                probe.close()

                return HwCapturer("$clsName.captureDisplay") {
                    try {
                        wrapHwFrame(captureDisplay.invoke(null, args))
                    } catch (_: Exception) { null }
                }
            }
        }
        return null
    }

    private fun buildCaptureArgs(builderClass: Class<*>, displayId: Int, width: Int, height: Int): Any? {
        val builder = try {
            builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(displayId)
        } catch (_: Exception) {
            try {
                val b = builderClass.getConstructor().newInstance()
                builderClass.methods.firstOrNull {
                    it.name == "setDisplayId" && it.parameterTypes.size == 1
                }?.invoke(b, displayId) ?: return null
                b
            } catch (_: Exception) { return null }
        }
        // Capture at the overlay's native size — do not upscale to the phone screen.
        return try {
            builderClass.getMethod("build").invoke(builder)
        } catch (_: Exception) { null }
    }

    private fun wrapHwFrame(result: Any?): HwFrame? {
        if (result == null) return null
        val cls = result.javaClass
        try {
            val hb = cls.getMethod("getHardwareBuffer").invoke(result) as? HardwareBuffer
            if (hb != null) {
                val cs = try {
                    cls.getMethod("getColorSpace").invoke(result) as? ColorSpace
                } catch (_: Exception) { null }
                val bmp = Bitmap.wrapHardwareBuffer(hb, cs) ?: return null
                return HwFrame(bmp, hb)
            }
        } catch (_: Exception) {}
        return try {
            val bmp = cls.getMethod("asBitmap").invoke(result) as? Bitmap ?: return null
            HwFrame(bmp, null)
        } catch (_: Exception) { null }
    }

    private fun startRawCapture(captureId: String, surface: Surface, width: Int, height: Int) {
        running = true
        captureThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            val dst = Rect()
            var bmp: Bitmap? = null
            var pixels: ByteArray? = null
            var expected = 0
            val header = ByteArray(12)

            while (running && surface.isValid) {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-d", captureId))
                    val input = proc.inputStream
                    if (!readFully(input, header, 12)) {
                        proc.destroy()
                        continue
                    }
                    val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val w = le.int
                    val h = le.int
                    val fmt = le.int
                    if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
                        proc.destroy()
                        continue
                    }
                    val bpp = if (fmt == 4) 2 else 4
                    val pixelBytes = w * h * bpp
                    if (pixels == null || expected != pixelBytes) {
                        pixels = ByteArray(pixelBytes)
                        expected = pixelBytes
                        bmp?.recycle()
                        bmp = Bitmap.createBitmap(w, h, if (bpp == 2) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
                    }
                    val raw = pixels!!
                    if (!readFully(input, raw, pixelBytes)) {
                        proc.destroy()
                        continue
                    }
                    proc.destroy()

                    val frame = bmp!!
                    frame.copyPixelsFromBuffer(ByteBuffer.wrap(raw))
                    val canvas = try {
                        surface.lockHardwareCanvas()
                    } catch (_: Exception) {
                        surface.lockCanvas(null)
                    } ?: continue
                    try {
                        dst.set(0, 0, canvas.width, canvas.height)
                        canvas.drawBitmap(frame, null, dst, null)
                    } finally {
                        surface.unlockCanvasAndPost(canvas)
                    }
                } catch (_: Exception) {
                    if (!running) break
                    Thread.sleep(8)
                }
            }
            bmp?.recycle()
        }.apply {
            name = "InnerDeskRawCap"
            isDaemon = true
            start()
        }
    }

    private fun readFully(input: java.io.InputStream, dest: ByteArray, count: Int): Boolean {
        var off = 0
        while (off < count) {
            val n = input.read(dest, off, count - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    override fun injectPointer(displayId: Int, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val im = inputManager ?: return
        val inject = injectMethod ?: return
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })
        val ev = MotionEvent.obtain(
            downTime, eventTime, action,
            1, props, coords,
            0, 0, 1f, 1f,
            0, 0, 0x1002, 0,
        )
        setDisplayId(ev, displayId)
        try {
            if (injectArity == 3) inject.invoke(im, ev, 0, displayId)
            else inject.invoke(im, ev, 0)
        } catch (_: Exception) {
            try { inject.invoke(im, ev, 0) } catch (_: Exception) {}
        }
        ev.recycle()
    }

    override fun injectScroll(displayId: Int, x: Float, y: Float, hScroll: Float, vScroll: Float) {
        val im = inputManager ?: return
        val inject = injectMethod ?: return
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        })
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL,
            1, props, coords,
            0, 0, 1f, 1f,
            0, 0, 0x2002, 0,
        )
        setDisplayId(ev, displayId)
        try {
            if (injectArity == 3) inject.invoke(im, ev, 0, displayId)
            else inject.invoke(im, ev, 0)
        } catch (_: Exception) {
            try { inject.invoke(im, ev, 0) } catch (_: Exception) {}
        }
        ev.recycle()
    }

    override fun injectFingers(
        displayId: Int,
        action: Int,
        pointerCount: Int,
        x: FloatArray,
        y: FloatArray,
        downTime: Long,
        eventTime: Long,
    ) {
        val im = inputManager ?: return
        val inject = injectMethod ?: return
        val n = pointerCount.coerceIn(1, minOf(x.size, y.size, 8))
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                this.x = x[i]
                this.y = y[i]
                pressure = 1f
                size = 1f
            }
        }
        val ev = MotionEvent.obtain(
            downTime, eventTime, action,
            n, props, coords,
            0, 0, 1f, 1f,
            0, 0, 0x1002, 0,
        )
        setDisplayId(ev, displayId)
        try {
            if (injectArity == 3) inject.invoke(im, ev, 0, displayId)
            else inject.invoke(im, ev, 0)
        } catch (_: Exception) {
            try { inject.invoke(im, ev, 0) } catch (_: Exception) {}
        }
        ev.recycle()
    }

    override fun injectMouse(displayId: Int, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val im = inputManager ?: return
        val inject = injectMethod ?: return
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_HOVER_MOVE) 0f else 1f
            size = 1f
        })
        val button = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> MotionEvent.BUTTON_PRIMARY
            else -> 0
        }
        val ev = MotionEvent.obtain(
            downTime, eventTime, action,
            1, props, coords,
            0, button, 1f, 1f,
            0, 0, 0x2002, 0,
        )
        setDisplayId(ev, displayId)
        try {
            if (injectArity == 3) inject.invoke(im, ev, 0, displayId)
            else inject.invoke(im, ev, 0)
        } catch (_: Exception) {
            try { inject.invoke(im, ev, 0) } catch (_: Exception) {}
        }
        ev.recycle()
    }

    override fun injectMouseButtons(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        buttons: Int,
    ) {
        val im = inputManager ?: return
        val inject = injectMethod ?: return
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_HOVER_MOVE) 0f else 1f
            size = 1f
        })
        val ev = MotionEvent.obtain(
            downTime, eventTime, action,
            1, props, coords,
            0, buttons, 1f, 1f,
            0, 0, 0x2002, 0,
        )
        setDisplayId(ev, displayId)
        try {
            if (injectArity == 3) inject.invoke(im, ev, 0, displayId)
            else inject.invoke(im, ev, 0)
        } catch (_: Exception) {
            try { inject.invoke(im, ev, 0) } catch (_: Exception) {}
        }
        ev.recycle()
    }

    override fun injectKey(displayId: Int, action: Int, keyCode: Int) {
        val im = inputManager ?: return
        val inject = injectMethod ?: return
        val now = SystemClock.uptimeMillis()
        val ev = android.view.KeyEvent(now, now, action, keyCode, 0)
        setKeyDisplayId(ev, displayId)
        try {
            if (injectArity == 3) inject.invoke(im, ev, 0, displayId)
            else inject.invoke(im, ev, 0)
        } catch (_: Exception) {
            try { inject.invoke(im, ev, 0) } catch (_: Exception) {}
        }
    }

    override fun injectText(displayId: Int, text: String) {
        if (text.isEmpty()) return
        val im = inputManager ?: return
        val inject = injectMethod ?: return
        val kcm = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = kcm.getEvents(text.toCharArray()) ?: return
        for (ev in events) {
            setKeyDisplayId(ev, displayId)
            try {
                if (injectArity == 3) inject.invoke(im, ev, 0, displayId)
                else inject.invoke(im, ev, 0)
            } catch (_: Exception) {
                try { inject.invoke(im, ev, 0) } catch (_: Exception) {}
            }
        }
    }

    override fun setDisplayImePolicy(displayId: Int, policy: Int): String {
        return try {
            val iwm = getIWindowManager() ?: return "no IWindowManager"
            val method = iwm.javaClass.methods.firstOrNull { m ->
                m.name == "setDisplayImePolicy" && m.parameterTypes.size == 2
            } ?: return "no setDisplayImePolicy"
            method.invoke(iwm, displayId, policy)
            "OK d=$displayId policy=$policy"
        } catch (e: Exception) {
            e.message ?: "ime policy failed"
        }
    }

    private fun setKeyDisplayId(event: android.view.KeyEvent, displayId: Int) {
        try {
            android.view.KeyEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        } catch (_: Exception) {
            try {
                val field = android.view.KeyEvent::class.java.getDeclaredField("mDisplayId")
                field.isAccessible = true
                field.setInt(event, displayId)
            } catch (_: Exception) {}
        }
    }

    private fun setDisplayId(event: MotionEvent, displayId: Int) {
        try {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        } catch (_: Exception) {
            try {
                val field = MotionEvent::class.java.getDeclaredField("mDisplayId")
                field.isAccessible = true
                field.setInt(event, displayId)
            } catch (_: Exception) {}
        }
    }

    private fun initInputManager() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
            val iimStub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val im = iimStub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            inputManager = im
            val iimClass = Class.forName("android.hardware.input.IInputManager")
            injectMethod = try {
                iimClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).also { injectArity = 3 }
            } catch (_: Exception) {
                iimClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                ).also { injectArity = 2 }
            }
        } catch (_: Exception) {}
    }

    private fun findWorkingDisplayId(logicalId: Int): String? {
        if (testRawScreencap(logicalId.toString())) return logicalId.toString()
        val sfId = resolveSfDisplayId()
        if (sfId != null && testRawScreencap(sfId)) return sfId
        return logicalId.toString()
    }

    private fun testRawScreencap(id: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-d", id))
            val header = ByteArray(12)
            val ok = readFully(proc.inputStream, header, 12)
            proc.destroy()
            if (!ok) return false
            val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val w = le.int
            val h = le.int
            w in 1..4096 && h in 1..4096
        } catch (_: Exception) { false }
    }

    private fun resolveSfDisplayId(): String? {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "dumpsys SurfaceFlinger --display-id | grep -i overlay"))
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            return Regex("(\\d{10,})").find(output)?.groupValues?.get(1)
        } catch (_: Exception) {}
        return null
    }

    override fun ping(): Int {
        try { java.io.File("/data/local/tmp/idx-priv.log").appendText("ping\n") } catch (_: Exception) {}
        return android.os.Process.myUid()
    }

    override fun exec(command: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.readBytes()
            val err = p.errorStream.readBytes()
            p.waitFor()
            (out + err).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            "fail ${e.message}"
        }
    }

    override fun stopMirror() {
        running = false
        captureThread?.interrupt()
        captureThread = null

        mirrorSc?.let { sc ->
            try {
                val scClass = Class.forName("android.view.SurfaceControl")
                val txnClass = Class.forName("android.view.SurfaceControl\$Transaction")
                val txn = txnClass.getConstructor().newInstance()
                txnClass.getMethod("reparent", scClass, scClass).invoke(txn, sc, null)
                txnClass.getMethod("apply").invoke(txn)
                scClass.getMethod("release").invoke(sc)
            } catch (_: Exception) {}
        }
        mirrorSc = null

        val cb = vdCallback
        val dm = idm
        if (cb != null && dm != null) {
            try {
                val cbClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
                Class.forName("android.hardware.display.IDisplayManager")
                    .getMethod("releaseVirtualDisplay", cbClass)
                    .invoke(dm, cb)
            } catch (_: Exception) {}
        }
        vdCallback = null
        callbackBinder = null
        idm = null
    }

    override fun destroy() { stopMirror() }
}
