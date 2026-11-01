#!/usr/bin/env python3
"""Checks the generated datapack against the ids the game knows, so a typo fails here instead of stopping a world from
loading. Minecraft refuses the whole datapack over one unknown id ("Unknown registry key"), and unit tests never load
it, so gen_data.py runs this after writing.

Known ids: vanilla 1.20.1 and Create 6.0.8 from tools/known_ids.json, the few of Immersive Weathering's the mod uses
(listed here); the mod's own blocks from its blockstates, its
features, placements and state providers from ERWorldgen.java, its block tags from the files gen_data.py wrote.
Ids of other namespaces (forge tags and the like) are not checked.
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src/main/resources")
MOD = "explorationremix"
DATA = os.path.join(RES, "data", MOD)


# Immersive Weathering 2.0.5's ids the mod uses (a required dependency since 0.4.1), from its jar.
IW_BLOCKS = {"frosty_fern", "frosty_grass", "frost", "icicle"}
IW_FEATURES = {"icicle_cluster"}


def load(path):
    with open(path) as f:
        return json.load(f)


def files(folder):
    """Every json under folder, by its id (path without the extension)."""
    out = {}
    for base, _, names in os.walk(folder):
        for n in names:
            if n.endswith(".json"):
                p = os.path.join(base, n)
                out[os.path.relpath(p, folder)[:-5].replace(os.sep, "/")] = p
    return out


def registered(kind):
    """Names the mod registers with KIND.register("name", ...) in ERWorldgen.java."""
    src = open(os.path.join(ROOT, "src/main/java/com/explorationremix/registry/ERWorldgen.java")).read()
    return set(re.findall(kind + r'\.register\(\s*"([a-z0-9_/]+)"', src))


def main():
    known = load(os.path.join(ROOT, "tools/known_ids.json"))
    ids = {
        "feature": {"minecraft:" + i for i in known["feature"]} | {f"{MOD}:{i}" for i in registered("FEATURES")},
        "placement": {"minecraft:" + i for i in known["placement_modifier_type"]} | {f"{MOD}:{i}" for i in registered("PLACEMENT_TYPES")},
        "provider": {"minecraft:" + i for i in known["block_state_provider_type"]} | {f"{MOD}:{i}" for i in registered("STATE_PROVIDERS")},
        "predicate": {"minecraft:" + i for i in known["block_predicate_type"]},
        "int": {"minecraft:" + i for i in known["int_provider_type"]},
        "float": {"minecraft:" + i for i in known["float_provider_type"]},
        "height": {"minecraft:" + i for i in known["height_provider_type"]} | {f"{MOD}:{i}" for i in registered("HEIGHT_PROVIDERS")},
        "block": {"minecraft:" + i for i in known["block"]} | {"create:" + i for i in known["create_block"]}
                 | {f"{MOD}:{i}" for i in files(os.path.join(RES, "assets", MOD, "blockstates"))}
                 | {"immersive_weathering:" + i for i in IW_BLOCKS},
    }
    ids["feature"] |= {"immersive_weathering:" + i for i in IW_FEATURES}
    any_type = set().union(*(v for k, v in ids.items() if k != "block"))
    block_tags = {f"#{MOD}:{t}" for t in files(os.path.join(DATA, "tags", "blocks"))}
    configured = files(os.path.join(DATA, "worldgen", "configured_feature"))
    placed = files(os.path.join(DATA, "worldgen", "placed_feature"))
    errors = []

    def block(where, b):
        if not isinstance(b, str):
            return
        if b.startswith("#"):
            if b.startswith(f"#{MOD}:") and b not in block_tags:
                errors.append(f"{where}: no block tag {b}")
        elif b.split(":")[0] in ("minecraft", "create", MOD, "immersive_weathering") and b not in ids["block"]:
            errors.append(f"{where}: unknown block {b}")

    def typed(where, obj, kind):
        t = obj.get("type") if isinstance(obj, dict) else None
        if isinstance(t, str) and t not in ids[kind]:
            errors.append(f"{where}: unknown {kind} type {t}")

    def walk(where, obj):
        """Checks every typed object (a feature where it has a config, a placement in a placement list, any other
        type against all the registries above) and every block name."""
        if isinstance(obj, dict):
            t = obj.get("type")
            if isinstance(t, str) and t not in any_type:
                errors.append(f"{where}: unknown type {t}")
            if "type" in obj and "config" in obj:
                typed(where, obj, "feature")
            if "Name" in obj:
                block(where, obj["Name"])
            if t == "minecraft:matching_blocks":
                for b in obj["blocks"] if isinstance(obj["blocks"], list) else [obj["blocks"]]:
                    block(where, b)
            if "feature" in obj and "placement" in obj:  # an inline placed feature
                if isinstance(obj["feature"], str):
                    ref(where, obj["feature"])
                for p in obj["placement"]:
                    typed(where, p, "placement")
            for v in obj.values():
                walk(where, v)
        elif isinstance(obj, list):
            # A block list (a HolderSet) is one "#tag" or a list of block ids; a tag inside a list fails to load.
            if any(isinstance(v, str) and v.startswith("#") for v in obj):
                errors.append(f"{where}: a tag inside a list {obj}; use one tag instead")
            for v in obj:
                walk(where, v)

    def ref(where, feature):
        ns, _, path = feature.partition(":")
        if ns == MOD and path not in configured:
            errors.append(f"{where}: no configured feature {feature}")

    for name, path in configured.items():
        walk(f"configured_feature/{name}", load(path))
    for name, path in placed.items():
        pf = load(path)
        where = f"placed_feature/{name}"
        if isinstance(pf["feature"], str):
            ref(where, pf["feature"])
        else:
            walk(where, pf["feature"])
        for p in pf["placement"]:
            typed(where, p, "placement")
        walk(where, pf["placement"])
    for name, path in files(os.path.join(DATA, "worldgen", "biome")).items():
        for step in load(path).get("features", []):
            for f in step:
                ns, _, p = f.partition(":")
                if ns == MOD and p not in placed:
                    errors.append(f"biome/{name}: no placed feature {f}")
    for name, path in files(os.path.join(DATA, "tags", "blocks")).items():
        for v in load(path)["values"]:
            block(f"tags/blocks/{name}", v["id"] if isinstance(v, dict) else v)
    # The mod's biomes in #immersive_weathering:icy (so IW's icicles there never melt) also get IW's own icicle clusters
    # from its biome modifier, which ignore the mod's filters: each must be in a forge:remove_features for them.
    icy = os.path.join(RES, "data", "immersive_weathering", "tags", "worldgen", "biome", "icy.json")
    if os.path.exists(icy):
        removed = set()
        for path in files(os.path.join(DATA, "forge", "biome_modifier")).values():
            m = load(path)
            feats = m.get("features")
            feats = [feats] if isinstance(feats, str) else (feats or [])
            if m.get("type") == "forge:remove_features" and "immersive_weathering:icicles" in feats:
                bs = m.get("biomes")
                removed |= set([bs] if isinstance(bs, str) else bs)
        for v in load(icy)["values"]:
            b = v["id"] if isinstance(v, dict) else v
            if b.startswith(MOD + ":") and b not in removed:
                errors.append(f"immersive_weathering icy tag: {b} keeps Immersive Weathering's own icicle clusters")
    return sorted(set(errors))


if __name__ == "__main__":
    errs = main()
    for e in errs:
        print(e)
    print(f"data check: {len(errs)} problems")
    sys.exit(1 if errs else 0)
