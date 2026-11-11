"""The timed swords' own texture (0.12.0), called by gen_data.py: the sweep a swing leaves through the air
(client.combat.SwingRender).

It is drawn added onto what is behind it (GlowRender's SWEEP), so it is premultiplied: a pixel's colour is already its
light, black is nothing, and the alpha only follows along. (A rune sword's ready light needs no texture: RuneGlow lays
it on the sword's own texels.)

Run through gen_data.py; python3 tools/swings.py --preview <dir> also writes an enlarged preview for review.
"""
import os, sys
import numpy as np
from PIL import Image


def smoothstep(a, b, v):
    t = np.clip((v - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


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


PAINTERS = {"misc/swing_sweep": swing_sweep}


def write_all(assets_dir):
    for name, painter in PAINTERS.items():
        path = os.path.join(assets_dir, "textures", name + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        painter().save(path)


def preview(out_dir):
    """The sweep, enlarged."""
    os.makedirs(out_dir, exist_ok=True)
    swing_sweep().resize((SWEEP_W * 6, SWEEP_H * 6), Image.NEAREST).save(os.path.join(out_dir, "swing_sweep.png"))


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--preview":
        preview(sys.argv[2])
    else:
        print("run gen_data.py, or: swings.py --preview <dir>")
