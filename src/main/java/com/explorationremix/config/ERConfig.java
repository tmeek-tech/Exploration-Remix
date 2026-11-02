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
    public final LayerConfig[] layers = new LayerConfig[4];
    // The Basalt Galleries' lava cycle (world.lava.LavaFlood), read while the server runs.
    public final ForgeConfigSpec.BooleanValue lavaCycleEnabled;
    public final ForgeConfigSpec.DoubleValue lavaCycleMinutes, lavaCalm, lavaWarning, lavaRise, lavaFull, lavaDrain;
    public final ForgeConfigSpec.IntValue lavaChecksPerTick, lavaChangesPerTick, lavaRadius;
    // 0.5.1: the Basalt Galleries' quakes (world.quake.Quakes), eruptions (world.eruption.Eruptions) and the terrain reset
    // (world.lava.TerrainReset), read while the server runs.
    public final ForgeConfigSpec.BooleanValue quakesEnabled;
    public final ForgeConfigSpec.DoubleValue quakeMinMinutes, quakeMaxMinutes, quakeSeconds, quakeDebrisPerSecond;
    public final ForgeConfigSpec.IntValue quakeDebrisRadius;
    public final ForgeConfigSpec.BooleanValue eruptionsEnabled, eruptionFire;
    public final ForgeConfigSpec.DoubleValue eruptionMinMinutes, eruptionMaxMinutes, eruptionActiveMinMinutes, eruptionActiveMaxMinutes, eruptionBombPower;
    public final ForgeConfigSpec.IntValue eruptionBombs, eruptionRange;
    public final ForgeConfigSpec.BooleanValue resetEnabled;
    public final ForgeConfigSpec.IntValue resetChangesPerTick;

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
            // height before 0.5) from moving the world's floor down to -976.
            height = b.comment("Height of the layer in blocks (rounded down to a multiple of 16).")
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
        layers[1] = new LayerConfig(b, d.layers().get(1), "hall and cavern height", "lake and terrace width", false, 64, 512, 64);
        // The Wasteland's sheets of tunnels adapt to its height: 64 gives 2, 128 gives 5, 160 gives 6. Its openings into
        // the Galleries fall from 4 over its lava table to 4 under its floor, so a deep table makes them deep pits: 16 at
        // most (10 is what was measured).
        layers[2] = new LayerConfig(b, d.layers().get(2), "cave height", "cave width", false, 64, 160, 16);
        // The Basalt Galleries' halls stand on ledges 43 to 62 blocks over the lava table under vaults up to 108 over it,
        // and the lava cycle floods 40 blocks: under 128 the halls lose their headroom and the band's landing stands in
        // the flood, and a lava table more than 24 over the floor leaves a 128-tall layer too little room above it.
        layers[3] = new LayerConfig(b, d.layers().get(3), "hall height", "hall width", false, 128, 256, 24);
        b.pop();

        b.comment("The Basalt Galleries' lava cycle: one lava sea that rises 40 blocks over the layer's lava table and falls again on the",
                "layer's own clock, the same for every player, with warning signs before it rises. Read while the server runs.").push("lavaCycle");
        lavaCycleEnabled = b.comment("Run the cycle. Turned off, the lava goes back down to its table round the players in the Galleries and stays there.")
                .define("enabled", true);
        lavaCycleMinutes = b.comment("Length of one cycle in minutes of game time. A change takes effect when the next cycle begins.")
                .defineInRange("cycleMinutes", 20.0, 1.0, 240.0);
        lavaCalm = b.comment("How the cycle is shared out between its phases, in proportion (the defaults are minutes of the 20-minute cycle):",
                "calm, with the lava at its table.").defineInRange("calm", 13.5, 0.0, 1000.0);
        lavaWarning = b.comment("Warning: rumbles, shaking and smoke, the lava still at its table.").defineInRange("warning", 1.5, 0.0, 1000.0);
        lavaRise = b.comment("Rise: the lava climbs 40 blocks at an even pace.").defineInRange("rise", 2.0, 0.0, 1000.0);
        lavaFull = b.comment("Full: the lava stays at its height.").defineInRange("full", 1.0, 0.0, 1000.0);
        lavaDrain = b.comment("Drain: the lava goes back down to its table at an even pace.").defineInRange("drain", 2.0, 0.0, 1000.0);
        lavaChecksPerTick = b.comment("Most blocks the lava looks at in one tick, over all players.").defineInRange("checksPerTick", 16384, 256, 1_000_000);
        lavaChangesPerTick = b.comment("Most blocks the lava changes in one tick, over all players (each one is re-lit and sent to the players",
                "watching it). Both are halved while the server is falling behind.").defineInRange("changesPerTick", 1500, 16, 100_000);
        lavaRadius = b.comment("The lava is kept up to date in the chunks this far round every player in or near the Galleries; other chunks catch",
                "up when a player comes near. 0: one chunk past the server's view distance (every chunk a client can see).").defineInRange("playerRadius", 0, 0, 64);
        b.pop();

        b.comment("Earthquakes in the Basalt Galleries, at random times: the view shakes, dust falls, basalt drops from the ceilings, and",
                "blocks players placed fall unless natural rock is at most 2 blocks under them (pillars, towers and bridges fall; steps",
                "and low walls stay). Felt only by players in the Galleries. Read while the server runs.").push("quakes");
        quakesEnabled = b.comment("Have quakes.").define("enabled", true);
        quakeMinMinutes = b.comment("Shortest time between two quakes, in minutes.").defineInRange("minMinutes", 3.0, 0.25, 240.0);
        quakeMaxMinutes = b.comment("Longest time between two quakes, in minutes (a smaller value than minMinutes counts as minMinutes).")
                .defineInRange("maxMinutes", 7.0, 0.25, 240.0);
        quakeSeconds = b.comment("How long a quake lasts, in seconds (each one a quarter more or less).").defineInRange("seconds", 10.0, 2.0, 60.0);
        quakeDebrisPerSecond = b.comment("Basalt dropping from the ceiling round each player in a quake, blocks per second at full strength (0: none).")
                .defineInRange("debrisPerSecond", 1.5, 0.0, 20.0);
        quakeDebrisRadius = b.comment("How far from a player the ceiling drops its basalt, in blocks.").defineInRange("debrisRadius", 24, 4, 64);
        b.pop();

        b.comment("Eruptions in the Caldera of the Basalt Galleries: a vent smokes and rumbles, then throws up a fountain of lava and lava",
                "bombs that blow holes in the rock. Only Calderas near players erupt. Read while the server runs.").push("eruptions");
        eruptionsEnabled = b.comment("Have eruptions.").define("enabled", true);
        eruptionMinMinutes = b.comment("Shortest time between two eruptions of one Caldera while the lava is calm or draining, in minutes.")
                .defineInRange("minMinutes", 3.0, 0.25, 240.0);
        eruptionMaxMinutes = b.comment("Longest time between them while the lava is calm or draining, in minutes.").defineInRange("maxMinutes", 7.0, 0.25, 240.0);
        eruptionActiveMinMinutes = b.comment("Shortest time between two eruptions while the lava warns, rises or stands full, in minutes.")
                .defineInRange("activeMinMinutes", 1.0, 0.25, 240.0);
        eruptionActiveMaxMinutes = b.comment("Longest time between them while the lava warns, rises or stands full, in minutes.")
                .defineInRange("activeMaxMinutes", 2.5, 0.25, 240.0);
        eruptionBombs = b.comment("Lava bombs one eruption throws, on average (0: none).").defineInRange("bombs", 10, 0, 64);
        eruptionBombPower = b.comment("Strength of a lava bomb's explosion, on average (a creeper's is 3; each bomb a fifth more or less; 0: no explosion).")
                .defineInRange("bombPower", 2.5, 0.0, 6.0);
        eruptionFire = b.comment("Lava bombs set fire round where they burst.").define("fire", true);
        eruptionRange = b.comment("A Caldera erupts while a player in the Galleries is within this many blocks of one of its vents.")
                .defineInRange("range", 192, 32, 320);
        b.pop();

        b.comment("The Basalt Galleries' terrain reset: while the lava stands full, everything changed in the Galleries since the last reset",
                "(by players, quakes and eruptions) is put back as it was generated, round the players there; other chunks catch up when",
                "a player comes near. Only runs with the lava cycle. Read while the server runs.").push("terrainReset");
        resetEnabled = b.comment("Reset the Galleries' terrain once a cycle.").define("enabled", true);
        resetChangesPerTick = b.comment("Most blocks the reset puts back in one tick, over all players (halved while the server is falling behind).")
                .defineInRange("changesPerTick", 2048, 16, 100_000);
        b.pop();
    }

    public TerrainSettings toSettings() {
        List<LayerSettings> ls = new ArrayList<>();
        for (LayerConfig lc : layers) ls.add(lc.toSettings());
        return new TerrainSettings(bandThickness.get(), gateSpacing.get(), passageRadius.get(), landingRadius.get(), List.copyOf(ls));
    }
}
