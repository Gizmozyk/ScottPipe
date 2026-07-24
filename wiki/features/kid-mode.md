# Kid mode

A version of ScottPipe safe for the user's kids: block certain actions
unless a parent approves. Modeled on [malerva0/CloggedPipe](https://github.com/malerva0/CloggedPipe)'s
functionality (see [ideas.md](../ideas.md)), reimplemented natively rather
than ported.

## What it does today (Phase A)

- Turn on in Settings → Kid mode. First time you enable it, you set a PIN —
  that PIN is required again any time you want to turn it back off, or
  re-enter this settings screen once it's been visited before.
- While enabled:
  - Playing a video from a channel you aren't subscribed to is blocked. A
    dialog shows "Waiting for a parent to approve" with an **Approve as
    parent** button (PIN-gated) and a **Deny** button.
  - Subscribing to a new channel is blocked the same way.
- Approving is same-device only right now: a parent hands the kid's device
  to enter the PIN. See below for what's planned to make this remote.
- Manually verified end-to-end on-device as of 2026-07-23 — see
  [testing.md](../testing.md) for the full checklist and how to reproduce.
- Whenever Kid Mode is on, a low-priority "Kid mode is on" notification is
  shown — this is the embedded local HTTP server (Phase B, see below)
  running as a foreground service, not just a UI indicator.

## How it works (for future reference)

- Blocked actions are recorded as rows in a local Room table
  (`kid_mode_approval_requests`), not held in memory — so the flow survives
  the app being killed and relaunched.
- The waiting dialog observes that row reactively (a Room `Flowable`), so
  whatever writes an `APPROVED`/`DENIED` status to it — right now the same
  device's PIN-gated button, later a remote parent device — is picked up
  and acted on the same way, with no extra plumbing.
- Approving a channel subscription re-fetches the channel's info at
  approval time (rather than caching it from when the request was made) so
  the eventual subscription always has current metadata.
- Code lives under `app/src/main/java/org/schabi/newpipe/kidmode/`. The two
  gating chokepoints are `BaseListFragment.onStreamSelected` (video
  playback) and `ChannelFragment.mapOnSubscribe` (subscribing).
- All approve/deny logic (including the subscribe-completion fetch) lives
  in one place, `KidModeGate.approve()`/`.deny()` — both the local dialog's
  PIN button and the Phase B HTTP server call the same code, so there's
  exactly one thing that ever performs a request's completion work,
  however the approval was triggered.

## Built: embedded local HTTP server (Phase B)

`KidModeServerService` (a foreground service, mirroring
`DownloadManagerService`'s pattern) runs `ApprovalHttpServer`
(`org.schabi.newpipe.kidmode.server`, using NanoHTTPD) whenever Kid Mode is
enabled — started/stopped from the Settings toggle, and restarted from
`App.onCreate()` if Kid Mode is already on when the app process (re)starts.

Endpoints (JSON):

- `GET /ping` — connectivity check, returns `pong`. Unauthenticated.
- `POST /pair` — see "Pairing" below. Unauthenticated (the pairing code is
  the proof).
- `GET /pending-requests` — `{"requests": [{id, type, status, title, channelUrl, createdAt}, ...]}`.
  Authenticated.
- `POST /approve/{id}` / `POST /deny/{id}` — resolves the request via
  `KidModeGate`, returns the updated request as JSON, or `404` if `id`
  doesn't match a pending request. Authenticated.

**Gotcha discovered building this:** Android blocks cleartext (plain HTTP)
traffic by default for apps targeting API 28+, with no automatic exception
for loopback addresses — even `127.0.0.1` gets rejected
(`CLEARTEXT communication to 127.0.0.1 not permitted`) without an explicit
network security config. `res/xml/network_security_config.xml` originally
allowed it for `127.0.0.1`/`localhost` only; Phase D widened this to the
whole app once the *parent* side also needed to reach the kid device's
real LAN IP (see Phase D below for why).

## Built: pairing and authenticated requests (Phase C)

The server now binds `0.0.0.0` (not just loopback) since requests are
authenticated — see the ADR for why Phase B deliberately held off on this.

**Pairing.** Settings → Kid mode → "Pair a parent device" (PIN-gated)
generates a 6-digit code, valid 5 minutes, single-use, shown in a dialog.
A second device proves it saw that code via `POST /pair` with
`{"deviceName": "...", "code": "123456"}`; on success the kid device
generates a device id (UUID) and a 256-bit shared secret, stores the
device (Keystore-AES-wrapped secret, same technique `KidModePinManager`
uses for the PIN), and returns `{"deviceId": "...", "sharedSecret": "..."}`
— the only time the plaintext secret is ever sent. Settings → Kid mode →
"Paired devices" lists paired devices with a revoke action.

**Authenticating a request.** Every request except `/ping` and `/pair`
must include `X-Kid-Mode-Device-Id` and `X-Kid-Mode-Signature` (hex
HMAC-SHA256 of `"<METHOD>\n<PATH>\n<BODY>"`, keyed with that device's
shared secret — see `KidModeHmac`, a pure `javax.crypto` helper with no
Android dependency, so it has a plain JVM unit test rather than needing a
device/emulator). Missing/invalid headers, an unknown/revoked device, or a
signature mismatch all return `401`.

**Deliberately no replay protection yet.** The signed message has no
nonce or timestamp, so a captured signed request could be resent later.
Accepted for now given the low blast radius (replaying an old approve/deny
on a same-LAN family network) — see the ADR.

**NSD advertising.** `KidModeNsdAdvertiser` registers the running server
under `_scottpipe._tcp.` whenever Kid Mode's service is running, so Parent
Mode (Phase D, see below) can discover it instead of needing an IP typed
in. **Still not fully verified end-to-end**: the emulator used for
development can't carry mDNS traffic between instances, so only "the
registration/discovery calls don't throw" has actually been confirmed —
real cross-device discovery needs testing on real hardware. See
[testing.md](../testing.md).

## Built: Parent Mode (Phase D)

Settings → Kid mode → "Parent Mode" opens a screen (the same app, not a
separate app, run on the parent's own phone) with two sections: paired
kid devices, and kid devices discovered on the LAN via
`KidModeNsdDiscoverer` (the client-side counterpart to
`KidModeNsdAdvertiser`).

**Pairing from the parent's side.** Tapping a discovered device, or
"Connect manually" (host/port/code entered by hand), prompts for the
pairing code shown on the kid device and calls `POST /pair` via
`KidModeApiClient` (plain OkHttp, reusing `KidModeHmac` as-is to sign
later requests — no Android/Keystore/DB dependency of its own). On
success, `ParentPairingManager` stores the result (`ParentPairingEntity`:
kid device id/name, host, port, Keystore-AES-wrapped shared secret — the
mirror image of what the kid device stores about a parent via
`PairedDeviceEntity`). Unlike the kid side's soft-revoke, unpairing here
is a hard delete: this side has no audit-trail need for keeping a record
of a removed pairing.

**"Connect manually" is a real feature, not just a test workaround.**
mDNS/NSD is commonly blocked by AP/client isolation on consumer routers
and mesh Wi-Fi systems, so a manual host/port fallback matters for actual
families' networks, not only for this dev environment's emulator
limitations.

**Viewing and acting on requests.** Tapping a paired device opens a
screen that fetches `/pending-requests` on open and auto-refreshes every
3 seconds while visible (plus manual pull-to-refresh) — deliberately
frequent, since the whole point is that the kid's action is sitting
blocked *right now*. Approve/deny buttons call `/approve/{id}`/`/deny/{id}`
directly — these hit the exact same endpoints Phases B/C already proved
share `KidModeGate` with the local PIN button, so the kid's own waiting
dialog reacts identically no matter which device approved it. A `401`
(the kid device revoked this pairing) shows a clear message with an
action to remove the stale pairing, rather than a raw error.

**Gotcha hit building this:** the network security config fix above —
Parent Mode's outgoing connection to the kid's LAN IP was blocked by the
same cleartext restriction Phase B hit for loopback, except this time
there's no fixed hostname to allowlist (the kid device's IP is
DHCP-assigned), so the fix had to become an app-wide
`cleartextTrafficPermitted="true"` base-config instead of a scoped
domain-config. This doesn't weaken the rest of the app: nothing outside
Kid Mode ever uses an `http://` URL, so no other traffic is affected.

See [0001-kid-mode-architecture.md](../adr/0001-kid-mode-architecture.md)
for why these phases were ordered this way, and
[testing.md](../testing.md) for how Phase D was verified end-to-end
without needing real hardware yet.
