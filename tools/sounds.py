"""Exploration Remix's sound events: assets/explorationremix/sounds.json. Called by gen_data.py.

The ambient loops are the mod's own recordings, made by synth_sounds.py into assets/explorationremix/sounds/ambient/.
One-shot additions mix those with vanilla sounds, which are only referenced, never copied.
The event ids must match ERSounds.java.
"""
import os

MOD = "explorationremix"


def own(name, volume=1.0, stream=False, weight=1, pitch=1.0, attenuation_distance=None):
    e = {"name": f"{MOD}:ambient/{name}", "volume": volume}
    if stream: e["stream"] = True
    if weight != 1: e["weight"] = weight
    if pitch != 1.0: e["pitch"] = pitch
    # how far a sound at volume 1 carries, in blocks (the game's default is 16; a louder play carries that many times as far)
    if attenuation_distance: e["attenuation_distance"] = attenuation_distance
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
    # Basalt Galleries: rumbling and lava pops. 0.9.0: no rattle of stone any more (vanilla's basalt-deltas debris), now
    # that rock really falls in the quakes: a rattle of stone means stone is coming down.
    "ambient.basalt.loop": [own("basalt_rumble", 0.8, stream=True)],
    "ambient.basalt.additions": [vanilla("liquid/lavapop", 0.7, 0.9, 3)]
                                + [own(f"rumble{i}", 0.8) for i in range(1, 3)],
    # Ash Hollows (0.5), the Wasteland's refuge: the same rock heard from far off, lower and much quieter, and now and
    # then a distant rumble or a lava pop; no debris rattling down.
    "ambient.hollows.loop": [own("basalt_rumble", 0.35, stream=True, pitch=0.8)],
    "ambient.hollows.additions": [own(f"rumble{i}", 0.35, pitch=0.75) for i in range(1, 3)] + [vanilla("liquid/lavapop", 0.25, 0.8)],
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


# 0.9.0's quakes (client.QuakeEffects plays them): the mod's own, made by synth_quake_sounds.py, mono so the positioned
# ones have a place, dry and with nothing under 80 Hz so a quake never sounds like the lava's deep warning. The big
# quake's rumble and the tremor's crackle are played all round the listener; the crack overhead is played where the
# block is with no fading (it would be out of range 100 blocks up); the creak, the landing's thud (played at volume 2:
# 32 blocks) and the hiss of something falling into the lava are positioned.
# The eruptions (client.EruptionEffects and client.BombEffects), the mod's own too, made by the same script and mono: a
# vent's rumble as it warns (played at volume 6: 96 blocks), the deep boom of its burst and the roar of its fountain
# (both placed at the vent with no fading, their volume set by the distance, so they are heard across the Caldera with
# their direction; the roar a seamless loop, streamed), a lava bomb's whistle (a loop that follows it at volume 6: 96
# blocks) and its far blast (volume 8: 128 blocks).
QUAKE_EVENTS = {
    "quake.rumble": [own(f"quake_rumble{i}", 1.0) for i in range(1, 3)],
    "quake.tremor": [own(f"quake_tremor{i}", 1.0) for i in range(1, 4)],
    "quake.crack": [own(f"quake_crack{i}", 1.0) for i in range(1, 4)],
    "quake.creak": [own(f"quake_creak{i}", 1.0) for i in range(1, 4)],
    "quake.debris": [own(f"quake_land{i}", 1.0) for i in range(1, 4)],
    "eruption.rumble": [own("eruption_rumble", 1.0)],
    "eruption.boom": [own(f"eruption_boom{i}", 1.0) for i in range(1, 3)],
    "eruption.roar": [own("eruption_roar", 1.0, stream=True)],
    "lava_bomb.whistle": [own("bomb_whistle", 1.0)],
    "lava_bomb.burst": [own(f"bomb_burst{i}", 1.0) for i in range(1, 3)],
    "lava_bomb.fizz": [own(f"bomb_fizz{i}", 1.0) for i in range(1, 3)],
}
QUAKE_SUBTITLES = {
    "quake.rumble": "The ground shakes", "quake.tremor": "The rock settles", "quake.crack": "Rock cracks overhead",
    "quake.creak": "The build creaks", "quake.debris": "Rock crashes down", "eruption.rumble": "A vent rumbles",
    "eruption.boom": "An eruption bursts", "eruption.roar": "Lava roars", "lava_bomb.whistle": "A lava bomb whistles",
    "lava_bomb.burst": "A lava bomb bursts", "lava_bomb.fizz": "Lava hisses",
}


# The Mossback (tools/synth_creature_sounds.py makes the files): its call carries far, so you hear it coming.
def creature(name, count, volume=1.0, attenuation=None, who="mossback"):
    out = []
    for i in range(1, count + 1):
        e = {"name": f"{MOD}:creature/{who}/{name}{i}", "volume": volume}
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

# The Skein: its rattle as it rears carries, so a near miss is heard before it is seen; its patter is quiet.
SKEIN_EVENTS = {
    "skein.patter": creature("patter", 4, 0.8, 16, who="skein"),
    "skein.chitter": creature("chitter", 3, 0.9, 18, who="skein"),
    "skein.rattle": creature("rattle", 2, 1.0, 24, who="skein"),
    "skein.bite": creature("bite", 2, 1.0, who="skein"),
    "skein.hurt": creature("hurt", 2, 1.0, who="skein"),
    "skein.death": creature("death", 1, 1.0, 24, who="skein"),
}
SKEIN_SUBTITLES = {
    "skein.patter": "Many feet patter", "skein.chitter": "Something chitters", "skein.rattle": "Skein rattles",
    "skein.bite": "Mandibles snap", "skein.hurt": "Skein hurts", "skein.death": "Skein dies",
}
CREATURE_EVENTS.update(SKEIN_EVENTS)
CREATURE_SUBTITLES.update(SKEIN_SUBTITLES)


def write_all(assets_dir, write):
    out = {}
    lang = {}
    subtitles = {**SUBTITLES, **LAVA_SUBTITLES, **QUAKE_SUBTITLES, **CREATURE_SUBTITLES}
    for ev, sounds in {**EVENTS, **LAVA_EVENTS, **QUAKE_EVENTS, **CREATURE_EVENTS}.items():
        sub = f"subtitles.{MOD}.{ev}"
        out[ev] = {"sounds": sounds, "subtitle": sub}
        lang[sub] = subtitles[ev]
    write(os.path.join(assets_dir, "sounds.json"), out)
    return lang
