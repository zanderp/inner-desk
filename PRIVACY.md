# InnerDesk — Privacy & Permissions

_Last updated: 2026-08-17_

InnerDesk is a **local-first** app. It puts the phone’s own desktop mode (Samsung DeX, Android desktop, or OEM desktop) on this screen. It has **no user accounts and no ads**. Session logs stay on the phone unless *you* share them.

This document explains what the app can access, why, and where data goes.

---

## Summary

- **No account, no sign-in.** The app never asks who you are.
- **Optional anonymous telemetry (default on).** A random UUID, app version, and (when something breaks) a redacted crash/error snippet may be sent to a Cloudflare Worker operated for this project — so we can see roughly how many phones use the app and fix crashes. Turn it off anytime under **About → Privacy → Anonymous usage & crash reports**.
- **Your desktop session stays on the device.** Pairing PINs, logs, and overlay settings are not uploaded as their own fields.
- Other traffic that leaves the phone (not to us as “who you are”): **Wireless debugging** pairing is a local ADB connection on this phone. GitHub-signed builds may query `api.github.com` once a day (or when you tap **Check for update**) to see if a newer APK is on Releases; F-Droid builds skip that. Links you tap in About (Discord, GitHub, Ko-fi, website) open in your browser under those sites’ own terms.

---

## Permissions

### Requested while using the app

| Permission | When | Why | What it does *not* mean |
| --- | --- | --- | --- |
| **Notifications** | First launch (Android 13+) | Required foreground-service status while desktop is on, plus pairing prompts | Not telemetry; notifications stay on the phone |
| **Display over other apps** | If Android asks | Used for the session HUD / controls while desktop is running | Not used to capture other apps’ content |
| **Ignore battery optimizations** | After overlay is granted | Keeps the privileged daemon from being killed while desktop is on | Optional; you can refuse |
| **Accessibility service** | When InnerDesk asks | Draws the desktop fullscreen above the system overlay window (Android caps that window at 50% of the screen) | Not used to read passwords or keystrokes for upload |

### System confirmations and optional access

| Access | When | Why |
| --- | --- | --- |
| **Wireless debugging pairing** | Once per boot | Starts the on-device privileged daemon (`idxprivd`) so InnerDesk can drive desktop mode. The 6-digit PIN is typed in the app and is **not** sent to telemetry |
| **Shizuku** *(optional)* | If you already use Shizuku | Alternate way to start the same daemon. Not required |

### Technical permissions granted by Android

Internet and network-state access (telemetry + opening https links), Wi-Fi multicast (mDNS for wireless debugging pairing), a foreground-service type (special use) and a wake lock so the session can stay alive. Package visibility is declared for DeX / desktop launcher packages and Shizuku so the app can detect what is installed — this does not grant access to your Google account.

---

## Data the app stores on your phone

| Data | Where | Notes |
| --- | --- | --- |
| **Settings** — desktop DPI/size, privacy toggle, pairing state this boot | App-private `SharedPreferences` | Stays in the app sandbox |
| **Anonymous telemetry id** | App-private prefs | Random UUID. Not your name or Google id |
| **Session / crash log** | App-private files (`last_session.log`, `last_crash.txt`) | Rolling technical log. Shared **only** when you tap **Share** (or accept the after-crash prompt) and pick a destination yourself |
| **Telemetry queue** | App-private file | Outbound pings/crashes waiting for network. Cleared if you turn telemetry off |

---

## Anonymous telemetry (optional)

When **Anonymous usage & crash reports** is on (About → Privacy):

| Sent | Not sent |
| --- | --- |
| Random UUID generated on the phone | Name, email, Google account |
| App version / Android API level / UI locale | Wireless debugging PIN, Wi-Fi password |
| Occasional “still installed” heartbeat (~20 hours) | Overlay specs as a dedicated field |
| Redacted crash stack / short error text | Contacts, photos, other apps’ content |

If the phone is offline, events stay in a small local queue and upload later. Turning the toggle **Off** stops uploads and clears the outbound queue.

The ingest service is a dedicated Cloudflare Worker + D1 database for InnerDesk (`innerdesk-telemetry`). Aggregates (unique UUIDs, versions, crash counts) are used for maintenance only. InnerDesk does **not** send data to the OpenCfMoto telemetry worker.

---

## If a permission is denied

The app still opens. Only the related feature is unavailable: without **Accessibility**, the desktop cannot fill the screen; without **Wireless debugging** (or Shizuku) this boot, the privileged daemon cannot start; without **Notifications**, the session service cannot run as a managed foreground session. Telemetry can be turned off independently of those.

---

## Contact

InnerDesk is an independent community project. Questions: [Discord](https://discord.gg/RJFeaetayh) or [GitHub](https://github.com/zanderp/inner-desk).

Samsung, DeX, Google, and Android are trademarks of their respective owners; this project is not affiliated with or endorsed by them.
