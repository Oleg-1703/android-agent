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
