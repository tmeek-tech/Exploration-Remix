package com.explorationremix.config;

import com.explorationremix.world.terrain.LayerSettings;
import com.explorationremix.world.terrain.TerrainSettings;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * World settings, read when a server starts. Written to {@code config/explorationremix-world.toml}. The sizes are
 * starting values, not limits.
 */
public final class ERConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ERConfig INSTANCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        INSTANCE = new ERConfig(b);
        SPEC = b.build();
    }

    public final ForgeConfigSpec.BooleanValue worldEnabled;
    public final ForgeConfigSpec.IntValue bandThickness;
    public final ForgeConfigSpec.IntValue gateSpacing;
    public final ForgeConfigSpec.DoubleValue passageRadius;
    public final ForgeConfigSpec.DoubleValue landingRadius;
    public final ForgeConfigSpec.IntValue structureFloorY;
    public final ForgeConfigSpec.BooleanValue replaceVanillaCaveBiomes;
    public final ForgeConfigSpec.BooleanValue joinDeadEnds;
    public final ForgeConfigSpec.BooleanValue removeCreateStriations;
    public final ForgeConfigSpec.BooleanValue upperRegions;
    /** The Upper Caves grown from noise (0.13): the switch for all of it, one per shape, and vanilla's worm tunnels. */
    public final ForgeConfigSpec.BooleanValue upperNoise, upperWarrens, upperRifts, upperHalls, upperSinkholes, upperWormTunnels, upperHostRock, upperDenRifts,
            upperMerge;
    public final LayerConfig[] layers = new LayerConfig[4];
    // The Basalt Galleries' lava cycle (world.lava.LavaFlood), read while the server runs.
    public final ForgeConfigSpec.BooleanValue lavaCycleEnabled;
    public final ForgeConfigSpec.DoubleValue lavaCycleMinutes, lavaCalm, lavaWarning, lavaRise, lavaFull, lavaDrain, lavaBreakShare;
    public final ForgeConfigSpec.IntValue lavaChecksPerTick, lavaChangesPerTick, lavaRadius, lavaClimbs;
    // 0.9.0: the Basalt Galleries' quakes (world.quake.Quakes) and the Caldera's eruptions (world.eruption.Eruptions), and
    // 0.5.1's terrain reset (world.lava.TerrainReset), read while the server runs. Keys whose meaning or default changed
    // from 0.8.0's are new names, so Forge drops the old ones from a player's file and writes the new defaults.
    public final ForgeConfigSpec.BooleanValue quakesEnabled, quakeTremors, quakeClimbTremors, quakeTopple, quakeToppleAtOnce;
    public final ForgeConfigSpec.DoubleValue quakeTremorMinSeconds, quakeTremorMaxSeconds, quakeMinMinutes, quakeMaxMinutes, quakeSeconds,
            quakeDebrisNear;
    public final ForgeConfigSpec.IntValue quakeSupportDepth, quakeToppleMax, quakeLowerTicks, quakeDebrisPerQuake, quakeDebrisRadius,
            quakeDebrisWarnTicks, quakeDebrisDamage, quakeDebrisMax;
    public final ForgeConfigSpec.BooleanValue eruptionsEnabled;
    public final ForgeConfigSpec.DoubleValue eruptionMinMinutes, eruptionMaxMinutes, eruptionRisingMinMinutes, eruptionRisingMaxMinutes,
            eruptionWarnSeconds, eruptionBurstSeconds, eruptionBlastPower, eruptionCraterRock, eruptionBombDamage, eruptionBombKnockback,
            eruptionCraterMagma, eruptionRisingScale;
    public final ForgeConfigSpec.IntValue eruptionWakeSeconds, eruptionBombs, eruptionBombsNear, eruptionNearMinBlocks, eruptionNearMaxBlocks,
            eruptionVentNearBias, eruptionVentBurn, eruptionEjecta, eruptionRange, eruptionHeardRange;
    public final ForgeConfigSpec.BooleanValue resetEnabled;
    public final ForgeConfigSpec.BooleanValue terrainSlabs;
    public final ForgeConfigSpec.IntValue resetChangesPerTick;
    // 0.18.0: vanilla's hostile mobs in groups (creature.groups), read while the server runs.
    public final ForgeConfigSpec.BooleanValue mobGroupsEnabled;
    public final ForgeConfigSpec.IntValue mobGroupMin, mobGroupMax, mobGroupsKept, mobGroupsMost;
    public final ForgeConfigSpec.DoubleValue mobEndermanChance, mobWoodsCanopy;

    public static final class LayerConfig {
        public final String id;
        public final ForgeConfigSpec.IntValue height;
        public final ForgeConfigSpec.IntValue fluidDepth;
        public final ForgeConfigSpec.DoubleValue heightMin, heightMax, widthMin, widthMax;
        final boolean lava;

        LayerConfig(ForgeConfigSpec.Builder b, LayerSettings d, String heightWord, String widthWord, boolean sizesRead,
                    int minHeight, int maxHeight, int maxFluidDepth) {
            this.id = d.id();
            this.lava = d.lava();
            b.push(d.id());
            // The ranges are what each layer's cave planner can build in (see the layers below). A value outside its range
            // is put back to the default by Forge, which also keeps an older file's 288 for the Basalt Galleries (their
            // height before 0.5) from moving the world's floor 144 blocks down.
            height = b.comment(minHeight == maxHeight
                            ? "Height of the layer in blocks: always " + minHeight + ", the height its caves are built for (another value is put back)."
                            : "Height of the layer in blocks (rounded down to a multiple of 16).")
                    .defineInRange("height", d.height(), minHeight, maxHeight);
            fluidDepth = b.comment("The " + (d.lava() ? "lava" : "water") + " table sits this many blocks above the layer's floor.")
                    .defineInRange("fluidDepth", d.fluidDepth(), 6, maxFluidDepth);
            // Since 0.3 the Deep Gardens and the Basalt Galleries are cave networks that size their own rooms; their four
            // space sizes are kept so old config files still load, but nothing reads them.
            String unused = sizesRead ? "" : " Not used since 0.3: the cave network sizes this layer's rooms itself.";
            heightMin = b.comment("Smallest " + heightWord + "." + unused).defineInRange("spaceHeightMin", d.heightMin(), 8.0, 400.0);
            heightMax = b.comment("Largest " + heightWord + "." + unused).defineInRange("spaceHeightMax", d.heightMax(), 8.0, 400.0);
            widthMin = b.comment("Smallest " + widthWord + "." + unused).defineInRange("spaceWidthMin", d.widthMin(), 8.0, 400.0);
            widthMax = b.comment("Largest " + widthWord + "." + unused).defineInRange("spaceWidthMax", d.widthMax(), 8.0, 400.0);
            b.pop();
        }

        LayerSettings toSettings() {
            double hMin = Math.min(heightMin.get(), heightMax.get()), hMax = Math.max(heightMin.get(), heightMax.get());
            double wMin = Math.min(widthMin.get(), widthMax.get()), wMax = Math.max(widthMin.get(), widthMax.get());
            return new LayerSettings(id, height.get(), fluidDepth.get(), lava, hMin, hMax, wMin, wMax);
        }
    }

    private ERConfig(ForgeConfigSpec.Builder b) {
        TerrainSettings d = TerrainSettings.defaults();
        b.comment("The deep world below the pack's old floor. Changes apply to newly generated chunks.").push("world");
        worldEnabled = b.comment("Extend the overworld down through the four deep layers. Lava in the caves above the old floor turns to water.")
                .define("enabled", true);
        bandThickness = b.comment("Thickness of the unbreakable bands between layers.").defineInRange("bandThickness", d.bandThickness(), 8, 64);
        gateSpacing = b.comment("One gate per band in every square of this many blocks.").defineInRange("gateSpacing", d.gateSpacing(), 320, 4096);
        passageRadius = b.comment("Radius of the twisting gate passages.").defineInRange("passageRadius", d.passageRadius(), 2.5, 12.0);
        landingRadius = b.comment("Radius of the landing chambers at each end of a gate passage.").defineInRange("landingRadius", d.landingRadius(), 8.0, 40.0);
        structureFloorY = b.comment("Strongholds and mineshafts stay above this Y so the End stays reachable.").defineInRange("structureFloorY", -56, -64, 0);
        replaceVanillaCaveBiomes = b.comment("The Upper Caves' entrances and plant caves: Root Vaults a few blocks under forests (frozen at their",
                        "mouths under snowy forests), sinkholes under plains, oasis hollows under deserts and badlands, more lush caves (the Frost Fern",
                        "Grotto under cold ground), and vanilla's deep dark kept out of the pack's caves above the first band (the pack's other",
                        "cave biomes take its place there; the deep dark moved down into the Blue Reaches).")
                .define("replaceVanillaCaveBiomes", true);
        joinDeadEnds = b.comment("Join the blind ends of the pack's caves above the old floor to a cave nearby with a short tunnel, so few",
                        "ways end in a dead end.")
                .define("joinDeadEnds", true);
        upperRegions = b.comment("Carve the Silver Fern Rift and the Ivory Root Cavern into the pack's caves between y -64 and about 0.")
                .define("upperRegions", true);
        removeCreateStriations = b.comment("Remove Create's striped stone layers from the overworld; the layers use Create's stones as patches instead.")
                .define("removeCreateStriations", true);
        layers[0] = new LayerConfig(b, d.layers().get(0), "rift depth", "geode width", true, 64, 512, 64);
        // The Deep Gardens' vent field is built for exactly 192 rows (240 until round 3 of its rework, Taran 2026-11-19:
        // "Reduce the layer height"; the 48 rows came off the bottom of the world, bedrock now at -784): its block tiles
        // hold that many rows and its storeys and depth bands are laid out on them, so a taller layer read past the tiles
        // and crashed generation (0.19.4). The height is fixed; Forge puts any other value, an old file's 240 among
        // them, back to 192.
        layers[1] = new LayerConfig(b, d.layers().get(1), "hall height", "hall width", false, 192, 192, 64);
        // The Wasteland's sheets of tunnels adapt to its height: 64 gives 2, 128 gives 5, 160 gives 6. Its openings into
        // the Galleries fall from 4 over its lava table to 4 under its floor, so a deep table makes them deep pits: 16 at
        // most (10 is what was measured).
        layers[2] = new LayerConfig(b, d.layers().get(2), "cave height", "cave width", false, 64, 160, 16);
        // The Basalt Galleries' halls stand on ledges 43 to 62 blocks over the lava table under vaults up to 108 over it,
        // and the lava cycle floods 40 blocks: under 128 the halls lose their headroom and the band's landing stands in
        // the flood, and a lava table more than 24 over the floor leaves a 128-tall layer too little room above it.
        layers[3] = new LayerConfig(b, d.layers().get(3), "hall height", "hall width", false, 128, 256, 24);
        b.pop();

        b.comment("The Upper Caves grown from noise instead of placed pieces (needs replaceVanillaCaveBiomes). Changes apply to newly",
                "generated chunks. With noiseShapes off, the planned Root Vaults, sinkholes, oasis hollows, Silver Fern Rift and Ivory",
                "Root Cavern of 0.12 come back.").push("upperCaves");
        upperNoise = b.comment("Grow the Upper Caves from noise: the shapes below over the pack's own caves, no planned entrances or set pieces.")
                .define("noiseShapes", true);
        upperWarrens = b.comment("Root warrens: two rolling sheets of looping tunnels 5 to 45 blocks under the ground (the top one just under",
                        "the soil), with root chambers, hanging roots and pools; rooty mouths under forests (snowed in under cold ones).")
                .define("warrens", true);
        upperRifts = b.comment("Rifts: cracks about 10 across and 35 tall, 15 to 55 blocks down, with ledges, a stepped stream and springs, open",
                        "to the sky in places (the Silver Fern Rift's look).")
                .define("rifts", true);
        upperHalls = b.comment("Halls: wide caverns about 60 to 85 blocks down with terraced pools and stalactites (the Ivory Root Cavern's look).")
                .define("halls", true);
        upperSinkholes = b.comment("Sinkholes under plains and sandy hollows under deserts: pits that drop to a lake and open onto the warrens.")
                .define("sinkholes", true);
        upperWormTunnels = b.comment("Keep vanilla's worm tunnels (the cave carver, whose tunnels end blind) above the old floor. Its canyons stay either way.")
                .define("wormTunnels", false);
        upperMerge = b.comment("Where the shapes meet the pack's own caves (Tectonic's) through rock 3 blocks thick or less, that rock falls away and",
                        "the two open into one cave, and pools, roots and stalactites stay only where the pack's caves leave the rock round",
                        "them whole. Off: the two stay a plain overlay, as before 0.18.5 (thin floors, pools on one-block crusts).")
                .define("mergeWithPackCaves", true);
        upperHostRock = b.comment("Carve the caves near the surface into the ground's own rock: soil 5 to 8 blocks deep (rooted dirt at its base under",
                        "trees) and, in the stone above the deepslate, big blobs of limestone, veridium in the tropics, sandstone under deserts,",
                        "terracotta under badlands, or the stone the pack (WWOO) gives the biome over them. Also works with noiseShapes off.")
                .define("hostRock", true);
        upperDenRifts = b.comment("Each Mossback den gate is a deep rift: a ferny crack from the den's ledge on the old floor down into the Blue",
                        "Reaches, with a walkable floor, and the band between the two layers is plain rock you can dig. Off: the den gates' old",
                        "passages through an unbreakable band.")
                .define("denRifts", true);
        b.pop();

        // 0.21.6: the sea is a smooth plane that climbs in stages and falls at once; the calm and the fall have new names
        // (calmMinutes, fallMinutes) for their new defaults, so a file written before keeps none of the old 13.5 and 2.0
        b.comment("The Basalt Galleries' lava cycle: one lava sea, a smooth surface over the whole layer, that rises 40 blocks over the layer's",
                "lava table in climbs with breaks between them and falls back at once, on the layer's own clock, the same for every player,",
                "with warning signs before it rises and before each climb. Anything under its surface is in lava. Read while the server runs.")
                .push("lavaCycle");
        lavaCycleEnabled = b.comment("Run the cycle. Turned off, the lava stays down at its table.")
                .define("enabled", true);
        lavaCycleMinutes = b.comment("Length of one cycle in minutes of game time. A change takes effect when the next cycle begins.")
                .defineInRange("cycleMinutes", 20.0, 1.0, 240.0);
        lavaCalm = b.comment("How the cycle is shared out between its phases, in proportion (the defaults are minutes of the 20-minute cycle):",
                "calm, with the lava at its table.").defineInRange("calmMinutes", 15.25, 0.0, 1000.0);
        lavaWarning = b.comment("Warning: rumbles, shaking and smoke, the lava still at its table.").defineInRange("warning", 1.5, 0.0, 1000.0);
        lavaRise = b.comment("Rise: the lava climbs 40 blocks in its climbs, with the breaks between them.").defineInRange("rise", 2.0, 0.0, 1000.0);
        lavaFull = b.comment("Full: the lava stays at its height while the land resets.").defineInRange("full", 1.0, 0.0, 1000.0);
        lavaDrain = b.comment("Fall: the lava drops back to its table in one smooth fall.").defineInRange("fallMinutes", 0.25, 0.0, 1000.0);
        lavaClimbs = b.comment("How many climbs the rise is made of, each an equal share of the 40 blocks (1: one steady climb).")
                .defineInRange("climbs", 4, 1, 20);
        lavaBreakShare = b.comment("The share of the rise spent in the breaks between its climbs, when the lava holds still (0.5: half of it;",
                "with the defaults, four climbs of 15 seconds and three breaks of 20).").defineInRange("breakShare", 0.5, 0.0, 0.9);
        lavaChecksPerTick = b.comment("Most blocks the drain looks at in one tick, over all players: only for lava blocks a build before 0.21.6 left",
                "in the Galleries (the sea was blocks then), which drain when a player comes near.").defineInRange("checksPerTick", 16384, 256, 1_000_000);
        lavaChangesPerTick = b.comment("Most of those blocks the drain changes in one tick, over all players (each one is re-lit and sent to the",
                "players watching it). Both are halved while the server is falling behind.").defineInRange("changesPerTick", 1500, 16, 100_000);
        lavaRadius = b.comment("Those old lava blocks drain in the chunks this far round every player in or near the Galleries. 0: one chunk past",
                "the server's view distance (every chunk a client can see).").defineInRange("playerRadius", 0, 0, 64);
        b.pop();

        b.comment("Earthquakes in the Basalt Galleries: big quakes every few minutes shake the view and drop rock from the ceilings round",
                "the players there; light tremors between them (and, with climbTremors, one wherever someone builds up). All of them make",
                "blocks players placed fall unless natural rock is at most supportDepth blocks under them (pillars, towers, ladders and",
                "bridges fall; steps and low walls stay); under the lava such blocks melt back into it. Rock that falls, and anything that",
                "toppled, goes back with the terrain reset. Read while the server runs.").push("quakes");
        quakesEnabled = b.comment("Have quakes and tremors.").define("enabled", true);
        quakeTremors = b.comment("Light tremors between the big quakes: a short shiver and a little dust that topple placed blocks; no rock falls",
                "from the ceilings.").define("tremors", true);
        quakeTremorMinSeconds = b.comment("Shortest time between two tremors, in seconds.").defineInRange("tremorMinSeconds", 30.0, 5.0, 600.0);
        quakeTremorMaxSeconds = b.comment("Longest time between two tremors (less than tremorMinSeconds counts as it).")
                .defineInRange("tremorMaxSeconds", 60.0, 5.0, 600.0);
        quakeClimbTremors = b.comment("The ground answers a climb: a block placed more than supportDepth over rock brings a tremor round it 1.5 to 3",
                "seconds later (at most once in 5 seconds for each player). Off by default: Taran chose timed tremors only.")
                .define("climbTremors", false);
        quakeMinMinutes = b.comment("Shortest time between two big quakes, in minutes.").defineInRange("minMinutes", 3.0, 0.25, 240.0);
        quakeMaxMinutes = b.comment("Longest time between two big quakes (less than minMinutes counts as it).").defineInRange("maxMinutes", 7.0, 0.25, 240.0);
        quakeSeconds = b.comment("How long a big quake lasts (each a quarter more or less).").defineInRange("seconds", 10.0, 2.0, 60.0);
        quakeTopple = b.comment("Quakes and tremors make placed blocks without support fall.").define("topple", true);
        quakeSupportDepth = b.comment("A placed block stays only with natural rock at most this many blocks under it, with only placed blocks between.")
                .defineInRange("supportDepth", 2, 1, 8);
        quakeToppleAtOnce = b.comment("Placed blocks without that support fall as soon as a player places them or takes their support away, like",
                "sand, not at the next quake or tremor.").define("toppleAtOnce", false);
        quakeToppleMax = b.comment("Most blocks one quake or tremor topples (the rest fall at the next).").defineInRange("toppleMax", 256, 0, 4096);
        quakeLowerTicks = b.comment("A build a player stands on sinks with them one block every this many ticks, so they are not hurt (0: it drops",
                "at once; 1 to 5 count as 6).").defineInRange("lowerTicks", 6, 0, 40);
        quakeDebrisPerQuake = b.comment("Ceiling blocks that come loose round each player in one big quake at full strength (0: none); a quarter of",
                "them bring 1 to 3 neighbours. Only with the terrain reset on.").defineInRange("debrisPerQuake", 16, 0, 64);
        quakeDebrisRadius = b.comment("How far from a player ceiling rock comes loose, in blocks.").defineInRange("debrisRadius", 24, 4, 64);
        quakeDebrisNear = b.comment("Share of them that land within 8 blocks of the player.").defineInRange("debrisNear", 0.3, 0.0, 1.0);
        quakeDebrisWarnTicks = b.comment("Ticks of grit and a crack before a block drops.").defineInRange("debrisWarnTicks", 20, 0, 100);
        quakeDebrisDamage = b.comment("Most damage a falling block does to whoever it lands on (2 for each block it fell; 8 is 4 hearts). It breaks",
                "on them and is gone.").defineInRange("debrisDamage", 8, 0, 40);
        quakeDebrisMax = b.comment("Most blocks that fall from the ceilings in one quake, over all players.").defineInRange("debrisMax", 128, 0, 1024);
        b.pop();

        b.comment("Eruptions in the Caldera of the Basalt Galleries: a vent smokes and rumbles, then throws up a fountain of lava and lava bombs",
                "that blow craters in the rock. Only Calderas near players erupt; they wake as the lava rises. Craters go back with the",
                "terrain reset; with it off, bombs blow none. Read while the server runs.").push("eruptions");
        eruptionsEnabled = b.comment("Have eruptions.").define("enabled", true);
        eruptionMinMinutes = b.comment("Shortest time between two eruptions of one Caldera while the lava is calm, warns or drains (0: none then).")
                .defineInRange("minMinutes", 3.0, 0.0, 240.0);
        eruptionMaxMinutes = b.comment("Longest time between them then.").defineInRange("maxMinutes", 7.0, 0.0, 240.0);
        eruptionRisingMinMinutes = b.comment("Shortest time between two eruptions while the lava rises and stands full (0: none then).")
                .defineInRange("risingMinMinutes", 1.0, 0.0, 240.0);
        eruptionRisingMaxMinutes = b.comment("Longest time between them then.").defineInRange("risingMaxMinutes", 2.0, 0.0, 240.0);
        eruptionWakeSeconds = b.comment("When the lava starts to rise, each Caldera erupts within this many seconds unless one is due sooner.")
                .defineInRange("wakeSeconds", 40, 0, 600);
        eruptionWarnSeconds = b.comment("Smoke and rumbling before the burst, in seconds.").defineInRange("warnSeconds", 4.0, 0.0, 30.0);
        eruptionBurstSeconds = b.comment("How long the fountain lasts (each a quarter more or less).").defineInRange("burstSeconds", 10.0, 2.0, 60.0);
        eruptionBombs = b.comment("Lava bombs one eruption throws, on average (0: none).").defineInRange("bombs", 10, 0, 64);
        eruptionBombsNear = b.comment("Of them, how many come down near a player in the Caldera (never near one on a stack top, a ledge or the shore).")
                .defineInRange("bombsNear", 2, 0, 8);
        eruptionNearMinBlocks = b.comment("Closest such a bomb lands to the player, in blocks.").defineInRange("nearMinBlocks", 3, 0, 16);
        eruptionNearMaxBlocks = b.comment("Farthest (less than nearMinBlocks counts as it).").defineInRange("nearMaxBlocks", 8, 1, 24);
        eruptionVentNearBias = b.comment("Vents nearer the players erupt more often: a vent this many blocks from the nearest player erupts half as",
                "often as one beside them (0: all alike).").defineInRange("ventNearBias", 48, 0, 256);
        eruptionBlastPower = b.comment("Power of a lava bomb's blast (a creeper's is 3): its reach, its knockback and the size of its crater. 0: bombs",
                "only splash.").defineInRange("blastPower", 3.0, 0.0, 6.0);
        eruptionCraterRock = b.comment("How hard the Galleries' rock is to a bomb (vanilla basalt is 4.2, stone 6): lower blows bigger craters. At",
                "blastPower 3, 0.5 blows about 60 blocks, 3 deep.").defineInRange("craterRock", 0.5, 0.05, 10.0);
        eruptionBombDamage = b.comment("Share of a vanilla blast's damage a bomb does to players and creatures (1: a creeper's at blastPower 3).")
                .defineInRange("bombDamage", 0.6, 0.0, 2.0);
        eruptionBombKnockback = b.comment("Share of the blast's push a bomb gives players and creatures.").defineInRange("bombKnockback", 1.0, 0.0, 1.0);
        eruptionVentBurn = b.comment("Damage every half second, with fire, to whoever stands in an erupting vent's mouth.").defineInRange("ventBurn", 4, 0, 20);
        eruptionEjecta = b.comment("Rocks a bomb's crater throws out.").defineInRange("ejecta", 3, 0, 8);
        eruptionCraterMagma = b.comment("Chance a crater keeps a magma block at its bottom.").defineInRange("craterMagma", 0.5, 0.0, 1.0);
        eruptionRisingScale = b.comment("How much bigger the fountain and the plume are while the lava rises and stands full.")
                .defineInRange("risingScale", 1.5, 1.0, 3.0);
        eruptionRange = b.comment("A Caldera erupts while a player in or near the Galleries is within this many blocks of one of its vents (bombs",
                "only within the server's simulation distance).").defineInRange("range", 192, 32, 320);
        eruptionHeardRange = b.comment("Players within this many blocks of an erupting vent see and hear it.").defineInRange("heardRange", 256, 64, 2048);
        b.pop();

        b.comment("The Basalt Galleries' terrain reset: while the lava stands full, everything changed in the Galleries since the last reset",
                "(by players, quakes and eruptions) is put back as it was generated, round the players there; other chunks catch up when a",
                "player comes near. Only runs with the lava cycle. Read while the server runs.").push("terrainReset");
        resetEnabled = b.comment("Reset the Galleries' terrain once a cycle.").define("enabled", true);
        resetChangesPerTick = b.comment("Most blocks the reset puts back in one tick, over all players (halved while the server is falling behind).")
                .defineInRange("changesPerTick", 2048, 16, 100_000);
        b.pop();

        b.comment("Vanilla's hostile mobs in groups of their own kind, each kind in its own home: zombies and skeletons together in woods and",
                "savannas, spider packs and witch covens in dark oak forests, creepers and slimes in the green Upper Caves (lush caves, Rootvaults,",
                "Silver Fern Rifts, the Frost Fern Grotto), husks in deserts, strays on snowy land, drowned in rivers and the sea; nowhere else",
                "but a lone enderman now and then. Spawners, structures' own spawns (witch huts, monuments, outposts), patrols, phantoms, the",
                "Nether and the End are untouched. Read while the server runs.").push("mobGroups");
        mobGroupsEnabled = b.comment("Bring vanilla's hostile mobs out in groups. Off: vanilla's own spawning, as before.").define("enabled", true);
        mobGroupMin = b.comment("Fewest in a group (a witch coven is always two).").defineInRange("groupMin", 3, 2, 8);
        mobGroupMax = b.comment("Most in a group.").defineInRange("groupMax", 5, 2, 12);
        mobGroupsKept = b.comment("Groups of each home kept round a player (within 96 blocks of it).").defineInRange("groupsKept", 2, 0, 8);
        mobGroupsMost = b.comment("Most groups of each home round a player: the one past those kept comes only now and then.")
                .defineInRange("groupsMost", 3, 1, 10);
        mobEndermanChance = b.comment("Chance each second that a lone enderman comes out near a player with none within 128 blocks (only where",
                "the biome has endermen, and only where it is dark enough).").defineInRange("endermanChance", 0.02, 0.0, 1.0);
        mobWoodsCanopy = b.comment("Share of the ground round a spot in woods or savanna that trees must cover for zombies and skeletons to come",
                "out there (0: anywhere in those biomes).").defineInRange("woodsCanopy", 0.08, 0.0, 1.0);
        b.pop();

        b.comment("Fixes where other mods meet. Changes apply to newly generated chunks.").push("compat");
        terrainSlabs = b.comment("Keep Terrain Slabs' slabs made of the ground they lie on when a later feature changes that ground (as the",
                        "smooth basalt flows of WWOO's stony peaks do), cover them with the snow features lay beside them (WWOO's snow",
                        "patches skip slabs), and lay none inside powder snow. Does nothing without Terrain Slabs.")
                .define("terrainSlabs", true);
        b.pop();
    }

    public TerrainSettings toSettings() {
        List<LayerSettings> ls = new ArrayList<>();
        for (LayerConfig lc : layers) ls.add(lc.toSettings());
        return new TerrainSettings(bandThickness.get(), gateSpacing.get(), passageRadius.get(), landingRadius.get(), List.copyOf(ls));
    }
}
