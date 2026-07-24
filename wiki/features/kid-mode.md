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

## Planned: remote approval (future phases)

The goal is to approve requests from the parent's own phone over the same
Wi-Fi (explicitly not over the internet — see the ADR). That needs, roughly
in this order:

- **Phase B** — an embedded local HTTP server running inside the app,
  exposing the same approve/deny actions over the network instead of only
  a local button.
- **Phase C** — pairing over Android NSD (mDNS) so the parent's phone can
  discover and trust a kid's device on the LAN, with a PIN-code handshake.
- **Phase D** — a "Parent Mode" UI (the same app, not a separate app) that
  lists paired kid devices and their pending requests, and approves/denies
  them remotely.

None of this is built yet. See
[0001-kid-mode-architecture.md](../adr/0001-kid-mode-architecture.md) for
why these phases are ordered this way and the alternatives that were
considered and rejected.
