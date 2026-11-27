import contextlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import tunnel_publish as tp
import weekly_report as wr


class TelegramSettingsTest(unittest.TestCase):
    def test_own_settings_without_legacy_fallback_and_override(self):
        with patch.dict(tp.os.environ, {}, clear=True), patch.object(Path, 'home', return_value=Path('fake-home')):
            self.assertEqual(tp.settings_path(), Path('fake-home/.local/share/android-agent-tools/brain/telegram.json'))
        with patch.dict(tp.os.environ, {'AA_TELEGRAM_SETTINGS': 'own.json'}):
            self.assertEqual(tp.settings_path(), Path('own.json'))

    def test_missing_settings_publish_and_both_reports_fail_clearly(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            with patch.object(tp, 'settings_path', return_value=root / 'missing.json'):
                with self.assertRaises(tp.TelegramSettingsMissing):
                    tp.credentials()
                (root / 'tunnel.stderr.log').write_text('https://test.trycloudflare.com')
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    self.assertEqual(1, tp.main(['--state-dir', d]))
                self.assertIn('AA_TELEGRAM_SETTINGS', output.getvalue())
                for mode, function in [('--daily', 'run_daily'), ('--weekly', 'run_weekly')]:
                    output = io.StringIO()
                    with patch.object(wr, function, side_effect=lambda *args: wr.send_telegram('test')), contextlib.redirect_stdout(output):
                        self.assertEqual(1, wr.main(['--workspace', d, mode]))
                    self.assertIn('telegram.json', output.getvalue())
