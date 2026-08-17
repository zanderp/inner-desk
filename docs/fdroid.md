# F-Droid

InnerDesk is FOSS (AGPL-3.0-or-later) and can go on F-Droid. Play Store is not a fit (accessibility used for the desktop overlay, plus the privileged daemon).

## What we already did in this repo

- Fastlane listing: `fastlane/metadata/android/en-US/`
- F-Droid builds pass `-Pfdroid` so **anonymous telemetry is off until the user turns it on** (avoids the Tracking anti-feature). GitHub release builds stay opt-out.
- Release signing is skipped when `-Pfdroid` is set; F-Droid signs with its own key.
- Recipe to paste: [`fdroid-metadata.yml`](fdroid-metadata.yml)

## How F-Droid gets the app

1. Push Fastlane + the `-Pfdroid` Gradle bits, and make sure tag `v0.8.1` (or the next version) points at that commit.
2. Open a packaging request: [fdroid/rfp](https://gitlab.com/fdroid/rfp/-/issues) **or** a merge request on [fdroiddata](https://gitlab.com/fdroid/fdroiddata) with `metadata/dev.zanderp.innerdesk.yml` copied from `docs/fdroid-metadata.yml`.
3. Wait. New apps often take **weeks to a few months**. Reviewers build from source; they do not ship our GitHub APK.

Local check (optional, needs [fdroidserver](https://f-droid.org/docs/Installing_the_Server_and_Repo_Tools/)):

```bash
./gradlew assembleRelease -Pfdroid
```

## IzzyOnDroid

[IzzyOnDroid](https://apt.izzysoft.de/fdroid/) is a third-party F-Droid repo. It is usually **faster** (days, not months) and picks up **GitHub Releases** (developer-signed APK).

Request: [Codeberg IzzyOnDroid/repodata issues](https://codeberg.org/IzzyOnDroid/repodata/issues) using [`izzyondroid-request.md`](izzyondroid-request.md).

They require a **release signing key** (not the Android debug keystore). Keep `keystore.properties` and `innerdesk-release.jks` backed up off-git.

## Anti-features we are avoiding

| Flag | How |
| --- | --- |
| Tracking | F-Droid build: telemetry default **off** |
| NonFreeNet / TetheredNet | Telemetry is optional; worker source is in this repo |
| NonFreeDep | No Play Services, no Firebase |
