#!/usr/bin/env python3
"""Makes Exploration Remix's ambient sounds from scratch: wind, birds, drips with echoes, bubbling hot springs, the
distant roar of falling water and the basalt rumble. Every loop is built to repeat seamlessly (its noise and its
events wrap around the loop's end). Deterministic: the same script gives the same sounds.

Needs numpy and ffmpeg with libvorbis. Writes src/main/resources/assets/explorationremix/sounds/ambient/*.ogg.
Run from anywhere: python3 tools/synth_sounds.py
"""
import os, subprocess, tempfile, wave
import numpy as np

SR = 44100
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src/main/resources/assets/explorationremix/sounds/ambient")


# ----------------------------------------------------------------------------------------------- building blocks

def periodic_noise(n, rng, shape):
    """Noise of length n that loops seamlessly, with magnitude spectrum shape(freqs)."""
    f = np.fft.rfftfreq(n, 1 / SR)
    mag = shape(np.maximum(f, 1e-3))
    mag[0] = 0
    ph = rng.uniform(0, 2 * np.pi, len(f))
    x = np.fft.irfft(mag * np.exp(1j * ph), n)
    return x / (np.std(x) + 1e-12)


def filt(x, shape):
    """Circular filtering by a magnitude response (keeps loops seamless)."""
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    return np.fft.irfft(np.fft.rfft(x) * shape(np.maximum(f, 1e-3)), n)


def lowpass(fc, order=2): return lambda f: 1 / np.sqrt(1 + (f / fc) ** (2 * order))
def highpass(fc, order=2): return lambda f: 1 / np.sqrt(1 + (fc / f) ** (2 * order))
def bandpass(fc, q): return lambda f: 1 / np.sqrt(1 + (q * (f / fc - fc / f)) ** 2)
def pink(f): return 1 / np.sqrt(f)
def brown(f): return 1 / f


def slow_env(n, rng, period_s, depth, floor=0.0):
    """A smooth periodic envelope (loops seamlessly), wandering on a time scale of period_s."""
    x = periodic_noise(n, rng, lowpass(1.0 / period_s, 2))
    x = (x - x.min()) / (x.max() - x.min() + 1e-12)
    return floor + (1 - floor) * (1 - depth + depth * x)


def add_wrapped(buf, sig, at):
    """Adds sig into buf starting at sample at, wrapping round the end (for loops)."""
    n = len(buf)
    idx = (at + np.arange(len(sig))) % n
    np.add.at(buf, idx, sig)


def add_clipped(buf, sig, at):
    end = min(len(buf), at + len(sig))
    if at < end: buf[at:end] += sig[:end - at]


def reverb_ir(seconds, rng, decay_s, lp=4000):
    n = int(seconds * SR)
    t = np.arange(n) / SR
    ir = rng.standard_normal(n) * np.exp(-t / decay_s)
    ir = filt(ir, lowpass(lp, 1))
    ir[0] = 0
    return ir / np.sqrt(np.sum(ir ** 2) + 1e-12)


def convolve(x, ir):
    n = len(x) + len(ir) - 1
    m = 1 << (n - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(x, m) * np.fft.rfft(ir, m), m)[:n]


def convolve_wrapped(x, ir):
    """Circular convolution: a reverb tail that wraps round a loop's end."""
    n = len(x)
    pad = np.zeros(n)
    pad[:min(n, len(ir))] = ir[:n]
    return np.fft.irfft(np.fft.rfft(x) * np.fft.rfft(pad), n)


def tone(freq_curve, amp_curve, harmonics=((1, 1.0),)):
    """A tone following per-sample frequency and amplitude curves."""
    phase = 2 * np.pi * np.cumsum(freq_curve) / SR
    out = np.zeros_like(phase)
    for k, a in harmonics:
        out += a * np.sin(k * phase)
    return out * amp_curve


def env_ad(n, attack_s, decay_s):
    t = np.arange(n) / SR
    a = np.minimum(1, t / max(attack_s, 1e-4))
    return a * np.exp(-np.maximum(0, t - attack_s) / decay_s)


def fade_edges(x, s=0.01):
    k = int(s * SR)
    w = np.ones(len(x))
    w[:k] = np.linspace(0, 1, k)
    w[-k:] = np.linspace(1, 0, k)
    return x * w


def normalize_peak(x, db=-3.0):
    return x / (np.max(np.abs(x)) + 1e-12) * 10 ** (db / 20)


def normalize_rms(x, db):
    y = x / (np.sqrt(np.mean(x ** 2)) + 1e-12) * 10 ** (db / 20)
    peak = np.max(np.abs(y))
    return y / peak * 0.97 if peak > 0.97 else y


def save(name, x, quality=4):
    os.makedirs(OUT, exist_ok=True)
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, "a.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        out = os.path.join(OUT, name + ".ogg")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-c:a", "libvorbis", "-q:a", str(quality),
                        "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", out], check=True)
    print(f"{name}.ogg  {len(x) / SR:.1f} s")


# ----------------------------------------------------------------------------------------------- the sounds

def drip(rng, f0=None, decay=0.035):
    """One water drip: a short plink whose pitch rises as the bubble forms, with a tiny click on impact."""
    f0 = f0 or rng.uniform(900, 1700)
    n = int(0.25 * SR)
    t = np.arange(n) / SR
    rise = f0 * (1 + rng.uniform(0.6, 1.1) * (1 - np.exp(-t / 0.012)))
    amp = env_ad(n, 0.0015, decay)
    x = tone(rise, amp, ((1, 1.0), (2, 0.12)))
    click = rng.standard_normal(int(0.004 * SR)) * np.exp(-np.arange(int(0.004 * SR)) / (0.0008 * SR)) * 0.4
    x[:len(click)] += filt(np.pad(click, (0, 0)), highpass(2000)) if len(click) > 8 else 0
    return x


def echo_drip(rng, pitch=1.0):
    """A drip in a big cave: the plink, two or three slap-back echoes off far walls, and a long dark tail."""
    d = drip(rng, rng.uniform(900, 1500) * pitch, decay=0.04)
    n = int(3.6 * SR)
    out = np.zeros(n)
    add_clipped(out, d, 0)
    t = 0
    for k in range(3):
        t += rng.uniform(0.22, 0.45)
        add_clipped(out, filt(np.pad(d, (0, 2048)), lowpass(2500 - 600 * k, 1))[:len(d)] * (0.42 * 0.6 ** k), int(t * SR))
    tail = convolve(out, reverb_ir(3.0, rng, 0.9, lp=2200))[:n] * 0.5
    return fade_edges(normalize_peak(out + tail, -3.0), 0.005)


def bird(rng, kind):
    """A small bird somewhere above: four kinds of short phrase."""
    parts = []
    if kind == 1:  # three thin rising "tsee" notes
        for _ in range(3):
            n = int(rng.uniform(0.07, 0.1) * SR)
            t = np.arange(n) / SR
            f = rng.uniform(5200, 6200) + 1400 * t / t[-1]
            parts.append((tone(f, np.clip(np.sin(np.pi * t / t[-1]), 0, None) ** 1.5, ((1, 1), (2, 0.08))), rng.uniform(0.09, 0.14)))
    elif kind == 2:  # a falling trill
        base = rng.uniform(4200, 4800)
        for i in range(12):
            n = int(0.045 * SR)
            t = np.arange(n) / SR
            f = base * (1 - 0.02 * i) - 1500 * t / t[-1]
            parts.append((tone(f, np.clip(np.sin(np.pi * t / t[-1]), 0, None) ** 2, ((1, 1), (2, 0.15))), 0.028))
    elif kind == 3:  # a two-note whistle, high then lower, with a little vibrato
        for f0, dur in ((rng.uniform(4100, 4400), 0.32), (rng.uniform(3300, 3600), 0.38)):
            n = int(dur * SR)
            t = np.arange(n) / SR
            f = f0 * (1 + 0.012 * np.sin(2 * np.pi * 22 * t)) * (1 - 0.03 * t / t[-1])
            a = np.minimum(1, t / 0.03) * np.minimum(1, (t[-1] - t) / 0.06)
            parts.append((tone(f, a, ((1, 1), (2, 0.05))), 0.12))
    else:  # a quick warble of falling and rising chirps
        for i in range(6):
            n = int(rng.uniform(0.05, 0.09) * SR)
            t = np.arange(n) / SR
            up = i % 2 == 0
            f0 = rng.uniform(3000, 3800)
            f = f0 + (1800 if up else -900) * t / t[-1]
            parts.append((tone(f, np.clip(np.sin(np.pi * t / t[-1]), 0, None), ((1, 1), (2, 0.2))), rng.uniform(0.03, 0.07)))
    total = sum(len(p) + int(g * SR) for p, g in parts) + int(1.2 * SR)
    out = np.zeros(total)
    at = int(0.02 * SR)
    for p, gap in parts:
        add_clipped(out, p, at)
        at += len(p) + int(gap * SR)
    # From above and some way off: a little air absorption and a soft room.
    out = filt(out, lowpass(7000, 1))
    out = out + convolve(out, reverb_ir(1.0, rng, 0.35, lp=5000))[:len(out)] * 0.35
    return fade_edges(normalize_peak(out, -4.0), 0.004)


def upper_wind(rng, seconds=48):
    """Wind blowing over the cave mouths and shafts far above: soft gusts that swell and whistle."""
    n = seconds * SR
    base = filt(periodic_noise(n, rng, pink), lowpass(380, 2)) * slow_env(n, rng, 7, 0.7)
    mid = filt(periodic_noise(n, rng, pink), bandpass(750, 1.6)) * slow_env(n, rng, 5, 0.9) ** 2
    hiss = filt(periodic_noise(n, rng, pink), bandpass(1700, 2.5)) * slow_env(n, rng, 4, 0.95) ** 3
    whistle = filt(periodic_noise(n, rng, pink), bandpass(1150, 12)) * slow_env(n, rng, 6, 1.0) ** 4
    x = 1.0 * base + 0.55 * mid + 0.22 * hiss + 0.18 * whistle
    return normalize_rms(x, -22)


def gardens_drips(rng, seconds=40):
    """A big warm cave: a low hum of air and, every second or few, a drip echoing off far walls."""
    n = seconds * SR
    room = filt(periodic_noise(n, rng, brown), lowpass(160, 2)) * slow_env(n, rng, 9, 0.5) * 0.05
    drips = np.zeros(n)
    t = 0.3
    while t < seconds:
        d = echo_drip(rng, rng.uniform(0.85, 1.25)) * rng.uniform(0.25, 0.8)
        add_wrapped(drips, d, int(t * SR))
        t += rng.exponential(1.6) + 0.4
    return normalize_rms(room + drips * 0.6, -26)


def bubble(rng):
    """One bubble rising off a hot pool's bed: a ringing pop whose pitch rises as it goes."""
    r = rng.uniform(0.6, 3.5)  # radius in mm
    f0 = 3260 / r
    d = 0.006 + 0.004 * r
    n = int(d * 6 * SR)
    t = np.arange(n) / SR
    f = f0 * (1 + rng.uniform(0.1, 0.4) * t / (d * 6))
    return tone(f, env_ad(n, 0.0005, d)) * (0.4 + 0.3 * r / 3.5)


def springs_bubbling(rng, seconds=32):
    """Hot pools: bubbles in bursts, a low simmer, and the hiss of steam coming and going."""
    n = seconds * SR
    out = np.zeros(n)
    simmer_env = slow_env(n, rng, 3, 0.8)
    t = 0.0
    while t < seconds:
        burst = rng.random() < 0.15
        for _ in range(rng.integers(6, 18) if burst else 1):
            at = int((t + (rng.uniform(0, 0.35) if burst else 0)) * SR) % n
            add_wrapped(out, bubble(rng) * simmer_env[at] * rng.uniform(0.3, 1.0), at)
        t += rng.exponential(0.06)
    simmer = filt(periodic_noise(n, rng, brown), lowpass(220, 2)) * simmer_env * 0.25
    hiss = filt(periodic_noise(n, rng, lambda f: np.ones_like(f)), bandpass(5200, 1.2)) * slow_env(n, rng, 6, 1.0) ** 3 * 0.12
    x = out * 0.5 + simmer + hiss
    x = x + convolve_wrapped(x, reverb_ir(1.6, rng, 0.5, lp=3500)) * 0.25
    return normalize_rms(x, -24)


def blue_roar(rng, seconds=48):
    """A waterfall far below in the dark: a deep, steady roar that swells a little now and then."""
    n = seconds * SR
    roar = filt(periodic_noise(n, rng, pink), lowpass(520, 2)) * slow_env(n, rng, 10, 0.35)
    body = filt(periodic_noise(n, rng, brown), lowpass(140, 2)) * slow_env(n, rng, 13, 0.3) * 0.6
    spray = filt(periodic_noise(n, rng, pink), bandpass(1400, 1.0)) * slow_env(n, rng, 8, 0.6) * 0.08
    x = roar + body + spray
    x = x + convolve_wrapped(x, reverb_ir(2.5, rng, 0.9, lp=1500)) * 0.4
    return normalize_rms(x, -22)


def basalt_rumble(rng, seconds=48):
    """Deep rock under strain: a low rumble that rolls and swells, with the odd distant crack."""
    n = seconds * SR
    low = filt(periodic_noise(n, rng, brown), lowpass(110, 3)) * slow_env(n, rng, 6, 0.75)
    sub = filt(periodic_noise(n, rng, brown), bandpass(45, 2.0)) * slow_env(n, rng, 11, 0.6) * 0.8
    cracks = np.zeros(n)
    t = rng.uniform(1, 5)
    while t < seconds:
        m = int(rng.uniform(0.05, 0.3) * SR)
        c = rng.standard_normal(m) * np.exp(-np.arange(m) / (0.03 * SR))
        add_wrapped(cracks, c * rng.uniform(0.1, 0.35), int(t * SR))
        t += rng.uniform(4, 11)
    cracks = filt(cracks, lowpass(900, 2))
    x = low + sub + cracks
    x = x + convolve_wrapped(x, reverb_ir(2.0, rng, 0.8, lp=600)) * 0.5
    return normalize_rms(x, -20)


def rumble(rng, seconds=4.5):
    """A heavier roll of rumbling, as if rock shifted somewhere far off."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    x = filt(rng.standard_normal(n), lowpass(90, 3))
    envl = np.minimum(1, t / 0.6) * np.exp(-np.maximum(0, t - 0.6) / 1.2)
    x = x * envl * (1 + 0.4 * np.sin(2 * np.pi * 3.1 * t + rng.uniform(0, 6)))
    return fade_edges(normalize_peak(x, -2.0), 0.05)


def main():
    rng = np.random.default_rng(20261028)
    save("upper_wind", upper_wind(rng), 3)
    for k in range(1, 5):
        save(f"bird{k}", bird(rng, k))
    for k in range(1, 5):
        save(f"drip{k}", fade_edges(normalize_peak(np.concatenate([drip(rng), np.zeros(int(0.1 * SR))]), -3.0), 0.003))
    for k in range(1, 5):
        save(f"echo_drip{k}", echo_drip(rng))
    save("gardens_drips", gardens_drips(rng), 3)
    save("springs_bubbling", springs_bubbling(rng), 3)
    save("blue_roar", blue_roar(rng), 3)
    save("basalt_rumble", basalt_rumble(rng), 3)
    for k in range(1, 3):
        save(f"rumble{k}", rumble(rng))


if __name__ == "__main__":
    main()
