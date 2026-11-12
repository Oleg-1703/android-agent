# Existing solutions and build-vs-reuse decision

Reviewed on 2026-11-12:

| Project | URL | Relevant design |
| --- | --- | --- |
| Droid | https://github.com/Zfinix/droid | Kotlin Android agent, MCP/ACP, Apache-2.0 |
| BunnyRunner | https://github.com/clawbunny/BunnyRunner | Kotlin on-device Accessibility agent, MIT |
| PrivateAgent | https://github.com/orailnoor/private-agent | Flutter, LLM and native Android Accessibility |
| android-use | https://github.com/j0taaa/android-use | Kotlin on-device Accessibility automation |
| Fox-Islam/android-agent | https://github.com/Fox-Islam/android-agent | Kotlin no-ADB computer-use agent |

Next: audit manifests, maintenance, build requirements and permission models before importing any upstream code. License compatibility and actual feature behavior are not yet verified.

FatSecret API documentation: https://platform.fatsecret.com/docs/v1/food-entry.create
API-first is preferable if OAuth authorization supports the user's existing diary. Verify this with a real account before promising automated diary writes.

No upstream code copied. Current Android code is an intentionally small inspection scaffold.
