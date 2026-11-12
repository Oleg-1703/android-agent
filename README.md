# Android Agent — FatSecret-first

A Kotlin **on-device Android 11+** personal agent for Realme 6 Pro. Telegram controls an existing FatSecret diary without an always-on PC or a project backend. Cloud services are called directly from the phone.

**Status:** debug APK builds locally and in CI. Secure Telegram transport, existing-account OAuth, reviewed meal entry and optional AI input are implemented. **Real-account diary writes and Realme operation are not yet verified; this is not an accepted MVP.** See [PROGRESS](docs/PROGRESS.md) for exact evidence and blockers.

[Установка и настройка на русском](docs/INSTALL_RU.md) · [Architecture decisions](docs/DECISIONS.md) · [Upstream audit](docs/UPSTREAM.md) · [CI / APK artifacts](https://github.com/Oleg-1703/android-agent/actions/workflows/android.yml)

## Implemented
- Opt-in foreground Telegram long polling, persistent notification/Stop, encrypted offset, bounded reconnect backoff and private chat + sender allowlist. No boot auto-start. At-most-once dispatch can lose a command on a crash, avoiding automatic replay of writes.
- Android Keystore AES-GCM for credentials and persistent state; in-app configuration, no secrets in chat, no backup or raw UI uploads.
- FatSecret official OAuth 1.0 browser authorization for an **existing member** (no replacement `profile.create`), food search, gram-based serving conversion and diary API integration.
- `/meal YYYY-MM-DD lunch chicken cooked=200;rice cooked=150`, explicit `/pick ITEM CHOICE`, review and expiring `/confirm CODE`, `/cancel`, `/status`, `/stop`. Existing foods trigger a duplicate warning. Every create is followed by read-back verification; uncertain writes are durably blocked without automatic retry. Multi-item meals can partially complete.
- Optional OpenAI-compatible natural text/image extraction and voice transcription with explicit provider upload consent. All results still need food selection and review. Provider compatibility and live quality require testing.
- Package-restricted Accessibility inspection and internal bounded click/text/scroll primitives. No remote arbitrary phone control or unverified FatSecret UI recipe.

## Build and test
Requires JDK 17, Android SDK platform 35 / build-tools 35.0.0; minSdk 30, targetSdk 35.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For the dependency-free Android 11 smoke runner, build `./gradlew assembleDebugAndroidTest`, boot a clean disposable API 30 emulator and run `./scripts/device-smoke.sh` with `ANDROID_HOME` set. The script refuses non-emulator devices. It checks Activity/Keystore/foreground failure behavior and force-stops/relaunches to check encrypted persistence.

APK: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions uploads `android-agent-debug` after successful tests/lint/build. Wrapper 8.9 distribution checksum is pinned. CI APKs use ephemeral debug signing keys; see installation notes before updating an existing install.

## Roadmap / remaining acceptance
1. Completed: reproducible debug build and CI artifacts.
2. Implemented, device validation pending: Telegram service, secure setup and bounded local automation.
3. Implemented, account validation pending: official existing-account FatSecret OAuth/search/review/write/read-back pipeline.
4. Implemented, provider validation pending: optional text/photo/voice parsing.
5. Required: Realme installation, account authorization and a real diary write, reconnect/lock/battery tests, provider tests. Issues remain open until their acceptance evidence exists.

## Security and limitations
The repository is public. Never commit bot/model/OAuth credentials, meal history, logs or signing keys. A confirmed food entry is a real diary modification. The app never automatically retries a create with an uncertain result; inspect your diary before acknowledging its local block. The configured private Telegram owner is the only remote user. Accessibility is optional for the API path and limited to FatSecret. Phone control cannot work while locked. Read the installation guide for exact command semantics, limits and recovery.

Reuses OkHttp (Apache-2.0) and Android platform APIs. No upstream agent source has been copied. See the source/license audit before any future reuse.
