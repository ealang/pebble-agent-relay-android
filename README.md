# Pebble Telegram Agent

Forwards dictated messages from a Pebble watch to a Telegram chat of your
choice. The watch is the microphone; this app is the pipe.

Two front doors feed one durable queue:

- **PebbleKit 2** — the Core Devices app (CoreApp) binds to this app and
  hands over dictations from the companion watchapp directly. This is the
  primary path and needs nothing enabled or open on the phone.
- **Local webhook** (optional, off by default) — a loopback-only HTTP
  endpoint (`POST /index`) for senders on this same phone.

Messages are written to disk before they are acknowledged, then delivered to
Telegram by a short-lived foreground service that retries until the network
cooperates and exits when the queue is empty. Acknowledged means durably
queued; delivery is confirmed against Telegram before an item is dropped.

## Security

**Posture.** This app's job is to move your own dictations into your own
Telegram account on your own phone. There are no accounts, no analytics, no
cloud component, and no remote endpoints other than Telegram itself (via
TDLib/MTProto).

**Network exposure.** The only listener is the optional webhook server:
off by default, hardcoded to bind `127.0.0.1` (no other bind address is
supported), one endpoint, nothing served without auth. Requests carry a
bearer token — 192 bits from `SecureRandom`, generated on first use, stored
encrypted under an Android Keystore key, and compared in constant time.
Unknown paths 404 before auth is checked. When the server is disabled, no
socket exists; delivery-only runs never bind one.

**Exported surface.** Three components are reachable from outside the app,
none with data exposure:

- The launcher activity (the UI).
- The PebbleKit 2 service — exported with no permission because the PK2
  protocol requires it (CoreApp requests none). It gates in code, failing
  closed at three layers: the PebbleKit app picker is pinned to
  `coredevices.coreapp` with auto-selection disabled; every call is checked
  at the Binder entry point (the calling uid must belong to CoreApp *and*
  carry its pinned signing certificate); and only the companion watchapp's
  known UUIDs are accepted. Rejected callers get the protocol's refusal shape and
  learn nothing.
- The boot receiver is not exported; the system delivers its protected
  broadcasts regardless.

The delivery service itself is not exported.

**At rest.** Everything lives in the app-private `filesDir`: the message
queue (plain text — Android's per-app sandbox is the boundary), the debug
log (event names and message *lengths*, never message text), and TDLib's
database, which is encrypted with a random per-install 32-byte key wrapped
by the Android Keystore — a copied database is useless off this phone.
Backups are disabled app-wide (`allowBackup="false"`), so neither secrets
nor the logged-in Telegram session leave the sandbox via backup or
device-migration tools.

**Trust boundaries.** CoreApp necessarily sees every transcript — it is the
transport from the watch. Any watchapp on your own watch could reuse a
companion UUID; the attacker in that scenario is you.
