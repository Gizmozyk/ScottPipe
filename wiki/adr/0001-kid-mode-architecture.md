# 0001: Kid mode architecture

Status: accepted (Phases A and B implemented; C and D not yet built)
Date: 2026-07-23, updated 2026-07-23 (Phase B)

## Context

The user wants a version of ScottPipe safe for their kids, with parental
controls modeled on [malerva0/CloggedPipe](https://github.com/malerva0/CloggedPipe)
(blocks playback from unsubscribed channels; requires approval to
subscribe), plus remote approval from the user's own phone. See
[ideas.md](../ideas.md) and [features/kid-mode.md](../features/kid-mode.md).

## Decisions

**Same-Wi-Fi only, no cloud/push backend.** The user explicitly does not
need remote approval to work when away from home over the internet.
Rejected: a hosted relay + push notifications (e.g. a Cloudflare Worker +
FCM, or a third-party service like ntfy.sh/a Telegram bot). That would
have worked from anywhere, but means standing up and maintaining hosted
infrastructure for a requirement the user doesn't have. Local-only (NSD/
mDNS discovery + direct device-to-device HTTP on the LAN) is simpler and
sufficient.

**Parent Mode is a mode within the same ScottPipe app, not a separate
companion app.** One codebase, one thing to maintain; the parent's own
phone can still browse/watch normally outside of Parent Mode.

**Phased delivery, networking deferred as late as possible.** Order:
(A) data model + gating + local same-device approval, with zero
networking → (B) embedded local HTTP server → (C) NSD pairing → (D) remote
Parent Mode UI. Each phase is independently testable. The gating logic —
the part that actually matters for the kids' safety — is proven correct on
a single device before any networking complexity is layered on top,
rather than building the whole stack at once and debugging gating and
networking failures simultaneously.

**Custom numeric PIN (Keystore-backed), not the device's own screen
lock.** Considered reusing `BiometricPrompt`/`KeyguardManager` to gate on
whatever lock the kid's device already has — less code, but only as
secure as a lock the kid's device might not even have set, and ties Kid
Mode's security to a setting outside the app's control. A dedicated PIN,
stored as a salted PBKDF2 hash further wrapped with an Android
Keystore-backed AES key (`KidModePinManager`), keeps Kid Mode
self-contained. Reversible encryption of the PIN itself was deliberately
not used — nothing ever needs to recover the PIN, only verify a guess
against it, so a hash is all that's needed; the Keystore wrapping on top
just protects the on-disk hash from offline brute-forcing if it leaks
(e.g. via a backup), which `androidx.security.crypto`/
`EncryptedSharedPreferences` would have also achieved, but at the cost of
a whole extra dependency for what a dozen lines of `javax.crypto`
already covers here.

**Embedded HTTP server (Phase B): NanoHTTPD over Ktor server.** This app is
Android-only for this feature (the `:shared` KMP module has none of the
gating logic), so there's no cross-platform reason to reach for Ktor's
server engines. NanoHTTPD is a single small dependency purpose-built for
exactly this — embedding a lightweight server inside an Android app
process — versus pulling in Ktor's larger server stack (coroutine engine,
content negotiation, etc.) that nothing else in the app uses.
`kotlinx-serialization-json` (already a dependency) still handles the JSON
payloads either way.

**Phase B's server binds to loopback (`127.0.0.1`), not `0.0.0.0`, until
Phase C ships auth.** Phase C is what adds the PIN-code pairing handshake
and authenticates requests; shipping Phase B's `/approve`/`/deny` endpoints
reachable from the whole LAN before that exists would mean anyone on the
same Wi-Fi could hit them with no auth at all for however long elapses
before Phase C lands. Binding to loopback only costs nothing in
testability now (`adb forward` reaches it fine for manual/automated
testing) and Phase C's own work is what flips the bind address once
there's something protecting it.

**Data model kept independent of real subscriptions.** A separate
`kid_mode_approved_channels` table (rather than just subscribing the kid
automatically) means approving a single video's playback never silently
subscribes the kid to the channel — subscribing is its own, separately
gated action.

## Consequences

- No remote approval yet — Phase B's HTTP server exists but is
  loopback-only, so approving still requires physical access to the kid's
  device (either the PIN button directly, or `adb forward` for testing).
  See [features/kid-mode.md](../features/kid-mode.md) for what's planned
  in Phases C and D.
- Because `ApprovalRequestEntity` status changes drive the UI reactively
  (a Room `Flowable`), later phases only need to make something else write
  `APPROVED`/`DENIED` to that row — the waiting screen and the actions it
  triggers don't need to change.
- Kid Mode is a single global device-level switch — NewPipe has no user
  profile concept today, so per-kid profiles on a shared device aren't
  supported and would be a separate, larger effort.
