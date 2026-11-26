package com.explorationremix.rune.stone;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The stone a rune stone hides in (0.23.1): a rune stone is drawn as this block, with a small rune carved into its faces,
 * and sounds as it does. Each is a vanilla block of the places the stones are set: a stronghold's stone bricks, an
 * ancient city's or a Galleries ruin's worked deepslate, a pond bed's deepslate, a Deep Gardens vent's blackstone. The
 * infested blocks look as their plain ones do, so a stronghold's silverfish stone counts as its stone bricks.
 *
 * <p>The names are the blockstate property's values and the models' names (tools/rune_stones.py, HOSTS).
 */
public enum RuneStoneHost implements StringRepresentable {
    STONE_BRICKS("stone_bricks", () -> Blocks.STONE_BRICKS, () -> Blocks.INFESTED_STONE_BRICKS),
    MOSSY_STONE_BRICKS("mossy_stone_bricks", () -> Blocks.MOSSY_STONE_BRICKS, () -> Blocks.INFESTED_MOSSY_STONE_BRICKS),
    CRACKED_STONE_BRICKS("cracked_stone_bricks", () -> Blocks.CRACKED_STONE_BRICKS, () -> Blocks.INFESTED_CRACKED_STONE_BRICKS),
    DEEPSLATE("deepslate", () -> Blocks.DEEPSLATE, () -> Blocks.INFESTED_DEEPSLATE),
    DEEPSLATE_BRICKS("deepslate_bricks", () -> Blocks.DEEPSLATE_BRICKS, null),
    CRACKED_DEEPSLATE_BRICKS("cracked_deepslate_bricks", () -> Blocks.CRACKED_DEEPSLATE_BRICKS, null),
    DEEPSLATE_TILES("deepslate_tiles", () -> Blocks.DEEPSLATE_TILES, null),
    CRACKED_DEEPSLATE_TILES("cracked_deepslate_tiles", () -> Blocks.CRACKED_DEEPSLATE_TILES, null),
    POLISHED_DEEPSLATE("polished_deepslate", () -> Blocks.POLISHED_DEEPSLATE, null),
    BLACKSTONE("blackstone", () -> Blocks.BLACKSTONE, null);

    private final String name;
    private final Supplier<Block> block;
    @Nullable
    private final Supplier<Block> infested;

    RuneStoneHost(String name, Supplier<Block> block, @Nullable Supplier<Block> infested) {
        this.name = name;
        this.block = block;
        this.infested = infested;
    }

    @Override
    public String getSerializedName() { return name; }

    /** The vanilla block it looks like. */
    public Block block() { return block.get(); }

    /** The vanilla block's own state: what the stone sounds like, what pick-block gives in survival. */
    public BlockState state() { return block.get().defaultBlockState(); }

    private static volatile Map<Block, RuneStoneHost> byBlock;

    /** The host a block of the world is, or null: the block itself or its infested twin. */
    @Nullable
    public static RuneStoneHost of(BlockState state) {
        Map<Block, RuneStoneHost> m = byBlock;
        if (m == null) {
            m = new IdentityHashMap<>();
            for (RuneStoneHost h : values()) {
                m.put(h.block(), h);
                if (h.infested != null) m.put(h.infested.get(), h);
            }
            byBlock = m;
        }
        return m.get(state.getBlock());
    }
}
