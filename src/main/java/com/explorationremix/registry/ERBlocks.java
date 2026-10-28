package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.BandStoneBlock;
import com.explorationremix.block.CaveFloraBlock;
import com.explorationremix.block.LayerStoneBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public final class ERBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ExplorationRemix.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ExplorationRemix.MOD_ID);

    private ERBlocks() {}

    // --- the deep world's rock -------------------------------------------------------------------------------
    public static final RegistryObject<Block> BAND_STONE = block("band_stone", () -> new BandStoneBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(-1.0F, 3600000.0F).noLootTable()
                    .sound(SoundType.DEEPSLATE).pushReaction(PushReaction.BLOCK)));
    public static final RegistryObject<Block> LANTERN_STONE = layerStone("lantern_stone", MapColor.TERRACOTTA_BROWN);
    public static final RegistryObject<Block> REACH_STONE = layerStone("reach_stone", MapColor.COLOR_LIGHT_BLUE);
    public static final RegistryObject<Block> EMBER_STONE = layerStone("ember_stone", MapColor.TERRACOTTA_ORANGE);
    public static final RegistryObject<Block> GALLERY_STONE = layerStone("gallery_stone", MapColor.COLOR_BLACK);

    // --- rune block ---------------------------------------------------------------------------------------------
    public static final RegistryObject<Block> RUNE_BLOCK = block("rune_block", () -> new com.explorationremix.block.RuneBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(-1.0F, 3600000.0F).noLootTable()
                    .sound(SoundType.AMETHYST).lightLevel(s -> s.getValue(com.explorationremix.block.RuneBlock.LIT) ? 12 : 4).pushReaction(PushReaction.BLOCK)));

    // --- remains of earlier explorers: the source of attunement books ------------------------------------------
    public static final RegistryObject<Block> REMAINS = block("remains", () -> new com.explorationremix.block.RemainsBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY).strength(0.6F).sound(SoundType.BONE_BLOCK).noOcclusion().noLootTable()));

    // --- gate nests ---------------------------------------------------------------------------------------------
    public static final RegistryObject<Block> EGG_CLUSTER = block("egg_cluster", () -> new com.explorationremix.block.EggClusterBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).strength(2.5F, 2.0F).sound(SoundType.SLIME_BLOCK).noOcclusion().noLootTable().lightLevel(s -> 3)));

    // --- biome signature blocks -------------------------------------------------------------------------------
    public static final RegistryObject<Block> BANDED_IRON = block("banded_iron", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(3.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.DEEPSLATE)));
    public static final RegistryObject<Block> HONEYCOMB_OCHRUM = block("honeycomb_ochrum", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(1.5F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.STONE)));
    public static final RegistryObject<Block> OCHRE_CRYSTAL = block("ochre_crystal", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(1.5F, 1.5F).sound(SoundType.AMETHYST).lightLevel(s -> 9).noOcclusion()));
    public static final RegistryObject<Block> AMBER_RESIN = block("amber_resin", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(0.8F, 0.8F).sound(SoundType.HONEY_BLOCK).lightLevel(s -> 7).noOcclusion()));
    public static final RegistryObject<Block> ASURINE_CRYSTAL = block("asurine_crystal", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE).strength(1.5F, 1.5F).sound(SoundType.AMETHYST).lightLevel(s -> 8).noOcclusion()));
    public static final RegistryObject<Block> HEMATITE_CRYSTAL = block("hematite_crystal", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(2.0F, 2.0F).sound(SoundType.AMETHYST).lightLevel(s -> 5).noOcclusion()));
    public static final RegistryObject<Block> RUSTED_GEAR = block("rusted_gear", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(2.0F, 6.0F).sound(SoundType.COPPER)));
    public static final RegistryObject<Block> RUSTED_PIPE = block("rusted_pipe", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(2.0F, 6.0F).sound(SoundType.COPPER)));

    // --- cave plants -------------------------------------------------------------------------------------------
    public static final RegistryObject<Block> LANTERN_FUNGUS = flora("lantern_fungus", CaveFloraBlock.Attach.FLOOR, 10, SoundType.FUNGUS);
    public static final RegistryObject<Block> HANGING_WISTERIA = flora("hanging_wisteria", CaveFloraBlock.Attach.CEILING, 4, SoundType.CAVE_VINES);
    public static final RegistryObject<Block> SILVER_FERN = flora("silver_fern", CaveFloraBlock.Attach.FLOOR, 2, SoundType.GRASS);
    public static final RegistryObject<Block> BLUE_MUSHROOM = flora("blue_mushroom", CaveFloraBlock.Attach.FLOOR, 6, SoundType.FUNGUS);
    public static final RegistryObject<Block> SHELF_FUNGUS = flora("shelf_fungus", CaveFloraBlock.Attach.CEILING, 3, SoundType.FUNGUS);
    public static final RegistryObject<Block> LAVENDER_CORAL = flora("lavender_coral", CaveFloraBlock.Attach.FLOOR, 5, SoundType.CORAL_BLOCK);
    public static final RegistryObject<Block> HEAT_FUNGUS = flora("heat_fungus", CaveFloraBlock.Attach.FLOOR, 8, SoundType.FUNGUS);

    private static RegistryObject<Block> layerStone(String name, MapColor color) {
        return block(name, () -> new LayerStoneBlock(BlockBehaviour.Properties.of().mapColor(color).strength(-1.0F, 1200.0F)
                .noLootTable().sound(SoundType.DEEPSLATE).pushReaction(PushReaction.BLOCK)));
    }

    private static RegistryObject<Block> flora(String name, CaveFloraBlock.Attach attach, int light, SoundType sound) {
        return block(name, () -> new CaveFloraBlock(BlockBehaviour.Properties.of().mapColor(MapColor.PLANT).noCollission()
                .instabreak().sound(sound).lightLevel(s -> light).offsetType(BlockBehaviour.OffsetType.XZ), attach));
    }

    private static RegistryObject<Block> block(String name, Supplier<Block> supplier) {
        RegistryObject<Block> ro = BLOCKS.register(name, supplier);
        ITEMS.register(name, () -> new BlockItem(ro.get(), new Item.Properties()));
        return ro;
    }
}
