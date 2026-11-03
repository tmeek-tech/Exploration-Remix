"""Exploration Remix's blue fireflies: the glow each one is drawn with. textures.py merges this file's PAINTERS.

A firefly is too small for a shape of its own. The game (client.render.FireflySwarmRenderer) draws each as two little
squares that always face the camera, a bright core and a wider, fainter halo, both cut from this one texture, with
the render type eyes are drawn with: full-bright, its colour added to whatever lies behind it. Shader packs light that
render type as glowing (Complementary under Oculus treats it as a spider's eyes), and the shader's block light is
warm, so the colour lives here, in the texture, never in the light: a near-white cyan heart, cyan round it, fading
through a deep blue to nothing. Black is see-through under the additive blend, and the alpha falls with the light as
well, for packs that blend by alpha instead. The blue fireflies are the mod's own; nothing here is taken from any
other game.
"""
import math
from PIL import Image

SIZE = 32
# from the heart outwards: near-white cyan, bright cyan, sky blue, the deep blue of the halo's rim
RAMP = [(0.0, (226, 252, 255)), (0.12, (150, 236, 255)), (0.3, (78, 196, 255)), (0.6, (40, 120, 246)), (1.0, (22, 64, 200))]


def _colour(t):
    """The ramp's colour at t (0 the heart, 1 the rim)."""
    for (t0, c0), (t1, c1) in zip(RAMP, RAMP[1:]):
        if t <= t1:
            k = (t - t0) / (t1 - t0)
            return tuple(c0[i] + (c1[i] - c0[i]) * k for i in range(3))
    return RAMP[-1][1]


def _firefly():
    """A round glow: a small hot heart and a soft halo that dies away before the square's edge."""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    mid = (SIZE - 1) / 2.0
    for y in range(SIZE):
        for x in range(SIZE):
            r = math.hypot(x - mid, y - mid) / (SIZE / 2.0)       # 0 at the heart, 1 at the middle of an edge
            if r >= 1.0:
                continue
            heart = math.exp(-(r / 0.17) ** 2)
            halo = 0.8 * math.exp(-(r / 0.46) ** 2) * (1.0 - r) ** 0.5
            light = min(1.0, heart + halo)
            if light < 0.015:
                continue
            c = _colour(min(1.0, r / 0.9))
            # quantized a little, as the mod's other textures are, so the glow reads as soft rings, not mush
            light = round(light * 24) / 24.0
            px[x, y] = tuple(int(round(v * light)) for v in c) + (int(round(255 * light)),)
    return img


PAINTERS = {
    "entity/firefly_swarm/firefly": _firefly,
}
