#!/usr/bin/env python3
"""Makes the creatures' sounds from scratch (no recordings). The Mossback: its call, heavy steps, the hiss when it
sees you, the snap of its jaws, the growl while it shakes its catch, hurt and death. The Skein: the dry patter of its
many feet, its clicking chitter, the rattling hiss of its plates as it rears, the crack of its mandibles, hurt and
death. Built from tones and filtered noise with the helpers in synth_sounds.py, with a short cave echo.
Deterministic: the same script gives the same sounds.

Needs numpy and ffmpeg with libvorbis. Writes src/main/resources/assets/explorationremix/sounds/creature/<name>/*.ogg
(tools/sounds.py lists them in sounds.json). Run from anywhere: python3 tools/synth_creature_sounds.py [mossback]
[skein] (both when none is named).
"""
import os, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth_sounds as S
from synth_sounds import SR

CREATURE_DIR = os.path.join(S.ROOT, "src/main/resources/assets/explorationremix/sounds/creature")
S.OUT = os.path.join(CREATURE_DIR, "mossback")

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


# =============================================================================================== the Skein
# A long, many-legged, shell-plated thing: everything it makes is dry and clicky, shell on shell and claw on rock,
# high and close, nothing throaty. A small, hard cave echo.

SKEIN_COUNTS = {"patter": 4, "chitter": 3, "rattle": 2, "bite": 2, "hurt": 2, "death": 1}


def tick(rng, fc, q=1.6, length=0.004):
    """One hard little click: a burst of noise a few milliseconds long, rung through a narrow band."""
    n = secs(length + 0.01)
    t = np.arange(n) / SR
    x = rng.standard_normal(n) * np.exp(-t / (length / 3))
    return S.filt(x, S.bandpass(fc, q))


def clicks(rng, n, times, fc_lo, fc_hi, amp_lo=0.4, q=1.6, length=0.004):
    """Clicks at the given times (seconds), each in its own band and loudness, in a buffer of n samples."""
    out = np.zeros(n)
    for at in times:
        c = tick(rng, rng.uniform(fc_lo, fc_hi), q, length) * rng.uniform(amp_lo, 1.0)
        S.add_clipped(out, c, int(at * SR))
    return out


def hard_room(x, rng, wet=0.16):
    return cave(x, rng, 0.18, wet)


def patter(rng):
    """Its feet on rock as it runs: a quick, uneven spill of tiny claw-ticks in waves down the body, too many to count."""
    dur = rng.uniform(0.32, 0.42)
    n = secs(dur + 0.05)
    t = np.arange(n) / SR
    times, at = [], 0.0
    wave_rate = rng.uniform(9, 12)
    while at < dur:
        # the waves of legs: the ticks bunch up and thin out a few times over
        wave = 0.5 + 0.5 * np.sin(2 * np.pi * at * wave_rate)
        at += rng.uniform(0.004, 0.012) + 0.012 * (1 - wave)
        times.append(at)
    x = clicks(rng, n, times, 2600, 6200, 0.25, 1.4, 0.003)
    # the faint scuff of its weight under them
    scuff = noise(rng, n, S.bandpass(1300, 0.8)) * 0.06 * np.minimum(1, t / 0.04)
    x = (x + scuff) * np.clip((dur + 0.04 - t) / 0.06, 0, 1)
    return S.normalize_peak(hard_room(x, rng, 0.12), -5.0)


def chitter(rng):
    """Its voice while it lies still: two to four bursts of fast dry clicks, each one quickening as it goes."""
    groups = int(rng.integers(2, 5))
    dur = 0.0
    bursts = []
    for _ in range(groups):
        length = rng.uniform(0.08, 0.2)
        rate0, rate1 = rng.uniform(35, 50), rng.uniform(60, 90)
        fc = rng.uniform(1900, 2600)
        times, at = [], 0.0
        while at < length:
            at += (1.0 / (rate0 + (rate1 - rate0) * at / length)) * rng.uniform(0.8, 1.2)
            times.append(dur + at)
        bursts.append((times, fc, fc * rng.uniform(1.15, 1.35)))
        dur += length + rng.uniform(0.05, 0.16)
    n = secs(dur + 0.1)
    t = np.arange(n) / SR
    x = np.zeros(n)
    for times, lo, hi in bursts:
        x += clicks(rng, n, times, lo, hi, 0.55, 3.0, 0.005)
    # a thin, glassy ring under the clicks: the plates of its head buzzing with them
    ring = S.filt(np.abs(x), S.lowpass(80, 1)) * np.sin(2 * np.pi * rng.uniform(4200, 5200) * t) * 0.6
    return S.normalize_peak(hard_room(x + ring, rng, 0.2), -4.0)


def rattle(rng):
    """It rears: its plates clatter together, quickening to a buzz over a rising hiss, then stop hard as it lunges."""
    dur = rng.uniform(0.42, 0.55)
    n = secs(dur + 0.05)
    t = np.arange(n) / SR
    times, at = [], 0.0
    while at < dur:
        at += (1.0 / (25 + 160 * (at / dur) ** 1.5)) * rng.uniform(0.7, 1.3)
        times.append(at)
    knocks = clicks(rng, n, times, 700, 1600, 0.5, 2.2, 0.007) * 1.2
    shells = clicks(rng, n, [a + rng.uniform(0.0, 0.004) for a in times], 3000, 5000, 0.2, 1.2, 0.003) * 0.5
    hiss_ = noise(rng, n, S.bandpass(rng.uniform(3800, 4600), 1.1)) * 0.22 * np.minimum(1, t / dur) ** 2
    swell = 0.35 + 0.65 * np.minimum(1, t / dur)
    cut = np.clip((dur + 0.01 - t) / 0.01, 0, 1)
    return S.normalize_peak(hard_room((knocks + shells + hiss_) * swell * cut, rng, 0.18), -2.0)


def mandibles(rng):
    """The bite: its two mandibles cross with a scissor-click and a crack, almost on top of each other."""
    n = secs(0.25)
    t = np.arange(n) / SR
    gap = rng.uniform(0.006, 0.012)
    x = clicks(rng, n, [0.002, 0.002 + gap], 3200, 4600, 0.8, 2.5, 0.006) * 1.4
    crack = noise(rng, n, S.bandpass(rng.uniform(1800, 2400), 1.5)) * np.exp(-t / 0.012) * 0.7
    knock = S.tone(rng.uniform(380, 460) * np.ones(n), np.exp(-t / 0.02), ((1, 1.0), (2.7, 0.4)))
    return S.normalize_peak(hard_room(x + crack + 0.35 * knock, rng, 0.14), -1.5)


def screech(rng, dur, f_start, f_end):
    """A harsh, grating buzz: a file of ridges scraped (a train of clicks fast enough to sound as a pitch)."""
    n = secs(dur)
    t = np.arange(n) / SR
    wobble = S.filt(rng.standard_normal(n), S.lowpass(30, 1))
    f = (f_start + (f_end - f_start) * t / dur) * (1 + 0.06 * wobble / (np.std(wobble) + 1e-12))
    ph = np.cumsum(f) / SR
    train = (np.mod(ph, 1.0) < 0.12).astype(float) - 0.12
    train = S.filt(train, S.bandpass(rng.uniform(2400, 3200), 0.9))
    grit = noise(rng, n, S.bandpass(5000, 1.2))
    return train / (np.max(np.abs(train)) + 1e-12) + 0.08 * grit / (np.max(np.abs(grit)) + 1e-12)


def skein_hurt(rng):
    """A short, grating screech that drops away, with a clatter of plates."""
    dur = rng.uniform(0.28, 0.36)
    n = secs(dur)
    t = np.arange(n) / SR
    x = screech(rng, dur, rng.uniform(170, 210), rng.uniform(90, 120))
    x *= np.minimum(1, t / 0.01) * np.exp(-t / (dur * 0.55))
    x += clicks(rng, n, sorted(rng.uniform(0, dur * 0.6, 7)), 800, 1600, 0.4, 2.0, 0.006) * 0.8
    return S.normalize_peak(hard_room(x, rng, 0.2), -2.0)


def skein_death(rng):
    """A long screech that slows into separate scrapes, its legs ticking on after it, fewer and fewer."""
    dur = 1.8
    n = secs(dur)
    t = np.arange(n) / SR
    x = screech(rng, dur, 160, 30) * np.exp(-t / 0.7) * np.minimum(1, t / 0.02)
    legs = [rng.uniform(0.2, 0.5)]
    while legs[-1] < dur - 0.08:
        legs.append(legs[-1] + rng.uniform(0.03, 0.08) * (1 + 4 * legs[-1] / dur))
    x += clicks(rng, n, legs, 2600, 5200, 0.2, 1.4, 0.003) * 0.4
    x *= np.clip((dur - t) / 0.2, 0, 1)
    return S.normalize_peak(hard_room(x, rng, 0.25), -2.0)


SKEIN_MAKERS = {"patter": patter, "chitter": chitter, "rattle": rattle, "bite": mandibles, "hurt": skein_hurt,
                "death": skein_death}

# each creature's sounds (how many of each), their makers and the base of their seeds
CREATURES = {"mossback": (COUNTS, MAKERS, 7700), "skein": (SKEIN_COUNTS, SKEIN_MAKERS, 9100)}


def make(name):
    counts, makers, base = CREATURES[name]
    S.OUT = os.path.join(CREATURE_DIR, name)
    for k, (sound, count) in enumerate(counts.items()):
        for i in range(count):
            rng = np.random.default_rng(base + 31 * k + i)
            S.save(f"{sound}{i + 1}", makers[sound](rng))


def main():
    for name in sys.argv[1:] or list(CREATURES):
        make(name)


if __name__ == "__main__":
    main()
