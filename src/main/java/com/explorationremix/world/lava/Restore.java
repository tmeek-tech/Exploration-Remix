package com.explorationremix.world.lava;

/**
 * What the Basalt Galleries' terrain reset writes where it puts a block back: pure rules, like {@link Flood}'s, so they
 * are tested offline; {@link TerrainReset} applies them to real chunks.
 *
 * <p>The reset puts back the terrain's own block (the first one its {@link ChangeLog} holds), except under the lava sea:
 * in a slice of the flood band that the chunk's lava stands over ({@code slice < level}), an open block (air, fire,
 * flowing lava or the halls' glow, which the rising lava takes) comes back as the sea itself, a lava source with its bit
 * set in the chunk's flood mask, and with what the rising lava would have taken there remembered the way the rise
 * remembers it: its glow bit where it was a glow, the block itself in the chunk's {@link Flood.Kept} record where it was
 * a fall's flowing lava or fire. The drain later puts the air, the glow or that block back exactly. A block the flood does
 * not take (rock, a natural lava pool, anything else) comes back as itself with all its flood records cleared, so the
 * drain leaves it alone.
 */
public final class Restore {
    private Restore() {}

    /** Write the original block (and clear its flood records, if it is in the band). */
    public static final int ORIGINAL = 0;
    /** Write a lava source and set its flood mask bit (it was air: clear its glow bit and kept block). */
    public static final int SEA = 1;
    /** Write a lava source and set both its flood mask bit and its glow bit (clear its kept block). */
    public static final int SEA_GLOW = 2;
    /** Write a lava source, set its flood mask bit and keep the original block for the drain (clear its glow bit). */
    public static final int SEA_KEPT = 3;

    /**
     * What to write for an original block of flood kind {@code kind} ({@link Flood#AIR} and the rest) at {@code slice}
     * of the band (-1 outside it) in a chunk whose lava stands {@code level} blocks over the table.
     */
    public static int decide(int kind, int slice, int level) {
        if (slice >= 0 && slice < level && Flood.takes(kind)) {
            return kind == Flood.GLOW ? SEA_GLOW : kind == Flood.AIR ? SEA : SEA_KEPT;
        }
        return ORIGINAL;
    }

    /**
     * The records' side of putting one logged position back, shared by the terrain reset and the quakes' give-back (both
     * through {@code TerrainReset.putBack}, 0.9.0): decides what to write for the first block the log holds there
     * ({@link #decide}), sets or clears the position's flood records for it ({@link #mark}) and drops the entry. Returns
     * the decision: the caller writes the original block for {@link #ORIGINAL} and the sea's lava source otherwise.
     *
     * @param kind     the original block's flood kind ({@link Flood#AIR} and the rest)
     * @param original the original block's id, kept for {@link #SEA_KEPT}
     * @param slice    the position's slice of the flood band, -1 outside it (the lake's rows under the table among them)
     * @param level    how many blocks the chunk's lava stands over the table
     */
    public static int putBack(ChangeLog log, int key, int kind, int original, int slice, int level, Flood.Mask mask, Flood.Mask glow, Flood.Kept kept) {
        int d = decide(kind, slice, level);
        mark(d, mask, glow, kept, slice, ChangeLog.x(key), ChangeLog.z(key), original);
        log.remove(key);
        return d;
    }

    /** {@link #mark(int, Flood.Mask, Flood.Mask, Flood.Kept, int, int, int, int)} with no kept blocks. */
    public static void mark(int d, Flood.Mask mask, Flood.Mask glow, int i, int x, int z) { mark(d, mask, glow, null, i, x, z, -1); }

    /**
     * Sets or clears the position's flood records for decision {@code d} at slice i (nothing outside the band):
     * {@code original} is the block's id, kept for {@link #SEA_KEPT}.
     */
    public static void mark(int d, Flood.Mask mask, Flood.Mask glow, Flood.Kept kept, int i, int x, int z, int original) {
        if (i < 0 || i >= mask.slices()) return;
        switch (d) {
            case SEA -> {
                mask.set(i, x, z);
                glow.unset(i, x, z);
                if (kept != null) kept.remove(i, x, z);
            }
            case SEA_GLOW -> {
                mask.set(i, x, z);
                glow.set(i, x, z);
                if (kept != null) kept.remove(i, x, z);
            }
            case SEA_KEPT -> {
                mask.set(i, x, z);
                glow.unset(i, x, z);
                if (kept != null) {
                    if (original >= 0) kept.put(i, x, z, original);
                    else kept.remove(i, x, z);
                }
            }
            default -> {
                mask.unset(i, x, z);
                glow.unset(i, x, z);
                if (kept != null) kept.remove(i, x, z);
            }
        }
    }

    /** The kind {@link #seeThrough(int, boolean, boolean, boolean)} answers for a kept block it cannot name. */
    public static final int KEPT = -1;

    /** {@link #seeThrough(int, boolean, boolean, boolean)} with nothing kept. */
    public static int seeThrough(int kind, boolean masked, boolean glowBit) { return seeThrough(kind, masked, glowBit, false); }

    /**
     * The terrain's own block at a position the flood has filled: the log sees through the flood, so a lava source with
     * its mask bit set is recorded as what the rising lava took: the {@link Flood#GLOW} where its glow bit is set, the
     * kept block where one is kept ({@link #KEPT}: the caller reads it from the record), the {@link Flood#AIR} otherwise.
     * Returns the flood kind to record, or {@code kind} unchanged when the position is not the flood's.
     */
    public static int seeThrough(int kind, boolean masked, boolean glowBit, boolean keptBlock) {
        if (kind != Flood.LAVA || !masked) return kind;
        return glowBit ? Flood.GLOW : keptBlock ? KEPT : Flood.AIR;
    }
}
