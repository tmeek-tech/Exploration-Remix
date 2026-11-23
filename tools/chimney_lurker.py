"""The chimney lurker of the Deep Gardens (0.21.0, Taran's 2026-11-23 ask): its shape and textures.

A big spider-like ambusher that lives down in the mouths of the live vents: a low cephalothorax ringed by twelve long
walking legs (Taran's card: "Twelve walking legs") that span about six blocks ("Legs spanning about 6 blocks"), a
fat egg-shaped abdomen behind it, two short fangs and two feelers at its front. Mostly black (Taran: "mostly black
while it's waiting in ambush"): a matte near-black chitin with a faint cool sheen and fine bristles. Its eyes, a
bead-like pattern down its back and bands at its leg joints glow in its vent's colour (cards: "Eyes and markings",
"Its vent's colour"), and only while it hunts: the game draws the glow over the texture at the brightness the
creature has it (0 waiting in the dark), so this file paints the glow at full and the renderer dims it. The mod's own
creature; nothing here is taken from any other game.

The game poses every part itself (creature.body.LurkerPose): each part is a few boxes round its joint, with no rest
pose of its own. Two kinds of part:
  - body parts (the prosoma, the abdomen) lie along their own -z (forward; the abdomen along +z, backward from its
    waist), their top toward -y, their left toward +x;
  - limbs (each leg's coxa, femur, tibia and metatarsus, the fangs' bases and fangs, the feelers) run along their own
    +y, from the joint they hang from to the next one.
Lengths along a limb are the bone's own (LurkerSpec reads them from here: the longest box of each limb part).

Leg parts are named leg<pair><side>_<segment>: pair 0 (front) to 5 (back), side l or r.

Textures are painted face by face in vanilla's box layout (creatures.faces). The glow overlay is drawn as eyes are
(so shader packs light it), which adds its light to the texture's: under a glowing pixel the texture keeps only a
dark tint of it (UNDER), and the two together come out the pixel's own neon (as on the mantis and the glow mats).
"""
import json
import math
import os
import random
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import creatures as cr  # noqa: E402
import textures as T  # noqa: E402
from creatures import B, P, faces  # noqa: E402

MOD = cr.MOD
NAME = "chimney_lurker"
TEX_W, TEX_H = 256, 128

# ================================================================================================ shape (pixels)
PAIRS = 6
SIDES = ("l", "r")
# Each pair's segments (px): femur, tibia (with its knee), metatarsus (with its claw). The front pair is longest (it
# reaches up over the rim of its vent as it waits), the back pair next, the middle ones shortest: about six blocks
# across, foot to foot, standing.
FEMUR = (26, 23, 21, 21, 23, 25)
TIBIA = (25, 22, 20, 20, 22, 24)
META = (18, 16, 15, 15, 16, 17)
COXA = 3
CHELICERA, FANG = 6, 4
PALP_FEMUR, PALP_TIBIA = 6, 5


def leg_name(pair, side):
    return f"leg{pair}{side}"


def limb(name, length, thick, bulb=None, extra=(), mat="rod"):
    """A limb bone along +y: a square rod thick px across and length long, a joint bulb round its root (bulb px
    across, inflated a quarter so it covers the rod's end), and any extra boxes."""
    h = thick / 2.0
    boxes = [B(mat, (-h, 0, -h), (thick, length, thick))]
    if bulb:
        g = bulb / 2.0
        boxes.append(B("joint", (-g, -g, -g), (bulb, bulb, bulb), 0.25))
    boxes += list(extra)
    return P(name, "root", (0, 0, 0), (0, 0, 0), boxes)


PARTS = [
    # the prosoma (cephalothorax): a low, broad carapace with the head raised at its front, the sternum under it; its
    # middle is the body's centre. Eight eyes on the head: the big pair in front, three smaller pairs round them.
    P("prosoma", "root", (0, 0, 0), (0, 0, 0), [
        B("carapace", (-7, -3, -8), (14, 5, 16)),
        B("head", (-4.5, -5, -9), (9, 3, 6)),
        B("sternum", (-5, 2, -6.5), (10, 1, 12)),
        B("eye", (0.6, -5.7, -9.6), (2, 2, 1)),
        B("eye", (-2.6, -5.7, -9.6), (2, 2, 1)),
        B("eye_small", (3.0, -5.4, -8.8), (1, 1, 1)),
        B("eye_small", (-4.0, -5.4, -8.8), (1, 1, 1)),
        B("eye_small", (1.4, -5.6, -7.4), (1, 1, 1)),
        B("eye_small", (-2.4, -5.6, -7.4), (1, 1, 1)),
        B("eye_small", (3.6, -4.5, -6.6), (1, 1, 1)),
        B("eye_small", (-4.6, -4.5, -6.6), (1, 1, 1)),
    ]),
    # the abdomen: egg-shaped behind the waist, widest past its middle, the spinnerets under its tip
    P("abdomen", "root", (0, 0, 0), (0, 0, 0), [
        B("waist", (-2, -1.5, 0), (4, 3, 2)),
        B("belly", (-7, -6, 1.5), (14, 11, 4)),
        B("belly", (-9.5, -8, 5), (19, 15, 13)),
        B("belly", (-7.5, -6.5, 17.5), (15, 12, 5)),
        B("tip", (-4, -3.5, 22), (8, 7, 2)),
        B("spinner", (-1.5, 1, 23), (3, 2, 2)),
    ]),
]
for side in SIDES:
    PARTS += [limb(f"chelicera_{side}", CHELICERA, 3, bulb=None, mat="chelicera"),
              limb(f"fang_{side}", FANG, 1, mat="fang"),
              limb(f"palp_{side}_femur", PALP_FEMUR, 2, bulb=2),
              limb(f"palp_{side}_tibia", PALP_TIBIA, 2, bulb=2, mat="palp")]
for pair in range(PAIRS):
    for side in SIDES:
        n = leg_name(pair, side)
        PARTS += [
            limb(f"{n}_coxa", COXA, 3, bulb=3, mat="coxa"),
            # the femur: thick, its root bulb the trochanter, a band of glow round its far end (by the knee)
            limb(f"{n}_femur", FEMUR[pair], 3, bulb=4, mat="femur"),
            # the tibia: its root bulb the knee (patella), glowing
            limb(f"{n}_tibia", TIBIA[pair], 2, bulb=3, mat="tibia"),
            # the metatarsus down to the foot: its root bulb the ankle, glowing; a hooked claw at its tip
            limb(f"{n}_meta", META[pair], 2, bulb=2, mat="meta",
                 extra=[B("claw", (-0.5, META[pair] - 0.5, -0.5), (1, 2, 1))]),
        ]


# ================================================================================================ painting
# How much of a glowing pixel's colour the texture keeps under it (the overlay, added on top, brings the rest).
UNDER = 0.12
# The chitin: matte near-black, a little blue-violet, with a faint cool sheen on its upper faces and pale-ish bristles.
CHITIN = [cr.C(c)[:3] for c in ("#09080C", "#0F0E14", "#15131B", "#1B1922", "#23202B")]
SHEEN = cr.C("#34303F")[:3]
BRISTLE = cr.C("#2C2935")[:3]
FANG_GLOSS = [cr.C(c)[:3] for c in ("#100D10", "#1E1A1F", "#3A3239")]


class Canvas:
    """The texture and its glow overlay (the glowing pixels again, at full brightness)."""

    def __init__(self, neon):
        self.img = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.glow = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.px, self.gx = self.img.load(), self.glow.load()
        self.s = T.neon_shades(neon)

    def put(self, x, y, c):
        """Colours texel (x, y): an RGB tuple (lit by the world), or a neon shade's name (glowing: on the overlay,
        and the texture under it keeps only a dark tint)."""
        if not (0 <= x < TEX_W and 0 <= y < TEX_H): return
        if isinstance(c, str):
            rgb = tuple(self.s[c][:3])
            self.px[x, y] = tuple(round(v * UNDER) for v in rgb) + (255,)
            self.gx[x, y] = rgb + (255,)
        else:
            self.px[x, y] = tuple(c[:3]) + (255,)
            self.gx[x, y] = (0, 0, 0, 0)


def chitin(nz, lit=0.0):
    """A chitin shade from a noise value (0 to 1), lifted by `lit` (0 to 1: the upper faces catch a little sheen)."""
    k = nz * 0.75 + lit * 0.45
    if k > 0.93: return SHEEN
    return CHITIN[min(len(CHITIN) - 1, int(k * len(CHITIN)))]


def paint_box(cv, part, box, rng):
    mat = box["mat"]
    for face, (x0, y0, fw, fh) in faces(box).items():
        if fw == 0 or fh == 0: continue
        n = cr.noise2(rng, max(1, fw), max(1, fh), 2.0)
        for j in range(fh):
            for i in range(fw):
                c = texel(part, mat, face, i, j, fw, fh, n[j][i], rng, box["size"])
                if c is not None: cv.put(x0 + i, y0 + j, c)


def bristles(nz, rng, lit):
    """Fine bristles: a pale speck now and then on a dark ground."""
    if rng.random() < 0.09: return BRISTLE
    return chitin(nz, lit)


def texel(part, mat, face, i, j, fw, fh, nz, rng, size):
    top, bottom = face == "top", face == "bottom"
    if mat == "eye":
        # the big front eyes: a hot heart, neon round it
        if face == "north": return "hot" if (i, j) in ((0, 0), (1, 0), (0, 1), (1, 1)) and (i + j) % 3 != 2 else "neon"
        return "neon"
    if mat == "eye_small":
        return "hot" if face in ("north", "top") else "neon"
    if mat == "carapace":
        if bottom: return chitin(nz * 0.5)
        if top:
            # the fovea, a glowing dot down the middle behind the head, and a fine dark radial grain
            mid = fw // 2
            if i in (mid - 1, mid) and j in (fh // 2 - 1, fh // 2): return "mid"
            # two little beads either side of it
            if j == fh // 2 + 2 and i in (mid - 4, mid + 3): return "neon"
            return bristles(nz, rng, 0.55)
        # its sides: a thin sheen along the rim
        if j == 0: return SHEEN if nz > 0.5 else CHITIN[3]
        return bristles(nz, rng, 0.2)
    if mat == "head":
        if top:
            # a ring of faint glow round the eye mound's back edge
            if j == 0 and 1 <= i < fw - 1: return "mid" if i % 2 == 0 else CHITIN[2]
            return bristles(nz, rng, 0.6)
        if face == "north": return chitin(nz * 0.6, 0.2)
        return bristles(nz, rng, 0.25)
    if mat == "sternum":
        return chitin(nz * 0.5, 0.1)
    if mat in ("waist", "spinner", "tip"):
        if mat == "spinner" and bottom: return "dim"
        return bristles(nz, rng, 0.3 if top else 0.1)
    if mat == "belly":
        return belly(face, i, j, fw, fh, nz, rng, size)
    if mat == "chelicera":
        # the fangs' bases: stout, glossy, a shine down their front
        if face == "north" and i == 1: return FANG_GLOSS[2] if nz > 0.4 else FANG_GLOSS[1]
        return FANG_GLOSS[0] if nz < 0.5 else FANG_GLOSS[1]
    if mat == "fang":
        if j >= fh - 1: return FANG_GLOSS[2]
        return FANG_GLOSS[1] if face in ("north", "east") else FANG_GLOSS[0]
    if mat == "palp":
        if j >= fh - 1: return "mid"                         # the feeler's tip, a spark of glow
        return bristles(nz, rng, 0.3)
    if mat == "joint":
        # each leg joint's knob is black chitin with a thin ring of glow round its middle (card: "bands at its leg
        # joints"): brightest at the knees, dimmer at the ankles, faint where the femur leaves the body; the hips'
        # and the feelers' knobs stay dark
        if top or bottom: return chitin(nz * 0.5, 0.2)
        ring = j == fh // 2
        if part.endswith("_tibia") and ring: return "hot" if nz > 0.8 else "neon"     # the knee
        if part.endswith("_meta") and ring: return "mid"                               # the ankle
        if part.endswith("_femur") and ring: return "dim"                              # the trochanter
        return bristles(nz, rng, 0.3)
    if mat == "claw":
        return FANG_GLOSS[2] if j == fh - 1 else FANG_GLOSS[1]
    if mat in ("coxa", "femur", "tibia", "meta", "rod"):
        if top or bottom: return chitin(nz * 0.4)
        L = fh
        # the rods stay black (the joints carry the rings); a fine sheen down the upper side, bristles all along
        if mat == "meta" and j >= L - 2: return CHITIN[1]
        upper = face == "east"
        if upper and nz > 0.55: return SHEEN
        return bristles(nz, rng, 0.35 if upper else 0.1)
    return chitin(nz)


def belly(face, i, j, fw, fh, nz, rng, size):
    """The abdomen's pieces: black and bristly, with the bead pattern down its back (the top faces of its three
    pieces carry the beads in two rows that close in toward the tip, a line of small ones down the middle) and a row
    of small beads low along each side."""
    w, h, d = size
    if face == "bottom": return chitin(nz * 0.45)
    if face == "top":
        # row 0 is the back edge (toward the tip), the last row the front; i runs with x (the left is +x)
        mid = (fw - 1) / 2.0
        # how far along the whole abdomen this row lies (0 at the waist, 1 at the tip): the pieces are told apart by width
        if w == 14: a0, a1 = 0.0, 0.18
        elif w == 19: a0, a1 = 0.18, 0.78
        else: a0, a1 = 0.78, 1.0
        along = a0 + (a1 - a0) * (1.0 - (j + 0.5) / fh)
        half = (fw / 2.0) * (0.62 - 0.38 * along)            # the two rows close in toward the tip
        dx = abs(i - mid)
        phase = along * 6.0                                    # six pairs of beads along the back
        k = phase - math.floor(phase)
        if abs(dx - half) < 1.0 and 0.25 < k < 0.75:
            return "hot" if abs(dx - half) < 0.5 and 0.38 < k < 0.62 else "neon"
        # the middle line: small beads between the pairs
        if dx < 0.6 and (k < 0.12 or k > 0.88) and along > 0.12: return "mid"
        return bristles(nz, rng, 0.45)
    if face in ("west", "east"):
        # a row of small beads low along each side of the big piece
        if w == 19 and j == fh - 4 and i % 4 == 1: return "mid"
        frac = j / max(1, fh - 1)
        return bristles(nz, rng, 0.35 * (1.0 - frac))
    # front and back faces
    if face == "south" and w == 8: return chitin(nz * 0.5)
    return bristles(nz, rng, 0.2)


def paint(neon, seed=7301):
    cv = Canvas(neon)
    rng = random.Random(seed)
    for p in PARTS:
        for b in p["boxes"]:
            paint_box(cv, p["name"], b, rng)
    return cv


def geometry_json(parts):
    return {"texture_width": TEX_W, "texture_height": TEX_H, "parts": [
        {"name": p["name"], "parent": p["parent"], "pivot": p["pivot"], "rotation": p["rot"],
         "boxes": [{"origin": b["origin"], "size": b["size"], "uv": b["uv"], "inflate": b["inflate"]} for b in p["boxes"]]}
        for p in parts]}


def write(assets, write_json):
    """The lurker's geometry, a texture and a glow overlay for each neon colour, its spawn egg's model and its fang's
    item model; returns its lang entries."""
    cr.pack(PARTS, TEX_W, TEX_H)
    write_json(os.path.join(assets, "geometry", NAME + ".json"), geometry_json(PARTS))
    tex_dir = os.path.join(assets, "textures", "entity", NAME)
    os.makedirs(tex_dir, exist_ok=True)
    for i, neon in enumerate(T.NEONS):
        # one pattern for all four colours (the same seed), so they read as one creature in four colours
        cv = paint(neon, 7301)
        assert cv.glow.getbbox(), f"{NAME}: no glowing pixels"
        cv.img.save(os.path.join(tex_dir, f"{NAME}_{neon}.png"))
        cv.glow.save(os.path.join(tex_dir, f"{NAME}_{neon}_glow.png"))
    write_json(os.path.join(assets, "models", "item", NAME + "_spawn_egg.json"), {"parent": "minecraft:item/template_spawn_egg"})
    return {f"entity.{MOD}.{NAME}": "Chimney Lurker", f"item.{MOD}.{NAME}_spawn_egg": "Chimney Lurker Spawn Egg",
            f"item.{MOD}.{NAME}_fang": "Chimney Lurker Fang"}


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    cr.pack(PARTS, TEX_W, TEX_H)
    from PIL import ImageChops
    for neon in T.NEONS:
        cv = paint(neon, 7301)
        lit = ImageChops.add(cv.img, cv.glow)
        lit.putalpha(cv.img.getchannel("A"))
        for name, im in (("lit", lit), ("dark", cv.img)):
            big = im.resize((TEX_W * 4, TEX_H * 4), Image.NEAREST)
            bg = Image.new("RGBA", big.size, (60, 64, 70, 255))
            Image.alpha_composite(bg, big).save(os.path.join(out, f"tex_{neon}_{name}.png"))
