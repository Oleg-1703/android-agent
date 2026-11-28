# Отложенный набор

`tasks_heldout.json` содержит шесть новых задач с русскими формулировками для
штатных Контактов и Настроек Android 11 google_apis. Исходные шесть задач остаются
в `tasks.json`. Prompt, loop, policy и Device не изменены. Модели при подготовке
набора не вызывались.

| Задача | Что проверяет | Checker проверен на устройстве |
| --- | --- | --- |
| heldout_far_contact | Поиск Zulu Dispatch после 30 записей Atlas, замена телефона | Нет |
| heldout_edit_email | Правка email существующей записи с сохранением телефона | Нет |
| heldout_similar_contacts | Выбор среди двух Mira Sokol по номеру, избранное | Нет |
| heldout_two_apps | Чтение номера в Контактах и перенос в имя устройства в Настройках | Нет |
| heldout_font_search | Поиск вложенной настройки и выбор максимального шрифта | Нет |
| heldout_missing_contact | Отсутствующая запись: give_up, без создания или изменения | Нет |

## Проверки без модели

Только одноразовый `emulator-5580`, без аккаунтов и личных данных. Setup удаляет
контакты и создаёт 34 синтетические записи, задаёт имя устройства и масштаб шрифта.
Одноимённые контакты имеют отключённую агрегацию, чтобы оставаться двумя записями.
Clock, Messages, Firefox и PhoneUse не очищаются. После успешной проверки каждой
задачи восстанавливается её seed; состояние до запуска не восстанавливается.

Из `pc/` в WSL:

```sh
python3 -m uilab.verify_heldout
python3 -m unittest discover -s uilab -p 'test_*.py'
```

В Windows используйте `C:/Users/Oleg/.local/bin/python3.14.exe` вместо `python3`.
Верификатор принимает только заданный SDK adb.exe и фиксированный serial 5580.
Перед каждой командой он читает foreground; если активен PhoneUse или Firefox
либо foreground неизвестен, выполнение прекращается. Не запускать параллельно
с человеком или другим worker. Единственная команда до определения foreground -
чтение `dumpsys activity activities`. `--task <id>` проверяет одну задачу.

Для каждой задачи верификатор делает setup, проверяет False, применяет точные
ADB-изменения из `verification.correct`, проверяет True, повторяет setup,
применяет правдоподобную ошибку из `verification.wrong` и проверяет False.
Это проверка измерителя результата, а не UI-проходимости и не качества агента.
Для невозможной задачи правильное действие - отсутствие изменений и `give_up`.
Её ADB-состояние до и после отказа одинаково; False до выполнения обеспечивается
существующим status-предикатом при `pending`, True после - при `give_up`.

Checker читает полный список строк имён, телефонов, email и флагов избранного,
а также имя устройства и масштаб шрифта. Сравнение привязано ко всему выводу,
включая raw_contact_id: чужая правка, добавление, удаление, дубликат или неполный
ответ не засчитываются. `heldout_fixtures.json` содержит синтетические ответы
для unit-тестов, а не результаты устройства. Не проверяются все столбцы Contacts
и все настройки Android; это явно ограниченный набор проверяемых полей.

## Состояние и следующий шаг

27.11.2026: sandbox заблокировал первый ADB-запрос:
`Cannot mkdir '\.android': Permission denied`. Живые False/True/False проверки
не выполнялись. 52 unit-теста прошли. Нужно запустить верификатор вне sandbox;
до этого формат content-provider вывода, доступность локальных контактов,
сохранение raw_contact_id после UI-редактирования и значение максимального
шрифта 1.3 остаются предположениями, не подтверждёнными на этом AVD.
После верификации можно отдельно измерить агент; в этой задаче модели запрещены.

Выбор файла задач поддерживается без запуска ADB или модели:

```sh
python3 -m uilab.run_eval --serial emulator-5580 \
  --adb /mnt/c/Users/Oleg/.local/share/android-agent-tools/sdk/platform-tools/adb.exe \
  --tasks uilab/tasks_heldout.json --dry-run
```

Для последующего реального eval нужно убрать `--dry-run`; предварительную
проверку занятости эмулятора выполняет оператор. Поведение Device не менялось.

## Run 2026-11-27 (sonnet low and Codex default fast model, one run each)
First pass: sonnet 5/6, Codex 4/6. Defects found in the set itself and fixed afterwards (prompt, loop and policy unchanged): the phone check rejected the formatted number the Contacts editor stores; Settings search (settings.intelligence) was missing from the allowlist; seeding time counted against the 300 s task limit. After the fixes the affected tasks were rerun (see map in the captain notes); the set is no longer untouched held-out data for those three defects.
