package com.explorationremix.world.lava;

import com.explorationremix.ExplorationRemix;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;

import javax.annotation.Nullable;

/**
 * What the lava cycle keeps per chunk of the overworld: the height its lava stands at in the chunk's blocks, and which
 * blocks of the flood band the lava put there ({@link Flood.Mask}). A Forge capability, saved inside the chunk's own
 * data, so after a crash or an unload the record can never disagree with the blocks: a chunk is never left with a dry
 * gap under the lava, or with lava the cycle will not take away.
 *
 * <p>Saved as {@code {v, base, level, mask, glow, kept}}: the format version, the lava table's Y it was written against
 * (a changed config moves the table; the record is moved with it), the height, the bits, the bits of the blocks among
 * them that held the halls' invisible glow before the lava took them, and the other blocks it took exactly
 * ({@link Flood.Kept}: a fall's flowing lava at its level, fire) as {@code {palette, keys, states}} like the change log's
 * (each left out when empty; a record saved before there was a glow mask has none). All of them are left out while the
 * chunk's lava stands at its table.
 *
 * <p>Version 2 (this build) keeps the taken blocks. A record of version 1 that still holds some of the sea took its falls'
 * flowing lava without keeping it ({@link #legacyTop}): its drain puts air there as before and wakes the lava round it the
 * old way, so those falls pour again; the record is a version 2 one once its lava is down at the table.
 *
 * <p>The chunk's {@link ChangeLog} for the terrain reset (0.5.1) is saved beside them under {@code log}, whatever the
 * lava's height (left out when empty): {@code {ybase, palette, keys, states, gens}}, the Galleries' bottom the keys
 * count from, the blocks the log holds as block state data (so a log outlives the game's block ids), and for each
 * entry its key, its first block's place in the palette and its generation.
 *
 * <p>The reset and the glow: in a slice the lava holds ({@code i < level}), put a glow back as a lava source with its
 * bits set in both {@link #mask} and {@link #glow} (the drain then restores it), never as the glow block. A glow in the
 * sea is an open gap the lava draws its faces round, and one set with block updates lets the lava flow into it, which
 * the drain then clears to air. A fall's flowing lava or fire put back there likewise comes back as a lava source with
 * its mask bit set and the block in {@link #kept}. A spot put back to rock, or to anything else the flood does not take,
 * gets all its records cleared.
 */
public final class LavaChunk {
    public static final Capability<LavaChunk> CAP = CapabilityManager.get(new CapabilityToken<>() {});
    private static final ResourceLocation ID = new ResourceLocation(ExplorationRemix.MOD_ID, "lava");
    private static final int VERSION = 2;
    /** Marks a record that was never written against any table. */
    static final int NO_BASE = Integer.MIN_VALUE;

    /** The lava stands this many blocks over the table in this chunk's blocks. */
    int level;
    /** The lava table's Y this record was written against, or {@link #NO_BASE}. */
    int base = NO_BASE;
    final Flood.Mask mask;
    /** Which of the blocks in {@link #mask} held the glow when the lava took them: the drain puts the glow back there. */
    final Flood.Mask glow;
    /** The other blocks in {@link #mask} the lava took (a fall's flowing lava, fire), exactly: the drain puts them back. */
    final Flood.Kept kept;
    /**
     * The slices under this one were flooded by a build that did not keep the taken blocks (the record was saved by it
     * with some of the sea still up): drain them the old way (see the class comment). 0 for a record of this build; it
     * comes down with the drain, as a slice drained and flooded again keeps its blocks.
     */
    int legacyTop;
    /** What the chunk's part of the Galleries looked like before it changed since the last terrain reset (0.5.1). */
    final ChangeLog log = new ChangeLog();

    LavaChunk(int rise) {
        this.mask = new Flood.Mask(rise);
        this.glow = new Flood.Mask(rise);
        this.kept = new Flood.Kept(rise);
    }

    /** The record of a chunk, or null for chunks that have none (other dimensions, the client). */
    @Nullable
    static LavaChunk of(LevelChunk chunk) {
        return chunk.getCapability(CAP).orElse(null);
    }

    /**
     * Lines the record up with the lava table at {@code tableY}: a record written against another table has its bits
     * and height moved by the difference (any that leave the band are dropped).
     */
    void align(int tableY) {
        if (base == tableY) return;
        if (base != NO_BASE) {
            int by = base - tableY;
            mask.shift(by);
            glow.shift(by);
            kept.shift(by);
            level = Math.max(0, Math.min(mask.slices(), level + by));
            legacyTop = Math.max(0, Math.min(level, legacyTop + by));
        }
        base = tableY;
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        if (level != 0 || !mask.isEmpty()) {
            // a legacy record stays one (version 1) until its lava is down at the table
            t.putInt("v", legacyTop > 0 ? 1 : VERSION);
            if (legacyTop > 0) t.putInt("legacyTop", legacyTop);
            t.putInt("base", base);
            t.putInt("level", level);
            long[] bits = mask.toArray();
            if (bits != null) t.putLongArray("mask", bits);
            long[] lit = glow.toArray();
            if (lit != null) t.putLongArray("glow", lit);
            if (!kept.isEmpty()) t.put("kept", saveKept(kept));
        }
        if (!log.isEmpty()) t.put("log", saveLog(log));
        return t;
    }

    void load(CompoundTag t) {
        level = Math.max(0, Math.min(mask.slices(), t.getInt("level")));
        base = t.contains("base", Tag.TAG_INT) ? t.getInt("base") : NO_BASE;
        mask.fromArray(t.contains("mask", Tag.TAG_LONG_ARRAY) ? t.getLongArray("mask") : null);
        glow.fromArray(t.contains("glow", Tag.TAG_LONG_ARRAY) ? t.getLongArray("glow") : null);
        if (t.contains("kept", Tag.TAG_COMPOUND)) loadKept(kept, t.getCompound("kept"));
        else kept.clear();
        legacyTop = t.getInt("v") >= VERSION || mask.isEmpty() ? 0
                : t.contains("legacyTop", Tag.TAG_INT) ? Math.max(0, Math.min(level, t.getInt("legacyTop"))) : level;
        if (t.contains("log", Tag.TAG_COMPOUND)) loadLog(log, t.getCompound("log"));
        else log.clear();
    }

    /** The kept blocks as {@code {palette, keys, states}}: block state data, the bit of each and its place in the palette. */
    static CompoundTag saveKept(Flood.Kept kept) {
        int[][] a = kept.toArrays();
        int[] ids = a[1], idx = new int[ids.length];
        Int2IntOpenHashMap index = new Int2IntOpenHashMap();
        index.defaultReturnValue(-1);
        ListTag palette = new ListTag();
        for (int k = 0; k < ids.length; k++) {
            int p = index.get(ids[k]);
            if (p < 0) {
                p = palette.size();
                index.put(ids[k], p);
                palette.add(NbtUtils.writeBlockState(Block.stateById(ids[k])));
            }
            idx[k] = p;
        }
        CompoundTag t = new CompoundTag();
        t.put("palette", palette);
        t.putIntArray("keys", a[0]);
        t.putIntArray("states", idx);
        return t;
    }

    /** Reads kept blocks saved by {@link #saveKept} back; a block no longer in the game, or out of the palette, is dropped (air comes back). */
    static void loadKept(Flood.Kept kept, CompoundTag t) {
        ListTag palette = t.getList("palette", Tag.TAG_COMPOUND);
        HolderGetter<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        int[] ids = new int[palette.size()];
        for (int k = 0; k < ids.length; k++) {
            BlockState s = NbtUtils.readBlockState(blocks, palette.getCompound(k));
            ids[k] = s.isAir() ? -1 : Block.getId(s);
        }
        int[] idx = t.getIntArray("states"), states = new int[idx.length];
        for (int k = 0; k < idx.length; k++) states[k] = idx[k] >= 0 && idx[k] < ids.length ? ids[idx[k]] : -1;
        kept.fromArrays(t.getIntArray("keys"), states);
    }

    /** The change log as {@code {ybase, palette, keys, states, gens}} (see the class comment). */
    static CompoundTag saveLog(ChangeLog log) {
        int[][] a = log.toArrays();
        int[] ids = a[1], idx = new int[ids.length];
        Int2IntOpenHashMap index = new Int2IntOpenHashMap();
        index.defaultReturnValue(-1);
        ListTag palette = new ListTag();
        for (int k = 0; k < ids.length; k++) {
            int p = index.get(ids[k]);
            if (p < 0) {
                p = palette.size();
                index.put(ids[k], p);
                palette.add(NbtUtils.writeBlockState(Block.stateById(ids[k])));
            }
            idx[k] = p;
        }
        CompoundTag t = new CompoundTag();
        t.putInt("ybase", log.base());
        t.put("palette", palette);
        t.putIntArray("keys", a[0]);
        t.putIntArray("states", idx);
        t.putIntArray("gens", a[2]);
        return t;
    }

    /** Reads a change log saved by {@link #saveLog} back into {@code log}; a block no longer in the game reads as air. */
    static void loadLog(ChangeLog log, CompoundTag t) {
        ListTag palette = t.getList("palette", Tag.TAG_COMPOUND);
        HolderGetter<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        int[] ids = new int[palette.size()];
        for (int k = 0; k < ids.length; k++) ids[k] = Block.getId(NbtUtils.readBlockState(blocks, palette.getCompound(k)));
        int[] idx = t.getIntArray("states"), states = new int[idx.length];
        // a palette place out of range comes back as -1, which the log skips
        for (int k = 0; k < idx.length; k++) states[k] = idx[k] >= 0 && idx[k] < ids.length ? ids[idx[k]] : -1;
        log.load(t.contains("ybase", Tag.TAG_INT) ? t.getInt("ybase") : ChangeLog.NO_BASE, t.getIntArray("keys"), states, t.getIntArray("gens"));
    }

    // ============================================================================================== registration

    /** Mod bus: makes the capability known. */
    public static void register(RegisterCapabilitiesEvent event) { event.register(LavaChunk.class); }

    /** Forge bus: gives every chunk of the overworld on the server its record. */
    static void attach(AttachCapabilitiesEvent<LevelChunk> event) {
        LevelChunk chunk = event.getObject();
        Level level = chunk.getLevel();
        if (level == null || level.isClientSide() || level.dimension() != Level.OVERWORLD) return;
        Provider p = new Provider(new LavaChunk(LavaFlood.RISE));
        event.addCapability(ID, p);
        event.addListener(p.opt::invalidate);
    }

    private static final class Provider implements ICapabilitySerializable<CompoundTag> {
        final LavaChunk data;
        final LazyOptional<LavaChunk> opt;

        Provider(LavaChunk data) {
            this.data = data;
            this.opt = LazyOptional.of(() -> data);
        }

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) { return CAP.orEmpty(cap, opt); }

        @Override
        public CompoundTag serializeNBT() { return data.save(); }

        @Override
        public void deserializeNBT(CompoundTag tag) { data.load(tag); }
    }
}
