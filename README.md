# Exploration Remix

A Forge mod for Minecraft 1.20.1 that digs the overworld's underground much deeper: three new cave layers under
the pack's own caves, sealed from each other by unbreakable bands with natural gates, each layer built from vanilla
and Create stone and dressed to match a set of reference pictures. Made for a pack with Tectonic, Terrain Slabs,
Immersive Weathering, Create and Better Combat.

**Status: milestone 1 (0.2.0), world generation and cave looks.** The first build's creatures, runs, runes and
combat changes were removed (git history keeps them); they return in later milestones, built on these caves.

## Requirements

| What | Version |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.x (built against 47.4.28) |
| Java | 17 |
| Create | optional: its stones (asurine, crimsite, ochrum, veridium, limestone, scoria, scorchia) build the layers; vanilla stones stand in without it |
| mcremix | optional: rusted machine parts in the Rust Springs; Create and vanilla parts stand in without it |

**New worlds only.** The overworld is extended to y -832 when a world is created. Existing chunks are not
regenerated.

## Installing

1. Put `explorationremix-<version>.jar` in the `mods` folder of the client and the server.
2. Create a new world. The server log says `Deep world installed: overworld now spans y -832 to 319`.
3. Settings are in `config/explorationremix-world.toml` (world shape, read when a server starts) and
   `config/explorationremix-client.toml` (fog and particles, per player).

## The world below

| Layer | Y range | Refuge | Route | Hunting ground |
|---|---|---|---|---|
| Upper Caves | -64 and up | all safe: Rootvaults, lush caves, dripstone caves, Silver Fern Rift, Ivory Root Cavern | | |
| Band 1 | -65 to -80 | | | |
| Blue Reaches | -81 to -272 | Asurine Geodes | Deep Blue Rift | deep dark |
| Band 2 | -273 to -288 | | | |
| Deep Gardens | -289 to -528 | Rust Springs | Lantern Lake | Ember Gardens |
| Band 3 | -529 to -544 | | | |
| Basalt Galleries | -545 to -832 | Obsidian Tubes | Basalt Galleries | |

* **Bands** are 16 blocks of unbreakable band stone. Each has one **gate** per 600 by 600 block cell: a twisting
  passage between two landing chambers. The first gate's upper landing sits on the pack's old floor, with a ramp up
  into its caves.
* Each layer has a **route**, a meandering cavern you can walk from its entry landing down to the next gate (or to
  the bedrock landing in the last layer), with **refuges** beside it: bare, calm spaces with no plants. Everything
  else is **hunting ground**, the most overgrown part of each layer. Nothing hunts yet.
* Spaces are sized to each biome's picture, not grown with depth: the Deep Blue Rift is up to 160 blocks deep,
  Deep Gardens halls 30 to 100 tall around lakes up to 200 across, Basalt Galleries 120 to 180 tall.
* Each layer has a flat water table (lava in the Basalt Galleries, at y -823). The Basalt Galleries keep their
  arches, Obsidian Tubes, some high ledges and the last gate's landing above y -798, the highest the lava will rise
  when a later milestone adds quakes and lava surges.
* **Upper caves:** the deep dark and its ancient cities moved down into the Blue Reaches, and the **Rootvaults**
  take their old places, in three looks (root halls, teal grottos, ravines open to daylight). Inside the Rootvaults
  the lava lakes at the bottom of the pack's caves are still water instead. The Silver Fern Rift and the Ivory Root
  Cavern are carved into the pack's caves between y -64 and about y 0. Strongholds and mineshafts stay above y -56
  so the End stays reachable.
* **Looks:** new plants and crystals (white glow flowers, lantern fungi and roots, giant lantern fungi with glowing
  caps, wisteria, lantern pods, lavender flowers and glowing gold flowers, silver ferns, orange shelf fungi, lavender
  coral, pale coral fans, ember vines, asurine crystals, blue glow mushrooms and vines), glow berries thick in the
  upper caves, sea pickles on the lake beds, lava pools and falls in the Basalt Galleries, each biome's own fog, water
  colour, particles and ambient sound, steam over hot pools, embers over lava, and pollen in daylight shafts. Tuned
  for vanilla lighting without shaders: every biome carries enough glowing things to see by.

## Commands

`/remix` is for operators, to look around a test world:

| Command | Does |
|---|---|
| `/remix layers` | lists the layers and bands |
| `/remix info` | where you are: layer, biome, route |
| `/remix tp <1-3>` | the landing at the top of a layer |
| `/remix tp gate <1-3>` | the landing above a band, where its gate starts |
| `/remix tp bedrock` | the bedrock landing at the end of the last layer |
| `/remix tp biome <name>` | the nearest spot of a biome (also `rootvaults`) |
| `/remix view <name> [n]` | a spot with a wide view into a biome, facing it (n: Rootvaults look 0 to 2, or another set piece) |
| `/remix slice [x\|z] [half width]` | a picture of the blocks under you, in `remix_slices/` |

## Building

```
./gradlew build                 # jar in build/libs, runs the terrain tests
python3 tools/gen_data.py       # regenerates every datapack JSON, model, texture and lang file from tools/*.py
python3 tools/synth_sounds.py   # regenerates the ambient sound recordings
```

`src/preview` holds an offline preview that draws the terrain without Minecraft. The generated folders are owned by
`tools/gen_data.py`: edit the Python, not the JSON.
