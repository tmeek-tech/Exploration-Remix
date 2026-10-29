"""Exploration Remix's biomes and their features, as datapack JSON. Called by gen_data.py.

Every biome's look is set here: fog, water and grass colours, ambient particles and sounds, and the features that
dress its caves. Features are listed once, in one master order (FEATURES), and each biome takes the ones it names in
that order, so feature sorting never finds a cycle between biomes.

Refuges hold no plants. Routes have some growth. Hunting grounds are the most overgrown places in their layer.
"""
import json, os

MOD = "explorationremix"

# ----------------------------------------------------------------------------------------------- biome list
UPPER = ["rootvaults", "silver_fern_rift", "ivory_root_cavern"]
DEEP = ["lantern_lake", "ember_gardens", "rust_springs", "deep_blue_rift", "asurine_geodes", "basalt_galleries", "obsidian_tubes"]
OURS = UPPER + DEEP
NAMES = {
    "rootvaults": "Rootvaults", "silver_fern_rift": "Silver Fern Rift", "ivory_root_cavern": "Ivory Root Cavern",
    "lantern_lake": "Lantern Lake", "ember_gardens": "Ember Gardens", "rust_springs": "Rust Springs",
    "deep_blue_rift": "Deep Blue Rift", "asurine_geodes": "Asurine Geodes",
    "basalt_galleries": "Basalt Galleries", "obsidian_tubes": "Obsidian Tubes",
}
LAYER = {"rootvaults": "upper", "silver_fern_rift": "upper", "ivory_root_cavern": "upper",
         "lantern_lake": "deep_gardens", "ember_gardens": "deep_gardens", "rust_springs": "deep_gardens",
         "deep_blue_rift": "blue_reaches", "asurine_geodes": "blue_reaches",
         "basalt_galleries": "basalt_galleries", "obsidian_tubes": "basalt_galleries"}

# Fog is the colour of the distance, water and water fog tint the water, grass and foliage tint ferns and leaves.
#                       fog        water      water fog  grass      foliage
LOOKS = {
    "rootvaults":        ("#b4c27c", "#3cc4b4", "#0f5a55", "#6fae58", "#5d9c48"),
    "silver_fern_rift":  ("#93ab98", "#3aa9c2", "#0f4a5a", "#7fa86a", "#6f985a"),
    "ivory_root_cavern": ("#c7b784", "#38cbc2", "#0e5e5a", "#8fae5a", "#7d9e4a"),
    "lantern_lake":      ("#c28a46", "#3bd0c4", "#12655f", "#a89a6a", "#988a5a"),
    "ember_gardens":     ("#b5562f", "#4cbcc9", "#163f4d", "#a8904c", "#98803c"),
    "rust_springs":      ("#a9482a", "#c9662b", "#5a2a10", "#a08a50", "#907a40"),
    "deep_blue_rift":    ("#1f3f7c", "#2554ba", "#08183d", "#6a8aa8", "#5a7a98"),
    "asurine_geodes":    ("#2a4a86", "#3e72d8", "#0b1f4a", "#6f8fa8", "#5f7f98"),
    "basalt_galleries":  ("#70574b", "#40607a", "#1a2630", "#8a7a5a", "#7a6a4a"),
    "obsidian_tubes":    ("#4d3b42", "#40607a", "#1a2630", "#8a7a5a", "#7a6a4a"),
}

# Ambient particles drifting in the air: (particle, probability). Steam over hot pools, embers over lava, pollen in
# the daylight shafts and drips under ceilings are spawned by the client (CaveAtmosphere), not here.
PARTICLES = {
    "lantern_lake": ("minecraft:mycelium", 0.012),
    "ember_gardens": ("minecraft:mycelium", 0.012),
    "rust_springs": ("minecraft:mycelium", 0.008),
    "deep_blue_rift": ("minecraft:warped_spore", 0.01),
    "asurine_geodes": ("minecraft:warped_spore", 0.004),
    "basalt_galleries": ("minecraft:white_ash", 0.06),
    "obsidian_tubes": ("minecraft:ash", 0.02),
}

# Ambient sound: (loop, additions, additions chance per tick). Our sound events are registered by the mod (ERSounds).
SOUNDS = {
    "rootvaults": (f"{MOD}:ambient.upper.loop", f"{MOD}:ambient.upper.additions", 0.008),
    "silver_fern_rift": (f"{MOD}:ambient.upper.loop", f"{MOD}:ambient.upper.additions", 0.008),
    "ivory_root_cavern": (f"{MOD}:ambient.upper.loop", f"{MOD}:ambient.upper.additions", 0.008),
    "lantern_lake": (f"{MOD}:ambient.gardens.loop", f"{MOD}:ambient.gardens.additions", 0.012),
    "ember_gardens": (f"{MOD}:ambient.springs.loop", f"{MOD}:ambient.gardens.additions", 0.012),
    "rust_springs": (f"{MOD}:ambient.springs.loop", f"{MOD}:ambient.gardens.additions", 0.008),
    "deep_blue_rift": (f"{MOD}:ambient.blue.loop", f"{MOD}:ambient.blue.additions", 0.006),
    "asurine_geodes": (f"{MOD}:ambient.blue.loop", f"{MOD}:ambient.blue.additions", 0.003),
    "basalt_galleries": (f"{MOD}:ambient.basalt.loop", f"{MOD}:ambient.basalt.additions", 0.012),
    "obsidian_tubes": (f"{MOD}:ambient.basalt.loop", f"{MOD}:ambient.basalt.additions", 0.004),
}

HOT = {"ember_gardens", "rust_springs", "basalt_galleries", "obsidian_tubes"}
DRY = {"basalt_galleries", "obsidian_tubes"}

# The pack's dripstone caves list these (WWOO's copy, which matches vanilla). The Rootvaults keep their ores and
# water springs in the same order, but not the lava lakes or lava springs: the caves above the first band are safe.
ROOTVAULTS_VANILLA = [
    [],
    [],
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
    ["minecraft:spring_water"],
    ["minecraft:glow_lichen"],
    ["minecraft:freeze_top_layer"],
]

STEPS = ["raw_generation", "lakes", "local_modifications", "underground_structures", "surface_structures", "strongholds",
         "underground_ores", "underground_decoration", "fluid_springs", "vegetal_decoration", "top_layer_modification"]

# ----------------------------------------------------------------------------------------------- helpers

def state(name, **props):
    s = {"Name": name}
    if props:
        s["Properties"] = {k: (str(v).lower() if isinstance(v, bool) else str(v)) for k, v in props.items()}
    return s


def simple(name, **props): return {"type": "minecraft:simple_state_provider", "state": state(name, **props)}
def fallback(*ids): return {"type": f"{MOD}:fallback", "blocks": list(ids)}
def mix(*pairs): return {"type": f"{MOD}:mix", "entries": [{"data": p, "weight": w} for p, w in pairs]}
def uniform(lo, hi): return {"type": "minecraft:uniform", "value": {"min_inclusive": lo, "max_inclusive": hi}}
def funiform(lo, hi): return {"type": "minecraft:uniform", "value": {"min_inclusive": lo, "max_exclusive": hi}}

LIMESTONE = fallback("create:limestone", "minecraft:calcite")
CRIMSITE = fallback("create:crimsite", "minecraft:granite")

AIR = {"type": "minecraft:matching_blocks", "blocks": ["minecraft:air", "minecraft:cave_air"]}
AIR_OR_WATER = {"type": "minecraft:matching_blocks", "blocks": ["minecraft:air", "minecraft:cave_air", "minecraft:water"]}
SQUARE = {"type": "minecraft:in_square"}
BIOME = {"type": "minecraft:biome"}
def count(n): return {"type": "minecraft:count", "count": n}
def rarity(n): return {"type": "minecraft:rarity_filter", "chance": n}
def layer(name, inset=2, above_water=False): return {"type": f"{MOD}:layer_range", "layer": name, "inset": inset, "above_water": above_water}
def looks(*l): return {"type": f"{MOD}:rootvaults_look", "looks": list(l)}


def ceiling(offset=-1, steps=32, through=AIR):
    """From a random point, up to the ceiling above it (the ceiling block itself with offset 0)."""
    out = [{"type": "minecraft:environment_scan", "direction_of_search": "up",
            "target_condition": {"type": "minecraft:has_sturdy_face", "direction": "down"},
            "allowed_search_condition": through, "max_steps": steps}]
    if offset: out.append({"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": offset})
    return out


def floor(offset=1, steps=32, through=AIR):
    """From a random point, down to the floor below it (the block above the floor with offset 1)."""
    out = [{"type": "minecraft:environment_scan", "direction_of_search": "down",
            "target_condition": {"type": "minecraft:has_sturdy_face", "direction": "up"},
            "allowed_search_condition": through, "max_steps": steps}]
    if offset: out.append({"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": offset})
    return out


def patch(provider, survive, tries=32, xz=6, y=3, on=None):
    """Plants scattered around a spot: each where there is air, the plant can grow, and (optionally) on these blocks."""
    preds = [AIR, {"type": "minecraft:would_survive", "state": survive}]
    if on:
        preds.append({"type": "minecraft:matching_blocks", "offset": [0, -1, 0], "blocks": on})
    return {"type": "minecraft:random_patch", "config": {
        "tries": tries, "xz_spread": xz, "y_spread": y,
        "feature": {"feature": {"type": "minecraft:simple_block", "config": {"to_place": provider}},
                    "placement": [{"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:all_of", "predicates": preds}}]}}}


def hanging_column(body, tip, lo, hi):
    """A vine hanging from a ceiling: lo to hi blocks of body, then its tip."""
    return {"type": "minecraft:block_column", "config": {
        "direction": "down", "allowed_placement": AIR, "prioritize_tip": True,
        "layers": [{"height": uniform(lo, hi), "provider": simple(body)}, {"height": 1, "provider": simple(tip)}]}}


def hanging_patch(column, tries=8, xz=4, y=2):
    """Several vines hanging close together: a patch of block columns, each started right under a solid ceiling.
    Clusters light the caves in pockets with dim gaps between them, instead of an even scatter of single vines. Each
    try that lands in the air looks up for the ceiling over it, so the clusters form under rough ceilings too."""
    return {"type": "minecraft:random_patch", "config": {
        "tries": tries, "xz_spread": xz, "y_spread": y,
        "feature": {"feature": column, "placement": ceiling(steps=2 * y + 2)}}}


def berry_vines(lo, hi, berries):
    """Cave vines hanging from a ceiling, lo to hi blocks long, with glow berries on about this share of them (vanilla's
    share is a fifth). The tips are grown out, as vanilla's are, so the vines stay the length they were placed."""
    plain, ripe = round((1 - berries) * 20), round(berries * 20)
    def pick(name, **props):
        return {"type": "minecraft:weighted_state_provider", "entries": [
            {"data": state(name, berries=False, **props), "weight": plain}, {"data": state(name, berries=True, **props), "weight": ripe}]}
    tip = {"type": "minecraft:randomized_int_state_provider", "property": "age", "values": uniform(23, 25), "source": pick("minecraft:cave_vines", age=0)}
    return {"type": "minecraft:block_column", "config": {
        "direction": "down", "allowed_placement": AIR, "prioritize_tip": True,
        "layers": [{"height": uniform(lo, hi), "provider": pick("minecraft:cave_vines_plant")}, {"height": 1, "provider": tip}]}}


def attach(provider, faces, tries, on, radius=6, height=6, in_water=False):
    return {"type": f"{MOD}:surface_attach", "config": {"state": provider, "faces": faces, "tries": tries, "radius": radius,
                                                      "height": height, "on": on, "in_water": in_water}}


def fall(fluid, min_drop, width=(1, 1), search=10, rock=f"#{MOD}:cave_rock"):
    return {"type": f"{MOD}:fluid_fall", "config": {"fluid": state(fluid, level=0), "rock": rock, "min_drop": min_drop,
                                                  "search": search, "width": uniform(*width) if width[0] != width[1] else width[0]}}


def spike(body, tip, length, radius, floor_chance, point=None):
    c = {"body": body, "tip": tip, "length": uniform(*length), "radius": funiform(*radius), "floor_chance": floor_chance}
    if point: c["point"] = point
    return {"type": f"{MOD}:giant_spike", "config": c}


def pools(rim, bed, radius, edge=None, edge_chance=0.3, overflow=0.0, lava=False):
    c = {"rim": rim, "bed": bed, "radius": radius, "edge_chance": edge_chance, "overflow_chance": overflow}
    if edge: c["edge"] = edge
    if lava: c["lava"] = True
    return {"type": f"{MOD}:terrace_pools", "config": c}


def ref(cf): return cf  # a vanilla configured feature, by id


# ----------------------------------------------------------------------------------------------- features
# (name, step, biomes, configured feature (inline dict or vanilla id), placement). The list order is the master order.
FEATURES = []


def feature(name, step, biomes, configured, placement):
    FEATURES.append((name, step, biomes, configured, placement))


def build_features():
    FEATURES.clear()
    rv = ["rootvaults"]
    up = "upper"
    PLANT_FLOORS = ["minecraft:moss_block", "minecraft:grass_block", "minecraft:rooted_dirt", "minecraft:dirt", "minecraft:moss_carpet"]

    # ---- local modifications: shafts, spikes, pools and columns shape the space before anything grows in it
    feature("rootvaults/daylight_shaft_hall", "local_modifications", rv,
            {"type": f"{MOD}:daylight_shaft", "config": {"radius": funiform(1.5, 3.2), "max_depth": 120}},
            [count(4), SQUARE, layer(up)] + ceiling() + [looks(0), BIOME])
    feature("rootvaults/daylight_shaft_ravine", "local_modifications", rv,
            {"type": f"{MOD}:daylight_shaft", "config": {"radius": funiform(3.5, 6.0), "max_depth": 150, "stretch": 2.2, "max_lean": 2}},
            [count(4), SQUARE, layer(up)] + ceiling() + [looks(2), BIOME])
    feature("rootvaults/pools", "local_modifications", rv,
            pools(mix((LIMESTONE, 3), (simple("minecraft:moss_block"), 1)), mix((simple("minecraft:clay"), 2), (simple("minecraft:gravel"), 1)), 6),
            [count(3), SQUARE, layer(up)] + floor() + [looks(0), BIOME])
    feature("ivory_root_cavern/stalactites", "local_modifications", ["ivory_root_cavern"],
            spike(mix((LIMESTONE, 3), (simple("minecraft:calcite"), 2)), simple("minecraft:dripstone_block"), (8, 30), (2.0, 4.6), 0.35,
                  point=state("minecraft:pointed_dripstone", thickness="tip", vertical_direction="down")),
            [count(6), SQUARE, layer(up)] + ceiling(offset=0) + [BIOME])
    feature("ember_gardens/pools", "local_modifications", ["ember_gardens"],
            pools(mix((LIMESTONE, 3), (simple("minecraft:calcite"), 1)),
                  mix((simple("minecraft:calcite"), 6), (LIMESTONE, 3)), 10,
                  edge=mix((simple("explorationremix:lavender_coral"), 2), (simple(f"{MOD}:pale_coral"), 3),
                           (simple("explorationremix:pale_coral_fan"), 2), (simple(f"{MOD}:ember_anemone"), 3)), edge_chance=0.45,
                  overflow=0.08),
            [count(18), SQUARE, layer("deep_gardens", above_water=True)] + floor() + [BIOME])
    feature("rust_springs/stalactites", "local_modifications", ["rust_springs"],
            spike(mix((CRIMSITE, 4), (simple("minecraft:granite"), 1)), mix((CRIMSITE, 2), (simple("minecraft:red_terracotta"), 1)), (8, 28), (2.0, 4.6), 0.4),
            [count(8), SQUARE, layer("deep_gardens")] + ceiling(offset=0) + [BIOME])
    feature("rust_springs/pools", "local_modifications", ["rust_springs"],
            pools(mix((simple("minecraft:calcite"), 3), (LIMESTONE, 2)),
                  mix((LIMESTONE, 5), (simple("minecraft:calcite"), 3)), 11, overflow=0.12),
            [count(20), SQUARE, layer("deep_gardens", above_water=True)] + floor() + [BIOME])
    feature("basalt_galleries/lava_pools", "local_modifications", ["basalt_galleries"],
            pools(mix((simple("minecraft:blackstone"), 2), (simple("minecraft:basalt", axis="y"), 3)), simple("minecraft:magma_block"), 6, lava=True),
            [count(5), SQUARE, layer("basalt_galleries", above_water=True)] + floor() + [BIOME])
    feature("basalt_galleries/columns", "local_modifications", ["basalt_galleries"],
            {"type": f"{MOD}:column_cluster", "config": {
                "block": mix((simple("minecraft:basalt", axis="y"), 4), (simple("minecraft:polished_basalt", axis="y"), 1)),
                "radius": 6, "count": 26, "height": uniform(3, 16)}},
            [count(4), SQUARE, layer("basalt_galleries", above_water=True)] + floor() + [BIOME])

    # ---- underground decoration: moss, wall growth, crystals, frost, machines
    feature("rootvaults/moss_floor", "underground_decoration", rv, "minecraft:moss_patch",
            [count(30), SQUARE, layer(up)] + floor() + [looks(0, 1, 2), BIOME])
    feature("rootvaults/glow_lichen", "underground_decoration", rv + ["silver_fern_rift"],
            {"type": "minecraft:multiface_growth", "config": {
                "block": "minecraft:glow_lichen", "search_range": 20, "can_place_on_floor": True, "can_place_on_ceiling": True,
                "can_place_on_wall": True, "chance_of_spreading": 0.6,
                "can_be_placed_on": ["minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff",
                                     "minecraft:calcite", "minecraft:deepslate", "minecraft:cobbled_deepslate", "minecraft:dripstone_block",
                                     "minecraft:moss_block"]}},
            [count(80), SQUARE, layer(up), BIOME])
    feature("rootvaults/moss_ceiling", "underground_decoration", rv, "minecraft:moss_patch_ceiling",
            [count(30), SQUARE, layer(up)] + ceiling() + [looks(1, 2), BIOME])
    feature("ember_gardens/shelf_fungi", "underground_decoration", ["ember_gardens"],
            attach(simple("explorationremix:orange_shelf_fungus"), "walls", 96, f"#{MOD}:cave_rock", radius=8, height=8),
            [count(24), SQUARE, layer("deep_gardens", above_water=True), BIOME])
    feature("rust_springs/machines", "underground_decoration", ["rust_springs"],
            {"type": f"{MOD}:broken_machine", "config": {}},
            [count(2), SQUARE, layer("deep_gardens", above_water=True)] + floor(through=AIR_OR_WATER) + [BIOME])
    feature("deep_blue_rift/crystals", "underground_decoration", ["deep_blue_rift"],
            attach(mix((simple("explorationremix:asurine_crystal"), 1), (simple("explorationremix:asurine_bud"), 2)), "any", 48,
                   f"#{MOD}:blue_rift_walls", radius=7, height=7),
            [count(10), SQUARE, layer("blue_reaches", above_water=True), BIOME])
    feature("asurine_geodes/crystals", "underground_decoration", ["asurine_geodes"],
            attach(mix((simple("explorationremix:asurine_crystal"), 2), (simple("explorationremix:asurine_bud"), 3)), "any", 128,
                   f"#{MOD}:asurine_lining", radius=8, height=8),
            [count(12), SQUARE, layer("blue_reaches", above_water=True), BIOME])
    feature("asurine_geodes/frost", "underground_decoration", ["asurine_geodes"],
            attach({"type": "minecraft:weighted_state_provider", "entries": [
                {"data": state("minecraft:snow", layers=1), "weight": 3}, {"data": state("minecraft:snow", layers=2), "weight": 1}]},
                "floor", 24, f"#{MOD}:asurine_lining", radius=6, height=3),
            [count(4), SQUARE, layer("blue_reaches", above_water=True)] + floor() + [BIOME])

    # ---- vegetation rework, part A: sheets of ivy and lichen, wall ferns and brackets, before the floor and ceiling
    # plants take their spots. Ivy covers walls and ceilings but never floors, where a climbable sheet slows walking.
    feature("rootvaults/grotto_ivy", "underground_decoration", rv,
            attach(simple(f"{MOD}:grotto_ivy"), "walls_ceiling", 48, f"#{MOD}:cave_rock", radius=6, height=6),
            [count(24), SQUARE, layer(up), looks(1, 2), BIOME])
    feature("ivory_root_cavern/grotto_ivy", "underground_decoration", ["ivory_root_cavern"],
            attach(simple(f"{MOD}:grotto_ivy"), "walls_ceiling", 32, f"#{MOD}:cave_rock", radius=5, height=5),
            [count(8), SQUARE, layer(up), BIOME])
    feature("silver_fern_rift/wall_ferns", "underground_decoration", ["silver_fern_rift"],
            attach(simple(f"{MOD}:silver_wall_fern"), "walls", 32, f"#{MOD}:cave_rock", radius=6, height=6),
            [count(10), SQUARE, layer(up), BIOME])
    feature("ivory_root_cavern/brackets", "underground_decoration", ["ivory_root_cavern"],
            attach(simple(f"{MOD}:ivory_bracket"), "walls", 64, f"#{MOD}:cave_rock", radius=7, height=8),
            [count(10), SQUARE, layer(up), BIOME])
    feature("deep_blue_rift/lichen", "underground_decoration", ["deep_blue_rift"],
            attach(simple(f"{MOD}:blue_lichen"), "any", 40, f"#{MOD}:blue_rift_walls", radius=6, height=6),
            [count(12), SQUARE, layer("blue_reaches", above_water=True), BIOME])

    # ---- vegetation rework, part B: brown mushroom shelves up the Ember Gardens' walls and mushroom flesh in their
    # blackstone ceilings (vanilla blocks; plants grow on the shelves, vines hang from the caps), amber crystals in the
    # rock, a smouldering crust on the Basalt Galleries' walls. The caps only swap rock for rock, so they can't block a
    # way; the shelves keep off it.
    feature("ember_gardens/mushroom_shelves", "underground_decoration", ["ember_gardens"],
            {"type": f"{MOD}:mushroom_shelf", "config": {"block": simple("minecraft:brown_mushroom_block", down=False),
                                                       "tiers": uniform(2, 4), "spacing": uniform(2, 3), "depth": uniform(2, 4),
                                                       "half_width": uniform(1, 3)}},
            [count(10), SQUARE, layer("deep_gardens", above_water=True), BIOME])
    feature("ember_gardens/mushroom_caps", "underground_decoration", ["ember_gardens"],
            {"type": "minecraft:replace_blobs", "config": {"target": state("minecraft:blackstone"),
                                                          "state": state("minecraft:brown_mushroom_block"), "radius": uniform(2, 3)}},
            [count(6), SQUARE, layer("deep_gardens")] + ceiling(offset=0) +
            [{"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:matching_blocks", "blocks": "minecraft:blackstone"}}, BIOME])
    feature("ember_gardens/amber_shards", "underground_decoration", ["ember_gardens"],
            attach(simple(f"{MOD}:amber_shard"), "any", 24, f"#{MOD}:cave_rock", radius=6, height=6),
            [count(6), SQUARE, layer("deep_gardens", above_water=True), BIOME])
    feature("basalt_galleries/smoulder_lichen", "underground_decoration", ["basalt_galleries"],
            attach(simple(f"{MOD}:smoulder_lichen"), "walls_ceiling", 32, f"#{MOD}:basalt_rock", radius=6, height=8),
            [count(6), SQUARE, layer("basalt_galleries", above_water=True), BIOME])
    feature("basalt_galleries/amber_shards", "underground_decoration", ["basalt_galleries"],
            attach(simple(f"{MOD}:amber_shard"), "any", 16, f"#{MOD}:basalt_rock", radius=6, height=6),
            [count(4), SQUARE, layer("basalt_galleries", above_water=True), BIOME])

    # ---- fluid springs: waterfalls and lava falls
    feature("rootvaults/waterfalls", "fluid_springs", rv, fall("minecraft:water", 6, (1, 3)),
            [count(8), SQUARE, layer(up), BIOME])
    feature("silver_fern_rift/waterfalls", "fluid_springs", ["silver_fern_rift"], fall("minecraft:water", 8, (1, 3)),
            [count(24), SQUARE, layer(up), BIOME])
    feature("ivory_root_cavern/waterfalls", "fluid_springs", ["ivory_root_cavern"], fall("minecraft:water", 10, (2, 3)),
            [count(6), SQUARE, layer(up), BIOME])
    feature("deep_blue_rift/waterfalls", "fluid_springs", ["deep_blue_rift"], fall("minecraft:water", 16, (1, 3)),
            [count(8), SQUARE, layer("blue_reaches", above_water=True), BIOME])
    feature("asurine_geodes/meltwater", "fluid_springs", ["asurine_geodes"], fall("minecraft:water", 0, (1, 1), search=12),
            [count(1), SQUARE, layer("blue_reaches", above_water=True)] + floor() + [BIOME])
    feature("basalt_galleries/lava_falls", "fluid_springs", ["basalt_galleries"],
            fall("minecraft:lava", 10, (1, 2), rock=f"#{MOD}:basalt_rock"),
            [count(20), SQUARE, layer("basalt_galleries", above_water=True), BIOME])

    # ---- vegetal decoration: roots, vines and plants
    feature("rootvaults/ceiling_roots", "vegetal_decoration", rv,
            {"type": f"{MOD}:ceiling_roots", "config": {"log": state("minecraft:oak_log", axis="y"), "dirt": state("minecraft:rooted_dirt"),
                                                       "hanging": state("minecraft:hanging_roots"), "length": uniform(4, 18)}},
            [count(6), SQUARE, layer(up)] + ceiling() + [looks(0), BIOME])
    feature("rootvaults/hanging_roots", "vegetal_decoration", rv,
            attach(simple("minecraft:hanging_roots"), "ceiling", 32, ["minecraft:rooted_dirt", "minecraft:dirt", "minecraft:moss_block"], radius=6, height=4),
            [count(8), SQUARE, layer(up)] + ceiling() + [looks(0), BIOME])
    feature("rootvaults/lantern_roots", "vegetal_decoration", rv,
            hanging_column("explorationremix:lantern_roots", "explorationremix:lantern_roots", 0, 4),
            [count(40), SQUARE, layer(up)] + ceiling() + [looks(0), BIOME])
    feature("rootvaults/cave_vines", "vegetal_decoration", rv, berry_vines(1, 10, 0.4),
            [count(90), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("silver_fern_rift/cave_vines", "vegetal_decoration", ["silver_fern_rift"], berry_vines(1, 8, 0.5),
            [count(45), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("rootvaults/ferns", "vegetal_decoration", rv,
            patch({"type": "minecraft:weighted_state_provider", "entries": [
                {"data": state("minecraft:fern"), "weight": 3}, {"data": state("minecraft:grass"), "weight": 2},
                {"data": state("minecraft:large_fern"), "weight": 1}]},
                state("minecraft:fern"), tries=32, on=PLANT_FLOORS),
            [count(30), SQUARE, layer(up)] + floor() + [BIOME])
    feature("rootvaults/white_glow_flowers", "vegetal_decoration", rv,
            patch(simple("explorationremix:white_glow_flower"), state("explorationremix:white_glow_flower"), tries=20, xz=5, on=PLANT_FLOORS),
            [count(14), SQUARE, layer(up)] + floor() + [looks(1), BIOME])
    feature("rootvaults/white_glow_flowers_sparse", "vegetal_decoration", rv,
            patch(simple("explorationremix:white_glow_flower"), state("explorationremix:white_glow_flower"), tries=12, xz=4, on=PLANT_FLOORS),
            [count(10), SQUARE, layer(up)] + floor() + [looks(0, 2), BIOME])
    feature("silver_fern_rift/silver_ferns", "vegetal_decoration", ["silver_fern_rift"],
            patch(simple("explorationremix:silver_fern"), state("explorationremix:silver_fern"), tries=48, xz=7, y=4),
            [count(36), SQUARE, layer(up)] + floor() + [BIOME])
    feature("ivory_root_cavern/giant_lantern_fungi", "vegetal_decoration", ["ivory_root_cavern"],
            {"type": f"{MOD}:giant_fungus", "config": {
                "stem": state("minecraft:mushroom_stem"), "cap": state("minecraft:mushroom_stem", down=False),
                "glow": state("minecraft:shroomlight"), "roots": state(f"{MOD}:lantern_roots"),
                "height": uniform(2, 5), "radius": uniform(2, 3), "root_length": uniform(1, 4)}},
            [count(5), SQUARE, layer(up)] + floor() + [BIOME])
    feature("ivory_root_cavern/lantern_fungi", "vegetal_decoration", ["ivory_root_cavern"],
            patch(simple("explorationremix:lantern_fungus"), state("explorationremix:lantern_fungus"), tries=20, xz=5, y=3),
            [count(14), SQUARE, layer(up)] + floor() + [BIOME])
    feature("ivory_root_cavern/lantern_roots", "vegetal_decoration", ["ivory_root_cavern"],
            attach(simple("explorationremix:lantern_roots"), "ceiling", 32, f"#{MOD}:cave_rock", radius=6, height=3),
            [count(20), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("ivory_root_cavern/moss", "vegetal_decoration", ["ivory_root_cavern"],
            patch(simple("minecraft:moss_carpet"), state("minecraft:moss_carpet"), tries=24, xz=4, y=2),
            [count(6), SQUARE, layer(up)] + floor() + [BIOME])
    feature("lantern_lake/wisteria", "vegetal_decoration", ["lantern_lake"],
            hanging_patch(hanging_column("explorationremix:wisteria_plant", "explorationremix:wisteria", 1, 6), tries=8, xz=4, y=2),
            [count(10), SQUARE, layer("deep_gardens")] + ceiling() + [BIOME])
    feature("lantern_lake/wisteria_pods", "vegetal_decoration", ["lantern_lake"],
            hanging_column("explorationremix:wisteria_plant", "explorationremix:lantern_pod", 1, 4),
            [count(20), SQUARE, layer("deep_gardens")] + ceiling() + [BIOME])
    feature("lantern_lake/lantern_pods", "vegetal_decoration", ["lantern_lake"],
            patch(simple("explorationremix:lantern_pod"), state("explorationremix:lantern_pod"), tries=20, xz=5, y=6),
            [count(30), SQUARE, layer("deep_gardens")] + ceiling() + [BIOME])
    feature("lantern_lake/flowers", "vegetal_decoration", ["lantern_lake"],
            patch(mix((simple("explorationremix:lavender_flower"), 3), (simple("explorationremix:gold_flower"), 2)),
                  state("explorationremix:lavender_flower"), tries=40, xz=6, y=3, on=f"#{MOD}:garden_soil"),
            [count(16), SQUARE, layer("deep_gardens", above_water=True)] + floor() + [BIOME])
    feature("ember_gardens/ember_vines", "vegetal_decoration", ["ember_gardens"],
            hanging_patch(hanging_column("explorationremix:ember_vines_plant", "explorationremix:ember_vines", 1, 10), tries=8, xz=4, y=2),
            [count(16), SQUARE, layer("deep_gardens")] + ceiling() + [BIOME])
    feature("ember_gardens/coral", "vegetal_decoration", ["ember_gardens"],
            patch(mix((simple("explorationremix:lavender_coral"), 2), (simple(f"{MOD}:pale_coral"), 2), (simple("explorationremix:pale_coral_fan"), 2)),
                  state("explorationremix:lavender_coral"), tries=24, xz=5, y=2, on=f"#{MOD}:garden_soil"),
            [count(22), SQUARE, layer("deep_gardens", above_water=True)] + floor() + [BIOME])
    feature("deep_blue_rift/glow_vines", "vegetal_decoration", ["deep_blue_rift"],
            hanging_column("explorationremix:blue_glow_vines_plant", "explorationremix:blue_glow_vines", 1, 14),
            [count(56), SQUARE, layer("blue_reaches")] + ceiling() + [BIOME])
    feature("deep_blue_rift/mushrooms", "vegetal_decoration", ["deep_blue_rift"],
            patch(simple("explorationremix:blue_glow_mushroom"), state("explorationremix:blue_glow_mushroom"), tries=16, xz=4, y=2),
            [count(20), SQUARE, layer("blue_reaches", above_water=True)] + floor() + [BIOME])

    # ---- vegetation rework, part A: vines in clusters, floor plants. The new vines hang in patches (hanging_patch), so
    # their light comes in pockets with dim gaps between them.
    feature("rootvaults/amber_bulb_vines", "vegetal_decoration", rv,
            hanging_patch(hanging_column(f"{MOD}:amber_bulb_vine_plant", f"{MOD}:amber_bulb_vine", 1, 6), tries=8, xz=4, y=2),
            [count(10), SQUARE, layer(up)] + ceiling() + [looks(0, 1), BIOME])
    feature("rootvaults/weeping_ivy", "vegetal_decoration", rv,
            hanging_patch(hanging_column(f"{MOD}:weeping_ivy_plant", f"{MOD}:weeping_ivy", 2, 12), tries=10, xz=5, y=3),
            [count(12), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("rootvaults/starmoss", "vegetal_decoration", rv,
            patch(simple(f"{MOD}:starmoss"), state(f"{MOD}:starmoss"), tries=32, xz=5, y=2),
            [count(10), SQUARE, layer(up)] + floor() + [looks(1), BIOME])
    feature("rootvaults/starmoss_sparse", "vegetal_decoration", rv,
            patch(simple(f"{MOD}:starmoss"), state(f"{MOD}:starmoss"), tries=16, xz=4, y=2),
            [count(4), SQUARE, layer(up)] + floor() + [looks(0), BIOME])
    feature("rootvaults/azaleas", "vegetal_decoration", rv,
            patch(mix((simple("minecraft:azalea"), 3), (simple("minecraft:flowering_azalea"), 1)), state("minecraft:azalea"), tries=12, xz=5, y=2,
                  on=PLANT_FLOORS),
            [count(6), SQUARE, layer(up)] + floor() + [looks(2), BIOME])
    feature("silver_fern_rift/amber_bulb_vines", "vegetal_decoration", ["silver_fern_rift"],
            hanging_patch(hanging_column(f"{MOD}:amber_bulb_vine_plant", f"{MOD}:amber_bulb_vine", 1, 6), tries=8, xz=4, y=2),
            [count(8), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("silver_fern_rift/fronds", "vegetal_decoration", ["silver_fern_rift"],
            hanging_patch(hanging_column(f"{MOD}:silver_frond_plant", f"{MOD}:silver_frond", 1, 5), tries=8, xz=5, y=3),
            [count(16), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("silver_fern_rift/tall_ferns", "vegetal_decoration", ["silver_fern_rift"],
            patch(simple(f"{MOD}:tall_silver_fern"), state(f"{MOD}:tall_silver_fern"), tries=24, xz=6, y=3),
            [count(8), SQUARE, layer(up)] + floor() + [BIOME])
    feature("ivory_root_cavern/gold_threads", "vegetal_decoration", ["ivory_root_cavern"],
            hanging_patch(hanging_column(f"{MOD}:gold_threads_plant", f"{MOD}:gold_threads", 2, 9), tries=8, xz=4, y=2),
            [count(14), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("ivory_root_cavern/sage", "vegetal_decoration", ["ivory_root_cavern"],
            patch(simple(f"{MOD}:sage_bush"), state(f"{MOD}:sage_bush"), tries=16, xz=5, y=2),
            [count(10), SQUARE, layer(up)] + floor() + [BIOME])
    feature("deep_blue_rift/glowbells", "vegetal_decoration", ["deep_blue_rift"],
            patch(simple(f"{MOD}:glowbell"), state(f"{MOD}:glowbell"), tries=16, xz=4, y=2),
            [count(10), SQUARE, layer("blue_reaches", above_water=True)] + floor() + [BIOME])
    feature("deep_blue_rift/pagodas", "vegetal_decoration", ["deep_blue_rift"],
            patch(simple(f"{MOD}:pagoda_fungus"), state(f"{MOD}:pagoda_fungus"), tries=6, xz=4, y=2),
            [count(3), SQUARE, layer("blue_reaches", above_water=True)] + floor() + [BIOME])

    # ---- vegetation rework, part B: the Deep Gardens grow no green, and their floor plants grow only from limestone,
    # calcite and sand (garden_soil) and the mushroom shelves; under the lake they grow from its bed (lake_bed). The
    # Ember Gardens (the hunting ground) are the most overgrown, Lantern Lake (the route) has some growth, the Rust
    # Springs (a refuge) none. The Basalt Galleries get a little heat-loving growth; the Obsidian Tubes none.
    gardens_floor = [layer("deep_gardens", above_water=True)] + floor() + [BIOME]
    gardens_ceiling = [layer("deep_gardens")] + ceiling() + [BIOME]
    lake_bed = [layer("deep_gardens")] + floor(through=AIR_OR_WATER) + [BIOME]
    soil = f"#{MOD}:garden_soil"
    feature("lantern_lake/pale_wisteria", "vegetal_decoration", ["lantern_lake"],
            hanging_patch(hanging_column(f"{MOD}:pale_wisteria_plant", f"{MOD}:pale_wisteria", 1, 6), tries=8, xz=4, y=2),
            [count(8), SQUARE] + gardens_ceiling)
    feature("lantern_lake/plumes", "vegetal_decoration", ["lantern_lake"],
            patch(simple(f"{MOD}:lilac_plume"), state(f"{MOD}:lilac_plume"), tries=8, xz=4, y=2, on=soil),
            [count(3), SQUARE] + gardens_floor)
    feature("lantern_lake/coral", "vegetal_decoration", ["lantern_lake"],
            patch(mix((simple(f"{MOD}:pale_coral"), 2), (simple(f"{MOD}:lavender_coral"), 2)), state(f"{MOD}:pale_coral"), tries=16, xz=4, y=2,
                  on=soil),
            [count(8), SQUARE] + gardens_floor)
    feature("lantern_lake/petals", "vegetal_decoration", ["lantern_lake"],
            patch(simple(f"{MOD}:wisteria_petals"), state(f"{MOD}:wisteria_petals"), tries=24, xz=5, y=2, on=soil),
            [count(8), SQUARE] + gardens_floor)
    feature("lantern_lake/waterweed", "vegetal_decoration", ["lantern_lake"],
            attach(simple(f"{MOD}:lilac_waterweed"), "floor", 48, f"#{MOD}:lake_bed", radius=7, height=2, in_water=True),
            [count(16), SQUARE] + lake_bed)
    feature("lantern_lake/lantern_weed", "vegetal_decoration", ["lantern_lake"],
            attach(simple(f"{MOD}:lantern_weed"), "floor", 10, f"#{MOD}:lake_bed", radius=6, height=2, in_water=True),
            [count(6), SQUARE] + lake_bed)
    feature("ember_gardens/rose_tendrils", "vegetal_decoration", ["ember_gardens"],
            hanging_patch(hanging_column(f"{MOD}:rose_tendrils_plant", f"{MOD}:rose_tendrils", 2, 10), tries=8, xz=4, y=2),
            [count(12), SQUARE] + gardens_ceiling)
    feature("ember_gardens/plumes", "vegetal_decoration", ["ember_gardens"],
            patch(simple(f"{MOD}:lilac_plume"), state(f"{MOD}:lilac_plume"), tries=12, xz=5, y=3, on=soil),
            [count(10), SQUARE] + gardens_floor)
    feature("ember_gardens/ochre_grass", "vegetal_decoration", ["ember_gardens"],
            patch(simple(f"{MOD}:ochre_grass"), state(f"{MOD}:ochre_grass"), tries=32, xz=6, y=3, on=soil),
            [count(24), SQUARE] + gardens_floor)
    feature("ember_gardens/brown_mushrooms", "vegetal_decoration", ["ember_gardens"],
            patch(simple("minecraft:brown_mushroom"), state("minecraft:brown_mushroom"), tries=16, xz=5, y=2, on=soil),
            [count(6), SQUARE] + gardens_floor)
    basalt_floor = [layer("basalt_galleries", above_water=True)] + floor() + [BIOME]
    feature("basalt_galleries/cinder_blooms", "vegetal_decoration", ["basalt_galleries"],
            patch(simple(f"{MOD}:cinder_bloom"), state(f"{MOD}:cinder_bloom"), tries=12, xz=5, y=2, on=f"#{MOD}:basalt_rock"),
            [count(5), SQUARE] + basalt_floor)
    feature("basalt_galleries/ash_ferns", "vegetal_decoration", ["basalt_galleries"],
            patch(simple(f"{MOD}:ash_fern"), state(f"{MOD}:ash_fern"), tries=16, xz=6, y=3, on=f"#{MOD}:basalt_rock"),
            [count(4), SQUARE] + basalt_floor)

    # ---- top layer: the way down is kept open after everything else
    feature("open_way", "top_layer_modification", DEEP, {"type": f"{MOD}:open_way", "config": {}}, [])


# The vanilla deep dark, moved down into the Blue Reaches: its sculk, which vanilla only places relative to the old
# floor, placed again across the layer. Added to minecraft:deep_dark by a biome modifier. (name, configured feature
# (vanilla id or inline dict), placement).
DEEP_DARK_FEATURES = [
    ("deep_dark/sculk_vein", "minecraft:sculk_vein", [count(112), SQUARE, layer("blue_reaches"), BIOME]),
    ("deep_dark/sculk_patch", "minecraft:sculk_patch_deep_dark", [count(136), SQUARE, layer("blue_reaches"), BIOME]),
    # vegetation rework, part A: the deep dark's only plant, a few dark strands whose specks glow without giving light
    ("deep_dark/dusk_vines", hanging_column(f"{MOD}:dusk_vines_plant", f"{MOD}:dusk_vines", 1, 5),
     [count(4), SQUARE, layer("blue_reaches")] + ceiling() + [BIOME]),
]

# ----------------------------------------------------------------------------------------------- block tags
CREATE_STONES = ["create:limestone", "create:veridium", "create:asurine", "create:crimsite", "create:ochrum", "create:scoria", "create:scorchia"]
STAND_INS = ["minecraft:yellow_terracotta", "minecraft:deepslate_tiles", "minecraft:warped_wart_block", "minecraft:brown_terracotta",
             "minecraft:polished_blackstone", "minecraft:red_terracotta"]


def optional(ids): return [{"id": i, "required": False} for i in ids]


BLOCK_TAGS = {
    # Natural rock that plants, crystals and springs may grow on or out of.
    "cave_rock": ["#minecraft:base_stone_overworld", "minecraft:cobbled_deepslate", "minecraft:calcite", "minecraft:dripstone_block",
                  "minecraft:smooth_basalt", "minecraft:basalt", "minecraft:polished_basalt", "minecraft:blackstone", "minecraft:gilded_blackstone",
                  "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:magma_block", "minecraft:moss_block", "minecraft:rooted_dirt",
                  "minecraft:dirt", "minecraft:clay", "#minecraft:coal_ores", "#minecraft:iron_ores", "#minecraft:copper_ores", "#minecraft:gold_ores",
                  "#minecraft:redstone_ores", "#minecraft:lapis_ores", "#minecraft:diamond_ores", "#minecraft:emerald_ores"]
                 + STAND_INS + optional(CREATE_STONES),
    "asurine_lining": ["minecraft:deepslate_tiles"] + optional(["create:asurine"]),
    "blue_rift_walls": ["minecraft:deepslate", "minecraft:tuff", "minecraft:calcite", "minecraft:deepslate_tiles"] + optional(["create:asurine"]),
    # vegetation rework, part B: the Deep Gardens' plants grow from limestone, calcite and sand (no grass or moss) and
    # from the brown mushroom shelves; under Lantern Lake's water they grow from its bed.
    "garden_soil": ["minecraft:calcite", "minecraft:sand", "minecraft:brown_mushroom_block"] + optional(["create:limestone"]),
    "lake_bed": ["minecraft:sand", "minecraft:gravel", "minecraft:clay", "minecraft:calcite"] + optional(["create:limestone"]),
    "basalt_rock": ["minecraft:basalt", "minecraft:polished_basalt", "minecraft:smooth_basalt", "minecraft:blackstone", "minecraft:gilded_blackstone",
                    "minecraft:magma_block", "minecraft:deepslate_gold_ore", "minecraft:brown_terracotta", "minecraft:polished_blackstone"]
                   + optional(["create:scoria", "create:scorchia"]),
}

# ----------------------------------------------------------------------------------------------- writing

def rgb(h): return int(h.lstrip("#"), 16)


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


def spawners(name):
    s = {"monster": CAVE_MONSTERS, "creature": [], "ambient": [{"type": "minecraft:bat", "weight": 10, "minCount": 8, "maxCount": 8}],
         "underground_water_creature": [], "water_creature": [], "water_ambient": [], "misc": [], "axolotls": []}
    if name not in DRY:
        s["underground_water_creature"] = [{"type": "minecraft:glow_squid", "weight": 10, "minCount": 4, "maxCount": 6}]
    if name == "rootvaults":
        s["axolotls"] = [{"type": "minecraft:axolotl", "weight": 10, "minCount": 4, "maxCount": 6}]
    return s


def effects(name):
    fog, water, water_fog, grass, foliage = (rgb(c) for c in LOOKS[name])
    e = {"fog_color": fog, "sky_color": fog, "water_color": water, "water_fog_color": water_fog, "grass_color": grass, "foliage_color": foliage,
         "mood_sound": {"sound": "minecraft:ambient.cave", "tick_delay": 6000, "block_search_extent": 8, "offset": 2.0}}
    if name in PARTICLES:
        p, chance = PARTICLES[name]
        e["particle"] = {"options": {"type": p}, "probability": chance}
    loop, adds, chance = SOUNDS[name]
    e["ambient_sound"] = loop
    e["additions_sound"] = {"sound": adds, "tick_chance": chance}
    return e


def write_all(data_dir, res_dir, write):
    """data_dir: data/explorationremix; res_dir: src/main/resources; write(path, obj) writes JSON."""
    build_features()
    wg = os.path.join(data_dir, "worldgen")
    per_biome = {b: [[] for _ in STEPS] for b in OURS}
    for b, steps in [("rootvaults", ROOTVAULTS_VANILLA)]:
        for i, ids in enumerate(steps):
            per_biome[b][i].extend(ids)
    names = set()
    for name, step, biomes, configured, placement in FEATURES:
        assert name not in names, name
        names.add(name)
        if isinstance(configured, str):
            cf = configured
        else:
            write(os.path.join(wg, "configured_feature", name + ".json"), configured)
            cf = f"{MOD}:{name}"
        write(os.path.join(wg, "placed_feature", name + ".json"), {"feature": cf, "placement": placement})
        for b in biomes:
            per_biome[b][STEPS.index(step)].append(f"{MOD}:{name}")
    for name, cf, placement in DEEP_DARK_FEATURES:
        if not isinstance(cf, str):
            write(os.path.join(wg, "configured_feature", name + ".json"), cf)
            cf = f"{MOD}:{name}"
        write(os.path.join(wg, "placed_feature", name + ".json"), {"feature": cf, "placement": placement})
    for b in OURS:
        write(os.path.join(wg, "biome", b + ".json"), {
            "has_precipitation": False,
            "temperature": 1.2 if b in HOT else (0.5 if b == "asurine_geodes" else 0.8),
            "downfall": 0.0 if b in DRY else 0.5,
            "effects": effects(b),
            "spawners": spawners(b),
            "spawn_costs": {},
            "carvers": {},
            "features": per_biome[b],
        })
    # The deep dark's sculk across the Blue Reaches, and the way down kept open through it.
    write(os.path.join(data_dir, "forge", "biome_modifier", "deep_dark_sculk.json"), {
        "type": "forge:add_features", "biomes": "minecraft:deep_dark",
        "features": [f"{MOD}:{n}" for n, _, _ in DEEP_DARK_FEATURES], "step": "underground_decoration"})
    write(os.path.join(data_dir, "forge", "biome_modifier", "deep_dark_open_way.json"), {
        "type": "forge:add_features", "biomes": "minecraft:deep_dark", "features": f"{MOD}:open_way", "step": "top_layer_modification"})
    # Biome tags.
    def tag(ns, kind, tname, values):
        write(os.path.join(res_dir, "data", ns, "tags", kind, tname + ".json"), {"replace": False, "values": values})
    tag(MOD, "worldgen/biome", "is_deep", [f"{MOD}:{b}" for b in DEEP])
    tag(MOD, "worldgen/biome", "is_upper", [f"{MOD}:{b}" for b in UPPER])
    tag("minecraft", "worldgen/biome", "is_overworld", [f"{MOD}:{b}" for b in UPPER])
    tag("forge", "worldgen/biome", "is_cave", [f"{MOD}:{b}" for b in OURS])
    tag("forge", "worldgen/biome", "is_underground", [f"{MOD}:{b}" for b in OURS])
    for tname, values in BLOCK_TAGS.items():
        tag(MOD, "blocks", tname, values)
    return {f"biome.{MOD}.{k}": v for k, v in NAMES.items()}
