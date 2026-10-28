"""Exploration Remix block textures: 16 by 16, vanilla style (a short ramp of shades, hard pixels, no blur).

Each painter is deterministic, so the textures only change when this file does.
"""
import os, random
from PIL import Image


def hexc(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def wrap_noise(rng, size=16, cells=4):
    """Tileable value noise in [0, 1)."""
    g = [[rng.random() for _ in range(cells)] for _ in range(cells)]
    out = [[0.0] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            fx, fy = x / size * cells, y / size * cells
            x0, y0 = int(fx), int(fy)
            tx, ty = fx - x0, fy - y0
            tx = tx * tx * (3 - 2 * tx)
            ty = ty * ty * (3 - 2 * ty)
            x1, y1 = (x0 + 1) % cells, (y0 + 1) % cells
            a = g[y0][x0] * (1 - tx) + g[y0][x1] * tx
            b = g[y1][x0] * (1 - tx) + g[y1][x1] * tx
            out[y][x] = a * (1 - ty) + b * ty
    return out


def ramp_pick(ramp, v):
    i = int(v * len(ramp))
    return ramp[max(0, min(len(ramp) - 1, i))]


def band_stone():
    """Dense, dark, compressed rock: near-black with a violet cast, tight wavy layering and a few hard glints."""
    rng = random.Random(1601)
    ramp = [hexc(c) for c in ("#0e0c14", "#15121d", "#1c1826", "#241f30", "#2d273b")]
    glint = hexc("#4b4262")
    n1 = wrap_noise(rng, 16, 4)
    n2 = wrap_noise(rng, 16, 8)
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            # tight layering: a slow wave sets the bands, noise breaks them up
            wave = (y + 2.0 * n1[(y * 3) % 16][x]) % 4.0 / 4.0
            v = 0.45 * n1[y][x] + 0.35 * n2[y][x] + 0.2 * wave
            c = ramp_pick(ramp, v)
            px[x, y] = c + (255,)
    # fractures: short dark diagonal runs
    for _ in range(5):
        x, y = rng.randrange(16), rng.randrange(16)
        for k in range(rng.randrange(2, 5)):
            px[(x + k) % 16, (y + k // 2) % 16] = ramp[0] + (255,)
    # glints: single bright pixels with a darker neighbour under them
    for _ in range(4):
        x, y = rng.randrange(16), rng.randrange(16)
        px[x, y] = glint + (255,)
        px[x, (y + 1) % 16] = ramp[1] + (255,)
    return img


PAINTERS = {
    "block/band_stone": band_stone,
}

import plants  # noqa: E402  (the plant and crystal textures live in their own file)
PAINTERS.update(plants.PAINTERS)


def write_all(textures_dir):
    for name, painter in PAINTERS.items():
        path = os.path.join(textures_dir, name + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        painter().save(path)


def sheet(path, names=None, scale=8, cols=6):
    """A labelled contact sheet of the textures, for review."""
    from PIL import ImageDraw
    names = names or list(PAINTERS)
    rows = (len(names) + cols - 1) // cols
    cell = 16 * scale + 24
    out = Image.new("RGB", (cols * cell + 8, rows * (cell + 16) + 8), (40, 40, 44))
    d = ImageDraw.Draw(out)
    for i, n in enumerate(names):
        t = PAINTERS[n]().resize((16 * scale, 16 * scale), Image.NEAREST)
        x, y = 8 + (i % cols) * cell, 8 + (i // cols) * (cell + 16)
        out.paste(t, (x, y), t)
        d.text((x, y + 16 * scale + 4), n.split("/")[-1], fill=(230, 230, 230))
    out.save(path)
