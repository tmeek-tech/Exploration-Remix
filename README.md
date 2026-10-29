# Exploration Remix

A Forge mod for Minecraft 1.20.1 that digs the overworld's underground much deeper: three new cave layers under
the pack's own caves, sealed from each other by unbreakable bands with natural gates, each layer built from vanilla
and Create stone and dressed to match a set of reference pictures. Made for a pack with Tectonic, Terrain Slabs,
Immersive Weathering, Create and Better Combat.

**Status: milestone 1 (0.3.0, reworked after the first play test), world generation and cave looks.** The first build's creatures, runs, runes and
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

| Layer | Y range | Refuge | Main biome | Hunting ground |
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
* Each layer is a **cave network**, planned per cell: chambers joined by tunnels, with loops, side branches and dead
  ends, on storeys about 46 blocks apart, carrying on into the neighbouring cells. At least two ways leave every
  landing (most have two or three), and you can walk from a layer's entry landing to its way down (in the last
  layer, to the bedrock landing) without climbing. Storeys are also joined by **climbs**: shafts climbed in short
  stages, with vines up one wall, so no fall is longer than 15 blocks. **Refuges** are bare, calm rooms with no
  plants; the **hunting ground** is the most overgrown part of a layer (nothing hunts yet); everything else is the
  layer's main biome.
* **Deep Gardens and Basalt Galleries:** about 200 chambers per cell, most 15 to 30 blocks across and 8 to 20 tall,
  joined by tunnels 4 to 6 wide. Their set pieces: the Lantern Lake halls, the Ember Gardens' terraced district, the
  Rust Springs and a spiral well in the Deep Gardens; galleries, a great hall of terraces (about one in twenty with a
  lava channel on its lowest tread), a spiral chimney, arches, the Obsidian Tubes' hubs with round tubes (most blind
  ones end in lava) and a lava basin behind the bedrock landing in the Basalt Galleries.
* **Blue Reaches** keep 0.2.0's sizes: the Deep Blue Rift, up to 160 blocks deep, with its ledge path, natural bridges
  and the Asurine Geodes beside it; the deep dark's caverns on two tiers, with an ancient city in about one cell in
  seven. The network's rooms and tunnels join them. Columns, fins and spires stand in the widest spaces, so a view
  along the floor rarely runs past 160 blocks.
* Each layer has a flat water table (lava in the Basalt Galleries, at y -823), with lakes perched above it. Every
  Basalt Galleries room but the bedrock landing and its lava basin is above y -798, the highest the lava will rise
  when a later milestone adds quakes and lava surges; two terraced stairs and a few vine niches lead up from the
  landing.
* **Upper caves:** the deep dark and its ancient cities moved down into the Blue Reaches; where the pack put the
  deep dark above the first band, its ordinary caves are back. The **Rootvaults** are grottos 10 to 40 blocks under
  forests, jungles, taigas, swamps and cherry groves, where the trees above push their roots (in their own wood:
  oak, birch, spruce, jungle, dark oak, cherry or mangrove) through the ceilings, in three looks: root halls with a
  stream and terraced walls, teal grottos around a pool, and ravines open to the sky. Most are 15 to 30 blocks
  across; halls and teal grottos can have a skylight. Tunnels lead from each grotto down into the pack's caves and
  join neighbouring grottos. The Silver Fern Rift and the Ivory Root Cavern are set pieces 40 to 60 blocks across,
  carved into the pack's caves between y -64 and about y 0, never under an ocean or river. The upper caves hold no
  lava. Strongholds and mineshafts stay above y -56 so the End stays reachable.
* **Looks:** new plants and crystals (white glow flowers, lantern fungi and roots, giant lantern fungi with glowing
  caps, wisteria, lantern pods, lavender flowers and glowing gold flowers, silver ferns, orange shelf fungi, lavender
  coral, pale coral fans, ember vines, asurine crystals, blue glow mushrooms and vines), glow berries thick in the
  upper caves, lantern weed on the lake beds, lava pools and falls in the Basalt Galleries, each biome's own fog, water
  colour, particles and ambient sound, steam over hot pools, embers over lava, and pollen in daylight shafts. Tuned
  for vanilla lighting without shaders: every biome carries enough glowing things to see by.

## Commands

`/remix` is for operators, to look around a test world:

| Command | Does |
|---|---|
| `/remix layers` | lists the layers and bands |
| `/remix info` | where you are: layer, biome, and in a deep layer the network's chamber, tunnel, climb or rift there and the way down |
| `/remix tp <1-3>` | the landing at the top of a layer |
| `/remix tp gate <1-3>` | the landing above a band, where its gate starts |
| `/remix tp bedrock` | the bedrock landing of the last layer's network in this cell |
| `/remix tp biome <name>` | a deep biome's nearest chamber in the nine cells round you, set pieces first, on an open floor (also `rootvaults`: the nearest grotto, with its look and trees) |
| `/remix view <name> [n]` | a spot with a wide view into a biome, facing it (n: Rootvaults look 0 to 2, or the n-th place of a deep biome) |
| `/remix slice [x\|z] [half width]` | a picture of the blocks under you, in `remix_slices/` |
| `/remix census [radius]` | how overgrown and lit the caves around you are, per biome |

## Building

```
./gradlew build                 # jar in build/libs, runs the terrain tests
python3 tools/gen_data.py       # regenerates every datapack JSON, model, texture and lang file from tools/*.py
python3 tools/synth_sounds.py   # regenerates the ambient sound recordings
```

`src/preview` holds an offline preview (`TerrainPreview`) that draws each deep layer's network, sections through its
set pieces and the stack of layers without Minecraft. The generated folders are owned by
`tools/gen_data.py`: edit the Python, not the JSON.
