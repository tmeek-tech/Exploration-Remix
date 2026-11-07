#!/usr/bin/env python3
"""Makes the Basalt Galleries' quake sounds from scratch (0.9.0), with synth_sounds.py's building blocks: a big quake's
rumble (quake_rumble1-2), a tremor's crackle (quake_tremor1-3), the crack overhead of a ceiling block about to drop
(quake_crack1-3), the creak of a build under a player (quake_creak1-3), the thud of rock landing (quake_land1-3), and the
hiss of something falling into the lava (bomb_fizz1-2, shared with the eruptions' lava bombs).

The quakes speak their own language, never the lava's: the lava cycle's warning (synth_lava_sounds.py) is a deep
sub-bass roll with grinding and cracks, and it is the layer's only clock, so the quake files are dry stone, cracking,
grinding and rattling, with nothing under about 80 Hz (each is high-passed, and the check below holds them to under 1 %
of their energy there). Every file is mono, 44.1 kHz, Vorbis q4 (only mono sounds are placed in the world); deep sounds
would be made deep here, never pitched down in sounds.json (the game clamps pitch to 0.5-2). Deterministic, with its
own random seed.

Needs numpy and ffmpeg with libvorbis (and ffprobe for the check). Writes
src/main/resources/assets/explorationremix/sounds/ambient/{quake_*,bomb_fizz*}.ogg, then checks them.
Run from anywhere: python3 tools/synth_quake_sounds.py          (make and check)
                   python3 tools/synth_quake_sounds.py --check  (check the files as they are)
"""
import json, os, subprocess, sys
import numpy as np

from synth_sounds import (OUT, SR, add_clipped, bandpass, convolve, env_ad, fade_edges, filt, highpass, lowpass,
                          normalize_peak, periodic_noise, pink, reverb_ir, save, tone)
from synth_lava_sounds import gloop

SEED = 20261107
# The quake files and their lengths in seconds: each must keep under 1 % of its energy below 80 Hz.
QUAKE_FILES = {"quake_rumble": (2, 10.0), "quake_tremor": (3, 1.5), "quake_crack": (3, 0.5), "quake_creak": (3, 1.0),
               "quake_land": (3, 1.2)}
OTHER_FILES = {"bomb_fizz": (2, 1.2)}
LOW_HZ, LOW_SHARE = 80.0, 0.01


# ----------------------------------------------------------------------------------------------- building blocks

def one_shot(x, shape):
    """Filters a one-shot sound by a magnitude response, padded so the filter's ringing does not wrap round to its start."""
    n = len(x)
    m = 1 << (n + SR // 2 - 1).bit_length()
    return filt(np.pad(x, (0, m - n)), shape)[:n]


def dry_stone(x, fc=100.0):
    """The quakes' rule: a steep high-pass, so no quake sound has the lava warning's deep roll in it."""
    return one_shot(x, highpass(fc, 8))


def burst(rng, seconds, decay_s):
    """A short burst of noise that dies away: the raw stuff of cracks and clicks."""
    m = max(8, int(seconds * SR))
    return rng.standard_normal(m) * np.exp(-np.arange(m) / (decay_s * SR))


def clicks(n, rng, start_s, end_s, rate, centre, amp=1.0, fade_s=None):
    """Pebbles and grit: short clicks of noise round {centre} Hz between two times, about {rate} a second, thinning out
    toward the end (over fade_s seconds), each a little louder or softer."""
    out = np.zeros(n)
    at = start_s
    while at < end_s:
        thin = np.exp(-(at - start_s) / fade_s) if fade_s else 1.0
        if rng.random() < thin:
            c = burst(rng, rng.uniform(0.002, 0.008), rng.uniform(0.0004, 0.0016)) * rng.lognormal(0, 0.5) * amp
            add_clipped(out, c, int(at * SR))
        at += rng.exponential(1.0 / rate)
    return one_shot(out, bandpass(centre, 0.8))


def crack(rng, seconds=0.05, centre=3000.0, decay_s=0.008):
    """A crack of rock: a sharp burst and a rebound or two, round {centre} Hz."""
    m = int(0.12 * SR)
    out = np.zeros(m)
    add_clipped(out, burst(rng, seconds, decay_s), 0)
    for _ in range(rng.integers(1, 3)):
        add_clipped(out, burst(rng, seconds * 0.6, decay_s * 0.7) * rng.uniform(0.3, 0.7), int(rng.uniform(0.008, 0.045) * SR))
    return one_shot(out, bandpass(centre, 0.7))


def swells(n, rng, start_s, end_s, gap=(0.1, 0.4), length=(0.15, 0.6)):
    """An envelope of overlapping swells: the stop and go of rock grinding on rock."""
    out = np.zeros(n)
    at = start_s
    while at < end_s:
        m = int(rng.uniform(*length) * SR)
        w = np.sin(np.pi * np.arange(m) / m) ** 2 * rng.uniform(0.4, 1.0)
        add_clipped(out, w, int(at * SR))
        at += rng.uniform(*gap)
    return out


# ----------------------------------------------------------------------------------------------- the sounds

def quake_rumble(rng, seconds=10.0):
    """A big quake: a sharp crack at the onset, then a long dry grinding and rattling of stone with pebbles, 300 Hz to
    6 kHz, that holds and dies away over the last few seconds."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    hold = rng.uniform(5.8, 6.8)
    env = np.clip(t / 0.15, 0, 1) * np.exp(-np.maximum(0, t - hold) / rng.uniform(0.9, 1.2))
    onset = np.zeros(n)
    add_clipped(onset, crack(rng, 0.06, rng.uniform(2200, 2800), 0.012), int(0.03 * SR))
    add_clipped(onset, crack(rng, 0.04, rng.uniform(1500, 2000), 0.01) * 0.6, int(rng.uniform(0.09, 0.16) * SR))
    grind = periodic_noise(n, rng, lambda f: pink(f) * bandpass(rng.uniform(550, 700), 0.9)(f))
    grit = periodic_noise(n, rng, lambda f: pink(f) * bandpass(rng.uniform(1300, 1700), 1.4)(f))
    mod = 0.3 + swells(n, rng, 0.05, hold + 2.0)
    rattle = clicks(n, rng, 0.05, seconds - 0.3, 45, 3500, fade_s=hold + 1.5)
    pebbles = clicks(n, rng, 0.1, seconds - 0.3, 12, 2200, amp=1.6, fade_s=hold)
    cracks = np.zeros(n)
    for _ in range(rng.integers(3, 6)):
        add_clipped(cracks, crack(rng, 0.03, rng.uniform(1800, 3500), 0.006) * rng.uniform(0.3, 0.6), int(rng.uniform(0.5, hold) * SR))
    x = 1.0 * onset + env * mod * (0.55 * grind + 0.3 * grit) + env * (0.35 * rattle + 0.3 * pebbles) + cracks
    x = x + convolve(x, reverb_ir(2.0, rng, 0.55, lp=5000))[:n] * 0.3
    return fade_edges(normalize_peak(dry_stone(x), -2.0), 0.02)


def quake_tremor(rng, seconds=1.5):
    """A light tremor: a short dry crackle of settling rock, 1 to 5 kHz, and no rumble."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    env = np.clip(t / 0.03, 0, 1) * np.exp(-t / rng.uniform(0.3, 0.4))
    crackle = clicks(n, rng, 0.01, seconds - 0.2, 70, rng.uniform(2200, 2800), fade_s=0.35)
    grit = periodic_noise(n, rng, lambda f: pink(f) * bandpass(2000, 1.0)(f)) * env * 0.12
    pop = np.zeros(n)
    add_clipped(pop, crack(rng, 0.02, rng.uniform(1600, 2400), 0.004) * 0.6, int(0.01 * SR))
    x = crackle + grit + pop
    x = x + convolve(x, reverb_ir(0.8, rng, 0.25, lp=6000))[:n] * 0.2
    x = one_shot(x, lowpass(5000, 3))
    return fade_edges(normalize_peak(dry_stone(x, 300), -3.0), 0.01)


def quake_crack(rng, seconds=0.5):
    """A ceiling block coming loose overhead: a sharp crack of 2 to 5 kHz and a crumble after it."""
    n = int(seconds * SR)
    x = np.zeros(n)
    add_clipped(x, crack(rng, 0.045, rng.uniform(2800, 3600), 0.009), int(0.005 * SR))
    x += clicks(n, rng, 0.05, seconds - 0.06, 55, 3000, amp=0.5, fade_s=0.15)
    x = x + convolve(x, reverb_ir(0.6, rng, 0.2, lp=6000))[:n] * 0.25
    x = one_shot(x, lowpass(5500, 3))
    return fade_edges(normalize_peak(dry_stone(x, 600), -2.0), 0.004)


def quake_creak(rng, seconds=1.0):
    """A build under a player about to go: a grinding creak (rock or wood straining, slipping and catching, its catches
    quickening) and a crack at the end, 200 Hz to 3 kHz."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    pulses = np.zeros(n)
    at, end = 0.04, rng.uniform(0.62, 0.72)
    f0, f1 = rng.uniform(28, 38), rng.uniform(60, 80)
    while at < end:
        u = (at - 0.04) / (end - 0.04)
        swell = np.sin(np.pi * min(1.0, u * 1.1)) ** 0.7
        pulses[int(at * SR)] += swell * rng.uniform(0.6, 1.0)
        at += 1.0 / (f0 + (f1 - f0) * u) * rng.uniform(0.85, 1.15)
    k = np.arange(int(0.03 * SR)) / SR
    fr1, fr2 = rng.uniform(380, 520), rng.uniform(950, 1300)
    kernel = np.sin(2 * np.pi * fr1 * k) * np.exp(-k / 0.006) + 0.6 * np.sin(2 * np.pi * fr2 * k) * np.exp(-k / 0.003)
    creak = convolve(pulses, kernel)[:n]
    env = np.clip(t / 0.05, 0, 1) * np.clip((end + 0.05 - t) / 0.1, 0, 1)
    grind = periodic_noise(n, rng, lambda f: pink(f) * bandpass(750, 1.0)(f)) * env * 0.08
    snap = np.zeros(n)
    add_clipped(snap, crack(rng, 0.05, rng.uniform(1500, 2100), 0.011), int((end + rng.uniform(0.0, 0.06)) * SR))
    x = creak / (np.max(np.abs(creak)) + 1e-12) + grind + 0.9 * snap
    x = x + convolve(x, reverb_ir(0.7, rng, 0.22, lp=4500))[:n] * 0.2
    x = one_shot(x, lowpass(3200, 2))
    return fade_edges(normalize_peak(dry_stone(x, 180), -2.0), 0.005)


def quake_land(rng, seconds=1.2):
    """A block of rock landing: a short thump of 100 to 200 Hz, a crunch, and pebbles skittering away."""
    n = int(seconds * SR)
    m = int(0.25 * SR)
    k = np.arange(m) / SR
    f = rng.uniform(165, 190) - rng.uniform(45, 60) * np.clip(k / 0.08, 0, 1)
    thump = np.zeros(n)
    add_clipped(thump, tone(f, env_ad(m, 0.002, rng.uniform(0.04, 0.05)), ((1, 1.0), (2, 0.35), (3, 0.15))), 0)
    add_clipped(thump, one_shot(burst(rng, 0.06, 0.02), lowpass(500, 2)) * 0.4, 0)
    crunch = np.zeros(n)
    add_clipped(crunch, one_shot(burst(rng, 0.12, 0.035), bandpass(rng.uniform(1200, 1600), 0.6)), int(0.004 * SR))
    for _ in range(rng.integers(2, 4)):
        add_clipped(crunch, crack(rng, 0.03, rng.uniform(1500, 3000), 0.006) * rng.uniform(0.3, 0.6), int(rng.uniform(0.005, 0.06) * SR))
    pebbles = clicks(n, rng, 0.07, seconds - 0.1, 35, 3200, amp=0.6, fade_s=0.3)
    x = 1.0 * thump + 0.7 * crunch + pebbles
    x = x + convolve(x, reverb_ir(1.2, rng, 0.35, lp=4000))[:n] * 0.3
    return fade_edges(normalize_peak(dry_stone(x, 100), -1.5), 0.004)


def bomb_fizz(rng, seconds=1.2):
    """Something hot falling into lava: a hiss and sizzle that dies away, and a gloop or two as it goes under."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    env = np.clip(t / 0.01, 0, 1) * np.exp(-t / rng.uniform(0.35, 0.45))
    hiss = periodic_noise(n, rng, lambda f: bandpass(rng.uniform(4500, 5500), 0.7)(f)) * env * 0.5
    steam = periodic_noise(n, rng, lambda f: pink(f) * bandpass(1500, 0.8)(f)) * env * 0.25
    sizzle = clicks(n, rng, 0.0, seconds - 0.2, 90, 6000, amp=0.5, fade_s=0.4)
    gloops = np.zeros(n)
    at = rng.uniform(0.04, 0.1)
    for _ in range(rng.integers(1, 3)):
        add_clipped(gloops, gloop(rng) * 0.7, int(at * SR))
        at += rng.uniform(0.15, 0.35)
    x = hiss + steam + sizzle + gloops
    return fade_edges(normalize_peak(x, -2.0), 0.005)


# ----------------------------------------------------------------------------------------------- the check

def probe(path):
    """ffprobe's channels, sample rate and length of an OGG."""
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=channels,sample_rate:format=duration", "-of", "json", path],
                         check=True, capture_output=True, text=True).stdout
    j = json.loads(out)
    s = j["streams"][0]
    return int(s["channels"]), int(s["sample_rate"]), float(j["format"]["duration"])


def decode(path):
    """The OGG decoded to mono floats at 44.1 kHz, as the game would hear it."""
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def low_share(x, hz=LOW_HZ):
    """The share of a sound's energy below {hz}."""
    power = np.abs(np.fft.rfft(x)) ** 2
    f = np.fft.rfftfreq(len(x), 1 / SR)
    return float(power[f < hz].sum() / (power.sum() + 1e-30))


def check():
    """Every file there, mono, 44.1 kHz and of its length; the quake files under 1 % of their energy below 80 Hz.
    Returns the problems found."""
    problems = []
    for files, band in ((QUAKE_FILES, True), (OTHER_FILES, False)):
        for name, (count, seconds) in files.items():
            for k in range(1, count + 1):
                path = os.path.join(OUT, f"{name}{k}.ogg")
                if not os.path.exists(path):
                    problems.append(f"{name}{k}.ogg: missing")
                    continue
                channels, rate, length = probe(path)
                if channels != 1: problems.append(f"{name}{k}.ogg: {channels} channels (only mono is placed in the world)")
                if rate != SR: problems.append(f"{name}{k}.ogg: {rate} Hz")
                if abs(length - seconds) > 0.05: problems.append(f"{name}{k}.ogg: {length:.2f} s, not {seconds} s")
                share = low_share(decode(path))
                note = ""
                if band and share >= LOW_SHARE:
                    problems.append(f"{name}{k}.ogg: {share:.2%} of its energy below {LOW_HZ:.0f} Hz (the lava's deep roll)")
                    note = "  TOO LOW"
                print(f"{name}{k}.ogg  {length:.2f} s  {channels} ch  {share:.3%} below {LOW_HZ:.0f} Hz{note}")
    print(f"quake sounds check: {len(problems)} problems")
    return problems


def main():
    rng = np.random.default_rng(SEED)
    for k in range(1, 3):
        save(f"quake_rumble{k}", quake_rumble(rng))
    for k in range(1, 4):
        save(f"quake_tremor{k}", quake_tremor(rng))
    for k in range(1, 4):
        save(f"quake_crack{k}", quake_crack(rng))
    for k in range(1, 4):
        save(f"quake_creak{k}", quake_creak(rng))
    for k in range(1, 4):
        save(f"quake_land{k}", quake_land(rng))
    for k in range(1, 3):
        save(f"bomb_fizz{k}", bomb_fizz(rng))


if __name__ == "__main__":
    if "--check" not in sys.argv[1:]:
        main()
    sys.exit(1 if check() else 0)
