# IzzyOnDroid AppRequest (paste at Codeberg)

File at: https://codeberg.org/IzzyOnDroid/repodata/issues/new?template=app-request.yaml
(If the template picker is missing, paste the body below into a new issue titled `[AppRequest] InnerDesk`.)

Official F-Droid RFP (separate, slower): https://gitlab.com/fdroid/rfp/-/issues/new

---

### Guidelines

- [x] I am the developer of the app. (If not, please explain the developer's stance on this inclusion request in the Further Notices section.)
- [x] The app complies with the [App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/).
- [x] The app is not already listed in the repo or issue tracker.
- [x] The [Fastlane](https://izzyondroid.org/docs/general/Fastlane/) folder is available in the app's repo.

### Link to the source code

https://github.com/zanderp/inner-desk

### Link to app in another app store

GitHub Releases: https://github.com/zanderp/inner-desk/releases
F-Droid official: packaging request planned / in progress (they build from source with their own key)

### License used

AGPL-3.0-or-later

### Categories

System

### Summary

DeX or Android desktop on this phone's own screen

### Description

InnerDesk puts your phone’s own desktop mode on this screen — Samsung DeX, Android desktop, or OEM desktop. Pair once per boot with Wireless debugging (Shizuku works too), tap Start desktop, and the desktop fills the phone. Unfolded Folds go fullscreen; tabletop splits desktop and a trackpad. Regular phones work in portrait or landscape.

Independent community project. Not affiliated with Samsung or Google.

### Build instructions

GitHub/IzzyOnDroid APK (developer-signed):

```
./gradlew assembleRelease
```

Needs a local `keystore.properties` (not in git) pointing at the release keystore. Output: `app/build/outputs/apk/release/app-release.apk`

F-Droid flavor (unsigned, telemetry opt-in):

```
./gradlew assembleRelease -Pfdroid
```

### Assistance Level

Moderate – specific tasks or modules

### "AI" Tool(s)

Cursor (coding agent)

### What did the tools help with?

Implementation assistance under maintainer direction (UI, Gradle, listing metadata). Product behavior is specified and tested on device by the maintainer.

### AI Accountability

- [x] The human developer(s) reviewed and edited all "AI"-generated outputs
- [x] The human developer(s) ran manual tests and manually verified all changes

### Further Notices

I am the developer (GitHub: zanderp).

- APK is on GitHub Releases (`InnerDesk.apk`, arm64, ~12 MB, R8).
- Optional anonymous usage/crash reports (random UUID, Cloudflare Worker whose source is in this repo). GitHub builds default on (toggle in About → Privacy). F-Droid builds pass `-Pfdroid` so that is **off until the user opts in**.
- The app uses an accessibility overlay to draw the desktop fullscreen, and a privileged on-device daemon started via Wireless debugging pairing (or Shizuku). No root. No Play Services.
- Signing cert SHA-256: `C2:3D:B1:7C:07:74:7B:60:A7:77:8E:9F:A0:49:75:0F:72:9B:24:8A:B9:DB:F5:52:D1:83:A1:C2:A8:65:EE:34`
