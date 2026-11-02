package com.explorationremix.world.lava;

/**
 * What the Basalt Galleries' terrain reset writes where it puts a block back: pure rules, like {@link Flood}'s, so they
 * are tested offline; {@link TerrainReset} applies them to real chunks.
 *
 * <p>The reset puts back the terrain's own block (the first one its {@link ChangeLog} holds), except under the lava sea:
 * in a slice of the flood band that the chunk's lava stands over ({@code slice < level}), an open block (air, fire,
 * flowing lava or the halls' glow, which the rising lava takes) comes back as the sea itself, a lava source with its bit
 * set in the chunk's flood mask, and with its glow bit too where it was a glow, so the drain later puts the air or the
 * glow back. A block the flood does not take (rock, a natural lava pool, anything else) comes back as itself with both
 * bits unset, so the drain leaves it alone.
 */
public final class Restore {
    private Restore() {}

    /** Write the original block (and unset its flood bits, if it is in the band). */
    public static final int ORIGINAL = 0;
    /** Write a lava source and set its flood mask bit (and unset its glow bit). */
    public static final int SEA = 1;
    /** Write a lava source and set both its flood mask bit and its glow bit. */
    public static final int SEA_GLOW = 2;

    /**
     * What to write for an original block of flood kind {@code kind} ({@link Flood#AIR} and the rest) at {@code slice}
     * of the band (-1 outside it) in a chunk whose lava stands {@code level} blocks over the table.
     */
    public static int decide(int kind, int slice, int level) {
        if (slice >= 0 && slice < level && Flood.takes(kind)) return kind == Flood.GLOW ? SEA_GLOW : SEA;
        return ORIGINAL;
    }

    /** Sets or unsets the position's bits in the flood's masks for decision {@code d} at slice i (nothing outside the band). */
    public static void mark(int d, Flood.Mask mask, Flood.Mask glow, int i, int x, int z) {
        if (i < 0 || i >= mask.slices()) return;
        switch (d) {
            case SEA -> {
                mask.set(i, x, z);
                glow.unset(i, x, z);
            }
            case SEA_GLOW -> {
                mask.set(i, x, z);
                glow.set(i, x, z);
            }
            default -> {
                mask.unset(i, x, z);
                glow.unset(i, x, z);
            }
        }
    }

    /**
     * The terrain's own block at a position the flood has filled: the log sees through the flood, so a lava source with
     * its mask bit set is recorded as the {@link Flood#AIR} it took, or the {@link Flood#GLOW} where its glow bit is set.
     * Returns the flood kind to record, or {@code kind} unchanged when the position is not the flood's.
     */
    public static int seeThrough(int kind, boolean masked, boolean glowBit) {
        if (kind != Flood.LAVA || !masked) return kind;
        return glowBit ? Flood.GLOW : Flood.AIR;
    }
}
