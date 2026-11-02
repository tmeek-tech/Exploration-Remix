"""Exploration Remix's sound events: assets/explorationremix/sounds.json. Called by gen_data.py.

The ambient loops are the mod's own recordings, made by synth_sounds.py into assets/explorationremix/sounds/ambient/.
One-shot additions mix those with vanilla sounds, which are only referenced, never copied.
The event ids must match ERSounds.java.
"""
import os

MOD = "explorationremix"


def own(name, volume=1.0, stream=False, weight=1, pitch=1.0):
    e = {"name": f"{MOD}:ambient/{name}", "volume": volume}
    if stream: e["stream"] = True
    if weight != 1: e["weight"] = weight
    if pitch != 1.0: e["pitch"] = pitch
    return e


def vanilla(name, volume=1.0, pitch=1.0, weight=1):
    e = {"name": f"minecraft:{name}", "volume": volume}
    if pitch != 1.0: e["pitch"] = pitch
    if weight != 1: e["weight"] = weight
    return e


DRIPS = [vanilla(f"block/pointed_dripstone/drip_water{i}", 0.55, p) for i in range(1, 16) for p in (0.8,) if i <= 15]

EVENTS = {
    # Upper caves: wind and birds from above, drips.
    "ambient.upper.loop": [own("upper_wind", 0.55, stream=True)],
    "ambient.upper.additions": [own(f"bird{i}", 0.35, weight=2) for i in range(1, 5)] + [own(f"drip{i}", 0.5) for i in range(1, 5)],
    # Deep Gardens: drips with long echoes; bubbling and hissing by the hot springs.
    "ambient.gardens.loop": [own("gardens_drips", 0.7, stream=True)],
    "ambient.gardens.additions": [own(f"echo_drip{i}", 0.6) for i in range(1, 5)],
    "ambient.springs.loop": [own("springs_bubbling", 0.6, stream=True)],
    # Blue Reaches: a distant roar of falling water.
    "ambient.blue.loop": [own("blue_roar", 0.7, stream=True)],
    "ambient.blue.additions": [own(f"echo_drip{i}", 0.45, pitch=0.7) for i in range(1, 5)],
    # Basalt Galleries: rumbling and lava pops.
    "ambient.basalt.loop": [own("basalt_rumble", 0.8, stream=True)],
    "ambient.basalt.additions": [vanilla("block/lava/pop", 0.7, 0.9, 3)]
                                + [vanilla(f"ambient/nether/basalt_deltas/debris{i}", 0.5) for i in range(1, 4)]
                                + [own(f"rumble{i}", 0.8) for i in range(1, 3)],
    # Ash Hollows (0.5), the Wasteland's refuge: the same rock heard from far off, lower and much quieter, and now and
    # then a distant rumble or a lava pop; no debris rattling down.
    "ambient.hollows.loop": [own("basalt_rumble", 0.35, stream=True, pitch=0.8)],
    "ambient.hollows.additions": [own(f"rumble{i}", 0.35, pitch=0.75) for i in range(1, 3)] + [vanilla("block/lava/pop", 0.25, 0.8)],
}

SUBTITLES = {
    "ambient.upper.loop": "Wind blows above", "ambient.upper.additions": "Birds sing above",
    "ambient.gardens.loop": "Water drips", "ambient.gardens.additions": "A drip echoes",
    "ambient.springs.loop": "Hot springs bubble", "ambient.blue.loop": "Water roars far below",
    "ambient.blue.additions": "A drip echoes", "ambient.basalt.loop": "The rock rumbles",
    "ambient.basalt.additions": "Lava pops",
    "ambient.hollows.loop": "The rock rumbles far off", "ambient.hollows.additions": "A distant rumble",
}


# The Basalt Galleries' lava cycle, played by each client (LavaWarnings.java), not by a biome: the rumbles that warn of
# a rise (synth_lava_sounds.py makes them) and the rise's continuous surge.
LAVA_EVENTS = {
    "lava.rumble": [own(f"lava_rumble{i}", 1.0) for i in range(1, 4)],
    "lava.surge": [own("lava_surge", 1.0, stream=True)],
}

LAVA_SUBTITLES = {"lava.rumble": "The deep rock rumbles", "lava.surge": "Lava surges up"}


# The Mossback (tools/synth_creature_sounds.py makes the files): its call carries far, so you hear it coming.
def creature(name, count, volume=1.0, attenuation=None):
    out = []
    for i in range(1, count + 1):
        e = {"name": f"{MOD}:creature/mossback/{name}{i}", "volume": volume}
        if attenuation: e["attenuation_distance"] = attenuation
        out.append(e)
    return out


CREATURE_EVENTS = {
    "mossback.call": creature("call", 3, 1.0, 28),
    "mossback.step": creature("step", 4, 0.9),
    "mossback.hiss": creature("hiss", 2, 1.0, 24),
    "mossback.bite": creature("bite", 2, 1.0),
    "mossback.shake": creature("shake", 3, 1.0),
    "mossback.hurt": creature("hurt", 3, 1.0),
    "mossback.death": creature("death", 1, 1.0, 24),
}
CREATURE_SUBTITLES = {
    "mossback.call": "Mossback clicks", "mossback.step": "Heavy footsteps", "mossback.hiss": "Mossback hisses",
    "mossback.bite": "Jaws snap", "mossback.shake": "Mossback growls", "mossback.hurt": "Mossback hurts",
    "mossback.death": "Mossback dies",
}


def write_all(assets_dir, write):
    out = {}
    lang = {}
    subtitles = {**SUBTITLES, **LAVA_SUBTITLES, **CREATURE_SUBTITLES}
    for ev, sounds in {**EVENTS, **LAVA_EVENTS, **CREATURE_EVENTS}.items():
        sub = f"subtitles.{MOD}.{ev}"
        out[ev] = {"sounds": sounds, "subtitle": sub}
        lang[sub] = subtitles[ev]
    write(os.path.join(assets_dir, "sounds.json"), out)
    return lang
