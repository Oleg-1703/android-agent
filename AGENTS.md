# Project mission
Build a private meal agent for Oleg's existing FatSecret account. The owner clarified on 2026-11-16: text with exact macros, nutrition-label photo plus consumed quantity by voice, a food photo with optional context, or a barcode must become a CUSTOM FOOD diary entry automatically. Do not substitute catalog products or require /pick and per-number confirmations. Processing must use an existing local Claude/Codex subscription, without paid AI API keys. The phone may collect Telegram messages while the paired PC performs inference and the fixed Accessibility recipe in the already authenticated emulator-5556. The owner clarified that his panini values were PER 100 G. Packaged-food text without a basis defaults to 100 g with an explicit note; only explicit whole-portion totals such as 'всего' use consumed grams as the basis. Always preserve a stated basis. Label data uses its printed basis. Food-photo estimates must be marked; unknown/incomplete barcode facts must fail clearly without invented nutrition. CI alone is not acceptance: verify the actual custom-food UI and diary result.

# Architecture
An explicit owner request to find an ALREADY EXISTING food is a separate supported route: source=existing, exact name including size and brand, no model nutrition. The executor reads nutrition from the one exact FatSecret card. Missing, duplicate or incomplete search results must stop without writing. Do not log the owner's example roll merely because it was used as a hypothetical request. The default custom-food route remains unchanged.

`app/` is the Android application; `docs/` holds research, decisions, installation and progress; `.github/workflows/` builds/tests and uploads debug APKs. Use Kotlin, supported Android APIs, a user-started foreground service, Telegram Bot API, HTTPS and Android Keystore. LLM output is untrusted structured input, never executable instructions. Accessibility is the primary package-restricted, bounded execution path. Inspect and test the real application UI before implementing its workflow. The existing FatSecret developer API implementation is an optional legacy experiment, not the requested solution; do not require its setup for the primary goal or claim UI execution already exists.

# Engineering rules
Read README, docs/UPSTREAM.md, docs/PROGRESS.md and relevant source before changes. Reuse established components after auditing licenses and Android 11 support. Do not duplicate components, add unnecessary dependencies or silently substitute stubs. Separate transport, state, parsing and execution. Handle errors explicitly. Test critical authorization, parsing, signing and retry safety. Keep API 30 compatibility.

# Security
Never commit secrets, tokens, personal meal data, UI dumps, signing keys, APKs or local settings. Accept only configured private Telegram chat and sender IDs. Encrypt secrets with Android Keystore and disable backups. Do not log credentials or HTTP bodies. Restrict Accessibility to approved packages and bounded actions. Never execute arbitrary dangerous actions from messages or model output. The owner explicitly authorized automatic custom-food creation and diary logging on 2026-11-16. The fixed executor journals before creation, verifies the entered nutrition and reads the exact diary delta. No catalog replacement is permitted in the primary scenario. Manual legacy offline commands retain explicit review. Other irreversible actions still require review. Never automatically retry an uncertain write.

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
- `PcBrain`, `pc/brain.py`, `pc/meal_jobs.py`: paired HTTPS custom-meal jobs, tool-less Claude subscription parser, durable deduplication and fixed emulator execution. `pc/barcodes.py` reads complete Open Food Facts nutrition with a source URL. No paid AI API auth or second Telegram poller. PC and the authenticated emulator must remain on.
- `app/src/test`: JVM contracts/state/security regression tests. `app/src/androidTest`: dependency-free disposable-device smoke runner; never run against configured personal devices.

Never remove the HTTP 503 no-replay interceptor or pre-write state commit without equivalent regression coverage. Do not add automatic create retries, automatic uncertain-write acknowledgement, broad app permissions, or model-supplied confirmation commands. Automatic food logging is authorized by the owner and implemented by the fixed executor, never by executing model instructions.

The only auxiliary window is TYPE_INPUT_METHOD of the currently selected DEFAULT_INPUT_METHOD. While FatSecret context/query remain verified, the recipe may click exactly one visible Search key in the lower half of that keyboard. Never click other keyboard actions or collect, log, persist or transmit keyboard suggestions or input history. Key labels may be compared only to locate that fixed Search action. No generic gestures or coordinates are allowed.

The user logged into real FatSecret on emulator-5556 on 2026-11-15. Preserve this AVD/account data. No physical Realme is attached. The 2026-11-16 owner request authorizes logging his real supplied meals automatically, including the panini request, without another confirmation. Never inject synthetic meals into this personal account: parser tests must mock the executor, and emulator acceptance may use only an owner-supplied real meal with durable receipts preventing duplication. Test APK execution must continue refusing physical devices.
