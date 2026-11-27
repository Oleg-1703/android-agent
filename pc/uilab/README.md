# UI lab: стенд для одноразового Android 11 AOSP / Google APIs эмулятора
Только Python 3 stdlib, ADB и Claude CLI или Codex CLI с действующей подпиской; API-ключи не нужны.
Запускайте из `pc`: `python3 -m unittest discover -s uilab -p 'test_*.py'`.
Проверка без устройств и моделей: `python3 uilab/run_eval.py --dry-run --serial emulator-5590 --adb adb`.
Оценка: `python3 uilab/run_eval.py --serial emulator-5590 --adb adb --model sonnet` (или `haiku`).
Codex: `--provider codex [--model gpt-6.2-sol] [--codex PATH]`, effort low; промпт и схема те же. Codex всегда добавляет глобальный `~/.codex/AGENTS.md`: отключить нечем.
Выбор: `--task dark_theme`; общий лимит `--max-calls 150`.
`--debug`: текст экрана и действия модели целиком в `uilab/results/debug/` (только одноразовый эмулятор, не коммитить).
Эмулятор запускается вручную; физические устройства и `emulator-5556` запрещены кодом.
Setup очищает тестовые будильники, контакты и SMS: используйте только одноразовый AVD без аккаунтов.
Результаты: `uilab/results/<UTC>.jsonl`, без текста экрана, мыслей, ответа и введённых строк; успех решает checker.
Экраны, где `uiautomator dump` не дожидается idle, читает `dumper/uidump.jar` (сборка: `dumper/build.sh`, нужен `UILAB_TOOLS`).
Лимиты: 25 шагов / 300 секунд на задачу; Unicode-ввод unsupported; всплывающие окна (tooltip, выпадающие списки) в наблюдение не попадают.
