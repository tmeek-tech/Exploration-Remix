package com.explorationremix.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Every tunable number lives here (Forge common config: config/explorationremix-common.toml).
 * Layer heights, gate spacing, spawn counts, XP values and rune costs are all read from this class.
 */
public final class ERConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ERConfig INSTANCE;

    static {
        Pair<ERConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(ERConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    // ---------------------------------------------------------------- world
    public final ForgeConfigSpec.BooleanValue worldEnabled;
    public final ForgeConfigSpec.IntValue bandThickness;
    public final ForgeConfigSpec.IntValue gateSpacing;
    public final ForgeConfigSpec.DoubleValue gateTubeRadius;
    public final ForgeConfigSpec.DoubleValue landingRadius;
    public final ForgeConfigSpec.IntValue refugeRadius;
    public final ForgeConfigSpec.IntValue routeHalfWidth;
    public final ForgeConfigSpec.IntValue huntingRuinsPerCell;
    public final ForgeConfigSpec.IntValue routeRuinsPerCell;
    public final ForgeConfigSpec.IntValue structureFloorY;
    public final ForgeConfigSpec.BooleanValue replaceVanillaCaveBiomes;
    public final ForgeConfigSpec.BooleanValue removeCreateStriations;
    public final LayerConfig[] layers = new LayerConfig[4];

    // ---------------------------------------------------------------- runs
    public final ForgeConfigSpec.IntValue startingSlots;
    public final ForgeConfigSpec.IntValue startingSentences;
    public final ForgeConfigSpec.IntValue surfaceBedMinY;
    public final ForgeConfigSpec.BooleanValue elytraBelowBand;
    public final ForgeConfigSpec.BooleanValue keepInventory;
    public final ForgeConfigSpec.BooleanValue returnHomeFromBedrock;
    public final ForgeConfigSpec.IntValue returnHomeDelayTicks;
    public final ForgeConfigSpec.BooleanValue blockPortalsBelowBand;
    public final ForgeConfigSpec.BooleanValue blockDimensionTravelDuringRun;
    public final ForgeConfigSpec.BooleanValue endRunOnLogout;
    public final ForgeConfigSpec.IntValue xpPerKillBase;
    public final ForgeConfigSpec.DoubleValue xpPerLayerMultiplier;
    public final ForgeConfigSpec.IntValue xpPerGate;
    public final ForgeConfigSpec.IntValue xpSurfaceClusterKill;
    public final ForgeConfigSpec.IntValue runeBlockBaseCost;
    public final ForgeConfigSpec.IntValue runeBlockCostPerLayer;

    // ---------------------------------------------------------------- enchanting removal
    public final ForgeConfigSpec.BooleanValue nullifyEnchantments;
    public final ForgeConfigSpec.BooleanValue blockXpOrbs;
    public final ForgeConfigSpec.BooleanValue disableEnchantingTable;
    public final ForgeConfigSpec.BooleanValue freeAnvils;
    public final ForgeConfigSpec.ConfigValue<List<? extends String>> allowedEnchantments;
    public final ForgeConfigSpec.BooleanValue stripLootEnchantments;
    public final ForgeConfigSpec.BooleanValue stripTradeEnchantments;

    // ---------------------------------------------------------------- combat
    public final ForgeConfigSpec.DoubleValue poiseBase;
    public final ForgeConfigSpec.DoubleValue poisePerHealth;
    public final ForgeConfigSpec.IntValue staggerTicks;
    public final ForgeConfigSpec.DoubleValue staggerDamageMultiplier;
    public final ForgeConfigSpec.IntValue hunterReelTicks;
    public final ForgeConfigSpec.DoubleValue momentumDamagePerSpeed;
    public final ForgeConfigSpec.DoubleValue momentumPoisePerSpeed;
    public final ForgeConfigSpec.DoubleValue wallSlamDamagePerSpeed;
    public final ForgeConfigSpec.DoubleValue wallSlamMinSpeed;
    public final ForgeConfigSpec.BooleanValue screenShake;
    public final ForgeConfigSpec.IntValue hitStopTicks;
    public final ForgeConfigSpec.DoubleValue emberSpreadRange;

    // ---------------------------------------------------------------- creatures
    public final ForgeConfigSpec.IntValue huntersPerPlayerPerLayer;
    public final ForgeConfigSpec.IntValue hunterRespawnSeconds;
    public final ForgeConfigSpec.IntValue hunterGiveUpSeconds;
    public final ForgeConfigSpec.IntValue hunterRefugeGiveUpSeconds;
    public final ForgeConfigSpec.DoubleValue hunterHearingRange;
    public final ForgeConfigSpec.DoubleValue hunterSightRange;
    public final ForgeConfigSpec.IntValue flocksPerChunk;
    public final ForgeConfigSpec.IntValue flockSize;
    public final ForgeConfigSpec.IntValue nestActiveEnemies;
    public final ForgeConfigSpec.IntValue nestEggClusters;
    public final ForgeConfigSpec.BooleanValue disableNaturalHostiles;
    public final ForgeConfigSpec.BooleanValue disableSpawners;
    public final ForgeConfigSpec.IntValue clusterIntervalMinSeconds;
    public final ForgeConfigSpec.IntValue clusterIntervalMaxSeconds;
    public final ForgeConfigSpec.IntValue clusterSizeMin;
    public final ForgeConfigSpec.IntValue clusterSizeMax;
    public final ForgeConfigSpec.IntValue creeperCaveIntervalSeconds;

    // ---------------------------------------------------------------- atmosphere
    public final ForgeConfigSpec.BooleanValue layerFog;
    public final ForgeConfigSpec.DoubleValue fogStrength;

    private ERConfig(ForgeConfigSpec.Builder b) {
        b.comment("The deeper world. Y values are absolute block heights.").push("world");
        worldEnabled = b.comment("Master switch for the deep layers. New worlds only.").define("enabled", true);
        bandThickness = b.comment("Thickness of each unbreakable band. 16 beats chorus fruit's 8-block teleport.")
                .defineInRange("bandThickness", 16, 16, 64);
        gateSpacing = b.comment("One gate per gateSpacing x gateSpacing block cell in each band.")
                .defineInRange("gateSpacing", 600, 128, 4096);
        gateTubeRadius = b.comment("Radius of the twisting passage through a band, in blocks.")
                .defineInRange("gateTubeRadius", 5.5, 2.0, 16.0);
        landingRadius = b.comment("Radius of the cave chamber carved at each end of a gate.")
                .defineInRange("landingRadius", 15.0, 6.0, 40.0);
        refugeRadius = b.comment("Radius of a refuge chamber on the route.").defineInRange("refugeRadius", 22, 8, 64);
        routeHalfWidth = b.comment("How far from the route centreline the route biome reaches.")
                .defineInRange("routeHalfWidth", 28, 8, 128);
        huntingRuinsPerCell = b.comment("Ruins placed deep in the hunting grounds, per gate cell.")
                .defineInRange("huntingRuinsPerCell", 3, 0, 12);
        routeRuinsPerCell = b.comment("Ruins placed along the route, per gate cell (refuges always get one).")
                .defineInRange("routeRuinsPerCell", 2, 0, 12);
        structureFloorY = b.comment("Strongholds and mineshafts are kept above this Y so the End stays reachable.")
                .defineInRange("structureFloorY", -56, -2032, 320);
        replaceVanillaCaveBiomes = b.comment("Replace vanilla dripstone caves and deep dark above the first band with Rootvaults (both moved into the layers).")
                .define("replaceVanillaCaveBiomes", true);
        removeCreateStriations = b.comment("Remove Create's striated stone layers from the overworld; its stones appear as accents in the deep layers instead.")
                .define("removeCreateStriations", true);
        b.pop();

        String[] ids = {"lantern_deeps", "blue_reaches", "ember_gardens", "basalt_galleries"};
        int[] heights = {128, 160, 192, 224};
        String[] fluids = {"water", "water", "water", "lava"};
        boolean[] pockets = {false, false, true, false};
        String[] styles = {"halls", "rift", "basin", "galleries"};
        String[] refuge = {"dripstone_halls", "asurine_geodes", "banded_iron_halls", "obsidian_tubes"};
        String[] route = {"ivory_root_cavern", "deep_blue_rift", "rust_springs", "basalt_galleries"};
        String[][] hunting = {{"lantern_lake", "amber_hive"}, {"silver_fern_rift", "minecraft:deep_dark"},
                {"ember_gardens"}, {"smolder_groves"}};
        String[] stones = {"explorationremix:lantern_stone", "explorationremix:reach_stone",
                "explorationremix:ember_stone", "explorationremix:gallery_stone"};
        for (int i = 0; i < 4; i++) {
            layers[i] = new LayerConfig(b, ids[i], heights[i], fluids[i], pockets[i], styles[i],
                    refuge[i], route[i], List.of(hunting[i]), stones[i]);
        }

        b.comment("Runs: start, end, slots and XP.").push("runs");
        startingSentences = b.defineInRange("startingSentences", 2, 1, 6);
        startingSlots = b.comment("Slots per sentence at the start of a run; each new layer adds one.")
                .defineInRange("startingSlots", 3, 1, 12);
        surfaceBedMinY = b.comment("Beds and spawn points only work at or above this Y, or where the sky is visible.")
                .defineInRange("surfaceBedMinY", 40, -2032, 320);
        elytraBelowBand = b.comment("Allow elytra flight below the first band.").define("elytraBelowBand", false);
        keepInventory = b.comment("Players keep their inventory when they die on a run. Runes and run XP are what they lose.")
                .define("keepInventory", true);
        returnHomeFromBedrock = b.comment("Reaching the bedrock landing ends the run and sends the player back to their spawn point.")
                .define("returnHomeFromBedrock", true);
        returnHomeDelayTicks = b.comment("Ticks between touching the bedrock landing and the trip home.")
                .defineInRange("returnHomeDelayTicks", 100, 0, 12000);
        blockPortalsBelowBand = b.comment("Nether portals cannot be lit below the first band, and portals never place the player below it.")
                .define("blockPortalsBelowBand", true);
        blockDimensionTravelDuringRun = b.comment("Changing dimension during a run ends the run (no skipping layers through the Nether).")
                .define("blockDimensionTravelDuringRun", true);
        endRunOnLogout = b.comment("Logging out ends the run (so a relog cannot reset hunters or dodge a fight). Off keeps the run across sessions.")
                .define("endRunOnLogout", false);
        xpPerKillBase = b.comment("Run XP for a kill in the first layer.").defineInRange("xpPerKillBase", 6, 0, 10000);
        xpPerLayerMultiplier = b.comment("Each deeper layer multiplies kill XP by this.")
                .defineInRange("xpPerLayerMultiplier", 1.6, 1.0, 10.0);
        xpPerGate = b.comment("Run XP for breaking every egg cluster of a nest (times the layer index).")
                .defineInRange("xpPerGate", 60, 0, 100000);
        xpSurfaceClusterKill = b.comment("Run XP for a kill above the first band (surface clusters). Keep it small.")
                .defineInRange("xpSurfaceClusterKill", 0, 0, 1000);
        runeBlockBaseCost = b.comment("XP points to wake a rune block in the first layer.")
                .defineInRange("runeBlockBaseCost", 30, 0, 100000);
        runeBlockCostPerLayer = b.comment("Added to the wake cost for each layer below the first.")
                .defineInRange("runeBlockCostPerLayer", 30, 0, 100000);
        b.pop();

        b.comment("Vanilla enchanting removal and loophole closers.").push("enchanting");
        nullifyEnchantments = b.comment("If an enchantment slips through from a command or another mod, it does nothing.")
                .define("nullifyEnchantments", true);
        blockXpOrbs = b.comment("Block every vanilla XP orb and XP change that this mod did not award.")
                .define("blockXpOrbs", true);
        disableEnchantingTable = b.comment("Existing enchanting tables do nothing and the recipe is removed.")
                .define("disableEnchantingTable", true);
        freeAnvils = b.comment("Anvils repair with materials and rename at no XP cost.").define("freeAnvils", true);
        allowedEnchantments = b.comment("Enchantment ids that keep working (for example \"minecraft:mending\"). Everything else is nullified.")
                .defineListAllowEmpty("allowedEnchantments", List.of(), o -> o instanceof String);
        stripLootEnchantments = b.comment("Remove enchantments from loot chests, fishing, and mob drops.").define("stripLootEnchantments", true);
        stripTradeEnchantments = b.comment("Remove enchanted books and enchanted gear from villager trades.").define("stripTradeEnchantments", true);
        b.pop();

        b.comment("The mod's own combat layer. No dodge, no parry, no stamina bar.").push("combat");
        poiseBase = b.comment("Base poise of every mob.").defineInRange("poiseBase", 10.0, 0.0, 10000.0);
        poisePerHealth = b.comment("Extra poise per point of max health.").defineInRange("poisePerHealth", 0.5, 0.0, 100.0);
        staggerTicks = b.comment("How long a staggered enemy reels.").defineInRange("staggerTicks", 40, 1, 400);
        staggerDamageMultiplier = b.comment("Damage multiplier against a staggered enemy.")
                .defineInRange("staggerDamageMultiplier", 1.75, 1.0, 10.0);
        hunterReelTicks = b.comment("Hunters only reel for this long and take no extra damage.")
                .defineInRange("hunterReelTicks", 12, 1, 200);
        momentumDamagePerSpeed = b.comment("Bonus damage per block/tick of attacker speed on a momentum hit.")
                .defineInRange("momentumDamagePerSpeed", 6.0, 0.0, 100.0);
        momentumPoisePerSpeed = b.defineInRange("momentumPoisePerSpeed", 10.0, 0.0, 100.0);
        wallSlamDamagePerSpeed = b.comment("Damage per block/tick of speed when a shoved enemy hits a wall.")
                .defineInRange("wallSlamDamagePerSpeed", 5.0, 0.0, 100.0);
        wallSlamMinSpeed = b.defineInRange("wallSlamMinSpeed", 0.45, 0.0, 10.0);
        screenShake = b.comment("Client: small camera kick on heavy hits.").define("screenShake", true);
        hitStopTicks = b.comment("Client: ticks of hit-stop on heavy hits and finishers (0 disables).")
                .defineInRange("hitStopTicks", 3, 0, 10);
        emberSpreadRange = b.comment("Ember spreads to creatures within this range (never to blocks).")
                .defineInRange("emberSpreadRange", 1.6, 0.0, 8.0);
        b.pop();

        b.comment("Hunters, flocks, nests and surface clusters.").push("creatures");
        huntersPerPlayerPerLayer = b.defineInRange("huntersPerPlayerPerLayer", 2, 0, 8);
        hunterRespawnSeconds = b.comment("A dead hunter is replaced after this long.").defineInRange("hunterRespawnSeconds", 240, 1, 36000);
        hunterGiveUpSeconds = b.comment("Seconds a hunter searches after losing you.").defineInRange("hunterGiveUpSeconds", 25, 1, 600);
        hunterRefugeGiveUpSeconds = b.comment("Seconds a hunter keeps chasing inside a refuge.").defineInRange("hunterRefugeGiveUpSeconds", 8, 1, 600);
        hunterHearingRange = b.comment("How far a loud sound carries through cave air to a hunter.").defineInRange("hunterHearingRange", 48.0, 1.0, 256.0);
        hunterSightRange = b.defineInRange("hunterSightRange", 32.0, 1.0, 256.0);
        flocksPerChunk = b.comment("Bat flocks per chunk of cave ceiling (x100, so 12 = 12%).").defineInRange("flocksPerChunk", 12, 0, 100);
        flockSize = b.comment("Animals per flock.").defineInRange("flockSize", 24, 1, 200);
        nestActiveEnemies = b.comment("Nest enemies awake at once.").defineInRange("nestActiveEnemies", 5, 1, 40);
        nestEggClusters = b.comment("Egg clusters per nest.").defineInRange("nestEggClusters", 6, 1, 40);
        disableNaturalHostiles = b.comment("Turn off vanilla natural hostile spawning in the overworld.").define("disableNaturalHostiles", true);
        disableSpawners = b.comment("Mob spawners spawn nothing hostile in the overworld (no mob farms).").define("disableSpawners", true);
        clusterIntervalMinSeconds = b.defineInRange("clusterIntervalMinSeconds", 240, 1, 86400);
        clusterIntervalMaxSeconds = b.defineInRange("clusterIntervalMaxSeconds", 900, 1, 86400);
        clusterSizeMin = b.defineInRange("clusterSizeMin", 5, 1, 64);
        clusterSizeMax = b.defineInRange("clusterSizeMax", 11, 1, 64);
        creeperCaveIntervalSeconds = b.comment("Creepers appear in lush caves and Rootvaults about this often per player.")
                .defineInRange("creeperCaveIntervalSeconds", 300, 1, 86400);
        b.pop();

        b.push("atmosphere");
        layerFog = b.comment("Client: thicker fog per layer.").define("layerFog", true);
        fogStrength = b.defineInRange("fogStrength", 1.0, 0.0, 4.0);
        b.pop();
    }

    /** Config for one of the four deep layers. */
    public static final class LayerConfig {
        public final String id;
        public final ForgeConfigSpec.IntValue height;
        public final ForgeConfigSpec.ConfigValue<String> fluid;
        public final ForgeConfigSpec.BooleanValue lavaPockets;
        public final ForgeConfigSpec.ConfigValue<String> style;
        public final ForgeConfigSpec.ConfigValue<String> refugeBiome;
        public final ForgeConfigSpec.ConfigValue<String> routeBiome;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> huntingBiomes;
        public final ForgeConfigSpec.ConfigValue<String> baseStone;
        public final ForgeConfigSpec.ConfigValue<String> caveFunction;
        public final ForgeConfigSpec.DoubleValue cavernScale;
        public final ForgeConfigSpec.IntValue fluidLevel;

        LayerConfig(ForgeConfigSpec.Builder b, String id, int height, String fluid, boolean pockets, String style,
                    String refuge, String route, List<String> hunting, String stone) {
            this.id = id;
            b.comment("Layer " + id).push("world." + id);
            this.height = b.comment("Height of the layer in blocks (multiple of 16).").defineInRange("height", height, 32, 1024);
            this.fluid = b.comment("Aquifer fluid: water, lava or none.").define("fluid", fluid);
            this.lavaPockets = b.comment("Allow vanilla-style lava pockets in local aquifers.").define("lavaPockets", pockets);
            this.style = b.comment("Route style: halls, rift, basin or galleries.").define("style", style);
            this.refugeBiome = b.define("refugeBiome", refuge);
            this.routeBiome = b.define("routeBiome", route);
            this.huntingBiomes = b.defineList("huntingBiomes", hunting, o -> o instanceof String);
            this.baseStone = b.comment("Block id of the layer's base rock.").define("baseStone", stone);
            this.caveFunction = b.comment("Density function id for the layer's noise caves.")
                    .define("caveFunction", "explorationremix:caves/" + id);
            this.cavernScale = b.comment("Multiplier on cavern size (1 = as designed).").defineInRange("cavernScale", 1.0, 0.25, 4.0);
            this.fluidLevel = b.comment("Height of the layer's water (or lava) table above the layer's bottom, in blocks.")
                    .defineInRange("fluidLevel", 14, 0, 1024);
            b.pop(2);
        }
    }
}
