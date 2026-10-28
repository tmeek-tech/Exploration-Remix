package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.BandStoneBlock;
import com.explorationremix.block.CavePlantBlock;
import com.explorationremix.block.HangingPlantBlock;
import com.explorationremix.block.HangingVinesBlock;
import com.explorationremix.block.HangingVinesPlantBlock;
import com.explorationremix.block.PoolPlantBlock;
import com.explorationremix.block.ShelfFungusBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
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
    public static final RegistryObject<Block> BLUE_GLOW_VINES = BLOCKS.register("blue_glow_vines",
            () -> new HangingVinesBlock(plant(MapColor.COLOR_BLUE, SoundType.CAVE_VINES, 8), ERBlocks.BLUE_GLOW_VINES_PLANT_REF));
    public static final RegistryObject<Block> BLUE_GLOW_VINES_PLANT = BLOCKS.register("blue_glow_vines_plant",
            () -> new HangingVinesPlantBlock(plant(MapColor.COLOR_BLUE, SoundType.CAVE_VINES, 8), ERBlocks.BLUE_GLOW_VINES_REF));

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

    // Vine tips and bodies point at each other, so they look each other up when first needed.
    private static final Supplier<Block> WISTERIA_REF = () -> WISTERIA.get();
    private static final Supplier<Block> WISTERIA_PLANT_REF = () -> WISTERIA_PLANT.get();
    private static final Supplier<Block> BLUE_GLOW_VINES_REF = () -> BLUE_GLOW_VINES.get();
    private static final Supplier<Block> BLUE_GLOW_VINES_PLANT_REF = () -> BLUE_GLOW_VINES_PLANT.get();
    private static final Supplier<Block> EMBER_VINES_REF = () -> EMBER_VINES.get();
    private static final Supplier<Block> EMBER_VINES_PLANT_REF = () -> EMBER_VINES_PLANT.get();

    // Only the vine tips have items, like vanilla's weeping vines.
    static {
        item("wisteria", WISTERIA);
        item("blue_glow_vines", BLUE_GLOW_VINES);
        item("ember_vines", EMBER_VINES);
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

    /** A flower: a plant set a little off the block's centre, like vanilla's, that burns. */
    private static BlockBehaviour.Properties flower(MapColor color, int light) {
        return plant(color, SoundType.GRASS, light).offsetType(BlockBehaviour.OffsetType.XZ).ignitedByLava();
    }

    static RegistryObject<Block> block(String name, Supplier<Block> supplier) {
        RegistryObject<Block> ro = BLOCKS.register(name, supplier);
        item(name, ro);
        return ro;
    }

    private static void item(String name, RegistryObject<Block> block) {
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }
}
