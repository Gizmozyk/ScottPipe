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

Endpoints (JSON, no auth yet — see "Loopback-only for now" below):

- `GET /ping` — connectivity check, returns `pong`.
- `GET /pending-requests` — `{"requests": [{id, type, status, title, channelUrl, createdAt}, ...]}`.
- `POST /approve/{id}` / `POST /deny/{id}` — resolves the request via
  `KidModeGate`, returns the updated request as JSON, or `404` if `id`
  doesn't match a pending request.

**Loopback-only for now.** The server binds to `127.0.0.1`, not `0.0.0.0`
— it's not reachable from other devices on the LAN yet, only from the kid's
own device (or via `adb forward` for testing — see
[testing.md](../testing.md)). There's no pairing or authentication yet
(that's Phase C), so binding to all interfaces now would put an
unauthenticated `/approve`/`/deny` control channel on the network for
however long elapses before Phase C ships auth. Phase C's own work is what
switches this to `0.0.0.0`.

**Gotcha discovered building this:** Android blocks cleartext (plain HTTP)
traffic by default for apps targeting API 28+, with no automatic exception
for loopback addresses — even `127.0.0.1` gets rejected
(`CLEARTEXT communication to 127.0.0.1 not permitted`) without an explicit
network security config. `res/xml/network_security_config.xml` allows it
for `127.0.0.1`/`localhost` only; everything else in the app still
requires HTTPS as normal.

## Planned: remote approval (future phases)

The goal is to approve requests from the parent's own phone over the same
Wi-Fi (explicitly not over the internet — see the ADR).

- **Phase C** — pairing over Android NSD (mDNS) so the parent's phone can
  discover and trust a kid's device on the LAN, with a PIN-code handshake;
  switches the HTTP server to bind `0.0.0.0` once requests are
  authenticated.
- **Phase D** — a "Parent Mode" UI (the same app, not a separate app) that
  lists paired kid devices and their pending requests, and approves/denies
  them remotely.

Neither is built yet. See
[0001-kid-mode-architecture.md](../adr/0001-kid-mode-architecture.md) for
why these phases are ordered this way and the alternatives that were
considered and rejected.
