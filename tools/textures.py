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

# The plant and crystal textures live in their own files: plants.py (the first plants and the shared helpers), then
# plants_upper.py (Upper Caves and Blue Reaches) and plants_deep.py (Deep Gardens and Basalt Galleries). plants_deep
# goes last because it may repaint a plant of plants.py. The Upper Caves rework added plants_frost.py and plants_ice.py
# (the Frost Fern Grotto and the frozen Root Vaults) and plants_entries.py (the sinkholes and the oasis hollows).
import plants  # noqa: E402
import plants_upper  # noqa: E402
import plants_frost  # noqa: E402
import plants_ice  # noqa: E402
import plants_entries  # noqa: E402
import plants_deep  # noqa: E402
PAINTERS.update(plants.PAINTERS)
PAINTERS.update(plants_upper.PAINTERS)
PAINTERS.update(plants_frost.PAINTERS)
PAINTERS.update(plants_ice.PAINTERS)
PAINTERS.update(plants_entries.PAINTERS)
PAINTERS.update(plants_deep.PAINTERS)

# Emissive overlays: texture -> the colours lifted into <texture>_glow.png, which the models draw a second time at full
# block light (gen_data.py), so those pixels glow even where the plant gives little or no light. The plant files add
# theirs with plants.register(..., glow=...); the first glowing plants get theirs here.
GLOW = {
    "block/white_glow_flower": {"#F6F0DE", "#F4CF6A"},
    "block/lantern_fungus": {"#FFE29A", "#FFF3CC", "#FFDD86", "#F1B84F"},
    "block/lantern_roots": {"#F1B84F", "#FFE08A"},
    "block/lantern_pod": {"#F4A93A", "#FFD26E", "#FFF0C0"},
    "block/gold_flower": {"#FFF4C8", "#EBCB72"},
    "block/asurine_crystal": {"#A8D0FF", "#4486EE"},
    "block/blue_glow_mushroom": {"#A8D0FF", "#6AA6F6"},
    "block/blue_glow_vines": {"#8CC0FF", "#3F7FE8"},
    "block/blue_glow_vines_plant": {"#8CC0FF", "#3F7FE8"},
    "block/blue_glow_vines_plant_2": {"#8CC0FF", "#3F7FE8"},
    "block/ember_vines": {"#FFE6A6", "#F7B046"},
    "block/ember_vines_plant": {"#FFE6A6", "#F7B046"},
}
GLOW.update(plants.GLOW)


def glow_layer(img, colours):
    """Only the pixels of these colours (hex), everything else transparent."""
    keep = {plants.hexc(c) for c in colours}
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    src, dst = img.load(), out.load()
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            if src[x, y] in keep:
                dst[x, y] = src[x, y]
    return out


def write_all(textures_dir):
    for name, painter in PAINTERS.items():
        path = os.path.join(textures_dir, name + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img = painter()
        img.save(path)
        if name in GLOW:
            overlay = glow_layer(img, GLOW[name])
            assert overlay.getbbox() is not None, f"{name}: none of its glow colours is in the texture"
            overlay.save(os.path.join(textures_dir, name + "_glow.png"))
    for name in GLOW:
        assert name in PAINTERS, f"{name}: a glow overlay for a texture nobody paints"


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
