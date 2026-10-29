#!/usr/bin/env python3
"""Generates Exploration Remix's datapack JSON, blockstates, models, lang and textures.

Run from anywhere: python3 tools/gen_data.py
Everything this script writes lives in folders it owns, which it clears first, so delete a block or biome here and
its files disappear on the next run. Edit this script, not the JSON.
"""
import json, os, shutil
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import textures
import worldgen
import sounds

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src/main/resources")
MOD = "explorationremix"
DATA = os.path.join(RES, "data", MOD)
ASSETS = os.path.join(RES, "assets", MOD)

OWNED = [
    os.path.join(DATA, "worldgen"), os.path.join(DATA, "tags"), os.path.join(DATA, "forge"), os.path.join(DATA, "loot_tables"),
    os.path.join(RES, "data", "minecraft", "tags"), os.path.join(RES, "data", "forge", "tags"),
    os.path.join(ASSETS, "blockstates"), os.path.join(ASSETS, "models"), os.path.join(ASSETS, "textures"), os.path.join(ASSETS, "lang"),
    os.path.join(ASSETS, "sounds.json"), os.path.join(ASSETS, "particles"),
]


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def tag(ns, kind, name, values, replace=False): write(os.path.join(RES, "data", ns, "tags", kind, name + ".json"), {"replace": replace, "values": values})


# =============================================================================================== blocks
# Every block the mod adds: id -> (English name, kind) or (English name, kind, options). Kinds decide the model,
# blockstate, item and drop:
#   cube        a full block with one texture
#   cross       a plant drawn as two crossed planes (also single hanging plants and underwater plants)
#   vine        the growing tip of a hanging vine; "vine_body:<tip>" is the length above it, which drops the tip's item
#   crystal     a cluster that grows on any face, turned like vanilla's amethyst clusters
#   coral_fan   a flat fan, like vanilla's coral fans
#   shelf       bracket fungus on a wall, turned to face away from it
#   tall        a two-block plant: textures <id>_bottom and <id>_top; drops from its lower half only
#   lichen      a sheet on any of the six faces, like glow lichen (a GlowLichenBlock); drops one per face
#   carpet      a flat sheet lying on the floor, turned at random
#   wall_plant  a plant growing sideways out of a wall, drawn with vanilla's coral wall fan model
# Options: variants (how many textures: <id>, <id>_2, <id>_3; the game picks one per block from its position, so it
# never changes), weights (one per variant), glow_under (shelf: the whole underside glows). A texture with an entry
# in textures.GLOW gets an emissive overlay.
BLOCKS = {
    "band_stone": ("Band Stone", "cube"),
    "white_glow_flower": ("White Glow Flower", "cross"),
    "lantern_fungus": ("Lantern Fungus", "cross", {"variants": 2}),
    "lantern_roots": ("Lantern Roots", "cross"),
    "wisteria": ("Wisteria", "vine"),
    "wisteria_plant": ("Wisteria", "vine_body:wisteria"),
    "lantern_pod": ("Lantern Pod", "cross"),
    "lavender_flower": ("Lavender Flower", "cross"),
    "gold_flower": ("Gold Flower", "cross"),
    "asurine_crystal": ("Asurine Crystal", "crystal", {"variants": 2}),
    "blue_glow_mushroom": ("Blue Glow Mushroom", "cross", {"variants": 3}),
    "blue_glow_vines": ("Blue Glow Vines", "vine"),
    "blue_glow_vines_plant": ("Blue Glow Vines", "vine_body:blue_glow_vines"),
    "silver_fern": ("Silver Fern", "cross", {"variants": 3}),
    "orange_shelf_fungus": ("Orange Shelf Fungus", "shelf"),
    "lavender_coral": ("Lavender Coral", "cross"),
    "pale_coral_fan": ("Pale Coral Fan", "coral_fan"),
    "ember_vines": ("Ember Vines", "vine"),
    "ember_vines_plant": ("Ember Vines", "vine_body:ember_vines"),
    # ---- vegetation rework, part A: Upper Caves and Blue Reaches
    "amber_bulb_vine": ("Amber Bulb Vine", "vine", {"variants": 2}),
    "amber_bulb_vine_plant": ("Amber Bulb Vine", "vine_body:amber_bulb_vine", {"variants": 3}),
    "weeping_ivy": ("Weeping Ivy", "vine", {"variants": 2}),
    "weeping_ivy_plant": ("Weeping Ivy", "vine_body:weeping_ivy", {"variants": 3}),
    "grotto_ivy": ("Grotto Ivy", "lichen", {"variants": 2}),
    "starmoss": ("Starmoss", "carpet", {"variants": 2}),
    "silver_frond": ("Silver Frond", "vine", {"variants": 2}),
    "silver_frond_plant": ("Silver Frond", "vine_body:silver_frond", {"variants": 2}),
    "tall_silver_fern": ("Tall Silver Fern", "tall", {"variants": 2}),
    "silver_wall_fern": ("Silver Wall Fern", "wall_plant", {"variants": 2}),
    "ivory_bracket": ("Ivory Bracket", "shelf", {"variants": 2, "glow_under": True}),
    "gold_threads": ("Gold Threads", "vine", {"variants": 2}),
    "gold_threads_plant": ("Gold Threads", "vine_body:gold_threads", {"variants": 2}),
    "sage_bush": ("Sage Bush", "cross", {"variants": 2}),
    "glowbell": ("Glowbell", "cross", {"variants": 2}),
    "pagoda_fungus": ("Pagoda Fungus", "tall"),
    "blue_lichen": ("Blue Lichen", "lichen", {"variants": 2}),
    "asurine_bud": ("Asurine Bud", "crystal", {"variants": 2}),
    "dusk_vines": ("Dusk Vines", "vine"),
    "dusk_vines_plant": ("Dusk Vines", "vine_body:dusk_vines", {"variants": 2}),
}
NO_DROP = {"band_stone"}

# Blocks in vanilla tags: vines climb like weeping vines, and lantern pods can hang from wisteria.
BLOCK_TAGS = {
    ("minecraft", "climbable"): ["wisteria", "wisteria_plant", "blue_glow_vines", "blue_glow_vines_plant", "ember_vines", "ember_vines_plant",
                                 # vegetation rework, part A: the new vines, and the ivy on the walls
                                 "amber_bulb_vine", "amber_bulb_vine_plant", "weeping_ivy", "weeping_ivy_plant", "silver_frond",
                                 "silver_frond_plant", "gold_threads", "gold_threads_plant", "dusk_vines", "dusk_vines_plant", "grotto_ivy"],
    ("minecraft", "mineable/pickaxe"): ["asurine_crystal", "asurine_bud"],
    ("minecraft", "mineable/axe"): ["orange_shelf_fungus", "ivory_bracket", "grotto_ivy", "blue_lichen"],
    (MOD, "hanging_supports"): ["wisteria", "wisteria_plant", "lantern_roots"],
}
# Vanilla growth that /remix census counts as plants, with every block above but band stone (tag explorationremix:plants).
VANILLA_PLANTS = ["minecraft:cave_vines", "minecraft:cave_vines_plant", "minecraft:glow_lichen", "minecraft:moss_carpet", "minecraft:fern",
                  "minecraft:grass", "minecraft:large_fern", "minecraft:hanging_roots", "minecraft:azalea", "minecraft:flowering_azalea",
                  "minecraft:brown_mushroom", "minecraft:sea_pickle", "minecraft:seagrass", "minecraft:tall_seagrass", "minecraft:spore_blossom"]

# A crystal points along its facing; the model points up. These are vanilla amethyst's turns.
FACE6 = {"down": {"x": 180}, "east": {"x": 90, "y": 90}, "north": {"x": 90}, "south": {"x": 90, "y": 180}, "up": {}, "west": {"x": 90, "y": 270}}
# The shelf and wall plant models stick out north from a wall to their south.
FACE4 = {"north": {}, "east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270}}
# A sheet's model lies against the block to its north; vanilla glow lichen's turns for the other five faces.
LICHEN_TURNS = {"north": {}, "east": {"y": 90, "uvlock": True}, "south": {"y": 180, "uvlock": True}, "west": {"y": 270, "uvlock": True},
                "up": {"x": 270, "uvlock": True}, "down": {"x": 90, "uvlock": True}}
FACES = ["down", "east", "north", "south", "up", "west"]

# Model templates (models/block/template/<name>.json). The *_glow ones draw each plane a second time with the overlay
# texture #glow at full block light (Forge's forge_data), so the overlay's pixels glow in the dark. The light is baked
# into the quads, which vanilla's renderer and Embeddium both merge with the world's light.
EMISSIVE = {"forge_data": {"block_light": 15}}


def _cross_planes(key, extra):
    """Vanilla's cross: two planes turned 45 degrees."""
    turn = {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": True}
    return [dict({"from": [0.8, 0, 8], "to": [15.2, 16, 8], "rotation": turn, "shade": False,
                  "faces": {"north": {"uv": [0, 0, 16, 16], "texture": key}, "south": {"uv": [0, 0, 16, 16], "texture": key}}}, **extra),
            dict({"from": [8, 0, 0.8], "to": [8, 16, 15.2], "rotation": turn, "shade": False,
                  "faces": {"west": {"uv": [0, 0, 16, 16], "texture": key}, "east": {"uv": [0, 0, 16, 16], "texture": key}}}, **extra)]


def _flat(key, extra):
    """A plane just above the floor."""
    return [dict({"from": [0, 0.25, 0], "to": [16, 0.25, 16],
                  "faces": {"up": {"uv": [0, 0, 16, 16], "texture": key}, "down": {"uv": [0, 16, 16, 0], "texture": key}}}, **extra)]


def _sheet(key, extra):
    """Vanilla glow lichen's plane, just off the face of the block to the north."""
    return [dict({"from": [0, 0, 0.1], "to": [16, 16, 0.1],
                  "faces": {"north": {"uv": [16, 0, 0, 16], "texture": key}, "south": {"uv": [0, 0, 16, 16], "texture": key}}}, **extra)]


TEMPLATES = {
    "cross_glow": {"ambientocclusion": False, "textures": {"particle": "#cross"},
                   "elements": _cross_planes("#cross", {}) + _cross_planes("#glow", EMISSIVE)},
    "carpet": {"ambientocclusion": False, "textures": {"particle": "#texture"}, "elements": _flat("#texture", {})},
    "carpet_glow": {"ambientocclusion": False, "textures": {"particle": "#texture"}, "elements": _flat("#texture", {}) + _flat("#glow", EMISSIVE)},
    "lichen": {"ambientocclusion": False, "textures": {"particle": "#texture"}, "elements": _sheet("#texture", {})},
    "lichen_glow": {"ambientocclusion": False, "textures": {"particle": "#texture"}, "elements": _sheet("#texture", {}) + _sheet("#glow", EMISSIVE)},
}


def shelf_model(tex, under, glow_under=False):
    m = {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "textures": {"particle": tex, "top": tex, "under": under},
        "elements": [{
            "from": [1, 5, 6], "to": [15, 9, 16],
            "faces": {
                # top: texture rows 0-9, the pale rim at the open edge; under: rows 0-9, gills fanning from the wall
                "up": {"uv": [1, 0, 15, 10], "texture": "#top"},
                "down": {"uv": [1, 0, 15, 10], "texture": "#under"},
                # the edge strip is rows 12-15 of the top texture
                "north": {"uv": [1, 12, 15, 16], "texture": "#top"},
                "east": {"uv": [0, 12, 10, 16], "texture": "#top"},
                "west": {"uv": [0, 12, 10, 16], "texture": "#top"},
            },
        }],
    }
    if glow_under:
        m["elements"][0]["faces"]["down"].update(EMISSIVE)
    return m


def ref(tex):
    return f"{MOD}:block/{tex}"


def variant_names(base, n):
    """base, base_2, base_3 ...: the texture and model names of a block's variants."""
    return [base] + [f"{base}_{i}" for i in range(2, n + 1)]


def pick(entries, weights=None):
    """A blockstate value: one model, or a list the game picks from by the block's position (weights optional)."""
    if weights:
        assert len(weights) == len(entries), entries
        entries = [dict(e, weight=w) for e, w in zip(entries, weights)]
    return entries[0] if len(entries) == 1 else entries


def plane_model(tex, plain_parent, template, key):
    """A cross, carpet or lichen model, drawn with the glow overlay when its texture has one."""
    t = {key: ref(tex)}
    if f"block/{tex}" in textures.GLOW:
        t["glow"] = ref(tex + "_glow")
        return {"parent": f"{MOD}:block/template/{template}_glow", "render_type": "minecraft:cutout", "textures": t}
    return {"parent": plain_parent, "render_type": "minecraft:cutout", "textures": t}


def loot(bid, drop, kind):
    entry = {"type": "minecraft:item", "name": f"{MOD}:{drop}"}
    if kind == "tall":
        # both halves break together; only the lower half's table drops the plant
        entry["conditions"] = [{"condition": "minecraft:block_state_property", "block": f"{MOD}:{bid}", "properties": {"half": "lower"}}]
    elif kind == "lichen":
        # one for each face it covers, like vanilla's glow lichen (which needs shears; these don't)
        entry["functions"] = [{"function": "minecraft:set_count", "add": True, "count": 1,
                               "conditions": [{"condition": "minecraft:block_state_property", "block": f"{MOD}:{bid}", "properties": {f: "true"}}]}
                              for f in FACES] + [{"function": "minecraft:set_count", "add": True, "count": -1}]
    return {"type": "minecraft:block",
            "pools": [{"rolls": 1, "bonus_rolls": 0, "entries": [entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}]}


def block_assets():
    lang = {}
    used_glow = set()
    for name, model in TEMPLATES.items():
        write(os.path.join(ASSETS, "models", "block", "template", name + ".json"), model)
    for bid, entry in BLOCKS.items():
        name, kind = entry[0], entry[1]
        opts = entry[2] if len(entry) > 2 else {}
        texs = variant_names(bid, opts.get("variants", 1))
        weights = opts.get("weights")
        lang[f"block.{MOD}.{bid}"] = name
        models = {}
        item = {"parent": "minecraft:item/generated", "textures": {"layer0": ref(bid)}}
        drop = bid
        if kind == "cube":
            models[bid] = {"parent": "minecraft:block/cube_all", "textures": {"all": ref(bid)}}
            states = {"variants": {"": {"model": ref(bid)}}}
            item = {"parent": ref(bid)}
        elif kind in ("cross", "vine", "crystal") or kind.startswith("vine_body:"):
            for t in texs:
                models[t] = plane_model(t, "minecraft:block/cross", "cross", "cross")
            if kind == "crystal":
                states = {"variants": {f"facing={f}": pick([dict({"model": ref(t)}, **turn) for t in texs], weights) for f, turn in FACE6.items()}}
            else:
                states = {"variants": {"": pick([{"model": ref(t)} for t in texs], weights)}}
            if kind.startswith("vine_body:"):
                item = None
                drop = kind.split(":", 1)[1]
        elif kind == "tall":
            bottoms, tops = variant_names(bid + "_bottom", len(texs)), variant_names(bid + "_top", len(texs))
            for t in bottoms + tops:
                models[t] = plane_model(t, "minecraft:block/cross", "cross", "cross")
            # Lists of the same length and order: both halves are seeded from the lower half's position, so variant k's
            # top always sits on variant k's bottom.
            states = {"variants": {"half=lower": pick([{"model": ref(t)} for t in bottoms], weights),
                                   "half=upper": pick([{"model": ref(t)} for t in tops], weights)}}
            item = {"parent": "minecraft:item/generated", "textures": {"layer0": ref(tops[0])}}
        elif kind == "carpet":
            for t in texs:
                models[t] = plane_model(t, f"{MOD}:block/template/carpet", "carpet", "texture")
            states = {"variants": {"": pick([{"model": ref(t), "y": r} for t in texs for r in (0, 90, 180, 270)])}}
        elif kind == "lichen":
            for t in texs:
                models[t] = plane_model(t, f"{MOD}:block/template/lichen", "lichen", "texture")
            # Vanilla's multipart: a part per face, and every part when no face is set (as vanilla draws that state).
            # All parts of one block share a random seed, so all its faces show the same variant.
            parts = []
            for face, turn in LICHEN_TURNS.items():
                apply = pick([dict({"model": ref(t)}, **turn) for t in texs], weights)
                parts.append({"when": {face: "true"}, "apply": apply})
                parts.append({"when": {f: "false" for f in FACES}, "apply": apply})
            states = {"multipart": parts}
        elif kind == "wall_plant":
            for t in texs:
                models[t] = {"parent": "minecraft:block/coral_wall_fan", "render_type": "minecraft:cutout", "textures": {"fan": ref(t)}}
            states = {"variants": {f"facing={f}": pick([dict({"model": ref(t)}, **turn) for t in texs], weights) for f, turn in FACE4.items()}}
        elif kind == "coral_fan":
            for t in texs:
                models[t] = {"parent": "minecraft:block/coral_fan", "render_type": "minecraft:cutout", "textures": {"fan": ref(t)}}
            states = {"variants": {"": pick([{"model": ref(t)} for t in texs], weights)}}
        elif kind == "shelf":
            for t in texs:
                models[t] = shelf_model(ref(t), ref(bid + "_under"), opts.get("glow_under", False))
            states = {"variants": {f"facing={f}": pick([dict({"model": ref(t)}, **turn) for t in texs], weights) for f, turn in FACE4.items()}}
            item = {"parent": ref(bid)}
        else:
            raise ValueError(f"{bid}: unknown kind {kind}")
        for mname, m in models.items():
            for key, tex in m["textures"].items():
                painted = "block/" + tex.split("block/", 1)[1]
                if key == "glow":
                    used_glow.add(painted[:-len("_glow")])
                else:
                    assert painted in textures.PAINTERS, f"{bid}: no painter for {tex}"
            write(os.path.join(ASSETS, "models", "block", mname + ".json"), m)
        write(os.path.join(ASSETS, "blockstates", bid + ".json"), states)
        if item is not None:
            write(os.path.join(ASSETS, "models", "item", bid + ".json"), item)
        if bid not in NO_DROP:
            write(os.path.join(DATA, "loot_tables", "blocks", bid + ".json"), loot(bid, drop, kind))
    unused = set(textures.GLOW) - used_glow
    assert not unused, f"glow overlays no model draws: {sorted(unused)}"
    lang[f"itemGroup.{MOD}"] = "Exploration Remix"
    return lang


# =============================================================================================== biomes
# The biomes, their features and the block tags they use live in worldgen.py.


def tags():
    # Band stone: nothing breaks it, moves it or grows through it.
    for t in ("dragon_immune", "wither_immune", "features_cannot_replace", "geode_invalid_blocks"):
        tag("minecraft", "blocks", t, [f"{MOD}:band_stone"])
    for (ns, name), ids in BLOCK_TAGS.items():
        tag(ns, "blocks", name, [f"{MOD}:{b}" for b in ids])
    tag(MOD, "blocks", "plants", [f"{MOD}:{b}" for b in BLOCKS if b not in NO_DROP] + VANILLA_PLANTS)


def biome_modifiers():
    write(os.path.join(DATA, "forge", "biome_modifier", "remove_create_striations.json"), {"type": f"{MOD}:remove_create_striations"})


def lang(extra):
    out = dict(extra)
    write(os.path.join(ASSETS, "lang", "en_us.json"), dict(sorted(out.items())))


# The mod's particle types (ERParticles) reuse vanilla sprites; CaveParticles tints and animates them.
PARTICLES = {
    "steam": [f"minecraft:big_smoke_{i}" for i in range(12)],
    "ember": ["minecraft:generic_0", "minecraft:generic_1"],
    "pollen": ["minecraft:generic_0"],
}


def particles():
    for name, sprites in PARTICLES.items():
        write(os.path.join(ASSETS, "particles", name + ".json"), {"textures": sprites})


def main():
    for d in OWNED:
        if os.path.isdir(d): shutil.rmtree(d)
        elif os.path.isfile(d): os.remove(d)
    names = block_assets()
    textures.write_all(os.path.join(ASSETS, "textures"))
    names.update(worldgen.write_all(DATA, RES, write))
    tags()
    biome_modifiers()
    names.update(sounds.write_all(ASSETS, write))
    particles()
    lang(names)
    print("data written")


if __name__ == "__main__":
    main()
