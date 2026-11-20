import base64
import http.client
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

import brain


class CustomTransportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.server = brain.BrainServer(('127.0.0.1', 0), {'pairing_token': 'fixture-token' * 4}, 'unused', Path(self.temp.name))
        self.processor = Mock(return_value={'status': 'complete', 'receipts': [{'preview': 'verified fixture'}]})
        self.server.jobs.processor = self.processor
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.jobs.close()
        self.server.server_close()
        self.temp.cleanup()

    def request(self, method, path, payload=None, token='fixture-token' * 4):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        connection.request(method, path, None if payload is None else json.dumps(payload), {'Authorization': 'Bearer ' + token})
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def payload(self):
        return {'request_id': 'a' * 64, 'text': 'lunch food', 'date': '2026-11-16',
                'images': [{'media_type': 'image/png', 'data': base64.b64encode(b'\x89PNG\r\n\x1a\nfixture').decode()}],
                'voice': {'data': base64.b64encode(b'OggSfixture').decode()}}

    def test_combined_label_and_voice_are_one_job_with_a_durable_receipt(self):
        body = self.payload()
        self.assertEqual(self.request('POST', '/v1/log', body)[0], 202)
        for _ in range(100):
            status, receipt = self.request('GET', '/v1/jobs/' + body['request_id'])
            if receipt['status'] == 'complete':
                break
            time.sleep(.01)
        self.assertEqual(receipt['receipts'][0]['preview'], 'verified fixture')
        self.assertEqual(self.request('POST', '/v1/log', body)[0], 202)
        self.assertEqual(self.processor.call_count, 1)
        self.assertIn('voice', self.processor.call_args.args[0])
        self.assertEqual(len(self.processor.call_args.args[0]['images']), 1)

    def test_unauthorized_upload_or_receipt_access_never_executes(self):
        self.assertEqual(self.request('POST', '/v1/log', self.payload(), token='wrong')[0], 401)
        self.assertEqual(self.request('GET', '/v1/jobs/' + 'a' * 64, token='wrong')[0], 401)
        self.processor.assert_not_called()

    def test_phone_claim_and_receipt_require_pairing_and_follow_the_lifecycle(self):
        body = self.payload()
        body['target'] = 'phone'
        self.processor.return_value = {'status': 'prepared', 'target': 'phone', 'plan': {'items': []}}
        self.request('POST', '/v1/log', body)
        for _ in range(100):
            if self.request('GET', '/v1/jobs/' + body['request_id'])[1]['status'] == 'prepared':
                break
            time.sleep(.01)
        path = '/v1/jobs/' + body['request_id']
        self.assertEqual(self.request('POST', path + '/claim', {}, token='wrong')[0], 401)
        self.assertEqual(self.request('POST', path + '/claim', {})[1]['status'], 'claimed')
        self.assertEqual(self.request('POST', path + '/claim', {})[1]['status'], 'already_started')
        receipt = {'status': 'complete', 'receipts': [{'preview': 'verified on phone'}]}
        self.assertEqual(self.request('POST', path + '/receipt', receipt, token='wrong')[0], 401)
        self.assertEqual(self.request('POST', path + '/receipt', receipt)[1]['status'], 'accepted')
        self.assertEqual(self.request('GET', path)[1]['status'], 'complete')

    @patch.object(brain, 'Emulator')
    @patch.object(brain, 'parse_meal')
    def test_phone_preparation_never_constructs_or_calls_emulator(self, parse, emulator):
        parse.return_value = {'date': '2026-11-17', 'meal': 'breakfast', 'question': '', 'items': [
            {'query': 'Fixture', 'grams': '100', 'nutrition': {'basis_grams': '100', 'calories': '89', 'protein': '1', 'fat': '0', 'carbs': '20'}}]}
        result = self.server.process_custom_meal({'text': 'fixture', 'date': '2026-11-17', 'target': 'phone', '_execution_id': 'a' * 64})
        self.assertEqual(result['status'], 'prepared')
        self.assertEqual(result['plan']['_execution_id'], 'a' * 64)
        emulator.assert_not_called()

    def test_remote_image_urls_and_reused_ids_with_different_data_are_rejected(self):
        body = self.payload()
        body['images'] = [{'url': 'https://private.invalid/'}]
        self.assertEqual(self.request('POST', '/v1/log', body)[0], 400)
        self.processor.assert_not_called()
        body = self.payload()
        self.request('POST', '/v1/log', body)
        body['text'] = 'different meal'
        self.assertEqual(self.request('POST', '/v1/log', body)[0], 400)

    @patch.object(brain, 'Emulator')
    @patch.object(brain, 'parse_meal')
    def test_emulator_receipt_preserves_whole_meal_composition(self, parse, emulator):
        parse.return_value = {'date': '2026-11-17', 'meal': 'lunch', 'question': '', 'notes': 'Estimate', 'items': [
            {'query': 'Fixture', 'grams': '100', 'nutrition': {'basis_grams': '100', 'calories': '89', 'protein': '1', 'fat': '0', 'carbs': '20'}}]}
        emulator.return_value.log.return_value = {'status': 'complete', 'notes': 'Estimate\nComposition: rice, fish'}
        result = self.server.process_custom_meal({'text': 'fixture', 'date': '2026-11-17', '_execution_id': 'a' * 32})
        self.assertEqual(result['notes'], 'Estimate\nComposition: rice, fish')
