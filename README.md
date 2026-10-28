# Exploration Remix

A Forge mod for Minecraft 1.20.1 that turns the overworld's underground into a run from the surface to bedrock:
four deep cave layers separated by unbreakable bands, a creature ecology that hunts by sound, and a rune grammar
that fully replaces vanilla enchanting. Built for a pack that already has Overhauled Overworld, Terrain Slabs,
Tectonic, Immersive Weathering, Create and Better Combat.

Status: first build. Every system in the first build list is implemented and the mod loads, generates and runs
on a dedicated server in a headless test. It has **not** yet been played in a live client; expect balance and
polish passes after the first play session.

## Requirements

| What | Version |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.x (built against 47.4.28) |
| Java | 17 |
| Create | optional; its stones (asurine, crimsite, ochrum, veridium, limestone, scoria, scorchia) decorate the layers, and the mod removes Create's striated ore layers from the overworld (config) |
| Better Combat | optional; the `on_combo_finisher` trigger fires on a combo's last swing with it, or every third hit within two seconds without it |

**New worlds only.** The mod extends the overworld to y -832 (configurable per layer) when a world is created.
Opening an existing world with the mod installed will not add the layers under existing chunks.

## Installing

1. Drop `explorationremix-<version>.jar` into the pack's `mods` folder (client and server).
2. Create a new world. The first log line to look for is `Deep world installed: overworld now spans y -832 to 319`.
3. Config is written to `config/explorationremix-common.toml` on first launch.

## The deep world

Below the pack's own floor (y -64) sit four layers, each sealed off by a 16-block **band** of unbreakable
Band Stone. The only way through a band is its **gate**: a twisting passage, one per 600x600 block cell, that
opens into a landing chamber on each side. Every gate's lower landing holds a **nest** (egg clusters guarded by
Nest Swarmers, Wardens and Spitters); breaking every cluster of the nest is what earns the layer's gate XP and
opens the nest for that run. Nests regrow when the run ends.

| Layer | Y range (default) | Refuge | Route | Hunting grounds | Hunters |
|---|---|---|---|---|---|
| Lantern Deeps | -208 to -81 | Dripstone Halls | Ivory Root Cavern | Lantern Lake, Amber Hive | Climber |
| Blue Reaches | -384 to -225 | Asurine Geodes | Deep Blue Rift | Silver Fern Rift, Deep Dark Reach | Climber, Ceiling Ambusher |
| Ember Gardens | -592 to -401 | Banded Iron Halls | Rust Springs | Ember Gardens | Ambusher, Burrower |
| Basalt Galleries | -832 to -609 | Obsidian Tubes | Basalt Galleries | Smolder Groves | Burrower, Smasher |

Inside a layer the **route** is a planned, meandering cavern from the entry landing to the exit gate, with bare
stone **refuges** (no plants, no hunters follow for long) along it. Everything off the route is hunting ground:
overgrown, loud, and where the hunters live. The bottom of the last layer is the **bedrock landing**: a bowl of
bedrock that ends the run and (by default) sends the player home after five seconds.

Vanilla dripstone caves and the deep dark above the first band are replaced by **Rootvaults**; dripstone moved to
the Dripstone Halls refuge and the deep dark to the Deep Dark Reach hunting ground. Strongholds and mineshafts are
kept above y -56 so the End stays reachable.

Each layer has a thicker fog, its own stone, its own crystal growth and its own fluid (water in the first three,
lava in the Basalt Galleries).

## Runs

A **run** starts when a player passes below the first band and ends on death, on reaching the bedrock landing,
on climbing back above the first band, on changing dimension, or on switching to creative or spectator.
Inventory is kept on death (config). What a run loses is its runes and its run XP.

* **Run XP** is a currency, not a bar: kills in the layers award it (more per layer), and clearing a nest awards
  a lump. Vanilla XP orbs are blocked, so there is no ore or mob farm route to it.
* **Rune blocks** stand in ruins (every refuge has one, and there are more along the route and deep in the hunting
  grounds). Waking one costs run XP and offers runes for the tool you hold. Rune blocks are the only place runes
  are added, moved or removed; the tool tooltip and the **runes screen** (default key `G`) are read-only.
* **Attunement books** are found with Old Remains in the ruins deepest in the hunting grounds. Using one on a tool
  at a rune block makes that book's rune family (movement, combat or fun) more likely in that tool's offers for
  good. Looted remains stay looted; unused books crumble when the run ends.
* **Reaching bedrock** ends the run with a summary screen. Runes and XP are gone; the player, their inventory,
  and whatever they carried home remain.

## Runes

Every tool carries two **sentences** of three **slots** each (config). Each new layer reached adds a slot to each
sentence. A sentence reads left to right: a **trigger**, then any number of **modifiers**, then an **effect**;
**passives** sit anywhere and are always on. A tool starts a run with `On hit · Spark` and `On jump · Leap`.

| Kind | Runes |
|---|---|
| Triggers | On hit, On kill, On jump, On block, On finisher, On momentum hit, On stagger, When hurt, While falling, While sneaking |
| Modifiers (stack) | Twice, Wide, Heavy, Chain, Delayed, Quiet |
| Effects | Spark, Ember, Frost, Shove, Dash, Leap, Glide, Ward, Mend, Lure, Smoke, Blast, Confetti |
| Passives | Keen Edge, Thick Hide, Cat's Feet |

Effects are physical and talk to each other: Spark arcs further through wet creatures and water, Frost freezes a
wet creature solid and a heavy hit shatters it, Ember spreads between packed creatures but never to blocks,
Shove slams creatures into walls for impact damage, and Blast is the only thing that breaks layer stone (never
band stone). Runes are data: `data/explorationremix/runes/*.json`.

## Vanilla enchanting is gone

* The enchanting table recipe is removed and existing tables do nothing.
* Enchanted books in loot become plain books; enchanted loot, fishing and mob-drop gear is stripped.
* Villagers no longer sell enchanted books or bottles o' enchanting; enchanted gear trades become plain gear.
* Anvils repair and rename for free and are never "too expensive".
* Backstop: any enchantment that slips through (a command, another mod) does nothing, unless its id is on the
  `allowedEnchantments` config list.

## Combat and creatures

The mod adds its own combat layer on top of Better Combat's animations: **poise** and **stagger** (a staggered
enemy takes extra damage), **momentum hits** (out of a sprint, a Dash, a Leap or a fall), **wall slams**, hit-stop
and a small camera kick. No dodge, no parry, no stamina.

* **Hunters** (Climber, Ceiling Ambusher, Burrower, Smasher) are assigned per player per layer, hunt by sound
  (footsteps, mining, combat, Spark), search when they lose you, give up quickly inside a refuge, and respawn
  after a while.
* **Nests** at each gate hold Swarmers, Wardens and Spitters around egg clusters; breaking clusters wakes them.
* **Cave Flockers** roost on cave ceilings and scatter when disturbed, which hunters hear.
* **Surface clusters**: with vanilla hostile spawning off, hostiles arrive in clusters at night on the surface and
  in dark forests, and creepers show up in lush caves and Rootvaults. Mob spawners spawn nothing hostile.
* Teleports (chorus fruit, ender pearls, commands) never cross a band; beds and spawn points only work near the
  surface; elytra do not work below the first band; nether portals cannot be lit below it.

## Config

`config/explorationremix-common.toml`, grouped as `world`, `world.<layer>`, `runs`, `enchanting`, `combat`,
`creatures` and `client`. Every value has a comment. The ones to know about:

* `world.enabled`, `bandThickness`, `gateSpacing`, `landingRadius`, `removeCreateStriations`, `replaceVanillaCaveBiomes`
* `world.<layer>.height` (multiple of 16), `fluid`, `style`, `refugeBiome`, `routeBiome`, `huntingBiomes`, `cavernScale`
* `runs.startingSentences`, `startingSlots`, `keepInventory`, `returnHomeFromBedrock`, `endRunOnLogout`, `xpPerKillBase`, `runeBlockBaseCost`
* `enchanting.allowedEnchantments`, `freeAnvils`, `stripLootEnchantments`
* `creatures.huntersPerPlayerPerLayer`, `hunterHearingRange`, `nestEggClusters`, `disableNaturalHostiles`
* `client.layerFog`, `fogStrength`, `combat.screenShake`, `hitStopTicks`

## Commands (operators)

`/remix layers` prints the layer table. `/remix tp <gate|1-4|layer id|bedrock>` jumps to a gate, a layer or the
bedrock landing. `/remix cell` describes the current gate cell (route, refuges, ruins, nest). `/remix slice`
writes a cross-section image of the terrain around you. `/remix run start|end|info`, `/remix rune add|clear|list`,
`/remix xp <points>`, `/remix book <family>` and `/remix slots <n>` drive a run by hand for testing.

## Building from source

```
JAVA_HOME=/opt/jdk17 ./gradlew build
```

The jar lands in `build/libs/`. `./gradlew runClient` and `./gradlew runServer` start the dev environment;
`ER_DEVRUN=1 ./gradlew runServer --args="nogui"` runs the headless world-generation smoke test and writes
`run/remix_devrun/report.txt` plus terrain slices. `./gradlew test` runs the rune grammar and rune data tests.

Most data files (biomes, features, placed features, tags, models, textures, lang, runes, creature shapes) are
generated by `python3 tools/gen_data.py`; edit the generator rather than the JSON.

## Layout

```
src/main/java/com/explorationremix/
  world/        DeepWorld (layer layout), density functions, surface rules, biome resolver, features, cell plans
  run/          RunManager, RunXp, run summary
  rune/         grammar, registry, casting, rune block menu
  enchant/      vanilla enchanting removal
  loophole/     teleport, bed, elytra, portal and spawn rules
  combat/       poise, stagger, momentum, wall slams
  creature/     hunters, nests, flocks, clusters, directors, entities
  client/       fog, HUD, runes screen, creature renderer
  mixin/        the handful of vanilla hooks that events cannot reach
tools/          data generators (gen_data.py, gen_runes.py, gen_creatures.py)
```
