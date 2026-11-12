# Architecture decisions

## 2026-11-12: preserve the small native app
Audit in UPSTREAM.md found large complete agents, not a drop-in existing FatSecret diary module. Keep Kotlin/API 30 and use OkHttp rather than introducing Flutter, child-process runtimes or broad permissions.

## 2026-11-12: official existing-account API first
FatSecret's [three-legged OAuth guide](https://platform.fatsecret.com/docs/guides/authentication/oauth1/three-legged) explicitly links the resulting profile to an existing fatsecret.com member. Use browser authorization with `oauth_callback=oob` and an in-app verifier; never collect the member's password and never call `profile.create` as a substitute.

Verified documentation endpoints:
- POST https://authentication.fatsecret.com/oauth/request_token
- Browser https://authentication.fatsecret.com/oauth/authorize?oauth_token=...
- GET https://authentication.fatsecret.com/oauth/access_token (signed with request token secret and verifier)
- [foods.search](https://platform.fatsecret.com/docs/v1/foods.search), [food.get.v4](https://platform.fatsecret.com/docs/v4/food.get)
- [food_entry.create](https://platform.fatsecret.com/docs/v1/food_entry.create), [food_entries.get](https://platform.fatsecret.com/docs/v1/food_entries.get)

Old hyphenated `docs/v1/food-entry.create` redirects to general documentation; use underscore names. API keys, developer entitlement, regional food availability and real diary visibility cannot be validated without user configuration on-device. API responses do not provide idempotency keys. Never retry create automatically: persist a write intent first, verify by returned entry ID and quarantine uncertain writes until the user inspects the diary. Multi-item meals are not atomic; report verified partial progress.

## Telegram dispatch and foreground lifecycle
A user starts/stops a `specialUse` foreground service. This type fits an opt-in persistent command listener without Android 15's dataSync six-hour limit. API 30 remains the target device; Android 14/15 behavior and distribution policy are not yet device-tested. No boot receiver, background start or silent permission grants. A single worker serializes commands. Bot-scoped update offsets are encrypted and committed before dispatch; delivery is at-most-once (a crash may lose a command). Only fresh messages from the allowlisted private chat and matching sender are accepted; forwarded/via-bot messages are rejected. Both Telegram rate-limit formats are honored. 401/403/409 stop polling; network failures back off with jitter. A new bot token receives its own offset scope.

## Write safety and inference boundaries
LLM parses data only; it has no tools or Android executor and cannot confirm a write. Structured `/meal` works without AI. Each item requires explicit search-result selection, an expiring meal-wide review nonce and confirmation. Existing foods on the date trigger a warning. Before each create, encrypted write intent is synchronously committed. Review is consumed before effects. Read-back checks entry ID, food/serving IDs, date, meal and quantity. Any create/read-back failure retains the block; no automatic retry or rollback. Unsent items are not sent after an uncertain result. Local acknowledgement requires stopping the active worker and manually inspecting the diary. Keystore/storage errors fail closed.

OkHttp automatic connection retries and redirects are disabled. A network interceptor changes `503 Retry-After: 0` to a positive delay because OkHttp otherwise automatically repeats such requests independently of `retryOnConnectionFailure(false)`. A regression test asserts a single POST. Responses/downloads are bounded; remote errors are redacted. AI uploads are opt-in, HTTPS only, memory-only for attachments, with provider-specific compatibility left to live validation.
