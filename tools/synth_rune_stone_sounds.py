#!/usr/bin/env python3
"""Makes the rune stones' sounds from scratch (0.23.1, no recordings). Taran, 2026-11-26: "Also give them some magic
effects that make them satisfying to break."

  hum      the loop a stone hums while it is dug. The client plays it louder and higher as the pick works (pitch 0.7 to
           1.35, RuneStoneClient.Hum), so it is a soft, even tone: a fundamental of 220 Hz with a few harmonics (strong
           enough that a small speaker hears it), each doubled half a hertz away so the tone breathes slowly, a faint glassy shimmer over it and a little air.
           Every frequency in it is a whole number of cycles over its two seconds, and its air is noise made to loop,
           so it loops without a seam.
  break    a stone going: a bright crack and grit, the dull knock of the stone's body and a puff of air, then a glassy
           chime ringing out (a glass bell's partials, not a harmonic series) with a shimmer of light falling through it.
  catch    a rune landing in the pack: a clear, soft note (a celesta's, C6) with a sparkle on it. The second rune of a
           stone plays it a fourth higher (FreedRune).
  flare    a stone answering a broken one, from where it lies in the rock: the glass chime again, softer, lower, muffled
           by the rock round it and ringing on in it. The client shifts each answer a few semitones apart, so several
           answering make a chord.

The project's rules for its sounds hold: mono, 44.1 kHz, Vorbis q4, and nothing carrying weight below 40 Hz (each file
is high-passed and checked as synth_choir_sounds.py checks the Choir's). Deterministic, with its own seed.

Needs numpy and ffmpeg with libvorbis (ffprobe for the check). Writes
src/main/resources/assets/explorationremix/sounds/rune_stone/*.ogg, then checks them.
Run from anywhere: python3 tools/synth_rune_stone_sounds.py            (make them, then check them)
                   python3 tools/synth_rune_stone_sounds.py --check    (check the files as they are)
                   python3 tools/synth_rune_stone_sounds.py --previews DIR
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth_sounds as S
import synth_choir_sounds as C
from synth_sounds import SR

OUT = os.path.join(S.ROOT, "src/main/resources/assets/explorationremix/sounds/rune_stone")
SEED = 23100
HUM_SECONDS = 2.0
# every file: how many of it (one keeps the bare name, several are name_1, name_2, ...) and how long it should be
FILES = {"hum": (1, HUM_SECONDS), "break": (2, 1.5), "catch": (2, 1.0), "flare": (3, 1.8)}
# A glass bell's partials (ratio to its first, strength, seconds it rings): bright and cold, far from a harmonic series.
GLASS = ((1.00, 1.00, 0.95), (2.32, 0.55, 0.62), (4.25, 0.34, 0.40), (6.63, 0.20, 0.26), (9.38, 0.11, 0.17), (12.6, 0.06, 0.11))
# A celesta's: nearly harmonic and soft, a struck bar in a wooden box.
CELESTA = ((1.00, 1.00, 0.42), (2.00, 0.30, 0.30), (3.00, 0.10, 0.18), (4.12, 0.06, 0.10), (5.40, 0.03, 0.07))


def secs(x): return int(round(x * SR))


def hum(rng):
    """The dug stone's hum: an even, breathing tone that loops without a seam (see the module's notes)."""
    n = secs(HUM_SECONDS)
    t = np.arange(n) / SR
    step = 1.0 / HUM_SECONDS                     # every frequency a whole number of these: the loop closes
    f0 = 220.0
    out = np.zeros(n)
    for k, a in ((1, 1.0), (2, 0.6), (3, 0.32), (4, 0.16), (5, 0.07), (6, 0.04)):
        f = f0 * k
        ph = rng.uniform(0, 2 * np.pi)
        # the partial and its twin half a hertz up: they beat once over the loop, so the tone swells and eases
        out += a * (0.62 * np.sin(2 * np.pi * f * t + ph) + 0.38 * np.sin(2 * np.pi * (f + step) * t + ph))
    # a glassy shimmer high over it, coming and going on its own slow beats
    for f, rate, a in ((1320.0, 1.5, 0.05), (1760.0, 2.5, 0.035), (2640.0, 1.0, 0.02)):
        am = 0.5 + 0.5 * np.sin(2 * np.pi * rate * t + rng.uniform(0, 2 * np.pi))
        out += a * am * np.sin(2 * np.pi * f * t + rng.uniform(0, 2 * np.pi))
    # a little air through it, made to loop
    air = S.filt(S.periodic_noise(n, rng, S.bandpass(900.0, 1.6)), S.highpass(200, 2))
    out += 0.05 * air * (0.7 + 0.3 * np.sin(2 * np.pi * step * t))
    out = S.filt(out, S.highpass(70.0, 4))       # circular, so the loop stays whole
    return S.normalize_rms(out, -17.0)


def bell_at(rng, n, f0, partials, at, amp, attack=0.002, ring=1.0, beat=0.3):
    """A bell of these partials struck {at} seconds in, {amp} loud, into a buffer of n samples."""
    out = np.zeros(n)
    m = n - secs(at)
    S.add_clipped(out, C.bell(rng, m, f0, partials, attack=attack, beat=beat, ring=ring, detune=0.002) * amp, secs(at))
    return out


def shimmer(rng, n, at, length, top, bottom, amp):
    """Light falling: noise through a band that sweeps down from {top} to {bottom} Hz, swelling in and fading out."""
    out = np.zeros(n)
    m = secs(length)
    x = rng.standard_normal(m)
    fc = np.geomspace(top, bottom, m)
    y = C.moving_band(x, fc, q=2.5) * np.sin(np.pi * np.arange(m) / m) ** 1.5
    S.add_clipped(out, y / (np.max(np.abs(y)) + 1e-12) * amp, secs(at))
    return out


def break_(rng, variant):
    """A stone going: crack, grit, knock and air, then the glass chime with light falling through it."""
    n = secs(1.5)
    out = np.zeros(n)
    S.add_clipped(out, C.crack(rng, centre=2300.0 + 250 * variant, seconds=0.03, decay=0.005, rebounds=2) * 0.9, 0)
    S.add_clipped(out, C.crack(rng, centre=1400.0, seconds=0.02, decay=0.004, rebounds=1) * 0.5, secs(0.012))
    S.add_clipped(out, C.stone_knock(rng, secs(0.25), 150, 220, 0.03) * 0.55, 0)
    out += C.pebbles(n, rng, 0.01, 0.45, 110, 3200.0, amp=0.35, fade_s=0.14)
    m = secs(0.2)
    puff = S.tone(np.linspace(130.0, 80.0, m), S.env_ad(m, 0.004, 0.05), ((1, 1.0), (2, 0.25)))
    S.add_clipped(out, puff * 0.35, 0)
    f0 = (740.0, 698.5)[variant % 2]
    out += bell_at(rng, n, f0, GLASS, 0.006, 0.55)
    out += shimmer(rng, n, 0.02, 0.75, 6500.0, 1600.0, 0.16)
    out = C.arena(out, rng, n, decay=0.35, wet=0.22, lp=5000)
    return S.normalize_peak(C.fade(C.hp(out, 45.0, 6), 0.002, 0.12), -1.5)


def catch(rng, variant):
    """A rune landing in the pack: a soft, clear celesta note with a sparkle on it."""
    n = secs(1.0)
    out = np.zeros(n)
    m = secs(0.07)
    swell = rng.standard_normal(m) * np.linspace(0, 1, m) ** 2
    S.add_clipped(out, C.one_shot(swell, S.bandpass(4200.0, 1.5)) * 0.12, 0)
    out += bell_at(rng, n, 1046.5, CELESTA, 0.06, 0.8, attack=0.003, ring=1.0 + 0.1 * variant, beat=0.2)
    for at, f in ((0.075, 3950.0), (0.11 + 0.02 * variant, 4700.0), (0.16, 5600.0)):
        S.add_clipped(out, bell_at(rng, secs(0.25), f, ((1.0, 1.0, 0.05), (2.7, 0.3, 0.03)), 0.0, 0.07 + 0.02 * rng.random()), secs(at))
    out = C.arena(out, rng, n, decay=0.25, wet=0.18, lp=6000)
    return S.normalize_peak(C.fade(C.hp(out, 60.0, 6), 0.002, 0.1), -2.0)


def flare(rng, variant):
    """A stone answering from inside the rock: the glass chime, softer and lower, muffled and ringing on."""
    n = secs(1.8)
    out = bell_at(rng, n, (587.3, 659.3, 784.0)[variant], GLASS, 0.0, 1.0, attack=0.008, ring=1.25, beat=0.4)
    out = C.one_shot(out, S.lowpass(2600.0, 2))
    out = C.arena(out, rng, n, decay=0.8, wet=0.55, lp=2400, dry=0.75)
    return S.normalize_peak(C.fade(C.hp(out, 60.0, 6), 0.004, 0.25), -3.0)


MAKERS = {"hum": lambda rng, v: hum(rng), "break": break_, "catch": catch, "flare": flare}


def names():
    for sound, (count, seconds) in FILES.items():
        for i in range(1, count + 1):
            yield sound, (sound if count == 1 else f"{sound}_{i}"), i, seconds


def check():
    """As the Choir's check: every file there, mono, 44.1 kHz, about its length, not clipping, nothing low. And the
    hum must loop: its last few milliseconds run into its first without a jump."""
    C.OUT = OUT
    C.FILES = {s: v for s, v in FILES.items()}
    problems = C.check()
    x = C.decode(os.path.join(OUT, "hum.ogg"))
    # the step across the seam, against the largest step inside it
    seam = abs(x[0] - x[-1])
    inner = np.max(np.abs(np.diff(x)))
    if seam > 2.0 * inner:
        problems.append(f"hum.ogg: a jump of {seam:.3f} at its seam (inside it at most {inner:.3f})")
    print(f"hum loop seam {seam:.4f}, largest step inside {inner:.4f}")
    return problems


def main():
    S.OUT = OUT
    for k, (sound, name, i, _) in enumerate(names()):
        S.save(name, MAKERS[sound](np.random.default_rng(SEED + 31 * k + i), i - 1))


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--check" not in args:
        main()
    problems = check()
    if "--previews" in args:
        C.OUT = OUT
        C.previews(args[args.index("--previews") + 1], ("hum", "break_1", "catch_1", "flare_1"))
    for p in problems:
        print("PROBLEM:", p)
    sys.exit(1 if problems else 0)
