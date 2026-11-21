#!/usr/bin/env python3
"""Diagnosing an audio parity failure: Strudel's render (~/.cache/cosic-audio-ref/<id>.f32) against Cosic's (engine/build/audio-parity/<id>.f32).

  python3 tools/strudel-ref/audio/compare.py <id> [--from SECONDS --to SECONDS] [--png out.png] [--sr 48000]

Prints the level per half second on both sides, the lag and gain at which the two waveforms agree best (a deterministic sound should agree to within a rounding error at
lag 0 and gain 1), the energy of both in octave bands, and with --png a plot of the waveforms and spectrograms of the window.
Both files are planar float32: all of the left channel, then all of the right.
"""
import argparse
import os
import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument('id')
ap.add_argument('--from', dest='t0', type=float, default=0.0)
ap.add_argument('--to', dest='t1', type=float, default=None)
ap.add_argument('--png')
ap.add_argument('--sr', type=int, default=48000)
ap.add_argument('--ref', default=os.path.expanduser('~/.cache/cosic-audio-ref'))
ap.add_argument('--got', default=os.path.join(os.path.dirname(__file__), '../../../engine/build/audio-parity'))
a = ap.parse_args()


def load(path):
    x = np.fromfile(path, dtype='<f4')
    n = len(x) // 2
    return np.stack([x[:n], x[n:]])


ref = load(os.path.join(a.ref, a.id + '.f32'))
got = load(os.path.join(a.got, a.id + '.f32'))
n = min(ref.shape[1], got.shape[1])
sr = a.sr
i0 = int(a.t0 * sr)
i1 = n if a.t1 is None else min(n, int(a.t1 * sr))
r, g = ref[:, i0:i1], got[:, i0:i1]
db = lambda v: 20 * np.log10(max(v, 1e-5))
print(f'{a.id}: reference {ref.shape[1]} frames, render {got.shape[1]} frames; peak {db(np.abs(ref).max()):.1f} / {db(np.abs(got).max()):.1f} dBFS')
step = sr // 2
print(' t(s)   ref L/R rms dB      cosic L/R rms dB')
for s in range(i0, i1, step):
    seg = slice(s - i0, min(s - i0 + step, i1 - i0))
    rr = [db(np.sqrt(np.mean(r[c, seg] ** 2))) for c in range(2)]
    gg = [db(np.sqrt(np.mean(g[c, seg] ** 2))) for c in range(2)]
    flag = '  <<<' if max(abs(rr[0] - gg[0]), abs(rr[1] - gg[1])) > 1.5 else ''
    print(f'{s / sr:5.1f}  {rr[0]:7.1f} {rr[1]:7.1f}    {gg[0]:7.1f} {gg[1]:7.1f}{flag}')
# best lag and gain between the left channels
m = min(len(r[0]), 1 << 18)
x, y = r[0, :m].astype(np.float64), g[0, :m].astype(np.float64)
if np.abs(x).max() > 1e-6 and np.abs(y).max() > 1e-6:
    corr = np.fft.irfft(np.fft.rfft(x, 2 * m) * np.conj(np.fft.rfft(y, 2 * m)))
    lag = int(np.argmax(corr))
    lag = lag if lag < m else lag - 2 * m
    gain = np.dot(x, np.roll(y, lag)) / max(np.dot(np.roll(y, lag), np.roll(y, lag)), 1e-30)
    resid = x - gain * np.roll(y, lag)
    print(f'best lag {lag} samples ({lag / sr * 1000:.2f} ms), gain {gain:.4f} ({db(gain):.2f} dB), residual {db(np.sqrt(np.mean(resid ** 2)) / max(np.sqrt(np.mean(x ** 2)), 1e-9)):.1f} dB below the reference')
# octave bands over the whole window, both channels
def bands(x):
    spec = np.abs(np.fft.rfft(x.mean(axis=0) * np.hanning(x.shape[1]))) ** 2
    f = np.fft.rfftfreq(x.shape[1], 1 / sr)
    out = []
    for b in range(8):
        c = 62.5 * 2 ** b
        sel = (f >= c / 2 ** 0.5) & (f < c * 2 ** 0.5)
        out.append(10 * np.log10(max(spec[sel].sum() / x.shape[1] ** 2 * 4, 1e-12)))
    return out
br, bg = bands(r), bands(g)
print('band centre Hz: ' + ' '.join(f'{62.5 * 2 ** b:7.0f}' for b in range(8)))
print('reference dB:   ' + ' '.join(f'{v:7.1f}' for v in br))
print('cosic dB:       ' + ' '.join(f'{v:7.1f}' for v in bg))
if a.png:
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(3, 1, figsize=(14, 10))
    t = np.arange(r.shape[1]) / sr + a.t0
    ax[0].plot(t, r[0], lw=0.4, label='strudel')
    ax[0].plot(t, g[0], lw=0.4, label='cosic', alpha=0.7)
    ax[0].legend()
    ax[1].specgram(r[0], NFFT=2048, Fs=sr, noverlap=1024, cmap='magma', vmin=-120)
    ax[1].set_title('strudel')
    ax[2].specgram(g[0], NFFT=2048, Fs=sr, noverlap=1024, cmap='magma', vmin=-120)
    ax[2].set_title('cosic')
    fig.tight_layout()
    fig.savefig(a.png, dpi=70)
    print('wrote', a.png)
