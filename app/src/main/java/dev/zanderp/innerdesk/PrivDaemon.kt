package dev.zanderp.innerdesk

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.File
import kotlin.system.exitProcess

class PrivDaemon {
    companion object {
        const val SOCKET = "innerdesk_priv"
        const val SERVICE_NAME = "innerdesk.priv"
        const val AUTHORITY = "dev.zanderp.innerdesk.priv"
        private const val LOG = "/data/local/tmp/idx-priv.log"
        private const val HOOK = "dev.zanderp.innerdesk.PrivHookService"
        private const val RECEIVER = "dev.zanderp.innerdesk.PrivReceiver"
        private const val PKG = "dev.zanderp.innerdesk"

        @Volatile private var delivered = false
        @Volatile private var delivering = false
        @Volatile private var loggedSigs = false

        @JvmStatic
        fun main(args: Array<String>) {
            log("idxprivd start uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}")
            try { Looper.prepareMainLooper() } catch (_: Exception) {}
            try {
                Class.forName("com.android.internal.os.BinderInternal")
                    .getMethod("getContextObject").invoke(null)
                log("binder thread pool up")
            } catch (e: Exception) {
                log("binder pool: ${e.message}")
            }
            val appUid = args.firstOrNull()?.toIntOrNull() ?: 0
            val svc = MirrorUserService()
            val main = Handler(Looper.getMainLooper())
            Thread({ commandLoop(appUid) }, "idx-cmd").apply { isDaemon = true }.start()
            Thread({ watchAppOrphan(appUid) }, "idx-orphan").apply { isDaemon = true }.start()
            Thread({
                while (true) {
                    try {
                        if (!delivering) {
                            delivering = true
                            try { deliverAll(svc.asBinder()) } catch (e: Exception) {
                                log("deliver: ${e.cause?.message ?: e.message}")
                            } finally {
                                delivering = false
                            }
                        }
                    } catch (e: Exception) {
                        log("deliver: ${e.cause?.message ?: e.message}")
                    }
                    try { Thread.sleep(if (delivered) 15_000 else 2_000) } catch (_: Exception) {}
                }
            }, "idx-deliver").apply { isDaemon = true }.start()
            try {
                Looper.loop()
            } catch (_: Exception) {
                while (true) try { Thread.sleep(60_000) } catch (_: Exception) {}
            }
        }

        private fun log(msg: String) {
            try { File(LOG).appendText("${java.util.Date()} $msg\n") } catch (_: Exception) {}
        }

        private fun watchAppOrphan(appUid: Int) {
            var misses = 0
            while (true) {
                try { Thread.sleep(2000) } catch (_: Exception) {}
                if (appUid <= 0) continue
                if (!overlayActive()) {
                    misses = 0
                    continue
                }
                if (uidHasProcess(appUid)) {
                    misses = 0
                    continue
                }
                misses++
                log("overlay orphan miss=$misses uid=$appUid")
                if (misses < 3) continue
                log("app uid $appUid gone, clearing overlay")
                teardownOverlay()
                misses = 0
            }
        }

        private fun overlayActive(): Boolean {
            val v = shOut("settings get global overlay_display_devices").trim()
            return v.isNotEmpty() && v != "null" && v != "none"
        }

        private fun uidHasProcess(appUid: Int): Boolean {
            val proc = File("/proc")
            val dirs = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return true
            for (dir in dirs) {
                try {
                    val line = File(dir, "status").useLines { lines ->
                        lines.firstOrNull { it.startsWith("Uid:") }
                    } ?: continue
                    val uid = line.substringAfter("Uid:").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                    if (uid == appUid) return true
                } catch (_: Exception) {
                }
            }
            return false
        }

        private fun teardownOverlay() {
            sh("settings put global overlay_display_devices none")
            sh("settings delete global overlay_display_devices")
            sh("settings put global overlay_display_devices \"\"")
            sh("settings delete global overlay_display_devices")
        }

        private fun sh(cmd: String) {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
            } catch (_: Exception) {
            }
        }

        private fun shOut(cmd: String): String {
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                p.waitFor()
                out
            } catch (_: Exception) {
                ""
            }
        }

        private fun commandLoop(appUid: Int) {
            val server = try {
                LocalServerSocket(SOCKET)
            } catch (e: Exception) {
                log("socket fail: ${e.message}")
                exitProcess(0)
            }
            log("socket listening uid=$appUid")
            while (true) {
                var client: LocalSocket? = null
                try {
                    client = server.accept()
                    val uid = try { client.peerCredentials.uid } catch (_: Exception) { -1 }
                    if (appUid > 0 && uid != appUid && uid != 2000 && uid != 0) {
                        client.close()
                        continue
                    }
                    val cmd = client.inputStream.bufferedReader().readLine() ?: continue
                    val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    val out = p.inputStream.readBytes()
                    val err = p.errorStream.readBytes()
                    p.waitFor()
                    client.outputStream.write(out)
                    if (err.isNotEmpty()) client.outputStream.write(err)
                    client.outputStream.flush()
                } catch (e: Exception) {
                    log("cmd: ${e.message}")
                } finally {
                    try { client?.close() } catch (_: Exception) {}
                }
            }
        }

        private fun deliverAll(mirror: IBinder) {
            try { deliverStartService(mirror) } catch (e: Exception) {
                log("startService: ${e.cause?.message ?: e.message}")
            }
        }

        private fun deliverStartService(mirror: IBinder) {
            val am = activityManager() ?: throw IllegalStateException("no AM")
            logMethods(am)
            val extras = Bundle()
            extras.putBinder("b", mirror)
            extras.putParcelable("m", Messenger(mirror))
            val intent = Intent().setClassName(PKG, HOOK)
                .putExtras(extras)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            for (m in am.javaClass.methods.filter { it.name == "startService" }) {
                val pts = m.parameterTypes
                if (pts.none { it == Intent::class.java }) continue
                try {
                    val result = m.invoke(am, *argsFor(pts, intent, extras))
                    log("AM.startService n=${pts.size} result=$result")
                    if (result != null) {
                        delivered = true
                        log("startService accepted")
                        return
                    }
                } catch (e: Exception) {
                    log("AM.startService n=${pts.size}: ${e.cause?.message ?: e.message}")
                }
            }
        }

        private fun deliverBroadcast(mirror: IBinder) {
            val am = activityManager() ?: return
            val extras = Bundle()
            extras.putBinder("b", mirror)
            val intent = Intent("dev.zanderp.innerdesk.PRIV_BINDER").apply {
                setClassName(PKG, RECEIVER)
                setPackage(PKG)
                putExtras(extras)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            }
            for (m in am.javaClass.methods.filter { it.name.startsWith("broadcastIntent") }) {
                val pts = m.parameterTypes
                if (pts.none { it == Intent::class.java }) continue
                try {
                    val result = m.invoke(am, *argsFor(pts, intent, extras))
                    log("AM.${m.name} n=${pts.size} result=$result")
                    if (result != null) return
                } catch (e: Exception) {
                    log("AM.${m.name} n=${pts.size}: ${e.cause?.message ?: e.message}")
                }
            }
        }

        private fun argsFor(pts: Array<Class<*>>, intent: Intent, extras: Bundle?): Array<Any?> {
            val stringIdxs = pts.indices.filter { pts[it] == String::class.java }
            val pkgIdx = when (stringIdxs.size) {
                0 -> -1
                1 -> stringIdxs[0]
                else -> stringIdxs[1]
            }
            val lastInt = pts.indexOfLast { it == Int::class.javaPrimitiveType }
            val args = arrayOfNulls<Any>(pts.size)
            for (i in pts.indices) {
                val p = pts[i]
                args[i] = when {
                    p == Intent::class.java -> intent
                    p == Bundle::class.java -> extras
                    p == String::class.java && i == pkgIdx -> "com.android.shell"
                    p == String::class.java -> null
                    p == Boolean::class.javaPrimitiveType -> false
                    p == Int::class.javaPrimitiveType && i == lastInt -> 0
                    p == Int::class.javaPrimitiveType -> 0
                    p == Long::class.javaPrimitiveType -> 0L
                    p == Array<String>::class.java -> null
                    else -> null
                }
            }
            return args
        }

        private fun logMethods(am: Any) {
            if (loggedSigs) return
            loggedSigs = true
            am.javaClass.methods.filter {
                it.name == "startService" || it.name.startsWith("broadcastIntent")
            }.forEach { m ->
                log("sig ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
        }

        private fun activityManager(): Any? {
            val sm = Class.forName("android.os.ServiceManager")
            val raw = sm.getMethod("getService", String::class.java).invoke(null, "activity") as IBinder
            val stub = Class.forName("android.app.IActivityManager\$Stub")
            return stub.getMethod("asInterface", IBinder::class.java).invoke(null, raw)
        }
    }
}
