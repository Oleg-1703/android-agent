# Realme 6 Pro / Android 11 manual smoke test — 2026-11-15

## Scope and status
User-tested APK built from GitHub Actions run 43213640670 (artifact 13941688976, described as debug APK ~3.65 MB). User successfully downloaded and installed APK on a Realme 6 Pro running Android 11. **Not an end-to-end pass. Telegram bot never replied to /start or a normal text message.** This is a user-observed result, not a confirmed root cause. No emulator test or source-level diagnosis has yet been performed in this report.

## Observed UI / usability problems
- Main screen is an unpolished English developer configuration panel with a dense wall of labels, inputs and actions; not suitable for a nontechnical end user. Needs Russian localization, onboarding, clear sections and concise help/error text.
- User initially missed the Telegram Bot Token field. Other fields include Allowed Private Chat Sender ID, FatSecret Developer Consumer Key, FatSecret Consumer Secret, OpenAI-compatible HTTP API base (default https://api.openai.com/v1), optional Model API Key, vision/text model (GPT-4o-mini), transcription model (whisper-1), and a checkbox authorizing provider processing of food text/photos/voice.
- Actions seen: Save Configuration, Stop Agent, Authorize Existing FatSecret Account, Complete FatSecret Authorization, Check Today Existing Diary, Start Telegram Agent, Refresh Status, Inspect Diary, an accessibility/settings shortcut, Open FatSecret Battery Settings, Realme Setup, Disconnect FatSecret. Some labels were read by voice and may not be exact.
- No clear visual feedback observed when pressing Start Telegram Agent or Refresh Status; no readily visible running/stopped/error indicator. Critical UX and diagnosability issue.
- Screenshot capture was blocked with a personal-information warning (likely secure window flag; verify actual implementation). Consider a safe redacted diagnostics screen or export without secrets.

## Installation / permissions
- Google Play Protect initially blocked installation with: “Приложение заблокировано для защиты устройства”, warning about sensitive-data access and fraud. “Подробнее” opened https://support.google.com/android/answer/2812853?hl=ru; no install-anyway option reported.
- Later installation succeeded; exact steps that allowed it were not established. Do not assume Play Protect was disabled or the APK is safe.
- In Realme app info > battery usage: priority execution enabled, background operation disabled, auto-start disabled. User enabled background operation and auto-start; priority execution remained enabled. Telegram still failed afterward.
- Realme Setup opened system battery optimization list where Android Agent was not found; user located battery controls via app info instead.
- No Accessibility grant or other sensitive permission was confirmed. Do not assume it was granted.

## Telegram setup and failed test
- User has an existing Telegram bot used by local agents to **send notifications only**. Reusing the same bot for receiving commands seems plausible, but confirm whether any other consumer polls getUpdates or a webhook is configured.
- User entered the bot token in Android Agent; UI subsequently showed “Saved, Leave blank to keep”, suggesting it was stored. **Never log, export or commit the token.**
- User entered their allowed private chat sender ID and saved configuration. This report intentionally omits the numeric ID from the repository; check local configuration and ID type/format.
- User pressed Start Telegram Agent and Refresh Status; no observable UI feedback.
- Telegram /start received no response. After background/auto-start permissions were enabled, /start still received no response. An ordinary text message (“Что я сегодня ел?”) also received no response.
- FatSecret account authorization, diary read/write, AI vision/transcription, and Accessibility actions were **not tested**.

## Engineering follow-up / acceptance criteria
1. Reproduce with a clean Android 11 emulator and the same APK/commit. Install, launch, walk every screen, exercise all buttons, validate permission flows and report issues with screenshots/video (using a safe redacted/test configuration).
2. Inspect Android logcat and application logs for Start Telegram Agent: lifecycle/foreground service, exceptions, permission requirements, network/TLS, bot token validity, sender ID filtering, polling vs webhook conflicts, and whether /start and plain text are implemented. Distinguish service startup from message handler failures. Do not print secrets.
3. Add explicit UI states and actionable errors: configuration saved, token present, service starting/running/stopped, Telegram connection/auth failure, last update, and diagnostics export with redaction.
4. Redesign the main screen for ordinary users: Russian language by default, setup wizard, Telegram and FatSecret steps, grouped advanced AI settings, safe secret fields, clear primary actions and status, no wall of text. Ensure secure screenshots policy still permits safe bug reporting.
5. Verify Play Protect behavior and permission manifest; do not bypass security by default. Test background restrictions and autostart on Realme, and Android 11 compatibility.
6. Add automated unit/integration tests and a repeatable emulator UI smoke test (e.g. instrumentation/UIAutomator), run CI, attach evidence, then build a new installable APK.
7. Only hand the user a release candidate after emulator UI/Telegram smoke tests pass. FatSecret OAuth and read/write should be tested with test credentials and explicit user approval before any real diary modification.

## Handoff to desktop agent
Fetch the latest repository branch and this report. Check existing PR #6 and its build against main; do not assume it was merged. Reproduce and fix bugs in a feature branch, add automated emulator/UI checks, open a PR with evidence and APK artifact. Avoid collecting credentials in screenshots/logs or Git commits. The user prefers **not** to repeat manual trial-and-error on their phone until the agent has tested the UI and integrations independently.
