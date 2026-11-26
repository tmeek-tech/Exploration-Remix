package com.explorationremix.rune.stone;

import com.explorationremix.registry.ERSounds;
import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What happens when a rune stone breaks (0.23.1; Taran, 2026-11-26: "Also give them some magic effects that make them
 * satisfying to break"). While it is dug its carving brightens and light leaks out of it, with a hum that climbs as the
 * pick works (each client draws that itself, client.rune.stone.RuneStoneClient). As it goes, a burst: a flash of light,
 * a ring, glinting shards and stone chips thrown out, and a bright crack and chime ({@link #burst}). Then its runes rise
 * out of the hole and fly into the pack ({@link FreedRune}), and every other rune stone within {@link #ECHO} blocks
 * answers: a spark of light shoots to it through the rock and its carving flares, with a far chime from where it lies,
 * so a stronghold or a city with more of them gives them away to whoever breaks the first ({@link #echo}).
 */
public final class RuneStones {
    /** How far the other stones answer a broken one (Taran's card pick: those within 24 blocks flare). */
    public static final int ECHO = 24;
    /** At most this many answer. */
    static final int ECHO_MAX = 8;
    /** The pace the answering spark travels at, blocks a tick. */
    static final double ECHO_PACE = 1.4;

    /** For /remix runestones: stones broken and runes they gave since the game started. */
    public static final AtomicLong BROKEN = new AtomicLong(), FREED = new AtomicLong();

    private RuneStones() {}

    /**
     * The stone at pos is going, however it goes (a player in creative too, or a blast): its burst and its crack, seen
     * and heard round it, and the stones round it answer; {@code by} the player breaking it.
     */
    public static void burst(ServerLevel level, BlockPos pos, BlockState state, @Nullable Player by) {
        Vec3 c = Vec3.atCenterOf(pos);
        Vec3 toward = by == null ? Vec3.ZERO : by.getEyePosition().subtract(c);
        RuneStoneHost host = state.getBlock() instanceof RuneStoneBlock b ? b.host(state) : null;
        RuneFx.stoneBurst(level, c, toward, host == null ? 0 : Block.getId(host.state()));
        level.playSound(null, pos, ERSounds.RUNE_STONE_BREAK.get(), SoundSource.BLOCKS, 1.0F, 0.95F + level.random.nextFloat() * 0.1F);
        BROKEN.incrementAndGet();
        echo(level, pos);
    }

    /**
     * Broken by {@code player} with {@code tool} in survival: its loot (one rune, a second now and then) rises out of the
     * hole as freed runes that fly to them.
     */
    public static void free(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player, ItemStack tool) {
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack s : Block.getDrops(state, level, pos, null, player, tool)) if (!s.isEmpty()) drops.add(s);
        Vec3 c = Vec3.atCenterOf(pos);
        for (int i = 0; i < drops.size(); i++) FreedRune.spawn(level, c, player, drops.get(i), i, drops.size(), pos.asLong());
    }

    /** Every other rune stone within ECHO blocks of {@code from}, nearest first (at most ECHO_MAX), answers. */
    static void echo(ServerLevel level, BlockPos from) {
        Vec3 c = Vec3.atCenterOf(from);
        for (BlockPos p : near(level, from, ECHO, ECHO_MAX)) {
            Vec3 at = Vec3.atCenterOf(p);
            Vec3 way = at.subtract(c);
            int delay = Math.max(2, (int) Math.round(way.length() / ECHO_PACE));
            RuneFx.stoneFlare(level, at, way, delay);
        }
    }

    /**
     * The rune stones within {@code reach} blocks of {@code from} (a cube; then nearest first, at most {@code max}),
     * among loaded chunks only. Sections whose palette holds no rune stone are skipped without a look at their blocks.
     */
    public static List<BlockPos> near(ServerLevel level, BlockPos from, int reach, int max) {
        List<BlockPos> out = new ArrayList<>();
        int x0 = from.getX() - reach, x1 = from.getX() + reach, y0 = Math.max(level.getMinBuildHeight(), from.getY() - reach),
                y1 = Math.min(level.getMaxBuildHeight() - 1, from.getY() + reach), z0 = from.getZ() - reach, z1 = from.getZ() + reach;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (int sy = SectionPos.blockToSectionCoord(y0); sy <= SectionPos.blockToSectionCoord(y1); sy++) {
                    int si = chunk.getSectionIndexFromSectionY(sy);
                    if (si < 0 || si >= chunk.getSectionsCount()) continue;
                    LevelChunkSection s = chunk.getSection(si);
                    if (s.hasOnlyAir() || !s.maybeHas(st -> st.getBlock() instanceof RuneStoneBlock)) continue;
                    int bx0 = Math.max(x0, cx << 4), bx1 = Math.min(x1, (cx << 4) + 15);
                    int bz0 = Math.max(z0, cz << 4), bz1 = Math.min(z1, (cz << 4) + 15);
                    int by0 = Math.max(y0, sy << 4), by1 = Math.min(y1, (sy << 4) + 15);
                    for (int x = bx0; x <= bx1; x++)
                        for (int y = by0; y <= by1; y++)
                            for (int z = bz0; z <= bz1; z++) {
                                if (!(s.getBlockState(x & 15, y & 15, z & 15).getBlock() instanceof RuneStoneBlock)) continue;
                                if (x == from.getX() && y == from.getY() && z == from.getZ()) continue;
                                out.add(p.set(x, y, z).immutable());
                            }
                }
            }
        out.sort(Comparator.comparingDouble(q -> q.distSqr(from)));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }
}
