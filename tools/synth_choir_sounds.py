#!/usr/bin/env python3
"""Makes the Hollow Choir's sounds from scratch (no recordings): the Deep Dark's boss, a ring of pale bone plates with
bells hanging under it and a knot of sculk at its heart, over a sunken stone arena on six long tendrils.

Its voice is a struck bell and a choir of cold, hollow voices, and nothing else. The bell is inharmonic (the tierce, a
minor third among its partials, is what makes a bell cold), and every partial is doubled a fraction of a hertz away
from itself, so it beats and warbles as a real bell does and the ring never sits still. The voices are a glottal tone
through a throat shaped for a dark vowel, each a few cents off the others and none of them steady, so a chord beats
slowly and never settles; the breath that carries them is half of what you hear. Everything physical is stone and
bone: dry cracks, dull knocks, grinding, grit. Nothing here is the Warden's, and nothing is vanilla's bell block: no
heartbeat, no sonic boom, no bright struck metal, no brass, no scream, no animal roar.

Where the sounds sit: the bell's hum tone is 84 Hz, so its prime, tierce, quint and nominal stack up through the 200
to 600 Hz band, which is where a small speaker hears it and where the toll has to carry across the arena. Nothing
below 40 Hz carries any weight, and every file is high-passed at 33 Hz (the check below holds each to under 0.5 % of
its energy below 25 Hz): the project has been bitten before by sounds that were almost all under 20 Hz and so were
never heard at all (see the old basalt rumble in synth_layer_sounds.py). Mono, 44.1 kHz, Vorbis q4, as the other
creatures' sounds are (tools/sounds.py lists them in sounds.json, with toll_far carrying 160 blocks). Deterministic,
with its own seed: the same script gives the same files every run.

Needs numpy and ffmpeg with libvorbis (ffprobe for the check, matplotlib for --previews). Writes
src/main/resources/assets/explorationremix/sounds/creature/hollow_choir/*.ogg, then checks them.
Run from anywhere: python3 tools/synth_choir_sounds.py                 (make them, then check them)
                   python3 tools/synth_choir_sounds.py --check         (check the files as they are)
                   python3 tools/synth_choir_sounds.py --previews DIR  (also WAVs and plots of a few)
"""
import json, os, subprocess, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth_sounds as S
from synth_sounds import SR

OUT = os.path.join(S.ROOT, "src/main/resources/assets/explorationremix/sounds/creature/hollow_choir")
SEED = 11250
F0 = 84.0                        # the bell's hum tone in Hz: its prime, tierce, quint and nominal fill 160 to 600 Hz
LOW_HZ, LOW_SHARE = 25.0, 0.005  # nothing below 25 Hz: it is never heard in game, it only eats the level
HP_HZ, HP_ORDER = 33.0, 8        # the high-pass that holds that line (-19 dB at 25 Hz, nothing at all under 20)

# every file: how many of it (one keeps the bare name, several are name_1, name_2, ...) and how long it should be
FILES = {"wake": (1, 4.0), "toll": (1, 2.5), "toll_far": (1, 3.5), "windup": (1, 0.9), "slam": (1, 1.2),
         "tendril_cut": (1, 1.1), "fall": (1, 2.0), "rise": (1, 2.0), "core": (3, 0.6), "bloom": (1, 2.2),
         "sprout": (1, 0.5), "hurt": (2, 0.7), "idle": (1, 5.0), "death": (1, 5.0), "reliquary": (1, 1.6),
         "key_set": (1, 1.2), "grab": (1, 1.6), "root": (1, 0.6)}
TOLERANCE = 0.15                 # how far a file may be off its length, in seconds
PREVIEWS = ("toll", "toll_far", "wake", "death", "rise", "slam")


# ----------------------------------------------------------------------------------------------- building blocks

def secs(x): return int(x * SR)


def one_shot(x, shape):
    """Filters a one-shot by a magnitude response, padded so the filter's ringing cannot wrap round to its start."""
    n = len(x)
    m = 1 << (n + SR // 2 - 1).bit_length()
    return S.filt(np.pad(x, (0, m - n)), shape)[:n]


def hp(x, fc=HP_HZ, order=HP_ORDER):
    """The rule for every file here: a steep high-pass, so nothing is left under 25 Hz."""
    return one_shot(x, S.highpass(fc, order))


def fade(x, a=0.004, b=None):
    """A click-free start and end (sine-squared), as a one-shot needs."""
    b = a if b is None else b
    n = len(x)
    w = np.ones(n)
    ka, kb = max(1, int(a * SR)), max(1, int(b * SR))
    w[:ka] = np.sin(np.linspace(0, np.pi / 2, ka)) ** 2
    w[-kb:] = np.cos(np.linspace(0, np.pi / 2, kb)) ** 2
    return x * w


def unit(x): return x / (np.std(x) + 1e-12)


def burst(rng, seconds, decay_s):
    """A short burst of noise that dies away: the raw stuff of cracks, knocks and grit."""
    m = max(8, secs(seconds))
    return rng.standard_normal(m) * np.exp(-np.arange(m) / (decay_s * SR))


def crack(rng, centre=1600.0, seconds=0.025, decay=0.004, rebounds=2):
    """Stone or bone splitting: a sharp burst round {centre} Hz and a rebound or two."""
    out = np.zeros(secs(0.16))
    S.add_clipped(out, burst(rng, seconds, decay), 0)
    for _ in range(int(rng.integers(0, rebounds + 1))):
        S.add_clipped(out, burst(rng, seconds * 0.6, decay * 0.7) * rng.uniform(0.3, 0.7),
                      secs(rng.uniform(0.006, 0.05)))
    return one_shot(out, S.bandpass(centre, 0.7))


def pebbles(n, rng, start, end, rate, centre, amp=1.0, fade_s=None, q=0.8):
    """Grit and shards thrown off the stone: short clicks round {centre} Hz, about {rate} a second, thinning out."""
    out = np.zeros(n)
    at = start
    while at < end:
        if fade_s is None or rng.random() < np.exp(-(at - start) / fade_s):
            c = burst(rng, rng.uniform(0.002, 0.008), rng.uniform(0.0004, 0.0016)) * rng.lognormal(0, 0.5) * amp
            S.add_clipped(out, c, secs(at))
        at += rng.exponential(1.0 / rate)
    return one_shot(out, S.bandpass(centre, q))


def swells(n, rng, start, end, gap=(0.08, 0.3), length=(0.12, 0.5)):
    """An envelope of overlapping swells: the stop and go of rock grinding on rock."""
    out = np.zeros(n)
    at = start
    while at < end:
        m = secs(rng.uniform(*length))
        S.add_clipped(out, np.sin(np.pi * np.arange(m) / m) ** 2 * rng.uniform(0.4, 1.0), secs(at))
        at += rng.uniform(*gap)
    return out


def moving_band(x, fc, q=1.0, frame=2048):
    """Noise or a train of pulses through a band whose centre moves: filtered in short overlapping frames (a Hann
    window at half-overlap adds back up to one), which is how the creatures' sweeping hisses are made."""
    n = len(x)
    fc = np.broadcast_to(np.asarray(fc, dtype=float), (n,))
    out = np.zeros(n)
    hop = frame // 2
    for i in range(0, n, hop):
        seg = x[i:i + frame]
        if len(seg) < 32: break
        mid = float(fc[min(i + len(seg) // 2, n - 1)])
        out[i:i + len(seg)] += S.filt(seg * np.hanning(len(seg)), S.bandpass(mid, q))
    return out


def arena(x, rng, n, decay=0.8, wet=0.4, lp=2200, dry=1.0):
    """The sunken stone arena: a dark echo off walls a long way off. The answer is exactly n samples, so every file
    keeps its length; the dry sound must have died inside it."""
    ir = S.reverb_ir(min(3.0, max(1.2, 4 * decay)), rng, decay, lp=lp)
    y = S.convolve(x, ir)[:n]
    return (dry * np.concatenate([x, np.zeros(max(0, n - len(x)))])[:n]
            + wet * np.concatenate([y, np.zeros(max(0, n - len(y)))]))


# ----------------------------------------------------------------------------------------------- the bell

# A bell's partials as multiples of its hum tone, with their strength and how long each rings. Not a harmonic series:
# the tierce (2.4, a minor third over the prime) is what makes a bell sound cold, and the quint and nominal stand a
# fifth and an octave over it. The hum rings on for seconds; the clang above it is gone in a moment.
BELL = ((1.00, 1.00, 3.6), (2.00, 0.85, 2.8), (2.40, 0.60, 2.3), (3.00, 0.45, 1.9), (4.00, 0.75, 1.5),
        (5.04, 0.32, 1.0), (5.95, 0.24, 0.80), (6.75, 0.20, 0.60), (8.10, 0.16, 0.45), (9.42, 0.12, 0.34),
        (11.3, 0.09, 0.26), (13.1, 0.07, 0.20), (16.4, 0.05, 0.15), (20.2, 0.035, 0.11), (25.6, 0.025, 0.08))
CLANG = BELL[4:]     # the clang alone, with no hum under it
UPPER = BELL[5:]     # what is left of a bell once it has been ringing a while


def bell(rng, n, f0, partials=BELL, attack=0.003, beat=0.45, ring=1.0, detune=0.0035, bend=None):
    """A bell struck once: every partial its own sine, each doubled a fraction of a hertz away from itself so the two
    beat slowly against each other (a real bell's two near-equal modes, and its warble), and each a shade off where a
    harmonic series would put it. The pair is struck in step and the quieter one only dips it, so a partial never
    cancels itself, however deep it is or however short the sound. beat: the warble in Hz, faster the higher the
    partial. bend: a curve every partial's pitch follows, for a bell sinking away."""
    t = np.arange(n) / SR
    out = np.zeros(n)
    for ratio, amp, dec in partials:
        f = f0 * ratio * (1 + detune * rng.standard_normal())
        if f > 0.45 * SR: continue
        env = np.minimum(1, t / attack) * np.exp(-t / (dec * ring))
        phase = rng.uniform(0, 2 * np.pi)
        warble = beat * rng.uniform(0.6, 1.5) * (1 + ratio / 10)
        for g, d in ((0.62, 0.0), (0.38, warble)):
            curve = np.broadcast_to(np.asarray((f + d) * (1.0 if bend is None else bend), dtype=float), (n,))
            out += g * amp * env * np.sin(2 * np.pi * np.cumsum(curve) / SR + phase)
    return out / (np.max(np.abs(out)) + 1e-12)


def bone(rng, n, f_lo=420, f_hi=1600, decay=0.035, modes=4):
    """A bone plate rung: a few dry, close-set modes that die in a few hundredths of a second. Wood, not metal."""
    t = np.arange(n) / SR
    out = np.zeros(n)
    f = rng.uniform(f_lo, f_hi)
    for i, r in enumerate((1.0, 1.73, 2.61, 3.42, 4.57)[:modes]):
        fk = f * r * (1 + 0.012 * rng.standard_normal())
        out += 0.8 ** i * np.sin(2 * np.pi * fk * t + rng.uniform(0, 2 * np.pi)) * np.exp(-t / (decay / (1 + 0.6 * i)))
    return out / (np.max(np.abs(out)) + 1e-12)


def clapper(rng, n, f_lo=620, f_hi=1000):
    """What strikes it: bone on bone, a dull hard knock with no metal in it at all."""
    out = np.zeros(n)
    S.add_clipped(out, one_shot(burst(rng, 0.03, 0.0035), S.bandpass(rng.uniform(f_lo, f_hi), 0.7)), 0)
    S.add_clipped(out, bone(rng, min(n, secs(0.12)), 300, 560, 0.03) * 0.5, 0)
    return out / (np.max(np.abs(out)) + 1e-12)


def stone_knock(rng, n, f_lo=170, f_hi=260, decay=0.018):
    """Stone set against stone: a dull knock with the edge filed off it, nothing like a tick on glass."""
    out = np.zeros(n)
    S.add_clipped(out, one_shot(burst(rng, 0.014, 0.0016), S.bandpass(rng.uniform(460, 820), 0.7)), 0)
    m = min(n, secs(0.12))
    S.add_clipped(out, S.tone(np.full(m, rng.uniform(f_lo, f_hi)), S.env_ad(m, 0.002, decay),
                              ((1, 1.0), (2.4, 0.3))) * 0.6, 0)
    return one_shot(out, S.lowpass(2000, 2)) / (np.max(np.abs(out)) + 1e-12)


# ----------------------------------------------------------------------------------------------- the voices

# A throat's resonances for four dark vowels, and how much each one lets through: nothing bright, nothing open. Cold
# and hollow is a low first formant with a narrow second one over it and almost nothing above 3 kHz.
VOWELS = {"oo": ((300, 760, 2300, 3200), (1.00, 0.30, 0.08, 0.03)),
          "oh": ((440, 860, 2500, 3300), (1.00, 0.45, 0.10, 0.03)),
          "aw": ((620, 1000, 2500, 3300), (1.00, 0.55, 0.14, 0.04)),
          "uh": ((520, 1200, 2450, 3300), (1.00, 0.40, 0.18, 0.04))}


def vowel_gain(f, vowel, bw=110.0):
    """How much of a partial at f Hz comes out of a throat shaped for this vowel."""
    fs, amps = VOWELS[vowel]
    f = np.maximum(np.asarray(f, dtype=float), 1e-3)
    g = 0.03 * np.ones_like(f)
    for fc, a in zip(fs, amps):
        g = g + a * S.bandpass(fc, fc / bw)(f)
    return g


def voice(rng, n, f0, vowel, vib=0.004, drift=0.006, breath=0.35, hmax=40, fmax=4200):
    """One cold voice: a glottal tone through a throat shaped for a vowel, its pitch never quite steady (a slow drift
    and a little vibrato), with the breath that carries it. f0 may be a curve, for a voice that slides."""
    t = np.arange(n) / SR
    base = float(np.mean(f0))
    wobble = unit(S.filt(rng.standard_normal(n), S.lowpass(1.3, 2)))
    f = np.asarray(f0, dtype=float) * (1 + drift * wobble
                                       + vib * np.sin(2 * np.pi * rng.uniform(4.0, 5.6) * t + rng.uniform(0, 6.28)))
    harm = [(k, float(vowel_gain(base * k, vowel)) / k ** 0.8) for k in range(1, hmax + 1) if base * k <= fmax]
    x = unit(S.tone(np.broadcast_to(f, (n,)), np.ones(n), harm))
    if breath:
        air = one_shot(rng.standard_normal(n),
                       lambda ff: vowel_gain(ff, vowel) * S.highpass(280, 2)(ff) * S.lowpass(3600, 2)(ff))
        x = x + breath * unit(air)
    return unit(x)


def choir(rng, n, f0, ratios, vowels=("oo", "oh", "uh", "aw"), spread=0.004, breath=0.35, each=2, tilt=0.25):
    """A knot of voices on a chord of the bell's own partials: two or three to a note, each a few cents off the others,
    so the chord beats slowly against itself and never settles. tilt: how much quieter each note up the chord is."""
    out = np.zeros(n)
    for i, r in enumerate(ratios):
        for v in range(each):
            f = np.asarray(f0, dtype=float) * r * (1 + spread * rng.standard_normal())
            out += voice(rng, n, f, vowels[(i + v) % len(vowels)], breath=breath) * rng.uniform(0.7, 1.0) / (1 + tilt * i)
    return unit(out)


CHORD = (1.0, 2.0, 2.4, 3.0, 4.0)   # the choir sings the bell's partials: a minor triad spread over two octaves


# ----------------------------------------------------------------------------------------------- the sounds

def wake(rng):
    """The summoning: three keys turn in their sockets, and the bell swells up out of the floor behind them, long and
    slow, loud enough for the whole cavern to hear it."""
    n = secs(4.0)
    t = np.arange(n) / SR
    keys = np.zeros(n)
    for at in (0.06, 0.52, 1.0):
        S.add_clipped(keys, stone_knock(rng, secs(0.3)) * rng.uniform(0.7, 1.0), secs(at))
        m = secs(0.22)
        turn = unit(moving_band(rng.standard_normal(m), 640 + 260 * np.linspace(0, 1, m), 1.2, frame=1024))
        S.add_clipped(keys, turn * np.hanning(m) * 0.35, secs(at + 0.02))
    rung = bell(rng, n, F0, attack=1.9, ring=2.4, beat=0.4)
    voices = choir(rng, n, F0, CHORD, spread=0.004, breath=0.5)
    swell = np.clip(t / 3.25, 0, 1) ** 1.6 * np.clip((4.0 - t) / 0.72, 0, 1)
    x = 0.6 * keys + (1.0 * rung + 0.7 * voices) * swell
    x = arena(x, rng, n, decay=1.0, wet=0.5, lp=2200)
    return S.normalize_peak(fade(hp(x), 0.002, 0.25), -2.0)


def toll(rng):
    """The attack toll: the bell struck once and hard, the strike right out in front of it, then the ring going out
    over the floor. The whole fight is read by this, so it is the loudest thing here and it sits where a small speaker
    hears it: the tierce, the quint and the nominal, 200 to 600 Hz."""
    n = secs(2.5)
    t = np.arange(n) / SR
    struck = bell(rng, n, F0, ring=0.62, beat=0.5)
    travel = choir(rng, n, F0, (2.0, 2.4, 3.0, 4.0), spread=0.005, breath=0.4)
    out = (1 - np.exp(-t / 0.3)) * np.exp(-t / 0.85)      # the ring leaving: it swells, passes and is gone
    x = 1.0 * struck + 0.8 * clapper(rng, n) + 0.45 * travel * out
    x = arena(x, rng, n, decay=0.6, wet=0.38, lp=2600)
    return S.normalize_peak(fade(hp(x), 0.0015, 0.2), -1.0)


def toll_far(rng):
    """The same bell from the far side of the cave, and the trophy bell idling: the strike is worn off it by the
    distance and what is left is tail. Dark and quiet, but the same partials, so it is plainly the same bell."""
    n = secs(3.5)
    t = np.arange(n) / SR
    struck = bell(rng, n, F0, attack=0.22, ring=1.1, beat=0.35)
    far = choir(rng, n, F0, (1.0, 2.0, 2.4, 3.0), spread=0.005, breath=0.3)
    swell = (1 - np.exp(-t / 0.55)) * np.exp(-t / 1.5)
    x = one_shot(1.0 * struck + 0.5 * far * swell, S.lowpass(1100, 2))
    x = arena(x, rng, n, decay=1.1, wet=0.75, lp=1300, dry=0.3)
    return S.normalize_peak(fade(hp(x), 0.06, 0.5), -9.0)


def windup(rng):
    """A tendril lifting: stone-hard chitin dragged over itself, slipping and catching, its catches quickening and its
    ring rising. Dry, with no tail at all: it stops dead, and the slam follows."""
    n = secs(0.9)
    t = np.arange(n) / SR
    end = 0.84
    pulses = np.zeros(n)
    at = 0.0
    while at < end:
        u = at / end
        pulses[secs(at)] += np.sin(np.pi * min(1.0, u * 1.12)) ** 0.5 * rng.uniform(0.6, 1.0)
        at += 1.0 / (26 + 78 * u ** 1.3) * rng.uniform(0.8, 1.2)
    u = np.clip(t / end, 0, 1)
    low = unit(moving_band(pulses, 300 + 620 * u, 3.0, frame=1024))
    high = unit(moving_band(pulses, 1000 + 1200 * u, 2.0, frame=1024))
    grind = unit(moving_band(rng.standard_normal(n), 520 + 700 * u, 1.1)) * 0.1 * (0.3 + 0.7 * u)
    x = (low + 0.18 * high + grind) * np.clip((end + 0.02 - t) / 0.01, 0, 1)
    x = arena(x, rng, n, decay=0.12, wet=0.08, lp=3000) * np.clip((end + 0.03 - t) / 0.012, 0, 1)
    return S.normalize_peak(fade(hp(x), 0.004, 0.004), -5.0)


def slam(rng):
    """A tendril landing on the arena floor: a deep hard crack, the stone tiles under it smashing and their shards
    going everywhere, and the weight of it through the floor."""
    n = secs(1.2)
    hit = np.zeros(n)
    S.add_clipped(hit, one_shot(burst(rng, 0.022, 0.003), S.bandpass(rng.uniform(900, 1500), 0.8)), 0)
    S.add_clipped(hit, bone(rng, secs(0.22), 380, 820, 0.05) * 0.9, secs(0.001))
    smash = np.zeros(n)
    S.add_clipped(smash, one_shot(burst(rng, 0.1, 0.03), S.bandpass(rng.uniform(380, 620), 0.6)), secs(0.004))
    for _ in range(int(rng.integers(3, 6))):
        S.add_clipped(smash, crack(rng, rng.uniform(900, 2000), 0.03, 0.006) * rng.uniform(0.3, 0.7),
                      secs(rng.uniform(0.005, 0.09)))
    m = secs(0.4)
    k = np.arange(m) / SR
    f = rng.uniform(64, 80) - rng.uniform(10, 16) * np.clip(k / 0.1, 0, 1)
    thud = np.zeros(n)
    S.add_clipped(thud, S.tone(f, S.env_ad(m, 0.003, rng.uniform(0.07, 0.1)), ((1, 1.0), (2, 0.3), (3.1, 0.12))), secs(0.002))
    S.add_clipped(thud, one_shot(burst(rng, 0.09, 0.025), S.lowpass(280, 2)) * 0.5, 0)
    shards = pebbles(n, rng, 0.03, 0.75, 45, 2400, amp=0.45, fade_s=0.25)
    shiver = bell(rng, n, F0 * rng.uniform(0.95, 1.05), CLANG, attack=0.005, ring=0.3) * 0.12
    x = 1.0 * hit + 0.7 * smash + 0.9 * thud + 0.5 * shards + shiver
    x = arena(x, rng, n, decay=0.5, wet=0.3, lp=2600)
    return S.normalize_peak(fade(hp(x), 0.001, 0.1), -1.5)


def tendril_cut(rng):
    """A tendril parting: a wet snap as it gives, and the note it was holding falling away out of it."""
    n = secs(1.1)
    t = np.arange(n) / SR
    snap = np.zeros(n)
    S.add_clipped(snap, one_shot(burst(rng, 0.016, 0.0022), S.bandpass(rng.uniform(800, 1400), 0.9)), 0)
    S.add_clipped(snap, one_shot(burst(rng, 0.14, 0.03), S.bandpass(rng.uniform(260, 420), 1.2)) * 0.8, secs(0.002))
    S.add_clipped(snap, pebbles(secs(0.3), rng, 0.0, 0.2, 70, 1600, amp=0.3, fade_s=0.08) * 0.6, secs(0.004))
    sung = F0 * 3.0 * (1 - 0.22 * np.clip((t - 0.1) / 0.7, 0, 1))        # the note it was holding, sliding away
    note = voice(rng, n, sung, "oh", breath=0.4)
    note = note * np.clip((t - 0.05) / 0.06, 0, 1) * np.exp(-np.maximum(0, t - 0.1) / 0.3)
    x = 1.0 * snap + 0.55 * note
    x = arena(x, rng, n, decay=0.45, wet=0.3, lp=2400)
    return S.normalize_peak(fade(hp(x), 0.001, 0.12), -4.0)


def fall(rng):
    """The crown coming down when the last tendril is cut: a long sagging groan out of what is left of the voices, and
    a heavy settle of bone and stone at the end of it."""
    n = secs(2.0)
    t = np.arange(n) / SR
    sag = F0 * (1 - 0.3 * np.clip(t / 1.3, 0, 1) ** 1.3)
    groan = choir(rng, n, sag, (1.0, 2.0, 2.4), spread=0.008, breath=0.5, each=1, tilt=0.15)
    groan = groan * (0.72 + 0.28 * np.sin(2 * np.pi * rng.uniform(19, 26) * t)) * np.minimum(1, t / 0.1)
    groan = groan * np.clip((1.38 - t) / 0.2, 0, 1)
    settle = np.zeros(n)
    at = 1.22
    m = secs(0.45)
    k = np.arange(m) / SR
    S.add_clipped(settle, S.tone(rng.uniform(66, 80) - 18 * np.clip(k / 0.12, 0, 1),
                                 S.env_ad(m, 0.004, 0.1), ((1, 1.0), (2, 0.35), (3.3, 0.12))), secs(at))
    clatter = np.zeros(n)
    hit = at
    while hit < at + 0.5:
        plate = np.zeros(secs(0.2))
        S.add_clipped(plate, burst(rng, 0.012, 0.0018), 0)
        plate = one_shot(plate, S.bandpass(rng.uniform(700, 1800), 0.8))
        S.add_clipped(clatter, (plate + 0.8 * bone(rng, secs(0.2), 340, 1200, 0.04)) * rng.uniform(0.25, 1.0), secs(hit))
        hit += rng.uniform(0.035, 0.11)
    grit = pebbles(n, rng, at, 1.9, 30, 2200, amp=0.35, fade_s=0.3)
    x = 0.8 * groan + 1.0 * settle + 0.7 * clatter + 0.35 * grit
    x = arena(x, rng, n, decay=0.65, wet=0.33, lp=2400)
    return S.normalize_peak(fade(hp(x), 0.02, 0.15), -2.0)


def rise(rng):
    """The crown climbing back up: the groan run the other way, rising and swelling out of the floor, with the stone
    grinding under it, and a bell ticking once as it comes to rest."""
    n = secs(2.0)
    t = np.arange(n) / SR
    climb = F0 * (0.72 + 0.28 * np.clip(t / 1.55, 0, 1) ** 1.2)
    groan = choir(rng, n, climb, (1.0, 2.0, 2.4), spread=0.008, breath=0.5, each=1, tilt=0.15)
    groan = groan * (0.72 + 0.28 * np.sin(2 * np.pi * rng.uniform(19, 26) * t)) * np.clip(t / 1.5, 0, 1) ** 1.3
    grind = unit(moving_band(rng.standard_normal(n), 380 + 340 * np.clip(t / 1.5, 0, 1), 0.9, frame=4096))
    grind = grind * (0.2 + swells(n, rng, 0.0, 1.5, gap=(0.1, 0.3), length=(0.15, 0.5))) * 0.35
    tick = np.zeros(n)
    S.add_clipped(tick, bell(rng, secs(0.4), F0 * 2, UPPER, ring=0.3, beat=1.2), secs(1.58))
    x = 1.0 * groan + grind + 0.45 * tick
    x = arena(x, rng, n, decay=0.6, wet=0.33, lp=2400)
    return S.normalize_peak(fade(hp(x), 0.03, 0.2), -3.0)


def core(rng):
    """A hit landing on the open core: a soft hollow thud in the sculk with one brief overtone over it. Nothing
    metallic, nothing that rings on."""
    n = secs(0.6)
    t = np.arange(n) / SR
    f = rng.uniform(96, 132)
    body = S.tone(np.full(n, f) * (1 - 0.06 * np.clip(t / 0.1, 0, 1)),
                  np.minimum(1, t / 0.006) * np.exp(-t / rng.uniform(0.1, 0.14)), ((1, 1.0), (2, 0.25)))
    over = S.tone(np.full(n, f * rng.uniform(2.6, 3.2)), np.minimum(1, t / 0.004) * np.exp(-t / 0.055), ((1, 1.0),))
    pock = one_shot(burst(rng, 0.05, 0.012), S.bandpass(rng.uniform(300, 480), 0.7))
    pock = np.pad(pock, (0, max(0, n - len(pock))))[:n]
    soft = one_shot(burst(rng, 0.12, 0.035), S.bandpass(rng.uniform(900, 1400), 0.8)) * 0.25   # the wet of the sculk
    soft = np.pad(soft, (0, max(0, n - len(soft))))[:n]
    x = one_shot(1.0 * body + 0.3 * over + 0.5 * pock + soft, S.lowpass(3000, 2))
    x = arena(x, rng, n, decay=0.3, wet=0.18, lp=2200)
    return S.normalize_peak(fade(hp(x), 0.002, 0.08), -7.0)


# The fight's clock (creature.choir.ChoirActs), in seconds: the bloom's sculk brightens for 24 ticks before it pulses, and
# the grab's ripples run in for 20 before it pulls. Each of the two sounds starts with its warning and has its blow at
# that moment, so what is heard counts down with what is seen.
BLOOM_AT, GRAB_AT = 1.2, 1.0


def bloom(rng):
    """The sculk blooming in the last phase. First the warning: a dry crackle gathering over the floor, quickening and
    brightening for a second and a fifth. Then the pulse itself, a low pressure going out through the floor, and the
    crackle scattering away after it, thinner and further off as it goes."""
    n = secs(2.2)
    t = np.arange(n) / SR
    end = 2.05
    spread = np.zeros(n)
    at = 0.03
    while at < BLOOM_AT:                               # the gathering
        u = at / BLOOM_AT
        c = burst(rng, rng.uniform(0.002, 0.007), rng.uniform(0.0004, 0.0014)) * rng.lognormal(0, 0.5) * (0.5 + 0.5 * u)
        S.add_clipped(spread, one_shot(c, S.bandpass(700 + 2200 * u ** 1.4, 0.9)), secs(at))
        at += rng.exponential(1.0 / (36 + 90 * u * u))
    at = BLOOM_AT + 0.02
    while at < end:                                    # the scattering
        u = (at - BLOOM_AT) / (end - BLOOM_AT)
        c = burst(rng, rng.uniform(0.002, 0.007), rng.uniform(0.0004, 0.0014)) * rng.lognormal(0, 0.5) * (1 - 0.8 * u)
        S.add_clipped(spread, one_shot(c, S.bandpass(900 + 2600 * u ** 1.4, 0.9)), secs(at))
        at += rng.exponential(1.0 / (90 * (1 - 0.6 * u)))
    k = np.clip(t - BLOOM_AT, 0, None)
    on = (t >= BLOOM_AT).astype(float)
    pulse = S.tone(np.full(n, rng.uniform(54, 68)) * (1 - 0.12 * np.clip(k / 0.5, 0, 1)),
                   on * np.minimum(1, k / 0.03) * np.exp(-k / 0.4), ((1, 1.0), (2, 0.25), (3.2, 0.08)))
    push = np.zeros(n)
    S.add_clipped(push, one_shot(burst(rng, 0.5, 0.14), S.lowpass(220, 2)), secs(BLOOM_AT))
    rise = np.clip(t / BLOOM_AT, 0, 1)
    swell = unit(moving_band(rng.standard_normal(n), 400 + 1300 * rise, 0.9))
    swell = swell * (0.25 + 0.75 * rise ** 2) * np.minimum(1, t / 0.05) * np.where(t >= BLOOM_AT, np.exp(-k / 0.25), 1.0) * 0.26
    x = 0.8 * pulse + 0.35 * push + 1.1 * spread + swell
    x = arena(x, rng, n, decay=0.6, wet=0.32, lp=2600)
    return S.normalize_peak(fade(hp(x), 0.004, 0.14), -3.0)


def grab(rng):
    """The grab's warning and the grab: the floor drawing in. Three dry rasps run in over the stone, each nearer and
    higher than the last, with the voices drawing one long breath in behind them; then, a second in, the pull itself:
    the breath cut off, and a low hollow knock as everything in the bowl is taken a step towards the dais."""
    n = secs(1.6)
    t = np.arange(n) / SR
    rasp = np.zeros(n)
    for k in range(3):
        m = secs(0.5)
        u = np.arange(m) / m
        band = unit(moving_band(rng.standard_normal(m), 320 + 260 * k + (700 + 300 * k) * u ** 1.5, 1.4, frame=1024))
        S.add_clipped(rasp, band * np.sin(np.pi * u ** 0.7) ** 2 * (0.55 + 0.22 * k), secs(0.25 * k))
    draw = np.clip(t / GRAB_AT, 0, 1)
    breath = choir(rng, n, F0 * 2 * (1 + 0.5 * draw ** 2), (1.0, 1.5), spread=0.006, breath=0.9)
    breath = breath * draw ** 1.5 * np.clip((GRAB_AT + 0.015 - t) / 0.015, 0, 1)
    grit = pebbles(n, rng, 0.05, GRAB_AT, 40, 1800, amp=0.5) * draw
    pull = np.zeros(n)
    m = secs(0.45)
    k = np.arange(m) / SR
    S.add_clipped(pull, S.tone(rng.uniform(70, 82) - 18 * np.clip(k / 0.12, 0, 1), S.env_ad(m, 0.004, 0.11),
                               ((1, 1.0), (2, 0.35), (3.1, 0.12))), secs(GRAB_AT))
    S.add_clipped(pull, bone(rng, secs(0.2), 300, 620, 0.045) * 0.7, secs(GRAB_AT))
    S.add_clipped(pull, one_shot(burst(rng, 0.12, 0.035), S.lowpass(320, 2)) * 0.6, secs(GRAB_AT))
    x = 0.45 * rasp + 0.4 * breath + 0.25 * grit + 1.4 * pull
    x = arena(x, rng, n, decay=0.45, wet=0.28, lp=2400)
    return S.normalize_peak(fade(hp(x), 0.004, 0.12), -3.0)


def root(rng):
    """A tendril reaching the floor and taking root: the hard end of it knocked into the stone, the stone giving a
    little under it, and its grit settling."""
    n = secs(0.6)
    x = stone_knock(rng, n, 150, 230, 0.03)
    S.add_clipped(x, bone(rng, secs(0.18), 280, 560, 0.04) * 0.6, secs(0.002))
    S.add_clipped(x, crack(rng, rng.uniform(700, 1300), 0.02, 0.004) * 0.45, secs(0.01))
    x = x + pebbles(n, rng, 0.03, 0.4, 35, 2100, amp=0.3, fade_s=0.12)
    x = arena(x, rng, n, decay=0.3, wet=0.2, lp=2600)
    return S.normalize_peak(fade(hp(x), 0.001, 0.08), -6.0)


def sprout(rng):
    """A sprout rising out of the sculk: a small tick as the floor gives, and the quiet unfurling behind it."""
    n = secs(0.5)
    t = np.arange(n) / SR
    u = t / 0.5
    tick = np.zeros(n)
    S.add_clipped(tick, one_shot(burst(rng, 0.008, 0.0012), S.bandpass(rng.uniform(650, 1100), 0.9)), 0)
    S.add_clipped(tick, bone(rng, secs(0.1), 340, 760, 0.014) * 0.6, 0)
    unfurl = unit(moving_band(rng.standard_normal(n), 450 + 1000 * u ** 1.2, 1.1))
    unfurl = unfurl * (0.4 + 0.6 * np.abs(unit(S.filt(rng.standard_normal(n), S.lowpass(45, 1)))))
    unfurl = unfurl * np.minimum(1, t / 0.02) * np.exp(-t / 0.18)
    soft = one_shot(burst(rng, 0.1, 0.035), S.bandpass(rng.uniform(240, 380), 1.1))
    soft = np.pad(soft, (0, max(0, n - len(soft))))[:n]
    x = 0.8 * tick + 0.45 * unfurl + 0.4 * soft
    x = arena(x, rng, n, decay=0.25, wet=0.16, lp=3000)
    return S.normalize_peak(fade(hp(x), 0.002, 0.07), -15.0)


def hurt(rng):
    """Hurt: the chord strains and will not hold, its voices thrown a semitone and a tritone off each other and
    breaking as they go, with a bone plate cracking across them."""
    n = secs(0.7)
    t = np.arange(n) / SR
    f0 = F0 * rng.uniform(0.97, 1.04) * (1 - 0.1 * np.clip(t / 0.7, 0, 1))
    strain = choir(rng, n, f0, (2.0, 2.12, 2.4, 2.83, 3.0), spread=0.01, breath=0.5, each=1, tilt=0.1)
    broken = 0.35 + 0.65 * (np.sin(2 * np.pi * rng.uniform(16, 24) * t + rng.uniform(0, 6.28)) > -0.25)
    env = np.minimum(1, t / 0.008) * np.exp(-t / 0.3)
    plate = np.zeros(n)
    S.add_clipped(plate, one_shot(burst(rng, 0.016, 0.0022), S.bandpass(rng.uniform(1100, 1800), 0.9)), 0)
    S.add_clipped(plate, bone(rng, secs(0.25), 400, 1100, 0.05) * 0.9, 0)
    x = 1.0 * strain * broken * env + 0.6 * plate
    x = arena(x, rng, n, decay=0.45, wet=0.3, lp=2600)
    return S.normalize_peak(fade(hp(x), 0.002, 0.1), -2.5)


def idle(rng):
    """Alive and up: two voices holding the same note a little out of tune, so it beats slowly, breathing in and out.
    Very quiet, and it fades up and away at its ends, so the game can play it over and over without a seam."""
    n = secs(5.0)
    t = np.arange(n) / SR
    apart = rng.uniform(0.24, 0.36)                         # that far out of tune, the two of them beat this slowly
    a = voice(rng, n, F0 * 2, "oo", breath=0.45)
    b = voice(rng, n, F0 * 2 + apart, "oh", breath=0.45)
    breathe = 0.62 + 0.38 * np.sin(2 * np.pi * rng.uniform(0.16, 0.24) * t + rng.uniform(0, 6.28))
    x = arena((a + 0.9 * b) * breathe, rng, n, decay=0.7, wet=0.28, lp=1800)
    return S.normalize_rms(fade(hp(x), 1.2, 1.4), -30)


def death(rng):
    """Beaten: the voices come apart one by one, the highest first, each sagging as it goes out; and when the last of
    them has gone, one bell is struck somewhere far off and sinks away."""
    n = secs(5.0)
    t = np.arange(n) / SR
    voices = np.zeros(n)
    for i, r in enumerate((4.0, 3.0, 2.4, 2.0, 1.0)):
        gone = 0.45 + 0.4 * i + rng.uniform(0, 0.2)
        sag = 1 - rng.uniform(0.015, 0.05) * np.clip((t - gone + 0.6) / 0.6, 0, 1)
        f = F0 * r * (1 + 0.004 * rng.standard_normal()) * sag
        env = np.minimum(1, t / 0.06) * np.clip((gone - t) / rng.uniform(0.22, 0.45), 0, 1)
        voices += voice(rng, n, f, ("oo", "oh", "uh")[i % 3], breath=0.45) * env / (1 + 0.18 * i)
    at = 2.55
    m = n - secs(at)
    tt = np.arange(m) / SR
    sink = 1 - 0.14 * (1 - np.exp(-tt / 1.5))               # 84 Hz down to 72: none of it is lost down low
    last = np.zeros(n)
    S.add_clipped(last, one_shot(bell(rng, m, F0, ring=0.75, beat=0.4, bend=sink), S.lowpass(950, 2)), secs(at))
    x = 0.85 * unit(voices) + 2.2 * last
    x = arena(x, rng, n, decay=1.0, wet=0.55, lp=1800)
    return S.normalize_peak(fade(hp(x), 0.03, 0.8), -2.0)


def reliquary(rng):
    """The city reliquary's lid: stone ground back off stone, a knock as it comes to rest, and one soft chime out of
    the dark inside as it gives up its key."""
    n = secs(1.6)
    t = np.arange(n) / SR
    slide = unit(moving_band(rng.standard_normal(n), 420 + 360 * np.clip(t / 0.7, 0, 1), 0.9))
    slide = slide * (0.2 + swells(n, rng, 0.0, 0.72, gap=(0.05, 0.16), length=(0.08, 0.26))) * np.clip((0.78 - t) / 0.12, 0, 1)
    grit = pebbles(n, rng, 0.02, 0.75, 30, 1300, amp=0.3)
    rest = np.zeros(n)
    S.add_clipped(rest, stone_knock(rng, secs(0.3)), secs(0.74))
    chime = np.zeros(n)
    S.add_clipped(chime, bell(rng, secs(0.78), F0 * 2, BELL[:7], attack=0.02, ring=0.5, beat=0.8), secs(0.82))
    x = 0.6 * slide + 0.25 * grit + 0.6 * rest + 1.0 * chime
    x = arena(x, rng, n, decay=0.7, wet=0.35, lp=2400)
    return S.normalize_peak(fade(hp(x), 0.01, 0.2), -8.0)


def key_set(rng):
    """A choir key pressed into its socket: a dull stone click, and a note that comes up under it and holds, two
    voices a tone apart that never settle onto one another."""
    n = secs(1.2)
    t = np.arange(n) / SR
    click = stone_knock(rng, n)
    held = (voice(rng, n, F0 * 2, "oo", breath=0.4)
            + 0.85 * voice(rng, n, F0 * 2 * 1.125, "oh", breath=0.4))     # a tone apart: it asks, it does not answer
    hold = np.clip((t - 0.12) / 0.4, 0, 1) ** 1.3
    x = 1.0 * click + 0.5 * unit(held) * hold
    x = arena(x, rng, n, decay=0.6, wet=0.3, lp=2200)
    return S.normalize_peak(fade(hp(x), 0.001, 0.22), -9.0)


MAKERS = {"wake": wake, "toll": toll, "toll_far": toll_far, "windup": windup, "slam": slam,
          "tendril_cut": tendril_cut, "fall": fall, "rise": rise, "core": core, "bloom": bloom, "sprout": sprout,
          "hurt": hurt, "idle": idle, "death": death, "reliquary": reliquary, "key_set": key_set,
          "grab": grab, "root": root}


def names():
    """Every file: the sound it belongs to, its name (one of a sound keeps the bare name, several are name_1,
    name_2, ...), which one of its sound it is, and how long it should be."""
    for sound, (count, seconds) in FILES.items():
        for i in range(1, count + 1):
            yield sound, (sound if count == 1 else f"{sound}_{i}"), i, seconds


# ----------------------------------------------------------------------------------------------- the check

def probe(path):
    """ffprobe's channels, sample rate and length of an OGG."""
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=channels,sample_rate:format=duration",
                          "-of", "json", path], check=True, capture_output=True, text=True).stdout
    j = json.loads(out)
    return int(j["streams"][0]["channels"]), int(j["streams"][0]["sample_rate"]), float(j["format"]["duration"])


def decode(path):
    """The OGG decoded to mono floats at 44.1 kHz: the sound as the game hears it, past the encoder."""
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def share(x, lo, hi):
    """The share of a sound's energy between two frequencies."""
    power = np.abs(np.fft.rfft(x)) ** 2
    f = np.fft.rfftfreq(len(x), 1 / SR)
    return float(power[(f >= lo) & (f < hi)].sum() / (power.sum() + 1e-30))


def check():
    """Every file there, mono, 44.1 kHz, about the length it should be, not clipping, with next to nothing below
    25 Hz and nothing carrying its weight below 40. Prints each one's length, peak, level and bands, and returns the
    problems found."""
    problems = []
    print(f"{'file':16} {'secs':>5} {'peak dB':>8} {'rms dB':>7} {'<25 Hz':>8} {'<60 Hz':>7} {'<40':>6} {'40-200':>7} "
          f"{'200-600':>8} {'600-1k':>7} {'>1k':>6}")
    for sound, name, i, seconds in names():
        path = os.path.join(OUT, name + ".ogg")
        if not os.path.exists(path):
            problems.append(f"{name}.ogg: missing")
            continue
        channels, rate, length = probe(path)
        x = decode(path)
        peak = float(np.max(np.abs(x)))
        bands = [share(x, *b) for b in ((0, 40), (40, 200), (200, 600), (600, 1000), (1000, SR / 2))]
        s25, s60 = share(x, 0, LOW_HZ), share(x, 0, 60.0)
        notes = []
        if channels != 1: problems.append(f"{name}.ogg: {channels} channels (only mono is placed in the world)")
        if rate != SR: problems.append(f"{name}.ogg: {rate} Hz")
        if abs(length - seconds) > TOLERANCE:
            problems.append(f"{name}.ogg: {length:.2f} s, not about {seconds} s")
            notes.append("LENGTH")
        if peak >= 1.0:
            problems.append(f"{name}.ogg: clipping (peak {peak:.3f})")
            notes.append("CLIPS")
        if s25 >= LOW_SHARE:
            problems.append(f"{name}.ogg: {s25:.2%} of its energy below {LOW_HZ:.0f} Hz")
            notes.append("TOO LOW")
        if bands[0] > 0.25:
            problems.append(f"{name}.ogg: {bands[0]:.0%} of its energy below 40 Hz, which carries nothing")
            notes.append("WEIGHT LOW")
        print(f"{name + '.ogg':16} {length:5.2f} {20 * np.log10(peak + 1e-12):8.2f} "
              f"{20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-12):7.2f} {s25:8.4%} {s60:7.2%} "
              + " ".join(f"{b:6.1%}" for b in bands) + ("  " + " ".join(notes) if notes else ""))
    print(f"the Hollow Choir's sounds: {sum(c for c, _ in FILES.values())} files, {len(problems)} problems")
    return problems


def previews(folder, wanted=PREVIEWS):
    """WAVs of a few of them, and a plot of each (its waveform, a spectrogram and its spectrum with the 25 and 40 Hz
    lines marked), so they can be checked without listening. Needs matplotlib."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    os.makedirs(folder, exist_ok=True)
    for name in wanted:
        src = os.path.join(OUT, name + ".ogg")
        wav = os.path.join(folder, name + ".wav")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-ac", "1", "-ar", str(SR), wav], check=True)
        x = decode(src)
        dur = len(x) / SR
        frame, hop = 4096, 1024
        win = np.hanning(frame)
        cols = max(1, (len(x) - frame) // hop + 1)
        spec = np.array([np.abs(np.fft.rfft(x[i * hop:i * hop + frame] * win)) for i in range(cols)]).T
        sdb = 20 * np.log10(spec / (spec.max() + 1e-12) + 1e-9)
        sf = np.fft.rfftfreq(frame, 1 / SR)
        top = int(np.searchsorted(sf, 2000))
        power = np.abs(np.fft.rfft(x)) ** 2
        f = np.fft.rfftfreq(len(x), 1 / SR)
        fig, ax = plt.subplots(3, 1, figsize=(10, 9))
        ax[0].plot(np.arange(len(x)) / SR, x, lw=0.4, color="#3b4a6b")
        ax[0].set(xlim=(0, dur), ylim=(-1, 1), ylabel="amplitude",
                  title=f"{name}  {dur:.2f} s  peak {20 * np.log10(np.max(np.abs(x)) + 1e-12):.1f} dB")
        im = ax[1].imshow(sdb[:top], origin="lower", aspect="auto", vmin=-80, vmax=0, cmap="magma",
                          extent=(0.0, dur, 0.0, float(sf[top])))
        ax[1].set(ylabel="Hz", xlabel="s")
        fig.colorbar(im, ax=ax[1], label="dB")
        ax[2].semilogx(f[1:], 10 * np.log10(power[1:] / (power.max() + 1e-30) + 1e-14), lw=0.6, color="#3b4a6b")
        for line in (LOW_HZ, 40.0):
            ax[2].axvline(line, color="#b03030", ls="--", lw=0.8)
        ax[2].set(xlim=(10, 20000), ylim=(-120, 5), xlabel="Hz", ylabel="dB",
                  title=f"{share(x, 0, LOW_HZ):.4%} of its energy below {LOW_HZ:.0f} Hz, "
                        f"{share(x, 0, 60.0):.2%} below 60 Hz, {share(x, 200, 600):.1%} in 200-600 Hz")
        ax[2].grid(True, which="both", lw=0.3, alpha=0.4)
        fig.tight_layout()
        fig.savefig(os.path.join(folder, name + ".png"), dpi=110)
        plt.close(fig)
        print(f"{name}: {wav} and {os.path.join(folder, name + '.png')}")


def main():
    S.OUT = OUT
    for k, (sound, name, i, _) in enumerate(names()):
        S.save(name, MAKERS[sound](np.random.default_rng(SEED + 31 * k + i)))


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--check" not in args:
        main()
    problems = check()
    if "--previews" in args:
        previews(args[args.index("--previews") + 1])
    sys.exit(1 if problems else 0)
