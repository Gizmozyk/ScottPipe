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

## Running two emulators (Phase D dual-device testing)

Parent Mode needs a genuinely separate device to test against, not just
`curl` standing in for one. Since two emulator instances can't reach each
other directly (each is behind its own NAT and neither carries multicast
for NSD), the workaround is to route through the *host* machine:

```
# Create a second AVD once (same image as scottpipe-test):
avdmanager create avd -n scottpipe-parent \
  -k "system-images;android-35;google_apis;x86_64" -d pixel_6 --force

emulator -avd scottpipe-parent -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot &

# Install the debug APK on both (adb -s <serial> ...), enable Kid Mode on
# one ("kid"), get a pairing code from it, then on the other ("parent")
# expose the kid's server on the host and reach it via the emulator's
# built-in host-loopback alias:
adb -s <kid-serial> forward tcp:46821 tcp:46821
# In the parent's "Connect manually" dialog: host = 10.0.2.2, port = 46821
```

This exercises the *entire* real Parent Mode UI and protocol end-to-end
(pairing, viewing pending requests, approve, deny, revoke → `401` →
"pair again") — genuinely more coverage than curl-as-a-stand-in-device
testing reaches. What it still doesn't cover: actual NSD discovery
between two independent devices, since `10.0.2.2` bypasses discovery
entirely. That still needs real hardware (see the Phase D section below).

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

**Gotcha — `adb shell input text` can silently truncate long strings.**
Typing a full channel URL (~50+ characters) into an `EditText` in one
`input text` call sometimes lands only a truncated prefix, with no error
—confirmed by dumping the UI immediately after and reading the field's
actual `text` attribute back, rather than assuming the tap-and-type
sequence landed correctly. This is an ADB timing/buffering artifact, not
an app bug (no length limit exists in the actual dialog code). Splitting
a long string into two smaller `input text` calls avoided it. When a test
step depends on an exact typed value (not just "something non-empty"),
verify by reading the field back before proceeding, especially for long
strings.

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

**Gotcha — `--` inside an XML comment is illegal, and it's easy to type
one by accident.** Hit twice now (Phase B's `network_security_config.xml`,
then Phase D's rewrite of the same file): a plain-English "—"-style aside
written as `--` inside `<!-- -->` fails the XML parser
(`The string "--" is not permitted within comments`), and the resulting
error (`Failed to parse XML file ... network_security_config.xml`) doesn't
point at the actual bad line as clearly as it could. Use `;` or a full
word instead of `--` in XML comments, always.

**Gotcha — a bumped `DB_VER_N` needs its `DatabaseMigrationTest` step added
*before* running the suite, not after hitting the failure.** Every
existing test in that file migrates a database up to the *previous*
latest version and then calls `getMigratedDatabase()`, which opens Room at
whatever the *current* runtime `AppDatabase` version is — so a version
bump without a matching `runMigrationsAndValidate(..., DB_VER_N, ...,
MIGRATION_(N-1)_N)` step added to all of them fails with `A migration from
N-1 to N was required but not found`. Hit once in Phase C, pre-empted
successfully in Phase D by adding the step proactively.

**Gotcha — `NewPipeDatabase.kt`'s `addMigrations(...)` list is a separate,
easy-to-miss place a migration needs registering.** Phase C added
`MIGRATION_10_11` to `Migrations.kt` and imported it into
`NewPipeDatabase.kt`, but never actually passed it to `.addMigrations(...)`
— a real bug that instrumented tests didn't catch (they build `Room`
directly via `MigrationTestHelper`, bypassing this class entirely) but
would have crashed any real user's upgrade from a pre-Phase-C install.
Found and fixed while adding Phase D's own migration; when adding a new
`MIGRATION_N_N+1`, check both `Migrations.kt` *and* the actual
`.addMigrations(...)` call site in `NewPipeDatabase.kt`, not just the
import.

**Gotcha — `KidModeContentFilter`/`KidModeGate` silently no-op when Kid
Mode is off, and it defaults to off.** Both classes' very first check is
`KidModeGate(context).isEnabled()` (reads a SharedPreferences flag), and
both return "allowed"/"unfiltered" immediately if it's false — by design,
so the rest of the app has zero overhead when Kid Mode isn't in use. This
silently broke `FeedViewModelTest` once: the test blacklisted a channel
and asserted it was filtered out, but never flipped the preference on, so
the filter no-op'd and the assertion failed with a confusing "expected 0
got 1" rather than any obvious "Kid Mode is off" signal. Any test
exercising either class needs `PreferenceManager.getDefaultSharedPreferences(context)
.edit().putBoolean(context.getString(R.string.kid_mode_enabled_key), true).apply()`
in its setup (see `KidModeGateTest`/`KidModeContentFilterTest`/
`FeedViewModelTest` for the exact pattern) — don't assume "I seeded the
data, so the behavior will trigger."

**Gotcha — extractor `Info` classes with private constructors block
Fragment-level fake-data testing.** Tried to write a test proving
`BaseListInfoFragment`'s/`SearchFragment`'s real loading chains actually
call `KidModeContentFilter` (not just that the filter logic itself is
correct in isolation, which `KidModeContentFilterTest` already covers).
This needs a fake/canned `ListInfo` subclass (`KioskInfo`, `PlaylistInfo`,
etc.) to hand a test Fragment instead of hitting real network — but those
classes have **private constructors**, only ever producible via their own
real `getInfo(...)` extraction pipeline (confirmed via `javap` on the
vendored `NewPipeExtractor` jar, not assumed). `SearchInfo`/`ChannelTabInfo`
happen to have public constructors, but `SearchFragment.startLoading()`
calls the static `ExtractorHelper.searchFor(...)` directly with no
injectable seam, so even those can't be swapped for fake data without
mocking a static method (Mockito's inline mock maker, not currently an
`androidTestImplementation` dependency) or a production-code refactor
introduced purely for testability. Concluded this wiring is only
practically verifiable by manual testing (see Phase E below) or real
integration tests hitting real network — didn't force it, and didn't add
`fragment-testing`/Mockito-in-androidTest as a result since neither would
have actually solved the constructor problem on its own.

**Pattern — testing a `ViewModel` that exposes `LiveData` and reacts to a
process-wide singleton event source.** `FeedViewModelTest` is this
project's first `ViewModel` test; the recipe, reusable for
`SubscriptionViewModel`-style classes later: construct the `ViewModel`
and register `.observeForever { ... }` inside
`InstrumentationRegistry.getInstrumentation().runOnMainSync { ... }`
(`LiveData` requires observer registration on the main thread), capture
the emitted state into a `CountDownLatch`-guarded variable from the
observer callback, then `latch.await(timeout, ...)` on the test thread.
For `FeedViewModel` specifically, also call `FeedEventManager.reset()` in
`@Before` — it's a process-wide singleton `BehaviorProcessor` that
retains its last-emitted event across test method (and even test class)
boundaries within the same instrumented test run, so a previous test's
leftover event could otherwise leak into a fresh `ViewModel`'s first
`combineLatest` tick.


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

## Kid Mode Parent Mode manual verification (Phase D)

Full walkthrough performed and passing as of 2026-07-24, using the
dual-emulator setup described above (`scottpipe-test` as the kid device,
a second `scottpipe-parent` AVD as the parent device, connected via
`10.0.2.2` + `adb forward` since NSD discovery doesn't work between
emulator instances):

- Settings → Kid mode → "Parent Mode" opens with empty "Paired devices"
  and "Discovered devices" sections (no crash, no NSD `SecurityException`
  in `adb logcat -s KidModeNsdDiscoverer`)
- "Connect manually" with the kid device's `10.0.2.2`/`46821` and a fresh
  pairing code succeeds, storing the pairing and showing it in "Paired
  devices" (label was the entered host, since manual connections have no
  discovered name to use)
- Tapping the paired device opens the requests screen, which auto-refreshed
  and picked up a real pending request within 3 seconds of triggering a
  blocked subscribe on the kid device (no manual refresh needed)
- **Approve** on the parent's screen made the kid device's already-open
  waiting dialog dismiss and the channel's button flip to "SUBSCRIBED" —
  confirms the remote path reuses `KidModeGate` exactly like Phases B/C's
  curl-based testing already proved, now via the real production UI on a
  separate device
- **Deny** on a second request correctly left the channel unsubscribed
- Revoking the pairing from the kid device's "Paired devices" screen made
  the parent's next auto-refresh (within 3s) show the "Access revoked"
  dialog; "Remove pairing" deleted the local `ParentPairingEntity` and
  returned to the device list
- No `FATAL EXCEPTION` on either emulator across the whole session
  (`adb logcat -d | grep -i "FATAL EXCEPTION"`)

**Bug caught during this walkthrough, not by any automated test:** the
first pairing attempt appeared to do nothing — the dialog closed but no
device showed up in "Paired devices," and neither `adb logcat` (grepped
for exception/kidmode/parentmode keywords) nor a UI dump taken a couple
seconds later showed any trace of a failure (the error Toast the code
does show almost certainly appeared and disappeared before any dump was
taken — Toasts aren't retrievable after the fact the way persistent UI
state is). Root cause, found by testing pairing manually with
`Content-Type` variations in Phase C and reasoning from there: the
cleartext-traffic block hitting `KidModeApiClient`'s connection to the
kid's real LAN IP, since the existing `network_security_config.xml` only
allowlisted `127.0.0.1`/`localhost`, which Phase D's client never talks
to. Fixed by widening the network security config (see the ADR). Worth
remembering: a Toast-only error path is easy to miss entirely during
`adb`-driven testing since there's no reliable way to capture its text
after it's gone — for anything that might fail on the very first try,
prefer checking `adb logcat -d` immediately (within a second or two) or
adding a temporary persistent log line while debugging, rather than
relying on the Toast having been caught in time.

**Still not verified**: genuine NSD discovery between two independent
devices (this walkthrough bypassed it entirely via manual connect). That
needs the user's own real phone on the same Wi-Fi as a kid device — worth
doing once Phase D is otherwise considered stable, since this is the
first phase where trying that is actually meaningful.

## Kid Mode channel whitelist/blacklist and feed filtering manual verification (Phase E)

Full walkthrough performed and passing as of 2026-07-24, on a single
emulator against real (live) YouTube data — a channel was blacklisted/
whitelisted via `curl`+`openssl` (standing in for Parent Mode's HTTP
calls, same technique as Phases B/C) and, separately, via the actual
Parent Mode UI paired to itself over `127.0.0.1` (the server binds
`0.0.0.0`, so a device can reach its own server this way — a convenient
way to smoke-test the Parent Mode UI without needing the full
dual-emulator setup for every check):

- Blacklisting a real channel (Veritasium) made it disappear entirely
  from live search results for a query that previously returned its
  channel card and videos prominently (confirmed by the search going from
  several results to "No results" for the exact same query)
- The same blacklisted channel's own "Videos" tab was empty when visited
  directly — an emergent (correct, not a bug) consequence of the same
  uploader-URL filter applying everywhere, including a channel's own tab
- Tapping SUBSCRIBE on the blacklisted channel's page showed the "This
  channel is blocked" toast immediately, with **no approval dialog** and
  **no new row** in `/pending-requests` — confirmed by checking the
  pending-requests list before and after and seeing it unchanged
- Whitelisting a different real channel (Kurzgesagt) and tapping
  SUBSCRIBE flipped the button straight to "SUBSCRIBED" with zero dialog
  and, again, no new pending-request row
- Un-listing (removing) the blacklist rule made the channel reappear in
  search immediately
- **The scenario specifically requested**: blacklisting a channel the kid
  was *already subscribed to* (Kurzgesagt, subscribed via the whitelist
  step above) removed its videos from the subscriptions feed
  ("What's New") on the next real fetch — confirmed by triggering an
  actual `FeedLoadService` network fetch (`Feed last updated: moments
  ago` in the UI, `FeedLoadService` lifecycle logged in `adb logcat`) and
  seeing "Nothing here but crickets" despite a successful fetch, then
  removing the blacklist and confirming the exact same real videos
  reappeared on the next fetch
- The full `ParentModeChannelsActivity` UI was exercised for real (not
  just via curl): the "Manage channels" menu item on
  `ParentModeRequestsActivity`, the rule list rendering existing rules,
  the "Add channel" dialog's three buttons (Cancel/Blacklist/Whitelist),
  and tap-to-remove with its confirmation dialog all worked correctly

The Feed ("What's New") screen needs an explicit fetch to test against —
it doesn't eagerly load a newly-subscribed channel's videos on its own.
Trigger one by tapping the "Feed last updated: ..." bar itself (its whole
row is the refresh target, not a separate small icon — check
`resource-id="...refresh_root_view"` bounds if unsure) and confirm via
`adb logcat -s FeedLoadService` that a real fetch ran before drawing
conclusions from an empty feed.

## Kid Mode QR-code pairing verification

`KidModePairingQrCodeTest` (plain JVM, encode/decode round-trip + `null`
on non-JSON/wrong-shape/mismatched-`type` input) and
`KidModeLanAddressTest`/`KidModeQrCodeGeneratorTest` (instrumented) all
pass as of 2026-07-24. `./gradlew :app:assembleDebug` also confirmed
`zxing-android-embedded`'s SDK-24-ish expectations are satisfied at this
app's minSdk 23 by the existing core-library desugaring — no version
pinning fallback was needed.

**Not yet manually verified — needs a real camera, which this
environment doesn't have.** Two emulator instances have no camera
passthrough between them the way `10.0.2.2` bridges networking for HTTP
(unlike every other Kid Mode phase so far, this one can't be smoke-tested
purely on this machine). Still to do, on real hardware:

- Kid device: "Pair a parent device" shows the QR image (not just the
  numeric code) when Wi-Fi is connected.
- Parent device: "Scan QR code" row appears (camera-having device),
  requests the `CAMERA` permission on first use, and a successful scan
  pairs with zero typing.
- Denying the camera permission degrades gracefully (a toast/cancelled
  scan, not a crash), and "Connect manually" still works from the same
  screen.
- A camera-less device/AVD never shows the "Scan QR code" row at all.
- Scanning an unrelated QR code (anything not produced by this app)
  shows the "That doesn't look like a ScottPipe pairing code" toast
  rather than crashing or attempting a pairing.
- This is also the natural time to finally test genuine NSD
  cross-device discovery (still outstanding from Phase D, see above) if
  a second real device is available in the same session, since both
  gaps need real hardware anyway.
