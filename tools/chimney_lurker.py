"""The vent urchin of the Deep Gardens (0.21.3, Taran's 2026-11-24 ask): its shape and textures.

Taran: "Can you actually make the vent spiders more sea urchin-like than spider-like and have them straddle the vents
that they live in?" It reworks 0.21.0's chimney lurker (the creature keeps its id, chimney_lurker, so the lurkers
already set down in a world stay): a round black test (shell) about a block and a half across, ringed by sixteen long
walking spines that it stands on, each bending once at a knee close to the test (Taran's card: "Needles with one
knee", so it walks more like a spider on thin needles), a coat of short spines all over it, and its mouth underneath
with five teeth. Mostly black (Taran's 0.21.0 card: "mostly black while it's waiting in ambush"): a matte near-black
test with a faint cool sheen, the five-part star across its crown a row of paler pores. Neon bands ring every spine
(Taran's card: "Bands down every spine"), glowing in its vent's colour only while it hunts: the game draws the glow
over the texture at the brightness the creature has it (0 waiting in the dark), so this file paints the glow at full
and the renderer dims it. The mod's own creature; nothing here is taken from any other game.

The game poses every part itself (creature.body.LurkerPose): each part is a few boxes round its own root, with no
rest pose of its own. Two kinds of part:
  - the test lies in its own frame: forward along -z, up along -y, its left along +x, its middle at the origin (and
    each walking spine's boss, square with it, round its socket);
  - rods (each walking spine's base and needle, the coat spines, the teeth) run along their own +y from their root.
A rod part's pivot is where it sits on the test (the test's frame, pixels): LurkerSpec reads the sockets from there.

Textures are painted face by face in vanilla's box layout (creatures.faces); the test is painted by where each texel
lies on it (its way from the middle), so the star's five rays run unbroken over the boxes' faces. The glow overlay is
drawn as eyes are (so shader packs light it), which adds its light to the texture's: under a glowing pixel the texture
keeps only a dark tint of it (UNDER), and the two together come out the pixel's own neon (as on the mantis).
"""
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
# The test: an oblate ball 24 px across and 20 high, four boxes round its middle (a stepped ball, as the game's own
# round things are). Its half sizes, for placing what sits on it.
HALF_W, HALF_H = 12, 10
TEST = [
    B("test", (-10, -8, -10), (20, 16, 20)),
    B("test", (-12, -6, -8), (24, 12, 16)),
    B("test", (-8, -6, -12), (16, 12, 24)),
    B("test", (-8, -10, -8), (16, 20, 16)),
]
# The walking spines: sixteen round the test a little under its widest, each a short stout base from its socket's boss
# to a knee, and from there a long needle tapering to a point: about three and a half blocks in all (0.21.4, Taran: "make
# its legs a little longer so it can straddle big vents"; card: "A quarter longer"), so it spans about eight blocks.
SPINES = 16
SPINE_ELEVATION = -9.0       # degrees: their sockets sit a little under the test's widest
BASE_SEGS = ((3, 12),)                 # (thickness, length) from the socket to the knee: 12 px
NEEDLE_SEGS = ((2, 20), (1, 25))       # from the knee to the point: 45 px
KNEE = 3                               # the knee's knob, px across
# The coat: short spines all over the test above its mouth, the longer ones among them, sockets spread evenly
# (a golden-angle spiral), none on the bare crown round the star's middle nor on the soft skin round the mouth.
COAT_TRIES = 84
COAT_LOW, COAT_HIGH = -38.0, 71.0   # degrees: from just under its widest to the crown's edge
# The mouth: five teeth under the test's middle, in a small ring, closing to a point.
TEETH = 5
TOOTH_RING = 2.2


def dir_of(az, el):
    """A way from the test's middle, (forward, up, left), from its azimuth (from forward toward the left) and its
    elevation (degrees)."""
    a, e = math.radians(az), math.radians(el)
    return (math.cos(e) * math.cos(a), math.sin(e), math.cos(e) * math.sin(a))


def to_model(fwd, up, left):
    """The test's frame (forward, up, left) to model pixels (x left, y down, z back)."""
    return (left, -up, -fwd)


def surface(d):
    """Where the way d (forward, up, left; unit) leaves the test's boxes: the distance out from its middle (px)."""
    x, y, z = to_model(*d)
    best = 0.0
    for b in TEST:
        ox, oy, oz = b["origin"]
        sx, sy, sz = b["size"]
        t = float("inf")
        for o, s, v in ((ox, sx, x), (oy, sy, y), (oz, sz, z)):
            if abs(v) < 1e-9: continue
            t = min(t, ((o + s) if v > 0 else o) / v)
        best = max(best, t)
    return best


def socket(az, el, sink):
    """A socket on the test's surface at (az, el), sunk `sink` px into it: its place in model pixels."""
    d = dir_of(az, el)
    r = surface(d) - sink
    return to_model(d[0] * r, d[1] * r, d[2] * r)


def rod(name, segs, knob, tip_box=None, pivot=(0, 0, 0)):
    """A rod part along +y: its segments (thickness, length) end to end from its root, a knob round its root (knob px
    across, inflated a quarter so it covers the rod's end), and any extra box."""
    boxes, y = [], 0.0
    for thick, length in segs:
        h = thick / 2.0
        boxes.append(B("rod", (-h, y, -h), (thick, length, thick)))
        y += length
    if knob:
        g = knob / 2.0
        boxes.append(B("knob", (-g, -g, -g), (knob, knob, knob), 0.25))
    if tip_box: boxes.append(tip_box)
    return P(name, "root", pivot, (0, 0, 0), boxes)


def coat_sockets():
    """The coat's sockets: (azimuth, elevation, length px) along a golden-angle spiral over the test, kept off the
    walking spines' ring (their knobs sit there), longer and shorter spines mixed."""
    out = []
    golden = math.pi * (3.0 - math.sqrt(5.0))
    rng = random.Random(4417)
    for k in range(COAT_TRIES):
        # evenly over a sphere: heights evenly spaced, turning by the golden angle
        s = 1.0 - 2.0 * (k + 0.5) / COAT_TRIES
        el = math.degrees(math.asin(s))
        az = math.degrees(k * golden) % 360.0
        if not (COAT_LOW <= el <= COAT_HIGH): continue
        # off the walking spines' knobs
        near = min(abs(((az - i * 360.0 / SPINES) + 180.0) % 360.0 - 180.0) for i in range(SPINES))
        if abs(el - SPINE_ELEVATION) < 9.0 and near < 8.0: continue
        length = rng.choice((5, 6, 6, 7, 7, 8, 9, 10))
        out.append((az, el, length))
    return out


COAT = coat_sockets()


def build_parts():
    parts = [P("test", "root", (0, 0, 0), (0, 0, 0), list(TEST))]
    for t in range(TEETH):
        # each tooth: a stout blade and a point; its root on a small ring under the test's middle (pivot, model px)
        a = math.radians(t * 72.0)
        pivot = (math.sin(a) * TOOTH_RING, HALF_H - 0.5, -math.cos(a) * TOOTH_RING)
        parts.append(rod(f"tooth{t}", ((2, 4),), 0, B("tooth_tip", (-0.5, 4, -0.5), (1, 2, 1)), pivot))
    for i in range(SPINES):
        az = i * 360.0 / SPINES
        pivot = socket(az, SPINE_ELEVATION, 1.0)
        # the needle (its root slips into the test as it is pressed), and the boss it turns on: the test's own, so it
        # stays put on the test whatever the needle does
        parts.append(rod(f"base{i}", BASE_SEGS, 0, pivot=pivot))
        parts.append(rod(f"spine{i}", NEEDLE_SEGS, KNEE, pivot=pivot))
        parts.append(P(f"knob{i}", "root", pivot, (0, 0, 0), [B("knob", (-2, -2, -2), (4, 4, 4), 0.25)]))
    for j, (az, el, length) in enumerate(COAT):
        parts.append(rod(f"coat{j}", ((1, length),), 2, pivot=socket(az, el, 0.5)))
    return parts


PARTS = build_parts()


def pack(parts):
    """The texture's layout: the test, one tooth, one walking spine and one coat spine of each length get their own
    places (creatures.pack); every other tooth and spine shares its twin's, so the ring reads as one creature's."""
    first = {}
    proto = []
    for p in parts:
        key = p["name"].rstrip("0123456789")
        if key == "coat": key += str(int(p["boxes"][0]["size"][1]))
        if key in first: continue
        first[key] = p
        proto.append(p)
    cr.pack(proto, TEX_W, TEX_H)
    for p in parts:
        key = p["name"].rstrip("0123456789")
        if key == "coat": key += str(int(p["boxes"][0]["size"][1]))
        src = first[key]
        if src is p: continue
        for b, s in zip(p["boxes"], src["boxes"]):
            b["uv"] = list(s["uv"])
    return proto


# ================================================================================================ painting
# How much of a glowing pixel's colour the texture keeps under it (the overlay, added on top, brings the rest).
UNDER = 0.12
# The test: matte near-black, a little blue-violet; its plates' seams a shade lighter, a faint cool sheen up top.
SHELL = [cr.C(c)[:3] for c in ("#08070B", "#0D0C12", "#121118", "#17151E", "#1D1A25")]
SEAM = cr.C("#25212E")[:3]
# The star's pores: a shade paler than the plates, dull (they do not glow).
PORE = [cr.C(c)[:3] for c in ("#2E2A38", "#3A3546")]
SHEEN = cr.C("#2F2B3A")[:3]
TUBERCLE = cr.C("#2A2633")[:3]
# The soft skin round its mouth, and its teeth (a cool pale grey, glossy, paler at their points).
SKIN = [cr.C(c)[:3] for c in ("#140F18", "#1B1520", "#231B29")]
TOOTH = [cr.C(c)[:3] for c in ("#5E6274", "#7C8194", "#9DA2B4", "#C3C7D4")]
# The spines: black and glossy, a fine sheen down one side.
SPINE = [cr.C(c)[:3] for c in ("#0A090D", "#110F15", "#18161E")]
SPINE_SHEEN = cr.C("#2B2836")[:3]
# Where the star's five rays run round the test (degrees from forward), and how far down its sides they reach.
RAYS = tuple(18.0 + 72.0 * k for k in range(5))
RAY_LOW = -24.0


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


def wrap(a):
    return (a + 180.0) % 360.0 - 180.0


def test_texel(cv, x, y, z, nz, rng):
    """The test's colour at model point (x, y, z): the star's rays of pale pores from the crown down its sides, the
    apical ring at the very top, plates and their seams between the rays, the soft skin round the mouth below."""
    fwd, up, left = -z, -y, x
    horiz = math.hypot(fwd, left)
    el = math.degrees(math.atan2(up / HALF_H, max(1e-6, horiz) / HALF_W))
    az = math.degrees(math.atan2(left, fwd)) % 360.0
    r = math.sqrt(horiz * horiz + up * up)
    # the crown: the apical ring (five pale pores round a dark middle) at the very top
    if up >= HALF_H - 0.01:
        rr = horiz
        if rr < 1.3: return SHELL[0]
        if rr < 3.2:
            near = min(abs(wrap(az - a)) for a in RAYS)
            if near < 22.0: return PORE[1]
            return SHELL[1]
    # the soft skin round its mouth, under it
    if el < -52.0:
        k = (el + 90.0) / 38.0
        if horiz < 4.2: return SKIN[0]
        return SKIN[min(2, int(nz * 2.0 + k))]
    # the star's rays: a double row of pores down each, from the crown to under its widest
    if el > RAY_LOW:
        ring = max(1.0, horiz)                         # how far out from the test's axis the point lies (px)
        for a in RAYS:
            off = wrap(az - a) * math.pi / 180.0 * ring  # px across the ray
            if abs(off) > 2.6: continue
            # pores in pairs either side of the ray's line, at intervals down it
            along = (90.0 - el) / 11.0
            bead = along - math.floor(along)
            row = 0.6 < abs(off) < 2.0
            if row and 0.2 < bead < 0.8:
                if el > 30.0: return PORE[1] if abs(off) < 1.4 and bead < 0.6 else PORE[0]
                return PORE[0]
            if abs(off) <= 0.6 and el > 40.0 and bead > 0.75: return SEAM
            return SEAM if abs(off) < 0.6 else SHELL[2]
    # plates between the rays: a grain of tubercles and a seam down the middle of each
    mid_off = min(abs(wrap(az - a - 36.0)) for a in RAYS) * math.pi / 180.0 * max(1.0, horiz)
    if mid_off < 0.5 and el > -20.0: return SEAM
    if rng.random() < 0.08: return TUBERCLE
    lit = max(0.0, min(1.0, (el + 10.0) / 80.0))
    k = nz * 0.7 + lit * 0.45
    if k > 0.98: return SHEEN
    return SHELL[min(len(SHELL) - 1, int(k * len(SHELL)))]


def paint_box(cv, part, box, rng):
    mat = box["mat"]
    for face, (x0, y0, fw, fh) in faces(box).items():
        if fw == 0 or fh == 0: continue
        n = cr.noise2(rng, max(1, fw), max(1, fh), 2.0)
        for j in range(fh):
            for i in range(fw):
                if mat == "test":
                    c = test_texel(cv, *texel_point(box, face, i, j), n[j][i], rng)
                else:
                    c = texel(part, mat, face, i, j, fw, fh, n[j][i], rng, box)
                if c is not None: cv.put(x0 + i, y0 + j, c)


def spine_shade(nz, face, rng):
    if face == "east" and nz > 0.55: return SPINE_SHEEN
    if rng.random() < 0.05: return SPINE[2]
    return SPINE[0] if nz < 0.5 else SPINE[1]


def texel(part, mat, face, i, j, fw, fh, nz, rng, box):
    top, bottom = face == "top", face == "bottom"
    base_y = box["origin"][1]
    if part.startswith("tooth"):
        if mat == "tooth_tip": return TOOTH[3] if bottom or j >= fh - 1 else TOOTH[2]
        if top: return TOOTH[0]
        return TOOTH[2] if face in ("north", "east") and nz > 0.4 else TOOTH[1]
    if mat == "knob":
        # the ball joints (its socket's boss on the test, its knee): black, a dull sheen on the crown
        if top: return SPINE_SHEEN if nz > 0.6 else SPINE[2]
        return SPINE[1]
    # a spine's shaft: black and glossy, ringed with neon bands (Taran's card: "Bands down every spine")
    length = sum(b["size"][1] for b in PART_BY_NAME[part]["boxes"] if b["mat"] == "rod")
    # the texel's place along the rod (px from its root, at the texel's middle): its end caps at the box's ends
    y = base_y + (box["size"][1] - 0.5 if bottom else (0.5 if top else j + 0.5))
    from_tip = length - y
    band = spine_band(part, y, from_tip, length)
    if band is not None: return band
    if top or bottom: return SPINE[1]
    return spine_shade(nz, face, rng)


# A walking spine's needle: its point, then a band every NEEDLE_BAND px up it (three px wide, brightest in the middle);
# its base: one band round its middle. (Texels sit at half pixels along a rod, so the bands' middles do too.)
NEEDLE_BAND = 7.0


def spine_band(part, y, from_tip, length):
    """The neon shade at y (px from the root; from_tip from the point) along a spine, or None between its bands."""
    if part.startswith("spine"):
        if from_tip < 1.0: return "hot"
        if from_tip < 2.0: return "neon"
        k = round((from_tip - 0.5) / NEEDLE_BAND)
        c = k * NEEDLE_BAND + 0.5
        if k >= 1 and c < length - 3.0:
            off = abs(from_tip - c)
            if off < 0.5: return "neon"
            if off < 1.5: return "mid"
        return None
    if part.startswith("base"):
        off = abs(y - (math.floor(length * 0.5) + 0.5))
        if off < 0.5: return "neon"
        if off < 1.5: return "mid"
        return None
    # a coat spine: its point, and on the longer ones a band a little down it
    if from_tip < 1.0: return "neon"
    if length >= 7 and abs(from_tip - 3.5) < 0.5: return "mid"
    return None


PART_BY_NAME = {p["name"]: p for p in PARTS}


def paint(neon, seed=7301):
    cv = Canvas(neon)
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


PACKED = pack(PARTS)


def write(assets, write_json):
    """The urchin's geometry, a texture and a glow overlay for each neon colour and its spawn egg's model; returns its
    lang entries."""
    write_json(os.path.join(assets, "geometry", NAME + ".json"), geometry_json(PARTS))
    tex_dir = os.path.join(assets, "textures", "entity", NAME)
    os.makedirs(tex_dir, exist_ok=True)
    for neon in T.NEONS:
        # one pattern for all four colours (the same seed), so they read as one creature in four colours
        cv = paint(neon, 7301)
        assert cv.glow.getbbox(), f"{NAME}: no glowing pixels"
        cv.img.save(os.path.join(tex_dir, f"{NAME}_{neon}.png"))
        cv.glow.save(os.path.join(tex_dir, f"{NAME}_{neon}_glow.png"))
    write_json(os.path.join(assets, "models", "item", NAME + "_spawn_egg.json"), {"parent": "minecraft:item/template_spawn_egg"})
    return {f"entity.{MOD}.{NAME}": "Vent Urchin", f"item.{MOD}.{NAME}_spawn_egg": "Vent Urchin Spawn Egg",
            f"item.{MOD}.{NAME}_fang": "Vent Urchin Spine"}


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    from PIL import ImageChops
    print(f"{len(PARTS)} parts, {len(COAT)} coat spines, {sum(len(p['boxes']) for p in PARTS)} boxes")
    for neon in T.NEONS:
        cv = paint(neon, 7301)
        lit = ImageChops.add(cv.img, cv.glow)
        lit.putalpha(cv.img.getchannel("A"))
        for name, im in (("lit", lit), ("dark", cv.img)):
            big = im.resize((TEX_W * 4, TEX_H * 4), Image.NEAREST)
            bg = Image.new("RGBA", big.size, (60, 64, 70, 255))
            Image.alpha_composite(bg, big).save(os.path.join(out, f"tex_{neon}_{name}.png"))
