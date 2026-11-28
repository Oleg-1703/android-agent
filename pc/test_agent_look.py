import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import agent_look
import test_brain


def request():
    return {"task": "Как добавить ужин?", "package": agent_look.PACKAGE, "size": [1080, 2400],
            "nodes": [{"index": 0, "cls": "TextView", "text": "Ужин", "desc": "", "id": "title",
                       "flags": ["clickable", "checked"], "bounds": [0, 0, 100, 100]}], "truncated": False}


class LookTests(unittest.TestCase):
    def test_validation_and_same_lab_rendering(self):
        screen = agent_look.validate(request())
        self.assertEqual(agent_look.render(screen), screen.compact())
        self.assertIn('[0] TextView "Ужин" desc="" id="title" clickable,checked 0,0,100,100', screen.compact())
        for update in ({"task": ""}, {"task": 1}, {"package": "keyboard"}, {"nodes": [None]},
                       {"nodes": request()["nodes"] * 301}, {"size": [True, 20]}, {"truncated": "false"}):
            with self.subTest(update=update), self.assertRaises(ValueError):
                agent_look.validate({**request(), **update})
        for key, value in (("index", True), ("index", 1), ("text", "x" * 1001), ("flags", ["execute"]),
                           ("bounds", [0, 0, 2000, 100]), ("bounds", [0, 0, 0, 100]), ("desc", None)):
            body = request()
            body["nodes"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                agent_look.validate(body)

    @patch.object(agent_look.telemetry, "emit")
    def test_fake_model_reply_retention_and_metadata(self, emit):
        model = Mock(return_value=({"action": "tap", "index": 0, "thought": "Открывает раздел ужина"}, {"ms": 123}, None))
        with tempfile.TemporaryDirectory() as folder:
            result = agent_look.look(request(), folder, decider=model)
            self.assertEqual(result["step"], "нажать «Ужин»")
            self.assertEqual(result["model_ms"], 123)
            saved = json.loads(next((Path(folder) / "agent-looks").glob("*.json")).read_text(encoding="utf-8"))
            self.assertEqual(saved["request"], request())
            self.assertEqual(saved["answer"], result)
            for i in range(52):
                (Path(folder) / "agent-looks" / f"000-{i}.json").write_text("{}")
            agent_look.look(request(), folder, decider=model)
            self.assertEqual(len(list((Path(folder) / "agent-looks").glob("*.json"))), 50)
        self.assertIn("previous_steps", model.call_args.args[0])
        self.assertEqual(model.call_args.args[1], 60)
        self.assertEqual(emit.call_args.args, ("agent_look",))
        self.assertEqual(set(emit.call_args.kwargs), {"ok", "ms", "n"})
        self.assertEqual(emit.call_args.kwargs["n"], {"nodes": 1})

    def test_action_words_and_no_retry(self):
        screen = agent_look.validate(request())
        for action, words in (("done", "готово"), ("give_up", "не могу"), ("scroll_down", "прокрутить вниз")):
            self.assertEqual(agent_look.words({"action": action}, screen), words)
        self.assertIn("ввести «abc»", agent_look.words({"action": "type", "index": 0, "text": "abc"}, screen))
        with self.assertRaises(ValueError):
            agent_look.words({"action": "tap", "index": 50}, screen)
        model = Mock(return_value=(None, {"ms": 100}, "model_timeout"))
        with tempfile.TemporaryDirectory() as folder, self.assertRaises(RuntimeError):
            agent_look.look(request(), folder, decider=model)
        model.assert_called_once()


class LookEndpointTests(unittest.TestCase):
    setUp = test_brain.BrainTests.setUp
    tearDown = test_brain.BrainTests.tearDown
    post = test_brain.BrainTests.post

    def test_endpoint(self):
        with patch.object(agent_look, "Decider") as factory:
            factory.return_value.return_value = ({"action": "done", "thought": "Всё готово"}, {"ms": 7}, None)
            self.assertEqual(self.post(request(), token="wrong", path="/v1/agent/look")[0], 401)
            self.assertEqual(self.post([], path="/v1/agent/look")[0], 400)
            factory.assert_not_called()
            status, result = self.post(request(), path="/v1/agent/look")
            self.assertEqual(status, 200)
            self.assertEqual(result["step"], "готово")
            factory.assert_called_once_with(model="sonnet", cli="unused")

    def test_endpoint_busy_and_failure_never_retry(self):
        with patch.object(agent_look, "Decider") as factory:
            with self.server.busy:
                self.assertEqual(self.post(request(), path="/v1/agent/look")[0], 429)
            factory.assert_not_called()
            factory.return_value.return_value = (None, {"ms": 8}, "model_timeout")
            status, result = self.post(request(), path="/v1/agent/look")
            self.assertEqual((status, result), (503, {"error": "look_unavailable"}))
            factory.return_value.assert_called_once()
            self.assertTrue(self.server.busy.acquire(blocking=False))
            self.server.busy.release()
