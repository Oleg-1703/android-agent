# Project mission
Build an on-device Kotlin agent for Realme 6 Pro / Android 11 (API 30), controlled by an authorized private Telegram chat. The user clarified on 2026-11-15 that the primary goal is controlling the existing installed FatSecret application through Android Accessibility, not integrating its developer API. Developer registration/keys must not be required for that primary scenario. Text, food photos and voice should produce reviewed food entries in the existing FatSecret app. MVP requires a built APK, device installation, authenticated polling, a verified UI workflow and safe failure recovery. CI alone is not MVP acceptance.

# Architecture
`app/` is the Android application; `docs/` holds research, decisions, installation and progress; `.github/workflows/` builds/tests and uploads debug APKs. Use Kotlin, supported Android APIs, a user-started foreground service, Telegram Bot API, HTTPS and Android Keystore. LLM output is untrusted structured input, never executable instructions. Accessibility is the primary package-restricted, bounded execution path. Inspect and test the real application UI before implementing its workflow. The existing FatSecret developer API implementation is an optional legacy experiment, not the requested solution; do not require its setup for the primary goal or claim UI execution already exists.

# Engineering rules
Read README, docs/UPSTREAM.md, docs/PROGRESS.md and relevant source before changes. Reuse established components after auditing licenses and Android 11 support. Do not duplicate components, add unnecessary dependencies or silently substitute stubs. Separate transport, state, parsing and execution. Handle errors explicitly. Test critical authorization, parsing, signing and retry safety. Keep API 30 compatibility.

# Security
Never commit secrets, tokens, personal meal data, UI dumps, signing keys, APKs or local settings. Accept only configured private Telegram chat and sender IDs. Encrypt secrets with Android Keystore and disable backups. Do not log credentials or HTTP bodies. Restrict Accessibility to approved packages and bounded actions. Never execute arbitrary dangerous actions from messages or model output. Require explicit review before diary writes or irreversible actions. Never automatically retry an uncertain write.

# Git workflow
Use small Conventional Commits; push after checks and inspect CI. Do not rewrite main history. Use a branch and PR for risky changes. Fix build failures. Do not commit generated outputs.

# Documentation
Keep README, roadmap, architecture decisions and known limitations current. Record tests, blockers and next steps in docs/PROGRESS.md before ending a session. Update relevant GitHub Issues with evidence; do not close device-dependent work without device verification.

# Autonomous execution
Choose the next roadmap task independently, continue after the first commit, and finish authorized work within available tools/time. Do not ask about routine implementation choices. Document blockers and continue independent tasks. Never claim unverified functionality. Real-device and account tests may require the user; never request passwords/tokens in chat, provide secure in-app entry.

## Current module map
- `MainActivity`: in-app secret entry, OAuth browser/verifier flow, opt-in controls and manual uncertain-write acknowledgement.
- `SecretStore` / `StateStore`: Keystore AES-GCM encrypted configuration/state with synchronous commits.
- `AgentService`, `TelegramApi`, `TelegramAccess`: single-worker foreground polling, owner authorization, media bounds and backoff.
- `Meals`, `MealEngine`, `Portions`: strict command validation, candidate selection, reviewed quantities and durable no-replay write/verification state.
- `OAuth1`, `FatSecretApi` / `DiaryApi`: signed official existing-account OAuth and food/diary calls.
- `AiMeals`: untrusted structured extraction/transcription only, never direct actions.
- `FatAccessibilityService`, `FatUi`, `UiMealEngine`: fixed package-restricted screen workflow, explicit product/portion confirmation and shared uncertain-write journal. Runtime evidence is tracked in docs/PROGRESS.md.
- `PcBrain`, `pc/brain.py`: paired HTTPS transport to the user's PC; tool-less Claude Code subscription parser. No paid API auth or second Telegram poller. PC must remain on.
- `app/src/test`: JVM contracts/state/security regression tests. `app/src/androidTest`: dependency-free disposable-device smoke runner; never run against configured personal devices.

Never remove the HTTP 503 no-replay interceptor or pre-write state commit without equivalent regression coverage. Do not add automatic create retries, automatic uncertain-write acknowledgement, broad app permissions, or LLM-driven confirmation.

The only auxiliary window is TYPE_INPUT_METHOD of the currently selected DEFAULT_INPUT_METHOD. While FatSecret context/query remain verified, the recipe may click exactly one visible Search key in the lower half of that keyboard. Never click other keyboard actions or collect, log, persist or transmit keyboard suggestions or input history. Key labels may be compared only to locate that fixed Search action. No generic gestures or coordinates are allowed.

The user logged into real FatSecret on emulator-5556 on 2026-11-15. Preserve this AVD/account data. No physical Realme is attached. Diary writes require reviewed confirmation. Never inject synthetic meals into the personal account without that confirmation.
