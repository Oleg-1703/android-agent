"""Offline, bounded voice transcription. Run in the optional voice environment."""
import argparse
import json
from pathlib import Path


def decode_bounded(path: Path):
    import av
    import numpy as np
    blocks = []
    samples = 0
    with av.open(str(path)) as container:
        if not container.streams.audio:
            raise ValueError("no_audio")
        resampler = av.AudioResampler(format="flt", layout="mono", rate=16000)
        for frame in container.decode(audio=0):
            for converted in resampler.resample(frame):
                block = converted.to_ndarray().reshape(-1)
                samples += block.size
                if samples > 125 * 16000:
                    raise ValueError("audio_too_long")
                blocks.append(block)
    if samples < 1600:
        raise ValueError("audio_too_short")
    return np.concatenate(blocks).astype(np.float32, copy=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", type=Path, required=True)
    parser.add_argument("--audio", type=Path)
    parser.add_argument("--warmup", action="store_true")
    args = parser.parse_args()
    from faster_whisper import WhisperModel
    model = WhisperModel("small", device="cpu", compute_type="int8", cpu_threads=2,
                         download_root=str(args.models), local_files_only=not args.warmup)
    if args.warmup:
        print("READY: local small/int8 transcription model cached")
        return
    if not args.audio or args.audio.stat().st_size > 4_000_000:
        parser.error("Audio file missing or too large")
    audio = decode_bounded(args.audio)
    segments, info = model.transcribe(audio, beam_size=3, vad_filter=True, condition_on_previous_text=False)
    text = " ".join(segment.text.strip() for segment in segments).strip()
    if not text or len(text) > 4000:
        raise ValueError("no_bounded_transcript")
    print(json.dumps({"text": text, "language": info.language}, ensure_ascii=False))


if __name__ == "__main__":
    main()
