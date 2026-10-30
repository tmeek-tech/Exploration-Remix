package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.BandStoneBlock;
import com.explorationremix.block.CavePlantBlock;
import com.explorationremix.block.HangingPlantBlock;
import com.explorationremix.block.HangingVinesBlock;
import com.explorationremix.block.HangingVinesPlantBlock;
import com.explorationremix.block.PoolPlantBlock;
import com.explorationremix.block.ShelfFungusBlock;
import com.explorationremix.block.SubmergedPlantBlock;
import com.explorationremix.block.TallCavePlantBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GlowLichenBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * Every block the mod adds. The plants and crystals are decoration for now: they can be placed, broken and collected,
 * and give light where the spec says so. Hanging vines climb like vanilla's weeping vines, and shelf fungi can be
 * stood on.
 */
public final class ERBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ExplorationRemix.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ExplorationRemix.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ExplorationRemix.MOD_ID);

    private ERBlocks() {}

    /** The unbreakable rock of the bands between layers. */
    public static final RegistryObject<Block> BAND_STONE = block("band_stone", () -> new BandStoneBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(-1.0F, 3600000.0F).noLootTable()
                    .sound(SoundType.DEEPSLATE).pushReaction(PushReaction.BLOCK).isValidSpawn((s, l, p, e) -> false)));

    // ------------------------------------------------------------------------------------------- Rootvaults
    public static final RegistryObject<Block> WHITE_GLOW_FLOWER = block("white_glow_flower",
            () -> new CavePlantBlock(flower(MapColor.SNOW, 6), Block.box(3, 0, 3, 13, 13, 13)));

    // ------------------------------------------------------------------------------------------- Ivory Root Cavern
    public static final RegistryObject<Block> LANTERN_FUNGUS = block("lantern_fungus",
            () -> new CavePlantBlock(plant(MapColor.SAND, SoundType.FUNGUS, 13), Block.box(2, 0, 2, 14, 14, 14)));
    public static final RegistryObject<Block> LANTERN_ROOTS = block("lantern_roots",
            () -> new HangingPlantBlock(plant(MapColor.COLOR_YELLOW, SoundType.HANGING_ROOTS, 8), Block.box(2, 0, 2, 14, 16, 14)));

    // ------------------------------------------------------------------------------------------- Lantern Lake
    public static final RegistryObject<Block> WISTERIA = BLOCKS.register("wisteria",
            () -> new HangingVinesBlock(plant(MapColor.COLOR_PURPLE, SoundType.CAVE_VINES, 0), ERBlocks.WISTERIA_PLANT_REF));
    public static final RegistryObject<Block> WISTERIA_PLANT = BLOCKS.register("wisteria_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.COLOR_PURPLE, SoundType.CAVE_VINES, 0), ERBlocks.WISTERIA_REF));
    public static final RegistryObject<Block> LANTERN_POD = block("lantern_pod",
            () -> new HangingPlantBlock(plant(MapColor.COLOR_ORANGE, SoundType.CAVE_VINES, 14), Block.box(3, 2, 3, 13, 16, 13)));
    public static final RegistryObject<Block> LAVENDER_FLOWER = block("lavender_flower",
            () -> new CavePlantBlock(flower(MapColor.COLOR_PURPLE, 0), Block.box(3, 0, 3, 13, 14, 13)));
    public static final RegistryObject<Block> GOLD_FLOWER = block("gold_flower",
            () -> new CavePlantBlock(flower(MapColor.GOLD, 9), Block.box(3, 0, 3, 13, 13, 13)));

    // ------------------------------------------------------------------------------------------- Blue Reaches
    public static final RegistryObject<Block> ASURINE_CRYSTAL = block("asurine_crystal", () -> new AmethystClusterBlock(7, 3,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).forceSolidOn().noOcclusion().sound(SoundType.AMETHYST_CLUSTER)
                    .strength(1.5F).lightLevel(s -> 10).pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> BLUE_GLOW_MUSHROOM = block("blue_glow_mushroom",
            () -> new CavePlantBlock(plant(MapColor.COLOR_LIGHT_BLUE, SoundType.FUNGUS, 10), Block.box(3, 0, 3, 13, 12, 13)));
    /** Bright at the tip and dim along the strand, so the light pools under the ends instead of washing the rift evenly. */
    public static final RegistryObject<Block> BLUE_GLOW_VINES = BLOCKS.register("blue_glow_vines",
            () -> new HangingVinesBlock(plant(MapColor.COLOR_BLUE, SoundType.CAVE_VINES, 10), ERBlocks.BLUE_GLOW_VINES_PLANT_REF));
    public static final RegistryObject<Block> BLUE_GLOW_VINES_PLANT = BLOCKS.register("blue_glow_vines_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.COLOR_BLUE, SoundType.CAVE_VINES, 6), ERBlocks.BLUE_GLOW_VINES_REF));

    // ------------------------------------------------------------------------------------------- Silver Fern Rift
    public static final RegistryObject<Block> SILVER_FERN = block("silver_fern",
            () -> new CavePlantBlock(plant(MapColor.METAL, SoundType.GRASS, 0).replaceable().offsetType(BlockBehaviour.OffsetType.XYZ),
                    Block.box(2, 0, 2, 14, 13, 14)));

    // ------------------------------------------------------------------------------------------- Ember Gardens
    public static final RegistryObject<Block> ORANGE_SHELF_FUNGUS = block("orange_shelf_fungus", () -> new ShelfFungusBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(0.4F).noOcclusion().sound(SoundType.FUNGUS)
                    .lightLevel(s -> 6).pushReaction(PushReaction.DESTROY).isValidSpawn((s, l, p, e) -> false)));
    public static final RegistryObject<Block> LAVENDER_CORAL = block("lavender_coral",
            () -> new PoolPlantBlock(plant(MapColor.COLOR_PURPLE, SoundType.WET_GRASS, 0), Block.box(2, 0, 2, 14, 15, 14)));
    public static final RegistryObject<Block> PALE_CORAL_FAN = block("pale_coral_fan",
            () -> new PoolPlantBlock(plant(MapColor.SAND, SoundType.WET_GRASS, 0), Block.box(2, 0, 2, 14, 4, 14)));
    public static final RegistryObject<Block> EMBER_VINES = BLOCKS.register("ember_vines",
            () -> new HangingVinesBlock(plant(MapColor.COLOR_ORANGE, SoundType.WEEPING_VINES, 10), ERBlocks.EMBER_VINES_PLANT_REF));
    public static final RegistryObject<Block> EMBER_VINES_PLANT = BLOCKS.register("ember_vines_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.COLOR_ORANGE, SoundType.WEEPING_VINES, 10), ERBlocks.EMBER_VINES_REF));

    // =========================================================================================== vegetation rework, part A
    // ------------------------------------------------------------------------------------------- Rootvaults
    public static final RegistryObject<Block> AMBER_BULB_VINE = BLOCKS.register("amber_bulb_vine",
            () -> new HangingVinesBlock(plant(MapColor.COLOR_ORANGE, SoundType.CAVE_VINES, 12), ERBlocks.AMBER_BULB_VINE_PLANT_REF));
    public static final RegistryObject<Block> AMBER_BULB_VINE_PLANT = BLOCKS.register("amber_bulb_vine_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.COLOR_ORANGE, SoundType.CAVE_VINES, 6), ERBlocks.AMBER_BULB_VINE_REF));
    public static final RegistryObject<Block> WEEPING_IVY = BLOCKS.register("weeping_ivy",
            () -> new HangingVinesBlock(plant(MapColor.PLANT, SoundType.VINE, 0), ERBlocks.WEEPING_IVY_PLANT_REF));
    public static final RegistryObject<Block> WEEPING_IVY_PLANT = BLOCKS.register("weeping_ivy_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.PLANT, SoundType.VINE, 0), ERBlocks.WEEPING_IVY_REF));
    /** Ivy over the walls and ceilings of the teal grottos and ravines: a sheet like glow lichen, giving no light. */
    public static final RegistryObject<Block> GROTTO_IVY = block("grotto_ivy",
            () -> new GlowLichenBlock(lichen(MapColor.COLOR_GREEN, SoundType.VINE, 0)));
    public static final RegistryObject<Block> STARMOSS = block("starmoss",
            () -> new CavePlantBlock(plant(MapColor.COLOR_GREEN, SoundType.MOSS_CARPET, 4).replaceable(), Block.box(0, 0, 0, 16, 1, 16)));

    // ------------------------------------------------------------------------------------------- Silver Fern Rift
    public static final RegistryObject<Block> SILVER_FROND = BLOCKS.register("silver_frond",
            () -> new HangingVinesBlock(plant(MapColor.METAL, SoundType.GRASS, 0), ERBlocks.SILVER_FROND_PLANT_REF));
    public static final RegistryObject<Block> SILVER_FROND_PLANT = BLOCKS.register("silver_frond_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.METAL, SoundType.GRASS, 0), ERBlocks.SILVER_FROND_REF));
    public static final RegistryObject<Block> TALL_SILVER_FERN = tallBlock("tall_silver_fern",
            () -> new TallCavePlantBlock(plant(MapColor.METAL, SoundType.GRASS, 0).replaceable().offsetType(BlockBehaviour.OffsetType.XZ),
                    Block.box(2, 0, 2, 14, 16, 14), Block.box(2, 0, 2, 14, 14, 14)));
    /** Fronds growing sideways out of cracks in the walls, drawn like vanilla's coral wall fans. */
    public static final RegistryObject<Block> SILVER_WALL_FERN = block("silver_wall_fern",
            () -> new ShelfFungusBlock(plant(MapColor.METAL, SoundType.GRASS, 0), Block.box(0, 4, 5, 16, 12, 16)));

    // ------------------------------------------------------------------------------------------- Ivory Root Cavern
    /** A pale bracket with glowing gold gills underneath; firm enough to stand on, like the orange shelf fungus. */
    public static final RegistryObject<Block> IVORY_BRACKET = block("ivory_bracket", () -> new ShelfFungusBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(0.4F).noOcclusion().sound(SoundType.FUNGUS)
                    .lightLevel(s -> 11).pushReaction(PushReaction.DESTROY).isValidSpawn((s, l, p, e) -> false)));
    public static final RegistryObject<Block> GOLD_THREADS = BLOCKS.register("gold_threads",
            () -> new HangingVinesBlock(plant(MapColor.GOLD, SoundType.HANGING_ROOTS, 9), ERBlocks.GOLD_THREADS_PLANT_REF));
    public static final RegistryObject<Block> GOLD_THREADS_PLANT = BLOCKS.register("gold_threads_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.GOLD, SoundType.HANGING_ROOTS, 5), ERBlocks.GOLD_THREADS_REF));
    public static final RegistryObject<Block> SAGE_BUSH = block("sage_bush",
            () -> new CavePlantBlock(plant(MapColor.PLANT, SoundType.AZALEA, 0).offsetType(BlockBehaviour.OffsetType.XZ), Block.box(1, 0, 1, 15, 13, 15)));

    // ------------------------------------------------------------------------------------------- Blue Reaches
    public static final RegistryObject<Block> GLOWBELL = block("glowbell",
            () -> new CavePlantBlock(plant(MapColor.COLOR_LIGHT_BLUE, SoundType.SPORE_BLOSSOM, 9).offsetType(BlockBehaviour.OffsetType.XZ),
                    Block.box(3, 0, 3, 13, 14, 13)));
    public static final RegistryObject<Block> PAGODA_FUNGUS = tallBlock("pagoda_fungus",
            () -> new TallCavePlantBlock(plant(MapColor.COLOR_BLUE, SoundType.FUNGUS, 7), Block.box(3, 0, 3, 13, 16, 13), Block.box(2, 0, 2, 14, 12, 14)));
    /** Blue specks over the rift's walls: light only where it covers a face, like glow lichen. */
    public static final RegistryObject<Block> BLUE_LICHEN = block("blue_lichen",
            () -> new GlowLichenBlock(lichen(MapColor.COLOR_BLUE, SoundType.GLOW_LICHEN, 4)));
    public static final RegistryObject<Block> ASURINE_BUD = block("asurine_bud", () -> new AmethystClusterBlock(3, 4,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).forceSolidOn().noOcclusion().sound(SoundType.SMALL_AMETHYST_BUD)
                    .strength(1.5F).lightLevel(s -> 5).pushReaction(PushReaction.DESTROY)));
    /** A crust of small asurine facets over the asurine: light only where it covers a face, like glow lichen. */
    public static final RegistryObject<Block> ASURINE_CRUST = block("asurine_crust",
            () -> new GlowLichenBlock(lichen(MapColor.COLOR_LIGHT_BLUE, SoundType.AMETHYST_CLUSTER, 3)));
    /** The deep dark's only new plant: dark strands whose cold specks glow without giving light, so the dark stays dark. */
    public static final RegistryObject<Block> DUSK_VINES = BLOCKS.register("dusk_vines",
            () -> new HangingVinesBlock(plant(MapColor.COLOR_BLACK, SoundType.SCULK_VEIN, 0), ERBlocks.DUSK_VINES_PLANT_REF));
    public static final RegistryObject<Block> DUSK_VINES_PLANT = BLOCKS.register("dusk_vines_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.COLOR_BLACK, SoundType.SCULK_VEIN, 0), ERBlocks.DUSK_VINES_REF));

    // =========================================================================================== vegetation rework, part B
    // No green in the Deep Gardens: lilac, cream, ochre, amber and rust.
    // ------------------------------------------------------------------------------------------- Lantern Lake
    /** Cream wisteria hanging beside the lilac; lantern pods hang from it too. */
    public static final RegistryObject<Block> PALE_WISTERIA = BLOCKS.register("pale_wisteria",
            () -> new HangingVinesBlock(plant(MapColor.SAND, SoundType.CAVE_VINES, 0), ERBlocks.PALE_WISTERIA_PLANT_REF));
    public static final RegistryObject<Block> PALE_WISTERIA_PLANT = BLOCKS.register("pale_wisteria_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.SAND, SoundType.CAVE_VINES, 0), ERBlocks.PALE_WISTERIA_REF));
    /** Petals fallen from the wisteria, lying on the islands. */
    public static final RegistryObject<Block> WISTERIA_PETALS = block("wisteria_petals",
            () -> new CavePlantBlock(plant(MapColor.COLOR_PURPLE, SoundType.PINK_PETALS, 0).replaceable(), Block.box(0, 0, 0, 16, 1, 16)));
    /** The lake bed's fronds, in place of seagrass. */
    public static final RegistryObject<Block> LILAC_WATERWEED = block("lilac_waterweed",
            () -> new SubmergedPlantBlock(plant(MapColor.COLOR_PURPLE, SoundType.WET_GRASS, 0).replaceable(), Block.box(2, 0, 2, 14, 12, 14)));
    /** Glowing pods on the lake bed, in place of sea pickles. */
    public static final RegistryObject<Block> LANTERN_WEED = block("lantern_weed",
            () -> new SubmergedPlantBlock(plant(MapColor.COLOR_ORANGE, SoundType.WET_GRASS, 8), Block.box(3, 0, 3, 13, 13, 13)));
    /** Glassy beads on the beds of the warm pools and the lake shallows; they glow turquoise through the water. */
    public static final RegistryObject<Block> POOL_PEARLS = block("pool_pearls",
            () -> new SubmergedPlantBlock(plant(MapColor.COLOR_CYAN, SoundType.WET_GRASS, 15).replaceable(), Block.box(0, 0, 0, 16, 1, 16)));
    public static final RegistryObject<Block> PALE_CORAL = block("pale_coral",
            () -> new PoolPlantBlock(plant(MapColor.SAND, SoundType.WET_GRASS, 0), Block.box(2, 0, 2, 14, 14, 14)));

    // ------------------------------------------------------------------------------------------- Ember Gardens
    public static final RegistryObject<Block> OCHRE_GRASS = block("ochre_grass",
            () -> new CavePlantBlock(plant(MapColor.COLOR_ORANGE, SoundType.GRASS, 0).replaceable().offsetType(BlockBehaviour.OffsetType.XYZ)
                    .ignitedByLava(), Block.box(2, 0, 2, 14, 13, 14)));
    public static final RegistryObject<Block> LILAC_PLUME = tallBlock("lilac_plume",
            () -> new TallCavePlantBlock(plant(MapColor.COLOR_PURPLE, SoundType.GRASS, 0).offsetType(BlockBehaviour.OffsetType.XZ).ignitedByLava(),
                    Block.box(1, 0, 1, 15, 16, 15), Block.box(1, 0, 1, 15, 14, 15)));
    /** The hot pools' glowing plant, along their rims under the water. */
    public static final RegistryObject<Block> EMBER_ANEMONE = block("ember_anemone",
            () -> new SubmergedPlantBlock(plant(MapColor.COLOR_ORANGE, SoundType.WET_GRASS, 11), Block.box(3, 0, 3, 13, 10, 13)));
    public static final RegistryObject<Block> ROSE_TENDRILS = BLOCKS.register("rose_tendrils",
            () -> new HangingVinesBlock(plant(MapColor.COLOR_BROWN, SoundType.WEEPING_VINES, 0), ERBlocks.ROSE_TENDRILS_PLANT_REF));
    public static final RegistryObject<Block> ROSE_TENDRILS_PLANT = BLOCKS.register("rose_tendrils_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.COLOR_BROWN, SoundType.WEEPING_VINES, 0), ERBlocks.ROSE_TENDRILS_REF));
    /** Amber crystals in the rock, in the Ember Gardens and the Basalt Galleries. */
    public static final RegistryObject<Block> AMBER_SHARD = block("amber_shard", () -> new AmethystClusterBlock(5, 3,
            BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).forceSolidOn().noOcclusion().sound(SoundType.LARGE_AMETHYST_BUD)
                    .strength(1.5F).lightLevel(s -> 7).pushReaction(PushReaction.DESTROY)));

    // ------------------------------------------------------------------------------------------- Basalt Galleries
    public static final RegistryObject<Block> CINDER_BLOOM = block("cinder_bloom",
            () -> new CavePlantBlock(plant(MapColor.COLOR_RED, SoundType.NETHER_SPROUTS, 5), Block.box(3, 0, 3, 13, 10, 13)));
    public static final RegistryObject<Block> ASH_FERN = block("ash_fern",
            () -> new CavePlantBlock(plant(MapColor.COLOR_GRAY, SoundType.ROOTS, 0).replaceable().offsetType(BlockBehaviour.OffsetType.XZ),
                    Block.box(2, 0, 2, 14, 13, 14)));
    /** A dark crust over the basalt with glowing cracks: light only where it covers a face, like glow lichen. */
    public static final RegistryObject<Block> SMOULDER_LICHEN = block("smoulder_lichen",
            () -> new GlowLichenBlock(lichen(MapColor.NETHER, SoundType.GLOW_LICHEN, 3)));

    // =========================================================================================== Upper Caves rework
    // ------------------------------------------------------------------------------------------- Frost Fern Grotto
    // (and the frozen Root Vaults' thawing halls)
    public static final RegistryObject<Block> FROST_FERN = block("frost_fern",
            () -> new CavePlantBlock(plant(MapColor.ICE, SoundType.GRASS, 0).replaceable().offsetType(BlockBehaviour.OffsetType.XYZ),
                    Block.box(2, 0, 2, 14, 13, 14)));
    public static final RegistryObject<Block> TALL_FROST_FERN = tallBlock("tall_frost_fern",
            () -> new TallCavePlantBlock(plant(MapColor.ICE, SoundType.GRASS, 0).offsetType(BlockBehaviour.OffsetType.XZ),
                    Block.box(2, 0, 2, 14, 16, 14), Block.box(2, 0, 2, 14, 14, 14)));
    public static final RegistryObject<Block> FROST_ASTER = block("frost_aster",
            () -> new CavePlantBlock(flower(MapColor.COLOR_LIGHT_BLUE, 0), Block.box(3, 0, 3, 13, 12, 13)));
    /** Nodding white bells whose throats glow a cold blue. */
    public static final RegistryObject<Block> SNOWBELL = block("snowbell",
            () -> new CavePlantBlock(flower(MapColor.SNOW, 6), Block.box(3, 0, 3, 13, 13, 13)));
    /** Like vanilla's cave vines, with pale blue berries that give light. */
    public static final RegistryObject<Block> FROSTBERRY_VINE = BLOCKS.register("frostberry_vine",
            () -> new HangingVinesBlock(plant(MapColor.COLOR_LIGHT_BLUE, SoundType.CAVE_VINES, 12), ERBlocks.FROSTBERRY_VINE_PLANT_REF));
    public static final RegistryObject<Block> FROSTBERRY_VINE_PLANT = BLOCKS.register("frostberry_vine_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.COLOR_LIGHT_BLUE, SoundType.CAVE_VINES, 8), ERBlocks.FROSTBERRY_VINE_REF));
    /** Pale crystals of rime over the walls: light only where it covers a face, like glow lichen. */
    public static final RegistryObject<Block> RIME_LICHEN = block("rime_lichen",
            () -> new GlowLichenBlock(lichen(MapColor.ICE, SoundType.GLOW_LICHEN, 6)));
    /** Frost feathers over the rock near the cold mouths. No light. */
    public static final RegistryObject<Block> HOARFROST = block("hoarfrost",
            () -> new GlowLichenBlock(lichen(MapColor.SNOW, SoundType.POWDER_SNOW, 0)));
    /** Icicles hanging from the ceilings by the cold mouths; they grow downwards like vines but cannot be climbed. */
    public static final RegistryObject<Block> ICICLE = BLOCKS.register("icicle",
            () -> new HangingVinesBlock(ice(), ERBlocks.ICICLE_PLANT_REF));
    public static final RegistryObject<Block> ICICLE_PLANT = BLOCKS.register("icicle_plant",
            () -> new HangingVinesPlantBlock(ice(), ERBlocks.ICICLE_REF));

    // ------------------------------------------------------------------------------------------- Sinkholes and oasis hollows
    /** Grass hanging over a sinkhole's rim from the turf above. */
    public static final RegistryObject<Block> TRAILING_GRASS = BLOCKS.register("trailing_grass",
            () -> new HangingVinesBlock(plant(MapColor.PLANT, SoundType.GRASS, 0), ERBlocks.TRAILING_GRASS_PLANT_REF));
    public static final RegistryObject<Block> TRAILING_GRASS_PLANT = BLOCKS.register("trailing_grass_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.PLANT, SoundType.GRASS, 0), ERBlocks.TRAILING_GRASS_REF));
    /** A small palm in the sand of an oasis hollow. */
    public static final RegistryObject<Block> FAN_PALM = tallBlock("fan_palm",
            () -> new TallCavePlantBlock(plant(MapColor.PLANT, SoundType.AZALEA, 0).offsetType(BlockBehaviour.OffsetType.XZ).ignitedByLava(),
                    Block.box(2, 0, 2, 14, 16, 14), Block.box(1, 0, 1, 15, 14, 15)));
    public static final RegistryObject<Block> OASIS_GRASS = block("oasis_grass",
            () -> new CavePlantBlock(plant(MapColor.PLANT, SoundType.GRASS, 0).replaceable().offsetType(BlockBehaviour.OffsetType.XYZ)
                    .ignitedByLava(), Block.box(2, 0, 2, 14, 13, 14)));
    /** A pale lily by the oasis pools, its trumpets glowing amber. */
    public static final RegistryObject<Block> DUNE_LILY = block("dune_lily",
            () -> new CavePlantBlock(flower(MapColor.SAND, 9), Block.box(3, 0, 3, 13, 14, 13)));

    // Vine tips and bodies point at each other, so they look each other up when first needed.
    private static final Supplier<Block> WISTERIA_REF = () -> WISTERIA.get();
    private static final Supplier<Block> WISTERIA_PLANT_REF = () -> WISTERIA_PLANT.get();
    private static final Supplier<Block> BLUE_GLOW_VINES_REF = () -> BLUE_GLOW_VINES.get();
    private static final Supplier<Block> BLUE_GLOW_VINES_PLANT_REF = () -> BLUE_GLOW_VINES_PLANT.get();
    private static final Supplier<Block> EMBER_VINES_REF = () -> EMBER_VINES.get();
    private static final Supplier<Block> EMBER_VINES_PLANT_REF = () -> EMBER_VINES_PLANT.get();
    // vegetation rework, part A
    private static final Supplier<Block> AMBER_BULB_VINE_REF = () -> AMBER_BULB_VINE.get();
    private static final Supplier<Block> AMBER_BULB_VINE_PLANT_REF = () -> AMBER_BULB_VINE_PLANT.get();
    private static final Supplier<Block> WEEPING_IVY_REF = () -> WEEPING_IVY.get();
    private static final Supplier<Block> WEEPING_IVY_PLANT_REF = () -> WEEPING_IVY_PLANT.get();
    private static final Supplier<Block> SILVER_FROND_REF = () -> SILVER_FROND.get();
    private static final Supplier<Block> SILVER_FROND_PLANT_REF = () -> SILVER_FROND_PLANT.get();
    private static final Supplier<Block> GOLD_THREADS_REF = () -> GOLD_THREADS.get();
    private static final Supplier<Block> GOLD_THREADS_PLANT_REF = () -> GOLD_THREADS_PLANT.get();
    private static final Supplier<Block> DUSK_VINES_REF = () -> DUSK_VINES.get();
    private static final Supplier<Block> DUSK_VINES_PLANT_REF = () -> DUSK_VINES_PLANT.get();
    // vegetation rework, part B
    private static final Supplier<Block> PALE_WISTERIA_REF = () -> PALE_WISTERIA.get();
    private static final Supplier<Block> PALE_WISTERIA_PLANT_REF = () -> PALE_WISTERIA_PLANT.get();
    private static final Supplier<Block> ROSE_TENDRILS_REF = () -> ROSE_TENDRILS.get();
    private static final Supplier<Block> ROSE_TENDRILS_PLANT_REF = () -> ROSE_TENDRILS_PLANT.get();
    // Upper Caves rework
    private static final Supplier<Block> FROSTBERRY_VINE_REF = () -> FROSTBERRY_VINE.get();
    private static final Supplier<Block> FROSTBERRY_VINE_PLANT_REF = () -> FROSTBERRY_VINE_PLANT.get();
    private static final Supplier<Block> ICICLE_REF = () -> ICICLE.get();
    private static final Supplier<Block> ICICLE_PLANT_REF = () -> ICICLE_PLANT.get();
    private static final Supplier<Block> TRAILING_GRASS_REF = () -> TRAILING_GRASS.get();
    private static final Supplier<Block> TRAILING_GRASS_PLANT_REF = () -> TRAILING_GRASS_PLANT.get();

    // Only the vine tips have items, like vanilla's weeping vines.
    static {
        item("wisteria", WISTERIA);
        item("blue_glow_vines", BLUE_GLOW_VINES);
        item("ember_vines", EMBER_VINES);
        // vegetation rework, part A
        item("amber_bulb_vine", AMBER_BULB_VINE);
        item("weeping_ivy", WEEPING_IVY);
        item("silver_frond", SILVER_FROND);
        item("gold_threads", GOLD_THREADS);
        item("dusk_vines", DUSK_VINES);
        // vegetation rework, part B
        item("pale_wisteria", PALE_WISTERIA);
        item("rose_tendrils", ROSE_TENDRILS);
        // Upper Caves rework
        item("frostberry_vine", FROSTBERRY_VINE);
        item("icicle", ICICLE);
        item("trailing_grass", TRAILING_GRASS);
    }

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + ExplorationRemix.MOD_ID))
            .icon(() -> new ItemStack(LANTERN_FUNGUS.get()))
            .displayItems((params, out) -> ITEMS.getEntries().forEach(i -> out.accept(i.get())))
            .build());

    /** A small plant you walk through and break with a punch. */
    private static BlockBehaviour.Properties plant(MapColor color, SoundType sound, int light) {
        BlockBehaviour.Properties p = BlockBehaviour.Properties.of().mapColor(color).noCollission().instabreak().sound(sound)
                .pushReaction(PushReaction.DESTROY);
        return light > 0 ? p.lightLevel(s -> light) : p;
    }

    /** An icicle: walked through and broken with a punch, with glass's sound. */
    private static BlockBehaviour.Properties ice() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.ICE).noCollission().instabreak().sound(SoundType.GLASS)
                .pushReaction(PushReaction.DESTROY);
    }

    /** A flower: a plant set a little off the block's centre, like vanilla's, that burns. */
    private static BlockBehaviour.Properties flower(MapColor color, int light) {
        return plant(color, SoundType.GRASS, light).offsetType(BlockBehaviour.OffsetType.XZ).ignitedByLava();
    }

    /** A sheet of lichen or ivy over the rock (a GlowLichenBlock), giving light only where it covers a face. */
    private static BlockBehaviour.Properties lichen(MapColor color, SoundType sound, int light) {
        BlockBehaviour.Properties p = BlockBehaviour.Properties.of().mapColor(color).replaceable().noCollission().strength(0.2F).sound(sound)
                .pushReaction(PushReaction.DESTROY);
        return light > 0 ? p.lightLevel(GlowLichenBlock.emission(light)) : p;
    }

    static RegistryObject<Block> block(String name, Supplier<Block> supplier) {
        RegistryObject<Block> ro = BLOCKS.register(name, supplier);
        item(name, ro);
        return ro;
    }

    /** A two-block plant, whose item places both halves. */
    static RegistryObject<Block> tallBlock(String name, Supplier<Block> supplier) {
        RegistryObject<Block> ro = BLOCKS.register(name, supplier);
        ITEMS.register(name, () -> new DoubleHighBlockItem(ro.get(), new Item.Properties()));
        return ro;
    }

    private static void item(String name, RegistryObject<Block> block) {
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }
}
