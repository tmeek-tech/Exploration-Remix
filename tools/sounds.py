"""Exploration Remix's sound events: assets/explorationremix/sounds.json. Called by gen_data.py.

The ambient loops are the mod's own recordings, made by synth_sounds.py and (0.20.9, each layer's own ambience)
synth_layer_sounds.py into assets/explorationremix/sounds/ambient/.
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


EVENTS = {
    # Upper Caves (0.20.9, Taran: "Change bird noises in the Upper Caves to calm, atmospheric cricket noises"): no biome
    # plays a loop here any more. The client's UpperCaveSounds plays the crickets in the mod's own upper caves, where
    # the birds were (card pick "Only where the birds were", 0.21.1; the client config can add the pack's own caves): a
    # stereo bed, a soft far chorus of tree crickets (card pick "Far chorus only"), and a single cricket (two in a big
    # cave) singing now and then from the rock near you, which goes quiet as you come close (positional, mono; card pick
    # "Yes, it goes quiet"). The mod's own upper caves keep their water drips as additions. Made by
    # synth_layer_sounds.py, like everything new in 0.20.9.
    "ambient.upper.crickets": [own("crickets_loop", 0.75, stream=True)],
    "ambient.upper.cricket": [own(f"cricket{i}", 0.5, attenuation_distance=24) for i in range(1, 5)],
    "ambient.upper.additions": [own(f"drip{i}", 0.5) for i in range(1, 5)],
    # The frozen caves (the Frozen Root Vaults and the Frost Fern Grottos; Taran's card pick "Cold and still"): no
    # crickets in the cold, a thin draft, the tick of ice settling, and now and then (one in four) ice singing far off.
    "ambient.frozen.loop": [own("cold_draft", 0.8, stream=True)],
    "ambient.frozen.additions": [own(f"ice_tick{i}", 0.7, weight=3) for i in range(1, 4)] + [own(f"ice_song{i}", 0.6) for i in range(1, 4)],
    # Deep Gardens (0.20.9, Taran: "The Deep Gardens should be quiet"; card pick "Hushed, vents breathe up close"): a
    # faint slow draft through the chimneys and a far vent sighing every half minute or so. A live vent's crown hisses and
    # simmers, heard only within a few blocks (vent.simmer, VentCrownBlock). The drips and hot springs' bubbling of the
    # old wet design are gone.
    "ambient.gardens.loop": [own("gardens_hush", 1.0, stream=True)],
    "ambient.gardens.additions": [own(f"vent_sigh{i}", 0.7) for i in range(1, 4)],
    "vent.simmer": [own(f"vent_simmer{i}", 0.5, attenuation_distance=10) for i in range(1, 4)],
    # Blue Reaches: a distant roar of falling water.
    "ambient.blue.loop": [own("blue_roar", 0.7, stream=True)],
    "ambient.blue.additions": [own(f"echo_drip{i}", 0.45, pitch=0.7) for i in range(1, 5)],
    # Basalt Galleries (0.20.9, Taran: "the Basalt Galleries should have a low rumble"): a low, steady rumble you can
    # hear (the old basalt_rumble kept 99.9% of its level below 20 Hz, so the Galleries were silent) and lava pops. The
    # rolls of rumbling are gone from here: with the lava's warnings (LavaWarnings) the only rumbles that roll, a roll
    # always means the lava is coming. 0.9.0: no rattle of stone either (vanilla's basalt-deltas debris), since rock
    # really falls in the quakes.
    "ambient.basalt.loop": [own("deep_rumble", 0.45, stream=True)],
    "ambient.basalt.additions": [vanilla("liquid/lavapop", 0.7, 0.9)],
    # The Wasteland over them (no lava cycle there): the same rumble heard through the band, fainter, and now and then a
    # heavier roll far off or a lava pop, as before 0.20.9.
    "ambient.wasteland.loop": [own("deep_rumble", 0.28, stream=True)],
    "ambient.wasteland.additions": [vanilla("liquid/lavapop", 0.7, 0.9, 3)] + [own(f"rumble{i}", 0.8) for i in range(1, 3)],
    # The refuges, the Wasteland's Ash Hollows (0.5) and, since 0.20.9, the Galleries' Obsidian Tubes: the rock heard only
    # from far off, quieter still, and now and then a distant rumble or a lava pop.
    "ambient.hollows.loop": [own("deep_rumble", 0.2, stream=True)],
    "ambient.hollows.additions": [own(f"rumble{i}", 0.35, pitch=0.75) for i in range(1, 3)] + [vanilla("liquid/lavapop", 0.25, 0.8)],
}

SUBTITLES = {
    "ambient.upper.crickets": "Crickets chirp", "ambient.upper.cricket": "A cricket chirps",
    "ambient.upper.additions": "Water drips",
    "ambient.frozen.loop": "A cold draft blows", "ambient.frozen.additions": "Ice settles",
    "ambient.gardens.loop": "Air stirs", "ambient.gardens.additions": "A vent sighs far off", "vent.simmer": "A vent hisses",
    "ambient.blue.loop": "Water roars far below", "ambient.blue.additions": "A drip echoes",
    "ambient.basalt.loop": "The deep rock rumbles", "ambient.basalt.additions": "Lava pops",
    "ambient.wasteland.loop": "The rock rumbles below", "ambient.wasteland.additions": "A distant rumble",
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


# 0.12.0's timed swings (tools/synth_swing_sounds.py makes the files): each swing's whoosh, played by the swinger's own
# game the moment it lands and by the server for everyone else near; the hum as a held swing turns strong; the quiet
# shimmer and note of a rune sword's ready light, played only for its holder. The hunters' wind-ups swell for half a
# second into the strike.
def files(folder, name, count, volume=1.0, attenuation=None):
    out = []
    for i in range(1, count + 1):
        e = {"name": f"{MOD}:{folder}/{name}{i}", "volume": volume}
        if attenuation: e["attenuation_distance"] = attenuation
        out.append(e)
    return out


SWING_EVENTS = {
    "swing.light": files("swing", "light", 3),
    "swing.heavy": files("swing", "heavy", 2),
    "swing.thrust": files("swing", "thrust", 2),
    "swing.charge": files("swing", "charge", 2, 0.9),
    "runes.ready": files("runes", "ready", 2, 0.8, 12),
    # 0.22.7: a sword's jump attack coming down, and its guard: raised, a blow taken on it, a parry (heard far)
    "swing.smash": files("swing", "smash", 2),
    "guard.raise": files("guard", "raise", 2, 0.6, 10),
    "guard.block": files("guard", "block", 3),
    "guard.parry": files("guard", "parry", 2, 1.0, 32),
    "mossback.windup": creature("windup", 2, 1.0, 24),
    "skein.windup": creature("windup", 2, 1.0, 20, who="skein"),
    # 0.23.6: vanilla hostiles' wind-ups (pitched to each one's length) and the creak before a skeleton's or pillager's shot
    "foe.windup": files("foe", "windup", 2, 0.9, 20),
    "foe.draw": files("foe", "draw", 2, 0.9, 24),
}
SWING_SUBTITLES = {
    "swing.light": "Blade swishes", "swing.heavy": "Blade chops", "swing.thrust": "Blade thrusts",
    "swing.charge": "Blade gathers", "runes.ready": "Runes ready", "mossback.windup": "Mossback winds up",
    "skein.windup": "Skein winds up", "swing.smash": "Blade smashes down", "guard.raise": "Blade raised",
    "guard.block": "Blade blocks", "guard.parry": "Blade parries", "foe.windup": "Mob winds up",
    "foe.draw": "Bowstring creaks",
}
CREATURE_EVENTS.update(SWING_EVENTS)
CREATURE_SUBTITLES.update(SWING_SUBTITLES)

# 0.16.0's blue fireflies: the soft flutter of a landed swarm startled up off the rock (tools/synth_creature_sounds.py
# firefly). Quiet and close: heard across a cave room, not through the rock.
FIREFLY_EVENTS = {
    "firefly.flutter": creature("flutter", 3, 0.8, 14, who="firefly"),
}
FIREFLY_SUBTITLES = {
    "firefly.flutter": "Fireflies flutter up",
}
CREATURE_EVENTS.update(FIREFLY_EVENTS)
CREATURE_SUBTITLES.update(FIREFLY_SUBTITLES)


# 0.18.8's giant Skein of the Deep Blue Rift: the Skein's own recordings played slower and deeper, and heard from
# across the rift (a rift is 60 to 80 blocks wide), so you hear it on the far wall before you find its lights.
def giant(name, count, volume=1.0, attenuation=None, pitch=0.55):
    out = []
    for e in creature(name, count, volume, attenuation, who="skein"):
        e["pitch"] = pitch
        out.append(e)
    return out


GIANT_EVENTS = {
    "giant_skein.step": giant("patter", 4, 0.9, 24, 0.5),
    "giant_skein.chitter": giant("chitter", 3, 1.0, 40, 0.5),
    "giant_skein.notice": giant("rattle", 2, 1.0, 48, 0.42),
    "giant_skein.windup": giant("windup", 2, 1.0, 48, 0.55),
    "giant_skein.bite": giant("bite", 2, 1.0, 28, 0.55),
    "giant_skein.hurt": giant("hurt", 2, 1.0, 32, 0.55),
    "giant_skein.death": giant("death", 1, 1.0, 64, 0.45),
}
GIANT_SUBTITLES = {
    "giant_skein.step": "Heavy legs scrape", "giant_skein.chitter": "Something huge clicks",
    "giant_skein.notice": "Giant Skein turns", "giant_skein.windup": "Giant Skein rears",
    "giant_skein.bite": "Huge mandibles snap", "giant_skein.hurt": "Giant Skein hurts", "giant_skein.death": "Giant Skein dies",
}
CREATURE_EVENTS.update(GIANT_EVENTS)
CREATURE_SUBTITLES.update(GIANT_SUBTITLES)

# 0.20.0's stilt mantis of the Deep Gardens (tools/synth_creature_sounds.py mantis): light and dry, nothing throaty, so
# a herd reads as gentle: the tap of its stilt feet, a soft glassy chirr while it grazes, the papery rattle of its
# wings flared in its startle display, a thin squeak hurt and a fading chirr as it dies. Quiet: heard across a grove.
MANTIS_EVENTS = {
    "stilt_mantis.step": creature("tap", 4, 0.55, 12, who="stilt_mantis"),
    "stilt_mantis.chirr": creature("chirr", 3, 0.7, 20, who="stilt_mantis"),
    "stilt_mantis.rattle": creature("rattle", 2, 0.9, 24, who="stilt_mantis"),
    "stilt_mantis.hurt": creature("hurt", 2, 0.8, 16, who="stilt_mantis"),
    "stilt_mantis.death": creature("death", 1, 0.9, 20, who="stilt_mantis"),
}
MANTIS_SUBTITLES = {
    "stilt_mantis.step": "Stilt feet tap", "stilt_mantis.chirr": "Stilt Mantis chirrs",
    "stilt_mantis.rattle": "Stilt Mantis rattles its wings", "stilt_mantis.hurt": "Stilt Mantis hurts",
    "stilt_mantis.death": "Stilt Mantis dies",
}
CREATURE_EVENTS.update(MANTIS_EVENTS)
CREATURE_SUBTITLES.update(MANTIS_SUBTITLES)


# The Deep Gardens' vent urchin (0.21.3; 0.21.0's chimney lurker, tools/synth_creature_sounds.py chimney_lurker): dry
# and hard, lower and heavier than the Skein. The rattle of its spines as its glow flashes on carries (heard before it
# comes over its rim); the clicks while it stirs or hunts carry a little; its spines' points on the rock are heard only
# close.
LURKER_EVENTS = {
    "chimney_lurker.step": creature("tap", 4, 0.5, 12, who="chimney_lurker"),
    "chimney_lurker.click": creature("click", 3, 0.75, 20, who="chimney_lurker"),
    "chimney_lurker.rattle": creature("rattle", 2, 1.0, 32, who="chimney_lurker"),
    "chimney_lurker.hiss": creature("hiss", 2, 0.9, 20, who="chimney_lurker"),
    "chimney_lurker.bite": creature("bite", 2, 1.0, 20, who="chimney_lurker"),
    "chimney_lurker.hurt": creature("hurt", 2, 0.9, 16, who="chimney_lurker"),
    "chimney_lurker.death": creature("death", 1, 1.0, 24, who="chimney_lurker"),
}
LURKER_SUBTITLES = {
    "chimney_lurker.step": "Spines tick on rock", "chimney_lurker.click": "Vent Urchin clicks",
    "chimney_lurker.rattle": "Vent Urchin's spines rattle", "chimney_lurker.hiss": "Vent Urchin hisses",
    "chimney_lurker.bite": "Teeth snap", "chimney_lurker.hurt": "Vent Urchin hurts",
    "chimney_lurker.death": "Vent Urchin dies",
}
CREATURE_EVENTS.update(LURKER_EVENTS)
CREATURE_SUBTITLES.update(LURKER_SUBTITLES)

# The Hollow Choir, the Deep Dark's boss (0.21.9; tools/synth_choir_sounds.py makes the files): a ring of bone plates
# with bells under it, hanging over its arena on six tendrils. Everything it has is a struck bell and cold, hollow
# voices; the stone and bone of the arena is the rest. The toll is what the fight is read by, so it carries across the
# whole bowl; toll_far is the same bell from the other side of the cavern and the trophy bell's idle note, so it
# carries 160 blocks (the arena's own hint, heard before it is found). The windup is the tell before a slam, so it has
# to be heard before the slam lands. The reliquary and the key are block sounds in an ancient city, heard close up.
def choir(name, volume=1.0, attenuation=None, count=1):
    """One of the Choir's files, or the several a sound has (name_1, name_2, ...)."""
    out = []
    for i in range(1, count + 1):
        e = {"name": f"{MOD}:creature/hollow_choir/{name}" + ("" if count == 1 else f"_{i}"), "volume": volume}
        if attenuation: e["attenuation_distance"] = attenuation
        out.append(e)
    return out


CHOIR_EVENTS = {
    "choir.wake": choir("wake", 1.0, 64),
    "choir.toll": choir("toll", 1.0, 48),
    "choir.toll_far": choir("toll_far", 0.8, 160),
    "choir.windup": choir("windup", 1.0, 24),
    "choir.slam": choir("slam", 1.0, 32),
    "choir.tendril_cut": choir("tendril_cut", 1.0, 20),
    "choir.fall": choir("fall", 1.0, 32),
    "choir.rise": choir("rise", 1.0, 32),
    "choir.core": choir("core", 0.9, count=3),
    "choir.bloom": choir("bloom", 1.0, 40),
    "choir.sprout": choir("sprout", 0.5, 12),
    "choir.hurt": choir("hurt", 1.0, 24, count=2),
    "choir.idle": choir("idle", 0.7, 32),
    "choir.death": choir("death", 1.0, 64),
    "choir.reliquary": choir("reliquary", 0.8),
    "choir.key_set": choir("key_set", 0.8),
    "choir.grab": choir("grab", 1.0, 40),
    "choir.root": choir("root", 0.8, 24),
}
CHOIR_SUBTITLES = {
    "choir.wake": "The Hollow Choir wakes", "choir.toll": "Hollow Choir tolls",
    "choir.toll_far": "A bell tolls far off", "choir.windup": "A tendril lifts",
    "choir.slam": "A tendril slams down", "choir.tendril_cut": "A tendril parts",
    "choir.fall": "The crown falls", "choir.rise": "The crown rises", "choir.core": "The core is struck",
    "choir.bloom": "Sculk blooms", "choir.sprout": "A sprout rises", "choir.hurt": "Hollow Choir hurts",
    "choir.idle": "The Hollow Choir hums", "choir.death": "Hollow Choir dies",
    "choir.reliquary": "A reliquary grinds open", "choir.key_set": "A choir key settles",
    "choir.grab": "The floor draws in", "choir.root": "A tendril takes root",
}
CREATURE_EVENTS.update(CHOIR_EVENTS)
CREATURE_SUBTITLES.update(CHOIR_SUBTITLES)

# The rune stones (0.23.1; tools/synth_rune_stone_sounds.py makes the files). A stone being dug hums: a loop the
# client plays louder and higher as the pick works, heard only close by. It breaks with a bright crack and a glassy
# chime; each rune it frees lands in the pack with a clear note (the second a fourth higher, by its pitch); and every
# stone answering a broken one chimes from where it lies, softly, as if from inside the rock, heard 32 blocks off.
def rune_stone(name, volume=1.0, attenuation=None, count=1):
    out = []
    for i in range(1, count + 1):
        e = {"name": f"{MOD}:rune_stone/{name}" + ("" if count == 1 else f"_{i}"), "volume": volume}
        if attenuation: e["attenuation_distance"] = attenuation
        out.append(e)
    return out


RUNE_STONE_EVENTS = {
    "rune_stone.hum": rune_stone("hum", 0.8, 12),
    "rune_stone.break": rune_stone("break", 1.0, 24, count=2),
    "rune_stone.catch": rune_stone("catch", 0.7, count=2),
    "rune_stone.flare": rune_stone("flare", 0.9, 32, count=3),
}
RUNE_STONE_SUBTITLES = {
    "rune_stone.hum": "Rune stone hums", "rune_stone.break": "Rune stone shatters",
    "rune_stone.catch": "A rune comes to you", "rune_stone.flare": "A rune stone answers",
}
CREATURE_EVENTS.update(RUNE_STONE_EVENTS)
CREATURE_SUBTITLES.update(RUNE_STONE_SUBTITLES)


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
