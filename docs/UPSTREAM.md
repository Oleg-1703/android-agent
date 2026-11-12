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

## Source audit (2026-11-12)

Inspected repository metadata, root README/license, Android manifests and Gradle files via GitHub API/raw source (not device-tested).

| Project | Compatibility / maintenance observed | Reuse decision |
| --- | --- | --- |
| Droid | minSdk 26 / target 35; last push 2026-10-31; Apache-2.0. Telegram and a foreground child-process agent with Unix-socket Accessibility bridge; very broad permissions. | Useful architecture reference; importing the runtime/CLI would substantially expand this small app and its permission scope. |
| BunnyRunner | minSdk 26 / target 35; last push 2026-06-04; MIT. Kotlin Accessibility agent with additional UI/model dependencies. | Possible future library extraction after reviewing the specific files; no FatSecret-specific existing-account workflow found in audited documentation. |
| PrivateAgent | Flutter + native plugins, app minSdk 26; last push 2026-07-17. Telegram documented. No SPDX license/standard root LICENSE observed; README says available for modification. | No code copied: licensing unresolved and Flutter migration is unnecessary. |
| android-use | minSdk 30 / target 35; last push 2026-09-23. Native agent with bounded tool loop and encrypted history. No repository license detected. | Design reference only until permission/license clarified. |
| Fox-Islam/android-agent | minSdk 30 / target 35; last push 2026-09-13. Accessibility + model agent, specialUse foreground service; no repository license detected. | Design reference only until permission/license clarified. |

All were unarchived at audit time. A recent push does not establish quality or verified Android 11 behavior. None was evaluated on Realme. Reuse OkHttp (Apache-2.0) for HTTP instead of importing a complete phone-control agent; retain Android platform Keystore and a narrow application-specific executor. No upstream source copied.
