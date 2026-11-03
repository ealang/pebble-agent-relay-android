# Agent Relay for Pebble

A streamlined Android app for sending a dictated message to a single AI agent
over Telegram. One app supports both the
[Pebble Index](https://repebble.com/index) and the companion
[Pebble watchapp](https://github.com/ealang/pebble-agent-relay-watchapp).
For Index, it provides a webhook endpoint that runs locally on Android.
Messages are queued on the phone, so a dictation sent while offline is
delivered once the phone reconnects.

## Using it

The app walks you through connecting to Telegram and choosing the chat to
deliver to. For Index, it lets you enable a private `127.0.0.1` endpoint to
point the Index voice action at.

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
