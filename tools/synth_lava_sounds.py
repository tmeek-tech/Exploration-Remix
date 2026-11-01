#!/usr/bin/env python3
"""Makes the Basalt Galleries' lava cycle sounds from scratch, with synth_sounds.py's building blocks: the rumbles that
warn of a rise (lava_rumble1-3) and the rise's continuous surge (lava_surge, a seamless loop). Kept apart from the
biomes' ambient sounds so either set can be remade alone; deterministic, with its own random seed.

Needs numpy and ffmpeg with libvorbis. Writes src/main/resources/assets/explorationremix/sounds/ambient/lava_*.ogg.
Run from anywhere: python3 tools/synth_lava_sounds.py
"""
import numpy as np

from synth_sounds import (SR, add_clipped, add_wrapped, bandpass, brown, convolve, convolve_wrapped, env_ad, fade_edges,
                          filt, highpass, lowpass, normalize_peak, normalize_rms, periodic_noise, pink, reverb_ir, save,
                          slow_env, tone)


def rumble_noise(n, rng, cutoff):
    """Brown noise under the cutoff, without the drift below 30 Hz that no speaker plays but would eat the headroom."""
    return periodic_noise(n, rng, lambda f: brown(f) * lowpass(cutoff, 3)(f) * highpass(30, 4)(f))


def lava_rumble(rng, seconds=6.5):
    """The deep rock groaning before the lava rises: a sub-bass roll that swells and fades, grinding rock in the middle
    (so small speakers carry it too) and a crack or two, in a big dark hall."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    attack, hold = rng.uniform(0.7, 1.1), rng.uniform(2.0, 2.8)
    env = np.clip(t / attack, 0, 1) ** 2 * np.exp(-np.maximum(0, t - attack - hold) / rng.uniform(0.9, 1.3))
    roll = 1 + 0.35 * np.sin(2 * np.pi * rng.uniform(1.0, 1.6) * t + rng.uniform(0, 6)) \
        + 0.2 * np.sin(2 * np.pi * rng.uniform(2.4, 3.4) * t + rng.uniform(0, 6))
    sub = rumble_noise(n, rng, 60)
    body = periodic_noise(n, rng, lambda f: pink(f) * bandpass(120, 1.2)(f))
    grind = periodic_noise(n, rng, lambda f: pink(f) * bandpass(rng.uniform(280, 380), 2.5)(f))
    bursts = np.zeros(n)
    at = attack * 0.6
    while at < attack + hold:
        m = int(rng.uniform(0.2, 0.6) * SR)
        w = np.sin(np.pi * np.arange(m) / m) ** 2 * rng.uniform(0.5, 1.0)
        add_clipped(bursts, w, int(at * SR))
        at += rng.uniform(0.25, 0.8)
    cracks = np.zeros(n)
    for _ in range(rng.integers(1, 4)):
        m = int(rng.uniform(0.03, 0.08) * SR)
        c = rng.standard_normal(m) * np.exp(-np.arange(m) / (0.012 * SR))
        add_clipped(cracks, c * rng.uniform(0.5, 1.0), int(rng.uniform(0.3, attack + hold) * SR))
    cracks = filt(cracks, lowpass(1500, 2))
    x = env * roll * (1.0 * sub + 0.8 * body) + env * bursts * 0.35 * grind + 0.35 * cracks
    x = x + convolve(x, reverb_ir(2.8, rng, 0.9, lp=700))[:n] * 0.5
    return fade_edges(normalize_peak(x, -2.0), 0.05)


def gloop(rng):
    """One fat bubble breaking on the lava: a low tone that rises as it bursts."""
    d = rng.uniform(0.15, 0.4)
    m = int(d * SR)
    t = np.arange(m) / SR
    f0 = rng.uniform(60, 110)
    f = f0 * (1 + rng.uniform(0.5, 1.0) * t / d)
    return tone(f, env_ad(m, 0.01, d * 0.35), ((1, 1.0), (2, 0.3), (3, 0.1))) * rng.uniform(0.4, 1.0)


def lava_surge(rng, seconds=36):
    """The lava sea rising: a heavy steady roar with a slow swell, lava churning and gulping, a faint sizzle. Loops."""
    n = seconds * SR
    roar = rumble_noise(n, rng, 80) * slow_env(n, rng, 6, 0.5)
    body = filt(periodic_noise(n, rng, pink), bandpass(140, 1.0)) * slow_env(n, rng, 4, 0.6) * 1.2
    churn = np.zeros(n)
    at = 0.0
    while at < seconds:
        add_wrapped(churn, gloop(rng), int(at * SR))
        at += rng.exponential(0.35) + 0.05
    churn = filt(churn, lowpass(600, 2)) * slow_env(n, rng, 3, 0.5)
    sizzle = filt(periodic_noise(n, rng, pink), bandpass(2500, 1.5)) * slow_env(n, rng, 5, 0.8) * 0.03
    x = roar + body + 0.5 * churn + sizzle
    x = x + convolve_wrapped(x, reverb_ir(2.0, rng, 0.7, lp=900)) * 0.4
    return normalize_rms(x, -18)


def main():
    rng = np.random.default_rng(20261101)
    for k in range(1, 4):
        save(f"lava_rumble{k}", lava_rumble(rng))
    save("lava_surge", lava_surge(rng), 3)


if __name__ == "__main__":
    main()
