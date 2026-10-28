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

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src/main/resources")
MOD = "explorationremix"
DATA = os.path.join(RES, "data", MOD)
ASSETS = os.path.join(RES, "assets", MOD)

OWNED = [
    os.path.join(DATA, "worldgen"), os.path.join(DATA, "tags"), os.path.join(DATA, "forge"), os.path.join(DATA, "loot_tables"),
    os.path.join(RES, "data", "minecraft", "tags"), os.path.join(RES, "data", "forge", "tags"),
    os.path.join(ASSETS, "blockstates"), os.path.join(ASSETS, "models"), os.path.join(ASSETS, "textures"), os.path.join(ASSETS, "lang"),
]


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def worldgen(kind, name, obj): write(os.path.join(DATA, "worldgen", kind, name + ".json"), obj)
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
    (MOD, "hanging_supports"): ["wisteria", "wisteria_plant"],
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
# Colours per biome. Fog is the colour of the distance in the cave, water and water fog tint the water.
def rgb(h): return int(h.lstrip("#"), 16)

DEEP_BIOMES = ["ivory_root_cavern", "lantern_lake", "asurine_geodes", "deep_blue_rift", "silver_fern_rift", "rust_springs",
               "ember_gardens", "basalt_galleries"]
BIOME_NAMES = {
    "rootvaults": "Rootvaults", "ivory_root_cavern": "Ivory Root Cavern", "lantern_lake": "Lantern Lake", "asurine_geodes": "Asurine Geodes",
    "deep_blue_rift": "Deep Blue Rift", "silver_fern_rift": "Silver Fern Rift", "rust_springs": "Rust Springs", "ember_gardens": "Ember Gardens",
    "basalt_galleries": "Basalt Galleries",
}
LOOKS = {
    #                    fog        water      water fog  grass      foliage
    "rootvaults":        ("#b9c98c", "#2fb7a9", "#0f5a55", "#6fae58", "#5d9c48"),
    "ivory_root_cavern": ("#c8954a", "#35c9c0", "#0e5e5a", "#8fae5a", "#7d9e4a"),
    "lantern_lake":      ("#b98544", "#3bd0c4", "#12655f", "#86b056", "#74a046"),
    "asurine_geodes":    ("#2c4f8c", "#3e72d8", "#0b1f4a", "#6f8fa8", "#5f7f98"),
    "deep_blue_rift":    ("#1d3e75", "#2554ba", "#08183d", "#6a8aa8", "#5a7a98"),
    "silver_fern_rift":  ("#2c4b7c", "#2f63c6", "#0a1c42", "#7f9db2", "#6f8da2"),
    "rust_springs":      ("#a64b2b", "#c2672c", "#5a2a10", "#a08a50", "#907a40"),
    "ember_gardens":     ("#a8472d", "#4fa9c5", "#163f4d", "#a8904c", "#98803c"),
    "basalt_galleries":  ("#6b5249", "#40607a", "#1a2630", "#8a7a5a", "#7a6a4a"),
}

# The pack's dripstone caves list these in this order (WWOO's copy, which matches vanilla). The Rootvaults take its
# place, so they keep its ores and springs in the same order: a different order would break feature sorting.
DRIPSTONE_FEATURES = [
    [],
    ["minecraft:lake_lava_underground", "minecraft:lake_lava_surface"],
    ["minecraft:amethyst_geode"],
    ["minecraft:monster_room", "minecraft:monster_room_deep"],
    [],
    [],
    ["minecraft:ore_dirt", "minecraft:ore_gravel", "minecraft:ore_granite_upper", "minecraft:ore_granite_lower", "minecraft:ore_diorite_upper",
     "minecraft:ore_diorite_lower", "minecraft:ore_andesite_upper", "minecraft:ore_andesite_lower", "minecraft:ore_tuff", "minecraft:ore_coal_upper",
     "minecraft:ore_coal_lower", "minecraft:ore_iron_upper", "minecraft:ore_iron_middle", "minecraft:ore_iron_small", "minecraft:ore_gold",
     "minecraft:ore_gold_lower", "minecraft:ore_redstone", "minecraft:ore_redstone_lower", "minecraft:ore_diamond", "minecraft:ore_diamond_large",
     "minecraft:ore_diamond_buried", "minecraft:ore_lapis", "minecraft:ore_lapis_buried", "minecraft:ore_copper_large", "minecraft:underwater_magma",
     "minecraft:disk_sand", "minecraft:disk_clay", "minecraft:disk_gravel"],
    [],
    ["minecraft:spring_water", "minecraft:spring_lava"],
    ["minecraft:glow_lichen"],
    ["minecraft:freeze_top_layer"],
]

CAVE_MONSTERS = [
    {"type": "minecraft:spider", "weight": 100, "minCount": 4, "maxCount": 4},
    {"type": "minecraft:zombie", "weight": 95, "minCount": 4, "maxCount": 4},
    {"type": "minecraft:zombie_villager", "weight": 5, "minCount": 1, "maxCount": 1},
    {"type": "minecraft:skeleton", "weight": 100, "minCount": 4, "maxCount": 4},
    {"type": "minecraft:creeper", "weight": 100, "minCount": 4, "maxCount": 4},
    {"type": "minecraft:slime", "weight": 100, "minCount": 4, "maxCount": 4},
    {"type": "minecraft:enderman", "weight": 10, "minCount": 1, "maxCount": 4},
    {"type": "minecraft:witch", "weight": 5, "minCount": 1, "maxCount": 1},
]


def spawners(water_life=True):
    s = {"monster": CAVE_MONSTERS, "creature": [], "ambient": [{"type": "minecraft:bat", "weight": 10, "minCount": 8, "maxCount": 8}],
         "underground_water_creature": [], "water_creature": [], "water_ambient": [], "misc": [], "axolotls": []}
    if water_life:
        s["underground_water_creature"] = [{"type": "minecraft:glow_squid", "weight": 10, "minCount": 4, "maxCount": 6}]
    return s


def biome(name, features, water_life=True, temperature=0.8):
    fog, water, water_fog, grass, foliage = (rgb(c) for c in LOOKS[name])
    return {
        "has_precipitation": False,
        "temperature": temperature,
        "downfall": 0.4,
        "effects": {
            "fog_color": fog,
            "sky_color": fog,
            "water_color": water,
            "water_fog_color": water_fog,
            "grass_color": grass,
            "foliage_color": foliage,
            "mood_sound": {"sound": "minecraft:ambient.cave", "tick_delay": 6000, "block_search_extent": 8, "offset": 2.0},
        },
        "spawners": spawners(water_life),
        "spawn_costs": {},
        "carvers": {},
        "features": features,
    }


def empty_steps(): return [[] for _ in range(11)]


def biomes():
    # Rootvaults: the dripstone caves' ores and springs, nothing of the dripstone itself.
    rv = [list(step) for step in DRIPSTONE_FEATURES]
    worldgen("biome", "rootvaults", biome("rootvaults", rv))
    for name in DEEP_BIOMES:
        f = empty_steps()
        f[10].append(f"{MOD}:open_way")
        worldgen("biome", name, biome(name, f, water_life=name not in ("basalt_galleries",), temperature=1.2 if name in ("rust_springs", "ember_gardens", "basalt_galleries") else 0.7))


def features():
    worldgen("configured_feature", "open_way", {"type": f"{MOD}:open_way", "config": {}})
    worldgen("placed_feature", "open_way", {"feature": f"{MOD}:open_way", "placement": []})


def tags():
    deep = [f"{MOD}:{b}" for b in DEEP_BIOMES]
    tag(MOD, "worldgen/biome", "is_deep", deep)
    tag("minecraft", "worldgen/biome", "is_overworld", [f"{MOD}:rootvaults"])
    tag("forge", "worldgen/biome", "is_cave", deep + [f"{MOD}:rootvaults"])
    tag("forge", "worldgen/biome", "is_underground", deep + [f"{MOD}:rootvaults"])
    # Band stone: nothing breaks it, moves it or grows through it.
    for t in ("dragon_immune", "wither_immune", "features_cannot_replace", "geode_invalid_blocks"):
        tag("minecraft", "blocks", t, [f"{MOD}:band_stone"])
    for (ns, name), ids in BLOCK_TAGS.items():
        tag(ns, "blocks", name, [f"{MOD}:{b}" for b in ids])


def biome_modifiers():
    write(os.path.join(DATA, "forge", "biome_modifier", "remove_create_striations.json"), {"type": f"{MOD}:remove_create_striations"})


def lang(extra):
    out = {f"biome.{MOD}.{k}": v for k, v in BIOME_NAMES.items()}
    out.update(extra)
    write(os.path.join(ASSETS, "lang", "en_us.json"), dict(sorted(out.items())))


def main():
    for d in OWNED:
        if os.path.isdir(d): shutil.rmtree(d)
    names = block_assets()
    textures.write_all(os.path.join(ASSETS, "textures"))
    biomes()
    features()
    tags()
    biome_modifiers()
    lang(names)
    print("data written")


if __name__ == "__main__":
    main()
