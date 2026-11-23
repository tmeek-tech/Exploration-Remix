#!/usr/bin/env python3
"""Makes each cave layer's own ambience (0.20.9) from scratch, with synth_sounds.py's building blocks.

Taran (23 Nov 2026): "Change bird noises in the Upper Caves to calm, atmospheric cricket noises. The Deep Gardens
should be quiet and the Basalt Galleries should have a low rumble."

- Upper Caves: a soft far chorus of tree crickets echoing in a big cave (crickets_loop; Taran chose the chorus alone
  over one with slow field crickets nearer in the loop), and single nearer crickets (cricket1-4, mono, so the game can
  set them on the rock round the player, where they go quiet as you come close).
- Frozen caves (the Frozen Root Vaults and the Frost Fern Grottos): no crickets in the cold, a thin draft (cold_draft),
  the tick of ice (ice_tick1-3) and now and then the long falling song of ice under strain (ice_song1-3).
- Deep Gardens: a hush, a faint draft through the chimneys (gardens_hush), a far-off vent breathing out (vent_sigh1-3),
  and, close to a live vent's crown only, its hiss and simmer (vent_simmer1-3, mono, placed at the vent).
- Basalt Galleries: a low steady rumble of the deep rock (deep_rumble). Smooth on purpose: no cracks, no grinding, no
  rolls, so the lava's warning rumbles (synth_lava_sounds.py), which roll and grind, still stand out from it.

The beds and the far sounds are stereo (the game plays a stereo file round the listener as it is; only mono files are
placed in the world), so a chorus is wide and its crickets each have a side. Every loop repeats seamlessly: noise,
events and reverb tails wrap round its end, and each singer's rhythm divides the loop. Every sound is high-passed
before it is levelled (synth_sounds.py's loops kept most of their level below 20 Hz, where nothing can hear it: the old
basalt_rumble was 99.9% that, so the Galleries were silent). Deterministic, with its own seed, so the other sets are
left as they are.

Needs numpy and ffmpeg with libvorbis. Writes src/main/resources/assets/explorationremix/sounds/ambient/*.ogg.
Run from anywhere: python3 tools/synth_layer_sounds.py [--previews DIR] (DIR: MP3 previews to listen to as well)
"""
import os, subprocess, sys, tempfile, wave
import numpy as np

from synth_sounds import (SR, OUT, add_clipped, bandpass, brown, highpass, lowpass, pink, periodic_noise, slow_env, tone)

LOOP_S = 60


# ----------------------------------------------------------------------------------------------- building blocks

def filt(x, shape):
    """Circular filtering of mono or stereo x by a magnitude response (keeps loops seamless)."""
    n = x.shape[-1]
    f = np.fft.rfftfreq(n, 1 / SR)
    return np.fft.irfft(np.fft.rfft(x, axis=-1) * shape(np.maximum(f, 1e-3)), n, axis=-1)


def hp(x, fc=30, order=4):
    """High-pass (circular, so loops stay seamless): takes away the drift no speaker plays but that eats the level."""
    return filt(x, highpass(fc, order))


def aweight(f):
    f2 = f * f
    ra = (12194 ** 2 * f2 ** 2) / ((f2 + 20.6 ** 2) * np.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2)) * (f2 + 12194 ** 2))
    return ra * 10 ** (2.0 / 20)


def loudness_db(x):
    """A-weighted level in dB (per channel, for stereo), a rough stand-in for how loud it sounds."""
    xa = filt(x, aweight)
    return 20 * np.log10(np.sqrt(np.mean(xa ** 2)) + 1e-12)


def level_to(x, db, peak_db=-1.0):
    """Scales x (mono or stereo) to an A-weighted level of db, never past peak_db."""
    y = x * 10 ** ((db - loudness_db(x)) / 20)
    pk = np.max(np.abs(y))
    lim = 10 ** (peak_db / 20)
    return y * (lim / pk) if pk > lim else y


def fade(x, a=0.01, b=None):
    """Fades a one-shot in over a seconds and out over b (mono or stereo)."""
    b = a if b is None else b
    n = x.shape[-1]
    w = np.ones(n)
    ka, kb = max(1, int(a * SR)), max(1, int(b * SR))
    w[:ka] = np.sin(np.linspace(0, np.pi / 2, ka)) ** 2
    w[-kb:] = np.cos(np.linspace(0, np.pi / 2, kb)) ** 2
    return x * w


def cave_ir(rng, seconds, rt60, predelay=0.02, early=6, early_gain=0.5, dark=1.0, stereo=True):
    """A cave's response: a few early reflections off near rock, then a diffuse tail whose highs die first (rock and
    air soak them up), its two channels uncorrelated. Each channel carries unit energy. rt60: seconds to fall 60 dB."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    f = np.fft.rfftfreq(n, 1 / SR)
    out = []
    for _ in range(2 if stereo else 1):
        spec = np.fft.rfft(rng.standard_normal(n))
        tail = np.zeros(n)
        for lo, hi, k in ((0, 500, 1.0), (500, 2000, 0.8), (2000, 5000, 0.55 / dark), (5000, SR, 0.3 / dark)):
            band = np.fft.irfft(spec * ((f >= lo) & (f < hi)), n)
            tail += band * np.exp(-t / (rt60 * min(k, 1.0) / 6.91))
        tail *= 1 - np.exp(-t / 0.03)  # the tail builds as the reflections multiply
        ir = np.zeros(n)
        d = int(predelay * SR)
        ir[d:] = tail[:n - d]
        ir /= np.sqrt(np.sum(ir ** 2)) + 1e-12
        taps = np.zeros(n)
        for _ in range(early):
            at = int(rng.uniform(0.006, 0.085) * SR)
            taps[at] += rng.choice((-1, 1)) * rng.uniform(0.4, 1.0) * np.exp(-at / SR / 0.06)
        taps = filt(taps, lowpass(3500, 1))
        taps /= np.sqrt(np.sum(taps ** 2)) + 1e-12
        out.append(ir + early_gain * taps)
    return np.array(out)


def conv(x, ir, wrap):
    """Convolves mono x with each channel of ir: a stereo result, circular for loops (same length as x), else with its
    tail (x's length plus the response's)."""
    if wrap:
        n = len(x)
        X = np.fft.rfft(x)
        res = []
        for ch in ir:
            pad = np.zeros(n)
            pad[:min(n, len(ch))] = ch[:n]
            res.append(np.fft.irfft(X * np.fft.rfft(pad), n))
        return np.array(res)
    n = len(x) + ir.shape[1] - 1
    m = 1 << (n - 1).bit_length()
    X = np.fft.rfft(x, m)
    return np.array([np.fft.irfft(X * np.fft.rfft(ch, m), m)[:n] for ch in ir])


def place(x, pan, wrap=False):
    """A mono sound from one side: pan -1 (left) to 1 (right), at equal power, the far ear hearing it a little later
    and a little darker, as a head does."""
    if abs(pan) < 1e-3:
        return np.array([x, x]) * np.sqrt(0.5)
    th = (pan + 1) * np.pi / 4
    gl, gr = np.cos(th), np.sin(th)
    delay = int(abs(pan) * 0.00045 * SR)
    shadow = filt(x, lowpass(2500 + 6000 * (1 - abs(pan)), 1))
    away = np.roll(shadow, delay) if wrap else np.concatenate([np.zeros(delay), shadow[:len(x) - delay]])
    return np.array([away * gl, x * gr]) if pan > 0 else np.array([x * gl, away * gr])


def save_ogg(name, x, quality=4, folder=OUT):
    """Writes mono (1-D) or stereo (2 x n) x as name.ogg."""
    os.makedirs(folder, exist_ok=True)
    st = x.ndim == 2
    pcm = (np.clip(x.T if st else x, -1, 1) * 32767).astype(np.int16)
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, "a.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(2 if st else 1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        out = os.path.join(folder, name + ".ogg")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-c:a", "libvorbis", "-q:a", str(quality),
                        "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", out], check=True)
    n = x.shape[-1]
    print(f"{name}.ogg  {n / SR:.1f} s  {'stereo' if st else 'mono'}  {loudness_db(x):.1f} dB(A)")


def save_mp3(path, x):
    """A preview to listen to outside the game."""
    st = x.ndim == 2
    pcm = (np.clip(x.T if st else x, -1, 1) * 32767).astype(np.int16)
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, "a.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(2 if st else 1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-c:a", "libmp3lame", "-q:a", "3", path], check=True)


# ----------------------------------------------------------------------------------------------- crickets

def syllable(rng, f0, dur, glide=0.035, h2=0.09, h3=0.025, rough=0.0):
    """One stroke of a cricket's wing: a nearly pure tone whose pitch sags a little as the wing slows, swelling and
    fading over a few hundredths of a second (the teeth of the file strike once per cycle, so the tone is the rate).
    rough: how unevenly the teeth strike, a faint burr round the tone."""
    n = max(8, int(dur * SR))
    u = np.arange(n) / n
    f = f0 * (1 + glide * 0.5 - glide * u) * (1 + 0.002 * rng.standard_normal())
    env = np.sin(np.pi * u ** 0.75) ** 1.4
    if rough:
        burr = filt(rng.standard_normal(n + 512), lowpass(700, 2))[256:256 + n]
        env = env * (1 + rough * burr / (np.std(burr) + 1e-12))
    return tone(f, env, ((1, 1.0), (2, h2), (3, h3)))


def field_chirp(rng, f0, syllables=3, dur=0.017, period=0.042):
    """A field cricket's chirp: three or four wing strokes close together, the middle ones loudest."""
    shape = {3: (0.8, 1.0, 0.85), 4: (0.75, 1.0, 0.95, 0.7)}[syllables]
    out = np.zeros(int((syllables * period + 0.03) * SR))
    for i, a in enumerate(shape):
        s = syllable(rng, f0 * (1 + 0.006 * rng.standard_normal()), dur * (1 + 0.06 * rng.standard_normal()), rough=0.16)
        add_clipped(out, s * a * (1 + 0.05 * rng.standard_normal()), max(0, int((i * period + 0.002 * rng.standard_normal()) * SR) + 64))
    return out


def tree_chirp(rng, f0, pulses=7, rate=58.0):
    """A tree cricket's chirp: a soft run of quick pulses on a low, mellow tone, the classic sound of a summer night."""
    period = 1 / rate
    out = np.zeros(int((pulses * period + 0.03) * SR))
    for i in range(pulses):
        a = np.sin(np.pi * (i + 0.6) / (pulses + 0.2)) ** 0.6
        s = syllable(rng, f0 * (1 + 0.004 * rng.standard_normal()), period * 0.62, glide=0.02, h2=0.04, h3=0.01)
        add_clipped(out, s * a, int(i * period * SR) + 64)
    return out


def sing(rng, buf, call, period, start, length, gain, jitter=0.012, swell=0.25):
    """A cricket singing call after call every period seconds into buf (wrapping round a loop's end) for length
    seconds from start, its calls a little uneven in time and strength, faded in and out at the ends of the bout."""
    n = len(buf)
    k = 0
    t = 0.0
    while t < length:
        u = t / max(length, 1e-6)
        edge = min(1.0, t / 2.0, (length - t) / 2.0) if length > 4 else 1.0
        g = gain * edge * (1 - swell + swell * np.sin(np.pi * u)) * (1 + 0.08 * rng.standard_normal())
        at = int((start + t + jitter * rng.standard_normal()) * SR) % n
        c = call()
        idx = (at + np.arange(len(c))) % n
        np.add.at(buf, idx, c * g)
        k += 1
        t = k * period


def far(x, cutoff=3800):
    """Heard from far off down the cave: the highs worn off."""
    return filt(x, lowpass(cutoff, 1))


def cave_air(rng, n, db):
    """The faint movement of air in a big cave, so the cave never sounds dead between calls (high-passed: heard, not
    just measured). Stereo, its two sides breathing apart."""
    out = []
    for _ in range(2):
        a = filt(periodic_noise(n, rng, pink), lambda f: bandpass(220, 0.7)(f) * highpass(45, 3)(f)) * slow_env(n, rng, 11, 0.6)
        b = filt(periodic_noise(n, rng, pink), bandpass(900, 1.2)) * slow_env(n, rng, 7, 0.9) ** 2 * 0.25
        out.append(a + b)
    x = np.array(out)
    return x / (np.sqrt(np.mean(x ** 2)) + 1e-12) * 10 ** (db / 20)


def trill(rng, f0, length, rate):
    """A tree cricket's long trill: quick soft pulses for seconds on end, swelling in and fading out."""
    period = 1 / rate
    n = int((length + 0.05) * SR)
    out = np.zeros(n)
    k = 0
    while k * period < length:
        u = k * period / length
        a = min(1.0, u / 0.15, (1 - u) / 0.2) ** 1.5
        s = syllable(rng, f0 * (1 + 0.003 * rng.standard_normal()), period * 0.6, glide=0.015, h2=0.03, h3=0.008)
        add_clipped(out, s * a, int(k * period * SR))
        k += 1
    return out


def chorus_bed(rng, n, chirpers=10, trillers=4, f_lo=2500, f_hi=3100, period=0.75):
    """A far chorus of tree crickets: most chirp in step, as tree crickets do fall in with each other, a slow soft pulse
    like breathing; a few others trill on and off over it. Each is heard from its own side and far off, and groups come
    and go, so the chorus swells and thins."""
    seconds = n / SR
    period = seconds / round(seconds / period)
    bus = []
    for v in range(chirpers):
        f0 = rng.uniform(f_lo, f_hi)
        dry = np.zeros(n)
        t = rng.uniform(0, seconds)
        sung = 0.0
        phase = rng.uniform(-0.03, 0.03)  # a little out of step with the rest
        while sung < seconds * 0.75:
            length = rng.uniform(12, 30)
            start = round(t / period) * period + phase
            sing(rng, dry, lambda: tree_chirp(rng, f0, int(rng.integers(6, 9))), period, start, length,
                 rng.uniform(0.35, 1.0), jitter=0.006, swell=0.35)
            t += length + rng.uniform(3, 10)
            sung += length
        bus.append((far(dry, rng.uniform(2600, 3600)), rng.uniform(-0.95, 0.95)))
    for v in range(trillers):
        f0 = rng.uniform(f_lo - 250, f_hi - 300)
        rate = rng.uniform(38, 50)
        dry = np.zeros(n)
        t = rng.uniform(0, seconds)
        for _ in range(int(rng.integers(3, 6))):
            length = rng.uniform(3, 9)
            tr = trill(rng, f0, length, rate) * rng.uniform(0.25, 0.6)
            idx = (int(t * SR) + np.arange(len(tr))) % n
            np.add.at(dry, idx, tr)
            t += length + rng.uniform(5, 14)
        bus.append((far(dry, rng.uniform(2400, 3200)), rng.uniform(-0.95, 0.95)))
    return bus


def field_singers(rng, n, specs):
    """A few field crickets at middle distances, each with its own slow, steady chirp (slow, as on a cool night) and its
    own bouts of song and rest. specs: (pitch, chirp period, pan, gain, distance 0..1)."""
    seconds = n / SR
    bus = []
    for f0, period, pan, gain, dist in specs:
        k = round(seconds / period)
        period = seconds / k
        dry = np.zeros(n)
        t = rng.uniform(0, seconds)
        sung = 0.0
        while sung < seconds * 0.6:
            length = rng.uniform(9, 24)
            start = round(t / period) * period
            syl = int(rng.choice((3, 3, 4)))
            sing(rng, dry, lambda: field_chirp(rng, f0, syl), period, start, length, gain)
            t += length + rng.uniform(4, 12)
            sung += length
        bus.append((far(dry, 9000 - 5500 * dist), pan, dist))
    return bus


def crickets_mix(rng, n, chorus_db, singers, air_db, rt60=2.8, wet_far=0.9, wet_mid=0.6):
    """Mixes a chorus, middle-distance singers and the cave's air into one stereo loop, in one cave's reverb."""
    ir = cave_ir(rng, 3.2, rt60, predelay=0.025)
    mix = np.zeros((2, n))
    if chorus_db is not None:
        chorus = np.zeros((2, n))
        for dry, pan in chorus_bed(rng, n):
            direct = place(dry, pan, wrap=True)
            chorus += 0.35 * direct + wet_far * conv(dry, ir, True) * np.array([[1.0], [1.0]])
        chorus = hp(chorus, 300, 2)
        mix += chorus / (np.sqrt(np.mean(chorus ** 2)) + 1e-12) * 10 ** (chorus_db / 20)
    if singers:
        mid = np.zeros((2, n))
        for dry, pan, dist in field_singers(rng, n, singers):
            mid += (1 - 0.6 * dist) * place(dry, pan, wrap=True) + (wet_mid + 0.3 * dist) * conv(dry, ir, True)
        mid = hp(mid, 300, 2)
        mix += mid / (np.sqrt(np.mean(mid ** 2)) + 1e-12) * 10 ** (-26.5 / 20)
    mix += cave_air(rng, n, air_db)
    return mix


def crickets_loop(rng, option="both"):
    """The Upper Caves' bed (60 s, seamless). 'chorus': the far chorus alone (the in-game one); 'both': the far chorus
    with three slow field crickets at middle distance; 'singers': only a few slow field crickets, echoing."""
    n = LOOP_S * SR
    specs = [(3550, 1.25, -0.6, 1.0, 0.4), (3880, 1.05, 0.7, 0.8, 0.55), (3330, 1.5, 0.15, 0.7, 0.8)]
    if option == "chorus":
        x = crickets_mix(rng, n, -26, [], -44)
    elif option == "singers":
        x = crickets_mix(rng, n, None, specs + [(4050, 0.95, -0.25, 0.6, 0.7)], -42, rt60=3.2)
    else:
        x = crickets_mix(rng, n, -28.5, specs, -44)
    return level_to(hp(x, 35), -33)


def near_cricket(rng, f0, period, chirps, syl):
    """One cricket close by, for the game to set on the rock near the player (mono): a short bout of slow chirps with
    the cave's answer after each, which is what is left of it once it has gone quiet."""
    n = int((chirps * period + 2.4) * SR)
    dry = np.zeros(n)
    for i in range(chirps):
        g = (0.9 + 0.1 * np.sin(np.pi * (i + 0.5) / chirps)) * (1 + 0.06 * rng.standard_normal())
        add_clipped(dry, field_chirp(rng, f0, syl) * g, int((0.05 + i * period + 0.01 * rng.standard_normal()) * SR))
    ir = cave_ir(rng, 2.0, 1.8, predelay=0.012, stereo=False, dark=1.4)
    wet = conv(dry, ir, False)[0][:n]
    x = hp(dry + 0.22 * wet, 300, 2)
    return fade(level_to(x, -18), 0.005, 0.6)


# ----------------------------------------------------------------------------------------------- frozen caves

def cold_draft(rng):
    """A thin, cold draft through the ice: airy and high, a faint hollow whistle coming and going. No crickets."""
    n = LOOP_S * SR
    out = []
    for _ in range(2):
        air = filt(periodic_noise(n, rng, pink), lambda f: bandpass(1400, 0.9)(f) * highpass(300, 2)(f)) * slow_env(n, rng, 8, 0.75) ** 1.5
        hiss = filt(periodic_noise(n, rng, pink), bandpass(4200, 1.3)) * slow_env(n, rng, 6, 0.9) ** 2 * 0.25
        low = filt(periodic_noise(n, rng, pink), lambda f: bandpass(260, 0.9)(f) * highpass(60, 3)(f)) * slow_env(n, rng, 13, 0.5) * 0.45
        out.append(air + hiss + low)
    x = np.array(out)
    whistle = filt(periodic_noise(n, rng, pink), bandpass(1650, 22)) * slow_env(n, rng, 9, 1.0) ** 5
    x += place(whistle / (np.std(whistle) + 1e-12) * 0.35 * np.std(x), rng.uniform(-0.6, 0.6), wrap=True)
    ir = cave_ir(rng, 2.5, 2.4, predelay=0.02)
    x = x + 0.3 * np.array([conv(x[0], ir[:1], True)[0], conv(x[1], ir[1:], True)[0]])
    return level_to(hp(x, 60), -40)


def ice_tick(rng):
    """Ice settling in the cold: a tiny crisp tick with a short glassy ring, and the cave's echo."""
    n = int(1.9 * SR)
    t = np.arange(n) / SR
    m = int(0.0025 * SR)
    click = np.zeros(n)
    click[:m] = rng.standard_normal(m) * np.exp(-np.arange(m) / (0.0006 * SR))
    click = filt(click, highpass(1800, 2))
    ring = np.zeros(n)
    base = rng.uniform(2200, 3400)
    for ratio, a, dec in ((1.0, 1.0, 0.05), (1.62, 0.6, 0.035), (2.37, 0.4, 0.025), (3.11, 0.25, 0.018)):
        ring += a * np.sin(2 * np.pi * base * ratio * t + rng.uniform(0, 6)) * np.exp(-t / dec)
    dry = click + 0.08 * ring
    ir = cave_ir(rng, 1.8, 1.9, predelay=0.015)
    x = place(dry, rng.uniform(-0.8, 0.8)) * 0.8 + 0.35 * conv(dry, ir, False)[:, :n]
    return fade(level_to(hp(x, 400, 2), -38, peak_db=-10.0), 0.001, 0.4)


def ice_song(rng):
    """Ice singing somewhere off in the grotto as it shifts: a falling whistle (its high notes arrive first, as waves
    in ice travel), a fainter second one, and a long cold echo. Far and soft."""
    n = int(4.8 * SR)
    t = np.arange(n) / SR
    dry = np.zeros(n)
    for k, (at, g) in enumerate(((0.05, 1.0), (rng.uniform(0.35, 0.6), rng.uniform(0.35, 0.55)))):
        d = rng.uniform(0.45, 0.8)
        m = int(d * SR)
        u = np.arange(m) / SR
        f_hi, f_lo = rng.uniform(3200, 4200), rng.uniform(180, 320)
        t0 = d / (np.sqrt(f_hi / f_lo) - 1)
        f = f_hi * (t0 / (u + t0)) ** 2
        env = np.minimum(1, u / 0.01) * np.exp(-u / (d * 0.45)) * (0.4 + 0.6 * (1 - f / f_hi))
        add_clipped(dry, tone(f, env, ((1, 1.0), (2, 0.06))) * g, int(at * SR))
    ir = cave_ir(rng, 3.5, 3.4, predelay=0.03)
    x = 0.45 * place(far(dry, 5000), rng.uniform(-0.9, 0.9)) + 0.9 * conv(dry, ir, False)[:, :n]
    return fade(level_to(hp(x, 120, 2), -35), 0.003, 0.8)


# ----------------------------------------------------------------------------------------------- Deep Gardens

def gardens_hush(rng):
    """The Deep Gardens' near-silence: a faint, slow draft through the chimneys, low and soft, its two sides apart."""
    n = LOOP_S * SR
    out = []
    for _ in range(2):
        a = filt(periodic_noise(n, rng, pink), lambda f: bandpass(170, 0.8)(f) * highpass(50, 3)(f)) * slow_env(n, rng, 17, 0.65)
        b = filt(periodic_noise(n, rng, pink), bandpass(650, 1.0)) * slow_env(n, rng, 12, 0.85) ** 2 * 0.3
        out.append(a + b)
    return level_to(hp(np.array(out), 40), -46)


def vent_sigh(rng):
    """A vent somewhere far off breathing out: a soft, slow, breathy swell through the chimneys and a long echo."""
    n = int(7.5 * SR)
    t = np.arange(n) / SR
    rise, hold, fall = rng.uniform(1.0, 1.6), rng.uniform(0.3, 0.8), rng.uniform(1.6, 2.4)
    env = np.where(t < rise, np.sin(np.pi / 2 * t / rise) ** 2,
                   np.where(t < rise + hold, 1.0, np.exp(-(t - rise - hold) / (fall / 2.5))))
    noise = rng.standard_normal(n)
    # the breath's colour moves as it swells: a resonance sliding up and back down through the low mids
    f_res = 300 + 280 * np.sin(np.pi * np.clip(t / (rise + hold + fall * 0.6), 0, 1)) ** 1.5
    seg = int(0.05 * SR)
    breath = np.zeros(n)
    for s in range(0, n, seg):
        e = min(n, s + seg * 2)
        piece = filt(noise[s:e] * np.hanning(e - s), bandpass(f_res[min(s + seg // 2, n - 1)], 2.2))
        breath[s:e] += piece
    breath *= env
    thoom = tone(np.full(n, rng.uniform(42, 55)), np.minimum(1, t / 0.4) * np.exp(-t / 1.1), ((1, 1.0), (2, 0.4))) * 0.5
    dry = far(breath, 1600) + hp(thoom, 30, 4)
    ir = cave_ir(rng, 4.0, 3.8, predelay=0.04, dark=1.6)
    x = 0.4 * place(dry, rng.uniform(-0.9, 0.9)) + 1.0 * conv(dry, ir, False)[:, :n]
    return fade(level_to(hp(x, 35), -36), 0.05, 1.2)


def vent_simmer(rng):
    """A live vent close by (mono, placed at its crown): a soft hiss of hot gas that comes and goes, a low simmer, and
    the faint tick of the crust as the heat works it."""
    n = int(4.0 * SR)
    t = np.arange(n) / SR
    breathe = 0.55 + 0.45 * np.sin(2 * np.pi * rng.uniform(0.25, 0.45) * t + rng.uniform(0, 6)) ** 2
    hiss = filt(rng.standard_normal(n), lambda f: bandpass(3800, 0.8)(f) * highpass(1200, 2)(f)) * breathe
    simmer = filt(rng.standard_normal(n), lambda f: bandpass(260, 1.3)(f) * highpass(80, 2)(f)) * (0.6 + 0.4 * breathe) * 0.5
    ticks = np.zeros(n)
    at = rng.uniform(0.1, 0.5)
    while at < 3.7:
        m = int(0.0015 * SR)
        c = rng.standard_normal(m) * np.exp(-np.arange(m) / (0.0004 * SR))
        add_clipped(ticks, c * rng.uniform(0.3, 1.0), int(at * SR))
        at += rng.exponential(0.35) + 0.05
    ticks = filt(ticks, bandpass(4500, 0.7))
    x = 0.55 * hiss + simmer + 0.6 * ticks
    ir = cave_ir(rng, 1.2, 1.0, predelay=0.008, stereo=False)
    x = x + 0.2 * conv(x, ir, False)[0][:n]
    return fade(level_to(hp(x, 70), -26), 0.35, 0.6)


# ----------------------------------------------------------------------------------------------- Basalt Galleries

def deep_rumble(rng):
    """The Galleries' low steady rumble: the deep rock and the lava sea under it, felt as much as heard. A sub layer and
    a body that swell slowly and apart, and the hall's own low resonances humming in them like a vast pipe. Smooth: no
    cracks, grinding or rolls, which stay the lava's warnings and the quakes'."""
    n = LOOP_S * SR
    chans = []
    shared_sub = filt(periodic_noise(n, rng, lambda f: brown(f) * highpass(26, 6)(f) * lowpass(70, 4)(f)), lambda f: 1.0)
    sub_env = slow_env(n, rng, 13, 0.45)
    modes = [(rng.uniform(38, 44), 7), (rng.uniform(55, 61), 8), (rng.uniform(73, 79), 9), (rng.uniform(96, 104), 10),
             (rng.uniform(128, 136), 11)]
    for c in range(2):
        body = periodic_noise(n, rng, lambda f: pink(f) * bandpass(105, 0.9)(f) * highpass(50, 3)(f) * lowpass(240, 4)(f))
        body *= slow_env(n, rng, 8, 0.55)
        hum = np.zeros(n)
        for fm, q in modes:
            h = periodic_noise(n, rng, lambda f, fm=fm, q=q: bandpass(fm, q)(f) * highpass(30, 4)(f))
            hum += h * slow_env(n, rng, rng.uniform(9, 16), 0.7) * (1.0 if fm < 90 else 0.6)
        x = 1.0 * shared_sub * sub_env + 0.9 * body + 0.5 * hum / (np.std(hum) + 1e-12)
        chans.append(x)
    x = np.array(chans)
    ir = cave_ir(rng, 3.0, 3.0, predelay=0.04, dark=2.0)
    x = x + 0.35 * np.array([conv(x[0], ir[:1], True)[0], conv(x[1], ir[1:], True)[0]])
    x = filt(x, lowpass(260, 3))
    return level_to(hp(x, 28, 6), -40, peak_db=-2.0)


# ----------------------------------------------------------------------------------------------- main

def main():
    previews = None
    if "--previews" in sys.argv:
        previews = sys.argv[sys.argv.index("--previews") + 1]
        os.makedirs(previews, exist_ok=True)
    rng = np.random.default_rng(20261123)
    # The chorus with slow chirpers is still made first, though Taran chose the chorus alone (23 Nov 2026), so that
    # every later sound comes out as it was heard on the listening page; it is a preview only now.
    both = crickets_loop(rng)
    crng = np.random.default_rng(7)
    loop = crickets_loop(crng, "chorus")  # the page's "far chorus only" clip, Taran's pick
    save_ogg("crickets_loop", loop, 3)
    for k, (f0, period, chirps, syl) in enumerate(((3600, 1.1, 5, 3), (3950, 0.95, 6, 3), (3380, 1.3, 4, 4), (3750, 1.0, 5, 4)), 1):
        save_ogg(f"cricket{k}", near_cricket(rng, f0, period, chirps, syl))
    save_ogg("cold_draft", cold_draft(rng), 3)
    for k in range(1, 4):
        save_ogg(f"ice_tick{k}", ice_tick(rng))
    for k in range(1, 4):
        save_ogg(f"ice_song{k}", ice_song(rng))
    save_ogg("gardens_hush", gardens_hush(rng), 3)
    for k in range(1, 4):
        save_ogg(f"vent_sigh{k}", vent_sigh(rng))
    for k in range(1, 4):
        save_ogg(f"vent_simmer{k}", vent_simmer(rng))
    save_ogg("deep_rumble", deep_rumble(rng), 3)
    if previews:
        save_mp3(os.path.join(previews, "crickets_both.mp3"), fade(both[:, :30 * SR], 1.5, 3))
        save_mp3(os.path.join(previews, "crickets_chorus.mp3"), fade(loop[:, :30 * SR], 1.5, 3))
        save_mp3(os.path.join(previews, "crickets_singers.mp3"), fade(crickets_loop(crng, "singers")[:, :30 * SR], 1.5, 3))


if __name__ == "__main__":
    main()
