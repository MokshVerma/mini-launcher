# Mini Launcher

A minimal, glass-style home screen for Android TV / Google TV, written in plain Java with no libraries.
The whole app is a single custom-drawn view, which keeps memory low (about 25-30 MB on a TCL Google TV).

Built and tested on a TCL Smart TV (Android 14, 2.4 GB RAM, 1080p).

## Features

- Home screen: clock, date, live RAM readout, and one row of apps in a clear glass container.
- Apps are ordered by last use (pinned apps first). Uses Android's usage stats if granted, otherwise
  remembers what you open from the launcher.
- Press Down for the full app grid. The wallpaper fades to a solid colour taken from it.
- Control Center (sliders button, or the remote's Menu key): Wi-Fi, TCL picture settings, theme,
  clock format, next wallpaper, bluetooth/accessories, reload apps, volume and mute.
- Wallpaper: a random public-domain landscape painting from the Cleveland Museum of Art open-access
  API (CC0). A new one is fetched after the TV sleeps or restarts, or on demand from the Control
  Center. Only one image is ever kept on the device.
- Screensaver (`ArtDream`): shows the current painting with the clock and credit.
- Long-press OK pins or unpins an app.

## Build

Requires a JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). No Gradle.

```powershell
./build.ps1
```

This produces `build/minilauncher.apk`, signed with the standard Android debug key.

Release build (needs your own `release.keystore` and `signing.properties` next to `build.ps1`; both are git-ignored):

```powershell
./build.ps1 -Release -Version v0.1.0
```

This writes `dist/mini-launcher-v0.1.0.apk`. A different signing key from the debug one means a TV that has the debug build must uninstall it before installing the release build.

## Install on the TV (wireless debugging)

```bash
adb connect <tv-ip>:<port>
adb install -r build/minilauncher.apk
```

Make it the default home app:

```bash
adb shell cmd package set-home-activity dev.moksh.minilauncher/.MainActivity
```

Optional, so app ordering reflects apps opened from the remote's buttons too:

```bash
adb shell appops set dev.moksh.minilauncher GET_USAGE_STATS allow
```

Use it as the screensaver:

```bash
adb shell settings put secure screensaver_components dev.moksh.minilauncher/dev.moksh.minilauncher.ArtDream
```

Revert the home app to another launcher with the same `set-home-activity` command and that
launcher's component name.

## Artwork

Paintings come from the [Cleveland Museum of Art Open Access API](https://openaccess-api.clevelandart.org/)
and are released under CC0. The credit shown on screen comes from the API.

## Known limitations

- Drawing goes straight to a SurfaceView. On the test TV the app grid scrolls at about 43 fps (a normal view managed about 30). The TV itself tops out near 52 fps even for an empty full-screen surface, so 60 fps is not reachable from app code.
- The Control Center covers only what a normal app can change. Picture presets, backlight and power
  are not available without system permissions.
