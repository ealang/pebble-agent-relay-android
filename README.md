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

## Building

Standard Android Gradle project; Android Studio opens it as is.

- `compileSdk 37`, `minSdk 26`. A first build downloads any missing SDK
  platform and build-tools itself (not in `--offline` mode).
- **TDLib** is built from source at a pinned commit by
  `scripts/build-tdlib.sh` into the gitignored `app/src/main/jniLibs/`.
  No prebuilt binaries are committed or downloaded. It takes a while; the
  script's header lists the knobs (ABIs, NDK, commit).
- **Telegram API credentials** are baked in at build time from
  `local.properties` (gitignored) or the environment:

  ```properties
  TELEGRAM_API_ID=12345
  TELEGRAM_API_HASH=0123456789abcdef0123456789abcdef
  ```

  Get your own pair at <https://my.telegram.org> → *API development
  tools*. They identify the application, not an account, and every install
  of a given build shares them. Without them the build still succeeds and
  the app tells you it has no credentials.

Debug build (signed with your debug key, installable straight away):

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Optional `local.properties` knobs for development: `DEV_ABIS=arm64-v8a`
packs only that ABI into debug builds (saves ~70 MB per install);
`DEBUG_KEYSTORE=/path/to/debug.keystore` pins the debug signing key so a
rebuilt machine still installs over the phone's copy.

Release build (unsigned, split per ABI, plus the Play bundle):

```bash
./gradlew assembleRelease bundleRelease
```

Signing is a separate step with a key that is not part of this repo. See
[docs/RELEASING.md](docs/RELEASING.md) for the full recipe.

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
