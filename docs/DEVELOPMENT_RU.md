# Разработка и проверка на Windows

Репозиторий находится в `projects/android-agent`. Локальные инструменты установлены отдельно: `%USERPROFILE%/.local/share/android-agent-tools`.

- Temurin JDK 17, Gradle 8.9 через проверяемый wrapper.
- Android SDK: platform 35, build-tools 35.0.0, platform-tools.
- Android Emulator, AOSP Android 11 / API 30 x86_64, AVD `Oleg_Agent_API30`.
- Эмулятор: 720×1280, 1 ГБ RAM, 2 ядра, графика SwiftShader, аппаратное ускорение WHPX.

SDK скачан с официального сайта Android, SHA-256 command-line tools проверен по странице загрузки. Java скачана через официальный API Adoptium. Android Studio для командной разработки не требуется.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/dev.ps1 build
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/dev.ps1 start
# После загрузки Android:
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/dev.ps1 smoke
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/dev.ps1 stop
```

ExecutionPolicy задаётся только для процесса скрипта, постоянные настройки Windows не меняются. При недостатке памяти сначала останови свой тестовый эмулятор, собери APK, затем запусти эмулятор. Скрипт использует один worker Gradle и heap 1 ГБ. Чужие процессы останавливать не требуется.

Для сборки используется junction `android-agent-tools/project`, указывающий на этот же checkout. Это обход проблемы JVM-тестов с не-ASCII путями Windows, а не вторая копия исходников. Скрипт проверяет адрес ссылки. После обновления AGP/Gradle обход можно перепроверить.

`smoke` требует пустую конфигурацию и откажется тестировать физическое устройство. Проверяет шифрование, перезапуск процесса, безопасную остановку без настроек, русские вкладки и защиту экранов. Скриншоты пустых форм лежат в игнорируемой `.local/qa/`. Они снимаются только тестовым APK и только при пустой конфигурации. В обычном приложении скриншоты доступны лишь на обезличенном экране диагностики.

Дополнительные фазы тестового APK `configure`, `live` используются для локальной проверки настоящего Telegram. Временный `files/test-config.json` импортируется в Keystore и удаляется. Фаза `live` проверяет сеть, отправку и остановку одного живого поллера, затем удаляет тестовую конфигурацию и offsets. Между этими проверками нельзя запускать отдельную инструментацию: Android останавливает целевой процесс при её запуске. Секреты не передаются аргументами instrumentation, не входят в APK и не публикуются. Эти фазы не включены в CI и обычный smoke. Не запускай `smoke` поверх настроенного приложения и не очищай его данные без необходимости.

На другом компьютере сначала установи перечисленные SDK-пакеты официальным `sdkmanager`, JDK 17 и создай одноимённый AVD через `avdmanager`. Можно передать другой `-ToolsRoot`. Документация инструментов: [SDK](https://developer.android.com/studio), [запуск эмулятора](https://developer.android.com/studio/run/emulator-commandline).
