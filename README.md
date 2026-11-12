# Android Agent — FatSecret-first

An experimental **on-device Android 11** personal automation agent. Goal: log meals in an existing FatSecret diary from Telegram commands, without an always-on PC.

**Status:** research and early implementation; no verified APK, device test, or automatic diary entries yet.

## Research outcome
- FatSecret documents `food_entry.create` (OAuth 1.0 delegated user authorization). **Prefer API integration** if an existing FatSecret account can authorize it; do not assume a developer profile is identical to the user's existing diary.
- For general Android control, review [Droid](https://github.com/Zfinix/droid) (Apache-2.0), [BunnyRunner](https://github.com/clawbunny/BunnyRunner) (MIT), [PrivateAgent](https://github.com/orailnoor/private-agent) (Flutter + Telegram), and [android-use](https://github.com/j0taaa/android-use). No third-party code has been copied into this repository.
- Accessibility automation is a fallback for unsupported diary operations and for other phone tasks. Android may prevent screen control when locked or when an app blocks accessibility.

## Roadmap
1. Kotlin Android Studio project with restricted Accessibility tree inspection and non-destructive interactions.
2. Telegram long polling in a foreground service, chat ID allowlist, explicit start/stop, and no raw screen dumps sent to Telegram.
3. Deterministic FatSecret UI workflow (after real-device UI inspection).
4. Evaluate OAuth 1.0 three-legged authorization for **existing** FatSecret account and official diary API.
5. NLP/photo meal parsing and confirmation, retry safety, failure reporting.
6. CI debug APK, Realme 6 Pro device tests.

## Security
The repository is **public**. Never commit Telegram bot tokens, model/API credentials, OAuth tokens, meal history, or UI logs. Android Accessibility permission is powerful; do not enable on an untrusted APK. This project is not production-ready.
