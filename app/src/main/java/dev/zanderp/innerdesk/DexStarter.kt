package dev.zanderp.innerdesk

import android.content.Context

enum class DesktopKind { SAMSUNG, ANDROID }

object DexStarter {

    fun detectKind(context: Context): DesktopKind {
        val mfr = android.os.Build.MANUFACTURER.orEmpty()
        val brand = android.os.Build.BRAND.orEmpty()
        if (mfr.contains("samsung", ignoreCase = true) || brand.contains("samsung", ignoreCase = true)) {
            return DesktopKind.SAMSUNG
        }
        val pm = context.packageManager
        val samsungPkgs = listOf(
            "com.sec.android.app.desktoplauncher",
            "com.sec.android.desktopmode.uiservice",
        ).any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
        return if (samsungPkgs) DesktopKind.SAMSUNG else DesktopKind.ANDROID
    }

    fun kindLabel(kind: DesktopKind): String = when (kind) {
        DesktopKind.SAMSUNG -> "One UI desktop"
        DesktopKind.ANDROID -> "Android desktop"
    }

    fun setDesktopFlags(shell: PrivilegedShell, kind: DesktopKind): String {
        val out = StringBuilder()
        out.append("kind=").append(kind.name).append('\n')
        out.append(shell.run("settings put global force_desktop_mode_on_external_displays 1")).append('\n')
        out.append(shell.run("settings put global force_allow_on_external 1")).append('\n')
        out.append(shell.run("settings put global desktop_mode_enabled 1")).append('\n')
        if (kind == DesktopKind.ANDROID) {
            out.append(shell.run("settings put global enable_freeform_support 1")).append('\n')
            out.append(shell.run("settings put global force_resizable_activities 1")).append('\n')
            out.append(shell.run("settings put global enable_non_resizable_multi_window 1")).append('\n')
        }
        out.append(shell.run("settings put global overlay_display_devices none")).append('\n')
        return out.toString().trim()
    }

    fun startAsExternalMonitor(shell: PrivilegedShell, spec: String): String {
        val out = StringBuilder()
        out.append(shell.run("settings put global force_desktop_mode_on_external_displays 1")).append('\n')
        out.append(shell.run("settings put global force_allow_on_external 1")).append('\n')
        out.append(shell.run("settings put global desktop_mode_enabled 1")).append('\n')
        out.append(shell.run("settings put global overlay_display_devices $spec")).append('\n')
        val got = shell.run("settings get global overlay_display_devices")
        out.append("overlaySetting=$got").append('\n')
        return out.toString().trim()
    }

    fun clearSession(shell: PrivilegedShell): String {
        val out = StringBuilder()
        out.append(shell.run("settings put global overlay_display_devices none")).append('\n')
        out.append(shell.run("settings delete global overlay_display_devices")).append('\n')
        out.append(shell.run("settings put global overlay_display_devices \"\"")).append('\n')
        out.append(shell.run("settings delete global overlay_display_devices")).append('\n')
        out.append(shell.run("settings put global force_desktop_mode_on_external_displays 0")).append('\n')
        out.append(shell.run("settings put global force_allow_on_external 0")).append('\n')
        out.append(shell.run("settings put global desktop_mode_enabled 0")).append('\n')
        out.append(shell.run("settings put global enable_freeform_support 0")).append('\n')
        out.append(shell.run("settings put global enable_non_resizable_multi_window 0")).append('\n')
        out.append(shell.run("settings put secure desktop_mode 0")).append('\n')
        out.append(hideSamsungTouchpad(shell)).append('\n')
        out.append("overlaySetting=").append(shell.run("settings get global overlay_display_devices"))
        return out.toString().trim()
    }

    fun showSamsungTouchpad(shell: PrivilegedShell, overlayDisplayId: Int, overlaySpec: String): String {
        val out = StringBuilder()
        val existing = shell.run("settings get global overlay_display_devices").trim()
        val keep = when {
            overlaySpec.isNotBlank() && overlaySpec != "null" -> overlaySpec
            existing.isNotBlank() && existing != "null" && existing != "none" -> existing
            else -> ""
        }
        out.append(shell.run("settings put global autorun_touchpad 1")).append('\n')
        if (overlayDisplayId >= 0) {
            out.append(shell.run("settings put global dex_touchpad_desktop_display_id $overlayDisplayId")).append('\n')
        }
        out.append(shell.run("settings put system touchpad_enabled 1")).append('\n')
        out.append(
            shell.run("am startservice -n com.android.systemui/com.android.wm.shell.controlpanel.ControlPanelService"),
        ).append('\n')
        out.append(shell.run("am broadcast -a android.intent.action.AUTORUN_FLEX_PANEL")).append('\n')
        out.append(shell.run("am broadcast -a android.intent.action.EXPAND_FLEX_PANEL")).append('\n')
        out.append(
            shell.run(
                "am start --user 0 --display 0 -f 0x14a41000 " +
                    "-n com.android.systemui/com.android.wm.shell.controlpanel.activity.FlexPanelActivity",
            ),
        ).append('\n')
        if (keep.isNotBlank()) {
            repeat(8) {
                Thread.sleep(250)
                val now = shell.run("settings get global overlay_display_devices").trim()
                if (now != keep) {
                    out.append("overlay stolen ($now), restoring $keep\n")
                    shell.run("settings put global overlay_display_devices $keep")
                }
            }
        }
        return out.toString().trim()
    }

    fun collapseFlexPanel(shell: PrivilegedShell): String =
        shell.run("am broadcast -a android.intent.action.COLLAPSE_FLEX_PANEL")

    fun hideSamsungTouchpad(shell: PrivilegedShell): String {
        val out = StringBuilder()
        out.append(collapseFlexPanel(shell)).append('\n')
        out.append(shell.run("settings put system touchpad_enabled 0")).append('\n')
        return out.toString().trim()
    }

    fun overlayDisplayId(context: Context, preferW: Int = 0, preferH: Int = 0): Int? {
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val all = buildList {
            addAll(dm.displays)
            try {
                addAll(dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
            } catch (_: Exception) {
            }
        }.distinctBy { it.displayId }
        val others = all.filter { it.displayId != android.view.Display.DEFAULT_DISPLAY }
        val named = others.filter { it.name.contains("overlay", true) }
        val pool = named.ifEmpty { others.filter { it.displayId != 1 } }
        if (preferW > 0 && preferH > 0) {
            pool.firstOrNull { it.width == preferW && it.height == preferH }?.displayId
                ?: pool.firstOrNull { it.width == preferH && it.height == preferW }?.displayId
        } else {
            null
        }?.let { return it }
        return named.firstOrNull()?.displayId ?: pool.firstOrNull()?.displayId
    }
}
