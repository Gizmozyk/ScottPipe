# Testing

## Local Android SDK

This machine has no system-wide Android SDK. For manual/emulator testing,
one was bootstrapped at `~/android-sdk-local` (cmdline-tools + `sdkmanager`
install of `platform-tools`, `platforms;android-37.0`, `build-tools;37.0.0`,
`emulator`, and `system-images;android-35;google_apis;x86_64` — see
`.claude/hooks/session-start.sh` for the equivalent provisioning used in
Claude Code's remote sandbox). A JDK 21 is also required (`java`/`kotlin`
toolchain — see `app/build.gradle.kts`'s `kotlin { jvmToolchain(21) }`).

## Emulator

AVD name: `scottpipe-test` (Pixel 6 profile, Android 35, `google_apis`,
x86_64). Boot headless:

```
emulator -avd scottpipe-test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
```

**Gotcha — state doesn't survive a restart.** This AVD loads a quick-boot
snapshot (`default_boot`) on every launch rather than a true cold boot, and
that snapshot predates any app installs done in earlier sessions. In
practice this means: after any emulator restart, `pm list packages` and app
data (installed APK, Room DB, SharedPreferences, PIN, etc.) all silently
revert, and previously-granted runtime permissions get re-prompted.
**Always `adb install -r` the current debug APK again after every emulator
(re)start** before testing — don't assume state carried over from earlier
in the session.

**Gotcha — real video playback can crash the emulator.** Twice during Kid
Mode testing, playing a real (live) video — actual decode + software GL
rendering via `swiftshader_indirect` — hung QEMU's main loop and CPU
threads for 15s+, and the process died (`detected a hanging thread ...`,
`bad color buffer handle` in the emulator log). This reproduced consistently
under host load and is an emulator/host limitation, not an app bug. When a
test doesn't specifically need to confirm playback starts, navigate to
whatever's needed (e.g. a channel page) via **search** instead of tapping
into a video, to avoid triggering decode. Only trigger real playback when
that's specifically what's being verified.

## Driving the UI from the command line

Prefer `adb shell uiautomator dump` over guessing tap coordinates from a
screenshot's displayed pixel size — the display-vs-original scale factor is
easy to get wrong and wastes turns on missed taps. Workflow:

```
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml .
grep -o '<node[^>]*text="Some Label"[^>]*>' ui.xml   # find the element
# use its bounds="[x1,y1][x2,y2]" — if clickable="false", search nearby
# for the smallest clickable="true" ancestor bounds and tap its center
adb shell input tap <centerX> <centerY>
```

`uiautomator dump` occasionally returns `ERROR: null root node` transiently
(e.g. mid-transition) — just retry after a short sleep.

## Instrumented tests (androidTest)

DB/Context-dependent tests (Room DAOs, migrations, `KidModePinManager`'s use
of the real Android Keystore) live under `app/src/androidTest`, not
`app/src/test` — they need a real device/emulator, not a plain JVM. Run a
filtered subset against a connected device/emulator:

```
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="com.foo.TestA,com.foo.TestB"
```

## Kid Mode manual verification (Phase A)

Full walkthrough performed and passing as of 2026-07-23, on-device against
real (live) YouTube data:

- Enable Kid Mode, first-time PIN setup dialog appears and works
- Tapping a video from an unsubscribed channel blocks it and shows the
  approval dialog
- Wrong PIN on "Approve as parent" is rejected; correct PIN approves and
  playback actually starts
- Tapping Subscribe on an unsubscribed channel blocks it with the approval
  dialog showing the correct channel name
- Correct PIN approves the subscribe request and a real subscription is
  created (button flips to "SUBSCRIBED")
- Re-entering the Kid Mode settings screen (once a PIN exists) requires the
  PIN before the toggle is interactable
- Turning Kid Mode off requires the PIN; wrong PIN is rejected, correct PIN
  disables it

See [features/kid-mode.md](features/kid-mode.md) and
[adr/0001-kid-mode-architecture.md](adr/0001-kid-mode-architecture.md).

## Kid Mode HTTP server manual verification (Phase B)

The embedded server binds to loopback only (see the ADR), so reach it from
the host via `adb forward` rather than the emulator's own IP:

```
adb forward tcp:46821 tcp:46821   # 46821 = ApprovalHttpServer.DEFAULT_PORT
curl http://localhost:46821/ping
curl http://localhost:46821/pending-requests
curl -X POST http://localhost:46821/approve/<id>
curl -X POST http://localhost:46821/deny/<id>
```

With Kid Mode enabled, a persistent "Kid mode is on" notification should be
visible (`adb shell dumpsys notification --noredact | grep -A5 newpipeKidMode`
or just check the notification shade) — confirms `KidModeServerService`
is actually running as a foreground service, not just that the port
happens to respond. Trigger a gated action from the Phase A flow first so
`/pending-requests` has something to show; approving via `curl` should
make the kid device's still-open waiting dialog react exactly like the
local PIN button does (dismiss, and for a subscribe request the
subscription should actually appear) — this is the real proof that the
HTTP path and the local path share the same completion logic
(`KidModeGate.approve()`/`.deny()`), not two independent implementations.
