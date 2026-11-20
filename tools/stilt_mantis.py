"""The stilt mantis of the Deep Gardens (0.20.0, Taran's 2026-11-20 ask): its shape and textures.

A tall, gentle grazer of the vent field's glowing caves: a small body slung about two blocks up between four very long,
skinny legs whose knees arch up above it (like a crane fly's), a long neck-like prothorax rising to a triangular head
with big eyes, two folded grabbing arms it rakes the glow mats with, a long leafy abdomen and folded wings that flare
when it is startled. It comes in the vent field's four neon colours (block.Neon: magenta, violet, lime, light blue;
lime only near the lime vents), glowing all over in its colour like the glow caps, darker at its joints, eyes and
underside (Taran's picks on the cards, 2026-11-20). The mod's own creature; nothing here is taken from any other game.

The game poses every part itself (creature.body.MantisPose): each part is a few boxes round its joint, with no rest
pose of its own. Two kinds of part:
  - body parts (thorax, prothorax, head, abdomen, wings) lie along their own -z (forward; the abdomen and the wings
    along +z, backward from where they hang), their top toward -y, their left toward +x;
  - limbs (the legs' femur, tibia and tarsus, the arms' coxa, femur and tibia, the antennae) run along their own +y,
    from the joint they hang from to the next one.
Lengths along a limb are the bone's own (the IK uses them: MantisSpec reads them from here).

Textures are painted face by face in vanilla's box layout (creatures.faces) in the neon's five shades (textures.
neon_shades: deep and dim lit by the world, mid, neon and hot glowing), with a _glow overlay of the glowing pixels
drawn again at full brightness, as the glow mats are.
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
NAME = "stilt_mantis"
TEX_W, TEX_H = 128, 128

# The look (card 4, 2026-11-20): "body" = neon all over with darker joints, eyes and underside (recommended);
# "shell" = a near-black shell with neon markings; "legs" = neon legs under a dark body.
LOOK = "body"

# ================================================================================================ shape (pixels)
# Walking legs: femur, tibia, tarsus (the knee arches high over the body: MantisSpec).
FEMUR, TIBIA, TARSUS = 18, 38, 6
# Arms: coxa, femur, tibia (the tibia's last two pixels are its hooked claw).
ARM_COXA, ARM_FEMUR, ARM_TIBIA = 9, 11, 10
ANTENNA = 12
PROTHORAX = 14
LEGS = ("mid_left", "mid_right", "hind_left", "hind_right")
ARMS = ("arm_left", "arm_right")


def limb(name, length, thick, bulb=None, extra=()):
    """A limb bone along +y: a square rod thick px across and length long, a joint bulb round its root (bulb px
    across, inflated a quarter so it covers the rod's end), and any extra boxes."""
    h = thick / 2.0
    boxes = [B("rod", (-h, 0, -h), (thick, length, thick))]
    if bulb:
        g = bulb / 2.0
        boxes.append(B("joint", (-g, -g, -g), (bulb, bulb, bulb), 0.25))
    boxes += list(extra)
    return P(name, "root", (0, 0, 0), (0, 0, 0), boxes)


PARTS = [
    # the thorax, where the four walking legs hang (its middle is the body's centre)
    P("thorax", "root", (0, 0, 0), (0, 0, 0), [B("plate", (-2, -2, -4), (4, 4, 8))]),
    # the long prothorax, rising from the thorax's front to the head, its shoulders flared where the arms hang
    P("prothorax", "root", (0, 0, 0), (0, 0, 0), [
        B("plate", (-1.5, -1.5, -PROTHORAX), (3, 3, PROTHORAX)),
        B("flare", (1.45, -1.0, -13.0), (1, 2, 4)),
        B("flare", (-2.45, -1.0, -13.0), (1, 2, 4)),
    ]),
    # the head: a triangle, broad at the top between its two big eyes, narrowing to the mouth; it hangs from its neck
    P("head", "root", (0, 0, 0), (0, 0, 0), [
        B("crown", (-3, -2, -2.5), (6, 2, 3)),
        B("face", (-2, 0, -2.2), (4, 2, 3)),
        B("mouth", (-1, 2, -1.8), (2, 2, 2)),
        B("eye", (2.2, -3, -3), (2, 3, 3)),
        B("eye", (-4.2, -3, -3), (2, 3, 3)),
    ]),
    limb("antenna_left", ANTENNA, 1),
    limb("antenna_right", ANTENNA, 1),
    # the abdomen: four segments backward from the thorax, the second the broadest, with thin leafy lobes
    P("abdomen1", "root", (0, 0, 0), (0, 0, 0), [B("belly", (-2.5, -2, 0), (5, 4, 6))]),
    P("abdomen2", "root", (0, 0, 0), (0, 0, 0), [
        B("belly", (-3, -2, 0), (6, 4, 6)),
        B("lobe", (2.95, 0.5, 0.5), (3, 0, 5)),
        B("lobe", (-5.95, 0.5, 0.5), (3, 0, 5)),
    ]),
    P("abdomen3", "root", (0, 0, 0), (0, 0, 0), [
        B("belly", (-2, -1.5, 0), (4, 3, 6)),
        B("lobe", (1.95, 0.5, 0.5), (2, 0, 4)),
        B("lobe", (-3.95, 0.5, 0.5), (2, 0, 4)),
    ]),
    P("abdomen4", "root", (0, 0, 0), (0, 0, 0), [B("belly", (-1, -1, 0), (2, 2, 5))]),
    # the folded wings, two thin plates lying over the abdomen (flared when it is startled)
    P("wing_left", "root", (0, 0, 0), (0, 0, 0), [B("wing", (0, 0, 0), (3, 0, 15))]),
    P("wing_right", "root", (0, 0, 0), (0, 0, 0), [B("wing", (-3, 0, 0), (3, 0, 15))]),
]
for leg in LEGS:
    PARTS += [limb(f"{leg}_femur", FEMUR, 2, bulb=3), limb(f"{leg}_tibia", TIBIA, 1, bulb=2),
              limb(f"{leg}_tarsus", TARSUS, 1, bulb=1)]
for arm in ARMS:
    PARTS += [limb(f"{arm}_coxa", ARM_COXA, 2, bulb=2),
              # the femur's spines: a thin plate standing out of its inner side, toward the folded tibia
              limb(f"{arm}_femur", ARM_FEMUR, 2, bulb=2, extra=[B("spines", (0, 1, 1), (0, ARM_FEMUR - 1, 2))]),
              limb(f"{arm}_tibia", ARM_TIBIA, 1, bulb=1)]


# ================================================================================================ painting
class Canvas:
    """The texture and its glow overlay (the glowing pixels again, drawn at full brightness)."""

    def __init__(self):
        self.img = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.glow = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.px, self.gx = self.img.load(), self.glow.load()

    def put(self, x, y, shade, s):
        """Colours texel (x, y) in one of the neon's shades (None: see-through)."""
        if not (0 <= x < TEX_W and 0 <= y < TEX_H): return
        if shade is None:
            self.px[x, y] = (0, 0, 0, 0)
            self.gx[x, y] = (0, 0, 0, 0)
            return
        c = tuple(s[shade][:3]) + (255,)
        self.px[x, y] = c
        self.gx[x, y] = c if shade in T.GLOWING else (0, 0, 0, 0)


def shades(neon):
    """The neon's five shades, and a dark shell ramp (for the shell and legs looks): near-black tinted by the neon."""
    s = dict(T.neon_shades(neon))
    r, g, b = T.NEON_RGB[neon]
    for i, k in enumerate(("shell0", "shell1", "shell2")):
        base = T.NEAR_BLACK[1 + i]
        s[k] = tuple(round(base[n] * 0.8 + (r, g, b)[n] * (0.035 + 0.02 * i)) for n in range(3))
    return s


def value(rng, w, h, cell=2.5):
    return cr.noise2(rng, max(1, w), max(1, h), cell)


def paint_box(cv, part, box, s, rng):
    """Paints one box: its material decides the pattern."""
    mat = box["mat"]
    w, h, d = box["size"]
    for face, (x0, y0, fw, fh) in faces(box).items():
        if fw == 0 or fh == 0: continue
        n = value(rng, fw, fh)
        for j in range(fh):
            for i in range(fw):
                cv.put(x0 + i, y0 + j, texel(part, mat, face, i, j, fw, fh, n[j][i], rng, box["size"]), s)


def dark(look, k):
    """The non-glowing shade for a darker part, by look: the neon's own dim/deep, or the shell's."""
    if look == "body": return "dim" if k > 0.5 else "deep"
    return "shell2" if k > 0.5 else "shell1"


def texel(part, mat, face, i, j, fw, fh, nz, rng, size):
    """The shade of one texel. Body looks: neon plates with a hot dorsal stripe, darker toward the underside; shell
    looks: near-black with neon markings."""
    look = LOOK
    legish = part.split("_")[-1] in ("femur", "tibia", "tarsus", "coxa") or part.startswith("antenna")
    body_neon = look == "body" or (look == "legs" and legish)
    w, h, d = size
    top, bottom = face == "top", face == "bottom"
    side = face in ("west", "east", "north", "south")
    if mat == "joint":
        # the joints: darker knobs, a mid glint on top
        if look == "body": return "mid" if (top and nz > 0.6) else ("dim" if nz > 0.35 else "deep")
        return "neon" if (top and nz > 0.7) else dark(look, nz)
    if mat == "eye":
        # big dark eyes, a pseudopupil and one hot glint at the top front
        if face == "north" and i == fw // 2 and j == fh - 2: return "deep"
        if (face in ("north", "top")) and i == 0 and j == 0: return "hot"
        if face == "top" and nz > 0.55: return "mid" if look == "body" else "dim"
        return "dim" if nz > 0.3 else "deep"
    if mat == "mouth":
        return "deep" if (bottom or nz < 0.4) else ("dim" if look != "body" else "mid")
    if mat == "spines":
        # spikes along the femur's inner edge: every other pixel a spine, the rest see-through
        along = j
        if i == 0 and along % 2 == 0: return "hot" if body_neon or look != "body" else "neon"
        if i == 0: return None
        return None if along % 2 == 1 else "neon"
    if mat == "wing":
        # thin membranes with glowing veins, a hot eyespot near the tip that shows when they flare
        u, v = (i, j) if top or bottom else (i, j)
        L = fh
        cx, cy = fw / 2.0 - 0.5, L * 0.72
        r = math.hypot(u - cx, (v - cy) * 0.9)
        if r < 0.8: return "neon"
        if r < 1.9: return "hot"
        vein = (u == (1 if fw > 2 else 0)) or (v % 5 == 2 and u != fw - 1)
        if bottom: return "dim" if vein else "deep"
        if look == "body": return "neon" if vein else ("mid" if nz > 0.45 else "dim")
        return "neon" if vein else dark(look, nz)
    if mat == "lobe":
        edge = i == 0 or i == fw - 1 or j == 0 or j == fh - 1
        if bottom: return "dim"
        if look == "body": return "hot" if (edge and nz > 0.4) else ("neon" if nz > 0.35 else "mid")
        return "neon" if edge else dark(look, nz)
    if mat in ("rod",):
        # legs and arms: neon rods with a hot line down one side, darker rings now and then and darker near the ends
        if top or bottom: return dark(look, nz) if not body_neon else "dim"
        ring = (j % 9 == 8)
        near_end = j < 1 or j >= fh - 1
        hot_side = face == "east"
        if part.endswith("_tarsus"):
            if j >= fh - 1: return "hot"
            return "mid" if (body_neon and hot_side) else ("dim" if body_neon else dark(look, nz))
        if part.endswith("_tibia") and part.startswith("arm"):
            if j >= fh - 2: return "hot"                  # the hooked claw
        if part.startswith("antenna"):
            if j >= fh - 2: return "hot"
            return "mid" if body_neon else ("neon" if j % 3 == 0 else dark(look, nz))
        if body_neon:
            if near_end: return "dim"
            if ring: return "mid"
            if hot_side: return "hot" if nz > 0.25 else "neon"
            return "neon" if nz > 0.3 else "mid"
        # shell legs: dark with neon bands
        if ring or near_end: return "neon"
        return "mid" if (hot_side and nz > 0.7) else dark(look, nz)
    # body plates: thorax, prothorax, head crown and face, abdomen segments, the prothorax's flares
    segment_line = mat == "belly" and side and face in ("west", "east") and (i % 3 == 2)
    if look == "body":
        if bottom: return "deep" if nz < 0.45 else "dim"
        if face == "north" and mat == "belly": return "dim"
        if face == "south" and mat == "belly": return "deep" if part == "abdomen4" else "dim"
        if top:
            mid_col = fw // 2
            if i in (mid_col, mid_col - 1 if fw % 2 == 0 else mid_col) and mat in ("plate", "belly", "crown"):
                return "hot"
            return "neon" if nz > 0.35 else "mid"
        # sides: neon over the upper rows, falling to dim and deep under it
        frac = j / max(1, fh - 1)
        if segment_line: return "mid" if frac < 0.5 else "dim"
        if frac < 0.45: return "neon" if nz > 0.3 else "mid"
        if frac < 0.75: return "mid" if nz > 0.4 else "dim"
        return "dim" if nz > 0.4 else "deep"
    # shell looks: a dark shell with a neon dorsal stripe, spots and segment lines
    if bottom: return "shell0"
    if top:
        mid_col = fw // 2
        if i == mid_col and mat in ("plate", "belly", "crown"): return "neon"
        return "mid" if nz > 0.82 else dark("shell", nz)
    if segment_line: return "neon"
    return "mid" if nz > 0.86 else dark("shell", nz)


def paint(neon, seed=6101):
    s = shades(neon)
    cv = Canvas()
    rng = random.Random(seed)
    for p in PARTS:
        for b in p["boxes"]:
            paint_box(cv, p["name"], b, s, rng)
    return cv


def geometry_json(parts):
    return {"texture_width": TEX_W, "texture_height": TEX_H, "parts": [
        {"name": p["name"], "parent": p["parent"], "pivot": p["pivot"], "rotation": p["rot"],
         "boxes": [{"origin": b["origin"], "size": b["size"], "uv": b["uv"], "inflate": b["inflate"]} for b in p["boxes"]]}
        for p in parts]}


def write(assets, write_json):
    """The mantis's geometry, a texture and a glow overlay for each neon colour, its spawn egg's model; returns its
    lang entries."""
    cr.pack(PARTS, TEX_W, TEX_H)
    write_json(os.path.join(assets, "geometry", NAME + ".json"), geometry_json(PARTS))
    tex_dir = os.path.join(assets, "textures", "entity", NAME)
    os.makedirs(tex_dir, exist_ok=True)
    for i, neon in enumerate(T.NEONS):
        cv = paint(neon, 6101 + 13 * i)
        assert cv.glow.getbbox(), f"{NAME}: no glowing pixels"
        cv.img.save(os.path.join(tex_dir, f"{NAME}_{neon}.png"))
        cv.glow.save(os.path.join(tex_dir, f"{NAME}_{neon}_glow.png"))
    write_json(os.path.join(assets, "models", "item", NAME + "_spawn_egg.json"), {"parent": "minecraft:item/template_spawn_egg"})
    return {f"entity.{MOD}.{NAME}": "Stilt Mantis", f"item.{MOD}.{NAME}_spawn_egg": "Stilt Mantis Spawn Egg"}


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    cr.pack(PARTS, TEX_W, TEX_H)
    for neon in T.NEONS:
        cv = paint(neon)
        big = cv.img.resize((TEX_W * 4, TEX_H * 4), Image.NEAREST)
        bg = Image.new("RGBA", big.size, (24, 22, 30, 255))
        Image.alpha_composite(bg, big).save(os.path.join(out, f"tex_{neon}.png"))
