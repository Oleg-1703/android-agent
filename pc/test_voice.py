"""Optional local decoder bounds. Skips cleanly without the voice environment."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import wave

from transcribe import decode_bounded


@unittest.skipUnless(importlib.util.find_spec("av") and importlib.util.find_spec("numpy"), "optional voice environment")
class VoiceBoundsTests(unittest.TestCase):
    def fixture(self, seconds, directory):
        path = Path(directory) / "audio.wav"
        with wave.open(str(path), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(8000)
            output.writeframes(b"\x00\x00" * int(seconds * 8000))
        return path

    def test_compressed_duration_cannot_bypass_sample_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                decode_bounded(self.fixture(126, directory))

    def test_almost_empty_audio_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                decode_bounded(self.fixture(0.01, directory))
