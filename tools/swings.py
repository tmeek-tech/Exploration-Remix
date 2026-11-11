"""The timed swords' own textures (0.12.0), called by gen_data.py: the light on a sword's blade when its runes are ready
to cast (client.combat.RuneGlow) and the sweep a swing leaves through the air (client.combat.SwingRender).

Both are drawn added onto what is behind them (GlowRender's BLADE and SWEEP), so they are premultiplied: a pixel's
colour is already its light, black is nothing, and the alpha only follows along.

blade_glow.png is a strip of 72 x 72 frames, one under another: first the wake's 14 (shown a tick each: light runs up
the blade from the guard to the tip, which flares), then the loop's 32 (1.25 ticks each, 2 s round: the blade breathes,
a shimmer runs up it once and sparks of light wink along it). A frame covers the sword's 16 x 16 item pixels and one
more all round, 4 texels to a pixel. Every vanilla sword has the same outline, which the light follows: the blade runs
on the diagonal from just above the guard (6, 10) to the tip in the top right corner (16, 0). The renderer tints the
whole strip pale blue-white and dims it; here the light is white in the blade's heart and a clear light blue at its
edges (no cyan, nothing warm).

Run through gen_data.py; python3 tools/swings.py --preview <dir> [sword.png ...] also writes enlarged previews over
swords (vanilla's item textures, say) for review.
"""
import math, os, sys
import numpy as np
from PIL import Image

FRAME = 72
WAKE, LOOP = 14, 32
SCALE = 4  # texels to an item pixel

def _grid():
    """Each texel's middle in item pixels (x right, y down), the frame being the item's 16 and one more all round."""
    i = (np.arange(FRAME) + 0.5) / SCALE - 1.0
    return np.meshgrid(i, i)


X, Y = _grid()

# The blade's middle line, from just above the guard to just short of the tip, and how far its outline stands off that
# line (vanilla's blades: 1.75 px by the guard, narrowing to the tip).
P0, P1 = np.array([6.6, 9.4]), np.array([15.3, 0.7])
_D = P1 - P0
ALONG = ((X - P0[0]) * _D[0] + (Y - P0[1]) * _D[1]) / float(_D @ _D)   # 0 by the guard, 1 near the tip
_AC = np.clip(ALONG, 0.0, 1.0)
DIST = np.hypot(X - (P0[0] + _AC * _D[0]), Y - (P0[1] + _AC * _D[1]))  # px from the middle line
RADIUS = 1.75 - 0.45 * _AC


def smoothstep(a, b, v):
    t = np.clip((v - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


# none on the guard or the grip: the light starts just above the guard
START = smoothstep(-0.05, 0.05, ALONG)
# the blade's heart, its outline, and the light past the outline
INSIDE = (1.0 - smoothstep(RADIUS - 1.0, RADIUS - 0.35, DIST)) * START
RING = np.exp(-((DIST - (RADIUS - 0.25)) / 0.38) ** 2) * START
HALO = np.exp(-np.maximum(0.0, DIST - (RADIUS - 0.25)) / 0.75) * START

CORE = np.array([1.0, 1.0, 1.0])
EDGE = np.array([0.58, 0.78, 1.0])
DEEP = np.array([0.42, 0.62, 1.0])


def colour(inside, ring, halo, white=0.0):
    """Premultiplied RGBA from the three parts' light (0 up): the heart white, the outline light blue, the halo a
    deeper blue; white (0 to 1, per texel) turns all of it white (a shimmer, a spark)."""
    rgb = inside[..., None] * CORE + ring[..., None] * EDGE + halo[..., None] * DEEP
    w = np.clip(white, 0, 1)[..., None] if np.ndim(white) else white
    total = (inside + ring + halo)[..., None]
    rgb = rgb * (1 - w) + total * CORE * w
    rgb = np.clip(rgb, 0, 1)
    return np.concatenate([rgb, np.clip(rgb.max(axis=-1, keepdims=True), 0, 1)], axis=-1)


def star(cx, cy, arm, width, core):
    """A four-point star at (cx, cy) item px, its points along x and y."""
    dx, dy = X - cx, Y - cy
    r = np.hypot(dx, dy)
    heart = np.exp(-(r / core) ** 2)
    px = np.exp(-(dy / width) ** 2) * np.clip(1.0 - np.abs(dx) / arm, 0, 1) ** 1.6
    py = np.exp(-(dx / width) ** 2) * np.clip(1.0 - np.abs(dy) / arm, 0, 1) ** 1.6
    halo = 0.3 * np.exp(-(r / (arm * 0.45)) ** 2)
    return np.clip(heart + px + py + halo, 0, 1.6)


def breath(f):
    """The loop's steady light at its frame f: breathing, 2 s round."""
    return 0.88 + 0.12 * math.cos(2 * math.pi * f / LOOP)


def steady(k):
    """The ready blade's light at strength k."""
    return 0.32 * k * INSIDE, 0.62 * k * RING, 0.30 * k * HALO


def ease(t): return t * t * (3 - 2 * t)


TIP = (14.7, 1.3)


def band_at(centre, width):
    """A band of light across the blade at ALONG = centre."""
    return np.exp(-((ALONG - centre) / width) ** 2)


def wake_frame(f):
    """Light runs up from the guard (frames 0 to 9), then the tip flares and settles to the loop's first frame."""
    if f <= 9:
        front = -0.06 + 1.1 * ease(f / 9.0)
        lit = 1.15 * (1.0 - smoothstep(front - 0.03, front + 0.05, ALONG))
        band = band_at(front, 0.08)
        i, r, h = steady(lit + 1.4 * band)
        white = 0.8 * band
    else:
        i, r, h = steady(breath(0) * (1.2 - 0.2 * (f - 9) / 4.0))
        white = 0.0
    k = {9: 0.6, 10: 1.25, 11: 0.95, 12: 0.5, 13: 0.15}.get(f, 0.0)
    if k:
        # kept inside the frame: past its edge the next frame's light would bleed in
        size = 1.5 + 0.55 * min(1.0, (f - 8) / 2.0)
        flare = k * star(TIP[0], TIP[1], size, 0.24, 0.7)
        i = i + flare
        white = np.maximum(white, np.clip(flare, 0, 1))
    return colour(i, r, h, white)


# where the loop's sparks wink: (first frame, up the blade, off its middle line in px, which side)
SPARKS = [(2, 0.30, 1.35, 1), (10, 0.80, 1.1, -1), (20, 0.52, 1.45, -1), (26, 0.92, 0.9, 1)]
SPARK_LIGHT = [0.5, 1.0, 0.55, 0.2]


def at_along(a, off, side):
    """The item px point a fraction a up the blade, off px to one side of its middle line."""
    p = P0 + a * _D
    n = np.array([1.0, 1.0]) / math.sqrt(2) * side
    return p[0] + off * n[0], p[1] + off * n[1]


def loop_frame(f):
    i, r, h = steady(breath(f))
    white = np.zeros_like(X)
    # the shimmer: one bright band up the blade, frames 6 to 18, glinting at the tip as it leaves
    if 6 <= f <= 18:
        s = -0.12 + 1.27 * ease((f - 6) / 12.0)
        band = band_at(s, 0.09)
        bi, br, bh = steady(1.3 * band)
        i, r, h = i + bi, r + br, h + bh
        white = np.maximum(white, 0.7 * band)
    glint = {18: 0.35, 19: 0.55, 20: 0.25}.get(f, 0.0)
    if glint:
        g = glint * star(TIP[0], TIP[1], 1.7, 0.18, 0.45)
        i = i + g
        white = np.maximum(white, np.clip(g, 0, 1))
    for start, a, off, side in SPARKS:
        age = (f - start) % LOOP
        if age < len(SPARK_LIGHT):
            cx, cy = at_along(a, off, side)
            g = SPARK_LIGHT[age] * star(cx, cy, 1.3, 0.16, 0.33)
            i = i + g
            white = np.maximum(white, np.clip(g, 0, 1))
    return colour(i, r, h, white)


# each frame's outermost texels are dark, so the smoothing never pulls light across from the frame above or below
_BORDER = np.ones((FRAME, FRAME))
_BORDER[[0, -1], :] = 0.0
_BORDER[:, [0, -1]] = 0.0
_BORDER[[1, -2], 1:-1] *= 0.5
_BORDER[1:-1, [1, -2]] *= 0.5


def blade_glow():
    frames = [wake_frame(f) for f in range(WAKE)] + [loop_frame(f) for f in range(LOOP)]
    strip = np.concatenate([fr * _BORDER[..., None] for fr in frames], axis=0)
    px = np.round(np.clip(strip, 0, 1) * 255).astype(np.uint8)
    return Image.fromarray(px, "RGBA")


# ------------------------------------------------------------------------------------------------ the sweep

SWEEP_W, SWEEP_H = 64, 32


def swing_sweep():
    """u: along the sweep, from the blade (0) back to where it was (1); v: across, from the hilt's end (0) to the
    tip's (1). Brightest just inside the tip's path and fading behind the blade, with a few faint streaks along it."""
    u = (np.arange(SWEEP_W) + 0.5) / SWEEP_W
    v = (np.arange(SWEEP_H) + 0.5) / SWEEP_H
    U, V = np.meshgrid(u, v)
    age = (1.0 - U) ** 1.7 * smoothstep(0.0, 0.06, U)
    across = smoothstep(0.08, 0.85, V) ** 1.5 * (1.0 - smoothstep(0.9, 1.0, V))
    rng = np.random.default_rng(1211)
    streaks = np.zeros_like(V)
    for c in rng.uniform(0.35, 0.92, 5):
        streaks += 0.35 * np.exp(-((V - c) / 0.012) ** 2) * (1.0 - U) ** 0.8
    light = np.clip(0.55 * age * across + streaks * age * smoothstep(0.1, 0.5, V), 0, 1)
    white = smoothstep(0.55, 0.95, V) * (1.0 - U)
    w = white[..., None]
    rgb = (np.array([0.70, 0.84, 1.0]) * (1 - w) + w) * light[..., None]
    out = np.concatenate([rgb, light[..., None]], axis=-1)
    return Image.fromarray(np.round(np.clip(out, 0, 1) * 255).astype(np.uint8), "RGBA")


PAINTERS = {"misc/blade_glow": blade_glow, "misc/swing_sweep": swing_sweep}


def write_all(assets_dir):
    for name, painter in PAINTERS.items():
        path = os.path.join(assets_dir, "textures", name + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        painter().save(path)


# ------------------------------------------------------------------------------------------------ previews

def preview(out_dir, swords=()):
    """The blade's light added onto swords (vanilla-shaped item textures; a dark ground without), enlarged: every frame
    on the first, and a few frames on each; and the first as an animated GIF at the game's pace."""
    os.makedirs(out_dir, exist_ok=True)
    strip = np.asarray(blade_glow()).astype(float) / 255.0
    tint_rgb = np.array([0xD6, 0xEC, 0xFF]) / 255.0
    ground = np.array([0.06, 0.07, 0.09])

    def frames_on(png, zoom):
        sw = np.asarray(Image.open(png).convert("RGBA")).astype(float) / 255.0 if png else np.zeros((16, 16, 4))
        big = np.zeros((FRAME, FRAME, 4))
        big[SCALE:SCALE + 64, SCALE:SCALE + 64] = np.kron(sw, np.ones((SCALE, SCALE, 1)))
        base = big[..., :3] * big[..., 3:4] + ground * (1 - big[..., 3:4])
        out = []
        for k in range(WAKE + LOOP):
            f = strip[k * FRAME:(k + 1) * FRAME]
            img = np.clip(base + f[..., :3] * tint_rgb, 0, 1)
            out.append(Image.fromarray((img * 255).astype(np.uint8), "RGB").resize((FRAME * zoom, FRAME * zoom), Image.NEAREST))
        return out

    swords = list(swords) or [None]
    frames = frames_on(swords[0], 4)
    cols = 8
    rows = (len(frames) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * FRAME * 4, rows * FRAME * 4), (20, 20, 24))
    for i, fr in enumerate(frames):
        sheet.paste(fr, ((i % cols) * FRAME * 4, (i // cols) * FRAME * 4))
    sheet.save(os.path.join(out_dir, "blade_glow_frames.png"))
    pick = [0, 3, 6, 9, 10, 11, 13] + [WAKE + f for f in (0, 3, 8, 12, 16, 19, 27)]
    z = 3
    grid = Image.new("RGB", (len(pick) * FRAME * z, len(swords) * FRAME * z), (20, 20, 24))
    for r, png in enumerate(swords):
        fs = frames_on(png, z)
        for c, k in enumerate(pick):
            grid.paste(fs[k], (c * FRAME * z, r * FRAME * z))
    grid.save(os.path.join(out_dir, "blade_glow_swords.png"))
    durations = [50] * WAKE + [62] * LOOP
    loop = frames + frames[WAKE:] * 2
    frames[0].save(os.path.join(out_dir, "blade_glow.gif"), save_all=True, append_images=loop[1:],
                   duration=durations + [62] * (len(loop) - len(durations)), loop=0)
    swing_sweep().resize((SWEEP_W * 6, SWEEP_H * 6), Image.NEAREST).save(os.path.join(out_dir, "swing_sweep.png"))


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--preview":
        preview(sys.argv[2], sys.argv[3:])
    else:
        print("run gen_data.py, or: swings.py --preview <dir> [sword.png ...]")
