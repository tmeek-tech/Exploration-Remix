#!/usr/bin/env python3
"""Makes the timed swings' sounds from scratch (0.12.0; no recordings): a light swing's quick whoosh, the chain's chop
(lower and heavier), a strong attack's thrust (sharp, with a thin ring of steel), the rising hum as a held swing turns
strong, and the hunters' wind-ups: half a second that swells into the strike (the Mossback's throaty intake and growl,
the Skein's clicks running faster and faster), so the strike can be timed by ear. Built from tones and filtered noise
with the helpers in synth_sounds.py. Deterministic. (A rune sword's ready light plays silently: Taran, 2026-11-11.)

Needs numpy and ffmpeg with libvorbis. Writes src/main/resources/assets/explorationremix/sounds/swing/*.ogg and
sounds/creature/<who>/windup*.ogg (tools/sounds.py lists them in sounds.json).
Run from anywhere: python3 tools/synth_swing_sounds.py
"""
import os, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth_sounds as S
from synth_sounds import SR

SOUNDS = os.path.join(S.ROOT, "src/main/resources/assets/explorationremix/sounds")


def secs(n): return int(n * SR)


def sweep_band(x, fc, q):
    """x through a band-pass whose middle follows fc (per sample), in short overlapping frames."""
    n = len(x)
    out = np.zeros(n)
    frame = 512
    win = np.hanning(frame)
    for i in range(0, n, frame // 4):
        seg = x[i:i + frame]
        if len(seg) < 32:
            break
        w = win[:len(seg)]
        out[i:i + len(seg)] += S.filt(seg * w, S.bandpass(max(60.0, fc[min(i + len(seg) // 2, n - 1)]), q)) * 0.5
    return out


def curve(n, points):
    """A smooth curve through (time 0..1, value) points."""
    t = np.linspace(0, 1, n)
    xs, ys = zip(*points)
    return np.interp(t, xs, ys)


def whoosh(rng, dur, f_lo, f_peak, f_end, peak_at, body, q=1.3):
    n = secs(dur)
    t = np.linspace(0, 1, n)
    fc = curve(n, [(0, f_lo), (peak_at, f_peak), (1, f_end)]) * rng.uniform(0.92, 1.08)
    # pink noise, and nothing much above the sweep's top: air, not hiss
    air = sweep_band(S.filt(rng.standard_normal(n), S.pink), fc, q)
    air = S.filt(air, lambda f: S.lowpass(f_peak * 1.3, 2)(f) * S.highpass(f_lo * 0.7, 2)(f))
    low = S.filt(rng.standard_normal(n), S.bandpass(rng.uniform(180, 260), 0.9))
    env = np.where(t < peak_at, (t / peak_at) ** 2.2, np.exp(-(t - peak_at) / (1 - peak_at) * 3.2))
    # the air at full, the low rush of the blade's body under it as much as {body} says
    x = (air / (np.std(air) + 1e-9) + low / (np.std(low) + 1e-9) * body * 0.5) * env
    return S.fade_edges(x, 0.004)


def light(rng):
    """A quick, airy cut."""
    return S.normalize_peak(whoosh(rng, rng.uniform(0.2, 0.24), 550, 2300, 900, 0.42, 0.25), -4.0)


def heavy(rng):
    """The chain's chop: lower, longer, with more body behind it."""
    x = whoosh(rng, rng.uniform(0.3, 0.34), 320, 1350, 480, 0.5, 0.7, q=1.1)
    return S.normalize_peak(x, -2.5)


def thrust(rng):
    """The strong attack: a sharp rush forward and a thin ring of steel."""
    dur = 0.32
    n = secs(dur)
    t = np.arange(n) / SR
    x = S.normalize_peak(whoosh(rng, dur, 800, 3100, 1200, 0.28, 0.5, q=1.5), 0.0)
    ring = np.zeros(n)
    base = rng.uniform(2450, 2750)
    for k, a in ((1.0, 1.0), (1.52, 0.6), (2.37, 0.35), (3.1, 0.2)):
        ring += a * np.sin(2 * np.pi * base * k * t + rng.uniform(0, 6.28))
    on = t >= dur * 0.24
    ring *= on * np.exp(-np.maximum(0, t - dur * 0.24) / 0.09) * 0.07
    return S.normalize_peak(x + ring, -2.0)


def charge(rng):
    """A held swing turning strong: a tightening hum that rises to the moment it fires."""
    dur = 0.36
    n = secs(dur)
    t = np.arange(n) / SR
    k = t / dur
    fc = 380 + 1500 * k ** 1.4
    air = sweep_band(rng.standard_normal(n), fc, 2.2)
    f0 = rng.uniform(200, 230) * (1 + 0.9 * k ** 1.5)
    hum = S.tone(f0, np.ones(n), ((1, 1.0), (2, 0.4), (3, 0.15)))
    trem = 0.75 + 0.25 * np.sin(2 * np.pi * (14 + 22 * k) * t)
    env = (0.15 + 0.85 * k ** 1.6) * np.minimum(1, (dur - t) / 0.03)
    x = (air * 0.9 + hum * 0.22) * trem * env
    return S.normalize_peak(S.fade_edges(x, 0.004), -6.0)


def mossback_windup(rng):
    """Half a second: a throaty intake rising into a growl that swells to the snap (the bite follows it)."""
    dur = 0.5
    n = secs(dur)
    t = np.arange(n) / SR
    k = t / dur
    intake = sweep_band(rng.standard_normal(n), 300 + 900 * k, 1.4) * (0.4 + 0.6 * k)
    f0 = rng.uniform(64, 74) * (1 + 0.35 * k)
    growl = S.tone(f0, np.ones(n), ((1, 1.0), (2, 0.65), (3, 0.4), (4, 0.25), (6, 0.12)))
    growl *= 0.55 + 0.45 * np.abs(np.sin(2 * np.pi * (9 + 14 * k) * t))
    env = (0.25 + 0.75 * k ** 1.3) * np.minimum(1, t / 0.03) * np.minimum(1, (dur - t) / 0.02)
    x = S.filt((0.8 * intake + 0.7 * growl) * env, S.lowpass(2600, 2))
    return S.normalize_peak(x, -2.0)


def skein_windup(rng):
    """Half a second: dry clicks of plate on plate running faster and faster, a hiss rising under them, to the strike."""
    dur = 0.5
    n = secs(dur)
    t = np.arange(n) / SR
    k = t / dur
    rate = 10 + 70 * k ** 1.7
    ph = np.cumsum(rate) / SR
    clicks = np.zeros(n)
    edges = np.nonzero(np.diff(np.floor(ph)) > 0)[0]
    for e in edges:
        L = secs(0.004)
        burst = rng.standard_normal(L) * np.hanning(L)
        end = min(n, e + L)
        clicks[e:end] += burst[:end - e] * rng.uniform(0.6, 1.0)
    clicks = S.filt(clicks, S.bandpass(rng.uniform(2600, 3300), 1.6)) * 2.2
    hiss = S.filt(rng.standard_normal(n), S.bandpass(3600, 1.2)) * (0.1 + 0.5 * k ** 2)
    env = (0.35 + 0.65 * k) * np.minimum(1, (dur - t) / 0.015)
    x = S.filt((clicks + hiss) * env, S.lowpass(6000, 2))
    return S.normalize_peak(x, -2.5)


# (folder, name, maker, how many, seed base)
SETS = [
    ("swing", "light", light, 3, 4100),
    ("swing", "heavy", heavy, 2, 4200),
    ("swing", "thrust", thrust, 2, 4300),
    ("swing", "charge", charge, 2, 4400),
    ("creature/mossback", "windup", mossback_windup, 2, 4600),
    ("creature/skein", "windup", skein_windup, 2, 4700),
]


def main():
    for folder, name, maker, count, base in SETS:
        S.OUT = os.path.join(SOUNDS, folder)
        for i in range(count):
            rng = np.random.default_rng(base + i)
            S.save(f"{name}{i + 1}", maker(rng))


if __name__ == "__main__":
    main()
