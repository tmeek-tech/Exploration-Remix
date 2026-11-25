# Exploration Remix

A Forge mod for Minecraft 1.20.1 that digs the overworld's underground much deeper: four new cave layers under
the pack's own caves, sealed from each other by unbreakable bands with natural gates, each layer built from vanilla
and Create stone and dressed to match a set of reference pictures. Made for a pack with Tectonic, Terrain Slabs,
Immersive Weathering and Create (not Better Combat, which replaces the same attack as the timed swings).

**Status: 0.12.2: milestone 1's world generation and cave looks, the cave hunters, stage 1 of the rune overhaul
(rune rows on swords, bows and crossbows; see [Runes](#runes)) and timed swings for swords and axes (see
[Swords and axes](#swords-and-axes)).** The first build's runs were removed (git history keeps them); they return in
later milestones, built on these caves.

## Requirements

| What | Version |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.x (built against 47.4.28) |
| Java | 17 |
| Immersive Weathering | 1.20.1-2.0.5 or later, required: the cold caves grow its frosty ferns, frost and icicles, and its frost on the snowy surface gives way to the mod's hoarfrost |
| Create | optional: its stones (asurine, crimsite, ochrum, veridium, limestone, scoria, scorchia) build the layers; vanilla stones stand in without it |
| mcremix | optional: rusted machine parts in the Rust Springs; Create and vanilla parts stand in without it |

**New worlds only.** The overworld is extended to y -784 when a world is created (to y -832 before 0.19.6, when the
Deep Gardens grew shorter). Existing chunks are not regenerated, so a world made with an older version keeps its old
layout where it was explored, and one made before 0.19.6 loses its rows below y -784. One made before 0.19.7 keeps
its old crude oil where it was explored, so pools there may not meet the new ones at the edge of explored ground. In
one made before 0.19.9, the den fissure, vent glow and throat vein blocks (and items) turn into vent beads.

## Installing

1. Put `explorationremix-<version>.jar` in the `mods` folder of the client and the server.
2. Create a new world. The server log says `Deep world installed: overworld now spans y -784 to 319`.
3. Settings are in `config/explorationremix-world.toml` (world shape, read when a server starts) and
   `config/explorationremix-client.toml` (fog, particles and the Upper Caves' crickets, per player).
4. If the server log ever shows `[Exploration Remix] The cave plan of layer ... threw`, the world kept generating: that
   cell's caves were planned again with their layout moved, or left as rock round their landings. Please report the
   line; it names the seed and the cell.

## The world below

| Layer | Y range | Refuge | Main biome | Hunting ground |
|---|---|---|---|---|
| Upper Caves | -64 and up | all safe: Root Vaults, sinkholes, oasis hollows, lush caves, Frost Fern Grotto, dripstone caves, Silver Fern Rift, Ivory Root Cavern | | |
| Band 1 | -65 to -80 | | | |
| Blue Reaches | -81 to -272 | Asurine Geodes | Deep Blue Rift | deep dark |
| Band 2 | -273 to -288 | | | |
| Deep Gardens | -289 to -480 | Chimney Halls | Seep Beds | Vent Domes |
| Band 3 | -481 to -496 | | | |
| Wasteland | -497 to -640 | Ash Hollows | Wasteland | the whole layer (nothing hunts yet) |
| Basalt Galleries | -641 to -784 | Obsidian Tubes | Basalt Galleries; also the Caldera, the Causeway and the Basalt Rifts | none: nothing lives there |

* **Bands** are 16 blocks of unbreakable band stone (none lies between the Wasteland and the Basalt Galleries: the
  Wasteland's caves open into the Galleries' ceiling). Each has one **gate** per 600 by 600 block cell: a twisting
  passage between two landing chambers. The first gate's upper landing sits on the pack's old floor, with a ramp up
  into its caves.
* Each layer is a **cave network**, planned per cell: chambers joined by tunnels, with loops, side branches and dead
  ends, on storeys about 46 blocks apart, carrying on into the neighbouring cells. At least two ways leave every
  landing (most have two or three), and you can walk from a layer's entry landing to its way down (in the last
  layer, to the bedrock landing) without climbing. Storeys are also joined by **climbs**: shafts climbed in short
  stages, with vines up one wall, so no fall is longer than 15 blocks. **Refuges** are bare, calm rooms with no
  plants; the **hunting ground** is the most overgrown part of a layer (nothing hunts yet); everything else is the
  layer's main biome.
* **Deep Gardens** (0.19): a dry vent field shaped by noise, 192 blocks deep, in three bands. The **Seep Beds** on
  top are low flats with flat, knee-deep pools of crude oil (with mcremix) sunk into the low ground, stepping down
  slopes behind dry rock edges, and seep glow along them; the **Chimney Halls** below are
  dark halls under black dripstone, reaching the bottom in places; the **Vent Domes** at the bottom are cave-like
  domes round hollow blackstone vents, crowned and streaked with glowing vent beads, each vent's chamber sunk a few
  blocks into the cave floor as a bowl with beads running down its inside walls. About a third of the rock is black
  dripstone, with pointed black dripstone under its cones; neon glow mats, thinning out in a scattered edge, and glow
  caps light the floors. A few deep vents' throats wind down into the Wasteland's top tunnels.
* **Wasteland** (0.5): the old Basalt Galleries' look in a tighter network made for close encounters. Winding tunnels
  about 5 wide and 6 to 8 tall bend every 14 blocks or so and run at most 36 blocks from door to door between caves 15
  to 30 across, small fork rooms where three ways meet, a few short galleries and a great hall with an arch, on four
  storeys 25 apart joined by staged climbs. There are no dead ends: every room has at least two ways out. Its refuge,
  the **Ash Hollows**, is two pale, bare domed rooms per cell, each cut into a quiet tunnel.
* **Basalt Galleries** (0.5; halls since 0.20.4): the biggest caves in the game, domed halls about 80 to 130 blocks
  across and up to about 90 tall, each its own cave behind ragged basalt walls that break here and there, and every
  hall opens into each of its neighbours through a doorway; their floors are a landscape of ledges and low ground round
  lava lakes. The **Caldera**, a crater of magma round a lava lake with the vents where eruptions will start, and the
  **Causeway**, a field of basalt columns over the lava with a path across their tops, are each one greater hall; the
  **Basalt Rifts** are rifts of basalt and smooth basalt banded with thin flows of scorchia over a lava river, with
  natural bridges; two **Obsidian Tubes** hubs per cell. The rock is basalt and smooth basalt, with a column of scorchia
  now and then. The Wasteland lets out into the halls about every 120 blocks: a crater in its lowest tunnels opens into a
  hall's vault, and a ramp winds down from it to the hall's floor (the walls give way round the holes and the ramps, so
  a ramp that meets one comes down through a gap in it). Walkable arches and bridges cross the halls, and flights of
  stairs lead from the bedrock landing up out of the flood and from the entry landing down to the halls; there are no
  climbs.
  Nothing lives here: no plants and no mobs. Light comes from the lava, a few big lava falls, chains of shallow lava
  pools winding along the halls' low floors, magma and an unseen glow along the rock that lights the halls' walls,
  ledges and vaults as their lava would. The glow (`explorationremix:glow`) is light with nothing to see or hit: a
  placed block, fire, a nether portal, a growing tree or the lava takes its place, and a glow
  taken that way comes back only with the terrain reset (the flood band's also after each flood). `/fill ... keep` and
  `replace minecraft:air` skip it; fill with `replace explorationremix:glow` to clear it.
* **Blue Reaches** keep 0.2.0's sizes: the Deep Blue Rift, up to 160 blocks deep, with its ledge path, natural bridges
  and the Asurine Geodes beside it; the deep dark's caverns on two tiers, with an ancient city in about one cell in
  seven and the Hollow Choir's arena in about one cell in three (below). The network's rooms and tunnels join them.
  The long views stay open: nothing stands in the deep dark's caverns or in the rift only to cut them.
* Each layer has a flat water table (lava in the Wasteland, at y -663, and in the Basalt Galleries, at y -823), with
  lakes perched above it.
* **The Basalt Galleries' lava cycle:** every 20 minutes the lava sea rises 40 blocks, to y -783, and drains again, on
  one clock for everyone on the server: about 13.5 calm minutes, a minute and a half of warnings (deep rumbles closer
  and closer together, the view trembling, the lava bubbling and smoking), a 2-minute rise (a block every 3 seconds),
  a minute at full under heavy smoke and a 2-minute drain with hissing steam. There is no timer on screen. The low
  halls, the Causeway's column path, the canyon floors, the bedrock landing and its flights flood; the ledges, arches,
  the Obsidian Tubes and the way down from the entry landing stay dry, and from any low place dry ground is a short
  walk away. The lava fills only air (and fire and spilled lava) and takes back only what it brought, putting back
  exactly what it took (the glow, and the lava falls as they were), so placed blocks, torches and the world's own lava
  pools stay. The shake can be turned off (`screenShake` in `explorationremix-client.toml`). While the lava stands
  full, the **terrain reset** puts everything changed in the Galleries since the last one (blocks broken or placed,
  lava let flow) back as it was generated, round the players there (mined ores come back as plain rock; containers
  break and drop their contents once the lava is down); `/remix reset` tells where it is, `/remix reset now` runs it at
  once. Quakes and eruptions are not built yet: `/remix quake` and `/remix erupt` say so, and their sections of
  `explorationremix-world.toml` do nothing in this build.
* **Upper caves** are the pack's own (Tectonic's and Immersive Weathering's caves stay as they are), with entrances
  into them from the surface and more plants. The deep dark and its ancient cities moved down into the Blue Reaches;
  where the pack put the deep dark above the first band, its ordinary caves are back.
  * **Root Vaults** under forests, jungles, taigas, swamps and cherry groves: clusters of 2 to 5 chambers of mixed
    sizes (small, medium, large) close together, roofed 4 to 6 blocks under the ground, where the trees above push
    their roots (in their own wood: oak, birch, spruce, jungle, dark oak, cherry or mangrove) through the ceilings.
    Three looks: root halls with a stream and terraced walls, teal grottos around a pool, and ravines open to the
    sky; halls and grottos can have a skylight. Each cluster has one or two mouths up to the surface, descents into
    the pack's caves (most into a plant cave), passages between its chambers and links to the next cluster; every
    chamber has at least two ways out. Under jungles and mangrove swamps the teal grottos and ravines are veridium,
    and where a descent opens into a lush cave the veridium runs on into it; elsewhere they are grey stone with pale
    limestone ledges, and the ravines limestone cliffs. Under snowy forests a whole cluster is **frozen**: round its
    mouths, ravines and skylights deep snow and drifts, ice over the water, packed ice in the walls, Immersive
    Weathering's icicles in clusters, hoarfrost in big patches and giant icicles; further in it thaws to blue frost
    moss and limestone, and only deep inside grow frosty ferns, frost asters, snowbells and frostberry vines.
  * **Sinkholes** under plains, meadows and savannas: a pit open to the sky over a pool, undercut below its rim, with
    a talus slope down one side; grass, flowers and trailing grass in the daylight, lily pads and sugar cane by the
    pool. Under snowy plains they are snowed in.
  * **Oasis hollows** under deserts (sandstone) and badlands (red sandstone and terracotta): a domed hollow round a
    turquoise pool under a skylight, with fan palms, oasis grass, glowing dune lilies and sand trickling from the
    ceiling.
  * **Plant caves** are much more common: about half of the pack's ordinary caves grow plants, and nine in ten of
    those 25 to 65 blocks under the ground, so a way down crosses one almost anywhere. Under cold ground they are the
    **Frost Fern Grotto**: blue frost moss, Immersive Weathering's frosty ferns, snowbells and frostberry vines, its
    frost over the rock and its icy caves' icicle clusters on the ceilings, with rime glowing on the walls.
  * On the snowy surface Immersive Weathering's frost gives way to the mod's hoarfrost, in bigger patches on cliffs,
    banks and overhangs.
  * **Fewer dead ends:** where one of the pack's caves ends blindly, a short tunnel (6 to 24 blocks, never steeper
    than a walkable slope) joins it to another cave nearby, so most ways through the caves loop.
  * **No lava** above the old floor: lava springs, lava lakes and Tectonic's lava tunnels hold water instead.
  * The Silver Fern Rift and the Ivory Root Cavern are set pieces 40 to 60 blocks across, carved into the pack's caves
    between y -64 and about y 0, never under an ocean or river. Strongholds and mineshafts stay above y -56 so the End
    stays reachable.
* **Looks:** new plants and crystals (white glow flowers, lantern fungi and roots, giant lantern fungi with glowing
  caps, wisteria, lantern pods, lavender flowers and glowing gold flowers, silver ferns (placed on the side of a block
  they hang down it, as in the Silver Fern Rift), orange shelf fungi, lavender coral, pale coral fans, ember vines,
  asurine crystals, blue glow mushrooms and vines), glow berries thick in the upper caves, lantern weed on the lake
  beds, lava falls and magma in the Wasteland and the Basalt Galleries, cinder chains instead of
  vines in the lifeless biomes, each biome's own fog, water colour, particles and ambient sound, steam over hot pools,
  embers over lava, and pollen in daylight shafts. Tuned for vanilla lighting without shaders: every biome carries
  enough glowing things to see by. While the camera is under the old world floor (y -64) the clouds are moved far
  above it, so no clouds (a shader pack's included) show past the edge of the drawn world in the deep caves.
* **Sounds** (0.20.9): each layer has an ambience of its own. In the mod's own upper caves, where the birds were, a soft
  far chorus of crickets sings (0.21.1; the client config can let it sing in the pack's caves too, wherever you are well
  underground), and now and then a cricket sings from the rock near you, going quiet when you come within a few blocks
  and starting again once you have passed. The frozen Root Vaults and the Frost Fern Grotto are cold and still: a thin
  draft, and now and then the tick or the long falling song of ice. The Deep Gardens are hushed, a faint draft and a
  far-off vent sighing now and then, and live vents hiss and simmer softly within a few blocks of their crowns. The
  Basalt Galleries have a low, steady rumble of the deep rock, smooth, so the lava's warning rumbles still stand out.
  `cricketCaves` and `nearCrickets` in `explorationremix-client.toml` say where the crickets sing.

## Hostile mobs in groups

Vanilla's hostile mobs come out in groups of their own kind, each kind in its own home, in place of vanilla's scattered
spawns in the overworld:

* **Woods and savannas** (forests, taigas, jungles, swamps, savannas, cherry groves, wooded badlands; under trees, not
  in their open patches): zombies and skeletons together, a skeleton leading.
* **Dark oak forests:** spider packs (a few cave spiders among them where the biome has them) and witch covens of two.
* **The green Upper Caves** (lush caves, Rootvaults and frozen Rootvaults, Silver Fern Rifts, the Frost Fern Grotto):
  creepers and slimes, in groups of their own kind.
* **Deserts:** husks. **Snowy land:** strays. **Rivers and the sea:** drowned.
* **Everywhere else** (plains, meadows, peaks, ordinary caves, the deep layers): nothing but a lone enderman now and
  then, at most one near you and often none. Biomes with no monsters at all (mushroom fields, the deep dark, the
  Basalt Galleries) stay empty.

A group is 3 to 5 (a witch coven 2) and comes out 24 to 80 blocks from you, only where it is dark enough, as vanilla's
monsters do; two groups of each home are kept round you and a third comes now and then. A group roams together (a
member that strays more than 8 blocks from its leader walks back), fights together (when one turns on you, the others
within 24 blocks come too) and despawns together. Grouped monsters never hurt or turn on one another (a skeleton's
arrow does not hurt a zombie in its way), though a creeper's blast still hurts everything round it; a slime comes out
no bigger than its spot has room for. Spawners, the structures' own spawns (witch huts, ocean monuments,
pillager outposts), patrols, phantoms, village sieges, the Nether and the End are untouched. Sizes, counts, the
enderman's chance and how wooded the woods must be are in `explorationremix-world.toml` (`[mobGroups]`, read while the
server runs; `enabled = false` brings vanilla's own spawning back).

## The Hollow Choir

The Deep Dark's boss (0.21.9). It does not wander and it cannot be stumbled into: you call it, into an arena that was
always waiting, with three keys you have carried down from three ancient cities.

**The keys.** Every planned ancient city has one **reliquary** at its heart, a casket on the city's own floor holding a
single **choir key**. Three cities, three keys. Nothing else drops one.

**The arena.** About one Blue Reaches cell in three has a **sunken amphitheatre**, cut into the floor of one of that
cell's deep dark caverns: a round deepslate bowl 48 blocks across and 6 deep, three unclimbable tiers of seats round
it, two ramps cut through them to walk in and out, broken pillars on the fighting floor and twelve braziers burning
pale blue, each with a flame standing a block out of its bowl: six round the seats, three in on the fighting floor and
three on the dais ring, since a bowl this wide cannot be lit from its edge alone. Nothing is built over the rim, so the cavern's own ceiling is the roof and you can see clean across the bowl
from anywhere on it. A sleeping arena rings once every half minute or so, and the toll carries about 150 blocks
through the rock, so you hear one before you find one; that and the pale blue glow are the only hints, and there is no
trail and no pull on the keys to lead you there. Three **key sockets** stand on the rim walk, a third of a turn apart.
Put a key in each and the Choir comes. It holds its first blow for four seconds: time to see what has woken and to
get down off the rim.

**No Warden.** The arena stands in the Deep Dark, and vanilla's sculk grows sensors and shriekers wherever it likes,
the rim walk included. The first key set in a socket strikes dumb every shrieker within about 60 blocks of the dais
(0.22.0): they still shriek at a noise, but they warn nobody and can never call the Warden, here or after. The walk
to an arena is as dangerous as the rest of the Deep Dark; the fight in it is the Choir's alone.

**The fight.** A crown of bone plates hangs over the dais with a bell inside it, held up by six great sculk tendrils
that run from the cavern's ceiling past the crown down to roots on the floor. The roots stand still, eight blocks out
on the half spokes between the sockets (two of them in the paved lanes that run in from the ramps), and no broken
pillar stands within reach of one (0.22.2; they turned with the
crown before, and until this build each was drawn on the far side of the dais from where a sword found it). While the
crown is up it cannot be hurt at all: a blow on it rings the bell and nothing more. The way in is the tendrils. Damage to a tendril wears that
tendril through *and* tells on the Choir itself, so the bar moves while you work; cut the last standing tendril and the
crown has nothing left to hang from. It falls, the bell opens, and for nine seconds the core inside takes everything
you have. Then it climbs back and grows new tendrils, fewer and tougher each phase: six, then five, then four. A
strong attack that lands while a tendril is rearing to slam spoils the slam and leaves the Choir stalled.

Three phases, and the arena changes with each: the braziers go out a ring at a time, outside in, and the sculk creeps
further out over the floor, so the last phase is fought on ground that is nearly all sculk by the three flames left
standing on the dais. It raises
rooted **sprouts** to mend itself and blunt your blows; they are the only help it gets, and they can be cut down.
Leave the bowl and it lets you go, putting the arena back as it was.

Lose and nothing is spent: the Choir goes, the arena resets and your three keys stay in their sockets, so the next
try costs only the walk back; use any filled socket and it comes again, whole. Win and it gives up about twenty levels' worth of experience, a **choir bell**
to hang as a trophy, and two runes. It can be called again with three new keys.

## Runes

Runes are items set into a row on a sword, bow or crossbow. A row reads in speaking order, carrier first: `Spark, On
hit, Burst` is a spark that bursts where it hits, and `Fan` copies whatever follows it. A sword casts on the cast key
(**R** by default) and again each time it is ready while the key is held; right click stays the game's own (a shield
in the other hand blocks). A bow or crossbow casts with a full shot, its arrow the first carrier. Each cast is paid from the gear's energy in full or not at all; full-strength sword hits on creatures
fighting you charge the sword, and Siphon knocks loose white motes that refill it. Slots wake as a run goes deeper.
Press **K** with the gear in hand, once nothing has hurt you for 5 seconds, to edit the row; a rune that does nothing
is grey and says why. The creative tab **Runes** holds every rune. Limits and who runes may touch (other players
only with `runesHurtPlayers` on) are in the server config `explorationremix-runes.toml`.

| Command | Does |
|---|---|
| `/remix rune give <rune\|all> [count]` | rune items |
| `/remix rune row <runes>` | sets the held gear's row: rune ids in order, `_` for an empty slot, `\|` for a cast break |
| `/remix rune clear` | empties the held gear's row |
| `/remix rune energy [amount]` | fills the held gear's energy, or sets it |
| `/remix rune depth <0\|1\|2\|auto>` | the run depth that wakes extra slots, kept until `auto` |
| `/remix rune info` | the held gear's row, how it reads and what each cast costs |

## Swords and axes

Swords (other mods' too) and axes swing the timed way. A tap of the attack button is a light swing that lands a
quarter of a second after the press; light swings in a row make a chain (a sword's slash, rising backhand and overhead chop;
an axe's slower slash and chop), and after the chain's last swing comes a 1.2 second cooldown in which only a strong
attack can be made. Held, the swing turns strong: the blade draws back and fires on its own half a second after the
press (an axe's 0.7), lunges about a block and a half and strikes everything in its line; then nothing can start for
1.2 seconds. Every swing that lands is at full strength (vanilla's charge bar is gone for these weapons). A strong
attack that lands while a Mossback or a Skein rears to strike stops the strike and leaves it reeling. Marks under the
crosshair show the chain and the cooldown. A press on a block the weapon is meant for (an axe on wood, a sword on a
cobweb) with nothing in reach mines it as ever. When a runed sword gets back the energy for its next cast, a pale
blue-white light runs over the whole sword's own pixels from the pommel to the tip, once, and fades, with a quiet
shimmer and a soft note in your own hands; then, for as long as it has that energy, its pixels sparkle here and there
(under shader packs too, and on swords drawn by their own renderer, such as Create's cardboard sword).

## Commands

`/remix` is for operators, to look around a test world:

| Command | Does |
|---|---|
| `/remix layers` | lists the layers and bands |
| `/remix info` | where you are: layer, biome, and in a deep layer the network's chamber, tunnel, climb or rift there and the way down |
| `/remix tp <1-4>` | the landing at the top of a layer |
| `/remix tp gate <1-4>` | the landing above a band, where its gate starts |
| `/remix tp bedrock` | the bedrock landing of the last layer's network in this cell |
| `/remix tp biome <name>` | a deep biome's nearest chamber in the nine cells round you, set pieces first, on an open floor; also the Upper Caves' `rootvaults`, `frozen_rootvaults`, `sinkhole`, `oasis_hollow` and `frost_fern_grotto` |
| `/remix view <name> [n]` | a spot with a wide view into a biome, facing it (n: Root Vaults look 0 to 2, `tropical_rootvaults` the same in a jungle or mangrove cluster, frozen Root Vaults 0 the snowy zone round the openings or 1 the thawed halls deep inside, oasis rock 0 sand or 1 red, or the n-th place of a deep biome); also `lush_veridium`, where a Root Vault's descent opens into a lush cave, and `rootvaults_mouth [1]`, the nearest Root Vault mouth (1: a frozen one) seen from inside, looking up it at the daylight |
| `/remix entrances [cells]` | the Upper Caves' entrances in the 64-block cells round you: how many cells of each cover got one and why the others did not, and the nearest of each kind; works from the server console with `execute positioned` |
| `/remix slice [x\|z] [half width]` | a picture of the blocks under you, in `remix_slices/` |
| `/remix census [radius]` | how overgrown and lit the caves around you are, per biome |
| `/remix lava` | the Basalt Galleries' lava cycle: its phase, the time left in it, the lava's height, how many chunks round the players are still catching up, and what the flood costs per tick |
| `/remix lava set <phase> [seconds]` | jumps the cycle to that far into `calm`, `warning`, `rise`, `full` or `drain` |
| `/remix lava level <0-40>` | jumps the lava to that height over its table and holds the cycle there |
| `/remix lava freeze`, `/remix lava unfreeze` | holds the cycle where it is, or lets it run on |
| `/remix lava length <minutes>` | the cycle's length from now on, keeping its place in the phase (0: back to the config's) |
| `/remix deadends [radius]` | the blind cave ends left above the old floor in the loaded chunks round you, what lies round them, how many generation joined and why the rest were not, any lava in the caves there, and how many of the surface biome's ore placements generation let into the plant caves' and entrances' rock |
| `/remix groups list [radius]` | the groups of hostile mobs round you: each one's leader, members and how far they keep |
| `/remix groups here` | the group home of the ground, cave or water where you are, the groups of each home round you, and any monsters round you in no group |
| `/remix groups spawn <kind>` | a group of `undead`, `spiders`, `witches`, `creepers`, `slimes`, `husks`, `strays` or `drowned` near you, at the first place of its home dark enough to take it |
| `/remix groups stats` | what the group spawner has done since the server started |
| `/remix choir` | the Hollow Choir's arena nearest you: where its bowl, rim and ceiling are, which cavern tier it is cut into, where its three sockets stand, how many are filled, and, if the Choir is up, its phase, tendrils standing, health and sprouts |
| `/remix choir tp` | onto that arena's rim, looking in across the bowl |
| `/remix choir city` | the heart of the nearest planned ancient city, where its reliquary holds one choir key |
| `/remix choir keys` | three choir keys |
| `/remix choir summon` | fills that arena's three sockets and calls the Choir |
| `/remix choir cut` | parts every tendril that arena's Choir has standing, so the crown falls and the core opens |
| `/remix choir phase <0-3>` | dresses that arena as it stands at rest or in a phase of the fight (the braziers, the sculk and the sprouts), without fighting through to it |

`/remix choir`, `/remix choir summon`, `/remix choir cut` and `/remix choir phase` go by where the command came from rather than by a
player, so they work from the server console with `execute positioned <x> <y> <z> run remix choir ...`.

## Building

```
./gradlew build                 # jar in build/libs, runs the terrain tests
python3 tools/gen_data.py       # regenerates every datapack JSON, model, texture and lang file from tools/*.py
python3 tools/synth_sounds.py   # regenerates the drips, the Deep Blue Rift's roar and the rolls of distant rumbling
python3 tools/synth_layer_sounds.py  # regenerates each layer's own ambience: crickets, ice, vents, the Galleries' rumble
python3 tools/synth_lava_sounds.py  # regenerates the lava cycle's rumbles and surge
python3 tools/synth_swing_sounds.py # regenerates the swings' and the hunters' wind-up sounds
python3 tools/synth_choir_sounds.py # regenerates the Hollow Choir's tolls, bells, slams and the arena's far toll
```

`src/preview` holds an offline preview (`TerrainPreview`) that draws each deep layer's network, sections through its
set pieces and the stack of layers without Minecraft. The generated folders are owned by
`tools/gen_data.py`: edit the Python, not the JSON.
