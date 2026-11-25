#!/usr/bin/env python3
"""Makes the timed swings' sounds from scratch (0.12.0; no recordings): a light swing's quick whoosh, the chain's chop
(lower and heavier), a strong attack's thrust (sharp, with a thin ring of steel), the rising hum as a held swing turns
strong, the quiet shimmer and note of a rune sword's ready light (0.12.2: subtler than 0.12.0's chime, which 0.12.1
dropped), and the hunters' wind-ups: half a second that swells into the strike (the Mossback's throaty intake and
growl, the Skein's clicks running faster and faster), so the strike can be timed by ear. Built from tones and filtered
noise with the helpers in synth_sounds.py. Deterministic.

Needs numpy and ffmpeg with libvorbis. Writes src/main/resources/assets/explorationremix/sounds/swing/*.ogg,
sounds/runes/*.ogg and sounds/creature/<who>/windup*.ogg (tools/sounds.py lists them in sounds.json).
Run from anywhere: python3 tools/synth_swing_sounds.py [folder ...] (only those sets, e.g. "runes")
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


def ready(rng, note):
    """A rune sword's ready light (0.22.7, Taran 2026-11-25: the whole sword swells with light and bursts into glints,
    then sparkles): a faint, airy shimmer rising with the swell, one soft, round note at its height (0.15 s in), and a
    few tiny, quiet glints sprinkled after it as the burst's stars flash, all dying away. Near-harmonic partials and a
    slow onset keep the note from ringing like glass."""
    dur = 1.2
    n = secs(dur)
    t = np.arange(n) / SR
    peak = 0.15
    k = np.clip(t / peak, 0, 1)
    shimmer = sweep_band(rng.standard_normal(n), 2400 + 3000 * k, 2.5)
    shimmer *= k ** 1.5 * np.exp(-np.maximum(0, t - peak) / 0.08) * 0.12
    tt = np.maximum(0, t - peak)
    bell = np.zeros(n)
    for ratio, amp, decay in ((1.0, 1.0, 0.4), (2.0, 0.15, 0.18), (3.01, 0.04, 0.1)):
        bell += amp * np.sin(2 * np.pi * note * ratio * tt) * np.exp(-tt / decay)
    bell *= (t >= peak) * np.minimum(1, tt / 0.02)
    # the burst's glints: a handful of tiny high notes, quiet, scattered through the third of a second after the height
    glints = np.zeros(n)
    for _ in range(5):
        at = peak + 0.03 + rng.uniform(0.0, 0.33)
        f = note * rng.choice([3.0, 4.0, 5.04, 6.0])
        g = np.maximum(0, t - at)
        glints += (t >= at) * np.sin(2 * np.pi * f * g) * np.exp(-g / 0.045) * np.minimum(1, g / 0.003) * rng.uniform(0.04, 0.08)
    x = bell + shimmer + glints
    wet = S.convolve(x, S.reverb_ir(0.9, rng, 0.3, lp=4000))[:n]
    x = S.filt(x + 0.3 * wet, S.lowpass(6500, 2))
    return S.normalize_peak(S.fade_edges(x, 0.004), -14.0)


def smash(rng):
    """A sword's heavy jump attack (0.22.7) coming down: a deep rush of air as the blade falls, then a heavy, dull thud
    with a crunch of grit as it meets what is under it, and a short low tail."""
    dur = 0.62
    n = secs(dur)
    t = np.arange(n) / SR
    hit = 0.17
    rush = whoosh(rng, hit + 0.04, 260, 1100, 420, 0.8, 0.9, q=1.0)
    x = np.zeros(n)
    x[:len(rush)] += S.normalize_peak(rush, 0.0) * 0.55
    tt = np.maximum(0, t - hit)
    on = t >= hit
    # the thud: a low sine dropping in pitch, struck hard
    f = 68 * (1 + 0.6 * np.exp(-tt / 0.03))
    thud = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt / 0.11) * on
    body = S.filt(rng.standard_normal(n), S.bandpass(rng.uniform(150, 190), 1.1)) * np.exp(-tt / 0.07) * on
    grit = S.filt(rng.standard_normal(n), lambda fr: S.bandpass(2200, 0.8)(fr)) * np.exp(-tt / 0.035) * on
    crack = S.filt(rng.standard_normal(n), S.lowpass(5000, 2)) * np.exp(-tt / 0.008) * on
    x += thud * 1.0 + body / (np.std(body[on]) + 1e-9) * 0.25 + grit / (np.std(grit[on]) + 1e-9) * 0.12 \
        + crack / (np.std(crack[on]) + 1e-9) * 0.2
    wet = S.convolve(x, S.reverb_ir(0.5, rng, 0.14, lp=1800))[:n]
    x = S.filt(x + 0.25 * wet, S.lowpass(7000, 2))
    return S.normalize_peak(S.fade_edges(x, 0.004), -1.5)


def metal(rng, t, partials, at):
    """Struck metal from {at} seconds: (frequency, amplitude, decay) partials, each a little out of tune."""
    tt = np.maximum(0, t - at)
    x = np.zeros(len(t))
    for f, a, d in partials:
        f = f * rng.uniform(0.985, 1.015)
        x += a * np.sin(2 * np.pi * f * tt + rng.uniform(0, 6.28)) * np.exp(-tt / d)
    return x * (t >= at) * np.minimum(1, tt / 0.0015)


def guard_raise(rng):
    """A sword brought up to guard (0.22.7): a short, soft slide of steel through the air and a faint ring."""
    dur = 0.3
    n = secs(dur)
    t = np.arange(n) / SR
    air = whoosh(rng, 0.16, 1400, 4200, 2600, 0.55, 0.15, q=1.8)
    x = np.zeros(n)
    x[:len(air)] += S.normalize_peak(air, 0.0) * 0.7
    x += metal(rng, t, ((3150, 1.0, 0.07), (4890, 0.5, 0.05), (6420, 0.25, 0.035)), 0.1) * 0.12
    x = S.filt(x, lambda f: S.lowpass(8000, 2)(f) * S.highpass(500, 2)(f))
    return S.normalize_peak(S.fade_edges(x, 0.003), -9.0)


def guard_block(rng):
    """A blow taken on a raised sword (0.22.7): a dull, heavy clank, the blade shuddering under it, quickly damped."""
    dur = 0.42
    n = secs(dur)
    t = np.arange(n) / SR
    at = 0.005
    tt = np.maximum(0, t - at)
    knock = S.filt(rng.standard_normal(n), S.lowpass(2400, 2)) * np.exp(-tt / 0.012) * (t >= at)
    thump = np.sin(2 * np.pi * rng.uniform(105, 125) * tt) * np.exp(-tt / 0.06) * (t >= at)
    clank = metal(rng, t, ((610, 1.0, 0.09), (1330, 0.7, 0.07), (2190, 0.45, 0.05), (3020, 0.25, 0.035)), at)
    x = knock / (np.std(knock[t >= at]) + 1e-9) * 0.35 + thump * 0.6 + clank * 0.55
    wet = S.convolve(x, S.reverb_ir(0.4, rng, 0.1, lp=2500))[:n]
    x = S.filt(x + 0.2 * wet, S.lowpass(6000, 2))
    return S.normalize_peak(S.fade_edges(x, 0.003), -2.5)


def guard_parry(rng):
    """A parry (0.22.7): a sharp, bright clash of steel on the beat, ringing on clear and high for half a second, with a
    glint of shimmer over it, so a parry is heard as plainly as it is seen."""
    dur = 0.9
    n = secs(dur)
    t = np.arange(n) / SR
    at = 0.004
    tt = np.maximum(0, t - at)
    on = t >= at
    clash = S.filt(rng.standard_normal(n), S.highpass(1800, 2)) * np.exp(-tt / 0.006) * on
    ring = metal(rng, t, ((1870, 0.8, 0.32), (1893, 0.5, 0.3), (3080, 1.0, 0.42), (4610, 0.6, 0.3), (6170, 0.35, 0.2),
                          (8020, 0.15, 0.12)), at)
    shimmer = sweep_band(rng.standard_normal(n), 5200 + 1500 * np.exp(-t / 0.2), 3.0) * np.exp(-tt / 0.18) * on * 0.08
    x = clash / (np.std(clash[on]) + 1e-9) * 0.4 + ring * 0.55 + shimmer
    wet = S.convolve(x, S.reverb_ir(0.8, rng, 0.25, lp=6000))[:n]
    x = S.filt(x + 0.3 * wet, S.lowpass(11000, 2))
    return S.normalize_peak(S.fade_edges(x, 0.003), -1.5)


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
    ("runes", "ready", ready, 2, 4500),
    ("swing", "smash", smash, 2, 4800),
    ("guard", "raise", guard_raise, 2, 4900),
    ("guard", "block", guard_block, 3, 5000),
    ("guard", "parry", guard_parry, 2, 5100),
    ("creature/mossback", "windup", mossback_windup, 2, 4600),
    ("creature/skein", "windup", skein_windup, 2, 4700),
]


# the ready light's note, clear but not piercing: B5, then A5
READY_NOTES = (987.8, 880.0)


def main(only):
    for folder, name, maker, count, base in SETS:
        if only and folder not in only:
            continue
        S.OUT = os.path.join(SOUNDS, folder)
        for i in range(count):
            rng = np.random.default_rng(base + i)
            S.save(f"{name}{i + 1}", ready(rng, READY_NOTES[i]) if maker is ready else maker(rng))


if __name__ == "__main__":
    main(set(sys.argv[1:]))
