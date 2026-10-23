#!/usr/bin/env python3
"""
Speech round trip without a headset, using the same sherpa-onnx models as the app:

  Polish TTS says a phrase -> noise is mixed in -> wake word spotter listens ->
  after "JARVIS" fires, silero VAD cuts the rest -> Whisper transcribes it.

Runs both wake modes of the app (accurate: VAD + Whisper base gate; economy: keyword spotter).

Writes:
  <out>/transcripts.tsv  case, expected command kind, text the wake check sees, text the command uses
                         (read by RoundtripTranscriptsTest)
  <out>/replies/*.wav    plain TTS replies, for the voice effect render test
  <out>/report.txt       wake word hits, false alarms, transcripts and timings

Usage:
  python tools/roundtrip.py --models <dir with the downloaded models> --out tools/out [--command-model small]
"""
import argparse
import os
import time

import numpy as np
import sherpa_onnx
import soundfile as sf

KWS_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
TTS_DIR = "vits-piper-pl_PL-darkman-medium"
RATE = 16000

# (id, spoken text, expected command kind). The kind names match LocalCommands.Kind on the Kotlin side;
# "AI" means no local command should match and the question goes to Claude.
CASES = [
    ("spotify", "Dżarwis, uruchom Spotify.", "LAUNCH"),
    ("youtube", "Dżarwis, otwórz YouTube.", "LAUNCH"),
    ("beatsaber", "Dżarwis, odpal Beat Saber.", "LAUNCH"),
    ("stopklatka", "Dżarwis, włącz Stopklatkę.", "LAUNCH"),
    ("louder", "Dżarwis, głośniej.", "VOLUME_UP"),
    ("volume50", "Dżarwis, ustaw głośność na pięćdziesiąt procent.", "VOLUME_SET"),
    ("time", "Dżarwis, która jest godzina?", "TIME"),
    ("battery", "Dżarwis, ile mam baterii?", "BATTERY"),
    ("weather", "Dżarwis, jaka jutro będzie pogoda w Warszawie?", "AI"),
    ("joke", "Dżarwis, opowiedz mi krótki dowcip.", "AI"),
]

# Phrases without the wake word: the spotter must stay quiet.
NEGATIVES = [
    "Jarek wysłał mi wczoraj wiadomość.",
    "To jest zwykła rozmowa o pogodzie i o szkole.",
    "Darek, podaj mi proszę pilota od telewizora.",
    "Jaś i Małgosia poszli do lasu.",
    "Zagrajmy w coś innego wieczorem.",
]

REPLIES = {
    "launch": "Oczywiście, sir. Uruchamiam Spotify.",
    "time": "Jest godzina czternasta piętnaście, sir.",
    "greeting": "Dzień dobry, sir. Wszystkie systemy działają prawidłowo.",
    "weather": "Jutro w Warszawie zapowiadają słońce i około piętnastu stopni. Idealna pogoda na spacer, sir.",
}


def resample(x, src, dst):
    if src == dst:
        return x.astype(np.float32)
    n = int(round(len(x) * dst / src))
    t = np.linspace(0, len(x) - 1, n)
    return np.interp(t, np.arange(len(x)), x).astype(np.float32)


def with_noise(x, snr_db, rng):
    pad = np.zeros(int(0.6 * RATE), dtype=np.float32)
    x = np.concatenate([pad, x, pad])
    p = np.mean(x ** 2) + 1e-9
    noise = rng.normal(0, np.sqrt(p / (10 ** (snr_db / 10))), len(x)).astype(np.float32)
    return x + noise


def make_tts(models):
    d = os.path.join(models, TTS_DIR)
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=os.path.join(d, "pl_PL-darkman-medium.onnx"),
                tokens=os.path.join(d, "tokens.txt"),
                data_dir=os.path.join(d, "espeak-ng-data"),
            ),
            num_threads=2,
        ),
    )
    return sherpa_onnx.OfflineTts(cfg)


def make_kws(models, keywords_file, threshold, score):
    d = os.path.join(models, KWS_DIR)
    return sherpa_onnx.KeywordSpotter(
        tokens=os.path.join(d, "tokens.txt"),
        encoder=os.path.join(d, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
        decoder=os.path.join(d, "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
        joiner=os.path.join(d, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
        num_threads=1,
        keywords_file=keywords_file,
        keywords_score=score,
        keywords_threshold=threshold,
        num_trailing_blanks=1,
        max_active_paths=4,
        provider="cpu",
    )


def spot(kws, audio):
    """Feeds 100 ms chunks like the app. Returns the sample index where the keyword fired, or None."""
    stream = kws.create_stream()
    chunk = RATE // 10
    for i in range(0, len(audio), chunk):
        stream.accept_waveform(RATE, audio[i:i + chunk])
        while kws.is_ready(stream):
            kws.decode_stream(stream)
            if kws.get_result(stream):
                kws.reset_stream(stream)
                return i + chunk
    return None


def cut_speech(models, audio):
    """What the app does after the wake word: silero VAD, end of speech after 0.8 s of silence."""
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = os.path.join(models, "silero_vad.onnx")
    cfg.silero_vad.threshold = 0.5
    cfg.silero_vad.min_silence_duration = 0.8
    cfg.silero_vad.min_speech_duration = 0.2
    cfg.silero_vad.max_speech_duration = 10
    cfg.sample_rate = RATE
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=30)
    w = cfg.silero_vad.window_size
    out = []
    for i in range(0, len(audio) - w + 1, w):
        vad.accept_waveform(audio[i:i + w])
        while not vad.empty():
            out.append(np.array(vad.front.samples, dtype=np.float32))
            vad.pop()
    vad.flush()
    while not vad.empty():
        out.append(np.array(vad.front.samples, dtype=np.float32))
        vad.pop()
    return np.concatenate(out) if out else np.zeros(0, dtype=np.float32)


def make_whisper(models, size):
    d = os.path.join(models, f"whisper-{size}")
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=os.path.join(d, f"{size}-encoder.int8.onnx"),
        decoder=os.path.join(d, f"{size}-decoder.int8.onnx"),
        tokens=os.path.join(d, f"{size}-tokens.txt"),
        language="pl",
        task="transcribe",
        num_threads=4,
        decoding_method="greedy_search",
    )


def transcribe(rec, audio):
    s = rec.create_stream()
    s.accept_waveform(RATE, audio)
    rec.decode_stream(s)
    return s.result.text.strip()


# Same keyword variants and sensitivity mapping as speech/Speech.kt (WakeWord.keywords).
VARIANTS = [
    "▁JA R VI S", "▁JA R VI S ▁IS", "▁SHA R VI S", "▁CHA R VI S", "▁JA R ▁VI S", "▁JA R VE S",
    "▁JA R ▁W IS", "▁CHA R ▁VI S", "▁SHA R ▁VI S", "▁JA R VI E", "▁JA R VI A",
]


def keywords_file(path, sensitivity):
    score = 5 + 15 * sensitivity
    threshold = 0.01 * 0.1 ** sensitivity
    with open(path, "w", encoding="utf-8") as f:
        f.write("".join(f"{v} :{score:.1f} #{threshold:.4f} @JARVIS\n" for v in VARIANTS))
    return score, threshold


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", required=True)
    ap.add_argument("--out", default="tools/out")
    ap.add_argument("--sensitivity", type=float, default=0.5)
    ap.add_argument("--command-model", default="small", choices=["base", "small"])
    ap.add_argument("--snr", type=float, default=30.0, help="speech to noise in dB (the headset mic is close to the mouth)")
    args = ap.parse_args()

    os.makedirs(os.path.join(args.out, "replies"), exist_ok=True)
    rng = np.random.default_rng(7)
    tts = make_tts(args.models)
    kw_path = os.path.join(args.out, "keywords.txt")
    score, thr = keywords_file(kw_path, args.sensitivity)
    kws = make_kws(args.models, kw_path, thr, score)
    gate = make_whisper(args.models, "base")
    cmd = gate if args.command_model == "base" else make_whisper(args.models, args.command_model)
    report, rows = [], []
    t_gate = t_cmd = 0.0
    n_gate = n_cmd = 0

    def say(text, speed=1.0):
        a = tts.generate(text, sid=0, speed=speed)
        return resample(np.array(a.samples, dtype=np.float32), a.sample_rate, RATE)

    def timed(rec, audio):
        t0 = time.perf_counter()
        text = transcribe(rec, audio)
        return text, time.perf_counter() - t0

    for cid, text, kind in CASES:
        for speed in (0.95, 1.1):
            audio = with_noise(say(text, speed), args.snr, rng)
            tag = f"{cid}@{speed}"

            # accurate mode: VAD phrase -> Whisper base on its first 1.8 s -> (wake?) -> command model on all of it
            phrase = cut_speech(args.models, audio)
            head_text, dt = timed(gate, phrase[: int(1.8 * RATE)])
            t_gate += dt; n_gate += 1
            full_text, dt = timed(cmd, phrase)
            t_cmd += dt; n_cmd += 1
            rows.append(f"accurate-{tag}\t{kind}\t{head_text}\t{full_text}")
            report.append(f"accurate {tag:<16} head: {head_text!r:<42} full: {full_text!r}")

            # economy mode: keyword spotter -> last 1.5 s + rest -> VAD -> command model
            at = spot(kws, audio)
            if at is None:
                rows.append(f"economy-{tag}\t{kind}\t\t")
                report.append(f"economy  {tag:<16} keyword spotter MISSED")
            else:
                start = max(0, at - int(1.5 * RATE))
                eco_text, _ = timed(cmd, cut_speech(args.models, audio[start:]))
                rows.append(f"economy-{tag}\t{kind}\t{eco_text}\t{eco_text}")
                report.append(f"economy  {tag:<16} spotted at {at / RATE:.2f}s: {eco_text!r}")

    # phrases without the name: the accurate gate must not wake, the spotter should stay quiet
    for i, text in enumerate(NEGATIVES):
        audio = with_noise(say(text), args.snr, rng)
        head_text, _ = timed(gate, cut_speech(args.models, audio)[: int(1.8 * RATE)])
        rows.append(f"negative-{i}\tNONE\t{head_text}\t{head_text}")
        report.append(f"negative {i}: spotter {'FALSE ALARM' if spot(kws, audio) is not None else 'quiet'}, head: {head_text!r}")

    report.append(f"Whisper base (gate, 1.8 s): {t_gate / max(1, n_gate):.2f} s per phrase; "
                  f"{args.command_model} (command): {t_cmd / max(1, n_cmd):.2f} s per phrase (this machine, 4 threads)")
    with open(os.path.join(args.out, "transcripts.tsv"), "w", encoding="utf-8") as f:
        f.write("\n".join(rows) + "\n")
    for name, text in REPLIES.items():
        a = tts.generate(text, sid=0, speed=1.0)
        sf.write(os.path.join(args.out, "replies", f"{name}.wav"), np.array(a.samples), a.sample_rate, subtype="PCM_16")
    with open(os.path.join(args.out, "report.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(report) + "\n")
    print("\n".join(report))


if __name__ == "__main__":
    main()
