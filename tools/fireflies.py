"""Exploration Remix's blue fireflies: the glow each one is drawn with. textures.py merges this file's PAINTERS.

A firefly is too small for a shape of its own. The game (client.render.FireflySwarmRenderer) draws each as two little
squares that always face the camera, a bright core and a wider, fainter halo, each cut from its own half of this one
texture, with the render type eyes are drawn with: full-bright, its colour added to whatever lies behind it. Shader
packs light that render type as glowing (Complementary under Oculus treats it as a spider's eyes), and the shader's
block light is warm, so the colour lives here, in the texture, never in the light. The core (the left half): a
near-white cyan heart, cyan round it, fading through a deep blue to nothing. The halo (the right half): a clear blue
cyan all through, with no white heart of its own, deepening to blue at its rim. From a few blocks off the halo is
most of what shows of a firefly (the renderer grows it with the distance), so it has to read as a blue light, not as
a pale speck. Black is see-through under the additive blend, and the alpha falls with the light as well, for packs
that blend by alpha instead. The blue fireflies are the mod's own; nothing here is taken from any other game.
"""
import math
from PIL import Image

SIZE = 32
# the core, from the heart outwards: near-white cyan, bright cyan, sky blue, the deep blue of its rim
RAMP = [(0.0, (226, 252, 255)), (0.12, (150, 236, 255)), (0.3, (78, 196, 255)), (0.6, (40, 120, 246)), (1.0, (22, 64, 200))]
# the halo, from its middle outwards: blue cyan, a deeper sky blue, the same deep blue rim
HALO_RAMP = [(0.0, (70, 206, 255)), (0.4, (48, 160, 255)), (0.75, (32, 108, 240)), (1.0, (22, 64, 200))]


def _colour(t, ramp=RAMP):
    """The ramp's colour at t (0 the middle, 1 the rim)."""
    for (t0, c0), (t1, c1) in zip(ramp, ramp[1:]):
        if t <= t1:
            k = (t - t0) / (t1 - t0)
            return tuple(c0[i] + (c1[i] - c0[i]) * k for i in range(3))
    return ramp[-1][1]


def _glow(light_at, ramp):
    """A round glow, SIZE square: light_at(r) is its light at r (0 the middle, 1 the middle of an edge)."""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    mid = (SIZE - 1) / 2.0
    for y in range(SIZE):
        for x in range(SIZE):
            r = math.hypot(x - mid, y - mid) / (SIZE / 2.0)
            if r >= 1.0:
                continue
            light = min(1.0, light_at(r))
            if light < 0.015:
                continue
            c = _colour(min(1.0, r / 0.9), ramp)
            # quantized a little, as the mod's other textures are, so the glow reads as soft rings, not mush
            light = round(light * 24) / 24.0
            px[x, y] = tuple(int(round(v * light)) for v in c) + (int(round(255 * light)),)
    return img


def _core():
    """A small hot heart and a soft glow round it that dies away before the square's edge."""
    return _glow(lambda r: math.exp(-(r / 0.17) ** 2) + 0.8 * math.exp(-(r / 0.46) ** 2) * (1.0 - r) ** 0.5, RAMP)


def _halo():
    """A soft blue-cyan glow, brightest in the middle (where the core lies over it), dying away before the edge."""
    return _glow(lambda r: 0.95 * math.exp(-(r / 0.45) ** 2) * (1.0 - r) ** 0.5, HALO_RAMP)


def _firefly():
    """The two glows side by side: the core on the left, the halo on the right."""
    img = Image.new("RGBA", (2 * SIZE, SIZE), (0, 0, 0, 0))
    img.paste(_core(), (0, 0))
    img.paste(_halo(), (SIZE, 0))
    return img


PAINTERS = {
    "entity/firefly_swarm/firefly": _firefly,
}
