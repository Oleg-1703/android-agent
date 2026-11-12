# Progress — 2026-11-12

## Baseline audit
Read all repository source, README, UPSTREAM, issues #1–5 and latest CI. Baseline main: 34fe86c. Only a local Accessibility inspection scaffold exists. No Telegram, Keystore, meal executor, AI or tests yet. CI run 43206719109 fails before Gradle: setup-android requests removed SDK package `tools`. No Gradle wrapper is checked in. Working tree clean at start.

## Current work
Establish reproducible Gradle wrapper, explicit SDK packages, local debug/test/lint build. Then implement secure opt-in Telegram transport and bounded automation; research account integration before claiming FatSecret support.

## Acceptance blockers
No Realme or existing FatSecret account is attached. Account authorization and actual diary writes must be verified on-device. No secrets are needed in chat.

## Next
Record build results and commit links as each stage completes. MVP is not yet ready.

## Stage 1 verified
Local JDK 17 / Gradle 8.9: `testDebugUnitTest lintDebug assembleDebug` succeeded (baseline has no unit tests yet). Debug APK generated. Added checked-in wrapper with verified distribution SHA-256, explicit build-tools 35.0.0 and SDK packages in CI; removed obsolete `tools` package. Local tooling lives outside the repository; no SDK paths/proxy settings committed. Third-party source audit and API decision recorded in UPSTREAM.md and DECISIONS.md.

## Implementation — branch feat/secure-on-device-agent
- Stage 1 pushed as 2978696; [CI 43211704737](https://github.com/Oleg-1703/android-agent/actions/runs/43211704737) succeeded and published debug APK.
- Implemented private chat/sender authorization, foreground Telegram polling with opt-in Start/Stop, encrypted bot-scoped offsets, rate-limit handling and bounded backoff. Old/forwarded messages rejected. At-most-once dispatch documented.
- Keystore AES-GCM protects tokens/config/pending meal/write journal; synchronous commits; secure setup screen; backup/device transfer excluded.
- Existing-account FatSecret OAuth request-token/browser/OOB-verifier/access-token flow, search/details, correct gram-to-API-unit conversion, explicit product selection, expiring confirmation, duplicate warning, write/read-back matching and durable uncertain-write block. No implicit profile creation, automatic create retry or blind UI recipe.
- Optional OpenAI-compatible text/photo JSON extraction and voice transcription; explicit upload consent, bounded attachments, strict input validation and mandatory manual review.
- Local Accessibility action primitives for unique allowlisted resource IDs, bounded action count, unlocked screen and explicit local review. These are not exposed as arbitrary remote actions and are not device-validated.
- Russian installation/recovery guide and updated roadmap added.

## Automated verification
JDK 17 / Gradle 8.9 / SDK 35: `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` passed. 31 JVM tests: 17 validation/state/authorization/portion/OAuth-vector tests, 8 signed API/AI contracts, 6 HTTP limits/errors/rate-limit/no-replay tests. MockWebServer covers successful requests, disconnect after write and HTTP 503 Retry-After:0; fake diary covers durable blocking across executor recreation, stale confirmations, partial meals, verification mismatch and disk failure before write. These tests do not contact Telegram/FatSecret/LLM live.

Lint: 0 errors; 19 warnings (hard-coded English setup strings/localization). No lint baseline suppression added. APK and test APK generated locally. Device smoke runner added for API 30 installation, secure Activity, Keystore encryption/process restart and unconfigured foreground stop; execution result recorded below when available.

## Remaining blockers / acceptance
No actual Realme 6 Pro, Telegram token, FatSecret developer credentials/member authorization, or configured inference provider is available in this session. Do not ask for these in chat. Real-account access entitlements, existing diary visibility and a real diary create/read-back need in-app user setup and verification. API implementation and mock tests do not prove live provider behavior. Realme lock/battery/background recovery and Accessibility actions require real-device testing. No UI fallback is fabricated. Album grouping, multi-turn AI clarification, locale-specific FatSecret search and release signing remain outside this tested increment.

## Remote checks and issue status
Feature commit 312c1e6 is pushed in [draft PR #6](https://github.com/Oleg-1703/android-agent/pull/6). Both [push CI 43213501470](https://github.com/Oleg-1703/android-agent/actions/runs/43213501470) and [PR CI 43213640670](https://github.com/Oleg-1703/android-agent/actions/runs/43213640670) passed. The PR CI published [debug APK artifact 13941688976](https://github.com/Oleg-1703/android-agent/actions/runs/43213640670/artifacts/13941688976) and test reports. Issues #1–4 have implementation/test evidence and remain open for live account/device acceptance. Source-audit issue #5 is completed and closed.

## API 30 test harness correction
Installed app and test APK on the Android 11 AOSP x86_64 software emulator. The first smoke run reached Activity launch, FLAG_SECURE and Keystore round-trip, then exposed a race in the smoke assertion: the worker sets its stopped status before its finally block releases the active-worker flag. Updated the harness to wait for both conditions (bounded 30 seconds), and enabled raw instrumentation result codes in the shell script. The first corrected phase passed; final two-phase script results follow below. This change does not alter production app behavior.

## Final Android 11 emulator verification
`ANDROID_HOME=... ./scripts/device-smoke.sh` completed with exit code 0 on Android 11 AOSP x86_64 API 30 (software emulation). Both app and test APK installations succeeded. Write phase passed Activity launch, FLAG_SECURE, Keystore ciphertext/round-trip and safe unconfigured foreground-service termination. Read phase passed decryption after force-stop/process restart and removed the synthetic test marker. Raw instrumentation codes were -1 for both phases. [Sanitized evidence](DEVICE_SMOKE_2026-11-12.md). The script now refuses non-emulators/non-API-30 devices and waits for boot. This proves basic API 30 installation/runtime behavior, not Realme/OEM or actual FatSecret/Telegram/LLM behavior.

## Handoff / next step
Install the PR's debug APK on Realme and follow docs/INSTALL_RU.md. Enter credentials only in the app, authorize the existing FatSecret account, compare the read-only diary count, then review/confirm one small test entry and verify it in FatSecret. Keep #1–4 open until the listed network/lock/battery/account/media scenarios pass. Do not merge this draft as an accepted MVP solely because CI or emulator smoke tests passed. Next code work should be driven by sanitized real-device/account failure evidence; never fabricate successful live results or ask for tokens in chat.

## Distribution notices
Inspected debugRuntimeClasspath and packaged resources. Added Apache-2.0 and MPL-2.0 license texts plus runtime dependency notices to APK assets; retained OkHttp's original Public Suffix List NOTICE. No agent source was imported. This packaging-only follow-up does not change runtime behavior.
