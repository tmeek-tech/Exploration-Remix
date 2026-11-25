"""The Hollow Choir's blocks (0.21.9): their textures, models and blockstates.

Five blocks, none of which fits gen_data.py's plant and rock kinds, so they are written here instead:

  reliquary      the casket at an ancient city's heart, holding the one choir key that city has. Horizontal facing and
                 an `open` property: shut, a pale blue line of light runs along the seam of its lid; open, the lid is
                 tilted back and the inside is empty and dark.
  key_socket     one of the three cradles round the arena's rim, `filled` false or true.
  choir_brazier  the arena's shallow bowl of cold flame, `lit` true or false; lit, a flame a block tall stands out of
                 it, so the twelve of them read from across a cavern.
  choir_sprout   the rooted sprout the Choir raises out of the sculk.
  choir_bell     the trophy a beaten Choir leaves, horizontal facing, lit from inside.

Everything is worked deepslate, so the stone is painted from one dark grey ramp with the same crazed grain as the
arena's tiles, and every lit pixel is one of the Choir's two colours only (GLOW light blue, BONE off-white; Taran's
standing rules: nothing warm, no cyan). The lit pixels are listed in GLOW_PIXELS so gen_data's glow pass lifts them
into <name>_glow.png, which the game draws added over the texture.

Called by gen_data.main; `python3 tools/choir_blocks.py` writes into src/main/resources on its own.
"""
import math
import os
import random

from PIL import Image

MOD = "explorationremix"
# the Choir's two colours, the same numbers as block/ChoirLook.java
GLOW = "#8EC2FF"
BONE = "#F0EFF4"
# the worked deepslate the arena and the caskets are cut from: dark, cool, five steps
STONE = ["#1B1B21", "#23232B", "#2B2B34", "#34343E", "#3D3D48"]
# the dark inside of an opened casket and an unlit brazier's ash
VOID = "#0E0E12"
ASH = "#45454E"

# texture -> the hex colours of its pixels that glow (gen_data lifts these into <name>_glow.png)
GLOW_PIXELS = {
    "reliquary_top": [GLOW, BONE],
    "reliquary_side": [GLOW],
    "reliquary_front": [GLOW, BONE],
    "key_socket_top_filled": [GLOW, BONE],
    "key_socket_side_filled": [GLOW],
    "choir_brazier_top": [GLOW, BONE],
    "choir_brazier_side": [GLOW],
    "choir_sprout": [GLOW, BONE],
    "choir_bell_side": [GLOW, BONE],
    "choir_bell_top": [GLOW],
}


def hexc(h):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255


def blank():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def stone_face(seed, ramp=None, edge=True):
    """A 16 by 16 face of worked deepslate: a crazed grain over the ramp, with a chiselled edge."""
    ramp = ramp or STONE
    rng = random.Random(seed)
    img = Image.new("RGBA", (16, 16), hexc(ramp[2]))
    px = img.load()
    # value noise on a 4-block lattice, so the grain is patchy rather than speckled
    lat = [[rng.random() for _ in range(5)] for _ in range(5)]
    for y in range(16):
        for x in range(16):
            fx, fy = x / 4.0, y / 4.0
            i, k = int(fx), int(fy)
            tx, ty = fx - i, fy - k
            tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
            a = lat[i][k] * (1 - tx) + lat[i + 1][k] * tx
            b = lat[i][k + 1] * (1 - tx) + lat[i + 1][k + 1] * tx
            v = a * (1 - ty) + b * ty + (rng.random() - 0.5) * 0.18
            px[x, y] = hexc(ramp[max(0, min(len(ramp) - 1, int(v * len(ramp))))])
    if edge:
        for i in range(16):
            px[i, 0] = hexc(ramp[-1])
            px[0, i] = hexc(ramp[-1])
            px[i, 15] = hexc(ramp[0])
            px[15, i] = hexc(ramp[0])
    return img


def _ring(px, cx, cy, r, colour, width=1.0):
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if abs(d - r) <= width / 2:
                px[x, y] = hexc(colour)


def _disc(px, cx, cy, r, colour):
    for y in range(16):
        for x in range(16):
            if math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= r:
                px[x, y] = hexc(colour)


# ------------------------------------------------------------------------------------------------ the reliquary

def reliquary_top(open_lid):
    """The lid, seen from above: a chiselled frame round a seam. Shut the seam is lit; open it is the empty inside."""
    img = stone_face(101)
    px = img.load()
    for x in range(2, 14):
        px[x, 2] = hexc(STONE[4])
        px[x, 13] = hexc(STONE[0])
    for y in range(2, 14):
        px[2, y] = hexc(STONE[4])
        px[13, y] = hexc(STONE[0])
    if open_lid:
        for y in range(4, 12):
            for x in range(4, 12):
                px[x, y] = hexc(VOID)
        for x in range(4, 12):
            px[x, 4] = hexc(STONE[1])
    else:
        for x in range(4, 12):
            px[x, 7] = hexc(GLOW)
            px[x, 8] = hexc(GLOW)
        px[7, 7] = hexc(BONE)
        px[8, 8] = hexc(BONE)
    return img


def reliquary_side(open_lid):
    """The casket's side: a plinth, a band, and the lid's line. Shut, light leaks out of the line."""
    img = stone_face(102)
    px = img.load()
    for x in range(16):
        px[x, 15] = hexc(STONE[0])
        px[x, 14] = hexc(STONE[1])
        px[x, 5] = hexc(STONE[4])
    if not open_lid:
        for x in range(2, 14):
            px[x, 6] = hexc(GLOW)
    else:
        for x in range(2, 14):
            px[x, 6] = hexc(VOID)
    for x in range(3, 13, 3):
        px[x, 9] = hexc(STONE[4])
        px[x, 10] = hexc(STONE[0])
    return img


def reliquary_front(open_lid):
    """The front, which the key's own mark is cut into."""
    img = reliquary_side(open_lid)
    px = img.load()
    mark = BONE if not open_lid else STONE[4]
    for y in range(8, 13):
        px[8, y] = hexc(mark)
    px[7, 9] = hexc(mark)
    px[9, 9] = hexc(mark)
    if not open_lid:
        px[8, 8] = hexc(GLOW)
    return img


# ------------------------------------------------------------------------------------------------ the key socket

def key_socket_top(filled):
    """The cradle, from above: a round hollow. Filled, the key lies in it and the hollow glows."""
    img = stone_face(111)
    px = img.load()
    _ring(px, 8, 8, 5.5, STONE[4], 1.4)
    _disc(px, 8, 8, 4.2, VOID)
    if filled:
        _disc(px, 8, 8, 3.0, GLOW)
        for y in range(5, 11):
            px[8, y] = hexc(BONE)
        px[7, 6] = hexc(BONE)
        px[9, 6] = hexc(BONE)
        px[9, 9] = hexc(BONE)
    return img


def key_socket_side(filled):
    img = stone_face(112)
    px = img.load()
    for x in range(16):
        px[x, 15] = hexc(STONE[0])
        px[x, 4] = hexc(STONE[4])
    for x in range(2, 14, 4):
        for y in range(6, 13):
            px[x, y] = hexc(STONE[1])
    if filled:
        for x in range(4, 12):
            px[x, 5] = hexc(GLOW)
    return img


# -------------------------------------------------------------------------------------------------- the brazier

def choir_brazier_top(lit):
    """The bowl from above: a rim, and either the cold flame or grey ash."""
    img = stone_face(121)
    px = img.load()
    _ring(px, 8, 8, 6.2, STONE[4], 1.6)
    _disc(px, 8, 8, 5.0, VOID)
    if lit:
        _disc(px, 8, 8, 4.0, GLOW)
        _disc(px, 8, 8, 1.4, BONE)
        for d in ((0, -5), (5, 0), (0, 5), (-5, 0)):
            px[8 + d[0], 8 + d[1]] = hexc(GLOW)
    else:
        _disc(px, 8, 8, 4.0, ASH)
        rng = random.Random(1210)
        for _ in range(14):
            a, r = rng.random() * math.tau, rng.random() * 3.4
            px[int(8 + r * math.cos(a)), int(8 + r * math.sin(a))] = hexc(STONE[0])
    return img


def choir_brazier_flame():
    """
    The cold flame, drawn once and stood up over the bowl as a crossed pair of quads.

    The bowl's own lit pixels are ten across and read as nothing from the far side of a cavern, which is the whole
    hint a player gets that an arena is down there. A flame a block tall carries. It is drawn to fill the texture and
    the model narrows it, so the shape here is wider than it looks in the world: wide where it leaves the bowl, widest
    a third of the way up, then drawn out to a point, with a little lean so it is not a leaf.
    """
    img = blank()
    px = img.load()
    for y in range(1, 16):
        u = (15 - y) / 14.0                              # 0 at the bowl, 1 at the tip
        half = 4.6 + 1.8 * (u / 0.25) if u < 0.25 else 6.4 * (1 - (u - 0.25) / 0.75) ** 0.8
        cx = 8 + 1.1 * math.sin(u * 3.2)                 # the lean
        core = half - 3.0 if u < 0.3 else -1          # a small white heart only where it leaves the bowl
        for x in range(16):
            d = abs(x + 0.5 - cx)
            if d > half:
                continue
            px[x, y] = hexc(BONE if d <= core else GLOW)
    return img


def choir_brazier_side(lit):
    img = stone_face(122)
    px = img.load()
    for y in range(0, 9):
        for x in range(16):
            px[x, y] = (0, 0, 0, 0)
    for x in range(16):
        px[x, 9] = hexc(STONE[4])
        px[x, 15] = hexc(STONE[0])
    for x in range(0, 16, 5):
        for y in range(10, 15):
            px[x, y] = hexc(STONE[1])
    if lit:
        for x in range(2, 14, 3):
            px[x, 10] = hexc(GLOW)
    return img


# --------------------------------------------------------------------------------------------------- the sprout

def choir_sprout():
    """A bone stem with a pale blue bud, drawn as a cross plant."""
    img = blank()
    px = img.load()
    for y in range(6, 16):
        px[7, y] = hexc(BONE)
        px[8, y] = hexc("#D8D4C8")
    px[6, 11] = hexc(BONE)
    px[9, 9] = hexc(BONE)
    px[6, 12] = hexc("#D8D4C8")
    px[9, 10] = hexc("#D8D4C8")
    for y in range(3, 7):
        for x in range(6, 10):
            px[x, y] = hexc(GLOW)
    px[5, 4] = hexc(GLOW)
    px[10, 4] = hexc(GLOW)
    px[7, 2] = hexc(BONE)
    px[8, 2] = hexc(BONE)
    return img


# ----------------------------------------------------------------------------------------------- the bell trophy

def choir_bell_side():
    """The trophy: a bell in a deepslate frame, lit from inside."""
    img = blank()
    px = img.load()
    for y in range(1, 16):
        px[1, y] = hexc(STONE[3])
        px[14, y] = hexc(STONE[3])
    for x in range(1, 15):
        px[x, 1] = hexc(STONE[4])
        px[x, 15] = hexc(STONE[0])
    # the bell: a skirt widening downwards
    for y in range(4, 13):
        half = 2 + (y - 4) * 4 // 9
        for x in range(8 - half, 8 + half):
            px[x, y] = hexc(BONE if (x + y) % 5 else "#D8D4C8")
    for y in range(8, 12):
        for x in range(7, 9):
            px[x, y] = hexc(GLOW)
    px[7, 3] = hexc(STONE[4])
    px[8, 3] = hexc(STONE[4])
    px[7, 13] = hexc(GLOW)
    px[8, 13] = hexc(GLOW)
    return img


def choir_bell_top():
    img = stone_face(131)
    px = img.load()
    _ring(px, 8, 8, 4.5, STONE[4], 1.4)
    _disc(px, 8, 8, 3.2, VOID)
    _disc(px, 8, 8, 2.0, GLOW)
    return img


# ------------------------------------------------------------------------------------------------- what is written

def textures():
    """name -> image, for src/main/resources/assets/<mod>/textures/block."""
    return {
        "reliquary_top": reliquary_top(False),
        "reliquary_top_open": reliquary_top(True),
        "reliquary_side": reliquary_side(False),
        "reliquary_side_open": reliquary_side(True),
        "reliquary_front": reliquary_front(False),
        "reliquary_front_open": reliquary_front(True),
        "key_socket_top": key_socket_top(False),
        "key_socket_top_filled": key_socket_top(True),
        "key_socket_side": key_socket_side(False),
        "key_socket_side_filled": key_socket_side(True),
        "choir_brazier_top": choir_brazier_top(True),
        "choir_brazier_top_unlit": choir_brazier_top(False),
        "choir_brazier_side": choir_brazier_side(True),
        "choir_brazier_flame": choir_brazier_flame(),
        "choir_brazier_side_unlit": choir_brazier_side(False),
        "choir_sprout": choir_sprout(),
        "choir_bell_side": choir_bell_side(),
        "choir_bell_top": choir_bell_top(),
    }


# A glowing overlay plane is drawn at full block light, as gen_data's own *_glow templates do (Forge's forge_data), so
# the lit pixels read in a pitch-dark cavern even with shaders off.
EMISSIVE = {"forge_data": {"block_light": 15}, "shade": False}


def _faces(side, top, bottom):
    out = {}
    for d in ("north", "south", "west", "east"):
        out[d] = {"texture": "#" + side}
    out["up"] = {"texture": "#" + top}
    out["down"] = {"texture": "#" + bottom, "cullface": "down"}
    return out


def _box(textures_map, from_xyz, to_xyz, render="cutout", glow=False):
    """A single-box block model, with the lit pixels drawn again over it at full light when `glow` is on."""
    f, t = list(from_xyz), list(to_xyz)
    elements = [{"from": f, "to": t, "faces": _faces("side", "top", "bottom")}]
    if glow:
        # a hair outside the box, so the overlay never fights the face under it for depth
        g0 = [v - 0.002 for v in f]
        g1 = [v + 0.002 for v in t]
        elements.append(dict({"from": g0, "to": g1,
                              "faces": _faces("side_glow", "top_glow", "bottom_glow")}, **EMISSIVE))
    return {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:" + render,
        "textures": textures_map,
        "elements": elements,
    }


def write_all(assets, write):
    """Writes the textures, the models and the blockstates. Returns the lang names of what it made."""
    import textures as T

    tex_dir = os.path.join(assets, "textures", "block")
    os.makedirs(tex_dir, exist_ok=True)
    for name, img in textures().items():
        if name in GLOW_PIXELS:
            overlay = T.glow_layer(img, set(GLOW_PIXELS[name]))
            assert overlay.getbbox() is not None, f"{name}: none of its glow colours is in the texture"
            overlay.save(os.path.join(tex_dir, name + "_glow.png"))
        img.save(os.path.join(tex_dir, name + ".png"))

    b = lambda n: f"{MOD}:block/{n}"
    models = os.path.join(assets, "models")
    states = os.path.join(assets, "blockstates")

    def model(name, obj): write(os.path.join(models, "block", name + ".json"), obj)

    def item_model(name, obj): write(os.path.join(models, "item", name + ".json"), obj)

    def state(name, obj): write(os.path.join(states, name + ".json"), obj)

    # ---- the reliquary: a casket 12 wide and 11 tall, lid shut or tilted back
    for tag in ("", "_open"):
        t = {"particle": b("reliquary_side" + tag), "side": b("reliquary_side" + tag),
             "top": b("reliquary_top" + tag), "bottom": b("reliquary_side" + tag)}
        lit = tag == ""
        if lit:
            t.update({"side_glow": b("reliquary_side_glow"), "top_glow": b("reliquary_top_glow"),
                      "bottom_glow": b("reliquary_side_glow")})
        model("reliquary" + tag, _box(t, (2, 0, 2), (14, 11, 14), glow=lit))
    # the front face carries the key's mark, so the model is turned by the blockstate instead of swapping textures
    state("reliquary", {"variants": {
        f"open={str(o).lower()},facing={f}": {"model": b("reliquary" + ("_open" if o else "")), "y": y}
        for o in (False, True)
        for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))
    }})

    # ---- the key socket: a cradle 14 wide and 11 tall
    for suffix in ("", "_filled"):
        t = {"particle": b("key_socket_side" + suffix), "side": b("key_socket_side" + suffix),
             "top": b("key_socket_top" + suffix), "bottom": b("key_socket_side" + suffix)}
        lit = suffix == "_filled"
        if lit:
            t.update({"side_glow": b("key_socket_side_filled_glow"), "top_glow": b("key_socket_top_filled_glow"),
                      "bottom_glow": b("key_socket_side_filled_glow")})
        model("key_socket" + suffix, _box(t, (1, 0, 1), (15, 11, 15), glow=lit))
    state("key_socket", {"variants": {
        "filled=false": {"model": b("key_socket")},
        "filled=true": {"model": b("key_socket_filled")},
    }})

    # ---- the brazier: a shallow bowl 10 wide and 7 tall, the lit one with a flame a block tall standing out of it
    flame_turn = {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": True}
    flame = [dict({"from": [4.5, 5, 8], "to": [11.5, 17, 8], "rotation": flame_turn, "shade": False,
                   "faces": {d: {"uv": [0, 0, 16, 16], "texture": "#flame"} for d in ("north", "south")}}, **EMISSIVE),
             dict({"from": [8, 5, 4.5], "to": [8, 17, 11.5], "rotation": flame_turn, "shade": False,
                   "faces": {d: {"uv": [0, 0, 16, 16], "texture": "#flame"} for d in ("west", "east")}}, **EMISSIVE)]
    for suffix in ("", "_unlit"):
        t = {"particle": b("choir_brazier_side" + suffix), "side": b("choir_brazier_side" + suffix),
             "top": b("choir_brazier_top" + suffix), "bottom": b("choir_brazier_side" + suffix)}
        lit = suffix == ""
        if lit:
            t.update({"side_glow": b("choir_brazier_side_glow"), "top_glow": b("choir_brazier_top_glow"),
                      "bottom_glow": b("choir_brazier_side_glow"), "flame": b("choir_brazier_flame")})
        bowl = _box(t, (3, 0, 3), (13, 7, 13), glow=lit)
        if lit:
            bowl["elements"].extend(flame)
            bowl["ambientocclusion"] = False
        model("choir_brazier" + suffix, bowl)
    state("choir_brazier", {"variants": {
        "lit=true": {"model": b("choir_brazier")},
        "lit=false": {"model": b("choir_brazier_unlit")},
    }})
    item_model("choir_brazier", {"parent": b("choir_brazier")})

    # ---- the sprout: a cross plant
    turn = {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": True}
    def planes(key, extra):
        return [dict({"from": [0.8, 0, 8], "to": [15.2, 16, 8], "rotation": turn, "shade": False,
                      "faces": {"north": {"uv": [0, 0, 16, 16], "texture": key},
                                "south": {"uv": [0, 0, 16, 16], "texture": key}}}, **extra),
                dict({"from": [8, 0, 0.8], "to": [8, 16, 15.2], "rotation": turn, "shade": False,
                      "faces": {"west": {"uv": [0, 0, 16, 16], "texture": key},
                                "east": {"uv": [0, 0, 16, 16], "texture": key}}}, **extra)]
    model("choir_sprout", {"ambientocclusion": False, "render_type": "minecraft:cutout",
                           "textures": {"particle": b("choir_sprout"), "cross": b("choir_sprout"),
                                        "glow": b("choir_sprout_glow")},
                           "elements": planes("#cross", {}) + planes("#glow", EMISSIVE)})
    state("choir_sprout", {"variants": {"": {"model": b("choir_sprout")}}})

    # ---- the bell trophy: a framed bell 12 wide and 15 tall
    model("choir_bell", _box({
        "particle": b("choir_bell_side"), "side": b("choir_bell_side"),
        "top": b("choir_bell_top"), "bottom": b("choir_bell_top"),
        "side_glow": b("choir_bell_side_glow"), "top_glow": b("choir_bell_top_glow"),
        "bottom_glow": b("choir_bell_top_glow"),
    }, (2, 0, 2), (14, 15, 14), glow=True))
    state("choir_bell", {"variants": {
        f"facing={f}": {"model": b("choir_bell"), "y": y}
        for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))
    }})
    item_model("choir_bell", {"parent": f"{MOD}:item/choir_bell_flat"})
    # the bell's item is the painted item texture the creature art makes, not the block
    item_model("choir_bell_flat", {"parent": "item/generated", "textures": {"layer0": f"{MOD}:item/choir_bell"}})
    item_model("choir_key", {"parent": "item/generated", "textures": {"layer0": f"{MOD}:item/choir_key"}})

    return {
        f"block.{MOD}.reliquary": "Reliquary",
        f"block.{MOD}.key_socket": "Key Socket",
        f"block.{MOD}.choir_brazier": "Choir Brazier",
        f"block.{MOD}.choir_sprout": "Choir Sprout",
        f"block.{MOD}.choir_bell": "Hollow Bell",
        f"item.{MOD}.choir_key": "Choir Key",
    }


if __name__ == "__main__":
    import json

    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(here, "src", "main", "resources", "assets", MOD)

    def _write(path, obj):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            json.dump(obj, f, indent=2)
            f.write("\n")

    print(write_all(res, _write))
