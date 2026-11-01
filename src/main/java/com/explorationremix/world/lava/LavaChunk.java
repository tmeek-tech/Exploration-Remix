package com.explorationremix.world.lava;

import com.explorationremix.ExplorationRemix;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
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
 * <p>Saved as {@code {v, base, level, mask, glow}}: the format version, the lava table's Y it was written against (a
 * changed config moves the table; the record is moved with it), the height, the bits, and the bits of the blocks among
 * them that held the halls' invisible glow before the lava took them (both left out when empty; a record saved before
 * there was a glow mask has none). The next build keeps its change log for the terrain reset here too, under new keys.
 *
 * <p>The reset and the glow: in a slice the lava holds ({@code i < level}), put a glow back as a lava source with its
 * bits set in both {@link #mask} and {@link #glow} (the drain then restores it), never as the glow block. A glow in the
 * sea is an open gap the lava draws its faces round, and one set with block updates lets the lava flow into it, which
 * the drain then clears to air. A spot put back to rock, or to anything else the flood does not take, gets both bits
 * unset.
 */
public final class LavaChunk {
    public static final Capability<LavaChunk> CAP = CapabilityManager.get(new CapabilityToken<>() {});
    private static final ResourceLocation ID = new ResourceLocation(ExplorationRemix.MOD_ID, "lava");
    private static final int VERSION = 1;
    /** Marks a record that was never written against any table. */
    static final int NO_BASE = Integer.MIN_VALUE;

    /** The lava stands this many blocks over the table in this chunk's blocks. */
    int level;
    /** The lava table's Y this record was written against, or {@link #NO_BASE}. */
    int base = NO_BASE;
    final Flood.Mask mask;
    /** Which of the blocks in {@link #mask} held the glow when the lava took them: the drain puts the glow back there. */
    final Flood.Mask glow;

    LavaChunk(int rise) {
        this.mask = new Flood.Mask(rise);
        this.glow = new Flood.Mask(rise);
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
            level = Math.max(0, Math.min(mask.slices(), level + by));
        }
        base = tableY;
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        if (level == 0 && mask.isEmpty()) return t;
        t.putInt("v", VERSION);
        t.putInt("base", base);
        t.putInt("level", level);
        long[] bits = mask.toArray();
        if (bits != null) t.putLongArray("mask", bits);
        long[] lit = glow.toArray();
        if (lit != null) t.putLongArray("glow", lit);
        return t;
    }

    void load(CompoundTag t) {
        level = Math.max(0, Math.min(mask.slices(), t.getInt("level")));
        base = t.contains("base", Tag.TAG_INT) ? t.getInt("base") : NO_BASE;
        mask.fromArray(t.contains("mask", Tag.TAG_LONG_ARRAY) ? t.getLongArray("mask") : null);
        glow.fromArray(t.contains("glow", Tag.TAG_LONG_ARRAY) ? t.getLongArray("glow") : null);
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
