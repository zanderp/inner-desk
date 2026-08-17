package dev.zanderp.innerdesk

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer APK. Automatic check at most once a day;
 * failures are silent. Manual check from home / About.
 *
 * F-Droid builds set [BuildConfig.GITHUB_UPDATES] false so we never point those
 * installs at a differently signed GitHub APK.
 */
object UpdateChecker {

    const val REPO = "zanderp/inner-desk"

    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREF = "updates"
    private const val KEY_LAST_CHECK = "lastCheckMs"
    private const val KEY_SKIPPED = "skippedVersion"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val pageUrl: String,
    ) {
        val downloadUrl: String get() = apkUrl ?: pageUrl
    }

    /** Blocking; call off the main thread. Null = nothing newer / offline / rate-limited. */
    fun check(context: Context, manual: Boolean): Release? {
        if (!BuildConfig.GITHUB_UPDATES) return null
        if (!manual && !dueForCheck(context)) return null
        val release = fetch() ?: return null
        markChecked(context)
        if (!isNewer(release.version, BuildConfig.VERSION_NAME)) return null
        if (!manual && release.version == skipped(context)) return null
        return release
    }

    private fun fetch(): Release? = try {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "InnerDesk")
        }
        try {
            if (conn.responseCode != 200) null else {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = json.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apk = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                Release(
                    version = json.optString("tag_name").ifBlank { json.optString("name") },
                    notes = json.optString("body").trim(),
                    apkUrl = apk,
                    pageUrl = json.optString("html_url", "https://github.com/$REPO/releases"),
                )
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    internal fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun dueForCheck(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0L) > CHECK_INTERVAL_MS

    private fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    private fun skipped(context: Context): String? = prefs(context).getString(KEY_SKIPPED, null)

    fun skip(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SKIPPED, version).apply()
    }
}
