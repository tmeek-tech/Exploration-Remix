package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.BandStoneBlock;
import com.explorationremix.block.BoneScrapsBlock;
import com.explorationremix.block.CavePlantBlock;
import com.explorationremix.block.GlowBlock;
import com.explorationremix.block.GrazedGrassBlock;
import com.explorationremix.block.HangingFernBlock;
import com.explorationremix.block.HangingPlantBlock;
import com.explorationremix.block.HangingVinesBlock;
import com.explorationremix.block.HangingVinesPlantBlock;
import com.explorationremix.block.Neon;
import com.explorationremix.block.NeonBlock;
import com.explorationremix.block.NeonMushroomsBlock;
import com.explorationremix.block.NeonPlantBlock;
import com.explorationremix.block.PointedBlackDripstoneBlock;
import com.explorationremix.block.PoolPlantBlock;
import com.explorationremix.block.ShelfFungusBlock;
import com.explorationremix.block.SilverCattailsBlock;
import com.explorationremix.block.SubmergedPlantBlock;
import com.explorationremix.block.TallCavePlantBlock;
import com.explorationremix.block.TallNeonPlantBlock;
import com.explorationremix.block.TubeWormsBlock;
import com.explorationremix.block.VentCrownBlock;
import com.explorationremix.block.WallTubeWormsBlock;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.GlowLichenBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
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
    /** Glowing roots hanging from the ceiling: a vine, tip and body, so a column of them joins up (0.14.3). */
    public static final RegistryObject<Block> LANTERN_ROOTS = BLOCKS.register("lantern_roots",
            () -> new HangingVinesBlock(plant(MapColor.COLOR_YELLOW, SoundType.HANGING_ROOTS, 8), ERBlocks.LANTERN_ROOTS_PLANT_REF));
    public static final RegistryObject<Block> LANTERN_ROOTS_PLANT = BLOCKS.register("lantern_roots_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.COLOR_YELLOW, SoundType.HANGING_ROOTS, 8), ERBlocks.LANTERN_ROOTS_REF));
    static { item("lantern_roots", LANTERN_ROOTS); }

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
    public static final RegistryObject<Block> SILVER_FERN = BLOCKS.register("silver_fern",
            () -> new CavePlantBlock(plant(MapColor.METAL, SoundType.GRASS, 0).replaceable().offsetType(BlockBehaviour.OffsetType.XYZ),
                    Block.box(2, 0, 2, 14, 13, 14)));
    /** Stands the fern on a floor, or hangs it on the side of a block (the silver wall fern), as a torch goes on a wall. */
    public static final RegistryObject<Item> SILVER_FERN_ITEM = ITEMS.register("silver_fern", () -> new StandingAndWallBlockItem(
            SILVER_FERN.get(), ERBlocks.SILVER_WALL_FERN.get(), new Item.Properties(), Direction.DOWN));

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
    public static final RegistryObject<Block> WEEPING_IVY = BLOCKS.register("weeping_ivy",
            () -> new HangingVinesBlock(plant(MapColor.PLANT, SoundType.VINE, 0), ERBlocks.WEEPING_IVY_PLANT_REF));
    public static final RegistryObject<Block> WEEPING_IVY_PLANT = BLOCKS.register("weeping_ivy_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.PLANT, SoundType.VINE, 0), ERBlocks.WEEPING_IVY_REF));
    /** Ivy over the walls and ceilings of the teal grottos and ravines: a sheet like glow lichen, giving no light. */
    public static final RegistryObject<Block> GROTTO_IVY = block("grotto_ivy",
            () -> new GlowLichenBlock(lichen(MapColor.COLOR_GREEN, SoundType.VINE, 0)));

    // ------------------------------------------------------------------------------------------- Silver Fern Rift
    public static final RegistryObject<Block> SILVER_FROND = BLOCKS.register("silver_frond",
            () -> new HangingVinesBlock(plant(MapColor.METAL, SoundType.GRASS, 0), ERBlocks.SILVER_FROND_PLANT_REF));
    public static final RegistryObject<Block> SILVER_FROND_PLANT = BLOCKS.register("silver_frond_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.METAL, SoundType.GRASS, 0), ERBlocks.SILVER_FROND_REF));
    public static final RegistryObject<Block> TALL_SILVER_FERN = tallBlock("tall_silver_fern",
            () -> new TallCavePlantBlock(plant(MapColor.METAL, SoundType.GRASS, 0).replaceable().offsetType(BlockBehaviour.OffsetType.XZ),
                    Block.box(2, 0, 2, 14, 16, 14), Block.box(2, 0, 2, 14, 14, 14)));
    /**
     * A silver fern hanging down the side of a block, its fronds falling from near the top into the blocks below (the
     * outline stays in its own block), in three sizes (HangingFernBlock). No item of its own: it is the silver fern's,
     * placed on a wall (the small size), and drops it.
     */
    public static final RegistryObject<Block> SILVER_WALL_FERN = BLOCKS.register("silver_wall_fern",
            () -> new HangingFernBlock(plant(MapColor.METAL, SoundType.GRASS, 0), Block.box(1, 0, 8, 15, 14, 16)));

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
            () -> new com.explorationremix.block.GlowbellBlock(plant(MapColor.COLOR_LIGHT_BLUE, SoundType.SPORE_BLOSSOM, 9)
                    .offsetType(BlockBehaviour.OffsetType.XZ), Block.box(3, 0, 3, 13, 14, 13)));
    public static final RegistryObject<Block> PAGODA_FUNGUS = tallBlock("pagoda_fungus",
            () -> new TallCavePlantBlock(plant(MapColor.COLOR_BLUE, SoundType.FUNGUS, 7), Block.box(3, 0, 3, 13, 16, 13), Block.box(2, 0, 2, 14, 12, 14)));
    /** Blue specks over the rift's walls: light only where it covers a face, like glow lichen. */
    public static final RegistryObject<Block> BLUE_LICHEN = block("blue_lichen",
            () -> new GlowLichenBlock(lichen(MapColor.COLOR_BLUE, SoundType.GLOW_LICHEN, 4)));
    /**
     * 0.21.7: the lichen of the streaks down the noise rifts' walls, grown thick, which a player climbs like vines (tag
     * minecraft:climbable; Taran's card: "Climbable lichen streaks"), a little brighter than the thin lichen so a streak
     * shows the way up.
     */
    public static final RegistryObject<Block> THICK_BLUE_LICHEN = block("thick_blue_lichen",
            () -> new GlowLichenBlock(lichen(MapColor.COLOR_BLUE, SoundType.GLOW_LICHEN, 5)));
    public static final RegistryObject<Block> ASURINE_BUD = block("asurine_bud", () -> new AmethystClusterBlock(3, 4,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).forceSolidOn().noOcclusion().sound(SoundType.SMALL_AMETHYST_BUD)
                    .strength(1.5F).lightLevel(s -> 5).pushReaction(PushReaction.DESTROY)));
    /** A crust of small asurine facets over the asurine: light only where it covers a face, like glow lichen. */
    public static final RegistryObject<Block> ASURINE_CRUST = block("asurine_crust",
            () -> new GlowLichenBlock(lichen(MapColor.COLOR_LIGHT_BLUE, SoundType.AMETHYST_CLUSTER, 3)));
    /**
     * 0.21.7: reeds two or three blocks tall by the rifts' water, which hide a player standing in them from the Skeins
     * (Taran's card picks: "Silver cattails", "Hidden while you're in them"). Its item sets down the whole plant. The
     * heads at the plant's top glow faintly (light 3), so a bed shows by the water in the dark.
     */
    public static final RegistryObject<Block> SILVER_CATTAILS = block("silver_cattails",
            () -> new SilverCattailsBlock(plant(MapColor.COLOR_LIGHT_BLUE, SoundType.WET_GRASS, 0).offsetType(BlockBehaviour.OffsetType.XZ)
                    .lightLevel(s -> s.getValue(SilverCattailsBlock.PART).top() ? 3 : 0)));
    /** The deep dark's only new plant: dark strands whose cold specks glow without giving light, so the dark stays dark. */

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
    /** Amber crystals in the rock, in the Ember Gardens, the Wasteland and its refuge, the Ash Hollows. */
    public static final RegistryObject<Block> AMBER_SHARD = block("amber_shard", () -> new AmethystClusterBlock(5, 3,
            BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).forceSolidOn().noOcclusion().sound(SoundType.LARGE_AMETHYST_BUD)
                    .strength(1.5F).lightLevel(s -> 7).pushReaction(PushReaction.DESTROY)));

    // ------------------------------------------------------------------- the Wasteland (the old Basalt Galleries)
    public static final RegistryObject<Block> CINDER_BLOOM = block("cinder_bloom",
            () -> new CavePlantBlock(plant(MapColor.COLOR_RED, SoundType.NETHER_SPROUTS, 5), Block.box(3, 0, 3, 13, 10, 13)));
    public static final RegistryObject<Block> ASH_FERN = block("ash_fern",
            () -> new CavePlantBlock(plant(MapColor.COLOR_GRAY, SoundType.ROOTS, 0).replaceable().offsetType(BlockBehaviour.OffsetType.XZ),
                    Block.box(2, 0, 2, 14, 13, 14)));
    /** A dark crust over the basalt with glowing cracks: light only where it covers a face, like glow lichen. */
    public static final RegistryObject<Block> SMOULDER_LICHEN = block("smoulder_lichen",
            () -> new GlowLichenBlock(lichen(MapColor.NETHER, SoundType.GLOW_LICHEN, 3)));

    // =========================================================================================== 0.5: the lifeless Galleries
    // Nothing grows in the Basalt Galleries (since 0.5 the bottom layer, under the Wasteland): their light and their climbs
    // come from rock. Neither block is a plant (gen_data.NOT_PLANTS), so /remix census counts neither as growth.
    /**
     * Rock still glowing from the heat under it, in seams through the Galleries' walls and vaults and in plates over the
     * Caldera's floor: it lights the halls where no lava reaches. Under Complementary a mod block that gives light glows
     * over its whole face ({@code client/ShaderGlow}), and without the shader it is drawn at full brightness like magma,
     * so its texture is glowing plates with dark seams between them. Unlike magma it is safe to walk on.
     */
    public static final RegistryObject<Block> EMBER_CRUST = block("ember_crust", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(1.5F, 6.0F).sound(SoundType.BASALT)
                    .requiresCorrectToolForDrops().lightLevel(s -> 11).emissiveRendering((s, l, p) -> true)
                    .isValidSpawn((s, l, p, e) -> false)));
    /**
     * Beads of cinder fused into a hanging chain. 0.5's climbs of the Galleries and of the Wasteland's bare refuges hung
     * it; since 0.8.0 world generation places none (the two bottom layers are natural rock only), and it stays registered
     * for worlds that have it. It climbs like a vine (minecraft:climbable) and hangs from a sturdy ceiling or from the
     * chain above it (explorationremix:hanging_supports). No light: a few embers caught between its beads only glint (a
     * glow overlay left out of the shader's glowing blocks).
     */
    public static final RegistryObject<Block> CINDER_CHAIN = block("cinder_chain", () -> new HangingPlantBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).noCollission().strength(0.5F).sound(SoundType.BASALT)
                    .pushReaction(PushReaction.DESTROY).isValidSpawn((s, l, p, e) -> false), Block.box(5, 0, 5, 11, 16, 11)));
    /**
     * The Galleries' lava glow: invisible light in the air near the halls' rock ({@code HallGlowFeature}), which nothing
     * collides with or aims at (a placed block, a falling block, a fluid or an explosion takes its place). Not air to the
     * game: see {@link GlowBlock} for what still treats it as a block. No item.
     */
    public static final RegistryObject<Block> GLOW = BLOCKS.register("glow", () -> new GlowBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.NONE).replaceable().noCollission().noOcclusion().noLootTable().instabreak()
                    .sound(SoundType.EMPTY).pushReaction(PushReaction.DESTROY).lightLevel(s -> s.getValue(GlowBlock.LEVEL))));

    // =========================================================================================== Upper Caves rework
    // ------------------------------------------------------------------------------------------- Frost Fern Grotto
    // (and the frozen Root Vaults' inner halls). The frosty ferns, frost and icicles are Immersive Weathering's.
    /** Moss with a cold blue cast, in place of vanilla's green moss in the cold caves; ferns and flowers grow on it. */
    public static final RegistryObject<Block> FROST_MOSS = block("frost_moss", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(0.1F).sound(SoundType.MOSS).pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> FROST_MOSS_CARPET = block("frost_moss_carpet", () -> new CarpetBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(0.1F).sound(SoundType.MOSS_CARPET).pushReaction(PushReaction.DESTROY)));
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
    /** Frost feathers over the rock near the cold mouths. No light. */
    public static final RegistryObject<Block> HOARFROST = block("hoarfrost",
            () -> new GlowLichenBlock(lichen(MapColor.SNOW, SoundType.POWDER_SNOW, 0)));
    // (0.20.1's glow ice left the game in 0.20.7, Taran: "Remove glow ice"; ERRemaps turns old worlds' into packed ice.)

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
    private static final Supplier<Block> WEEPING_IVY_REF = () -> WEEPING_IVY.get();
    private static final Supplier<Block> WEEPING_IVY_PLANT_REF = () -> WEEPING_IVY_PLANT.get();
    private static final Supplier<Block> SILVER_FROND_REF = () -> SILVER_FROND.get();
    private static final Supplier<Block> SILVER_FROND_PLANT_REF = () -> SILVER_FROND_PLANT.get();
    private static final Supplier<Block> GOLD_THREADS_REF = () -> GOLD_THREADS.get();
    private static final Supplier<Block> GOLD_THREADS_PLANT_REF = () -> GOLD_THREADS_PLANT.get();
    // vegetation rework, part B
    private static final Supplier<Block> PALE_WISTERIA_REF = () -> PALE_WISTERIA.get();
    private static final Supplier<Block> PALE_WISTERIA_PLANT_REF = () -> PALE_WISTERIA_PLANT.get();
    private static final Supplier<Block> ROSE_TENDRILS_REF = () -> ROSE_TENDRILS.get();
    private static final Supplier<Block> ROSE_TENDRILS_PLANT_REF = () -> ROSE_TENDRILS_PLANT.get();
    // Upper Caves rework
    private static final Supplier<Block> FROSTBERRY_VINE_REF = () -> FROSTBERRY_VINE.get();
    private static final Supplier<Block> FROSTBERRY_VINE_PLANT_REF = () -> FROSTBERRY_VINE_PLANT.get();
    private static final Supplier<Block> TRAILING_GRASS_REF = () -> TRAILING_GRASS.get();
    private static final Supplier<Block> TRAILING_GRASS_PLANT_REF = () -> TRAILING_GRASS_PLANT.get();
    // 0.14.3: lantern roots
    private static final Supplier<Block> LANTERN_ROOTS_REF = () -> LANTERN_ROOTS.get();
    private static final Supplier<Block> LANTERN_ROOTS_PLANT_REF = () -> LANTERN_ROOTS_PLANT.get();

    // Only the vine tips have items, like vanilla's weeping vines.
    static {
        item("wisteria", WISTERIA);
        item("blue_glow_vines", BLUE_GLOW_VINES);
        item("ember_vines", EMBER_VINES);
        // vegetation rework, part A
        item("weeping_ivy", WEEPING_IVY);
        item("silver_frond", SILVER_FROND);
        item("gold_threads", GOLD_THREADS);
        // vegetation rework, part B
        item("pale_wisteria", PALE_WISTERIA);
        item("rose_tendrils", ROSE_TENDRILS);
        // Upper Caves rework
        item("frostberry_vine", FROSTBERRY_VINE);
        item("trailing_grass", TRAILING_GRASS);
    }

    /** A Mossback's den (creature.MossbackDen): trampled moss and gnawed bone on a den gate's landing. Drops nothing. */
    public static final RegistryObject<Block> MOSSBACK_DEN = block("mossback_den", () -> new com.explorationremix.creature.MossbackDen(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(0.6F).sound(SoundType.MOSS_CARPET).noLootTable()
                    .pushReaction(PushReaction.BLOCK)));
    /**
     * Bones and scraps round a den (world.feature.DenLairFeature, and the lizards' own leavings): thin litter with no
     * collision, so nothing's path ever catches on it. Breaks like a carpet and sometimes gives a bone.
     */
    public static final RegistryObject<Block> BONE_SCRAPS = block("bone_scraps", () -> new BoneScrapsBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.SAND).replaceable().noCollission().noOcclusion().strength(0.1F)
                    .sound(SoundType.BONE_BLOCK).pushReaction(PushReaction.DESTROY)));

    // =========================================================================================== Deep Gardens round 2
    // The vent field, the Deep Gardens' caves from y -289 to -480 (world.terrain.GardensVentField). Taran's pick for its
    // rock (deepslate caves, black vents): the cave walls are vanilla deepslate, the vents and spires vanilla blackstone
    // since round 3 (they were vent rock and live vent rock before), and the dripstone is black dripstone, so the neon
    // glow stands out on black. The neon blocks come in the four neon colours (Neon: magenta, violet, lime, light blue;
    // lime only near the ways down) and draw their glowing pixels at full brightness over rock lit by the world; those on
    // the vents draw their neon over vanilla's blackstone. Nothing here is warm, cyan or teal.
    /**
     * Vanilla's dripstone block in near-black: the vent field's hanging cones and the skin over much of its cave walls,
     * floors and ceilings (round 3), banded like vanilla's. It breaks and sounds like vanilla's dripstone block.
     */
    public static final RegistryObject<Block> BLACK_DRIPSTONE = block("black_dripstone", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops()
                    .sound(SoundType.DRIPSTONE_BLOCK).strength(1.5F, 1.0F)));
    /**
     * Vanilla's pointed dripstone in black dripstone (round 3): stalactites and stalagmites that stack, fall and hurt as
     * vanilla's do but never grow or drip, the vent field being dry (PointedBlackDripstoneBlock). Vanilla's properties
     * but its map colour and its random ticks.
     */
    public static final RegistryObject<Block> POINTED_BLACK_DRIPSTONE = block("pointed_black_dripstone", () -> new PointedBlackDripstoneBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).forceSolidOn().instrument(NoteBlockInstrument.BASEDRUM).noOcclusion()
                    .sound(SoundType.POINTED_DRIPSTONE).strength(1.5F, 3.0F).dynamicShape().offsetType(BlockBehaviour.OffsetType.XZ)
                    .pushReaction(PushReaction.DESTROY).isRedstoneConductor((s, l, p) -> false)));
    /** The giant mushrooms' stem, pale lavender-white. No light. */
    public static final RegistryObject<Block> PALE_STEM = block("pale_stem", () -> new Block(mushroomBlock().mapColor(MapColor.WOOL)));
    /**
     * A glowing living film over the deepslate of the cave floors, like nylium: vanilla's deepslate with the film drawn
     * over it (round 3), so a patch fades into the floor round it. It breaks and sounds like deepslate.
     */
    public static final RegistryObject<Block> GLOW_MAT = block("glow_mat", () -> new NeonBlock(onDeepslate().mapColor(ERBlocks::neonMap).lightLevel(s -> 9)));
    /** The glow mat's sparser film, on the outer ring of a patch where it thins out into the bare floor (round 3). */
    public static final RegistryObject<Block> THIN_GLOW_MAT = block("thin_glow_mat", () -> new NeonBlock(onDeepslate().mapColor(ERBlocks::neonMap).lightLevel(s -> 6)));
    /** The giant mushrooms' cap, neon all over with darker specks. */
    public static final RegistryObject<Block> GLOW_CAP = block("glow_cap", () -> new NeonBlock(mushroomBlock().mapColor(ERBlocks::neonMap).lightLevel(s -> 13)));
    /** The bright mineral rim of a live vent's mouth: crystalline neon crust on blackstone, the brightest of the set. */
    public static final RegistryObject<Block> VENT_CROWN = block("vent_crown", () -> new VentCrownBlock(onBlackstone().mapColor(ERBlocks::neonMap).lightLevel(s -> 15)));
    /**
     * Vent beads (the spire tip until round 3): rounded neon mineral knobs on blackstone, on every face (0.19.4's
     * knobs, back in round 4). They cap the small live spires, mix with the crown over a live vent's top and run down its
     * walls in streaks, outside and inside (round 4: they took the places of den fissure, vent glow and throat vein).
     */
    public static final RegistryObject<Block> VENT_BEADS = block("vent_beads", () -> new NeonBlock(onBlackstone().mapColor(ERBlocks::neonMap).lightLevel(s -> 11)));
    /** A faint crust on the dry rock at the edge of the crude oil seeps: a dim light blue film, light blue only. */
    public static final RegistryObject<Block> SEEP_CRUST = block("seep_crust", () -> new Block(ventRock().mapColor(MapColor.COLOR_BLACK).lightLevel(s -> 5)));

    // =========================================================================================== Deep Gardens plant life
    // 0.20.2 (Taran, 2026-11-20: "Can you add some atmospheric grass and plant life to the Deep Gardens?"): grown by the
    // vent field's dressing (world.terrain.GardensDress.plants), in its neon on black. None gives light (card pick:
    // "Glowing parts, no extra light"): only the glow overlays shine, so the mats and vents stay the layer's lights.
    // The grass and the small mushrooms let a spore drift up now and then (card pick: "A few rising motes"): one
    // animateTick in the number given (a block within 16 of the player gets about one a second).
    /** Filament grass (card picks: "Dark blades, glowing tips", the mat's colour): the short tufts at a meadow's edge. */
    public static final RegistryObject<Block> FILAMENT_TUFT = block("filament_tuft",
            () -> new NeonPlantBlock(grass(), Block.box(2, 0, 2, 14, 7, 14), 0));
    public static final RegistryObject<Block> FILAMENT_GRASS = block("filament_grass",
            () -> new NeonPlantBlock(grass(), Block.box(2, 0, 2, 14, 13, 14), 90));
    /** The knee-high drifts in a meadow's heart. */
    public static final RegistryObject<Block> TALL_FILAMENT_GRASS = tallBlock("tall_filament_grass",
            () -> new TallNeonPlantBlock(grass().offsetType(BlockBehaviour.OffsetType.XZ), Block.box(2, 0, 2, 14, 16, 14),
                    Block.box(2, 0, 2, 14, 14, 14), 45));
    /** The stubble a stilt mantis leaves (card pick: "Crops it short, it regrows"). No item, no drop. */
    public static final RegistryObject<Block> GRAZED_FILAMENT_GRASS = BLOCKS.register("grazed_filament_grass",
            () -> new GrazedGrassBlock(grass().randomTicks(), Block.box(2, 0, 2, 14, 5, 14)));
    /** Tube worms round the live vents, in the vent's colour (card pick: "Yes, they pull in as you near"). */
    public static final RegistryObject<Block> TUBE_WORMS = BLOCKS.register("tube_worms", () -> new TubeWormsBlock(
            worms().offsetType(BlockBehaviour.OffsetType.XZ)));
    /** 0.20.6: tube worms on a vent's steep wall, their tubes curving up (card pick: "Lower half, on ledges and walls"). */
    public static final RegistryObject<Block> WALL_TUBE_WORMS = BLOCKS.register("wall_tube_worms", () -> new WallTubeWormsBlock(worms()));
    // one item for both, as a torch's: against a wall it places the wall's worms
    static {
        ITEMS.register("tube_worms", () -> new StandingAndWallBlockItem(TUBE_WORMS.get(), WALL_TUBE_WORMS.get(), new Item.Properties(), Direction.DOWN));
    }
    /** Silk threads beaded with light blue lights, hanging in the dark halls (card pick: "Light blue, in the dark halls"). */
    public static final RegistryObject<Block> GLOWWORM_THREADS = BLOCKS.register("glowworm_threads",
            () -> new HangingVinesBlock(plant(MapColor.COLOR_LIGHT_BLUE, SoundType.HANGING_ROOTS, 0), ERBlocks.GLOWWORM_THREADS_PLANT_REF));
    public static final RegistryObject<Block> GLOWWORM_THREADS_PLANT = BLOCKS.register("glowworm_threads_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.COLOR_LIGHT_BLUE, SoundType.HANGING_ROOTS, 0), ERBlocks.GLOWWORM_THREADS_REF));
    static { item("glowworm_threads", GLOWWORM_THREADS); }
    /** Slender dark reeds along the oil seeps, their heads beaded faint light blue (card pick: "Dark reeds, light blue tips"). */
    public static final RegistryObject<Block> SEEP_REEDS = block("seep_reeds",
            () -> new CavePlantBlock(plant(MapColor.COLOR_BLACK, SoundType.GRASS, 0).replaceable().offsetType(BlockBehaviour.OffsetType.XZ),
                    Block.box(3, 0, 3, 13, 15, 13)));
    public static final RegistryObject<Block> TALL_SEEP_REEDS = tallBlock("tall_seep_reeds",
            () -> new TallCavePlantBlock(plant(MapColor.COLOR_BLACK, SoundType.GRASS, 0).offsetType(BlockBehaviour.OffsetType.XZ),
                    Block.box(3, 0, 3, 13, 16, 13), Block.box(3, 0, 3, 13, 15, 13)));
    /** Small mushrooms in their giant's colour round its stem (card pick: "Clusters round their stems"). */
    public static final RegistryObject<Block> NEON_MUSHROOMS = block("neon_mushrooms",
            () -> new NeonMushroomsBlock(BlockBehaviour.Properties.of().mapColor(ERBlocks::neonMap).noCollission().instabreak()
                    .sound(SoundType.FUNGUS).offsetType(BlockBehaviour.OffsetType.XZ).pushReaction(PushReaction.DESTROY), 120));
    private static final Supplier<Block> GLOWWORM_THREADS_REF = () -> GLOWWORM_THREADS.get();
    private static final Supplier<Block> GLOWWORM_THREADS_PLANT_REF = () -> GLOWWORM_THREADS_PLANT.get();

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + ExplorationRemix.MOD_ID))
            .icon(() -> new ItemStack(LANTERN_FUNGUS.get()))
            // a neon block's item once in each colour (it places the colour its BlockStateTag names); the plants of the
            // vent field too (0.20.2)
            .displayItems((params, out) -> ITEMS.getEntries().forEach(i -> {
                if (i.get() instanceof BlockItem b && b.getBlock().defaultBlockState().hasProperty(NeonBlock.NEON)) {
                    for (Neon n : Neon.values()) out.accept(NeonBlock.stack(b, n));
                } else {
                    out.accept(i.get());
                }
            }))
            .build());

    /** The vent field's rock, like deepslate a little harder: it takes a pickaxe (any) to get it back. */
    private static BlockBehaviour.Properties ventRock() {
        return BlockBehaviour.Properties.of().instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(3.5F, 6.0F)
                .sound(SoundType.DEEPSLATE);
    }

    /** A film drawn over vanilla's deepslate (the glow mats): it breaks and sounds like deepslate. */
    private static BlockBehaviour.Properties onDeepslate() {
        return BlockBehaviour.Properties.of().instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(3.0F, 6.0F)
                .sound(SoundType.DEEPSLATE);
    }

    /** A neon block drawn over vanilla's blackstone (on the vents and spires): it breaks and sounds like blackstone. */
    private static BlockBehaviour.Properties onBlackstone() {
        return BlockBehaviour.Properties.of().instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.5F, 6.0F)
                .sound(SoundType.STONE);
    }

    /** A block of a giant mushroom, like vanilla's mushroom blocks: soft, cut fastest with an axe, and it burns. */
    private static BlockBehaviour.Properties mushroomBlock() {
        return BlockBehaviour.Properties.of().instrument(NoteBlockInstrument.BASS).strength(0.2F).sound(SoundType.WOOD).ignitedByLava();
    }

    /** A neon block shows its colour on maps. */
    private static MapColor neonMap(BlockState state) { return state.getValue(NeonBlock.NEON).mapColor; }

    /**
     * The vent field's filament grass (0.20.2): like vanilla's grass, walked through, broken with a punch, placed over,
     * set a little off the block's centre; its colour on maps the neon's.
     */
    private static BlockBehaviour.Properties grass() {
        return BlockBehaviour.Properties.of().mapColor(ERBlocks::neonMap).noCollission().instabreak().sound(SoundType.GRASS).replaceable()
                .offsetType(BlockBehaviour.OffsetType.XYZ).pushReaction(PushReaction.DESTROY);
    }

    /** Tube worms (0.20.2; on walls too since 0.20.6): walked through, broken with a punch, wet underfoot. */
    private static BlockBehaviour.Properties worms() {
        return BlockBehaviour.Properties.of().mapColor(ERBlocks::neonMap).noCollission().instabreak().sound(SoundType.WET_GRASS)
                .pushReaction(PushReaction.DESTROY);
    }

    /** A small plant you walk through and break with a punch. */
    private static BlockBehaviour.Properties plant(MapColor color, SoundType sound, int light) {
        BlockBehaviour.Properties p = BlockBehaviour.Properties.of().mapColor(color).noCollission().instabreak().sound(sound)
                .pushReaction(PushReaction.DESTROY);
        return light > 0 ? p.lightLevel(s -> light) : p;
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
