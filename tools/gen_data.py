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
# Every block the mod adds: id -> (English name, kind). Kinds decide the model, blockstate, item and drop:
#   cube       a full block with one texture
#   cross      a plant drawn as two crossed planes (also single hanging plants)
#   vine       the growing tip of a hanging vine; "vine_body:<tip>" is the length above it, which drops the tip's item
#   crystal    a cluster that grows on any face, turned like vanilla's amethyst clusters
#   coral_fan  a flat fan, like vanilla's coral fans
#   shelf      bracket fungus on a wall, turned to face away from it
BLOCKS = {
    "band_stone": ("Band Stone", "cube"),
    "white_glow_flower": ("White Glow Flower", "cross"),
    "lantern_fungus": ("Lantern Fungus", "cross"),
    "lantern_roots": ("Lantern Roots", "cross"),
    "wisteria": ("Wisteria", "vine"),
    "wisteria_plant": ("Wisteria", "vine_body:wisteria"),
    "lantern_pod": ("Lantern Pod", "cross"),
    "lavender_flower": ("Lavender Flower", "cross"),
    "gold_flower": ("Gold Flower", "cross"),
    "asurine_crystal": ("Asurine Crystal", "crystal"),
    "blue_glow_mushroom": ("Blue Glow Mushroom", "cross"),
    "blue_glow_vines": ("Blue Glow Vines", "vine"),
    "blue_glow_vines_plant": ("Blue Glow Vines", "vine_body:blue_glow_vines"),
    "silver_fern": ("Silver Fern", "cross"),
    "orange_shelf_fungus": ("Orange Shelf Fungus", "shelf"),
    "lavender_coral": ("Lavender Coral", "cross"),
    "pale_coral_fan": ("Pale Coral Fan", "coral_fan"),
    "ember_vines": ("Ember Vines", "vine"),
    "ember_vines_plant": ("Ember Vines", "vine_body:ember_vines"),
}
NO_DROP = {"band_stone"}

# Blocks in vanilla tags: vines climb like weeping vines, and lantern pods can hang from wisteria.
BLOCK_TAGS = {
    ("minecraft", "climbable"): ["wisteria", "wisteria_plant", "blue_glow_vines", "blue_glow_vines_plant", "ember_vines", "ember_vines_plant"],
    ("minecraft", "mineable/pickaxe"): ["asurine_crystal"],
    ("minecraft", "mineable/axe"): ["orange_shelf_fungus"],
    (MOD, "hanging_supports"): ["wisteria", "wisteria_plant", "lantern_roots"],
}

# A crystal points along its facing; the model points up. These are vanilla amethyst's turns.
FACE6 = {"down": {"x": 180}, "east": {"x": 90, "y": 90}, "north": {"x": 90}, "south": {"x": 90, "y": 180}, "up": {}, "west": {"x": 90, "y": 270}}
# The shelf model sticks out north from a wall to its south.
FACE4 = {"north": {}, "east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270}}


def shelf_model(bid):
    tex = f"{MOD}:block/{bid}"
    return {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "textures": {"particle": tex, "top": tex, "under": tex + "_under"},
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


def block_assets():
    lang = {}
    for bid, (name, kind) in BLOCKS.items():
        lang[f"block.{MOD}.{bid}"] = name
        tex = f"{MOD}:block/{bid}"
        model = f"{MOD}:block/{bid}"
        states = {"variants": {"": {"model": model}}}
        item = {"parent": "minecraft:item/generated", "textures": {"layer0": tex}}
        drop = bid
        if kind == "cube":
            block_model = {"parent": "minecraft:block/cube_all", "textures": {"all": tex}}
            item = {"parent": model}
        elif kind in ("cross", "vine", "crystal") or kind.startswith("vine_body:"):
            block_model = {"parent": "minecraft:block/cross", "render_type": "minecraft:cutout", "textures": {"cross": tex}}
            if kind == "crystal":
                states = {"variants": {f"facing={f}": dict({"model": model}, **turn) for f, turn in FACE6.items()}}
            if kind.startswith("vine_body:"):
                item = None
                drop = kind.split(":", 1)[1]
        elif kind == "coral_fan":
            block_model = {"parent": "minecraft:block/coral_fan", "render_type": "minecraft:cutout", "textures": {"fan": tex}}
        elif kind == "shelf":
            block_model = shelf_model(bid)
            states = {"variants": {f"facing={f}": dict({"model": model}, **turn) for f, turn in FACE4.items()}}
            item = {"parent": model}
        else:
            raise ValueError(f"{bid}: unknown kind {kind}")
        write(os.path.join(ASSETS, "blockstates", bid + ".json"), states)
        write(os.path.join(ASSETS, "models", "block", bid + ".json"), block_model)
        if item is not None:
            write(os.path.join(ASSETS, "models", "item", bid + ".json"), item)
        if bid not in NO_DROP:
            write(os.path.join(DATA, "loot_tables", "blocks", bid + ".json"), {
                "type": "minecraft:block",
                "pools": [{"rolls": 1, "bonus_rolls": 0, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{drop}"}],
                           "conditions": [{"condition": "minecraft:survives_explosion"}]}],
            })
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
