# Set up a TV with an AI assistant

A ready-made prompt for [Claude Code](https://claude.com/claude-code) (or a similar coding assistant that can
run shell commands). It walks through installing Mini Launcher on an Android TV / Google TV over wireless
debugging, speeding the TV up, and disabling unwanted background apps, **asking before every change** and
**backing everything up first** with a tested rollback script.

How to use it:

1. Open the assistant on a PC that is on the same network as the TV and has
   [`adb`](https://developer.android.com/tools/releases/platform-tools) installed.
2. Paste everything inside the box below as your first message.
3. Answer its questions. It will not change anything on the TV until you approve that specific change.

It was written for the TCL Smart TV Pro (device `G08_4K_GB`, Google TV on Android 14) that Mini Launcher was
built on. The disable-apps phase has not been tested on another TV, so treat it as a guided checklist and
read each suggestion before approving it.

---

````
You are helping me speed up my Android TV and replace its home screen with "Mini Launcher", a minimal
open-source launcher: https://github.com/MokshVerma/mini-launcher
Work from my PC over ADB wireless debugging. You may only change the TV AFTER I explicitly approve that
specific change in chat. Reading information from the TV is always fine.

## The TV (it is the same model the project was built and tested on)
TCL Smart TV Pro ("Smart_TV_Pro", device G08_4K_GB), Google TV on Android 14 (API 34), about 2.4 GB RAM,
4K panel with a 1920x1080 display size, MediaTek MT5879 with a Mali GPU. Confirm this in Phase 2 and tell me
if anything differs. Known facts from that TV: the launcher settles at about 25 to 35 MB; the app grid scrolls
at about 43 fps, and the hardware tops out near 52 fps, so do not promise 60.

## Ground rules
1. Ask before every change to the TV: installing the launcher, changing the default home app, disabling any
   package, changing animation speed, changing the screensaver, granting a permission, rebooting.
   Say exactly what you will run, what it does, and the exact command that undoes it.
2. Only reversible changes. Allowed: `pm disable-user --user 0 <pkg>` (undo `pm enable <pkg>`),
   `settings put ...` after recording the old value, `appops set ...`, `cmd package set-home-activity ...`,
   installing the launcher APK. NEVER: factory reset, `pm uninstall` of system apps, root tricks, `rm` on the TV,
   changing accounts or passwords, changing security settings, or anything else without asking me first.
3. Never type my passwords or sign into any account. Downloads need my OK: tell me the filename, source and size.
4. Be honest. If something fails or you could not verify it, say so. Show before/after numbers.
5. Make changes in small batches (at most 5 at a time) and test the TV after every batch.
6. Do not start Phase 3 or later until the backup in Phase 2B exists and the rollback script has been dry-run.

## Phase 0: PC prerequisites
- Check `adb version`. If missing, tell me how to get Google's "platform-tools" and wait for me.
- Use the shell for my operating system (PowerShell on Windows, bash on macOS/Linux) for everything below.
- You do NOT need Java or the Android SDK. Use the prebuilt APK from the GitHub release (Phase 4).
- Create a working folder `tv-setup/` and tell me where it is. Everything you save goes in it.

## Phase 1: Connect to the TV
Ask me to turn on, on the TV: Settings > System > About, tap "Build" 7 times to unlock Developer options;
then Developer options > USB debugging ON and Wireless debugging ON. The TV and this PC must be on the same network.
- First time only: Wireless debugging > "Pair device with pairing code". Ask me for the IP:port and the
  6-digit code, then `adb pair <ip>:<pair-port>` and enter the code.
- Then `adb mdns services` shows the connect port (it is NOT the pairing port): `adb connect <ip>:<connect-port>`.
  Confirm with `adb devices -l`.
- Known behaviour: the connect port changes after the TV sleeps or restarts, and the connection drops when the
  TV sleeps. If it drops, ask me to wake the TV with the remote, then re-run `adb mdns services` and reconnect.
  Do not retry in a loop. Never put the TV to sleep with a key event: you cannot wake it from here.
- If a command is rejected as unauthorised, ask me to accept the "Allow USB debugging?" prompt on the TV.

## Phase 2: Audit (read-only)
Show me a short summary:
- Device: `getprop ro.product.model`, `ro.build.version.release`, `ro.build.version.sdk`, `wm size`.
- Compatibility gate: `pm has-feature android.software.leanback` must be true and SDK 21 or higher. If it is not
  an Android TV / Google TV, STOP and tell me why.
- Memory baseline ("before"): `grep -E 'MemTotal|MemAvailable|MemFree' /proc/meminfo` and the per-process PSS list
  from `dumpsys meminfo` (top 25).
- Current home app: `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`.
- Packages: `pm list packages -3`, `pm list packages -s`, `pm list packages -d`.

## Phase 2B: Backup and rollback (mandatory, before any change)
1. Create `tv-setup/backup-<date-time>/` and save, as plain text files:
   - `home_component.txt` (the current home activity, e.g. com.xxx/.MainActivity)
   - `packages_all.txt`, `packages_user.txt`, `packages_disabled.txt` (from `pm list packages ...`)
   - `settings_system.txt`, `settings_secure.txt`, `settings_global.txt` (from `adb shell settings list <namespace>`)
   - the exact old values of these keys, read with `settings get`:
     secure `screensaver_components`, `screensaver_enabled`, `screensaver_activate_on_sleep`,
     global `window_animation_scale`, `transition_animation_scale`, `animator_duration_scale`.
     IMPORTANT: `settings get` prints the text `null` when a key is not set. Record that fact. On rollback, a key
     that was `null` must be removed with `settings delete <namespace> <key>`, not set to the word "null".
   - `meminfo_before.txt` (the full `dumpsys meminfo`) and `meminfo_summary_before.txt`
   - `device.txt` (model, build fingerprint `getprop ro.build.fingerprint`, Android version)
2. Check `pm list packages` for `dev.moksh.minilauncher` and record whether it is already installed.
3. Generate `tv-setup/rollback.ps1` (or `rollback.sh`) that, run with the TV connected, does these in this order:
   a. Restore the home app: `cmd package set-home-activity <contents of home_component.txt>`
   b. Restore the screensaver and its two related settings (use `settings delete` for keys that were `null`)
   c. Restore the three animation scales (same `null` rule)
   d. Revoke the launcher's usage access: `appops set dev.moksh.minilauncher GET_USAGE_STATS default`
   e. Re-enable every package that is now disabled but was NOT in `packages_disabled.txt`
      (compute the difference at run time with `pm list packages -d`; do not hard-code it)
   f. Ask "Also uninstall Mini Launcher?" and only then run `adb uninstall dev.moksh.minilauncher`
   g. Print a final check: current home app, screensaver value, number of disabled packages vs the backup.
   The script must have a `-WhatIf` / `--dry-run` mode that prints each command without running it.
4. Run the dry-run and show me the output. Then PROVE the mechanism with a harmless real test: with my OK,
   set `window_animation_scale` to 0.5, run the matching rollback step, and confirm the value is back
   (or deleted, if it was `null`). Report the result. Do not continue if this test fails.
5. Before changing the home app, ask me to confirm a way to reach TV Settings without the launcher, for
   example the remote's gear/settings button, or voice ("open settings"). If I have none, explain the risk and
   ask me again before continuing.
6. Keep a running `tv-setup/tv-changes.md` of every change from now on: time, what, old value, undo command.
   After each change batch, copy the list of packages currently disabled to `tv-setup/checkpoint-<n>.txt`.

## Phase 3: Ask me what I want (a checklist; wait for my answers)
1. Install Mini Launcher and make it the default home screen?
2. Disable unwanted background apps/services? (I will review a list in Phase 5.)
3. Speed up animations (set the three animation scales to 0.5)?
4. Use the launcher's screensaver (shows the current painting and the clock)?
5. Let the launcher read "usage access" so apps are ordered by real last use, including remote-button launches?
Also ask: do you want a restart test at the end?

## Phase 4: Install the launcher (only if I said yes to 1)
1. Read `https://api.github.com/repos/MokshVerma/mini-launcher/releases/latest`. Tell me the APK name, size
   (about 40 KB) and sha256, and ask permission to download it. Download it, verify the sha256 matches, then
   `adb install -r <apk>`. (Package dev.moksh.minilauncher, activity .MainActivity.)
2. Set it as the default home (the old one is already backed up):
   `adb shell cmd package set-home-activity dev.moksh.minilauncher/.MainActivity`
3. If I said yes: `adb shell appops set dev.moksh.minilauncher GET_USAGE_STATS allow` and
   `adb shell settings put secure screensaver_components dev.moksh.minilauncher/dev.moksh.minilauncher.ArtDream`
4. Test: open Settings, press Home (`adb shell input keyevent KEYCODE_HOME`): the launcher must appear. Take a
   screenshot (`adb shell screencap -p /sdcard/t.png`, then `adb pull`) and show me. Check
   `adb logcat -d -s AndroidRuntime:E` has no fatal exception. The first start downloads a painting and needs
   internet: wait 15 seconds before judging. The launcher should settle at 25 to 35 MB
   (`dumpsys meminfo dev.moksh.minilauncher | grep 'TOTAL PSS'`). A brief one-second spike at start is normal.
5. If the launcher crashes or the screen is black: IMMEDIATELY run rollback step (a) and tell me.
6. Never disable or uninstall the TV's original launcher. It is the fallback.

## Phase 5: Disable unwanted processes (only if I said yes to 2)
Build the candidate list from evidence: running processes from `dumpsys meminfo`, plus pre-installed
user-facing apps. For each one look up what it is (label, package name, `dumpsys package <pkg>`).
Group them: (A) probably unused, (B) unsure, (C) do not touch. Present A and B as a numbered table: package,
plain description, memory used, what stops working if disabled, and the undo command. I choose the numbers.

Packages seen on this TV model that you should EXAMINE FIRST. I have not disabled any of them, so what they
do and what breaks is unverified. Check each, explain it to me, and let me decide:
`com.tcl.esticker`, T-Exhibition, T-solo, TCL Channel, TCL HOME, TCL Home Passive, Messagebox, MagiConnect,
Safety Guard, Quick panel, `com.tcl.gallery`. Notes: Quick panel and MagiConnect may power quick settings and
screen casting; `com.tcl.gallery` also provides a screensaver service; do not assume any of them is safe.

NEVER propose disabling anything in group C, including: `android`, `com.android.systemui`,
`com.android.tv.settings` (TCL's Picture and Network pages run inside it, and the launcher's Control Center
opens them), `com.android.providers.*`, the package installers, permission controller, `com.google.android.gms`
and `gsf`, the Play Store, WebView, the keyboard/input method, the TV input framework, tuner, HDMI/CEC and
live-TV services, Bluetooth and Wi-Fi/network services, anything that receives the remote or the voice button,
vendor display/picture/audio services (anything containing `display_service`, `mediatek`, `mstar`, `realtek`,
`amlogic`), and the original launcher (`com.google.android.tvlauncher`, `com.google.android.apps.tv.launcherx`,
or the vendor home). If you do not clearly understand a package, put it in group C or ask me.

Per batch (max 5):
1. Append each package and its undo command to `tv-changes.md`.
2. `adb shell pm disable-user --user 0 <pkg>`; save `checkpoint-<n>.txt`.
3. Test: Home works, remote buttons work, volume works, Wi-Fi is connected, switching to an HDMI input and back
   works, one streaming app plays. Ask me to confirm the TV behaves normally.
4. If anything misbehaves: `adb shell pm enable <pkg>` for the whole last batch, re-test, and tell me.

Optional (if I said yes to 3): `adb shell settings put global window_animation_scale 0.5` and the same for
`transition_animation_scale` and `animator_duration_scale`.

## Phase 6: Verify and report
- Repeat the Phase 2 memory measurements. Show a before/after table: MemAvailable, number of running
  processes (`ps -A | wc -l`), and the launcher's own PSS.
- Re-test: Home key from three different apps; the Control Center opens (Up from the first row, then OK); the
  app grid opens (Down) and scrolls without crashing; the screensaver setting reads back correctly.
- If I agreed to a restart test: ask again, then `adb reboot`. After it boots, reconnect (new port) and confirm
  the home app is still the launcher. If the TV reset it, tell me and offer to set it again.
- Finish with a plain list: what was changed, where the backup is, how to run `rollback` and `rollback -WhatIf`.

## Emergency recovery (read this to me before Phase 4)
- Launcher crashes, black screen, or unusable: from the PC, `adb connect` and run rollback step (a):
  `adb shell cmd package set-home-activity <contents of home_component.txt>`. Then `adb uninstall dev.moksh.minilauncher`
  if needed.
- Something I disabled broke a feature: `adb shell pm enable <pkg>` for the last batch, or run the whole rollback.
- ADB will not connect: wake the TV with the remote. If it is still unreachable, unplug the TV from power for
  30 seconds. Wireless debugging may be off after that; use the remote to turn it on again in Developer
  options, then `adb mdns services` for the new port.
- If the TV still will not start normally, stop and tell me. Do NOT factory reset on your own; that is my decision.

## Pitfalls already learned (avoid them)
- Judge speed from numbers, not screenshots.
- A launcher update signed with a different key must be uninstalled first, which clears pinned apps.
- Wireless debugging port changes after sleep or restart.
- The launcher orders apps by real usage only if usage access is granted.
````
