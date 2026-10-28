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
}

SUBTITLES = {
    "ambient.upper.loop": "Wind blows above", "ambient.upper.additions": "Birds sing above",
    "ambient.gardens.loop": "Water drips", "ambient.gardens.additions": "A drip echoes",
    "ambient.springs.loop": "Hot springs bubble", "ambient.blue.loop": "Water roars far below",
    "ambient.blue.additions": "A drip echoes", "ambient.basalt.loop": "The rock rumbles",
    "ambient.basalt.additions": "Lava pops",
}


def write_all(assets_dir, write):
    out = {}
    lang = {}
    for ev, sounds in EVENTS.items():
        sub = f"subtitles.{MOD}.{ev}"
        out[ev] = {"sounds": sounds, "subtitle": sub}
        lang[sub] = SUBTITLES[ev]
    write(os.path.join(assets_dir, "sounds.json"), out)
    return lang
