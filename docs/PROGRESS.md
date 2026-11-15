# Progress - 2026-11-12 onward

## 15.11.2026 - Accessibility и ИИ по подписке, работа продолжается

- Олег выполнил вход в FatSecret на видимом Android 11 emulator-5556. Аккаунт и данные сохраняются; записи не добавлялись.
- По фактическому UI добавлены `FatUi` и `UiMealEngine`: поиск, список вариантов, выбор продукта/граммов, предварительный просмотр, `/confirm` с кодом и обязательный журнал перед единственным SAVE. Новая запись блокируется до ручной проверки. Модель не задаёт нажатия.
- Основные вкладки теперь предлагают обычный вход в FatSecret, Accessibility и ИИ по текущей подписке на ПК. Старые developer/платные API скрыты в отдельные экспериментальные формы. Сборка остаётся в разработке, новый APK ещё не распространялся.
- `pc/brain.py`: stdlib, loopback, случайный код сопряжения, один запрос к модели, подписочный `claude.ai` вход, инструменты/MCP/hooks/skills/session persistence отключены, API-auth переменные исключены из child env. Реальный русский запрос разобран успешно. Реальный HTTPS tunnel -> private PC -> Claude -> JSON также прошёл.
- Добавлены 6 JVM-тестов UI-журнала: неверный код, смена окна/порции, отказ записи журнала, сбой после SAVE/пересоздание исполнителя, отмена с сохранением блокировки. Вместе с прежними: 52 JVM-теста. 5 Python-проверок авторизации/ограничений/изоляции прошли.
- Runtime dry-run на настоящем FatSecret прошёл: сводка -> поиск -> выбор -> 100 г -> preview -> отмена. Также прошёл сквозной Android PcBrain -> HTTPS -> подписка Claude -> UI -> preview -> отмена. SAVE в этих проверках не нажимался. Исправлены подавление Accessibility тестовой UiAutomation, некликабельная уже выбранная вкладка Diary и ожидание поля после смены единиц.
- Олег явно разрешил тест «Banana, 1 г, сегодняшний обед» с последующим удалением. Обнаружены две стадии: `fem_save_btn` только выбирает продукт, `multi_add_save_section_holder` записывает выбранное в дневник. Первый инструментальный тест дошёл только до выбора; после ручной проверки отсутствия записи рецепт доисследован локально. Тестовая запись создана, прочитана как Banana / 1 g, удалена только она; исходная строка обеда сохранилась. Неопределённость снята через штатный UI с двумя подтверждениями после проверки. Никакие production-проверки журнала не отключались.
- Исполнитель дополнен финальной стадией: проверяет тот же контекст даты/приёма пищи, ровно один выбранный продукт, имя и граммы; затем один раз нажимает toolbar commit. Изменение существующей записи запрещено. Дальнейшая автоматическая runtime-проверка этой стадии ещё нужна. Личный дневник не используется для произвольных синтетических записей.
- Ограничения: один продукт/сегодня, только текст, ручная проверка сохранения, временный HTTPS-адрес. Продолжается работа по поручению на ближайшие 12 часов. [Установка помощника](PC_BRAIN_RU.md).

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

## 15.11.2026 - доведение на локальном ПК

- Checkout: `projects/android-agent`, ветка `feat/desktop-ready` от незамерженного PR #6. Прочитан PR #7; исходный отчёт сохранён в `docs/testing/realme6pro-android11-2026-11-15.md`.
- Установлены JDK 17, SDK 35, Android Emulator и AOSP Android 11 / API 30 x86_64. WHPX работает. Инструменты вне Git; сборка через junction на этот же checkout обходит проблему кириллицы в Windows JVM classpath.
- Новый русский UI: отдельные вкладки, карточка состояния, проверка соединения, кнопка нужного бота, подсказки, безопасная диагностика. Основные действия адаптированы под узкий экран и крупный шрифт. Настройки приложения ведут прямо на карточку Android Agent.
- Telegram получает отдельную проверку webhook/токена, конкретные ошибки 401/403/404/409, ответ при пропуске устаревшего запроса. `/stop` отвечает до остановки. `/today` и вопрос о сегодняшней еде работают без ИИ после подключения дневника. Сохранены ограничения чата, шифрование и запрет повторной неопределённой записи.
- Локально: 36 JVM-тестов, lint без ошибок/предупреждений, APK и test APK. Runtime/Keystore/process restart/UI прошли на обычном экране и при 320 dp / шрифте 130%. Настоящие getMe/getWebhookInfo/getUpdates/sendMessage и остановка того же поллера прошли из Android; live-конфигурация удалена после проверки.
- Независимое read-only ревью: исправлена некорректная схема отдельной instrumentation-фазы остановки, добавлена очистка live-конфигурации и корректная подсказка при быстром Stop/Start. Продуктовые дефекты из узкого экрана проверены повторно.
- Олег подтвердил: developer-доступа FatSecret пока нет. Реальные OAuth/дневник/записи, фото/голос, Play Protect и фон Realme не проверены. Модели и платные API не подключались; новые записи еды не создавались. Полная приёмка MVP остаётся открытой.
- [Подробные доказательства и границы](testing/desktop-2026-11-15.md). Добавлены воспроизводимые Windows-команды и CI Android 11 с обезличенными скриншотами. Публикация PR и удалённые проверки выполняются после локальных тестов.
- Опубликован PR #8 (`feat/desktop-ready`). Локальный дополнительный тест обычного приложения: старт без сети → переподключение → восстановление сети → `Работает` → остановка прошёл. Удалённая JVM/lint/APK сборка прошла; первый CI-эмулятор завис в ожидании ADB. Ожидание ограничено, добавлен лог запуска при ошибке и современный режим SwiftShader. Дубликат push-проверки feature-ветки убран: для неё работает PR CI, для main - push CI.
- Лог CI выявил причину: emulator не находил созданный AVD в своём каталоге по умолчанию. `ANDROID_USER_HOME` и `ANDROID_AVD_HOME` заданы явно для создания и запуска, путь AVD проверяется до старта. Это исправление окружения CI; APK приложения не изменяется.
- [CI 43732188990](https://github.com/Oleg-1703/android-agent/actions/runs/43732188990) на `476c8aa`: **build и Android 11 emulator успешны**. Публикуются app/test APK, тестовые отчёты и обезличенные скриншоты. Локальный APK 0.3.0: SHA-256 `a58642e49ac6fb01f6663be3f26b88cbe8b29919f712e90b1ec3b7b78735a7a2`. Отчёт `.local/report.html` проверен в браузере на 320/390/1440 px: темы, фильтр, увеличение, Escape и сохранение разделов. Локальный эмулятор после проверки остановлен, credentials из него удалены. Полная приёмка FatSecret/Realme остаётся открытой.

## 15.11.2026 - 0.3.1, ложное устаревание и кнопка входа

- После уточнения Олега основной целью является управление уже установленным FatSecret через Accessibility, а не developer API. Управление экраном пока не реализовано. Старые требования ключей относятся только к экспериментальному API-режиму; AGENTS/README/инструкции и ответы бота скорректированы.
- Пользователь прислал повторные свежие «Помощь»/«Статус», отвергнутые как устаревшие. Найден дефект: фильтр сравнивал дату Telegram с часами телефона и запрещал даже отрицательную секунду. Теперь используется Date HTTPS-ответа Telegram + монотонное время Android. Помощь/статус/отмена/остановка доступны независимо от часов, после проверки владельца; действия всё ещё ограничены свежестью.
- Добавлены 10 JVM-тестов: часы вперёд/назад на сутки/5 часов/секунду, ошибка заголовка и устаревший образец, границы свежести, отсутствие доверенного времени, прежняя авторизация. Все 46 JVM-тестов прошли; lint без замечаний; app/test APK собраны.
- У кнопки входа FatSecret сообщение появлялось вне видимой области, а проверка ключей читала устаревающий UI-снимок. Сообщения прокручиваются в видимую область; введённые ключи сохраняются самой кнопкой; актуальная конфигурация читается непосредственно перед запросом. Ошибка сервера показывается с безопасным кодом без тела ответа.
- Олег сохранил ключи в игнорируемом `.env`. Значения не выводились и не включаются в APK/Git. В файле используются названия Client ID / Client Secret (OAuth 2.0); прежний вход рассчитан на OAuth 1.0 Consumer Key / Shared Secret. На локальном Android запрос с этой парой получил HTTP 400; успешная авторизация не заявляется. Пароль аккаунта не запрашивался, дневник не изменялся, временный импорт и конфигурация после теста удалены.
- Runtime/Keystore/process restart/UI-проверки на Android 11 прошли, включая видимость ошибки входа без ключей. Дополнительная UI-проверка реальной кнопки с имитацией успешного сетевого ответа и перехватом browser Intent прошла: автосохранение и переход проверены, это не live-вход в аккаунт.
- Коммит `847687e`: [CI 43737592463](https://github.com/Oleg-1703/android-agent/actions/runs/43737592463) полностью прошёл (build + Android 11 emulator). APK 0.3.1 отправлен в Telegram, подпись совпадает с ранее отправленной 0.3.0; `.env` и его значения в оба APK/исходники не попали.
- Для основной UI-задачи установлен настоящий FatSecret 11.10.0.3 в отдельном ARM-совместимом Android 11 эмуляторе. Исследован вход и начальная настройка на синтетическом профиле, затем тестовые данные удалены. Для доступа к дневнику теперь нужен самостоятельный вход пользователя в приложение либо Realme с готовым входом по USB. Это доступ к обычному приложению, не требование developer-ключей. [Факты, resource IDs и границы](testing/fatsecret-ui-2026-11-15.md).
