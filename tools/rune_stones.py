"""The rune stones (0.23.1): a hidden block in each layer that frees one or two of the layer's runes when it is broken.

Taran, 2026-11-26: "Can you add a rune block for each layer that drops 1-2 runes when broken? It should have a texture
that blends into the blocks it's placed in. Put the Galleries runes in the ruins, the Gardens runes in vents, the
Reaches runes in ponds and ancient cities, and the Upper Caves runes in strongholds."

Each stone is the block it hides in (its state's host: stone bricks in a stronghold, deepslate bricks or tiles in an
ancient city or a Galleries ruin, deepslate on a pond's bed, blackstone in a vent's chamber), drawn as vanilla draws that
block, with a small rune carved into each face as a cutout overlay and the carving's strokes glowing faintly light blue
over it at full brightness (Taran's card pick "A carved rune glowing faintly"). The carving keeps to one brick or one
tile of the host's pattern, never across a joint, and is drawn in the host's own darkest and lightest shades, so up close
it reads as cut into that very stone; at a distance the mipmaps fade it out and the stone is the wall.

The overlays are painted here from each host's few shades (the colours vanilla's texture is made of, listed below), so no
vanilla picture is needed or copied: a groove is the host's darkest shade or the one over it, the lip on its lit side
(down and to the right, as the game's light falls from the top left) its lightest. Four looks per host, picked by the
block's position, each with a different rune on its sides and on its top and bottom.

While a stone is dug, and when another one near it breaks, its carving lights up from inside: each face's `_lit`
texture holds that light exactly, added over the face by the client (client.rune.stone.RuneStoneClient, scaled by how
far the dig has got): the strokes' heart white, the strokes light blue, a soft light blue bleed a few pixels round
them, so the light grows brighter without turning cyan. Clear everywhere else: the client adds the light by its alpha,
since Embeddium fills a texture's clear pixels with its average colour. The atlas takes them from textures/block/ on
its own.

Writes the textures, the models (on one template: the rock, the carving, the glow), the blockstates (property host, the
four looks as random variants), the items, the loot tables (one rune for certain and a second one time in three,
whatever the pickaxe: until 0.24.5 a Bounty pickaxe made it half the time, as Fortune II; the Galleries' stones roll
the deepest layers most, as the ruins' rune chest did until 0.24.5, when Taran's card took its rune out and left the
ruins' runes to the stones) and the English names. Called by gen_data.main.

python3 tools/rune_stones.py --sheet OUT.png [VANILLA_BLOCK_TEXTURES] draws every look lit and in the dark, close up and
set in a wall of its host, for review (the walls need vanilla's textures, from a folder or the game's client jar).
"""
import os
import random
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from plants import hexc

MOD = "explorationremix"

# ============================================================================================== the hosts
# host -> (its vanilla block, its side texture, its end texture (top and bottom), its shades darkest first, the panels
# a carving may take on its side and on its end). A panel is (x0, y0, x1, y1), inclusive: one brick's or one tile's face
# inside its edges, so a rune never crosses a joint. Brick and tile faces are only four or five pixels high.
_BRICK = [(2, 1, 12, 5), (1, 9, 5, 13), (9, 9, 14, 13)]
# deepslate tiles: the wide tile over the first joint (4 rows), the two in the second row and the long one in the third
# (3 rows); the fourth row's tiles are two pixels high and are left bare
_TILE = [(5, 1, 13, 4), (0, 6, 7, 8), (9, 6, 15, 8), (5, 10, 15, 12)]
_OPEN = [(2, 2, 13, 13)]
STONE_SHADES = ["#5A595A", "#636363", "#6A6D6A", "#787678", "#7F7F7F", "#8B898B", "#9C999C"]
DEEP_WORKED = ["#242424", "#2D2D2D", "#383737", "#414141", "#4B4C4F", "#585858", "#6E6E6E"]
HOSTS = {
    "stone_bricks": ("minecraft:stone_bricks", "stone_bricks", "stone_bricks", STONE_SHADES, _BRICK, _BRICK),
    "mossy_stone_bricks": ("minecraft:mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks", STONE_SHADES, _BRICK, _BRICK),
    "cracked_stone_bricks": ("minecraft:cracked_stone_bricks", "cracked_stone_bricks", "cracked_stone_bricks", STONE_SHADES, _BRICK, _BRICK),
    "deepslate": ("minecraft:deepslate", "deepslate", "deepslate_top", ["#2F2F37", "#3D3D43", "#515151", "#646464", "#797979"], _OPEN, _OPEN),
    "deepslate_bricks": ("minecraft:deepslate_bricks", "deepslate_bricks", "deepslate_bricks", DEEP_WORKED, _BRICK, _BRICK),
    "cracked_deepslate_bricks": ("minecraft:cracked_deepslate_bricks", "cracked_deepslate_bricks", "cracked_deepslate_bricks",
                                 ["#1A1A1A"] + DEEP_WORKED, _BRICK, _BRICK),
    "deepslate_tiles": ("minecraft:deepslate_tiles", "deepslate_tiles", "deepslate_tiles", DEEP_WORKED[:6], _TILE, _TILE),
    "cracked_deepslate_tiles": ("minecraft:cracked_deepslate_tiles", "cracked_deepslate_tiles", "cracked_deepslate_tiles", DEEP_WORKED[:6],
                                _TILE, _TILE),
    "polished_deepslate": ("minecraft:polished_deepslate", "polished_deepslate", "polished_deepslate", DEEP_WORKED, _OPEN, _OPEN),
    "blackstone": ("minecraft:blackstone", "blackstone", "blackstone_top", ["#160F10", "#20131C", "#27221C", "#312C36", "#3C3947", "#4E4B54"],
                   _OPEN, _OPEN),
}

# ============================================================================================== the stones
# block id -> (English name, its layer's rune tags with their weights, its hosts, the first its default). The layer
# tags are runes.py's: a layer's runes but its collect runes (Taran's card pick: the layer's own). The Galleries have
# no runes of their own, so theirs roll the three layers as the ruins' rune chest did: the Deep Gardens' most often.
# The block ids and the host lists must match block/RuneStoneKind.java.
STONES = {
    "upper_caves_rune_stone": ("Upper Caves Rune Stone", [("upper_caves", 1)],
                               ["stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks"]),
    "blue_reaches_rune_stone": ("Blue Reaches Rune Stone", [("blue_reaches", 1)],
                                ["deepslate", "deepslate_bricks", "cracked_deepslate_bricks", "deepslate_tiles", "cracked_deepslate_tiles",
                                 "polished_deepslate"]),
    "deep_gardens_rune_stone": ("Deep Gardens Rune Stone", [("deep_gardens", 1)], ["blackstone", "deepslate"]),
    "galleries_rune_stone": ("Galleries Rune Stone", [("deep_gardens", 10), ("blue_reaches", 5), ("upper_caves", 2)],
                             ["deepslate_bricks", "cracked_deepslate_bricks", "deepslate_tiles", "cracked_deepslate_tiles", "polished_deepslate"]),
}
BLOCK_IDS = list(STONES)
LOOKS = 4
# The chance of a second rune: one in three (Taran's card pick), whatever the pickaxe. A Bounty rune (Fortune II,
# ToolRunes) raised it to a half until 0.24.5, when Taran changed their mind: "Can you make bounty not effect rune
# blocks?" So no Fortune counts.
SECOND = 0.3333

# ============================================================================================== the runes carved
# The carving's light: the active runes' light blue (runes.KIND_GLYPH["action"]), its core near white, its dim end. No
# warm colour, no cyan (Taran's standing rules).
MAIN, CORE, DIM = "#8EC2FF", "#E6F2FF", "#4F7FC4"
# The light a carving gives as it is dug, or as it flares answering a broken stone, added over the stone as it is drawn
# (the client's RuneStoneClient lays each face's _lit texture over it additively, by its alpha, at the dig's
# strength): the heart goes white, the light blue strokes paler, the dim ones light blue, the strokes left dark wake up
# dim blue, and the light bleeds into the stone round the strokes, fading over three pixels. Each is the colour to add,
# so a stroke ends where it should: MAIN + LIT_MAIN is #CDE4FF, DIM + LIT_DIM is MAIN. Nothing here leans green: no
# cyan.
LIT_HEART, LIT_MAIN, LIT_DIM, LIT_DARK = (25, 13, 0), (63, 34, 0), (63, 67, 59), (45, 80, 150)
LIT_BLEED, BLEED_REACH = (26, 46, 90), 3.2
# Each rune on its grid: '#' a stroke, '*' its heart (it glows brightest), '+' a faint end, '.' bare stone. Five rows
# for a brick's face or open rock, four for the wide tile, three for the narrow ones. Shapes of their own, not letters.
RUNES5 = [
    ["#.#.#",
     ".#*#.",
     "..#..",
     "..#..",
     "..+.."],
    ["..#..",
     ".#.#.",
     "#.*.#",
     ".#.#.",
     "..#.."],
    ["..#..",
     ".#*#.",
     "#.#.#",
     "..#..",
     "..+.."],
    [".###.",
     "#...#",
     "#.*.#",
     ".#.#.",
     "..#.."],
    ["##.##",
     ".#.#.",
     "..*..",
     ".#.#.",
     "##.##"],
    ["..+..",
     ".#.#.",
     "+.*.+",
     ".#.#.",
     "..+.."],
    ["#..#..",
     ".#.#..",
     "..*###",
     ".#.#..",
     "#..#.."],
    ["..#..",
     ".#.#.",
     ".#*#.",
     ".#.#.",
     "..#.."],
]
RUNES4 = [
    ["..#..",
     ".#*#.",
     "#.#.#",
     "..#.."],
    ["#.#.#",
     ".#*#.",
     "..#..",
     "..+.."],
    [".###.",
     "#.*.#",
     ".###.",
     "..+.."],
    ["#...#..",
     ".#.#.#.",
     "..*...#",
     ".#.#.#."],
]
RUNES3 = [
    [".###.",
     "#.*.#",
     ".###."],
    ["#...#",
     ".#*#.",
     "..#.."],
    [".#...#.",
     "#.#*#.#",
     "...#..."],
    ["+.#.+",
     ".#*#.",
     "+.#.+"],
]


def _fits(rune, panel):
    x0, y0, x1, y1 = panel
    return len(rune) <= y1 - y0 + 1 and len(rune[0]) <= x1 - x0 + 1


def _runes_for(panel):
    h = panel[3] - panel[1] + 1
    pool = RUNES5 if h >= 5 else RUNES4 if h == 4 else RUNES3
    return [r for r in pool if _fits(r, panel)]


def carve(shades, panels, rng, pick):
    """A carving in one of the panels: the cutout overlay (the groove and its lit lip), the glow over it, and the light
    it gives as it is dug (lit). {@code pick} chooses the rune among those that fit (an index, taken round)."""
    over = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    glow = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    op, gp = over.load(), glow.load()
    panel = panels[rng.randrange(len(panels))]
    runes = _runes_for(panel)
    rune = runes[pick % len(runes)]
    h, w = len(rune), len(rune[0])
    x0 = panel[0] + rng.randint(0, panel[2] - panel[0] + 1 - w)
    y0 = panel[1] + rng.randint(0, panel[3] - panel[1] + 1 - h)
    strokes = {(x0 + x, y0 + y): ch for y, row in enumerate(rune) for x, ch in enumerate(row) if ch != "."}
    dark, under, lit = hexc(shades[0]), hexc(shades[1]), hexc(shades[-1])
    for (x, y), ch in strokes.items():
        # the groove: the host's darkest shade, now and then the one over it, so it is worn, not drawn
        op[x, y] = dark if ch == "*" or rng.random() < 0.7 else under
        # its far lip catches the light that falls from the top left
        lx, ly = x + 1, y + 1
        if (lx, ly) not in strokes and lx < 16 and ly < 16 and rng.random() < 0.85:
            op[lx, ly] = lit
    # the light pools at the rune's heart and fades along its strokes: near white at the heart, light blue beside it,
    # dim further out, and here and there a stroke's end left dark, as if the light had worn out of it
    far = _steps_from_heart(strokes)
    for (x, y), ch in strokes.items():
        r, d = rng.random(), far[(x, y)]
        if ch == "*":
            gp[x, y] = hexc(CORE)
        elif ch == "+":
            if r < 0.55:
                gp[x, y] = hexc(DIM)
        elif d <= 1:
            gp[x, y] = hexc(MAIN) if r < 0.8 else hexc(DIM)
        elif d == 2:
            if r < 0.92:
                gp[x, y] = hexc(MAIN) if r < 0.2 else hexc(DIM)
        elif r < 0.75:
            gp[x, y] = hexc(DIM)
    return over, glow, lit_for(strokes, glow)


def lit_for(strokes, glow):
    """The light a carving gives as it is dug (see LIT_*): over each stroke by how it glows, round them bleeding out."""
    lit = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    lp, gp = lit.load(), glow.load()
    core, main = hexc(CORE)[:3], hexc(MAIN)[:3]
    for (x, y) in strokes:
        g = gp[x, y]
        add = LIT_DARK if g[3] == 0 else LIT_HEART if g[:3] == core else LIT_MAIN if g[:3] == main else LIT_DIM
        lp[x, y] = add + (255,)
    for y in range(16):
        for x in range(16):
            if (x, y) in strokes:
                continue
            d = min(((x - sx) ** 2 + (y - sy) ** 2) ** 0.5 for sx, sy in strokes)
            if d <= BLEED_REACH:
                k = 2.718281828 ** (-(d - 1.0) * 0.8)
                lp[x, y] = tuple(int(round(c * k)) for c in LIT_BLEED) + (255,)
    return lit


def _steps_from_heart(strokes):
    """Each stroke pixel's steps from the rune's heart, along its strokes (corners count as touching)."""
    hearts = [p for p, ch in strokes.items() if ch == "*"]
    far = {p: 99 for p in strokes}
    todo = list(hearts)
    for p in hearts:
        far[p] = 0
    while todo:
        x, y = todo.pop(0)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                q = (x + dx, y + dy)
                if q in far and far[q] > far[(x, y)] + 1:
                    far[q] = far[(x, y)] + 1
                    todo.append(q)
    return far


def looks():
    """Every host's looks: host -> [(side, side_glow, side_lit, end, end_glow, end_lit) x LOOKS]. Deterministic."""
    out = {}
    for hi, (host, (_, _, _, shades, side_panels, end_panels)) in enumerate(HOSTS.items()):
        row = []
        for v in range(LOOKS):
            rng = random.Random(9100 + 37 * hi + v)
            side, side_glow, side_lit = carve(shades, side_panels, rng, 3 * v + hi)
            end, end_glow, end_lit = carve(shades, end_panels, rng, 3 * v + hi + 2)
            row.append((side, side_glow, side_lit, end, end_glow, end_lit))
        out[host] = row
    return out


def tex(host, v, part):
    return f"rune_stone/{host}_{v}_{part}"


# ============================================================================================== the data
def template():
    """The rock as vanilla draws it (#rock_end on its top and bottom, #rock_side round it), the carving over it (#end,
    #side) lit by the world like the rock, and the carving's glow over that (#end_glow, #side_glow), unshaded at full
    block light. The three lie on the same planes, each drawn over the one before where it has pixels."""
    sides = ("north", "east", "south", "west")

    def cube(end, side, extra):
        faces = {"down": end, "up": end}
        faces.update({f: side for f in sides})
        return dict({"from": [0, 0, 0], "to": [16, 16, 16],
                     "faces": {f: {"texture": t, "cullface": f} for f, t in faces.items()}}, **extra)

    return {"parent": "minecraft:block/block", "textures": {"particle": "#rock_side"},
            "elements": [cube("#rock_end", "#rock_side", {}), cube("#end", "#side", {}),
                         cube("#end_glow", "#side_glow", {"shade": False, "forge_data": {"block_light": 15}})]}


def loot(weights):
    entries = [dict({"type": "minecraft:tag", "name": f"{MOD}:runes/{layer}", "expand": True}, **({"weight": w} if len(weights) > 1 else {}))
               for layer, w in weights]
    return {"type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": entries},
                      {"rolls": 1, "entries": entries,
                       "conditions": [{"condition": "minecraft:random_chance", "chance": SECOND}]}]}


def write_all(res, write):
    """Writes the textures, models, blockstates, items and loot tables under res (src/main/resources); returns the
    English names."""
    assets = os.path.join(res, "assets", MOD)
    data = os.path.join(res, "data", MOD)
    tex_dir = os.path.join(assets, "textures", "block")
    b = lambda n: f"{MOD}:block/{n}"
    for host, row in looks().items():
        for v, (side, side_glow, side_lit, end, end_glow, end_lit) in enumerate(row):
            # the _lit textures are no model's: the block atlas takes every texture under block/, and the client draws them
            for part, img in (("side", side), ("side_glow", side_glow), ("side_lit", side_lit), ("end", end), ("end_glow", end_glow),
                              ("end_lit", end_lit)):
                path = os.path.join(tex_dir, tex(host, v, part) + ".png")
                os.makedirs(os.path.dirname(path), exist_ok=True)
                img.save(path)
    write(os.path.join(assets, "models", "block", "template", "rune_stone.json"), template())
    for host, (block, side, end, _, _, _) in HOSTS.items():
        for v in range(LOOKS):
            write(os.path.join(assets, "models", "block", "rune_stone", f"{host}_{v}.json"), {
                "parent": b("template/rune_stone"), "render_type": "minecraft:cutout_mipped",
                "textures": {"rock_end": f"minecraft:block/{end}", "rock_side": f"minecraft:block/{side}",
                             "end": b(tex(host, v, "end")), "side": b(tex(host, v, "side")),
                             "end_glow": b(tex(host, v, "end_glow")), "side_glow": b(tex(host, v, "side_glow"))}})
    names = {}
    for bid, (name, weights, hosts) in STONES.items():
        write(os.path.join(assets, "blockstates", bid + ".json"), {"variants": {
            f"host={h}": [{"model": b(f"rune_stone/{h}_{v}")} for v in range(LOOKS)] for h in hosts}})
        write(os.path.join(assets, "models", "item", bid + ".json"), {"parent": b(f"rune_stone/{hosts[0]}_0")})
        write(os.path.join(data, "loot_tables", "blocks", bid + ".json"), loot(weights))
        names[f"block.{MOD}.{bid}"] = name
    return names


def vanilla_textures():
    """Every vanilla texture the models name (for the checks)."""
    return {f"minecraft:block/{t}" for (_, side, end, _, _, _) in HOSTS.values() for t in (side, end)}


# ============================================================================================== review sheet
def _vanilla(folder_or_jar):
    if folder_or_jar.endswith(".jar"):
        import io
        import zipfile
        z = zipfile.ZipFile(folder_or_jar)
        return lambda n: Image.open(io.BytesIO(z.read(f"assets/minecraft/textures/block/{n}.png"))).convert("RGBA").crop((0, 0, 16, 16))
    return lambda n: Image.open(os.path.join(folder_or_jar, n + ".png")).convert("RGBA").crop((0, 0, 16, 16))


def _shade(img, k):
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, bl, a = px[x, y]
            px[x, y] = (int(r * k), int(g * k), int(bl * k), a)
    return out


def _face(base, over, glow, dark, lit=None):
    img = base.copy()
    img.alpha_composite(over)
    if dark:
        img = _shade(img, 0.12)
    img.alpha_composite(glow)
    if lit is not None:
        # as the client draws it: the light added over the face at full strength
        px, lpx = img.load(), lit.load()
        for y in range(16):
            for x in range(16):
                r, g, b, a = px[x, y]
                lr, lg, lb, la = lpx[x, y]
                if la:
                    px[x, y] = (min(255, r + lr), min(255, g + lg), min(255, b + lb), a)
    return img


def sheet(out, vanilla):
    from PIL import ImageDraw
    tex_of = _vanilla(vanilla)
    all_looks = looks()
    cell, gap = 112, 10
    wall_px = 18
    rows = list(HOSTS.items())
    W = 190 + LOOKS * (3 * cell + gap) + 2 * (5 * wall_px) + 40
    H = 20 + len(rows) * (cell + 2 * gap) * 2
    img = Image.new("RGBA", (W, H), (22, 24, 30, 255))
    d = ImageDraw.Draw(img)
    y = 14
    for host, (_, side, end, _, _, _) in rows:
        base_side, base_end = tex_of(side), tex_of(end)
        d.text((10, y + 8), host.replace("_", " "), fill=(220, 226, 240, 255))
        d.text((10, y + 26), "side: lit / dark / dug", fill=(150, 160, 180, 255))
        d.text((10, y + cell + gap + 8), "top: lit / dark / dug", fill=(150, 160, 180, 255))
        for v, (s, sg, sl, e, eg, el) in enumerate(all_looks[host]):
            x = 190 + v * (3 * cell + gap)
            for k, dark in enumerate((False, True)):
                img.paste(_face(base_side, s, sg, dark).resize((cell, cell), Image.NEAREST), (x + k * cell, y))
                img.paste(_face(base_end, e, eg, dark).resize((cell, cell), Image.NEAREST), (x + k * cell, y + cell + gap))
            img.paste(_face(base_side, s, sg, True, sl).resize((cell, cell), Image.NEAREST), (x + 2 * cell, y))
            img.paste(_face(base_end, e, eg, True, el).resize((cell, cell), Image.NEAREST), (x + 2 * cell, y + cell + gap))
        # look 0 in a wall of its host, lit and dark, small (as seen from a few blocks off)
        wx = 190 + LOOKS * (3 * cell + gap) + 10
        for k, dark in enumerate((False, True)):
            plain = _shade(base_side, 0.12) if dark else base_side
            stone = _face(base_side, all_looks[host][0][0], all_looks[host][0][1], dark)
            for i in range(5):
                for j in range(5):
                    t = stone if (i, j) == (2, 2) else plain
                    img.paste(t.resize((wall_px, wall_px), Image.NEAREST), (wx + i * wall_px, y + k * (cell + gap) + j * wall_px))
        y += 2 * (cell + gap)
    img.save(out)


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--sheet":
        sheet(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else os.path.expanduser(
            "~/.gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar"))
        print("sheet written")
    else:
        print(__doc__)
