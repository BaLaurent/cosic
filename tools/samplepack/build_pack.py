#!/usr/bin/env python3
"""Builds the bundled CC0 sample pack for Cosic from a pinned VCSL commit.

Reads manifest.json, downloads the matching files into .cache/, trims, normalises
and encodes them to Ogg Vorbis under app/src/main/assets/samples/, and writes
index.json next to them. Re-running is safe: the cache is reused and the output
directory is rebuilt from scratch.
"""
import json
import math
import re
import shutil
import statistics
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CACHE = HERE / ".cache"
OUT = ROOT / "app/src/main/assets/samples"

SR = 44100
VORBIS_QUALITY = "4"
PITCHED_MAX_SECONDS = 4
TRIM_THRESHOLD = 10 ** (-50 / 20)
PREROLL = round(0.002 * SR)
FADE_OUT = round(0.05 * SR)
PEAK_TARGET = 10 ** (-1 / 20)
UNPITCHED_PICK = 8
MIN_ROOT_GAP = 3
AGREE_FRACTION = 0.8
AGREE_TOLERANCE = 0.6
SIZE_LIMIT = 25_000_000
NOTE_SEMITONES = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}

YIN_WINDOW = 4096
YIN_MAX_TAU = 2048
YIN_MIN_TAU = SR // 2000
YIN_THRESHOLD = 0.15


def http_get(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "cosic-samplepack"}), timeout=60) as r:
                return r.read()
        except Exception:
            if attempt == 3:
                raise
            time.sleep(1 + attempt)


def load_tree(sha):
    cached = CACHE / "tree.json"
    if not cached.exists():
        url = f"https://api.github.com/repos/sgossner/VCSL/git/trees/{sha}?recursive=1"
        cached.write_bytes(http_get(url))
    tree = json.loads(cached.read_text())
    if tree.get("truncated"):
        sys.exit("GitHub tree response was truncated")
    return sorted(e["path"] for e in tree["tree"] if e["type"] == "blob")


def fetch(sha, path):
    dest = CACHE / "files" / path
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        url = f"https://raw.githubusercontent.com/sgossner/VCSL/{sha}/{urllib.parse.quote(path)}"
        tmp = dest.with_suffix(".part")
        tmp.write_bytes(http_get(url))
        tmp.rename(dest)
    return dest


def decode(file):
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(file), "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def trim_leading(x):
    loud = np.flatnonzero(np.abs(x) > TRIM_THRESHOLD)
    if loud.size == 0:
        return x[:0]
    return x[max(0, int(loud[0]) - PREROLL):]


def truncate(x, max_seconds):
    limit = round(max_seconds * SR)
    if len(x) <= limit:
        return x
    x = x[:limit].copy()
    x[-FADE_OUT:] *= np.linspace(1.0, 0.0, FADE_OUT, dtype=np.float32)
    return x


def yin_frame(frame):
    """Fundamental frequency in Hz of one 6144-sample frame, or None."""
    n = YIN_WINDOW
    head = frame[:n].astype(np.float64)
    size = 1 << (n + YIN_MAX_TAU).bit_length()
    spec = np.fft.rfft(frame, size)
    # cross-correlation r[tau] = sum_j head[j] * frame[j + tau]
    corr = np.fft.irfft(np.conj(np.fft.rfft(head, size)) * spec, size)[:YIN_MAX_TAU]
    energy = np.concatenate(([0.0], np.cumsum(frame.astype(np.float64) ** 2)))
    e_head = energy[n]
    e_shift = energy[n + np.arange(YIN_MAX_TAU)] - energy[np.arange(YIN_MAX_TAU)]
    diff = e_head + e_shift - 2 * corr
    diff[0] = 0.0
    cmnd = np.ones(YIN_MAX_TAU)
    running = np.cumsum(diff[1:])
    cmnd[1:] = diff[1:] * np.arange(1, YIN_MAX_TAU) / np.maximum(running, 1e-12)
    tau = YIN_MIN_TAU
    while tau < YIN_MAX_TAU - 1:
        if cmnd[tau] < YIN_THRESHOLD:
            while tau + 1 < YIN_MAX_TAU - 1 and cmnd[tau + 1] < cmnd[tau]:
                tau += 1
            a, b, c = cmnd[tau - 1], cmnd[tau], cmnd[tau + 1]
            denom = a - 2 * b + c
            shift = 0.5 * (a - c) / denom if denom != 0 else 0.0
            return SR / (tau + shift)
        tau += 1
    return None


def detect_midi(x):
    """Median YIN pitch (fractional MIDI) over 100-600 ms, or None."""
    seg = x[round(0.1 * SR):round(0.6 * SR)]
    need = YIN_WINDOW + YIN_MAX_TAU
    found = []
    for start in range(0, len(seg) - need + 1, YIN_WINDOW // 2):
        f0 = yin_frame(seg[start:start + need])
        if f0:
            found.append(12 * math.log2(f0 / 440.0) + 69)
    return statistics.median(found) if found else None


def filename_midi(name):
    m = re.fullmatch(r"([A-G])(#?)(-?\d)", name)
    return 12 * (int(m.group(3)) + 1) + NOTE_SEMITONES[m.group(1)] + (1 if m.group(2) else 0)


def pitched_files(sha, spec, tree):
    regex = re.compile(spec["regex"])
    matches = []
    for path in tree:
        m = regex.search(path)
        if m:
            matches.append((filename_midi(m.group(1)), path))
    if not matches:
        sys.exit(f"{spec['name']}: regex matched no files")
    by_midi = {}
    for midi, path in matches:
        by_midi.setdefault(midi, path)
    with ThreadPoolExecutor(8) as pool:
        files = list(pool.map(lambda p: fetch(sha, p), by_midi.values()))
    audio = {midi: trim_leading(decode(f)) for midi, f in zip(by_midi, files)}

    offset = spec.get("octave_offset")
    if offset is None:
        rows, diffs = [], []
        for midi, x in audio.items():
            det = detect_midi(x)
            rows.append((midi, by_midi[midi], det))
            if det is not None:
                diffs.append(det - midi)
        offset = round(statistics.median(round(d / 12) * 12 for d in diffs)) if diffs else 0
        agree = sum(1 for d in diffs if abs(d - offset) <= AGREE_TOLERANCE)
        if agree < AGREE_FRACTION * len(rows):
            print(f"{spec['name']}: octave detection failed, {agree}/{len(rows)} files agree on offset {offset:+d}")
            for midi, path, det in rows:
                diff = "none" if det is None else f"{det - midi:+.2f}"
                print(f"  {path}  filename={midi}  detected={det}  diff={diff}")
            sys.exit(1)

    kept, last = [], None
    for midi in sorted(audio):
        root = midi + offset
        if last is None or root - last >= MIN_ROOT_GAP:
            kept.append((root, audio[midi]))
            last = root
    return [(root, truncate(x, PITCHED_MAX_SECONDS)) for root, x in kept]


def unpitched_files(sha, spec, tree):
    prefix = spec["folder"] + "/"
    paths = [p for p in tree if p.startswith(prefix) and re.fullmatch(r".*\.wav", p)]
    if not paths:
        sys.exit(f"{spec['name']}: folder matched no files")
    n = len(paths)
    if n > UNPITCHED_PICK:
        indices = sorted({math.floor(i * (n - 1) / (UNPITCHED_PICK - 1) + 0.5) for i in range(UNPITCHED_PICK)})
        paths = [paths[i] for i in indices]
    return [(None, truncate(trim_leading(decode(fetch(sha, p))), spec["max_seconds"])) for p in paths]


def encode(x, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "-",
         "-c:a", "libvorbis", "-q:a", VORBIS_QUALITY, str(dest)],
        input=x.astype(np.float32).tobytes(), check=True,
    )


def write_bank(name, entries, pitched):
    peak = max(float(np.max(np.abs(x))) for _, x in entries if len(x))
    gain = PEAK_TARGET / peak
    files = []
    for i, (root, x) in enumerate(entries):
        rel = f"{name}/{i:02d}.ogg"
        encode(x * np.float32(gain), OUT / rel)
        files.append({"file": rel, "root": root} if pitched else {"file": rel})
    return {"name": name, "pitched": pitched, "files": files}


def main():
    manifest = json.loads((HERE / "manifest.json").read_text())
    sha = manifest["commit"]
    CACHE.mkdir(exist_ok=True)
    tree = load_tree(sha)
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    banks = []
    for spec in manifest["pitched"]:
        banks.append(write_bank(spec["name"], pitched_files(sha, spec, tree), True))
    for spec in manifest["unpitched"]:
        banks.append(write_bank(spec["name"], unpitched_files(sha, spec, tree), False))

    index = {"version": 1, "source": f"VCSL {sha[:7]} (CC0-1.0)", "banks": banks}
    (OUT / "index.json").write_text(json.dumps(index, separators=(",", ":")))

    total = 0
    for bank in banks:
        size = sum((OUT / f["file"]).stat().st_size for f in bank["files"])
        total += size
        roots = f" roots {bank['files'][0]['root']}..{bank['files'][-1]['root']}" if bank["pitched"] else ""
        print(f"{bank['name']:<14}{'pitched' if bank['pitched'] else 'unpitched':<10}{len(bank['files']):>3} files{size / 1024:>9.0f} KB{roots}")
    total += (OUT / "index.json").stat().st_size
    print(f"{len(banks)} banks, total {total / 1e6:.2f} MB")
    if total > SIZE_LIMIT:
        sys.exit(f"Pack is {total} bytes, over the {SIZE_LIMIT} byte limit")


if __name__ == "__main__":
    main()
