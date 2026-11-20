#!/usr/bin/env python3
"""Builds the bundled public-domain sample pack for Cosic.

Sources, both pinned to a commit in manifest.json:
- VCSL (CC0): instrument banks and percussion banks.
- smpldsnds/drum-machines (public domain): one drum kit per machine folder.

Reads manifest.json, downloads the matching files into .cache/, trims, normalises
and encodes them to Ogg Vorbis under app/src/main/assets/samples/, and writes
index.json next to them. Kit banks are named "<kit>_<abbr>" and carry a "kit"
field. Re-running is safe: the cache is reused and the output directory is
rebuilt from scratch.
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
DRUM_ORDER = ["bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr", "rd", "cb", "cl", "sh", "tb", "perc"]
MIN_ROOT_GAP = 3
AGREE_FRACTION = 0.8
AGREE_TOLERANCE = 0.6
SIZE_LIMIT = 25_000_000
VCSL_REPO = "sgossner/VCSL"
CENTROID_SECONDS = 0.5
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


def load_tree(repo, sha):
    cached = CACHE / repo.replace("/", "_") / "tree.json"
    if not cached.exists():
        cached.parent.mkdir(parents=True, exist_ok=True)
        url = f"https://api.github.com/repos/{repo}/git/trees/{sha}?recursive=1"
        cached.write_bytes(http_get(url))
    tree = json.loads(cached.read_text())
    if tree.get("truncated"):
        sys.exit("GitHub tree response was truncated")
    return sorted(e["path"] for e in tree["tree"] if e["type"] == "blob")


def fetch(repo, sha, path):
    dest = CACHE / repo.replace("/", "_") / "files" / path
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        url = f"https://raw.githubusercontent.com/{repo}/{sha}/{urllib.parse.quote(path)}"
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
        files = list(pool.map(lambda p: fetch(VCSL_REPO, sha, p), by_midi.values()))
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
    paths = pick_evenly(paths, UNPITCHED_PICK)
    return [(None, truncate(trim_leading(decode(fetch(VCSL_REPO, sha, p))), spec["max_seconds"])) for p in paths]


def pick_evenly(items, count):
    """At most `count` items spread evenly over the sorted list, first and last included."""
    n = len(items)
    if n <= count:
        return items
    indices = sorted({math.floor(i * (n - 1) / (count - 1) + 0.5) for i in range(count)})
    return [items[i] for i in indices]


def spectral_centroid(x):
    """Magnitude-weighted mean frequency in Hz over the first CENTROID_SECONDS."""
    seg = x[:round(CENTROID_SECONDS * SR)].astype(np.float64)
    mag = np.abs(np.fft.rfft(seg))
    total = mag.sum()
    return float(np.dot(np.fft.rfftfreq(len(seg), 1 / SR), mag) / total) if total > 0 else 0.0


def split_by_centroid(items, labels):
    """Groups (centroid, payload) pairs into the labels by ascending centroid.

    Returns {label: [payloads]}. With fewer items than labels the outer labels are used
    (one item -> middle label; two -> lowest and highest).
    """
    ranked = sorted(items, key=lambda it: it[0])  # (centroid, payload)
    n = len(ranked)
    if n == 0:
        return {}
    if n == 1:
        return {labels[len(labels) // 2]: [ranked[0][1]]}
    if n < len(labels):
        return {labels[0]: [ranked[0][1]], labels[-1]: [r[1] for r in ranked[1:]]}
    out = {}
    for label, chunk in zip(labels, np.array_split(np.arange(n), len(labels))):
        out[label] = [ranked[i][1] for i in chunk]
    return out


def encode(x, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "-",
         "-c:a", "libvorbis", "-q:a", VORBIS_QUALITY, str(dest)],
        input=x.astype(np.float32).tobytes(), check=True,
    )


def write_bank(name, entries, pitched, folder=None, prefix="", kit=None):
    peak = max(float(np.max(np.abs(x))) for _, x in entries if len(x))
    gain = PEAK_TARGET / peak
    files = []
    for i, (root, x) in enumerate(entries):
        rel = f"{folder or name}/{prefix}{i:02d}.ogg"
        encode(x * np.float32(gain), OUT / rel)
        files.append({"file": rel, "root": root} if pitched else {"file": rel})
    bank = {"name": name, "pitched": pitched, "files": files}
    if kit:
        bank["kit"] = kit
    return bank


def vcsl_kit(spec, banks, audio):
    """Kit banks that reuse the already encoded VCSL percussion files (no new audio)."""
    by_name = {b["name"]: b for b in banks}
    kit = spec["name"]
    out = []
    for abbr, source in spec["sounds"].items():
        out.append({"name": f"{kit}_{abbr}", "kit": kit, "pitched": False, "files": by_name[source]["files"]})
    toms = by_name[spec["toms"]["bank"]]["files"]
    centroids = [(spectral_centroid(x), f) for f, (_, x) in zip(toms, audio[spec["toms"]["bank"]])]
    for label, files in split_by_centroid(centroids, spec["toms"]["labels"]).items():
        out.append({"name": f"{kit}_{label}", "kit": kit, "pitched": False, "files": sorted(files, key=lambda f: f["file"])})
    order = {a: i for i, a in enumerate(DRUM_ORDER)}
    out.sort(key=lambda b: order[b["name"].split("_", 1)[1]])
    return out


def kit_name(folder):
    return re.sub(r"[^a-z0-9]", "", folder.lower())


def classify(rel, rules, default):
    for rule in rules:
        if rule["compiled"].search(rel):
            return rule["abbr"]
    return default


def drum_machine_banks(spec, tree):
    """One kit per machine folder: {abbr: [source paths]} -> written banks, plus a mapping log."""
    repo, sha = spec["repo"], spec["commit"]
    prefs = {ext: i for i, ext in enumerate(spec["audio_preference"])}
    rules = [dict(r, compiled=re.compile(r["regex"])) for r in spec["rules"]]
    machines = sorted({p.split("/")[0] for p in tree if "/" in p and not p.startswith(".")
                       and p.rsplit(".", 1)[-1].lower() in prefs})
    cache = {}

    def audio(path):
        if path not in cache:
            cache[path] = truncate(trim_leading(decode(fetch(repo, sha, path))), spec["max_seconds"])
        return cache[path]

    banks, log = [], []
    for machine in machines:
        kit = kit_name(machine)
        stems = {}
        for p in tree:
            stem, _, ext = p.rpartition(".")
            if p.startswith(machine + "/") and ext.lower() in prefs:
                if stem not in stems or prefs[ext.lower()] < prefs[stems[stem].rsplit(".", 1)[-1].lower()]:
                    stems[stem] = p
        groups = {}
        for stem in sorted(stems):
            rel = stem[len(machine) + 1:].lower()
            groups.setdefault(classify(rel, rules, spec["default_abbr"]), []).append(stems[stem])
        generic = groups.pop("tom", [])
        if generic:
            ranked = [(spectral_centroid(audio(p)), p) for p in generic]
            for label, paths in split_by_centroid(ranked, spec["generic_tom_labels"]).items():
                groups.setdefault(label, []).extend(paths)
        order = {a: i for i, a in enumerate(DRUM_ORDER)}
        for abbr in sorted(groups, key=lambda a: order[a]):
            paths = sorted(groups[abbr])
            kept = pick_evenly(paths, spec["max_per_bank"])
            entries = [(None, x) for x in (audio(p) for p in kept) if len(x)]
            for p in paths:
                status = f"{kit}/{abbr}_{kept.index(p):02d}.ogg" if p in kept else "dropped (over max_per_bank)"
                log.append(f"  {machine}/{p[len(machine) + 1:]:<40} -> {abbr:<5} {status}")
            banks.append(write_bank(f"{kit}_{abbr}", entries, False, folder=kit, prefix=f"{abbr}_", kit=kit))
    return banks, machines, log


def main():
    manifest = json.loads((HERE / "manifest.json").read_text())
    sha = manifest["commit"]
    CACHE.mkdir(exist_ok=True)
    tree = load_tree(VCSL_REPO, sha)
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    banks, audio = [], {}
    for spec in manifest["pitched"]:
        banks.append(write_bank(spec["name"], pitched_files(sha, spec, tree), True))
    for spec in manifest["unpitched"]:
        entries = unpitched_files(sha, spec, tree)
        audio[spec["name"]] = entries
        banks.append(write_bank(spec["name"], entries, False))
    instrument_banks = len(banks)

    banks.extend(vcsl_kit(manifest["vcsl_kit"], banks, audio))
    dm = manifest["drum_machines"]
    dm_banks, machines, log = drum_machine_banks(dm, load_tree(dm["repo"], dm["commit"]))
    banks.extend(dm_banks)

    source = f"VCSL {sha[:7]} (CC0-1.0); {dm['repo']} {dm['commit'][:7]} (public domain)"
    index = {"version": 1, "source": source, "banks": banks}
    (OUT / "index.json").write_text(json.dumps(index, separators=(",", ":")))

    print("drum machine file mapping:")
    print("\n".join(log))
    seen, total = set(), 0
    for bank in banks:
        files = [f["file"] for f in bank["files"] if f["file"] not in seen]
        seen.update(files)
        size = sum((OUT / f).stat().st_size for f in files)
        total += size
        roots = f" roots {bank['files'][0]['root']}..{bank['files'][-1]['root']}" if bank["pitched"] else ""
        shared = "" if len(files) == len(bank["files"]) else " (shares files)"
        print(f"{bank['name']:<30}{'pitched' if bank['pitched'] else 'unpitched':<10}{len(bank['files']):>3} files{size / 1024:>9.0f} KB{roots}{shared}")
    total += (OUT / "index.json").stat().st_size
    print(f"{instrument_banks} instrument/percussion banks, {len(banks) - instrument_banks} kit banks "
          f"({len(machines) + 1} kits), total {total / 1e6:.2f} MB")
    if total > SIZE_LIMIT:
        sys.exit(f"Pack is {total} bytes, over the {SIZE_LIMIT} byte limit")


if __name__ == "__main__":
    main()
