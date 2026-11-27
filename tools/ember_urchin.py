"""The ember urchin of the Wasteland (0.23.7, Taran's 2026-11-27 ask): its shape and textures.

Taran: "Can you add another urchin-like monster to the Wastelands? This one should be more of a ball than a disk, be
black with orange, ember-like details, and straddle the cave walls. It should also walk around and be more of an
obstacle to avoid than something to fight." A round ball about a block and a quarter across (the cards' size: "Ball
1.25 blocks, spans 7"), its shell black plates like cooled lava crust with glowing orange seams between them ("Black
plates with ember seams"), thirty long walking spines all round it ("About thirty"), each a thin black needle with an
orange-hot point ("Ember-hot tips"), that bow like springs where they press on the rock ("Springy needles that bow"),
and a coat of short black spines between them. The mod's own creature; nothing here is taken from any other game.

The game poses every part itself (creature.body.EmberPose), with no rest pose of its own:
  - the ball is one box, 20 px a side round its middle, which the game draws as a ball: each of the box's faces cut
    into a grid and pushed out onto the sphere (EmberRig), a texel landing where tan(pi/4 * s) of its place s across
    the face (-1 to 1) points. This file paints each texel by that same point on the sphere (ball_dir), so the plates
    and their seams run unbroken over the faces' edges;
  - every spine (each walking spine's shaft and needle, each coat spine) runs along its own +y from its root, the root
    sunk a little into the ball. A walking spine is bent along a curve as it is drawn, so its long faces are cut across
    every few pixels (EmberRig); its socket's boss (a knob square with the ball) is a part of its own.
A rod part's pivot is where its root sits (model pixels: x left, y down, z back, the ball's middle at the origin):
EmberSpec reads the sockets from there.

The glow overlay is drawn as eyes are (so shader packs light it), which adds its light to the texture's: under a
glowing pixel the texture keeps only a dark tint (UNDER), and the two together come out the ember's own colour.
"""
import math
import os
import random
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import creatures as cr  # noqa: E402
from creatures import B, P, faces  # noqa: E402

MOD = cr.MOD
NAME = "ember_urchin"
TEX_W, TEX_H = 128, 64

# ================================================================================================ shape (pixels)
# The ball: a box 20 px a side (a block and a quarter), drawn as a ball of that size (EmberRig).
BALL = 10
# The walking spines: thirty sockets spread evenly over the ball (a golden-angle spiral), each a short stout shaft and a
# long thin needle: 46 px (nearly three blocks) from its root, its root sunk a pixel into the ball, so its point
# reaches about three and a half blocks from the ball's middle: the urchin spans about seven blocks.
SPINES = 30
SHAFT = (2, 16)        # (thickness, length) from the root
NEEDLE = (1, 30)       # from the shaft's end to the point
SINK = 1.0             # px its root sits under the ball's surface
BOSS = 3               # the knob round each socket, px across
# The coat: a few short bristles over the ball between the walking spines' sockets, sparse enough that the ball's
# plates and seams show between them (a dense pelt hid the ball: it read as a heap of sticks, not a ball).
COAT_TRIES = 48
COAT_CLEAR = 15.0      # degrees: no coat spine this near a walking spine's socket


def spiral(n, k, offset=0.5):
    """The k-th of n ways spread evenly over a sphere (a golden-angle spiral), as (x, y, z) in model pixels' axes (unit):
    heights evenly spaced, turning by the golden angle."""
    golden = math.pi * (3.0 - math.sqrt(5.0))
    y = 1.0 - 2.0 * (k + offset) / n
    r = math.sqrt(max(0.0, 1.0 - y * y))
    a = k * golden
    return (r * math.cos(a), y, r * math.sin(a))


def angle(a, b):
    d = max(-1.0, min(1.0, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]))
    return math.degrees(math.acos(d))


def rod(name, segs, pivot, knob=0):
    """A rod part along +y: its segments (thickness, length) end to end from its root, and a knob round its root."""
    boxes, y = [], 0.0
    for thick, length in segs:
        h = thick / 2.0
        boxes.append(B("rod", (-h, y, -h), (thick, length, thick)))
        y += length
    if knob:
        g = knob / 2.0
        boxes.append(B("knob", (-g, -g, -g), (knob, knob, knob), 0.25))
    return P(name, "root", pivot, (0, 0, 0), boxes)


SPINE_DIRS = [spiral(SPINES, k) for k in range(SPINES)]


def coat_sockets():
    """The coat's sockets: (way out (unit), length px) along a denser spiral, kept off the walking spines' sockets."""
    rng = random.Random(9127)
    out = []
    for k in range(COAT_TRIES):
        d = spiral(COAT_TRIES, k, 0.37)
        if min(angle(d, s) for s in SPINE_DIRS) < COAT_CLEAR: continue
        out.append((d, rng.choice((3, 4, 4, 5, 6))))
    return out


COAT = coat_sockets()


def build_parts():
    parts = [P("ball", "root", (0, 0, 0), (0, 0, 0), [B("ball", (-BALL, -BALL, -BALL), (2 * BALL, 2 * BALL, 2 * BALL))])]
    for i, d in enumerate(SPINE_DIRS):
        r = BALL - SINK
        pivot = (d[0] * r, d[1] * r, d[2] * r)
        parts.append(rod(f"spine{i}", (SHAFT, NEEDLE), pivot))
        parts.append(P(f"boss{i}", "root", pivot, (0, 0, 0), [B("boss", (-BOSS / 2, -BOSS / 2, -BOSS / 2), (BOSS, BOSS, BOSS), 0.25)]))
    for j, (d, length) in enumerate(COAT):
        r = BALL - 0.5
        parts.append(rod(f"coat{j}", ((1, length),), (d[0] * r, d[1] * r, d[2] * r)))
    return parts


PARTS = build_parts()


def key_of(p):
    k = p["name"].rstrip("0123456789")
    if k == "coat": k += str(int(p["boxes"][0]["size"][1]))
    return k


def pack(parts):
    """The texture's layout: the ball, one walking spine, one boss and one coat spine of each length get their own
    places (creatures.pack); every other spine shares its twin's, so all thirty read as one creature's."""
    first, proto = {}, []
    for p in parts:
        k = key_of(p)
        if k in first: continue
        first[k] = p
        proto.append(p)
    cr.pack(proto, TEX_W, TEX_H)
    for p in parts:
        src = first[key_of(p)]
        if src is p: continue
        for b, s in zip(p["boxes"], src["boxes"]):
            b["uv"] = list(s["uv"])
    return proto


# ================================================================================================ painting
# How much of a glowing pixel's colour the texture keeps under it (the overlay, added on top, brings the rest).
UNDER = 0.12
# The plates: near-black cooled crust, a little warm grey, rough with darker pits and paler grit.
CRUST = [cr.C(c)[:3] for c in ("#100D0C", "#16120F", "#1C1613", "#221B17", "#29211C")]
PIT = cr.C("#060505")[:3]
GRIT = cr.C("#2B2420")[:3]
# Near a seam the crust is scorched a dull red-brown (lit by the world, not glowing).
SCORCH = [cr.C(c)[:3] for c in ("#2A1510", "#3A1A10")]
# The seams' ember, from the hot middle out (the glow overlay): orange, its hottest only a little toward yellow (the
# glow is added over the dark texture, so a pale yellow comes out near white).
EMBER = {"hot": cr.C("#FFB24A")[:3], "ember": cr.C("#FF8A2A")[:3], "deep": cr.C("#DC5418")[:3], "dim": cr.C("#8A280B")[:3]}
# The spines: black and glossy, a fine sheen down one side; the bosses dull black.
SPINE = [cr.C(c)[:3] for c in ("#09080A", "#0F0D0F", "#151214")]
SPINE_SHEEN = cr.C("#2C2626")[:3]
BOSS_C = [cr.C(c)[:3] for c in ("#0C0A0A", "#141010", "#1C1615")]
# A walking spine's point, from the very tip back: hot, orange, deep orange, a dull red fading into the black.
TIP = ["hot", "ember", "ember", "deep", "deep", "dim", "dim"]
# The plates: sites spread over the sphere (a spiral, jittered); a point's plate is its nearest site.
PLATES = 16
SEAM = 0.062          # how near (in the sphere's units: a pixel is about 0.1) a point must lie to the line where two plates meet to be seam
SEAM_HOT = 0.016
# How hot the seams run, wandering smoothly over the ball (a few broad waves in three dimensions, so it runs on over
# the box's edges): where it is low a seam has cooled to a dark scorch, so the ball reads black with embers in its
# cracks rather than a net of fire.
HEAT_WAVES = 4
COOL = 0.27


def plate_sites():
    rng = random.Random(5521)
    out = []
    for k in range(PLATES):
        x, y, z = spiral(PLATES, k, 0.5)
        x += rng.uniform(-0.18, 0.18); y += rng.uniform(-0.18, 0.18); z += rng.uniform(-0.18, 0.18)
        l = math.sqrt(x * x + y * y + z * z)
        out.append((x / l, y / l, z / l))
    return out


SITES = plate_sites()


def heat_waves():
    rng = random.Random(7741)
    out = []
    for _ in range(HEAT_WAVES):
        x, y, z = rng.gauss(0, 1), rng.gauss(0, 1), rng.gauss(0, 1)
        l = math.sqrt(x * x + y * y + z * z)
        out.append(((x / l, y / l, z / l), rng.uniform(2.6, 4.4), rng.uniform(0, 2 * math.pi)))
    return out


WAVES = heat_waves()


def heat(d):
    """How hot the ball's seams run at the way d (0 cold to 1 hot), smooth over the whole ball."""
    v = sum(math.sin(f * (d[0] * a[0] + d[1] * a[1] + d[2] * a[2]) + ph) for a, f, ph in WAVES) / HEAT_WAVES
    return max(0.0, min(1.0, 0.5 + 0.9 * v))


class Canvas:
    """The texture and its glow overlay (the glowing pixels again, at full brightness)."""

    def __init__(self):
        self.img = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.glow = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.px, self.gx = self.img.load(), self.glow.load()

    def put(self, x, y, c):
        """Colours texel (x, y): an RGB tuple (lit by the world), or an ember shade's name (glowing: on the overlay,
        and the texture under it keeps only a dark tint)."""
        if not (0 <= x < TEX_W and 0 <= y < TEX_H): return
        if isinstance(c, str):
            rgb = tuple(EMBER[c])
            self.px[x, y] = tuple(round(v * UNDER) for v in rgb) + (255,)
            self.gx[x, y] = rgb + (255,)
        else:
            self.px[x, y] = tuple(c[:3]) + (255,)
            self.gx[x, y] = (0, 0, 0, 0)


def texel_point(box, face, i, j):
    """Where texel (i, j) of a box's face lies, in model pixels (vanilla's box layout, as MossbackRig.faces)."""
    (x0, y0, z0), (w, h, d) = box["origin"], box["size"]
    x1, y1, z1 = x0 + w, y0 + h, z0 + d
    return {
        "top": (x0 + i + 0.5, y0, z1 - j - 0.5),
        "bottom": (x0 + i + 0.5, y1, z1 - j - 0.5),
        "west": (x0, y0 + j + 0.5, z1 - i - 0.5),
        "north": (x0 + i + 0.5, y0 + j + 0.5, z0),
        "east": (x1, y0 + j + 0.5, z0 + i + 0.5),
        "south": (x1 - i - 0.5, y0 + j + 0.5, z1),
    }[face]


def ball_dir(x, y, z):
    """The way out from the ball's middle that a point on its box's surface is drawn at (EmberRig: each coordinate s of
    the box, from -1 to 1, taken as tan(pi/4 * s), so the texels lie evenly over the ball), unit."""
    m = [math.tan(math.pi / 4.0 * max(-1.0, min(1.0, v / BALL))) for v in (x, y, z)]
    l = math.sqrt(sum(v * v for v in m))
    return tuple(v / l for v in m)


def ball_texel(d, nz, rng):
    """The ball's colour at the way d: a plate's crust, scorched near a seam, the seam's ember along the line where two
    plates meet (hotter in its middle)."""
    dots = sorted(((d[0] * s[0] + d[1] * s[1] + d[2] * s[2]), k) for k, s in enumerate(SITES))
    a, b = dots[-1][0], dots[-2][0]
    # how far from the line where the two nearest plates meet (on the sphere, roughly in radians)
    gap = (a - b) / max(1e-6, math.sqrt(max(1e-9, 2.0 - 2.0 * (SITES[dots[-1][1]][0] * SITES[dots[-2][1]][0]
                                                                 + SITES[dots[-1][1]][1] * SITES[dots[-2][1]][1]
                                                                 + SITES[dots[-1][1]][2] * SITES[dots[-2][1]][2]))))
    # the seam's width wanders along it (a crack's, not a ruled line's), and so does its heat (cooled stretches dark)
    h = heat(d)
    w = SEAM * (0.7 + 0.6 * nz) * (0.75 + 0.5 * h)
    if h < COOL:
        if gap < w * 0.6: return SCORCH[1] if h > COOL * 0.6 else SCORCH[0]
        if gap < w: return SCORCH[0]
    else:
        hot = (h - COOL) / (1.0 - COOL)
        if gap < SEAM_HOT * (0.5 + 1.2 * nz): return "hot" if hot > 0.55 and nz > 0.45 else "ember"
        if gap < w * 0.55: return "ember" if hot > 0.35 else "deep"
        if gap < w * 0.8: return "deep" if hot > 0.5 else "dim"
        if gap < w: return "dim" if rng.random() < 0.6 else SCORCH[1]
        if gap < w * 1.5: return SCORCH[1] if gap < w * 1.2 else SCORCH[0]
    # the crust: a plate's middle a shade lighter, pits and grit
    r = rng.random()
    if r < 0.06: return PIT
    if r < 0.10: return GRIT
    k = min(len(CRUST) - 1, int((nz * 0.7 + min(1.0, gap / 0.35) * 0.4) * len(CRUST)))
    return CRUST[k]


def spine_texel(part, box, face, i, j, fw, fh, nz, rng):
    top, bottom = face == "top", face == "bottom"
    mat = box["mat"]
    if mat == "boss" or mat == "knob":
        if top: return BOSS_C[2] if nz > 0.6 else BOSS_C[1]
        return BOSS_C[0] if nz < 0.5 else BOSS_C[1]
    p = PART_BY_NAME[part]
    length = sum(b["size"][1] for b in p["boxes"] if b["mat"] == "rod")
    base_y = box["origin"][1]
    y = base_y + (box["size"][1] - 0.5 if bottom else (0.5 if top else j + 0.5))
    from_tip = length - y
    if part.startswith("spine"):
        k = int(from_tip)
        if k < len(TIP): return TIP[k]
    elif part.startswith("coat"):
        # a coat spine's point: a dull ember on the longer ones
        if from_tip < 1.0 and length >= 5: return "dim"
    if top or bottom: return SPINE[1]
    if face == "east" and nz > 0.55: return SPINE_SHEEN
    if rng.random() < 0.05: return SPINE[2]
    return SPINE[0] if nz < 0.5 else SPINE[1]


def paint_box(cv, part, box, rng):
    for face, (x0, y0, fw, fh) in faces(box).items():
        if fw == 0 or fh == 0: continue
        n = cr.noise2(rng, max(1, fw), max(1, fh), 2.0)
        for j in range(fh):
            for i in range(fw):
                if box["mat"] == "ball":
                    c = ball_texel(ball_dir(*texel_point(box, face, i, j)), n[j][i], rng)
                else:
                    c = spine_texel(part, box, face, i, j, fw, fh, n[j][i], rng)
                cv.put(x0 + i, y0 + j, c)


PART_BY_NAME = {p["name"]: p for p in PARTS}
PACKED = pack(PARTS)


def paint(seed=6113):
    cv = Canvas()
    rng = random.Random(seed)
    for p in PACKED:
        for b in p["boxes"]:
            paint_box(cv, p["name"], b, rng)
    return cv


def geometry_json(parts):
    return {"texture_width": TEX_W, "texture_height": TEX_H, "parts": [
        {"name": p["name"], "parent": p["parent"], "pivot": [round(v, 4) for v in p["pivot"]], "rotation": p["rot"],
         "boxes": [{"origin": b["origin"], "size": b["size"], "uv": b["uv"], "inflate": b["inflate"]} for b in p["boxes"]]}
        for p in parts]}


def item_texture(path):
    """The Ember Urchin Spine (its drop, and a long-burning fuel): a black needle from corner to corner with an
    orange-hot point, a pale glint along it."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    ramp = [cr.C(c) for c in ("#0B0909", "#141011", "#1E1819", "#2E2627")]
    for t in range(13):
        x, y = 2 + t, 13 - t
        if t < 3:
            # its root: a thicker stub
            for dx, dy in ((0, 0), (1, 0), (0, -1), (1, -1)): px[x + dx - 1, y + dy] = ramp[1] if dx else ramp[0]
        px[x, y] = ramp[2 if t % 3 == 0 else 1]
        if 3 <= t < 11 and t % 2 == 0: px[x, y - 1] = ramp[3]
    for t, c in ((10, "#8E2C0C"), (11, "#E0621E"), (12, "#FF9A3C"), (13, "#FFD98A")):
        px[2 + t, 13 - t] = cr.C(c)
    px[15, 0] = cr.C("#FFF1C8")
    img.save(path)


def write(assets, write_json):
    """The urchin's geometry, its texture and glow overlay, its spawn egg's model and its spine's; returns its lang entries."""
    write_json(os.path.join(assets, "geometry", NAME + ".json"), geometry_json(PARTS))
    tex_dir = os.path.join(assets, "textures", "entity", NAME)
    os.makedirs(tex_dir, exist_ok=True)
    cv = paint()
    assert cv.glow.getbbox(), f"{NAME}: no glowing pixels"
    cv.img.save(os.path.join(tex_dir, f"{NAME}.png"))
    cv.glow.save(os.path.join(tex_dir, f"{NAME}_glow.png"))
    write_json(os.path.join(assets, "models", "item", NAME + "_spawn_egg.json"), {"parent": "minecraft:item/template_spawn_egg"})
    item_dir = os.path.join(assets, "textures", "item")
    os.makedirs(item_dir, exist_ok=True)
    item_texture(os.path.join(item_dir, NAME + "_spine.png"))
    write_json(os.path.join(assets, "models", "item", NAME + "_spine.json"),
               {"parent": "minecraft:item/handheld", "textures": {"layer0": f"{MOD}:item/{NAME}_spine"}})
    return {f"entity.{MOD}.{NAME}": "Ember Urchin", f"item.{MOD}.{NAME}_spawn_egg": "Ember Urchin Spawn Egg",
            f"item.{MOD}.{NAME}_spine": "Ember Urchin Spine",
            f"death.attack.{MOD}.{NAME}": "%1$s was pricked to death by %2$s",
            f"death.attack.{MOD}.{NAME}.item": "%1$s was pricked to death by %2$s"}


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    from PIL import ImageChops
    print(f"{len(PARTS)} parts, {len(COAT)} coat spines, {sum(len(p['boxes']) for p in PARTS)} boxes")
    cv = paint()
    lit = ImageChops.add(cv.img, cv.glow)
    lit.putalpha(cv.img.getchannel("A"))
    for name, im in (("lit", lit), ("dark", cv.img), ("glow", cv.glow)):
        big = im.resize((TEX_W * 6, TEX_H * 6), Image.NEAREST)
        bg = Image.new("RGBA", big.size, (60, 64, 70, 255))
        Image.alpha_composite(bg, big).save(os.path.join(out, f"tex_{name}.png"))
    item_texture(os.path.join(out, "spine_item.png"))
