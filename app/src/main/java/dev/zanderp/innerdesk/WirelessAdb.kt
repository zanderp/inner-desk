package dev.zanderp.innerdesk

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WirelessAdb private constructor(context: Context) : AbsAdbConnectionManager() {
    private val app = context.applicationContext
    private val keyFile = File(app.filesDir, "adbkey")
    private val certFile = File(app.filesDir, "adbkey.crt")
    private val privateKey: PrivateKey
    private val certificate: Certificate
    private var shellStream: AdbStream? = null
    private var leftover = ""

    init {
        setApi(Build.VERSION.SDK_INT)
        val loaded = loadKeys()
        if (loaded != null) {
            privateKey = loaded.first
            certificate = loaded.second
        } else {
            val generated = generateKeys()
            privateKey = generated.first
            certificate = generated.second
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "InnerDesk"

    fun pairAt(host: String, port: Int, pin: String) {
        resetShell()
        try { disconnect() } catch (_: Exception) {}
        live = false
        pair(host, port, pin.trim())
        paired = true
        AppSession.appendLog("Wireless debugging: paired at $host:$port")
    }

    fun connectWireless(): Boolean {
        val wifi = app.getSystemService(android.net.wifi.WifiManager::class.java)
        val lock = try {
            wifi?.createMulticastLock("innerdesk-adb")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }
        return try {
            setThrowOnUnauthorised(false)
            setTimeout(8, TimeUnit.SECONDS)
            repeat(6) { attempt ->
                resetShell()
                try { if (isConnected()) disconnect() } catch (_: Exception) {}
                if (tryConnect(attempt)) {
                    live = true
                    paired = true
                    return true
                }
                Thread.sleep(500)
            }
            live = false
            AppSession.appendLog("Wireless debugging: connect failed after retries.")
            false
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
    }

    private fun shellWorks(): Boolean {
        return try {
            Thread.sleep(250)
            val id = shell("id")
            AppSession.appendLog("Wireless debugging: shell ${id.trim()}")
            id.contains("uid=2000") || id.contains("shell")
        } catch (e: Exception) {
            AppSession.appendLog("Wireless debugging: shell probe ${e.message}")
            try { disconnect() } catch (_: Exception) {}
            false
        }
    }

    private fun tryConnect(attempt: Int): Boolean {
        val found = discoverConnect(3_000L)
        val hosts = buildList {
            found?.first?.let { add(it) }
            add("127.0.0.1")
        }.distinct()
        val ports = buildList {
            found?.second?.let { add(it) }
            lastConnectPort.takeIf { it > 0 }?.let { add(it) }
            tlsPortFromProps()?.let { add(it) }
        }.distinct()
        if (ports.isEmpty()) {
            return try {
                val ok = connectTls(app, 4_000L)
                AppSession.appendLog("Wireless debugging: connectTls attempt ${attempt + 1} ok=$ok")
                ok && shellWorks()
            } catch (e: Exception) {
                AppSession.appendLog("Wireless debugging: connectTls ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
        for (host in hosts) {
            for (port in ports) {
                try {
                    if (connect(host, port)) {
                        AppSession.appendLog("Wireless debugging: tls $host:$port")
                        if (shellWorks()) {
                            lastConnectHost = host
                            lastConnectPort = port
                            AppSession.appendLog("Wireless debugging: connected $host:$port")
                            return true
                        }
                    } else {
                        AppSession.appendLog("Wireless debugging: connect $host:$port returned false")
                    }
                } catch (e: Exception) {
                    AppSession.appendLog("Wireless debugging: connect $host:$port ${e.javaClass.simpleName}: ${e.message}")
                    resetShell()
                    try { disconnect() } catch (_: Exception) {}
                }
            }
        }
        return false
    }

    private fun discoverConnect(timeoutMs: Long): Pair<String, Int>? {
        val latch = CountDownLatch(1)
        var host: String? = null
        var port = -1
        val mdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { address, p ->
            if (p > 0) {
                host = address?.hostAddress ?: "127.0.0.1"
                port = p
                latch.countDown()
            }
        }
        mdns.start()
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (port > 0) (host ?: "127.0.0.1") to port else null
        } finally {
            try { mdns.stop() } catch (_: Exception) {}
        }
    }

    private fun tlsPortFromProps(): Int? {
        val keys = listOf("service.adb.tls.port", "persist.adb.tls.port")
        for (key in keys) {
            val raw = try {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, key) as String
            } catch (_: Exception) {
                ""
            }
            raw.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return null
    }

    fun startDaemon(): String {
        if (!connectWireless()) {
            return "wireless connect failed"
        }
        return try {
            spawnDaemon()
        } catch (e: Exception) {
            AppSession.appendLog("Wireless spawn: ${e.message}")
            resetShell()
            if (!reconnectFast() && !connectWireless()) {
                return "wireless reconnect failed: ${e.message}"
            }
            try {
                spawnDaemon()
            } catch (e2: Exception) {
                "daemon spawn failed: ${e2.message}"
            }
        }
    }

    fun spawnDaemon(): String {
        val watch = "/data/local/tmp/idx-shizuku-watch.sh"
        val log = "/data/local/tmp/idx-shizuku-watch.log"
        val start = shell(
            """
            trap '' HUP
            APK=${'$'}(pm path dev.zanderp.innerdesk | head -1 | cut -d: -f2)
            APP_UID=${'$'}(pm list packages -U dev.zanderp.innerdesk | head -1)
            APP_UID=${'$'}{APP_UID##*uid:}
            APP_UID=${'$'}{APP_UID%%,*}
            APP_UID=${'$'}{APP_UID%% *}
            [ -n "${'$'}APP_UID" ] || APP_UID=0
            echo APK=${'$'}APK UID=${'$'}APP_UID
            unzip -p "${'$'}APK" assets/idx-shizuku-watch.sh > $watch
            chmod 700 $watch
            rm -f /data/local/tmp/idx-shizuku-watch.pid
            echo "${'$'}(date) spawn-from-app uid=${'$'}APP_UID" >> $log
            CLASSPATH="${'$'}APK" nohup setsid /system/bin/app_process /system/bin --nice-name=idxprivd dev.zanderp.innerdesk.PrivDaemon ${'$'}APP_UID </dev/null >>/data/local/tmp/idx-priv.log 2>&1 &
            nohup setsid /system/bin/sh $watch </dev/null >>$log 2>&1 &
            sleep 1.2
            pidof idxprivd || true
            """.trimIndent(),
        )
        AppSession.appendLog("Wireless debugging: start=${start.replace("\n", " | ").take(400)}")
        android.util.Log.e("InnerDesk", "spawn start=$start")
        if (start.contains("APK=") && !start.contains("APK=/data/")) {
            return "daemon spawn failed: no apk path ${start.take(180)}"
        }
        repeat(20) {
            if (AppSession.overlayMirror.pingAlive()) return "daemon ready"
            Thread.sleep(400)
        }
        val pids = try { shell("pidof idxprivd || true") } catch (_: Exception) { "" }
        val hint = try { shell("tail -8 $log") } catch (_: Exception) { "" }
        if (pids.isNotBlank()) {
            AppSession.overlayMirror.ensureBound()
            if (AppSession.overlayMirror.pingAlive()) return "daemon ready"
        }
        return "daemon started, waiting for binder pids=$pids ${hint.take(220)}"
    }

    fun shell(command: String): String {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return runInShell(command)
            } catch (e: Exception) {
                last = e
                AppSession.appendLog("Wireless shell retry ${attempt + 1}: ${e.message} cmd=${command.lineSequence().first().take(80)}")
                resetShell()
                if (attempt == 2) throw e
                if (!isConnected() && !reconnectFast()) throw e
            }
        }
        throw last ?: IOException("shell failed")
    }

    private fun runInShell(command: String): String {
        val stream = shellStream ?: openShellSession().also { shellStream = it }
        val stdin = stream.openOutputStream()
        val stdout = stream.openInputStream()
        val marker = "IDXE${System.nanoTime()}"
        stdin.write("$command\necho $marker\n".toByteArray(Charsets.UTF_8))
        stdin.flush()
        return readUntilMarker(stdout, marker, 20_000).trim()
    }

    private fun openShellSession(): AdbStream {
        leftover = ""
        val stream = openStream("shell:")
        val stdin = stream.openOutputStream()
        val stdout = stream.openInputStream()
        stdin.write("export PS1=\nexport PS2=\nunset PROMPT_COMMAND\necho IDX_READY\n".toByteArray(Charsets.UTF_8))
        stdin.flush()
        readUntilMarker(stdout, "IDX_READY", 8_000)
        return stream
    }

    private fun readUntilMarker(stdout: InputStream, marker: String, timeoutMs: Long): String {
        val buf = ByteArray(4096)
        val out = StringBuilder(leftover)
        leftover = ""
        val deadline = System.currentTimeMillis() + timeoutMs
        val line = Regex("(?m)^$marker$")
        while (System.currentTimeMillis() < deadline) {
            val available = try {
                stdout.available()
            } catch (e: Exception) {
                val match = line.find(out.toString())
                if (match != null) {
                    leftover = out.substring(match.range.last + 1).trimStart('\n')
                    return out.substring(0, match.range.first)
                }
                throw e
            }
            val n = if (available > 0) {
                stdout.read(buf, 0, minOf(buf.size, available))
            } else {
                Thread.sleep(40)
                0
            }
            if (n < 0) throw IOException("Stream closed")
            if (n > 0) out.append(String(buf, 0, n, Charsets.UTF_8).replace("\r", ""))
            val text = out.toString()
            val match = line.find(text)
            if (match != null) {
                leftover = text.substring(match.range.last + 1).trimStart('\n')
                return text.substring(0, match.range.first)
            }
        }
        throw IOException("timeout waiting for $marker got=${out.toString().take(240)}")
    }

    private fun resetShell() {
        leftover = ""
        try { shellStream?.close() } catch (_: Exception) {}
        shellStream = null
    }

    private fun reconnectFast(): Boolean {
        resetShell()
        try { disconnect() } catch (_: Exception) {}
        val host = lastConnectHost
        val port = lastConnectPort
        if (port <= 0) return false
        return try {
            setTimeout(8, TimeUnit.SECONDS)
            val ok = connect(host, port)
            if (ok) {
                live = true
                paired = true
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun loadKeys(): Pair<PrivateKey, Certificate>? {
        if (!keyFile.isFile || !certFile.isFile) return null
        return try {
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certFile.inputStream())
            key to cert
        } catch (_: Exception) {
            null
        }
    }

    private fun generateKeys(): Pair<PrivateKey, Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val pair = kpg.generateKeyPair()
        val now = Date()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=InnerDesk"),
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000),
            X500Name("CN=InnerDesk"),
            pair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider())
            .build(pair.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider())
            .getCertificate(builder.build(signer))
        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(cert.encoded)
        return pair.private to cert
    }

    companion object {
        @Volatile var live: Boolean = false
        @Volatile var paired: Boolean = false
        @Volatile var lastConnectHost: String = "127.0.0.1"
        @Volatile var lastConnectPort: Int = -1

        @Volatile private var instance: WirelessAdb? = null

        fun get(context: Context): WirelessAdb {
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
            return instance ?: WirelessAdb(context.applicationContext).also { instance = it }
        }

        fun discoverPairing(context: Context, onFound: (String, Int) -> Unit): AdbMdns {
            val mdns = AdbMdns(context.applicationContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host: InetAddress?, port: Int ->
                val ip = host?.hostAddress ?: "127.0.0.1"
                if (port > 0) onFound(ip, port)
            }
            mdns.start()
            return mdns
        }
    }
}
