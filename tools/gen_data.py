#!/usr/bin/env python3
"""Generates Exploration Remix's datapack JSON, blockstates, models, lang and original procedural textures.
Run from the repo root: python3 tools/gen_data.py
"""
import json, os, math, random
import sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import gen_runes
import gen_creatures
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src/main/resources")
MOD = "explorationremix"
DATA = os.path.join(RES, "data", MOD)
ASSETS = os.path.join(RES, "assets", MOD)

def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")

def data(kind, name, obj): write(os.path.join(DATA, "worldgen" if kind in ("biome","configured_feature","placed_feature","density_function") else "", kind, name + ".json"), obj)
def tag(ns, kind, name, values, replace=False):
    write(os.path.join(RES, "data", ns, "tags", kind, name + ".json"), {"replace": replace, "values": values})

# ----------------------------------------------------------------------------------------------- density helpers
def noise(n, xz=1.0, y=1.0): return {"type": "minecraft:noise", "noise": f"minecraft:{n}", "xz_scale": xz, "y_scale": y}
def add(a, b): return {"type": "minecraft:add", "argument1": a, "argument2": b}
def mul(a, b): return {"type": "minecraft:mul", "argument1": a, "argument2": b}
def mn(a, b): return {"type": "minecraft:min", "argument1": a, "argument2": b}
def mx(a, b): return {"type": "minecraft:max", "argument1": a, "argument2": b}
def clamp(i, lo, hi): return {"type": "minecraft:clamp", "input": i, "min": lo, "max": hi}
def abs_(a): return {"type": "minecraft:abs", "argument": a}
def cube(a): return {"type": "minecraft:cube", "argument": a}
def square(a): return {"type": "minecraft:square", "argument": a}
def once(a): return {"type": "minecraft:cache_once", "argument": a}
def ygrad(fy, ty, fv, tv): return {"type": "minecraft:y_clamped_gradient", "from_y": fy, "to_y": ty, "from_value": fv, "to_value": tv}
def rc(i, lo, hi, inr, outr): return {"type": "minecraft:range_choice", "input": i, "min_inclusive": lo, "max_exclusive": hi, "when_in_range": inr, "when_out_of_range": outr}
def weird(inp, n, mapper): return {"type": "minecraft:weird_scaled_sampler", "input": inp, "noise": f"minecraft:{n}", "rarity_value_mapper": mapper}

def spaghetti3d(thick=-0.0765, thick_var=-0.0115, rarity_xz=2.0):
    rar = once(noise("spaghetti_3d_rarity", rarity_xz, 1.0))
    rough = once(mul(add(-0.05, mul(-0.05, noise("spaghetti_roughness_modulator"))), add(-0.4, abs_(noise("spaghetti_roughness")))))
    core = clamp(add(mx(weird(rar, "spaghetti_3d_1", "type_1"), weird(rar, "spaghetti_3d_2", "type_1")),
                     add(thick, mul(thick_var, noise("spaghetti_3d_thickness")))), -1.0, 1.0)
    return add(rough, core)

def spaghetti2d(bottom, top, thick_base=-0.95, thick_var=-0.35):
    """Vanilla-style 2D spaghetti, with the elevation gradient spanning the layer instead of -64..320."""
    thickness = once(add(thick_base, mul(thick_var, noise("spaghetti_2d_thickness", 2.0, 1.0))))
    a = add(weird(noise("spaghetti_2d_modulator", 2.0, 1.0), "spaghetti_2d", "type_2"), mul(0.083, thickness))
    elev = cube(add(abs_(add(mul(8.0, noise("spaghetti_2d_elevation", 1.0, 0.0)), ygrad(bottom, top, 8.0, -8.0))), thickness))
    return clamp(mx(a, elev), -1.0, 1.0)

def pillars(xz=25.0, rare=-1.0):
    p = once(mul(add(mul(2.0, noise("pillar", xz, 0.3)), add(rare, mul(-1.0, noise("pillar_rareness")))),
                 cube(add(0.8, mul(0.5, noise("pillar_thickness"))))))
    return rc(p, -1000000.0, 0.03, -1000000.0, p)

def with_pillars(caves, pil): return mx(caves, pil)

# default layer geometry, only used for the 2D spaghetti elevation gradient
LAYERS = [
    ("lantern_deeps", -208, -81), ("blue_reaches", -384, -225), ("ember_gardens", -592, -401), ("basalt_galleries", -832, -609)]

def cave_functions():
    # Lantern Deeps: wide round halls joined by tunnels, with stone pillars.
    halls = add(0.21, noise("cave_cheese", 0.5, 0.95))
    tunnels = mul(4.0, spaghetti3d(thick=-0.09))
    lantern = with_pillars(mn(mn(halls, tunnels), spaghetti2d(-208, -81)), pillars())
    data("density_function", "caves/lantern_deeps", lantern)
    # Blue Reaches: tall narrow rifts (ridged 2D noise) and cold cheese stretched upward.
    rift = add(-0.12, abs_(noise("cave_layer", 0.9, 0.05)))        # ridged vertical cracks
    rift2 = add(-0.09, abs_(noise("spaghetti_2d", 0.55, 0.04)))
    tall = add(0.24, noise("cave_cheese", 1.1, 0.3))
    blue = mn(mn(mn(mx(rift, rift2), tall), mul(4.0, spaghetti3d(thick=-0.07))), spaghetti2d(-384, -225))
    data("density_function", "caves/blue_reaches", blue)
    # Ember Gardens: broad low basins with lakes, joined by wide tunnels.
    basins = add(0.14, noise("cave_cheese", 0.42, 1.6))
    ember = mn(mn(basins, mul(3.5, spaghetti3d(thick=-0.11))), spaghetti2d(-592, -401))
    data("density_function", "caves/ember_gardens", ember)
    # Basalt Galleries: arched galleries, a little tighter, with columns.
    galleries = add(0.2, noise("cave_cheese", 0.85, 0.6))
    basalt = with_pillars(mn(mn(galleries, mul(4.0, spaghetti3d(thick=-0.1))), spaghetti2d(-832, -609)), pillars(xz=18.0, rare=-0.7))
    data("density_function", "caves/basalt_galleries", basalt)

# ----------------------------------------------------------------------------------------------- blocks and assets
STONES = ["band_stone", "lantern_stone", "reach_stone", "ember_stone", "gallery_stone"]
SOLIDS = ["banded_iron", "honeycomb_ochrum", "rusted_gear", "rusted_pipe", "rune_block"]
CRYSTALS = ["ochre_crystal", "amber_resin", "asurine_crystal", "hematite_crystal"]
FLORA = ["lantern_fungus", "hanging_wisteria", "silver_fern", "blue_mushroom", "shelf_fungus", "lavender_coral", "heat_fungus"]

NAMES = {
    "band_stone": "Band Stone", "lantern_stone": "Lantern Stone", "reach_stone": "Reach Stone", "ember_stone": "Ember Stone",
    "gallery_stone": "Gallery Stone", "banded_iron": "Banded Iron", "honeycomb_ochrum": "Honeycomb Ochrum", "rusted_gear": "Rusted Gear",
    "rusted_pipe": "Rusted Pipe", "rune_block": "Rune Block", "ochre_crystal": "Ochre Crystal", "amber_resin": "Amber Resin",
    "asurine_crystal": "Asurine Crystal", "hematite_crystal": "Hematite Crystal", "lantern_fungus": "Lantern Fungus",
    "hanging_wisteria": "Hanging Wisteria", "silver_fern": "Silver Fern", "blue_mushroom": "Blue Mushroom", "shelf_fungus": "Shelf Fungus",
    "lavender_coral": "Lavender Coral", "heat_fungus": "Heat Fungus",
}
BIOME_NAMES = {
    "rootvaults": "Rootvaults", "dripstone_halls": "Dripstone Halls", "ivory_root_cavern": "Ivory Root Cavern", "lantern_lake": "Lantern Lake",
    "amber_hive": "Amber Hive", "asurine_geodes": "Asurine Geodes", "deep_blue_rift": "Deep Blue Rift", "silver_fern_rift": "Silver Fern Rift",
    "deep_dark_reach": "Deep Dark Reach", "banded_iron_halls": "Banded Iron Halls", "rust_springs": "Rust Springs", "ember_gardens": "Ember Gardens",
    "obsidian_tubes": "Obsidian Tubes", "basalt_galleries": "Basalt Galleries", "smolder_groves": "Smolder Groves",
}
LAYER_NAMES = {"lantern_deeps": "Lantern Deeps", "blue_reaches": "Blue Reaches", "ember_gardens": "Ember Gardens", "basalt_galleries": "Basalt Galleries"}

def rng(name): return random.Random(hash(name) & 0xffffffff)

def vnoise(r, w, h, cells=4):
    grid = [[r.random() for _ in range(cells + 1)] for _ in range(cells + 1)]
    out = [[0.0] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            fx, fy = x / w * cells, y / h * cells
            x0, y0 = int(fx), int(fy)
            tx, ty = fx - x0, fy - y0
            tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty)
            x1, y1 = (x0 + 1) % (cells + 1), (y0 + 1) % (cells + 1)
            a = grid[y0][x0] * (1 - tx) + grid[y0][x1] * tx
            b = grid[y1][x0] * (1 - tx) + grid[y1][x1] * tx
            out[y][x] = a * (1 - ty) + b * ty
    return out

def stone_texture(name, base, dark, light, speck=0.08, cells=4, stripes=None):
    r = rng(name)
    img = Image.new("RGBA", (16, 16))
    n = vnoise(r, 16, 16, cells)
    n2 = vnoise(r, 16, 16, 8)
    px = img.load()
    for y in range(16):
        for x in range(16):
            v = n[y][x] * 0.7 + n2[y][x] * 0.3
            c = [int(base[i] + (light[i] - base[i]) * (v - 0.5) * 1.6) if v > 0.5 else int(base[i] + (dark[i] - base[i]) * (0.5 - v) * 1.6) for i in range(3)]
            if r.random() < speck: c = [int((c[i] + dark[i]) / 2) for i in range(3)]
            if stripes and (y + int(n[y][x] * 3)) % stripes[0] < stripes[1]: c = [int(c[i] * 0.55 + stripes[2][i] * 0.45) for i in range(3)]
            px[x, y] = (max(0, min(255, c[0])), max(0, min(255, c[1])), max(0, min(255, c[2])), 255)
    return img

def crystal_texture(name, base, light, dark):
    r = rng(name)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            # faceted look: diagonal bands with bright edges, mostly opaque
            band = ((x + y) // 3 + (x - y) // 5) % 3
            c = [base, light, dark][band]
            if (x * 7 + y * 3) % 11 == 0: c = light
            a = 235 if (x + y) % 5 else 200
            px[x, y] = (c[0], c[1], c[2], a)
    return img

def plant_texture(name, stem, leaf, glow=None, shape="fern"):
    r = rng(name)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if shape == "fern":
        for i in range(5):
            x0 = 2 + i * 3
            d.line([(8, 15), (x0, 3 + r.randint(0, 3))], fill=stem + (255,), width=1)
            for t in range(3):
                yy = 6 + t * 3
                d.point((x0 + (1 if i < 2 else -1), yy), fill=leaf + (255,))
                d.point((x0, yy - 1), fill=leaf + (255,))
    elif shape == "mushroom":
        d.rectangle([7, 9, 8, 15], fill=stem + (255,))
        d.ellipse([3, 4, 12, 10], fill=leaf + (255,))
        if glow: 
            for _ in range(6): d.point((r.randint(4, 11), r.randint(5, 9)), fill=glow + (255,))
    elif shape == "hanging":
        for i in range(4):
            x0 = 3 + i * 3
            length = 8 + r.randint(0, 7)
            d.line([(x0, 0), (x0, length)], fill=stem + (255,), width=1)
            for yy in range(2, length, 2): d.point((x0 + (1 if yy % 4 else -1), yy), fill=leaf + (255,))
            if glow: d.point((x0, length), fill=glow + (255,))
    elif shape == "shelf":
        for i in range(3):
            y0 = 2 + i * 5
            d.rectangle([2 + i, y0, 13 - i, y0 + 2], fill=leaf + (255,))
            d.line([(2 + i, y0 + 2), (13 - i, y0 + 2)], fill=stem + (255,))
    elif shape == "coral":
        for i in range(5):
            x0 = 2 + i * 3
            d.line([(8, 15), (x0, 4 + r.randint(0, 4))], fill=stem + (255,), width=2)
            d.ellipse([x0 - 1, 2 + r.randint(0, 3), x0 + 1, 5 + r.randint(0, 3)], fill=leaf + (255,))
    return img

def gear_texture(name, base, dark, light):
    img = stone_texture(name, base, dark, light, speck=0.15, cells=6)
    d = ImageDraw.Draw(img)
    d.ellipse([3, 3, 12, 12], outline=dark + (255,), width=2)
    d.ellipse([6, 6, 9, 9], fill=dark + (255,))
    for a in range(0, 360, 45):
        x, y = 7.5 + 6 * math.cos(math.radians(a)), 7.5 + 6 * math.sin(math.radians(a))
        d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=light + (255,))
    return img

def pipe_texture(name, base, dark, light):
    img = stone_texture(name, base, dark, light, speck=0.15, cells=6)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 5, 15, 10], fill=base + (255,))
    d.line([(0, 5), (15, 5)], fill=light + (255,)); d.line([(0, 10), (15, 10)], fill=dark + (255,))
    for x in (2, 12): d.rectangle([x, 4, x + 1, 11], fill=dark + (255,))
    return img

def rune_texture():
    img = stone_texture("rune_block", (48, 36, 70), (24, 16, 40), (90, 70, 120), speck=0.05, cells=5)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 15, 15], outline=(120, 90, 170, 255))
    d.line([(8, 2), (8, 13)], fill=(220, 190, 255, 255), width=1)
    d.line([(4, 5), (12, 5)], fill=(220, 190, 255, 255)); d.line([(5, 10), (11, 10)], fill=(200, 160, 255, 255))
    return img

def honeycomb_texture():
    img = stone_texture("honeycomb_ochrum", (214, 160, 70), (150, 100, 40), (240, 200, 110), speck=0.05)
    d = ImageDraw.Draw(img)
    for yy in range(0, 16, 6):
        for xx in range(0, 16, 6):
            ox = 3 if (yy // 6) % 2 else 0
            d.polygon([(xx + ox + 2, yy), (xx + ox + 5, yy + 1), (xx + ox + 5, yy + 4), (xx + ox + 2, yy + 5), (xx + ox - 1, yy + 4), (xx + ox - 1, yy + 1)], outline=(120, 70, 20, 255))
    return img

def textures():
    T = os.path.join(ASSETS, "textures/block"); os.makedirs(T, exist_ok=True)
    stone_texture("band_stone", (34, 32, 38), (14, 12, 18), (62, 58, 70), speck=0.12, cells=6, stripes=(6, 1, (70, 60, 90))).save(f"{T}/band_stone.png")
    stone_texture("lantern_stone", (78, 66, 52), (48, 38, 28), (112, 96, 72), speck=0.1).save(f"{T}/lantern_stone.png")
    stone_texture("reach_stone", (58, 70, 86), (34, 42, 56), (92, 108, 128), speck=0.1).save(f"{T}/reach_stone.png")
    stone_texture("ember_stone", (84, 52, 40), (50, 28, 20), (124, 84, 60), speck=0.12).save(f"{T}/ember_stone.png")
    stone_texture("gallery_stone", (46, 44, 50), (24, 22, 28), (74, 72, 80), speck=0.14, cells=5).save(f"{T}/gallery_stone.png")
    stone_texture("banded_iron", (120, 60, 44), (60, 30, 24), (170, 90, 60), speck=0.06, stripes=(4, 2, (40, 36, 44))).save(f"{T}/banded_iron.png")
    honeycomb_texture().save(f"{T}/honeycomb_ochrum.png")
    gear_texture("rusted_gear", (150, 84, 40), (84, 44, 20), (200, 130, 70)).save(f"{T}/rusted_gear.png")
    pipe_texture("rusted_pipe", (140, 80, 44), (80, 42, 22), (190, 120, 70)).save(f"{T}/rusted_pipe.png")
    rune_texture().save(f"{T}/rune_block.png")
    crystal_texture("ochre_crystal", (230, 170, 60), (255, 225, 140), (170, 110, 30)).save(f"{T}/ochre_crystal.png")
    crystal_texture("amber_resin", (240, 150, 40), (255, 210, 110), (180, 90, 20)).save(f"{T}/amber_resin.png")
    crystal_texture("asurine_crystal", (70, 130, 230), (170, 210, 255), (30, 70, 170)).save(f"{T}/asurine_crystal.png")
    crystal_texture("hematite_crystal", (150, 50, 50), (220, 120, 110), (80, 20, 24)).save(f"{T}/hematite_crystal.png")
    plant_texture("lantern_fungus", (120, 100, 60), (240, 200, 90), glow=(255, 250, 200), shape="mushroom").save(f"{T}/lantern_fungus.png")
    plant_texture("hanging_wisteria", (90, 110, 70), (200, 160, 230), glow=(240, 220, 255), shape="hanging").save(f"{T}/hanging_wisteria.png")
    plant_texture("silver_fern", (80, 100, 90), (190, 210, 200), shape="fern").save(f"{T}/silver_fern.png")
    plant_texture("blue_mushroom", (60, 70, 110), (80, 140, 230), glow=(190, 230, 255), shape="mushroom").save(f"{T}/blue_mushroom.png")
    plant_texture("shelf_fungus", (110, 80, 50), (200, 150, 90), shape="shelf").save(f"{T}/shelf_fungus.png")
    plant_texture("lavender_coral", (120, 90, 160), (190, 150, 230), shape="coral").save(f"{T}/lavender_coral.png")
    plant_texture("heat_fungus", (90, 40, 20), (240, 110, 40), glow=(255, 220, 120), shape="mushroom").save(f"{T}/heat_fungus.png")
    # a tiny pack icon
    icon = Image.new("RGBA", (64, 64), (20, 14, 30, 255)); d = ImageDraw.Draw(icon)
    for i, c in enumerate([(78, 66, 52), (58, 70, 86), (84, 52, 40), (46, 44, 50)]):
        d.rectangle([4, 10 + i * 13, 59, 20 + i * 13], fill=c + (255,)); d.rectangle([4, 20 + i * 13, 59, 22 + i * 13], fill=(34, 32, 38, 255))
    icon.save(os.path.join(RES, "icon.png"))

def remains_texture(looted):
    r = rng("remains" + str(looted))
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    # a scatter of bones on the floor
    bone = (222, 214, 190, 255); shade = (160, 150, 128, 255)
    for (x0, y0, x1, y1) in [(2, 3, 9, 4), (6, 9, 13, 10), (10, 2, 11, 7), (3, 12, 8, 13), (12, 11, 13, 14)]:
        d.rectangle([x0, y0, x1, y1], fill=bone); d.point([(x0, y1), (x1, y1)], fill=shade)
    d.ellipse([9, 4, 14, 9], fill=bone); d.point([(11, 6), (12, 6)], fill=(40, 30, 30, 255)); d.point([(11, 8), (12, 8)], fill=shade)
    if not looted:
        d.rectangle([1, 6, 6, 11], fill=(110, 76, 44, 255)); d.rectangle([1, 6, 6, 7], fill=(140, 100, 60, 255)); d.point([(3, 9), (4, 9)], fill=(200, 170, 90, 255))
    return img

def remains_assets():
    T = os.path.join(ASSETS, "textures/block")
    remains_texture(False).save(f"{T}/remains.png"); remains_texture(True).save(f"{T}/remains_looted.png")
    side = Image.new("RGBA", (16, 16), (0, 0, 0, 0)); d = ImageDraw.Draw(side)
    for x in range(0, 16, 3): d.rectangle([x, 12, x + 1, 15], fill=(200, 192, 170, 255))
    side.save(f"{T}/remains_side.png")
    for name, tex in (("remains", "remains"), ("remains_looted", "remains_looted")):
        write(os.path.join(ASSETS, "models/block", name + ".json"), {
            "parent": "minecraft:block/block", "render_type": "minecraft:cutout",
            "textures": {"particle": f"{MOD}:block/{tex}", "top": f"{MOD}:block/{tex}", "side": f"{MOD}:block/remains_side"},
            "elements": [{"from": [1, 0, 1], "to": [15, 5, 15], "faces": {
                "up": {"uv": [1, 1, 15, 15], "texture": "#top"}, "down": {"uv": [1, 1, 15, 15], "texture": "#side"},
                "north": {"uv": [1, 11, 15, 16], "texture": "#side"}, "south": {"uv": [1, 11, 15, 16], "texture": "#side"},
                "west": {"uv": [1, 11, 15, 16], "texture": "#side"}, "east": {"uv": [1, 11, 15, 16], "texture": "#side"}}}]})
    write(os.path.join(ASSETS, "blockstates/remains.json"), {"variants": {"looted=false": {"model": f"{MOD}:block/remains"}, "looted=true": {"model": f"{MOD}:block/remains_looted"}}})
    write(os.path.join(ASSETS, "models/item/remains.json"), {"parent": f"{MOD}:block/remains"})

def egg_cluster_assets():
    T = os.path.join(ASSETS, "textures/block")
    r = rng("egg_cluster")
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    for (cx, cy, rad) in [(4, 5, 3), (10, 4, 3), (7, 10, 3), (12, 11, 2), (3, 12, 2)]:
        d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(190, 220, 140, 255))
        d.point([(cx - 1, cy - 1)], fill=(230, 245, 200, 255)); d.point([(cx + 1, cy + 1)], fill=(120, 150, 80, 255))
    for x in range(16):
        for y in range(16):
            if img.getpixel((x, y))[3] == 0 and r.random() < 0.55: img.putpixel((x, y), (90, 110, 60, 255))
    img.save(f"{T}/egg_cluster.png")
    write(os.path.join(ASSETS, "models/block/egg_cluster.json"), {
        "parent": "minecraft:block/block", "render_type": "minecraft:cutout",
        "textures": {"particle": f"{MOD}:block/egg_cluster", "all": f"{MOD}:block/egg_cluster"},
        "elements": [{"from": [2, 0, 2], "to": [14, 10, 14], "faces": {f: {"uv": [2, 2, 14, 14], "texture": "#all"} for f in ("up", "down", "north", "south", "west", "east")}}]})
    write(os.path.join(ASSETS, "blockstates/egg_cluster.json"), {"variants": {"": {"model": f"{MOD}:block/egg_cluster"}}})
    write(os.path.join(ASSETS, "models/item/egg_cluster.json"), {"parent": f"{MOD}:block/egg_cluster"})

def models_and_states():
    remains_assets()
    egg_cluster_assets()
    for b in STONES + SOLIDS:
        write(os.path.join(ASSETS, "blockstates", b + ".json"), {"variants": {"": {"model": f"{MOD}:block/{b}"}}} if b != "rune_block" else
              {"variants": {"lit=false": {"model": f"{MOD}:block/rune_block"}, "lit=true": {"model": f"{MOD}:block/rune_block"}}})
        write(os.path.join(ASSETS, "models/block", b + ".json"), {"parent": "minecraft:block/cube_all", "textures": {"all": f"{MOD}:block/{b}"}})
        write(os.path.join(ASSETS, "models/item", b + ".json"), {"parent": f"{MOD}:block/{b}"})
    for b in CRYSTALS:
        write(os.path.join(ASSETS, "blockstates", b + ".json"), {"variants": {"": {"model": f"{MOD}:block/{b}"}}})
        write(os.path.join(ASSETS, "models/block", b + ".json"), {"parent": "minecraft:block/cube_all", "render_type": "minecraft:translucent", "textures": {"all": f"{MOD}:block/{b}"}})
        write(os.path.join(ASSETS, "models/item", b + ".json"), {"parent": f"{MOD}:block/{b}"})
    for b in FLORA:
        write(os.path.join(ASSETS, "blockstates", b + ".json"), {"variants": {"waterlogged=false": {"model": f"{MOD}:block/{b}"}, "waterlogged=true": {"model": f"{MOD}:block/{b}"}}})
        write(os.path.join(ASSETS, "models/block", b + ".json"), {"parent": "minecraft:block/cross", "render_type": "minecraft:cutout", "textures": {"cross": f"{MOD}:block/{b}"}})
        write(os.path.join(ASSETS, "models/item", b + ".json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:block/{b}"}})

def lang():
    d = {}
    gen_runes.lang(d)
    gen_creatures.lang(d)
    for b, n in NAMES.items(): d[f"block.{MOD}.{b}"] = n
    for b, n in BIOME_NAMES.items(): d[f"biome.{MOD}.{b}"] = n
    for l, n in LAYER_NAMES.items(): d[f"layer.{MOD}.{l}"] = n
    d[f"itemGroup.{MOD}"] = "Exploration Remix"
    d[f"block.{MOD}.remains"] = "Old Remains"
    d[f"run.{MOD}.remains_no_run"] = "The satchel holds a book, but it would crumble up here. It waits for someone on a run."
    d[f"run.{MOD}.remains_found"] = "You find a %s in the satchel."
    d[f"layer.{MOD}.upper_caves"] = "Upper Caves"
    d[f"layer.{MOD}.bedrock"] = "Bedrock"
    write(os.path.join(ASSETS, "lang", "en_us.json"), d)

# ----------------------------------------------------------------------------------------------- features
def cf(name, type_, config): data("configured_feature", name, {"type": type_, "config": config}); return f"{MOD}:{name}"
def pf(name, feature, placement): data("placed_feature", name, {"feature": feature, "placement": placement}); return f"{MOD}:{name}"
def count(n): return {"type": "minecraft:count", "count": n}
def rarity(n): return {"type": "minecraft:rarity_filter", "chance": n}
SQ = {"type": "minecraft:in_square"}
BIOME = {"type": "minecraft:biome"}
def layer_range(l, inset=2): return {"type": f"{MOD}:layer_range", "layer": l, "inset": inset}
def scan(direction, steps=12, target=None):
    t = target or {"type": "minecraft:solid"}
    return {"type": "minecraft:environment_scan", "direction_of_search": direction, "max_steps": steps, "target_condition": t,
            "allowed_search_condition": {"type": "minecraft:matching_blocks", "blocks": ["minecraft:air", "minecraft:water"]}}
def offset_y(dy): return {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": dy}
def survives(block): return {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:would_survive", "state": {"Name": block}}}
def in_water(): return {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:matching_fluids", "fluids": ["minecraft:water"]}}
def in_air(): return {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:matching_blocks", "blocks": ["minecraft:air"]}}
def fallback(*blocks): return {"type": f"{MOD}:fallback", "blocks": list(blocks)}

def simple(block, props=None):
    st = {"Name": block}
    if props: st["Properties"] = props
    return {"type": "minecraft:simple_state_provider", "state": st}

FEATURES = {}   # biome -> {step: [placed ids]}
def feat(biome, step, *ids):
    FEATURES.setdefault(biome, {}).setdefault(step, []).extend(ids)

LAYER_OF = {"dripstone_halls": "lantern_deeps", "ivory_root_cavern": "lantern_deeps", "lantern_lake": "lantern_deeps", "amber_hive": "lantern_deeps",
            "asurine_geodes": "blue_reaches", "deep_blue_rift": "blue_reaches", "silver_fern_rift": "blue_reaches", "deep_dark_reach": "blue_reaches",
            "banded_iron_halls": "ember_gardens", "rust_springs": "ember_gardens", "ember_gardens": "ember_gardens",
            "obsidian_tubes": "basalt_galleries", "basalt_galleries": "basalt_galleries", "smolder_groves": "basalt_galleries"}
STONE_OF = {"lantern_deeps": f"{MOD}:lantern_stone", "blue_reaches": f"{MOD}:reach_stone", "ember_gardens": f"{MOD}:ember_stone", "basalt_galleries": f"{MOD}:gallery_stone"}
ALL_STONES = [f"{MOD}:lantern_stone", f"{MOD}:reach_stone", f"{MOD}:ember_stone", f"{MOD}:gallery_stone", "minecraft:deepslate", "minecraft:tuff", "minecraft:calcite",
              "minecraft:basalt", "minecraft:smooth_basalt", "minecraft:blackstone", "minecraft:dripstone_block", "minecraft:moss_block",
              "create:ochrum", "create:limestone", "create:asurine", "create:crimsite", "create:scoria", "create:scorchia", "create:veridium"]

def plant(name, block, biome, n, step=9, ceiling=False, water=False, inset=2):
    layer = LAYER_OF[biome]
    cfid = cf(f"{name}", "minecraft:simple_block", {"to_place": simple(block, {"waterlogged": "true"} if water else None)})
    mods = [count(n), SQ, layer_range(layer, inset), scan("up" if ceiling else "down", 14), offset_y(1 if ceiling else 0) if False else None]
    mods = [m for m in mods if m]
    if ceiling: mods.append({"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": -1})
    else: mods.append({"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": 1})
    mods.append(in_water() if water else in_air())
    mods.append(survives(block)); mods.append(BIOME)
    pid = pf(f"{name}_{biome}", cfid, mods)
    feat(biome, step, pid); return pid

def pile(name, block, biome, n, step=7, ceiling=False):
    layer = LAYER_OF[biome]
    cfid = cf(f"pile_{name}", "minecraft:block_pile", {"state_provider": simple(block)})
    mods = [count(n), SQ, layer_range(layer), scan("up" if ceiling else "down", 14), {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": -1 if ceiling else 1}, in_air(), BIOME]
    pid = pf(f"pile_{name}_{biome}", cfid, mods); feat(biome, step, pid); return pid

def remains(biome, chance=2):
    layer = LAYER_OF[biome]
    cfid = cf("remains", "minecraft:simple_block", {"to_place": simple(f"{MOD}:remains")})
    mods = [rarity(chance), SQ, layer_range(layer, 2), scan("down", 14), {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": 1}, in_air(), survives(f"{MOD}:remains"), BIOME]
    pid = pf(f"remains_{biome}", cfid, mods); feat(biome, 9, pid); return pid

def ore(layer, biome_list, name, state, size, n, discard=0.0):
    cfid = cf(f"ore_{name}_{layer}", "minecraft:ore", {"size": size, "discard_chance_on_air_exposure": discard,
              "targets": [{"target": {"predicate_type": "minecraft:block_match", "block": STONE_OF[layer]}, "state": {"Name": state}}]})
    pid = pf(f"ore_{name}_{layer}", cfid, [count(n), SQ, layer_range(layer, 4), BIOME])
    for b in biome_list: feat(b, 6, pid)

def features():
    ruin = pf("ruin", cf("ruin", f"{MOD}:ruin", {}), [BIOME])
    lichen = cf("glow_lichen_deep", "minecraft:multiface_growth", {"block": "minecraft:glow_lichen", "search_range": 20, "chance_of_spreading": 0.5,
                "can_place_on_floor": True, "can_place_on_ceiling": True, "can_place_on_wall": True, "can_be_placed_on": f"#{MOD}:cave_stone"})
    springs = cf("spring_water_deep", "minecraft:spring_feature", {"state": {"Name": "minecraft:water", "Properties": {"falling": "true"}}, "rock_count": 4, "hole_count": 1,
                 "requires_block_below": True, "valid_blocks": f"#{MOD}:cave_stone"})
    moss = cf("moss_patch_deep", "minecraft:vegetation_patch", {"surface": "floor", "depth": 1, "vertical_range": 5, "extra_bottom_block_chance": 0.0,
              "extra_edge_column_chance": 0.3, "vegetation_chance": 0.8, "xz_radius": {"type": "minecraft:uniform", "value": {"min_inclusive": 4, "max_inclusive": 7}},
              "replaceable": "#minecraft:moss_replaceable", "ground_state": simple("minecraft:moss_block"), "vegetation_feature": {"feature": "minecraft:moss_vegetation", "placement": []}})
    for biome, layer in LAYER_OF.items():
        feat(biome, 3, ruin)
        pid = pf(f"glow_lichen_{biome}", lichen, [count(50), SQ, layer_range(layer, 4), BIOME]); feat(biome, 7, pid)
    for biome in ["lantern_lake", "amber_hive", "silver_fern_rift", "deep_dark_reach", "ember_gardens", "smolder_groves"]:
        remains(biome)
    roost = cf("flock_roost", f"{MOD}:flock_roost", {})
    for biome, layer in LAYER_OF.items():
        pid = pf(f"flock_roost_{biome}", roost, [SQ, layer_range(layer, 3), scan("up", 16), {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": -1}, in_air(), BIOME])
        feat(biome, 9, pid)
    # ores
    L0 = ["dripstone_halls", "ivory_root_cavern", "lantern_lake", "amber_hive"]
    L1 = ["asurine_geodes", "deep_blue_rift", "silver_fern_rift", "deep_dark_reach"]
    L2 = ["banded_iron_halls", "rust_springs", "ember_gardens"]
    L3 = ["obsidian_tubes", "basalt_galleries", "smolder_groves"]
    ore("lantern_deeps", L0, "copper", "minecraft:deepslate_copper_ore", 12, 14); ore("lantern_deeps", L0, "iron", "minecraft:deepslate_iron_ore", 9, 16)
    ore("lantern_deeps", L0, "gold", "minecraft:deepslate_gold_ore", 7, 5); ore("lantern_deeps", L0, "coal", "minecraft:deepslate_coal_ore", 14, 10)
    ore("blue_reaches", L1, "iron", "minecraft:deepslate_iron_ore", 10, 12); ore("blue_reaches", L1, "lapis", "minecraft:deepslate_lapis_ore", 8, 8)
    ore("blue_reaches", L1, "diamond", "minecraft:deepslate_diamond_ore", 6, 5, 0.5); ore("blue_reaches", L1, "copper", "minecraft:deepslate_copper_ore", 10, 6)
    ore("ember_gardens", L2, "iron", "minecraft:deepslate_iron_ore", 14, 22); ore("ember_gardens", L2, "gold", "minecraft:deepslate_gold_ore", 9, 9)
    ore("ember_gardens", L2, "redstone", "minecraft:deepslate_redstone_ore", 10, 10); ore("ember_gardens", L2, "diamond", "minecraft:deepslate_diamond_ore", 5, 3, 0.5)
    ore("basalt_galleries", L3, "gold", "minecraft:deepslate_gold_ore", 10, 10); ore("basalt_galleries", L3, "diamond", "minecraft:deepslate_diamond_ore", 7, 7, 0.4)
    ore("basalt_galleries", L3, "emerald", "minecraft:deepslate_emerald_ore", 4, 4); ore("basalt_galleries", L3, "redstone", "minecraft:deepslate_redstone_ore", 10, 6)
    # Lantern Deeps
    for biome in ["dripstone_halls"]:
        feat(biome, 7, pf("dripstone_cluster_halls", "minecraft:dripstone_cluster", [count(70), SQ, layer_range("lantern_deeps", 6), BIOME]),
             pf("large_dripstone_halls", "minecraft:large_dripstone", [count(24), SQ, layer_range("lantern_deeps", 6), BIOME]),
             pf("pointed_dripstone_halls", "minecraft:pointed_dripstone", [count(140), SQ, layer_range("lantern_deeps", 4), {"type": "minecraft:count", "count": {"type": "minecraft:uniform", "value": {"min_inclusive": 1, "max_inclusive": 5}}}, {"type": "minecraft:random_offset", "xz_spread": 1, "y_spread": 0}, BIOME]),
             pf("fossil_halls", "minecraft:fossil_coal", [rarity(28), SQ, layer_range("lantern_deeps", 14), BIOME]))
        pile("ochre_crystal", f"{MOD}:ochre_crystal", biome, 10); pile("ochre_crystal_ceiling", f"{MOD}:ochre_crystal", biome, 6, ceiling=True)
    for biome in ["ivory_root_cavern"]:
        plant("hanging_wisteria", f"{MOD}:hanging_wisteria", biome, 80, ceiling=True)
        plant("lantern_fungus_sparse", f"{MOD}:lantern_fungus", biome, 14)
        pid = pf(f"cave_vines_{biome}", "minecraft:cave_vine", [count(120), SQ, layer_range("lantern_deeps"), scan("up", 12, {"type": "minecraft:has_sturdy_face", "direction": "down"}), {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": -1}, BIOME]); feat(biome, 9, pid)
        pid = pf(f"moss_{biome}", moss, [count(30), SQ, layer_range("lantern_deeps"), scan("down", 14), BIOME]); feat(biome, 9, pid)
        pile("ochre_crystal_route", f"{MOD}:ochre_crystal", biome, 5)
    for biome in ["lantern_lake"]:
        plant("lantern_fungus", f"{MOD}:lantern_fungus", biome, 70)
        plant("hanging_wisteria_lake", f"{MOD}:hanging_wisteria", biome, 30, ceiling=True)
        pid = pf(f"moss_{biome}", moss, [count(50), SQ, layer_range("lantern_deeps"), scan("down", 14), BIOME]); feat(biome, 9, pid)
        pid = pf(f"spring_{biome}", springs, [count(30), SQ, layer_range("lantern_deeps", 8), BIOME]); feat(biome, 8, pid)
    for biome in ["amber_hive"]:
        pile("amber_resin", f"{MOD}:amber_resin", biome, 24); pile("amber_resin_ceiling", f"{MOD}:amber_resin", biome, 10, ceiling=True)
        pile("honeycomb", "minecraft:honeycomb_block", biome, 10); pile("ochre_crystal_hive", f"{MOD}:ochre_crystal", biome, 6)
        plant("shelf_fungus_hive", f"{MOD}:shelf_fungus", biome, 20, ceiling=True)
    # Blue Reaches
    for biome in ["asurine_geodes"]:
        geode = cf("asurine_geode", "minecraft:geode", {
            "blocks": {"filling_provider": simple("minecraft:air"), "inner_layer_provider": simple(f"{MOD}:asurine_crystal"),
                       "alternate_inner_layer_provider": simple("minecraft:blue_ice"), "middle_layer_provider": fallback("create:asurine", f"{MOD}:reach_stone"),
                       "outer_layer_provider": simple("minecraft:packed_ice"), "inner_placements": [{"Name": f"{MOD}:asurine_crystal"}],
                       "cannot_replace": "#minecraft:features_cannot_replace", "invalid_blocks": "#minecraft:geode_invalid_blocks"},
            "layers": {"filling": 2.4, "inner_layer": 3.4, "middle_layer": 4.6, "outer_layer": 5.8},
            "crack": {"generate_crack_chance": 0.6, "base_crack_size": 2.5, "crack_point_offset": 3},
            "use_potential_placements_chance": 0.5, "use_alternate_layer0_chance": 0.12, "placements_require_layer0_alternate": False,
            "outer_wall_distance": {"type": "minecraft:uniform", "value": {"min_inclusive": 4, "max_inclusive": 6}},
            "distribution_points": {"type": "minecraft:uniform", "value": {"min_inclusive": 3, "max_inclusive": 5}},
            "point_offset": {"type": "minecraft:uniform", "value": {"min_inclusive": 1, "max_inclusive": 3}},
            "min_gen_offset": -16, "max_gen_offset": 16, "noise_multiplier": 0.05, "invalid_blocks_threshold": 1})
        feat(biome, 7, pf("asurine_geode", geode, [rarity(5), SQ, layer_range("blue_reaches", 26), BIOME]))
        pile("asurine_crystal", f"{MOD}:asurine_crystal", biome, 12); pile("asurine_crystal_ceiling", f"{MOD}:asurine_crystal", biome, 8, ceiling=True)
    for biome in ["deep_blue_rift"]:
        plant("blue_mushroom", f"{MOD}:blue_mushroom", biome, 50)
        plant("lavender_coral", f"{MOD}:lavender_coral", biome, 40, water=True)
        pile("asurine_crystal_rift", f"{MOD}:asurine_crystal", biome, 8); pile("asurine_crystal_rift_ceiling", f"{MOD}:asurine_crystal", biome, 6, ceiling=True)
        pid = pf(f"spring_{biome}", springs, [count(30), SQ, layer_range("blue_reaches", 8), BIOME]); feat(biome, 8, pid)
    for biome in ["silver_fern_rift"]:
        plant("silver_fern", f"{MOD}:silver_fern", biome, 120)
        plant("blue_mushroom_fern", f"{MOD}:blue_mushroom", biome, 20)
        pid = pf(f"moss_{biome}", moss, [count(60), SQ, layer_range("blue_reaches"), scan("down", 14), BIOME]); feat(biome, 9, pid)
        pid = pf(f"spring_{biome}", springs, [count(50), SQ, layer_range("blue_reaches", 8), BIOME]); feat(biome, 8, pid)
    for biome in ["deep_dark_reach"]:
        feat(biome, 7, pf("sculk_patch_reach", "minecraft:sculk_patch_deep_dark", [count(200), SQ, layer_range("blue_reaches"), BIOME]),
             pf("sculk_vein_reach", "minecraft:sculk_vein", [count(20), SQ, layer_range("blue_reaches"), BIOME]))
    # Ember Gardens
    for biome in ["banded_iron_halls"]:
        pile("hematite_crystal", f"{MOD}:hematite_crystal", biome, 14); pile("hematite_crystal_ceiling", f"{MOD}:hematite_crystal", biome, 8, ceiling=True)
    for biome in ["rust_springs"]:
        pile("rusted_gear", f"{MOD}:rusted_gear", biome, 16); pile("rusted_pipe", f"{MOD}:rusted_pipe", biome, 16)
        pile("hematite_crystal_springs", f"{MOD}:hematite_crystal", biome, 6)
        plant("shelf_fungus_rust", f"{MOD}:shelf_fungus", biome, 24, ceiling=True)
        pid = pf(f"spring_{biome}", springs, [count(50), SQ, layer_range("ember_gardens", 8), BIOME]); feat(biome, 8, pid)
    for biome in ["ember_gardens"]:
        plant("heat_fungus", f"{MOD}:heat_fungus", biome, 90)
        plant("shelf_fungus_ember", f"{MOD}:shelf_fungus", biome, 40, ceiling=True)
        pid = pf(f"moss_{biome}", moss, [count(30), SQ, layer_range("ember_gardens"), scan("down", 14), BIOME]); feat(biome, 9, pid)
    # Basalt Galleries
    for biome in ["obsidian_tubes"]:
        pile("magma_tubes", "minecraft:magma_block", biome, 8)
    for biome in ["basalt_galleries"]:
        feat(biome, 7, pf("basalt_columns_galleries", cf("basalt_columns_tall", "minecraft:basalt_columns", {"height": {"type": "minecraft:uniform", "value": {"min_inclusive": 4, "max_inclusive": 10}}, "reach": {"type": "minecraft:uniform", "value": {"min_inclusive": 2, "max_inclusive": 3}}}), [count(6), SQ, layer_range("basalt_galleries", 6), scan("down", 16), BIOME]))
        plant("shelf_fungus_basalt", f"{MOD}:shelf_fungus", biome, 15, ceiling=True)
        pile("magma_galleries", "minecraft:magma_block", biome, 6)
    for biome in ["smolder_groves"]:
        plant("heat_fungus_grove", f"{MOD}:heat_fungus", biome, 130)
        pile("magma_grove", "minecraft:magma_block", biome, 10)
        plant("shelf_fungus_grove", f"{MOD}:shelf_fungus", biome, 20, ceiling=True)
    # Rootvaults (above the first band): copies of vanilla lush-cave features so no feature order clashes
    rv = "rootvaults"
    hr = {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform", "min_inclusive": {"above_bottom": 2}, "max_inclusive": {"absolute": 60}}}
    feat(rv, 7, pf("glow_lichen_rootvaults", lichen, [count(40), SQ, hr, BIOME]))
    feat(rv, 9, pf("moss_rootvaults", moss, [count(60), SQ, hr, scan("down", 14), BIOME]),
         pf("rooted_azalea_rootvaults", "minecraft:rooted_azalea_tree", [count(20), SQ, hr, scan("up", 12, {"type": "minecraft:has_sturdy_face", "direction": "down"}), {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": -1}, BIOME]),
         pf("cave_vines_rootvaults", "minecraft:cave_vine", [count(100), SQ, hr, scan("up", 12, {"type": "minecraft:has_sturdy_face", "direction": "down"}), {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": -1}, BIOME]),
         pf("spore_blossom_rootvaults", "minecraft:spore_blossom", [count(25), SQ, hr, scan("up", 12, {"type": "minecraft:has_sturdy_face", "direction": "down"}), {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": -1}, BIOME]),
         pf("clay_rootvaults", "minecraft:lush_caves_clay", [count(20), SQ, hr, scan("down", 14), BIOME]))
    feat(rv, 8, pf("spring_rootvaults", "minecraft:spring_water", [count(20), SQ, hr, BIOME]))

# ----------------------------------------------------------------------------------------------- biomes
def biome(name, fog, water, water_fog, sky, grass=None, foliage=None, music=None, mood="minecraft:ambient.cave", particle=None, spawners=None, temp=0.6):
    steps = [[] for _ in range(11)]
    for step, ids in FEATURES.get(name, {}).items(): steps[step].extend(ids)
    effects = {"fog_color": fog, "water_color": water, "water_fog_color": water_fog, "sky_color": sky,
               "mood_sound": {"sound": mood, "tick_delay": 6000, "block_search_extent": 8, "offset": 2.0}}
    if grass: effects["grass_color"] = grass
    if foliage: effects["foliage_color"] = foliage
    if music: effects["music"] = {"sound": music, "min_delay": 12000, "max_delay": 24000, "replace_current_music": False}
    if particle: effects["particle"] = particle
    obj = {"temperature": temp, "downfall": 0.6, "has_precipitation": False, "effects": effects,
           "carvers": {}, "features": steps, "spawners": spawners or {"monster": [], "creature": [], "ambient": [], "axolotls": [], "underground_water_creature": [], "water_creature": [], "water_ambient": [], "misc": []},
           "spawn_costs": {}}
    data("biome", name, obj)

def biomes():
    amber = dict(fog=0x3a2a10, water=0x6a8a3a, water_fog=0x2a3a10, sky=0x2a1e0a, grass=0x9aa040, foliage=0x8a9a30, music="minecraft:music.overworld.dripstone_caves")
    blue = dict(fog=0x0c1a2e, water=0x2a6ac0, water_fog=0x0a2048, sky=0x0a1424, grass=0x7aa0a0, foliage=0x6a9090, music="minecraft:music.overworld.deep_dark")
    ember = dict(fog=0x2e1206, water=0x6a4a20, water_fog=0x2a1a08, sky=0x1a0a04, grass=0xc07a30, foliage=0xb06a20, music="minecraft:music.overworld.lush_caves")
    basalt = dict(fog=0x120c0c, water=0x4a3a2a, water_fog=0x1a1008, sky=0x0a0606, grass=0x806050, foliage=0x705040, music="minecraft:music.overworld.dripstone_caves")
    biome("rootvaults", 0x1e3a1e, 0x3f8a4f, 0x0e3a1a, 0x1a2e1a, grass=0x5fa84a, foliage=0x4a9a3a, music="minecraft:music.overworld.lush_caves",
          spawners={"monster": [{"type": "minecraft:creeper", "weight": 100, "minCount": 1, "maxCount": 2}], "creature": [], "ambient": [{"type": "minecraft:bat", "weight": 10, "minCount": 4, "maxCount": 8}],
                    "axolotls": [{"type": "minecraft:axolotl", "weight": 10, "minCount": 2, "maxCount": 4}], "underground_water_creature": [{"type": "minecraft:glow_squid", "weight": 10, "minCount": 2, "maxCount": 4}],
                    "water_creature": [], "water_ambient": [], "misc": []}, temp=0.7)
    biome("dripstone_halls", **amber); biome("ivory_root_cavern", **amber); biome("lantern_lake", **amber)
    biome("amber_hive", **{**amber, "fog": 0x4a3010})
    biome("asurine_geodes", **{**blue, "fog": 0x10203a}, temp=0.1); biome("deep_blue_rift", **blue, temp=0.2); biome("silver_fern_rift", **blue, temp=0.3)
    biome("deep_dark_reach", **{**blue, "fog": 0x080c14, "water": 0x1a3a60}, temp=0.2)
    biome("banded_iron_halls", **ember); biome("rust_springs", **ember); biome("ember_gardens", **{**ember, "fog": 0x3a1a08}, temp=0.9)
    biome("obsidian_tubes", **basalt, temp=1.2); biome("basalt_galleries", **basalt, temp=1.2); biome("smolder_groves", **{**basalt, "fog": 0x1e1008}, temp=1.5)

def tags():
    mine = [f"{MOD}:{b}" for b in SOLIDS if b != "rune_block"] + [f"{MOD}:{b}" for b in CRYSTALS]
    tag("minecraft", "blocks", "mineable/pickaxe", mine)
    tag("minecraft", "blocks", "needs_stone_tool", [f"{MOD}:banded_iron"])
    stones = [f"{MOD}:lantern_stone", f"{MOD}:reach_stone", f"{MOD}:ember_stone", f"{MOD}:gallery_stone"]
    for t in ["base_stone_overworld", "moss_replaceable", "dripstone_replaceable_blocks", "sculk_replaceable", "lush_ground_replaceable", "azalea_root_replaceable", "features_cannot_replace"]:
        tag("minecraft", "blocks", t, stones if t != "features_cannot_replace" else [f"{MOD}:band_stone", f"{MOD}:rune_block"])
    tag("minecraft", "blocks", "wither_immune", [f"{MOD}:band_stone", f"{MOD}:rune_block"])
    tag("minecraft", "blocks", "dragon_immune", [f"{MOD}:band_stone", f"{MOD}:rune_block"] + stones)
    tag("minecraft", "blocks", "geode_invalid_blocks", [f"{MOD}:band_stone"])
    tag(MOD, "blocks", "cave_stone", [b for b in ALL_STONES if not b.startswith("create:")] + [{"id": b, "required": False} for b in ALL_STONES if b.startswith("create:")])
    tag(MOD, "blocks", "layer_stone", stones)
    tag(MOD, "items", "rune_holders", ["minecraft:wooden_sword","minecraft:stone_sword","minecraft:iron_sword","minecraft:golden_sword","minecraft:diamond_sword","minecraft:netherite_sword",
        "minecraft:wooden_pickaxe","minecraft:stone_pickaxe","minecraft:iron_pickaxe","minecraft:golden_pickaxe","minecraft:diamond_pickaxe","minecraft:netherite_pickaxe",
        "minecraft:wooden_axe","minecraft:stone_axe","minecraft:iron_axe","minecraft:golden_axe","minecraft:diamond_axe","minecraft:netherite_axe",
        "minecraft:wooden_shovel","minecraft:stone_shovel","minecraft:iron_shovel","minecraft:golden_shovel","minecraft:diamond_shovel","minecraft:netherite_shovel",
        "minecraft:wooden_hoe","minecraft:stone_hoe","minecraft:iron_hoe","minecraft:golden_hoe","minecraft:diamond_hoe","minecraft:netherite_hoe",
        "minecraft:bow","minecraft:crossbow","minecraft:trident","minecraft:shield","minecraft:fishing_rod",
        {"id": "#forge:tools", "required": False}, {"id": "#minecraft:swords", "required": False}, {"id": "#minecraft:pickaxes", "required": False}, {"id": "#minecraft:axes", "required": False}, {"id": "#minecraft:shovels", "required": False}, {"id": "#minecraft:hoes", "required": False}])
    tag(MOD, "entity_types", "hunters", [f"{MOD}:climber", f"{MOD}:ambusher", f"{MOD}:burrower", f"{MOD}:smasher"])
    tag(MOD, "entity_types", "nest_creatures", [f"{MOD}:swarmer", f"{MOD}:armored", f"{MOD}:spitter"])
    tag(MOD, "entity_types", "remix_creatures", [f"{MOD}:swarmer", f"{MOD}:armored", f"{MOD}:spitter", f"{MOD}:climber", f"{MOD}:ambusher", f"{MOD}:burrower", f"{MOD}:smasher"])
    deep = [f"{MOD}:{b}" for b in LAYER_OF]
    tag(MOD, "worldgen/biome", "is_deep", deep)
    tag(MOD, "worldgen/biome", "is_refuge", [f"{MOD}:dripstone_halls", f"{MOD}:asurine_geodes", f"{MOD}:banded_iron_halls", f"{MOD}:obsidian_tubes"])
    tag(MOD, "worldgen/biome", "is_hunting", [f"{MOD}:lantern_lake", f"{MOD}:amber_hive", f"{MOD}:silver_fern_rift", f"{MOD}:deep_dark_reach", f"{MOD}:ember_gardens", f"{MOD}:smolder_groves"])
    tag(MOD, "worldgen/biome", "is_route", [f"{MOD}:ivory_root_cavern", f"{MOD}:deep_blue_rift", f"{MOD}:rust_springs", f"{MOD}:basalt_galleries"])
    tag(MOD, "worldgen/biome", "is_upper_cave", [f"{MOD}:rootvaults", "minecraft:lush_caves"])
    tag("forge", "worldgen/biome", "is_cave", deep + [f"{MOD}:rootvaults"])
    tag("forge", "worldgen/biome", "is_underground", deep + [f"{MOD}:rootvaults"])
    tag("minecraft", "worldgen/biome", "is_overworld", [f"{MOD}:rootvaults"])

def biome_modifiers():
    write(os.path.join(RES, "data", MOD, "forge/biome_modifier/remove_create_striations.json"), {"type": f"{MOD}:remove_create_striations"})
    pf("gate_tube", cf("gate_tube", f"{MOD}:gate_tube", {}), [])
    write(os.path.join(RES, "data", MOD, "forge/biome_modifier/gate_tube_overworld.json"),
          {"type": "forge:add_features", "biomes": "#minecraft:is_overworld", "features": f"{MOD}:gate_tube", "step": "raw_generation"})
    write(os.path.join(RES, "data", MOD, "forge/biome_modifier/gate_tube_deep.json"),
          {"type": "forge:add_features", "biomes": f"#{MOD}:is_deep", "features": f"{MOD}:gate_tube", "step": "raw_generation"})

def enchanting_removal():
    # The enchanting table recipe only loads while the table is allowed (config disableEnchantingTable = false).
    write(os.path.join(RES, "data/minecraft/recipes/enchanting_table.json"), {
        "type": "minecraft:crafting_shaped",
        "conditions": [{"type": "forge:not", "value": {"type": f"{MOD}:config", "key": "disableEnchantingTable"}}],
        "category": "decorations",
        "pattern": [" B ", "D#D", "###"],
        "key": {"#": {"item": "minecraft:obsidian"}, "B": {"item": "minecraft:book"}, "D": {"item": "minecraft:diamond"}},
        "result": {"item": "minecraft:enchanting_table"}})
    # Global loot modifier: every loot table hands out clean gear, plain books and no bottles o' enchanting.
    write(os.path.join(RES, "data", MOD, "loot_modifiers/strip_enchantments.json"), {"type": f"{MOD}:strip_enchantments", "conditions": []})
    write(os.path.join(RES, "data/forge/loot_modifiers/global_loot_modifiers.json"), {"replace": False, "entries": [f"{MOD}:strip_enchantments"]})

if __name__ == "__main__":
    cave_functions(); features(); biomes(); tags(); biome_modifiers(); textures(); models_and_states(); lang(); enchanting_removal()
    gen_runes.runes(RES); gen_runes.item_assets(ASSETS)
    gen_creatures.generate(RES, ASSETS)
    print("generated", sum(len(f) for _, _, f in os.walk(RES)), "resource files")
