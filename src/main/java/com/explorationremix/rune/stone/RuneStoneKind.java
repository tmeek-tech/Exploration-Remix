package com.explorationremix.rune.stone;

import com.explorationremix.rune.RuneRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The four rune stones (0.23.1), one per layer, and the stones each hides in (Taran, 2026-11-26: "Put the Galleries
 * runes in the ruins, the Gardens runes in vents, the Reaches runes in ponds and ancient cities, and the Upper Caves runes
 * in strongholds"): its block id, and its hosts, the first its default. What each frees is its loot table's
 * (tools/rune_stones.py): its layer's runes but the collect runes, the Galleries' the three layers', the deepest most
 * often. The ids and host lists must match tools/rune_stones.py's STONES.
 */
public enum RuneStoneKind {
    /** In the strongholds' walls: stone bricks, mossy or cracked. */
    UPPER_CAVES("upper_caves_rune_stone", RuneStoneHost.STONE_BRICKS, RuneStoneHost.MOSSY_STONE_BRICKS, RuneStoneHost.CRACKED_STONE_BRICKS),
    /** On the Blue Reaches' pond beds (deepslate) and in the ancient cities' worked deepslate. */
    BLUE_REACHES("blue_reaches_rune_stone", RuneStoneHost.DEEPSLATE, RuneStoneHost.DEEPSLATE_BRICKS, RuneStoneHost.CRACKED_DEEPSLATE_BRICKS,
            RuneStoneHost.DEEPSLATE_TILES, RuneStoneHost.CRACKED_DEEPSLATE_TILES, RuneStoneHost.POLISHED_DEEPSLATE),
    /** In the walls of the Deep Gardens' vent chambers: blackstone, or the deepslate a chamber is cut into. */
    DEEP_GARDENS("deep_gardens_rune_stone", RuneStoneHost.BLACKSTONE, RuneStoneHost.DEEPSLATE),
    /** In the lower Galleries' ruins, their worked deepslate. */
    GALLERIES("galleries_rune_stone", RuneStoneHost.DEEPSLATE_BRICKS, RuneStoneHost.CRACKED_DEEPSLATE_BRICKS, RuneStoneHost.DEEPSLATE_TILES,
            RuneStoneHost.CRACKED_DEEPSLATE_TILES, RuneStoneHost.POLISHED_DEEPSLATE);

    public final String id;
    /** The blockstate property: which of its hosts this stone is. */
    public final EnumProperty<RuneStoneHost> host;
    public final List<RuneStoneHost> hosts;

    RuneStoneKind(String id, RuneStoneHost... hosts) {
        this.id = id;
        this.hosts = List.of(hosts);
        this.host = EnumProperty.create("host", RuneStoneHost.class, hosts);
    }

    public boolean takes(@Nullable RuneStoneHost h) { return h != null && hosts.contains(h); }

    public Block block() { return RuneRegistry.stone(this); }

    /** This stone hiding in host h (which must be one of its hosts). */
    public BlockState state(RuneStoneHost h) { return block().defaultBlockState().setValue(host, h); }

    /** This stone in place of the block of the world {@code was}, if it is one of its hosts; else null. */
    @Nullable
    public BlockState replacing(BlockState was) {
        RuneStoneHost h = RuneStoneHost.of(was);
        return takes(h) ? state(h) : null;
    }
}
