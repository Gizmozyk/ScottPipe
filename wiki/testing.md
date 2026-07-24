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

**Gotcha — no backtick-quoted test names with spaces in `androidTest`.**
Plain JVM tests (`app/src/test`) can use Kotlin's `` fun `like this`() ``
test-name style freely (see `KidModeHmacTest.kt`), but `androidTest`
compiles to a real DEX-based APK for on-device install, and D8 rejects
spaces in method names below DEX version 040
(`Space characters in SimpleName '...' are not allowed prior to DEX
version 040`). Use plain camelCase method names in `androidTest` instead.


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

Verified as of 2026-07-23 when the server was loopback-only and
`/pending-requests`/`/approve`/`/deny` had no auth (both since changed by
Phase C — see the next section for the current workflow):
`adb forward` + plain `curl` reached `/ping`/`/pending-requests`, and
approving via `curl` made the kid device's still-open waiting dialog react
exactly like the local PIN button does (dismiss, and for a subscribe
request the subscription actually appeared) — proof the HTTP path and the
local path share `KidModeGate.approve()`/`.deny()`, not two independent
implementations. With Kid Mode enabled, a persistent "Kid mode is on"
notification should be visible (`adb shell dumpsys notification --noredact
| grep -A5 newpipeKidMode`, or just check the notification shade) —
confirms `KidModeServerService` is actually running as a foreground
service, not just that the port happens to respond.

## Kid Mode pairing/auth manual verification (Phase C)

The server now binds `0.0.0.0`, and `/pending-requests`/`/approve`/`/deny`
require the `X-Kid-Mode-Device-Id`/`X-Kid-Mode-Signature` headers (see the
ADR and `wiki/features/kid-mode.md` for the protocol). `adb forward` still
works fine for reaching it from the host even though the bind address
changed:

```
adb forward tcp:46821 tcp:46821   # 46821 = ApprovalHttpServer.DEFAULT_PORT

# 1. Get a pairing code: Settings -> Kid mode -> "Pair a parent device"
#    (PIN-gated), note the 6-digit code shown.

# 2. Complete the handshake -- returns a deviceId and a base64 sharedSecret:
curl -s -X POST http://localhost:46821/pair \
  -H "Content-Type: application/json" \
  -d '{"deviceName": "Test Parent", "code": "123456"}'

# 3. Compute the signature for a request. The signed message is exactly
#    "<METHOD>\n<PATH>\n<BODY>" (empty body for GET/these POSTs), HMAC-SHA256
#    keyed with the *decoded* shared secret:
SECRET_B64="<sharedSecret from step 2>"
SIGNATURE=$(printf 'GET\n/pending-requests\n' | \
  openssl dgst -sha256 -hmac "$(echo "$SECRET_B64" | base64 -d)" -hex | awk '{print $2}')

curl -s http://localhost:46821/pending-requests \
  -H "X-Kid-Mode-Device-Id: <deviceId from step 2>" \
  -H "X-Kid-Mode-Signature: $SIGNATURE"
```

**Gotcha — `/pair` needs an explicit `Content-Type: application/json`
header when testing with `curl -d`.** `curl -d` defaults to
`Content-Type: application/x-www-form-urlencoded`, and NanoHTTPD's
`parseBody` special-cases that content type by decoding the body as form
parameters instead of populating the raw-body `postData` entry that
`ApprovalHttpServer.readBody()` reads. Without the explicit header, the
body silently becomes `""`, `handlePair` fails JSON parsing, and the
response is `400 {"error":"invalid request"}` with no indication the body
was ever received. A real client that sets its own explicit content type
(e.g. an OkHttp `RequestBody` built from a `MediaType`) won't hit this,
but any manual `curl -d` testing needs the header set explicitly.

Full walkthrough performed and passing as of 2026-07-23: enabled Kid Mode
+ set a PIN, tapped "Pair a parent device" and got a 6-digit code,
completed `/pair` via curl (with the header above) and got back a real
`deviceId`/`sharedSecret`; `/pending-requests` returned `401` with no auth
headers, `401` with a deliberately wrong signature, and `200` with a
correctly-computed one; the paired device ("Test Parent") appeared under
Settings → Kid mode → "Paired devices"; revoking it there made the same
signature `401` again on a follow-up request. `adb logcat -s
KidModeNsdAdvertiser` confirmed the NSD service registered
(`Registered NSD service: ScottPipe Kid Mode - sdk_gphone64_x86_64`) with
no `SecurityException`/failure callback.

**NSD advertising is only partially verifiable here.** `KidModeNsdAdvertiser`
registers the service whenever `KidModeServerService` runs, but the
Android emulator's default networking (user-mode NAT) doesn't carry
multicast traffic to the host or between emulator instances. What's
actually been confirmed: the registration call completes without a
`SecurityException`/`onRegistrationFailed` callback (check logcat,
`adb logcat -s KidModeNsdAdvertiser`). Genuine "does a second device
discover this on the LAN" testing needs real hardware, which is Phase D's
territory (the phase that actually builds something to discover it with).
