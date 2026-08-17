package dev.zanderp.innerdesk

import android.content.Context

object PrivacyPrefs {
    private const val KEY = "anonymous_telemetry"

    fun telemetryEnabled(ctx: Context): Boolean =
        AppSession.prefs(ctx).getBoolean(KEY, BuildConfig.TELEMETRY_DEFAULT)

    fun setTelemetryEnabled(ctx: Context, on: Boolean) {
        AppSession.prefs(ctx).edit().putBoolean(KEY, on).apply()
        if (!on) AnonymousTelemetry.onDisabled(ctx)
    }
}
