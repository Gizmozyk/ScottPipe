# 0001: Kid mode architecture

Status: accepted (Phases A through E implemented)
Date: 2026-07-23, updated 2026-07-24 (Phases B through E)

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

**Phase C binds the server to `0.0.0.0`, the change Phase B's ADR entry
already flagged as Phase C's job**, now that `/pair` plus per-request
HMAC signatures protect it.

**HMAC-over-shared-secret request signing, not sending the shared secret
itself as a bearer token.** There's no TLS on this local server — a bearer
token would hand a passive LAN observer the actual secret on the first
captured request, permanently compromising that pairing until revoked.
HMAC signing never puts the secret on the wire, only a per-request
signature. Deliberate scope limit: this scheme has no nonce or timestamp
and is therefore replayable — a captured signed request could be resent
later. Accepted for now given the low blast radius (replaying an old
approve/deny on a same-LAN family network); revisit if that assumption
stops holding.

**Pairing secret Keystore-wrapped, not hashed like the PIN.** Unlike the
PIN (`KidModePinManager`, which only ever needs to verify a guess, so a
one-way hash suffices), the shared secret must be recovered in plaintext
on every request to compute the expected HMAC. `KidModePairingManager`
duplicates `KidModePinManager`'s AES/GCM Keystore-wrapping technique
(under its own key alias) rather than sharing code, since the two have
different one-way-vs-reversible requirements.

**Phase D's parent-side data model (`ParentPairingEntity`) is a deliberate
mirror of `PairedDeviceEntity`, not a shared table.** Same
Keystore-AES-wrapping technique for the secret (again duplicated under
its own key alias, following the same precedent as above), but different
lifecycle: the kid device soft-revokes (`revoked` flag) to keep an audit
trail of who was ever trusted, while the parent side has no such need and
just hard-deletes a pairing on unpair. `ParentPairingEntity` also carries
fields the kid side never needs (`host`/`port`) since the kid device is
always the server, never the one initiating a connection.

**`KidModeApiClient` (Phase D's HTTP client) reuses `KidModeHmac`
directly, with zero duplication.** Unlike the Keystore-wrapping code
(duplicated each time because Android Keystore key aliases are inherently
per-owner), `KidModeHmac` is pure `javax.crypto` with no Android
dependency, so both sides of the protocol share the exact same
sign/verify implementation instead of maintaining two copies that could
drift out of sync.

**"Connect manually" (host/port/code entry) is a permanent feature, not a
stopgap for Phase D's own testing.** mDNS/NSD is commonly blocked by
AP/client isolation on real consumer routers and mesh Wi-Fi systems
(guest networks in particular), so a family relying solely on discovery
could find Parent Mode simply doesn't work on their actual network. It
also happens to be what made Phase D's protocol fully testable in this
dev environment (two emulator instances can't discover each other over
NSD, but can reach each other via `adb forward` + the `10.0.2.2`
host-loopback alias with manual entry) — a useful side effect, not the
reason it was built.

**Network security config widened from loopback-only to app-wide
cleartext.** Phase B/C's `network_security_config.xml` allowlisted only
`127.0.0.1`/`localhost` for cleartext HTTP, which sufficed while all Kid
Mode traffic was local-device-only (`adb forward`-based testing). Phase D
needs the *parent* device to reach the kid device's real LAN IP, which is
DHCP-assigned and therefore has no fixed hostname to list — Android's
network-security-config `<domain>` matching only supports literal
hostnames, not IP ranges, so a scoped allowlist entry per possible IP
isn't feasible. The config now permits cleartext app-wide
(`<base-config cleartextTrafficPermitted="true">`). This doesn't weaken
anything else: nothing outside Kid Mode ever uses an `http://` URL, so no
other traffic in the app is affected by also permitting it — this only
stops the OS from pre-emptively blocking Kid Mode's inherently plaintext
local protocol, which has no cert infrastructure by design (see "Same-Wi-Fi
only, no cloud/push backend" above).

**Phase E's channel rules are one table with a status enum, not a separate
whitelist and blacklist table.** `ChannelRuleEntity` gained a `status`
column (`WHITELISTED`/`BLACKLISTED`) instead of introducing a second
table alongside the existing (previously unused) approved-channels one.
Two tables would let a channel end up in both lists simultaneously — a
bug class to guard against in application code — whereas one row per
channel makes that structurally impossible, and "changing your mind"
about a channel is just calling the same `upsertRule` again.

**`KidModeGate`'s result became a three-way `GateDecision`
(`ALLOWED`/`BLOCKED`/`NEEDS_APPROVAL`), not still a boolean.** A
blacklisted channel needs a decision distinct from both "proceed" and
"ask a parent" — proceeding would defeat the blacklist, and asking a
parent would create a pending request nobody needs to act on (the parent
already made their decision proactively). The channel rule is checked
*before* subscription/approval status specifically so a blacklist always
wins even if the kid was somehow already subscribed before the channel
was blacklisted.

**Feed/search filtering runs inside each screen's existing async loading
chain, never synchronously inside `handleResult`/`handleNextItems`.**
`NewPipeDatabase` is built without `.allowMainThreadQueries()`, and those
callback methods run on the main thread (after `.observeOn(AndroidSchedulers.mainThread())`)
— a blocking Room read there would crash. `KidModeContentFilter` is
instead spliced in as a `.map` step while still on `Schedulers.io()`, so
the three integration points (`BaseListInfoFragment`, `SearchFragment`,
`FeedViewModel`) needed no structural change to their existing
result-handling methods, only their upstream data-loading chains.

**The filter loads one snapshot per fetch (all rules + all denied video
URLs), not a query per list item.** The number of rules/denials a parent
has actually set is expected to be small (dozens at most, not thousands),
so one `Flowable<List<...>>` read per screen load, matched in-memory
against each item, is simpler and cheaper than a per-item DB round trip.

**Reused `KidModeHmac`/the existing HTTP auth pattern for the three new
channel-rule endpoints, no new auth mechanism.** `POST /channels/rule`,
`POST /channels/unlist`, and `GET /channels` are authenticated exactly
like `/approve`/`/deny`/`/pending-requests` already were — same
`withAuth` wrapper, same `KidModeApiClient` request-signing helpers —
since nothing about proactively setting a rule needs different trust
assumptions than approving a reactive request.

## Consequences

- Remote approval now works from a genuinely separate device (Phase D's
  Parent Mode), verified end-to-end across two emulator instances using
  the "Connect manually" path (see `wiki/testing.md`) — pairing, viewing
  pending requests, and approve/deny all confirmed to reuse the exact same
  kid-side code paths Phases A-C already proved.
- Cross-device NSD *discovery* specifically is still unverified: the
  emulator used for development doesn't carry multicast traffic between
  instances, so only "the registration/discovery calls don't throw" has
  actually been confirmed on both sides of the protocol. Needs
  real-hardware testing (installing on the user's own phone) to fully
  validate the original "remote approval from my own phone" goal.
  See `wiki/testing.md`.
- Because `ApprovalRequestEntity` status changes drive the UI reactively
  (a Room `Flowable`), later phases only need to make something else write
  `APPROVED`/`DENIED` to that row — the waiting screen and the actions it
  triggers don't need to change.
- Kid Mode is a single global device-level switch — NewPipe has no user
  profile concept today, so per-kid profiles on a shared device aren't
  supported and would be a separate, larger effort.
- Blacklisting a channel empties its own "Videos" tab if visited directly
  (every video there is filtered by the same uploader-URL check that
  hides it everywhere else). This is intended, not a bug, but is worth
  knowing so it isn't "fixed" by accident later.
- Phase E's gating/filtering was verified manually against real YouTube
  data (not just synthetic test fixtures) end-to-end: blacklisting a
  channel removed it from live search results and its own video tab,
  blocked its subscribe button with no dialog and no pending request
  created, and — the scenario specifically asked for — blacklisting a
  channel the kid was *already subscribed to* removed its videos from the
  subscriptions feed on the next real fetch, confirmed reversible by
  un-listing the rule. See `wiki/testing.md`.
