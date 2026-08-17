package dev.zanderp.innerdesk

object BinderShell : PrivilegedShell {
    override fun isAvailable(): Boolean = AppSession.overlayMirror.pingAlive()

    override fun run(command: String): String {
        return try {
            val raw = exec(command)
            "ok ${raw.replace('\n', ' ').trim().take(180)} exit=0"
        } catch (e: Exception) {
            "fail ${e.message}"
        }
    }

    override fun runQuiet(command: String) {
        try { exec(command) } catch (_: Exception) {}
    }

    private fun exec(command: String): String {
        val svc = AppSession.overlayMirror.getMirrorService()
            ?: throw IllegalStateException("no priv binder")
        return svc.exec(command) ?: ""
    }
}
