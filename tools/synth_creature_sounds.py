#!/usr/bin/env python3
"""Makes the Mossback's sounds from scratch (no recordings): its call, heavy steps, the hiss when it sees you, the
snap of its jaws, the growl while it shakes its catch, hurt and death. Built from tones and filtered noise with the
helpers in synth_sounds.py, with a short cave echo. Deterministic: the same script gives the same sounds.

Needs numpy and ffmpeg with libvorbis. Writes src/main/resources/assets/explorationremix/sounds/creature/mossback/*.ogg
(tools/sounds.py lists them in sounds.json). Run from anywhere: python3 tools/synth_creature_sounds.py
"""
import os, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth_sounds as S
from synth_sounds import SR

S.OUT = os.path.join(S.ROOT, "src/main/resources/assets/explorationremix/sounds/creature/mossback")

COUNTS = {"call": 3, "step": 4, "hiss": 2, "bite": 2, "shake": 3, "hurt": 3, "death": 1}


def secs(n): return int(n * SR)


def noise(rng, n, shape):
    return S.filt(rng.standard_normal(n), shape)


def cave(x, rng, decay=0.35, wet=0.22):
    ir = S.reverb_ir(1.2, rng, decay, lp=2500)
    y = S.convolve(x, ir)[:len(x) + secs(0.6)]
    dry = np.concatenate([x, np.zeros(len(y) - len(x))])
    return dry + wet * y


def call(rng):
    """A low, throaty run of clicks over a growl: the sound you hear it coming by."""
    dur = rng.uniform(0.9, 1.3)
    n = secs(dur)
    t = np.arange(n) / SR
    rate = rng.uniform(11, 16)                                  # clicks per second, slowing towards the end
    inst = rate * (1 - 0.35 * t / dur)
    ph = np.cumsum(inst) / SR
    clicks = (np.sin(2 * np.pi * ph) > 0.92).astype(float)
    clicks = S.filt(clicks, S.bandpass(rng.uniform(380, 520), 2.2)) * 3.0
    f0 = rng.uniform(62, 80) * (1 + 0.08 * np.sin(2 * np.pi * 1.7 * t))
    growl = S.tone(f0, np.ones(n), ((1, 1.0), (2, 0.6), (3, 0.35), (5, 0.15)))
    growl *= 0.6 + 0.4 * np.abs(np.sin(2 * np.pi * ph))       # the growl pulses with the clicks
    env = np.minimum(1, t / 0.08) * np.minimum(1, (dur - t) / 0.25)
    x = (0.55 * growl + clicks) * env
    x = S.filt(x, S.lowpass(1800, 2))
    return S.normalize_peak(cave(x, rng, 0.45, 0.3), -2.0)


def step(rng):
    """A heavy foot set down: a low thump and a short scrape of claws on rock."""
    n = secs(0.35)
    t = np.arange(n) / SR
    thump = S.tone(rng.uniform(48, 62) * (1 - 0.3 * t / 0.35), np.exp(-t / 0.06), ((1, 1.0), (2, 0.3)))
    scrape = noise(rng, n, S.bandpass(rng.uniform(2500, 3800), 1.5)) * np.exp(-np.maximum(0, t - 0.01) / 0.03) * 0.35
    x = thump + scrape
    return S.normalize_peak(S.fade_edges(x, 0.003), -4.0)


def hiss(rng):
    """The warning when it sees you: a breathy hiss whose formant sweeps up, rattled by its frills."""
    dur = rng.uniform(0.75, 0.95)
    n = secs(dur)
    t = np.arange(n) / SR
    base = rng.standard_normal(n)
    fc = 1500 + 1800 * np.minimum(1, t / (dur * 0.6))
    # a sweeping band: filter in short frames
    out = np.zeros(n)
    frame = 1024
    for i in range(0, n, frame // 2):
        seg = base[i:i + frame]
        if len(seg) < 16: break
        w = np.hanning(len(seg))
        out[i:i + len(seg)] += S.filt(seg * w, S.bandpass(fc[min(i, n - 1)], 1.3))
    rattle = 0.65 + 0.35 * np.sin(2 * np.pi * rng.uniform(28, 36) * t)
    env = np.minimum(1, t / 0.05) * np.minimum(1, (dur - t) / 0.3)
    return S.normalize_peak(cave(out * rattle * env, rng, 0.3, 0.2), -3.0)


def bite(rng):
    """The snap of the jaws: a hard click, a crack of noise and a low knock."""
    n = secs(0.3)
    t = np.arange(n) / SR
    click = np.zeros(n); click[0:40] = np.hanning(80)[40:] * 1.0
    crack = noise(rng, n, S.bandpass(rng.uniform(1400, 1900), 1.2)) * np.exp(-t / 0.018)
    knock = S.tone(rng.uniform(140, 170), np.exp(-t / 0.04), ((1, 1.0), (2, 0.4)))
    return S.normalize_peak(cave(click + 0.8 * crack + 0.6 * knock, rng, 0.25, 0.15), -1.5)


def shake(rng):
    """A rough growl through clenched jaws while it shakes its catch."""
    dur = rng.uniform(0.6, 0.8)
    n = secs(dur)
    t = np.arange(n) / SR
    f0 = rng.uniform(55, 70) * (1 + 0.15 * S.filt(rng.standard_normal(n), S.lowpass(8, 1)) / 3)
    saw = S.tone(f0, np.ones(n), [(k, 1.0 / k) for k in range(1, 9)])
    rough = 0.6 + 0.4 * np.sign(np.sin(2 * np.pi * rng.uniform(18, 24) * t))
    air = noise(rng, n, S.bandpass(900, 1.0)) * 0.25
    env = np.minimum(1, t / 0.04) * np.minimum(1, (dur - t) / 0.2)
    x = S.filt((saw * rough + air) * env, S.lowpass(2200, 2))
    return S.normalize_peak(cave(x, rng, 0.3, 0.2), -2.5)


def hurt(rng):
    """A short, cracked squall that falls in pitch."""
    dur = rng.uniform(0.35, 0.45)
    n = secs(dur)
    t = np.arange(n) / SR
    f0 = rng.uniform(260, 320) * (1 - 0.4 * t / dur)
    x = S.tone(f0, np.ones(n), ((1, 1.0), (2, 0.7), (3, 0.5), (4, 0.25)))
    x *= 0.7 + 0.3 * np.sin(2 * np.pi * rng.uniform(35, 45) * t)
    x += noise(rng, n, S.bandpass(1200, 1.0)) * 0.3
    env = np.minimum(1, t / 0.015) * np.exp(-t / (dur * 0.6))
    return S.normalize_peak(cave(x * env, rng, 0.3, 0.2), -2.0)


def death(rng):
    """A long croak that sinks and rattles out."""
    dur = 1.7
    n = secs(dur)
    t = np.arange(n) / SR
    f0 = 140 * np.exp(-t / 1.1) + 40
    x = S.tone(f0, np.ones(n), ((1, 1.0), (2, 0.6), (3, 0.4), (5, 0.2)))
    rate = 18 * np.exp(-t / 1.4) + 4
    x *= 0.5 + 0.5 * (np.sin(2 * np.pi * np.cumsum(rate) / SR) > 0)
    env = np.minimum(1, t / 0.05) * np.exp(-t / 0.8)
    x = S.filt(x * env, S.lowpass(1600, 2))
    return S.normalize_peak(cave(x, rng, 0.5, 0.3), -2.0)


MAKERS = {"call": call, "step": step, "hiss": hiss, "bite": bite, "shake": shake, "hurt": hurt, "death": death}


def main():
    for k, (name, count) in enumerate(COUNTS.items()):
        for i in range(count):
            rng = np.random.default_rng(7700 + 31 * k + i)
            S.save(f"{name}{i + 1}", MAKERS[name](rng))


if __name__ == "__main__":
    main()
