from io import BytesIO
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock

from barcodes import lookup, valid_barcode
from meal_jobs import Emulator, MealJobs, validate_plan


class BarcodeTests(unittest.TestCase):
    def response(self, product):
        stream = BytesIO(json.dumps(product).encode())
        return Mock(return_value=stream)

    def test_checksum_prevents_silent_digit_guess(self):
        self.assertTrue(valid_barcode('3017620422003'))
        for value in ['3017620422004', 'x', 'http://private/', '', None]:
            self.assertFalse(valid_barcode(value))

    def test_missing_product_or_nutrients_is_not_replaced_with_an_estimate(self):
        for product in [{"status": 0}, {"status": 1, "product": {"product_name": "Food", "nutriments": {"energy-kcal_100g": 100}}}]:
            with self.assertRaisesRegex(ValueError, 'не найден|полного КБЖУ'):
                lookup('3017620422003', self.response(product))

    def test_complete_label_values_and_source_are_preserved(self):
        result = lookup('3017620422003', self.response({"status": 1, "product": {"product_name": "Food", "code": "3017620422003",
            "nutriments": {"energy-kcal_100g": 209, "proteins_100g": 9.5, "fat_100g": 11, "carbohydrates_100g": 18}}}))
        self.assertEqual(result['nutrition']['protein'], '9.5')
        self.assertEqual(result['source'], 'barcode')
        self.assertIn('3017620422003', result['source_url'])


class JobTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'jobs.sqlite'
        self.processor = Mock(return_value={"status": "complete", "receipts": []})
        self.jobs = MealJobs(self.path, self.processor)

    def tearDown(self):
        self.jobs.close()
        self.temp.cleanup()

    def done(self, key):
        end = time.monotonic() + 2
        while time.monotonic() < end:
            result = self.jobs.get(key)
            if result['status'] not in ('queued', 'running'):
                return result
            time.sleep(.01)
        self.fail('Job did not finish')

    def test_repeated_request_returns_receipt_without_repeating_write(self):
        key = 'a' * 64
        self.jobs.submit(key, {'text': 'food'})
        self.assertEqual(self.done(key)['status'], 'complete')
        self.jobs.submit(key, {'text': 'food'})
        self.assertEqual(self.processor.call_count, 1)
        with self.assertRaises(ValueError):
            self.jobs.submit(key, {'text': 'different food'})

    def test_uncertain_write_is_not_replayed_after_restart(self):
        self.processor.side_effect = RuntimeError('unknown save result')
        key = 'b' * 64
        self.jobs.submit(key, {'text': 'food'})
        self.assertEqual(self.done(key)['status'], 'uncertain')
        self.jobs.close()
        self.jobs = MealJobs(self.path, self.processor)
        self.jobs.submit(key, {'text': 'food'})
        self.assertEqual(self.processor.call_count, 1)

    def test_partial_receipts_remain_visible_on_executor_failure(self):
        self.processor.return_value = {'status': 'uncertain', 'receipts': [{'preview': 'first saved'}], 'message': 'second unknown'}
        self.jobs.submit('c' * 64, {})
        result = self.done('c' * 64)
        self.assertEqual(result['status'], 'uncertain')
        self.assertEqual(result['receipts'][0]['preview'], 'first saved')

    def test_queued_payloads_are_not_persisted_as_plaintext(self):
        self.jobs.submit('d' * 64, {'text': 'private-meal-fixture'})
        self.done('d' * 64)
        self.assertNotIn(b'private-meal-fixture', self.path.read_bytes())

    def test_empty_macros_cannot_be_silently_supplied_by_catalog(self):
        with self.assertRaises(ValueError):
            validate_plan({'meal': 'breakfast', 'items': [{'query': 'Food', 'grams': '200'}]})

    def test_failed_status_is_history_not_a_new_write_attempt(self):
        self.processor.return_value = {'status': 'failed', 'message': 'Screen unavailable'}
        key = 'f' * 64
        self.jobs.submit(key, {'text': 'food'})
        self.assertIn('Последний запрос завершился ошибкой', self.done(key)['message'])
        self.assertEqual(self.jobs.get(key), self.jobs.get(key))
        self.assertEqual(self.processor.call_count, 1)

    def test_explicit_existing_food_uses_screen_values_without_model_macros(self):
        plan = {'meal': 'dinner', 'items': [{'query': 'Chicken roll small', 'grams': '200', 'source': 'existing'}]}
        self.assertEqual(validate_plan(plan), plan)
        plan['items'][0]['nutrition'] = {'calories': '123'}
        with self.assertRaises(ValueError):
            validate_plan(plan)


class ExecutorTests(unittest.TestCase):
    def test_uncertain_result_only_retries_reading_never_saving(self):
        executor = Emulator('unused')
        executor.instrument = Mock()
        executor.run = Mock(side_effect=[b'1', b'',
            json.dumps({'execution_id': 'e' * 64, 'status': 'uncertain', 'can_reconcile': True}).encode(),
            json.dumps({'execution_id': 'e' * 64, 'status': 'complete', 'receipts': []}).encode()])
        result = executor.log({'_execution_id': 'e' * 64})
        self.assertEqual(result['status'], 'complete')
        self.assertEqual([call.args[0] for call in executor.instrument.call_args_list], ['custom-meal', 'custom-reconcile'])

    def test_stale_result_is_never_accepted_for_another_request(self):
        executor = Emulator('unused')
        executor.instrument = Mock()
        executor.run = Mock(side_effect=[b'1', b'', json.dumps({'execution_id': 'old', 'status': 'complete'}).encode()])
        with self.assertRaises(RuntimeError):
            executor.log({'_execution_id': 'new'})
        self.assertEqual(executor.instrument.call_count, 1)


if __name__ == '__main__':
    unittest.main()
